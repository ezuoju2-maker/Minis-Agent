package com.openminis.app.tools

import org.json.JSONArray
import org.json.JSONObject

object McpPluginCatalog {

    data class Plugin(
        val id: String,
        val name: String,
        val description: String,
        val category: String,
        val config: JSONObject,
        val requiredEnv: List<String> = emptyList(),
        val setupNote: String = "",
    ) {
        val needsConfig: Boolean get() = requiredEnv.isNotEmpty()
    }

    fun all(): List<Plugin> = buildList {
        add(Plugin(
            id = "time",
            name = "Time & Timezone",
            description = "Current time and timezone conversion for any location.",
            category = "Productivity",
            config = JSONObject()
                .put("command", "python3")
                .put("args", JSONArray().put("-m").put("mcp_server_time")),
            setupNote = "Needs the mcp-server-time python package (pip install mcp-server-time).",
        ))
        add(Plugin(
            id = "filesystem",
            name = "Filesystem",
            description = "Read, write and search files in allowed directories.",
            category = "Developer",
            config = JSONObject()
                .put("command", "npx")
                .put("args", JSONArray().put("-y").put("@modelcontextprotocol/server-filesystem").put("/var/minis/workspace")),
            setupNote = "Scoped to /var/minis/workspace (the session sandbox) by default.",
        ))
        add(Plugin(
            id = "fetch",
            name = "Web Fetch",
            description = "Fetch a URL and return its content as markdown.",
            category = "Web",
            config = JSONObject()
                .put("command", "uvx")
                .put("args", JSONArray().put("mcp-server-fetch")),
            setupNote = "Needs uv (uvx) or adapt to pipx.",
        ))
        add(Plugin(
            id = "memory",
            name = "Knowledge Graph Memory",
            description = "Persistent knowledge graph: entities, relations and observations.",
            category = "Productivity",
            config = JSONObject()
                .put("command", "npx")
                .put("args", JSONArray().put("-y").put("@modelcontextprotocol/server-memory")),
        ))

        add(Plugin(
            id = "github",
            name = "GitHub",
            description = "Repos, issues, pull requests, code search and file operations.",
            category = "Developer",
            config = JSONObject()
                .put("command", "npx")
                .put("args", JSONArray().put("-y").put("@modelcontextprotocol/server-github"))
                .put("env", JSONObject().put("GITHUB_PERSONAL_ACCESS_TOKEN", "\$\$GITHUB_TOKEN")),
            requiredEnv = listOf("GITHUB_TOKEN"),
            setupNote = "Create a GitHub fine-grained token, store it as App env var GITHUB_TOKEN.",
        ))
        add(Plugin(
            id = "sqlite",
            name = "SQLite",
            description = "Query and manage a local SQLite database.",
            category = "Data",
            config = JSONObject()
                .put("command", "python3")
                .put("args", JSONArray().put("-m").put("mcp_server_sqlite").put("/var/minis/workspace/data.db")),
            setupNote = "Point at a database path inside the session workspace.",
        ))
    }

    fun categories(): List<String> = all().map { it.category }.distinct().sorted()

    fun byId(id: String): Plugin? = all().firstOrNull { it.id.equals(id, true) }

    fun importJson(plugin: Plugin): String =
        JSONObject().put("mcpServers", JSONObject().put(plugin.id, plugin.config)).toString()

    fun importJson(plugins: List<Plugin>): String {
        val servers = JSONObject()
        plugins.forEach { servers.put(it.id, it.config) }
        return JSONObject().put("mcpServers", servers).toString()
    }
}
