package com.openminis.app.diagnostics

import com.openminis.app.logging.AppLogger

object LargeAllocProbe {

    private const val TAG = "MemProbe"

    const val NOTABLE_BYTES = 512 * 1024

    inline fun <T> around(
        label: String,
        sizeBytes: Long,
        detail: String = "",
        block: () -> T,
    ): T {
        if (sizeBytes < NOTABLE_BYTES) return block()

        val before = MemorySnapshot.capture()
        val startNs = System.nanoTime()
        try {
            val result = block()
            report(label, sizeBytes, detail, before, startNs, failure = null)
            return result
        } catch (t: Throwable) {
            report(label, sizeBytes, detail, before, startNs, failure = t)
            throw t
        }
    }

    fun report(
        label: String,
        sizeBytes: Long,
        detail: String,
        before: MemorySnapshot,
        startNs: Long,
        failure: Throwable?,
    ) {
        val after = MemorySnapshot.capture()
        val ms = (System.nanoTime() - startNs) / 1_000_000
        val deltaJava = after.javaHeapMB - before.javaHeapMB
        val deltaRss = if (before.rssMB >= 0 && after.rssMB >= 0) after.rssMB - before.rssMB else -1

        val head = "[MemProbe] $label sizeBytes=$sizeBytes (${sizeBytes / 1024}KB) " +
            "tookMs=$ms deltaJavaMB=$deltaJava deltaRssMB=$deltaRss " +
            "before[${before.toLogString()}] after[${after.toLogString()}]" +
            if (detail.isEmpty()) "" else " $detail"

        when {
            failure != null -> {
                AppLogger.error(TAG, "$head FAILED=${failure.javaClass.name}: ${failure.message}")
                AppLogger.error(TAG, "[MemProbe] $label stack:\n${stackOf(failure)}")
            }
            after.javaHeapUsedFraction >= MemorySnapshot.HEAP_PRESSURE_FRACTION ->
                AppLogger.warning(TAG, "$head HEAP_PRESSURE")
            else -> AppLogger.info(TAG, head)
        }
    }

    fun stackOf(t: Throwable): String {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        return sw.toString().lineSequence().take(60).joinToString("\n")
    }
}
