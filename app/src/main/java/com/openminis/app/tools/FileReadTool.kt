package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.File

object FileReadTool {
    const val NAME = "file_read"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read a file from the Linux filesystem. Faster than shell_execute for reading files — no shell overhead. Returns file content with metadata. Rejects binary files.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Read Python script contents', 'Check system configuration file'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute Linux path to read (e.g. /var/minis/workspace/data.csv)"),
            "offset" to AgentToolParam("integer", "1-based line number to start reading from (default: 1). Ignored when direction is 'tail'. If a previous read was truncated, its header ends with next_offset=N — pass that as offset to continue from where it stopped."),
            "lines" to AgentToolParam("integer", "Maximum number of lines to return (default: all lines up to max_length)"),
            "max_length" to AgentToolParam("integer", "Maximum character length of returned content (default: 15000)"),
            "direction" to AgentToolParam("string", "Read direction: 'head' (from start, default) or 'tail' (from end of file)"),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path", "offset", "lines", "direction", "max_length"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            val toolTitle = args.optString("tool_title", NAME)
            val offset = args.optInt("offset", 1).coerceAtLeast(1)
            val MAX_LENGTH_HARD_CAP = 80_000
            val maxLength = args.optInt("max_length", 15000).coerceAtMost(MAX_LENGTH_HARD_CAP)
            val direction = args.optString("direction", "head")

            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            val file = PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)

            if (!file.exists()) {
                return ToolExecutionResult("Error: File not found: $path", false, toolTitle = toolTitle)
            }

            if (file.isDirectory) {
                return ToolExecutionResult("Error: Path is a directory: $path", false, toolTitle = toolTitle)
            }

            val requestedLines = if (args.has("lines")) args.optInt("lines") else null
            if (requestedLines != null && requestedLines < 0) {
                return ToolExecutionResult("Error: 'lines' must not be negative", false, toolTitle = toolTitle)
            }

            try {
                FileToolIO.run("Reading $path") {
                    read(file, path, offset, requestedLines, maxLength, direction, toolTitle)
                }
            } catch (e: FileToolIO.TimedOut) {
                ToolExecutionResult("Error: ${e.message}", false, toolTitle = toolTitle, timedOut = true)
            } catch (e: FileToolIO.OutOfMemory) {
                ToolExecutionResult(
                    "Error: ${e.message} Read a smaller range with offset/lines or direction='tail', " +
                        "or use the shell (head / tail / grep).",
                    false, toolTitle = toolTitle,
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult("Error reading file: ${e.message}", false)
        }
    }

    internal fun read(
        file: java.io.File,
        path: String,
        offset: Int,
        requestedLines: Int?,
        maxLength: Int,
        direction: String,
        toolTitle: String,
    ): ToolExecutionResult {
        val size = file.length()

        val isBinary = file.inputStream().use { input ->
            val buf = ByteArray(minOf(8192L, size).toInt())
            val read = input.read(buf)
            if (read > 0) buf.take(read).any { it == 0.toByte() } else false
        }

        if (isBinary) {
            return ToolExecutionResult(
                "[$path | $size bytes | binary file — cannot display contents]",
                true, toolTitle = toolTitle
            )
        }

        val window = FileToolIO.scanLines(
            file, tail = direction == "tail", offset = offset,
            lines = requestedLines, maxLength = maxLength,
        )
        val totalLines = window.totalLines
        val selectedCount = window.selectedCount

        val showStart = if (direction == "tail") {
            (totalLines - selectedCount) + 1
        } else {
            offset
        }
        val showEnd = showStart + selectedCount - 1

        var content = window.content

        var effectiveStart = showStart
        var effectiveEnd = showEnd
        var nextOffset: Int? = null
        var wasTruncated = false
        if (content.length > maxLength) {
            wasTruncated = true
            if (direction == "tail") {
                content = content.takeLast(maxLength)
                val firstNewline = content.indexOf('\n')
                if (firstNewline in 0 until content.length - 1) {
                    content = content.substring(firstNewline + 1)
                }
                effectiveStart = effectiveEnd - content.count { it == '\n' }
            } else {
                content = content.take(maxLength)
                val lastNewline = content.lastIndexOf('\n')
                if (lastNewline > 0) content = content.substring(0, lastNewline)
                effectiveEnd = showStart + content.count { it == '\n' }
                if (effectiveEnd < totalLines) nextOffset = effectiveEnd + 1
            }
        }

        var header = "[$path | $size bytes | $totalLines lines | " +
            "showing $effectiveStart-$effectiveEnd of $totalLines"
        if (wasTruncated) {
            header += " | truncated at $maxLength chars"
            header += if (nextOffset != null) ", next_offset=$nextOffset"
                      else ", retry with a smaller lines value"
        }
        header += "]"
        return ToolExecutionResult("$header\n$content", true, toolTitle = toolTitle)
    }
}
