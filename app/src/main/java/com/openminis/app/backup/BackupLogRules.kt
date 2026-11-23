package com.openminis.app.backup

internal object BackupLogRules {

    fun append(log: MutableList<BackupHistory.LogEntry>, entry: BackupHistory.LogEntry): Boolean {
        val last = log.lastOrNull()
        if (last?.message == entry.message) return false
        if (last?.isTransient == true) log.removeAt(log.lastIndex)
        log.add(entry)
        return true
    }


    const val MAX_LOG_LINES = 200

    const val OMITTED_MARKER_PREFIX = "[omitted-earlier-lines]:"

    private val PROGRESS_LINE = Regex("""^(Saving|Sending) to .+… \d{1,3}%$""")

    private fun isProgress(e: BackupHistory.LogEntry) = e.isTransient || PROGRESS_LINE.matches(e.message)

    fun omittedCount(e: BackupHistory.LogEntry): Int? =
        e.message.takeIf { it.startsWith(OMITTED_MARKER_PREFIX) }
            ?.removePrefix(OMITTED_MARKER_PREFIX)?.toIntOrNull()

    fun sanitize(log: List<BackupHistory.LogEntry>): List<BackupHistory.LogEntry> {
        val merged = ArrayList<BackupHistory.LogEntry>(minOf(log.size, 1_024))
        for (e in log) {
            if (!isProgress(e)) {
                merged.add(e)
                continue
            }
            val line = if (e.isTransient) e else e.copy(isTransient = true)
            if (merged.isNotEmpty() && isProgress(merged.last())) merged[merged.lastIndex] = line
            else merged.add(line)
        }
        val earlier = merged.firstOrNull()?.let(::omittedCount)
        val body = if (earlier != null) merged.subList(1, merged.size) else merged
        if (body.size <= MAX_LOG_LINES) return merged
        val dropped = body.size - MAX_LOG_LINES
        val kept = body.subList(dropped, body.size)
        val note = BackupHistory.LogEntry(
            at = kept.first().at,
            message = OMITTED_MARKER_PREFIX + ((earlier ?: 0) + dropped),
        )
        return listOf(note) + kept
    }
}

internal class PercentGate(
    private val minIntervalMs: Long = 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastPct = Int.MIN_VALUE
    private var lastAt = 0L

    @Synchronized
    fun admit(pct: Int): Boolean {
        if (pct == lastPct) return false
        val now = clock()
        if (lastPct != Int.MIN_VALUE && now - lastAt < minIntervalMs) return false
        lastPct = pct
        lastAt = now
        return true
    }
}

internal class ThrottledWriter(
    private val intervalMs: Long = 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val schedule: (delayMs: Long, block: () -> Unit) -> Unit,
    private val write: () -> Unit,
) {
    private val lock = Any()
    private var lastWriteAt = 0L
    private var written = false
    private var dirty = false
    private var trailingScheduled = false
    private var closed = false

    fun request() = synchronized(lock) {
        if (closed) return
        val now = clock()
        if (!written || now - lastWriteAt >= intervalMs) {
            doWrite(now)
            return
        }
        dirty = true
        if (!trailingScheduled) {
            trailingScheduled = true
            schedule(intervalMs - (now - lastWriteAt)) { trailing() }
        }
    }

    fun close() = synchronized(lock) {
        doWrite(clock())
        closed = true
    }

    private fun trailing() = synchronized(lock) {
        trailingScheduled = false
        if (closed || !dirty) return
        doWrite(clock())
    }

    private fun doWrite(now: Long) {
        dirty = false
        written = true
        lastWriteAt = now
        write()
    }
}
