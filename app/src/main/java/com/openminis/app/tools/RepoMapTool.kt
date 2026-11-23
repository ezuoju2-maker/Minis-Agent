package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.File

object RepoMapTool {
    const val NAME = "repo_map"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Outline a whole source tree cheaply before reading files: file paths ranked by structural " +
            "importance, with each file's class/function declarations and line numbers. Use it as the FIRST step " +
            "on an unfamiliar or large project (repo, workspace, mounted folder), then file_read only the " +
            "files/sections the map points at — this replaces blind `find`/`ls` + full-file reads and saves " +
            "thousands of tokens. Read-only. It shows declarations only, never file bodies, so it does not " +
            "replace reading a file before editing it. If the tree is large, narrow with `path` or `focus` " +
            "instead of raising the budget.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user (e.g. 'Map Android app modules'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Directory to map (absolute Linux path). Defaults to /var/minis/workspace. `/var/minis/mounts/<name>/` works for user-mounted folders."),
            "focus" to AgentToolParam("string", "Optional keywords (space-separated) to bias ranking toward matching paths or declaration names, e.g. 'auth token refresh'."),
            "max_tokens" to AgentToolParam("integer", "Output budget in tokens (default 4500, max 20000). Characters are capped at ~3.5× this."),
            "max_files" to AgentToolParam("integer", "Maximum files to inspect (default 1500, max 3000)."),
            "exclude" to AgentToolParam("string", "Optional comma-separated relative prefixes to skip, e.g. 'legacy,third_party'."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "path", "focus", "max_tokens", "max_files", "exclude"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val toolTitle = try {
            JSONObject(argsJson).optString("tool_title", NAME).ifBlank { NAME }
        } catch (_: Exception) { NAME }
        return try {
            val args = JSONObject(argsJson)
            val rawPath = args.optString("path", "").ifBlank { "/var/minis/workspace" }
            val maxTokens = args.optInt("max_tokens", 4500).coerceIn(500, 20_000)
            val maxFiles = args.optInt("max_files", 1500).coerceIn(50, 3000)
            val focus = args.optString("focus", "")
                .split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
            val exclude = args.optString("exclude", "")
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }

            val dir = resolveDirectory(rawPath, sessionId, context)
                ?: return ToolExecutionResult(
                    "Error: cannot resolve directory: $rawPath. Use an absolute Linux path under /var/minis/.",
                    false, toolTitle = toolTitle,
                )
            if (!dir.exists()) {
                return ToolExecutionResult("Error: directory not found: $rawPath", false, toolTitle = toolTitle)
            }

            val options = RepoMapBuilder.Options(
                maxChars = (maxTokens * 3.5).toInt(),
                maxFiles = maxFiles,
                focus = focus,
                exclude = exclude,
            )
            val map = RepoMapBuilder.build(dir, options)
            ToolExecutionResult(map, !map.startsWith("repo_map:"), toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error building repo map: ${e.message}", false, toolTitle = toolTitle)
        }
    }

    private fun resolveDirectory(path: String, sessionId: String, context: Context): File? {
        PRootKernel.resolveSessionHostPath(sessionId, path, context)?.let { resolved ->
            if (resolved.isDirectory) return resolved
        }
        val direct = File(path)
        return if (direct.isDirectory && !path.startsWith("/var/minis")) direct else null
    }
}
