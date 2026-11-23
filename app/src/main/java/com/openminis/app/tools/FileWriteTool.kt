package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.ContextOffload
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject

object FileWriteTool {
    const val NAME = "file_write"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Write content to a file on the Linux filesystem. Faster than shell_execute for writing files. Creates the file if it doesn't exist. Use append mode to add to existing files.\n\nIMPORTANT for large files (roughly >8KB): do NOT emit it as one call. Every byte of `content` is generated and streamed as tool arguments, so one huge call is slow and frequently dies mid-request. Instead either (a) write the first chunk, then extend it with further calls using append: true, or (b) when the content is repetitive or computable (SVG charts, generated tables, boilerplate), write a short script and run it with shell_execute — generating 2KB of code that emits 100KB beats transcribing 100KB.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Create Python statistics script', 'Write configuration file'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute Linux path to write (e.g. /root/test.txt)"),
            "content" to AgentToolParam("string", "The text content to write to the file. For large content prefer several appending calls over one huge one — see the tool description."),
            "append" to AgentToolParam("boolean", "If true, append to existing file instead of overwriting (default: false)"),
            "create_dirs" to AgentToolParam("boolean", "If true, create parent directories if they don't exist (default: false)"),
        ),
        required = listOf("tool_title", "path", "content"),
        propertyOrdering = listOf("tool_title", "path", "content", "append", "create_dirs"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            val content = args.optString("content", "")
            val append = args.optBoolean("append", false)
            val createDirs = args.optBoolean("create_dirs", false)
            val toolTitle = args.optString("tool_title", NAME)

            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            if (ContextOffload.isOffloadPlaceholder(content)) {
                AppLogger.warning(
                    "FileWrite",
                    "REFUSED offload-placeholder write path=$path bytes=${content.length}",
                )
                return ToolExecutionResult(
                    ContextOffload.placeholderWriteRefusal("content", path),
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

            try {
                content.toByteArray(Charsets.UTF_8)
            } catch (e: Exception) {
                return ToolExecutionResult("Error: Content is not valid UTF-8", false, toolTitle = toolTitle)
            }

            try {
                FileToolIO.run("Writing $path", writes = true) {
                    val parent = file.parentFile
                    if (parent != null && (createDirs || !parent.exists())) {
                        parent.mkdirs()
                    }

                    if (append) {
                        file.appendText(content)
                    } else {
                        file.writeText(content)
                    }
                }
            } catch (e: FileToolIO.TimedOut) {
                return ToolExecutionResult("Error: ${e.message}", false, toolTitle = toolTitle, timedOut = true)
            }

            val bytes = file.length()
            if (path.startsWith("/var/minis/mounts/")) {
                val landed = file.exists() && file.length() == bytes
                com.openminis.app.logging.AppLogger.info(
                    "FileWrite",
                    "mount write path=$path host=${file.absolutePath} bytes=$bytes " +
                        "exists=${file.exists()} landedOk=$landed",
                )
                if (!landed) {
                    com.openminis.app.logging.AppLogger.warning(
                        "FileWrite",
                        "mount write to $path reported success but did NOT persist to " +
                            "${file.absolutePath} — likely missing WRITE_EXTERNAL_STORAGE / " +
                            "shadowed FUSE view on this device",
                    )
                }
            }
            ToolExecutionResult("Wrote to $path ($bytes bytes)", true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error writing file: ${e.message}", false)
        }
    }
}
