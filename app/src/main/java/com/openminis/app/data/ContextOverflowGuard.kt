package com.openminis.app.data

object ContextOverflowGuard {

    private val OVERFLOW_STATUSES = setOf(400, 413)

    private val OVERFLOW_MARKERS = listOf(
        "maximum context length",
        "context length exceeded",
        "context_length_exceeded",
        "reduce the length of the messages",
        "too many tokens",
        "prompt is too long",
        "request too large",
        "exceeds the maximum",
        "input is too long",
        "exceeds the context window",
        "input exceeds the context",
        "exceed context limit",
        "上下文长度",
        "超出最大长度",
        "内容过长",
    )

    private val BYTE_AMBIGUOUS_MARKERS = setOf("exceeds the maximum", "request too large")

    fun isContextOverflow(status: Int?, detail: String?): Boolean {
        if (status == null || status !in OVERFLOW_STATUSES) return false
        val text = detail?.lowercase() ?: return false
        val hits = OVERFLOW_MARKERS.filter { text.contains(it) }
        if (hits.isEmpty()) return false
        if (text.contains("bytes")) return hits.any { it !in BYTE_AMBIGUOUS_MARKERS }
        return true
    }
}
