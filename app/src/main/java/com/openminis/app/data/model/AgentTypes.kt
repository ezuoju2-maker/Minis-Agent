package com.openminis.app.data.model

import org.json.JSONObject

sealed class AgentStreamEvent {
    data class ContentBlockStart(val start: AgentBlockStart) : AgentStreamEvent()
    data class TextDelta(val delta: String) : AgentStreamEvent()
    data class ToolInputDelta(val name: String, val accumulated: String) : AgentStreamEvent()
    data class ToolCallComplete(
        val id: String,
        val name: String,
        val args: JSONObject,
        val metadata: ToolCallMetadata? = null,
    ) : AgentStreamEvent()
    data class Usage(val usage: LLMUsage) : AgentStreamEvent()
    data class ThinkingDelta(val delta: String) : AgentStreamEvent()
    data class ReasoningContent(val content: String) : AgentStreamEvent()
    data class Done(val stopReason: AgentStopReason) : AgentStreamEvent()
}

sealed class AgentBlockStart {
    data object Text : AgentBlockStart()
    data class ToolUse(val id: String, val name: String) : AgentBlockStart()
}

enum class AgentStopReason { END_TURN, TOOL_USE, MAX_TOKENS }

data class ToolCallMetadata(
    val thoughtSignature: String? = null,
)

data class AgentMessage(
    val role: Role,
    var parts: List<AgentContentPart>,
    var isInterrupted: Boolean = false,
    var reasoningContent: String? = null,
    var dbMessageId: String? = null,
) {
    enum class Role(val value: String) {
        USER("user"),
        ASSISTANT("assistant"),
    }
}

fun sanitizeToolId(id: String): String = buildString(id.length) {
    for (ch in id) {
        append(if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '-')
    }
}

const val WIRE_TOOL_ID_MAX = 40

fun wireToolId(raw: String): String {
    val head = raw.substringBefore('|')
    val clean = buildString(head.length) {
        var i = 0
        while (i < head.length) {
            val cp = head.codePointAt(i)
            val ok = cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code ||
                cp in '0'.code..'9'.code || cp == '_'.code || cp == '-'.code
            append(if (ok) cp.toChar() else '-')
            i += Character.charCount(cp)
        }
    }
    if (clean.isNotEmpty() && clean.length <= WIRE_TOOL_ID_MAX) return clean
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString("") { "%02x".format(it) }
    return "call_" + hex.take(WIRE_TOOL_ID_MAX - "call_".length)
}

private val KIMI_NATIVE_TOOL_ID = Regex("^functions\\.[A-Za-z0-9_-]+:[0-9]+$")

const val KIMI_NATIVE_TOOL_ID_MAX = 80

fun isKimiNativeToolId(raw: String): Boolean =
    raw.length <= KIMI_NATIVE_TOOL_ID_MAX && KIMI_NATIVE_TOOL_ID.matches(raw)

fun wireToolIdWithSuffix(base: String, n: Int): String {
    val suffix = "-$n"
    return base.take((WIRE_TOOL_ID_MAX - suffix.length).coerceAtLeast(0)) + suffix
}

class WireToolIdMap(
    private val keepAsIs: (String) -> Boolean = { false },
) {
    private val byRaw = HashMap<String, String>()
    private val taken = HashSet<String>()

    fun idFor(raw: String): String = byRaw.getOrPut(raw) {
        val base = if (keepAsIs(raw)) raw else wireToolId(raw)
        var id = base
        var n = 2
        while (!taken.add(id)) id = wireToolIdWithSuffix(base, n++)
        id
    }

    fun changed(): List<Pair<String, String>> = byRaw.filter { (raw, wire) -> raw != wire }.toList()
}

fun logWireToolIds(protocol: String, ids: WireToolIdMap) {
    val changed = ids.changed()
    if (changed.isEmpty()) return
    android.util.Log.i(
        "ToolId",
        "[ToolId] protocol=$protocol rewritten=${changed.size} " +
            "rawLen=${changed.maxOf { it.first.length }} wireLen=${changed.maxOf { it.second.length }}",
    )
}
