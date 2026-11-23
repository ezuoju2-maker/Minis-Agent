package com.openminis.app.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MCPRepository(private val context: Context) {

    companion object {
        private const val TAG = "MCPRepository"
        private const val DB_NAME = "mcp.db"
        private const val DB_VERSION = 1
        private const val MAX_MCPS_IN_PROMPT = 20
        private const val MAX_NOTE_LENGTH = 200
    }


    data class MCPServerConfig(
        val id: String,
        val note: String? = null,
        val enabled: Boolean = true,
        val url: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val command: String? = null,
        val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(),
        val startupTimeoutSeconds: Int? = null,
        val oauth: com.openminis.app.mcp.oauth.MCPOAuthConfig? = null,
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        val isStdio: Boolean get() = !command.isNullOrBlank()
        val transportSummary: String
            get() = if (isStdio) {
                (listOf(command).plus(args)).joinToString(" ").trim()
            } else {
                url ?: ""
            }
    }


    private val _servers = MutableStateFlow<List<MCPServerConfig>>(emptyList())
    val servers: StateFlow<List<MCPServerConfig>> = _servers.asStateFlow()

    private val db: SQLiteDatabase by lazy {
        McpDbHelper(context).writableDatabase
    }

    private val mcpDir: File
        get() = File(context.filesDir, "minis-global/mcp-servers")

    private val serversFile: File
        get() = File(mcpDir, "servers.json")

    init {
        load()
    }


    fun load() {
        _servers.value = readServersFile()
    }

    fun reloadFromDisk() = load()

    private fun readServersFile(): List<MCPServerConfig> {
        val file = serversFile
        if (!file.exists()) return emptyList()
        val text = try { file.readText() } catch (e: Exception) {
            Log.w(TAG, "Failed to read servers.json: ${e.message}")
            return emptyList()
        }
        if (text.isBlank()) return emptyList()
        val root = try { JSONObject(text) } catch (e: Exception) {
            Log.w(TAG, "servers.json is not valid JSON: ${e.message}")
            return emptyList()
        }
        val obj = root.optJSONObject("mcpServers") ?: return emptyList()
        val out = mutableListOf<MCPServerConfig>()
        var needsPersist = false
        val now = System.currentTimeMillis()
        var idx = 0
        for (name in obj.keys()) {
            val entry = obj.optJSONObject(name) ?: continue
            val parsed = parseEntry(name, entry)
            if (!entry.has("createdAt")) {
                needsPersist = true
                out.add(parsed.copy(createdAt = now - (idx + 1)))
            } else {
                out.add(parsed)
            }
            idx++
        }
        val sorted = out.sortedByDescending { it.createdAt }
        if (needsPersist) save(sorted)
        return sorted
    }

    private fun save(list: List<MCPServerConfig> = _servers.value) {
        mcpDir.mkdirs()
        val mcpServers = JSONObject()
        for (s in list) {
            mcpServers.put(s.id, entryToJson(s))
        }
        val root = JSONObject().put("mcpServers", mcpServers)
        try {
            serversFile.writeText(root.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write servers.json: ${e.message}")
        }
    }

    private fun parseEntry(name: String, entry: JSONObject): MCPServerConfig {
        val enabled = when {
            entry.has("enabled") -> entry.optBoolean("enabled", true)
            entry.has("disabled") -> !entry.optBoolean("disabled", false)
            else -> true
        }
        val headers = entry.optJSONObject("headers")?.let { stringMap(it) } ?: emptyMap()
        val env = entry.optJSONObject("env")?.let { stringMap(it) } ?: emptyMap()
        val args = entry.optJSONArray("args")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }
        } ?: emptyList()
        val note = entry.optString("note", "").ifBlank { null }
        val createdAt = entry.optLong("createdAt", System.currentTimeMillis())
        return MCPServerConfig(
            id = name,
            note = note,
            enabled = enabled,
            url = entry.optString("url", "").ifBlank { null },
            headers = headers,
            command = entry.optString("command", "").ifBlank { null },
            args = args,
            env = env,
            startupTimeoutSeconds = parseStartupTimeout(entry),
            oauth = com.openminis.app.mcp.oauth.MCPOAuthConfig.fromJson(entry.optJSONObject("oauth")),
            createdAt = createdAt,
        )
    }

    private fun parseStartupTimeout(entry: JSONObject): Int? {
        val keys = listOf(
            "startupTimeoutSeconds", "startup_timeout_sec", "startupTimeout",
            "handshakeTimeout", "initializeTimeout",
        )
        for (key in keys) {
            if (!entry.has(key) || entry.isNull(key)) continue
            val raw = entry.get(key)
            when (raw) {
                is Number -> return raw.toInt()
                is String -> raw.trim().toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun entryToJson(s: MCPServerConfig): JSONObject {
        val o = JSONObject()
        if (s.isStdio) {
            o.put("command", s.command)
            if (s.args.isNotEmpty()) o.put("args", JSONArray(s.args))
            if (s.env.isNotEmpty()) o.put("env", JSONObject(s.env))
            s.startupTimeoutSeconds?.let { o.put("startupTimeoutSeconds", it) }
        } else if (!s.url.isNullOrBlank()) {
            o.put("url", s.url)
            if (s.headers.isNotEmpty()) o.put("headers", JSONObject(s.headers))
            s.oauth?.takeIf { it.isConfigured || it.isDynamic }?.let { o.put("oauth", it.toJson()) }
        }
        s.note?.takeIf { it.isNotBlank() }?.let { o.put("note", it) }
        o.put("enabled", s.enabled)
        o.put("createdAt", s.createdAt)
        return o
    }

    private fun stringMap(obj: JSONObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (k in obj.keys()) out[k] = obj.optString(k, "")
        return out
    }

    fun exportServerJSON(server: MCPServerConfig): String {
        val mcpServers = JSONObject().put(server.id, entryToJson(server))
        return JSONObject().put("mcpServers", mcpServers).toString(2)
    }


    fun add(server: MCPServerConfig): Boolean {
        if (server.id.isBlank()) return false
        _servers.value = _servers.value.filter { it.id != server.id } + server
        save()
        Log.i(TAG, "Added MCP server: ${server.id}")
        return true
    }

    fun update(server: MCPServerConfig): Boolean {
        if (_servers.value.none { it.id == server.id }) return false
        _servers.value = _servers.value.map { if (it.id == server.id) server else it }
        save()
        return true
    }

    fun delete(id: String) {
        db.execSQL("DELETE FROM mcp_session_overrides WHERE mcp_id=?", arrayOf(id))
        _servers.value = _servers.value.filter { it.id != id }
        save()
        Log.i(TAG, "Deleted MCP server: $id")
    }

    fun setEnabled(id: String, enabled: Boolean) {
        _servers.value = _servers.value.map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        save()
    }

    fun toggle(id: String) {
        val current = _servers.value.find { it.id == id } ?: return
        setEnabled(id, !current.enabled)
    }


    fun isEnabledForSession(mcpId: String, sessionId: String): Boolean {
        val cursor = db.rawQuery(
            "SELECT is_enabled FROM mcp_session_overrides WHERE session_id=? AND mcp_id=?",
            arrayOf(sessionId, mcpId)
        )
        val override = if (cursor.moveToFirst()) cursor.getInt(0) == 1 else null
        cursor.close()
        if (override != null) return override
        return _servers.value.find { it.id == mcpId }?.enabled ?: false
    }

    fun setSessionOverride(sessionId: String, mcpId: String, enabled: Boolean) {
        val globalEnabled = _servers.value.find { it.id == mcpId }?.enabled
        if (globalEnabled != null && enabled == globalEnabled) {
            db.execSQL(
                "DELETE FROM mcp_session_overrides WHERE session_id=? AND mcp_id=?",
                arrayOf(sessionId, mcpId)
            )
        } else {
            db.execSQL(
                "INSERT OR REPLACE INTO mcp_session_overrides (session_id, mcp_id, is_enabled) VALUES (?, ?, ?)",
                arrayOf<Any>(sessionId, mcpId, if (enabled) 1 else 0)
            )
        }
    }

    fun clearSessionOverrides(sessionId: String) {
        db.execSQL("DELETE FROM mcp_session_overrides WHERE session_id=?", arrayOf(sessionId))
    }

    fun renameSessionOverrides(fromDraft: String, toReal: String) {
        if (fromDraft == toReal) return
        db.execSQL(
            "UPDATE OR REPLACE mcp_session_overrides SET session_id=? WHERE session_id=?",
            arrayOf<Any>(toReal, fromDraft),
        )
    }


    fun importJSON(text: String): List<MCPServerConfig> {
        val root = try { JSONObject(text.trim()) } catch (e: Exception) {
            Log.w(TAG, "importJSON: not valid JSON: ${e.message}")
            return emptyList()
        }
        val parsed = parseImportRoot(root)
        if (parsed.isEmpty()) return emptyList()
        val incomingIds = parsed.mapTo(HashSet()) { it.id }
        _servers.value = _servers.value.filter { it.id !in incomingIds } + parsed
        _servers.value = _servers.value.sortedByDescending { it.createdAt }
        save()
        Log.i(TAG, "Imported ${parsed.size} MCP server(s)")
        return parsed
    }

    fun previewImport(text: String): Int {
        val root = try { JSONObject(text.trim()) } catch (_: Exception) { return 0 }
        return parseImportRoot(root).size
    }

    private fun parseImportRoot(root: JSONObject): List<MCPServerConfig> {
        root.optJSONObject("mcpServers")?.let { obj ->
            return obj.keys().asSequence().mapNotNull { name ->
                obj.optJSONObject(name)?.let { parseEntry(name, it) }
            }.toList()
        }
        if (root.has("command") || root.has("url")) {
            val name = root.optString("name", "").ifBlank { "imported-mcp" }
            return listOf(parseEntry(name, root))
        }
        val out = mutableListOf<MCPServerConfig>()
        for (name in root.keys()) {
            val entry = root.optJSONObject(name) ?: continue
            if (entry.has("command") || entry.has("url")) {
                out.add(parseEntry(name, entry))
            }
        }
        return out
    }


    fun mcpPromptFragment(sessionId: String): String? {
        val enabled = _servers.value
            .filter { isEnabledForSession(it.id, sessionId) }
            .sortedByDescending { it.createdAt }
        if (enabled.isEmpty()) return null

        val selected = enabled.take(MAX_MCPS_IN_PROMPT)

        return buildString {
            append("Available MCP Servers (use minis-mcp-cli to discover and call):\n")
            for (s in selected) {
                var note = s.note ?: ""
                if (note.length > MAX_NOTE_LENGTH) note = note.substring(0, MAX_NOTE_LENGTH) + "…"
                append("- ").append(s.id)
                if (note.isNotBlank()) append(": ").append(note)
                append("\n")
            }
            append("\n")
            append("To use: run `minis-mcp-cli tools <server>` to see available tools,\n")
            append("then `minis-mcp-cli call <server> <tool> [args]` to invoke.\n")
            append("Tool names may overlap across servers: if two servers expose the same tool, qualify it as `call <server> <tool>`. Always confirm the exact tool name and arguments with `tools <server>` before calling.\n")
            append("When adding or modifying an MCP server config (via minis-mcp-cli add / the UI), use \$\$VARNAME in env/headers/url values as a placeholder resolved at runtime from the system/App environment variables — do not hardcode secrets; reference an existing App environment variable as \$\$NAME.")
        }
    }


    private class McpDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE mcp_session_overrides (
                    session_id TEXT NOT NULL,
                    mcp_id TEXT NOT NULL,
                    is_enabled INTEGER NOT NULL,
                    PRIMARY KEY (session_id, mcp_id)
                )
            """)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        }
    }
}
