package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

object McpToolRegistry {

    private const val MAX_SERVERS = 4
    private const val PER_SERVER_CAP = 20
    private const val TOTAL_CAP = 48
    private const val DESCRIPTION_CAP = 180

    @Volatile
    private var snapshot: List<McpToolCatalog.Tool> = emptyList()

    @Volatile
    private var generation: Int = 0

    fun version(): Int = generation

    fun current(): List<McpToolCatalog.Tool> = snapshot

    fun clear() {
        synchronized(this) {
            snapshot = emptyList()
            generation++
        }
    }

    @Synchronized
    fun ingest(server: String, json: String): Int {
        val parsed = McpToolCatalog.parse(json, server)
            .filter { it.name.isNotBlank() }
            .take(PER_SERVER_CAP)
        if (parsed.isEmpty()) return 0
        val merged = snapshot.filter { it.server != server } + parsed
        val capped = merged.take(TOTAL_CAP)
        if (capped != snapshot) {
            snapshot = capped
            generation++
        }
        return parsed.size
    }

    suspend fun refresh(servers: List<String>, run: suspend (String) -> String?) {
        for (server in servers.distinct().take(MAX_SERVERS)) {
            val json = try { run(server) } catch (_: Exception) { null }
            if (json.isNullOrBlank()) continue
            ingest(server, json)
        }
    }

    fun definitions(): List<AgentToolDefinition> = snapshot.map { tool ->
        val name = McpNativeTools.toolName(tool.server, tool.name)
        val head = if (tool.description.isBlank()) {
            "MCP tool " + tool.name + " on server " + tool.server + "."
        } else {
            tool.description.replace(Regex("\\s+"), " ").trim().take(DESCRIPTION_CAP)
        }
        val hint = if (tool.inputNames.isEmpty()) {
            ""
        } else {
            " Arguments: " + tool.inputNames.joinToString(", ") + "."
        }
        AgentToolDefinition(
            name = name,
            description = head + hint,
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
                "arguments" to AgentToolParam("string", "JSON object with this tool's arguments (as the server reports them). Omit when none are needed."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf("tool_title", "arguments"),
        )
    }
}
