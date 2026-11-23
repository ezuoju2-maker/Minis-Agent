package com.openminis.app.diagnostics

import android.os.Debug
import com.openminis.app.logging.AppLogger

object StreamPerfMonitor {

    private const val TAG = "StreamPerf"

    private var active = false
    private var sessionId: String = ""
    private var turnStartNs = 0L
    private var tickCount = 0L
    private var flattenTotalNs = 0L
    private var flattenMaxNs = 0L
    private var frozenHitTicks = 0L
    private var lastFrozenRows = 0
    private var lastLiveRows = 0
    private var gcCountStart = 0L
    private var gcFreedStart = 0L

    private fun runtimeStat(key: String): Long =
        Debug.getRuntimeStat(key)?.toLongOrNull() ?: 0L

    fun turnStart(sid: String) {
        if (active && sid == sessionId) return
        if (active) turnEnd()
        active = true
        sessionId = sid
        turnStartNs = System.nanoTime()
        tickCount = 0
        flattenTotalNs = 0
        flattenMaxNs = 0
        frozenHitTicks = 0
        lastFrozenRows = 0
        lastLiveRows = 0
        gcCountStart = runtimeStat("art.gc.gc-count")
        gcFreedStart = runtimeStat("art.gc.bytes-freed")
    }

    fun tick(flattenNanos: Long, frozenReused: Boolean, frozenRows: Int, liveRows: Int) {
        if (!active) return
        tickCount++
        flattenTotalNs += flattenNanos
        if (flattenNanos > flattenMaxNs) flattenMaxNs = flattenNanos
        if (frozenReused) frozenHitTicks++
        lastFrozenRows = frozenRows
        lastLiveRows = liveRows
    }

    fun turnEnd() {
        if (!active) return
        active = false
        val turnMs = (System.nanoTime() - turnStartNs) / 1_000_000
        val gcCountDelta = runtimeStat("art.gc.gc-count") - gcCountStart
        val gcFreedDeltaMb = (runtimeStat("art.gc.bytes-freed") - gcFreedStart) / 1_000_000.0
        val avgUs = if (tickCount > 0) flattenTotalNs / tickCount / 1_000 else 0
        AppLogger.info(
            TAG,
            "turn sid=$sessionId ticks=$tickCount flattenAvgUs=$avgUs " +
                "flattenMaxMs=${flattenMaxNs / 1_000_000} frozenHits=$frozenHitTicks/$tickCount " +
                "rowsLast=${lastFrozenRows + lastLiveRows}(frozen=$lastFrozenRows,live=$lastLiveRows) " +
                "gcCount=+$gcCountDelta gcFreedMB=+${String.format(java.util.Locale.US, "%.1f", gcFreedDeltaMb)} " +
                "turnS=${turnMs / 1000}",
        )
    }
}
