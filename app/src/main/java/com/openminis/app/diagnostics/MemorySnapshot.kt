package com.openminis.app.diagnostics

import android.os.Debug
import java.io.File

data class MemorySnapshot(
    val javaHeapMB: Long,
    val javaHeapMaxMB: Long,
    val rssMB: Long,
    val rssPeakMB: Long,
    val vmSizeMB: Long,
    val nativeHeapRawMB: Long,
) {
    val javaHeapUsedFraction: Double
        get() = if (javaHeapMaxMB > 0) javaHeapMB.toDouble() / javaHeapMaxMB else 0.0

    fun toLogString(): String =
        "javaHeapMB=$javaHeapMB/$javaHeapMaxMB (${(javaHeapUsedFraction * 100).toInt()}%) " +
            "rssMB=$rssMB peakRssMB=$rssPeakMB vmSizeMB=$vmSizeMB nativeRawMB=$nativeHeapRawMB"

    companion object {
        const val HEAP_PRESSURE_FRACTION = 0.80

        fun capture(): MemorySnapshot {
            val rt = Runtime.getRuntime()
            val mb = 1024L * 1024L
            var rssKb = -1L
            var peakKb = -1L
            var vmKb = -1L
            try {
                File("/proc/self/status").useLines { lines ->
                    for (line in lines) {
                        when {
                            line.startsWith("VmRSS:") -> rssKb = parseKb(line)
                            line.startsWith("VmHWM:") -> peakKb = parseKb(line)
                            line.startsWith("VmSize:") -> vmKb = parseKb(line)
                        }
                        if (rssKb >= 0 && peakKb >= 0 && vmKb >= 0) break
                    }
                }
            } catch (_: Throwable) {
            }
            return MemorySnapshot(
                javaHeapMB = (rt.totalMemory() - rt.freeMemory()) / mb,
                javaHeapMaxMB = rt.maxMemory() / mb,
                rssMB = if (rssKb >= 0) rssKb / 1024 else -1,
                rssPeakMB = if (peakKb >= 0) peakKb / 1024 else -1,
                vmSizeMB = if (vmKb >= 0) vmKb / 1024 else -1,
                nativeHeapRawMB = Debug.getNativeHeapAllocatedSize() / mb,
            )
        }

        private fun parseKb(line: String): Long {
            val digits = line.filter { it.isDigit() }
            return digits.toLongOrNull() ?: -1L
        }
    }
}
