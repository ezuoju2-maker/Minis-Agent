package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.ContextOffload
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject

object FileEditTool {
    const val NAME = "file_edit"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Make targeted edits to an existing file using exact string replacement. ALWAYS use file_read first to see the current file contents before editing. Prefer file_edit over file_write when modifying existing files — only the changed part needs to be specified. The old_string must match exactly one location in the file (including whitespace/indentation), unless replace_all is true.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Fix typo in Python script', 'Update config value'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute Linux path to the file to edit (e.g. /root/script.py)"),
            "old_string" to AgentToolParam("string", "The exact text to find in the file. Must match precisely including whitespace and indentation. Must be unique in the file unless replace_all is true."),
            "new_string" to AgentToolParam("string", "The replacement text. Use empty string to delete old_string."),
            "replace_all" to AgentToolParam("boolean", "If true, replace ALL occurrences of old_string (default: false)"),
            "hunks" to AgentToolParam("string", "Optional partial apply, as a JSON array of hunk indices such as [0,2].  After computing the edit, the change is split into numbered hunks; list the hunk indices to keep (0-based, as shown in the diff card). Omitted hunks stay as they were. Only works when the whole diff is intact (not truncated). Use it when the user accepts only part of a large edit."),
        ),
        required = listOf("tool_title", "path", "old_string", "new_string"),
        propertyOrdering = listOf("tool_title", "path", "old_string", "new_string", "replace_all", "hunks"),
    )

    private fun hunkIndices(args: JSONObject): Set<Int>? {
        val arr = args.optJSONArray("hunks") ?: args.optString("hunks", "").trim().let { raw ->
            if (raw.isEmpty()) return null
            runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return null
        }
        val out = LinkedHashSet<Int>()
        for (i in 0 until arr.length()) {
            val v = arr.optInt(i, -1)
            if (v >= 0) out.add(v)
        }
        return out
    }

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            val oldString = args.optString("old_string", "")
            val newString = args.optString("new_string", "")
            val replaceAll = args.optBoolean("replace_all", false)
            val toolTitle = args.optString("tool_title", NAME)

            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }
            if (oldString.isEmpty()) {
                return ToolExecutionResult("Error: 'old_string' is required and cannot be empty", false, toolTitle = toolTitle)
            }

            if (ContextOffload.isOffloadPlaceholder(newString)) {
                AppLogger.warning(
                    "FileEdit",
                    "REFUSED offload-placeholder edit path=$path bytes=${newString.length}",
                )
                return ToolExecutionResult(
                    ContextOffload.placeholderWriteRefusal("new_string", path),
                    false, toolTitle = toolTitle,
                )
            }

            if (PRootKernel.isLinuxPathUnderReadOnlyMount(path)) {
                return ToolExecutionResult(
                    "Error: $path is inside a read-only mounted folder and cannot be modified. " +
                        "Toggle writability in Settings → Mount External Folders if this is a mistake.",
                    false, toolTitle = toolTitle,
                )
            }

            val file = PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)

            if (!file.exists()) {
                return ToolExecutionResult("Error: File not found: $path", false, toolTitle = toolTitle)
            }

            try {
                FileToolIO.run("Editing $path", writes = true) {
                    edit(file, path, oldString, newString, replaceAll, toolTitle, includeDiff = true, hunks = hunkIndices(args))
                }
            } catch (e: FileToolIO.TimedOut) {
                ToolExecutionResult("Error: ${e.message}", false, toolTitle = toolTitle, timedOut = true)
            } catch (e: FileToolIO.OutOfMemory) {
                ToolExecutionResult(
                    "Error: ${e.message} Use the shell (e.g. sed or a script) for large files.",
                    false, toolTitle = toolTitle,
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult("Error editing file: ${e.message}", false)
        }
    }

    internal fun edit(
        file: java.io.File,
        path: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
        toolTitle: String,
        includeDiff: Boolean = false,
        hunks: Set<Int>? = null,
    ): ToolExecutionResult {
        val size = file.length()
        if (size > FileToolIO.MAX_EDIT_BYTES) {
            return ToolExecutionResult(
                "Error: $path is $size bytes; file_edit handles files up to ${FileToolIO.MAX_EDIT_BYTES} bytes. " +
                    "Use the shell (e.g. sed or a script) for larger files.",
                false, toolTitle = toolTitle,
            )
        }

        val content = file.readText()

        var count = 0
        var searchFrom = 0
        while (true) {
            val idx = content.indexOf(oldString, searchFrom)
            if (idx < 0) break
            count++
            searchFrom = idx + oldString.length
        }

        if (count == 0) {
            return ToolExecutionResult("Error: old_string not found in $path", false, toolTitle = toolTitle)
        }

        if (count > 1 && !replaceAll) {
            return ToolExecutionResult(
                "Error: old_string found $count times in $path. Use replace_all=true to replace all occurrences, " +
                    "or provide a more specific old_string that matches exactly once.",
                false, toolTitle = toolTitle
            )
        }

        var newContent = if (replaceAll) {
            content.replace(oldString, newString)
        } else {
            content.replaceFirst(oldString, newString)
        }
        val wanted = hunks
        var hunkNote = ""
        if (wanted != null) {
            val plan = com.openminis.app.tools.patch.PatchSplitter.plan(content, newContent)
            if (!plan.complete) {
                return ToolExecutionResult(
                    "Error: this edit is too large to split into hunks (the diff was truncated). " +
                        "Apply the whole edit (omit `hunks`) or narrow old_string.",
                    false, toolTitle = toolTitle,
                )
            }
            val unknown = wanted - plan.ids().toSet()
            if (unknown.isNotEmpty()) {
                return ToolExecutionResult(
                    "Error: unknown hunk index/indices $unknown; this edit has hunks ${plan.ids()}.",
                    false, toolTitle = toolTitle,
                )
            }
            newContent = plan.apply(wanted)
            hunkNote = " (partial: kept hunks ${wanted.sorted()})"
        }

        file.writeText(newContent)
        val replacements = if (replaceAll) count else 1
        val base = "Edited $path ($replacements replacement(s), ${newContent.length} bytes)$hunkNote"
        val output = if (includeDiff) {
            val preview = ChangePreview.render(content, newContent, maxChars = 700)
            if (preview.isBlank()) base else "$base\nChanges:\n$preview"
        } else base
        return ToolExecutionResult(output, true, toolTitle = toolTitle)
    }
}
