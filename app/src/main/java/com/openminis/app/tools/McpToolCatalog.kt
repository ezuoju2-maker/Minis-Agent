package com.openminis.app.tools

import org.json.JSONObject

/**
 * [plugin-ecosystem / MCP] Pure logic for consuming `minis-mcp-cli tools`
 * output. MCP servers are the app's plugin mechanism: a user installs a server
 * and its tools become callable. This catalog:
 *
 *  - parses both the compact CLI envelope
 *    `{"ok":true,"result":{"server":"x","tools":[...],"count":n}}`
 *    and a bare `{"tools":[...]}` / `[...]` array;
 *  - detects tool-name collisions ACROSS servers (two servers both exposing
 *    "search" must be namespaced or the agent calls the wrong one);
 *  - produces a compact, token-cheap capability manifest for the system prompt
 *    (name + one-line description, no full JSON schema — the agent re-fetches
 *    the schema with `minis-mcp-cli tools <server>` when it needs to call);
 *  - resolves a qualified reference `server.tool` / `server/tool` back to a
 *    call plan, rejecting unknown or ambiguous references.
 *
 * No external dependency; runs on the JVM unit-test classpath.
 */
object McpToolCatalog {

    data class Tool(
        val server: String,
        val name: String,
        val description: String,
        val inputNames: List<String> = emptyList(),
    ) {
        val qualified: String get() = "$server.$name"
    }

    /** Parse the CLI `tools` JSON into a flat tool list. Empty on malformed input. */
    fun parse(json: String, serverHint: String = ""): List<Tool> {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return emptyList()
        return runCatching {
            if (trimmed.startsWith("[")) {
                parseArray(org.json.JSONArray(trimmed), serverHint)
            } else {
                val root = JSONObject(trimmed)
                val result = root.optJSONObject("result") ?: root
                val server = result.optString("server", serverHint)
                val arr = result.optJSONArray("tools") ?: root.optJSONArray("tools")
                if (arr == null) emptyList() else parseArray(arr, server)
            }
        }.getOrDefault(emptyList())
    }

    private fun parseArray(arr: org.json.JSONArray, server: String): List<Tool> =
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name", "")
            if (name.isEmpty()) return@mapNotNull null
            val desc = o.optString("description", "")
            val schema = o.optJSONObject("inputSchema")
            val props = schema?.optJSONObject("properties")
            val inputNames = if (props == null) emptyList() else (0 until props.length()).map { props.names()?.optString(it) ?: "" }
                .filter { it.isNotEmpty() }
            Tool(server, name, desc, inputNames)
        }

    /** Tool names exposed by more than one server — callers must qualify them. */
    fun collisions(tools: List<Tool>): Map<String, List<String>> =
        tools.groupBy { it.name }.filter { it.value.map { t -> t.server }.distinct().size > 1 }
            .mapValues { (_, list) -> list.map { it.server }.distinct().sorted() }

    /**
     * Compact manifest for injection. One line per tool, prefixed by server
     * only when its (server, name) is involved in a collision or [alwaysQualify].
     * Capped by [maxChars] and [maxTools] to stay token-cheap.
     */
    fun manifest(tools: List<Tool>, maxTools: Int = 40, maxChars: Int = 4_000, alwaysQualify: Boolean = false): String {
        if (tools.isEmpty()) return ""
        val dupNames = collisions(tools).keys
        val sb = StringBuilder()
        var count = 0
        for (t in tools.sortedWith(compareBy<Tool> { it.server }.thenBy { it.name })) {
            if (count >= maxTools) break
            val ref = if (alwaysQualify || t.name in dupNames) t.qualified else t.name
            val oneLine = t.description.replace(Regex("\\s+"), " ").trim().take(160)
            val line = "- $ref: ${oneLine.ifEmpty { "(no description)" }}\n"
            if (sb.length + line.length > maxChars) break
            sb.append(line)
            count++
        }
        return sb.toString().trimEnd()
    }

    /**
     * Resolve a user/agent reference to exactly one tool, or an error string.
     * Accepts `server.tool`, `server/tool`, or a bare name when unambiguous.
     */
    fun resolve(ref: String, tools: List<Tool>): ResolveResult {
        val r = ref.trim()
        if (r.isEmpty()) return ResolveResult.Error("empty tool reference")
        val parts = r.split('.', '/').filter { it.isNotEmpty() }
        val matches = if (parts.size >= 2) {
            val server = parts[0]
            val name = parts.drop(1).joinToString(".")
            tools.filter { it.server.equals(server, true) && it.name.equals(name, true) }
        } else {
            tools.filter { it.name.equals(r, true) }
        }
        return when {
            matches.isEmpty() -> ResolveResult.Error("unknown MCP tool: $r")
            matches.size == 1 -> ResolveResult.Found(matches[0])
            else -> ResolveResult.Ambiguous(r, matches.map { it.qualified }.distinct())
        }
    }

    sealed class ResolveResult {
        data class Found(val tool: Tool) : ResolveResult()
        data class Ambiguous(val ref: String, val candidates: List<String>) : ResolveResult()
        data class Error(val message: String) : ResolveResult()
    }

    /** Build the exact CLI call string for a resolved tool with JSON args. */
    fun callCommand(tool: Tool, argsJson: String = "{}"): String =
        "minis-mcp-cli call ${tool.server} ${tool.name} '$argsJson'"
}
