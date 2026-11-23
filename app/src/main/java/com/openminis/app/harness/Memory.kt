package com.openminis.app.harness

/**
 * v2.0 · Memory & interoperability contracts.
 *
 * Memory (Letta blocks + mem0 write-time extraction + Graphiti validity windows)
 * is implemented with plain Kotlin collections and persisted through MiniJson —
 * no vector DB, no FTS5 dependency, no Android. A 1B on-device model is enough
 * for the optional "extract fact" call; the default path is deterministic rules.
 *
 * MCP / A2A are represented as *contracts* (DTOs + validation), not a network
 * stack: the host plugs in OkHttp/Ktor. The security rules here are the ones
 * mobile MCP adapters get wrong most often (mcp-mobile's published pitfalls).
 */

class MemoryCore(private val clock: () -> Long = { 0L }) {

    data class Fact(val key: String, val value: String, val validFrom: Long, val validTo: Long? = null)
    data class Episode(val goal: String, val outcome: String, val steps: Int, val costMicros: Long, val at: Long)
    enum class Op { ADD, UPDATE, DELETE, NOOP }

    /** Letta-style always-visible context blocks (small, human-editable). */
    private val blocks = LinkedHashMap<String, String>()
    private val facts = ArrayList<Fact>()
    private val episodes = ArrayList<Episode>()

    init {
        blocks["user_preferences"] = ""
        blocks["frequent_apps"] = ""
        blocks["taboos"] = "不自动付款；不自动填写验证码/密码；不读取凭据文件。"
        blocks["device"] = ""
    }

    fun setBlock(name: String, value: String) { blocks[name] = value.take(2_000) }
    fun block(name: String): String? = blocks[name]

    /** Injected into every request; cheap and fully inspectable by the user. */
    fun contextBlocks(): String = buildString {
        blocks.forEach { (k, v) -> if (v.isNotEmpty()) append("[").append(k).append("] ").append(v).append('\n') }
        val active = activeFacts(clock())
        if (active.isNotEmpty()) {
            append("[facts]\n")
            active.forEach { append("- ").append(it.key).append(": ").append(it.value).append('\n') }
        }
    }

    /**
     * mem0-style ADD / UPDATE / DELETE decision at WRITE time. A fact is never
     * deleted: its validity window is closed (Graphiti), so "what was true last
     * week" remains answerable and an undo is trivial.
     */
    fun upsertFact(key: String, value: String, now: Long = clock()): Op {
        val k = key.trim()
        if (k.isEmpty()) return Op.NOOP
        val current = facts.lastOrNull { it.key == k && it.validTo == null }
        return when {
            current == null -> { facts.add(Fact(k, value, now)); Op.ADD }
            current.value == value -> Op.NOOP
            value.isBlank() -> { close(k, now); Op.DELETE }
            else -> { close(k, now); facts.add(Fact(k, value, now)); Op.UPDATE }
        }
    }

    private fun close(key: String, now: Long) {
        val i = facts.indexOfLast { it.key == key && it.validTo == null }
        if (i >= 0) facts[i] = facts[i].copy(validTo = now)
    }

    fun activeFacts(now: Long = clock()): List<Fact> = facts.filter {
        it.validFrom <= now && (it.validTo ?: Long.MAX_VALUE) > now
    }

    fun history(key: String): List<Fact> = facts.filter { it.key == key }

    fun addEpisode(goal: String, outcome: String, steps: Int, costMicros: Long, now: Long = clock()) {
        episodes.add(Episode(goal.take(300), outcome.take(500), steps, costMicros, now))
        if (episodes.size > 200) episodes.removeAt(0)
    }

    /** Rolling summary + big-result externalisation (OpenHands context compression). */
    fun compact(events: List<AgentEvent>, maxChars: Int = 2_000): String {
        val sb = StringBuilder()
        sb.append("Summary of ${events.size} events\n")
        events.filter { it.kind in setOf(EventKind.PLAN_UPDATED, EventKind.TOOL_RESULT, EventKind.ERROR, EventKind.SUMMARY, EventKind.RUN_COMPLETED) }
            .takeLast(40)
            .forEach { e -> sb.append("#").append(e.seq).append(" ").append(e.kind.name).append(": ").append(e.summary.take(160)).append('\n') }
        val text = sb.toString()
        if (text.length <= maxChars) return text
        val prefix = "…[${text.length - maxChars} chars omitted]…\n"
        return if (prefix.length >= maxChars) text.takeLast(maxChars)
        else prefix + text.takeLast(maxChars - prefix.length)
    }

    fun export(): String = MiniJson.stringify(mapOf(
        "blocks" to blocks,
        "facts" to facts.map { mapOf("key" to it.key, "value" to it.value, "from" to it.validFrom, "to" to (it.validTo ?: -1L)) },
        "episodes" to episodes.map { mapOf("goal" to it.goal, "outcome" to it.outcome, "steps" to it.steps, "cost" to it.costMicros, "at" to it.at) },
    ))

