package com.openminis.app.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * [plugin-ecosystem / curated catalog] A curated directory of mainstream MCP
 * servers that can be installed into the app's standard MCP configuration.
 *
 * This is deliberately a CATALOG OF TEMPLATES, not bundled plugins: installing
 * means writing a normal `mcpServers` entry (via the existing "Import JSON"
 * flow or the CLI), so the plugin is a first-class MCP server the agent calls
 * through `minis-mcp-cli` — exactly like any user-added server. No code runs
 * inside the app, no privileges are granted, and nothing touches security or
 * sandbox boundaries.
 *
 * Templates are grouped by whether they need credentials: "no-config" ones
 * (filesystem, time, fetch) work immediately; others need an env var / token
 * the user supplies via an existing App environment variable (`$$VAR`).
 */
object McpPluginCatalog {

    data class Plugin(
        val id: String,
        val name: String,
        val description: String,
        val category: String,
        /** JSON object for the `mcpServers[id]` value. */
        val config: JSONObject,
        /** Env var names the user must set in the App (empty = works out of the box). */
        val requiredEnv: List<String> = emptyList(),
        /** Short setup note shown to the user. */
        val setupNote: String = "",
    ) {
        val needsConfig: Boolean get() = requiredEnv.isNotEmpty()
    }

    /**
     * Build the catalog. Created on demand (JSONObject is mutable) so callers
     * always get fresh, independent objects.
     */
    fun all(): List<Plugin> = buildList {
        // ── No-config: STDIO servers that run with python/node already in the
        // Ubuntu env, or public HTTP endpoints needing no token. ──
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

        // ── Need credentials: user supplies an existing App env var. ──
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

    /** Generate a `servers.json` import payload for one plugin (merges-safe shape). */
    fun importJson(plugin: Plugin): String =
        JSONObject().put("mcpServers", JSONObject().put(plugin.id, plugin.config)).toString()

    /** Generate an import payload for several plugins at once. */
    fun importJson(plugins: List<Plugin>): String {
        val servers = JSONObject()
        plugins.forEach { servers.put(it.id, it.config) }
        return JSONObject().put("mcpServers", servers).toString()
    }
}
