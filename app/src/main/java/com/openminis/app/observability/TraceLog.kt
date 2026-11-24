package com.openminis.app.observability

data class TraceEvent(
    val seq: Long,
    val turn: Int,
    val kind: String,
    val name: String,
    val durationMs: Long,
    val ok: Boolean,
    val summary: String,
)

class TraceLog(
    private val maxEvents: Int = 2000,
    private val summaryLimit: Int = 240,
) {

    private val lock = Any()
    private val events = ArrayList<TraceEvent>()
    private var nextSeq = 1L

    val size: Int get() = synchronized(lock) { events.size }
    val lastSeq: Long get() = synchronized(lock) { nextSeq - 1 }

    fun emit(
        turn: Int,
        kind: String,
        name: String,
        durationMs: Long,
        ok: Boolean,
        summary: String = "",
    ): TraceEvent = synchronized(lock) {
        val e = TraceEvent(
            nextSeq++,
            turn,
            sanitize(kind, 32),
            sanitize(name, 64),
            maxOf(0L, durationMs),
            ok,
            clip(summary),
        )
        events.add(e)
        while (events.size > maxEvents) events.removeAt(0)
        e
    }

    fun since(seq: Long): List<TraceEvent> = synchronized(lock) { events.filter { it.seq > seq } }

    fun ofTurn(turn: Int): List<TraceEvent> = synchronized(lock) { events.filter { it.turn == turn } }

    fun failures(): List<TraceEvent> = synchronized(lock) { events.filter { !it.ok } }

    fun toNdjson(): String = synchronized(lock) {
        val sb = StringBuilder()
        for (e in events) {
            sb.append("{\"seq\":").append(e.seq)
                .append(",\"turn\":").append(e.turn)
                .append(",\"kind\":\"").append(escape(e.kind))
                .append("\",\"name\":\"").append(escape(e.name))
                .append("\",\"durationMs\":").append(e.durationMs)
                .append(",\"ok\":").append(e.ok)
                .append(",\"summary\":\"").append(escape(e.summary))
                .append("\"}\n")
        }
        sb.toString()
    }

    fun clear() = synchronized(lock) { events.clear() }

    private fun clip(text: String): String {
        val flat = text.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= summaryLimit) flat else flat.take(summaryLimit - 1) + "…"
    }

    private fun sanitize(text: String, limit: Int): String =
        text.replace('\n', ' ').replace('\r', ' ').trim().let { if (it.length <= limit) it else it.take(limit) }

    private fun escape(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (c in text) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    companion object {
        val shared = TraceLog()

        fun record(kind: String, name: String, durationMs: Long, ok: Boolean, summary: String = ""): TraceEvent =
            shared.emit(0, kind, name, durationMs, ok, summary)

        fun exportNdjson(): String = shared.toNdjson()
    }
}