    /** Transactional restore, same rule as the event log: corrupt input changes nothing. */
    fun restore(json: String): Boolean {
        val m = try { MiniJson.parseObject(json) } catch (_: Exception) { null } ?: return false
        val newBlocks = LinkedHashMap<String, String>()
        @Suppress("UNCHECKED_CAST")
        (m["blocks"] as? Map<String, Any?>)?.forEach { (k, v) -> if (v is String) newBlocks[k] = v }
        val newFacts = ArrayList<Fact>()
        @Suppress("UNCHECKED_CAST")
        (m["facts"] as? List<*>)?.forEach { row ->
            @Suppress("UNCHECKED_CAST")
            val r = row as? Map<String, Any?> ?: return@forEach
            val to = MiniJson.long(r, "to", -1L)
            newFacts.add(Fact(MiniJson.str(r, "key"), MiniJson.str(r, "value"), MiniJson.long(r, "from"), if (to < 0) null else to))
        }
        blocks.clear(); blocks.putAll(newBlocks)
        facts.clear(); facts.addAll(newFacts)
        episodes.clear()
        return true
    }
}

// ---------------------------------------------------------------- MCP / A2A

object McpContract {

    data class ServerConfig(
        val id: String,
        val url: String,
        val displayName: String,
        val authAlias: String? = null,      // Keystore alias, never a raw token
        val debugAllowLoopbackHttp: Boolean = false,
        val timeoutMs: Long = 20_000L,
        val pinnedTools: Set<String> = emptySet(),
    )

    /** Fail closed. Returns human-readable violations; empty means usable. */
    fun validate(cfg: ServerConfig): List<String> {
        val issues = ArrayList<String>()
        val lower = cfg.url.lowercase()
        when {
            lower.startsWith("https://") -> { /* ok */ }
            lower.startsWith("http://127.0.0.1") || lower.startsWith("http://localhost") -> {
                if (!cfg.debugAllowLoopbackHttp) issues.add("loopback HTTP is debug-only")
            }
            else -> issues.add("MCP endpoint must be HTTPS")
        }
        if (cfg.timeoutMs <= 0 || cfg.timeoutMs > 120_000L) issues.add("timeout must be bounded (0,120s]")
        if (cfg.authAlias != null && cfg.authAlias.startsWith("sk-")) issues.add("raw credential in config; use a Keystore alias")
        return issues
    }

    fun toolsListRequest(id: Long): String = MiniJson.obj(
        "jsonrpc" to "2.0", "id" to id, "method" to "tools/list", "params" to emptyMap<String, Any?>(),
    )

    /** Map remote tools into the SAME ToolDescriptor used by local device tools. */
    fun parseToolsList(json: String, defaultRisk: Risk = Risk.R2): List<ToolDescriptor> {
        val root = MiniJson.parseObject(json) ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val result = root["result"] as? Map<String, Any?> ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val tools = result["tools"] as? List<*> ?: return emptyList()
        return tools.mapNotNull { row ->
            @Suppress("UNCHECKED_CAST")
            val t = row as? Map<String, Any?> ?: return@mapNotNull null
            val name = MiniJson.str(t, "name")
            if (name.isEmpty()) return@mapNotNull null
            ToolDescriptor(
                name = "mcp:" + name,
                description = MiniJson.str(t, "description"),
                risk = defaultRisk,
                schemaJson = MiniJson.stringify(t["inputSchema"] ?: emptyMap<String, Any?>()),
                requiresNetwork = true,
                privacyModeAllowed = false,
                category = "mcp",
            )
        }
    }

    fun toolCallRequest(id: Long, name: String, argsJson: String): String {
        val args = MiniJson.parse(argsJson)
        return MiniJson.obj(
            "jsonrpc" to "2.0", "id" to id, "method" to "tools/call",
            "params" to mapOf("name" to name.removePrefix("mcp:"), "arguments" to args),
        )
    }

    fun parseToolCallResult(json: String): ToolResult {
        val root = MiniJson.parseObject(json) ?: return ToolResult("invalid MCP response", true, false, "MCP_PARSE")
        if (root.containsKey("error")) return ToolResult(MiniJson.stringify(root["error"]), true, true, "MCP_ERROR")
        @Suppress("UNCHECKED_CAST")
        val result = root["result"] as? Map<String, Any?> ?: return ToolResult("", false, false, null)
        val isError = MiniJson.bool(result, "isError")
        @Suppress("UNCHECKED_CAST")
        val content = (result["content"] as? List<*>)?.joinToString("\n") { part ->
            @Suppress("UNCHECKED_CAST")
            val p = part as? Map<String, Any?> ?: return@joinToString part.toString()
            MiniJson.str(p, "text", MiniJson.stringify(p))
        } ?: MiniJson.stringify(result)
        return ToolResult(content, isError, isError, if (isError) "MCP_TOOL_ERROR" else null)
    }

    /** Cancellation is best-effort: the UI MUST say the server may have executed. */
    fun cancellationNotice(id: Long, reason: String): String = MiniJson.obj(
        "jsonrpc" to "2.0", "method" to "notifications/cancelled",
        "params" to mapOf(
            "requestId" to id, "reason" to reason,
            "uiNotice" to "已请求取消；远端可能已执行该动作，请核对结果。",
        ),
    )

    const val AGENT_CARD_PATH = "/.well-known/agent-card.json"

    /** A2A client minimum: capability discovery before any SendMessage. */
    fun agentCardRequest(baseUrl: String): String? =
        if (!baseUrl.lowercase().startsWith("https://")) null
        else baseUrl.trimEnd('/') + AGENT_CARD_PATH
}
