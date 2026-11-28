package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.ContextOffload
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.File

object ApplyPatchTool {
    const val NAME = "apply_patch"

    private const val MAX_FILE_BYTES = 2_000_000L
    private const val MAX_TOTAL_BYTES = 8_000_000L
    private const val TIMEOUT_MS = 60_000L

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Apply a multi-file patch atomically, in the OpenAI Codex / Cline format. Prefer this over " +
            "several file_edit calls when one logical change touches several places or files — it is one " +
            "round-trip, it validates every hunk before writing, and it rolls back completely if any file " +
            "fails.\n\n" +
            "Format (paths relative to `base`, or absolute Linux paths):\n" +
            "*** Begin Patch\n" +
            "*** Update File: src/A.kt\n" +
            "@@ optional anchor text (moves the search here)\n" +
            " context line\n" +
            "-old line\n" +
            "+new line\n" +
            "*** Add File: src/B.kt\n" +
            "+every line of a new file\n" +
            "*** Delete File: src/C.kt\n" +
            "*** Move to: src/D.kt\n" +
            "*** End Patch\n\n" +
            "Rules: every hunk line starts with ' ' (context), '-' (remove) or '+' (add); context must match the " +
            "file's CURRENT content — read it first. Whitespace slips are tolerated (trailing spaces, then " +
            "indentation), but an ambiguous fuzzy match is refused rather than guessed. Include 2-3 context lines " +
            "around each change; bare removal with no context can be ambiguous in repetitive files.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user (e.g. 'Refactor config loader across 4 files'). Use the same language as the user."),
            "patch" to AgentToolParam("string", "The complete patch text, starting with `*** Begin Patch` and ending with `*** End Patch`."),
            "base" to AgentToolParam("string", "Directory that relative patch paths are resolved against (default /var/minis/workspace)."),
            "dry_run" to AgentToolParam("boolean", "If true, validate and report the diff but change nothing (default: false). Use it when unsure a hunk will match."),
        ),
        required = listOf("tool_title", "patch"),
        propertyOrdering = listOf("tool_title", "patch", "base", "dry_run"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val toolTitle = try {
            JSONObject(argsJson).optString("tool_title", NAME).ifBlank { NAME }
        } catch (_: Exception) { NAME }

        return try {
            val args = JSONObject(argsJson)
            val patchText = args.optString("patch", "")
            if (patchText.isBlank()) {
                return ToolExecutionResult("Error: 'patch' is required", false, toolTitle = toolTitle)
            }
            if (com.openminis.app.agent.guard.SandboxMode.get(context) ==
                com.openminis.app.agent.guard.SandboxMode.READ_ONLY
            ) {
                return ToolExecutionResult(
                    "Refused: the session sandbox mode is read-only, so patches are not applied.",
                    false, toolTitle = toolTitle,
                )
            }
            val base = args.optString("base", "/var/minis/workspace").ifBlank { "/var/minis/workspace" }
            val dryRun = args.optBoolean("dry_run", false)

            val parsed = ApplyPatch.parse(patchText)
            parsed.error?.let {
                return ToolExecutionResult("Error: patch rejected — $it", false, toolTitle = toolTitle)
            }

            data class Target(val rel: String, val file: File, val content: String?, val op: ApplyPatch.Op)

            val targets = ArrayList<Target>()
            for (op in parsed.ops) {
                val linuxPath = if (op.path.startsWith("/")) op.path else "$base/${op.path}"
                val file = PRootKernel.resolveSessionHostPath(sessionId, linuxPath, context)
                    ?: return ToolExecutionResult(
                        "Error: cannot resolve path '${op.path}' (from '${op.path}'). Use a path under /var/minis/.",
                        false, toolTitle = toolTitle,
                    )
                if (PRootKernel.isLinuxPathUnderReadOnlyMount(linuxPath)) {
                    return ToolExecutionResult(
                        "Error: '${op.path}' is inside a read-only mounted folder; patch aborted, nothing written.",
                        false, toolTitle = toolTitle,
                    )
                }
                val access = com.openminis.app.agent.guard.PathAccessPolicy.evaluate(linuxPath)
                    if (access.blocked) {
                        runCatching {
                            com.openminis.app.observability.TraceLog.record(
                                kind = "guard",
                                name = NAME,
                                durationMs = 0,
                                ok = false,
                                summary = "denied patch on $linuxPath (zone=${access.zone.label})",
                            )
                        }
                        return ToolExecutionResult(access.reason, false, toolTitle = toolTitle)
                    }
                val exists = file.exists() && file.isFile
                if (exists && file.length() > MAX_FILE_BYTES) {
                    return ToolExecutionResult(
                        "Error: '${op.path}' is ${file.length()} bytes — larger than the $MAX_FILE_BYTES-byte patch limit. " +
                            "Split the change or use file_edit.",
                        false, toolTitle = toolTitle,
                    )
                }
                val content = if (exists) file.readText() else null
                targets.add(Target(op.path, file, content, op))
            }

            val contents: Map<String, String?> = targets.associate { it.rel to it.content }
            val changes = ApplyPatch.apply(parsed.ops, contents)
            val totalBytes = changes.sumOf { it.after.length.toLong() }
            if (totalBytes > MAX_TOTAL_BYTES) {
                return ToolExecutionResult(
                    "Error: patch would write ${totalBytes / 1024} KB — over the ${MAX_TOTAL_BYTES / 1024} KB-per-call limit. " +
                        "Split into smaller patches.",
                    false, toolTitle = toolTitle,
                )
            }
            changes.firstOrNull { ContextOffload.isOffloadPlaceholder(it.after) }?.let {
                return ToolExecutionResult(
                    ContextOffload.placeholderWriteRefusal("patch", it.path),
                    false, toolTitle = toolTitle,
                )
            }

            val summary = buildString {
                append(if (dryRun) "Dry run — validated, nothing written.\n" else "Patch applied atomically.\n")
                for (c in changes) {
                    when {
                        c.after.isEmpty() && c.before.isNotEmpty() && c.path in contents.keys ->
                            append("- deleted ${c.path} (${c.before.lineCount()} lines)\n")
                        c.before.isEmpty() ->
                            append("+ created ${c.path} (${c.after.lineCount()} lines)\n")
                        else ->
                            append("~ ${c.path}: +${c.added} -${c.removed}\n")
                    }
                }
            }.trimEnd()

            if (dryRun) return ToolExecutionResult(summary, true, toolTitle = toolTitle)

            val created = ArrayList<File>()
            val written = LinkedHashMap<File, String?>()
            try {
                FileToolIO.run("Applying patch", writes = true, timeoutMs = TIMEOUT_MS) {
                    for (c in changes) {
                        val linuxPath = if (c.path.startsWith("/")) c.path else "$base/${c.path}"
                        val host = PRootKernel.resolveSessionHostPath(sessionId, linuxPath, context)
                            ?: throw IOException("cannot resolve ${c.path}")
                        val existed = host.exists()
                        if (!existed) created.add(host)
                        written.putIfAbsent(host, if (existed) host.readText() else null)
                        if (c.after.isEmpty() && existed) {
                            host.delete()
                        } else {
                            host.parentFile?.mkdirs()
                            host.writeText(c.after)
                        }
                    }
                }
            } catch (e: Exception) {
                for ((f, old) in written) {
                    runCatching {
                        if (old == null) f.delete() else { f.parentFile?.mkdirs(); f.writeText(old) }
                    }
                }
                created.forEach { runCatching { if (it.exists()) it.delete() } }
                AppLogger.warning("ApplyPatch", "patch failed, rolled back: ${e.message}")
                return ToolExecutionResult(
                    "Error: patch failed and was ROLLED BACK — no file was changed. Reason: ${e.message ?: "unknown"}. " +
                        "Re-read the affected files and regenerate the patch.",
                    false, toolTitle = toolTitle,
                )
            }

            ToolExecutionResult(summary, true, toolTitle = toolTitle)
        } catch (e: ApplyPatch.ApplyError) {
            ToolExecutionResult("Error: patch rejected — ${e.message}", false, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error: apply_patch failed: ${e.message}", false, toolTitle = toolTitle)
        }
    }

    private class IOException(message: String) : Exception(message)

    private fun String.lineCount(): Int = if (isEmpty()) 0 else split('\n').size
}
