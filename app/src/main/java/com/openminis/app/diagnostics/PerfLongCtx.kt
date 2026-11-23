package com.openminis.app.diagnostics

import com.openminis.app.logging.AppLogger
import java.util.concurrent.atomic.AtomicLong

object PerfLongCtx {

    private const val CATEGORY = "Perf"

    private val clickNsBySession = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val lastNsBySession = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val seq = AtomicLong(0)

    private val rowComposeCount = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
    private val rowComposeStartNs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val rowComposeTypes = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, AtomicLong>>()
    private val ROW_MILESTONES = longArrayOf(10, 50, 200)

    fun click(sessionId: String) {
        JankDiag.beginOpen(sessionId)
        val now = System.nanoTime()
        clickNsBySession[sessionId] = now
        lastNsBySession[sessionId] = now
        emit(sessionId, "click", elapsedMs = 0, extra = "seq=${seq.incrementAndGet()}")
    }

    fun step(sessionId: String, name: String, extra: String = "") {
        if (name in JANK_OPEN_STAGES) JankDiag.openStage(sessionId, name)
        val now = System.nanoTime()
        val last = lastNsBySession[sessionId] ?: clickNsBySession[sessionId] ?: now
        val elapsedMs = (now - last) / 1_000_000
        lastNsBySession[sessionId] = now
        emit(sessionId, name, elapsedMs, extra)
    }

    fun end(sessionId: String, name: String = "end", extra: String = "") {
        step(sessionId, name, extra)
        lastNsBySession.remove(sessionId)
    }

    fun maybeReportRowComposed(sessionId: String, itemClassName: String) {
        val counter = rowComposeCount.computeIfAbsent(sessionId) { AtomicLong(0) }
        val types = rowComposeTypes.computeIfAbsent(sessionId) { java.util.concurrent.ConcurrentHashMap() }
        types.computeIfAbsent(itemClassName) { AtomicLong(0) }.incrementAndGet()
        val now = counter.incrementAndGet()
        if (now == 1L) {
            rowComposeStartNs[sessionId] = System.nanoTime()
            return
        }
        var isMilestone = false
        for (m in ROW_MILESTONES) {
            if (now == m) { isMilestone = true; break }
        }
        if (isMilestone) {
            val startNs = rowComposeStartNs[sessionId] ?: return
            val ms = (System.nanoTime() - startNs) / 1_000_000
            val byType = types.entries.joinToString(",") { "${it.key}=${it.value.get()}" }
            step(
                sessionId,
                "rowsCompose.milestone",
                "rows=$now sinceFirstRowMs=$ms byType=$byType",
            )
        }
    }

    private fun emit(sessionId: String, name: String, elapsedMs: Long, extra: String) {
        val clickNs = clickNsBySession[sessionId]
        val sinceClickMs = if (clickNs != null) {
            (System.nanoTime() - clickNs) / 1_000_000
        } else {
            -1L
        }
        val mem = MemorySnapshot.capture()
        val extraPart = if (extra.isEmpty()) "" else " $extra"
        AppLogger.info(
            CATEGORY,
            "[Perf][LongCtx] step=$name session=$sessionId elapsedMs=$elapsedMs " +
                "sinceClickMs=$sinceClickMs ${mem.toLogString()}$extraPart",
        )
    }

    private val JANK_OPEN_STAGES = setOf(
        "chatScreen.mount", "buildFlatChatItems.firstBuild", "lazyColumn.firstLayout",
        "lazyColumn.firstItem.compose", "lazyColumn.firstItem.placed", "loadSession.exit",
    )
}
