package com.openminis.app.ui.chat

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.system.Os
import android.system.OsConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

class SystemResourceMonitor {
    @Volatile var cpuUsage: Float = 0f
        private set
    @Volatile var memUsedBytes: Long = 0L
        private set
    @Volatile var memTotalBytes: Long = 0L
        private set

    private var prevCpuTicks: Long? = null
    private var prevSampleNanos: Long? = null

    private val recentDeltas = ArrayDeque<Pair<Long, Long>>()
    private val windowSize = 5

    private val clockTicksPerSec: Long = runCatching {
        Os.sysconf(OsConstants._SC_CLK_TCK)
    }.getOrNull()?.takeIf { it > 0 } ?: 100L

    @Synchronized
    fun sampleOnce(context: Context) {
        sampleCpu()
        sampleMemory(context)
    }

    @Synchronized
    fun reset() {
        prevCpuTicks = null
        prevSampleNanos = null
        recentDeltas.clear()
        cpuUsage = 0f
    }

    private fun sampleCpu() {
        val ticks = readSelfCpuTicks() ?: return
        val nowNanos = System.nanoTime()
        val prevTicks = prevCpuTicks
        val prevNanos = prevSampleNanos
        if (prevTicks != null && prevNanos != null) {
            val tickDelta = ticks - prevTicks
            val wallNanos = nowNanos - prevNanos
            if (tickDelta >= 0 && wallNanos > 0) {
                recentDeltas.addLast(tickDelta to wallNanos)
                while (recentDeltas.size > windowSize) recentDeltas.removeFirst()

                var sumTicks = 0L
                var sumNanos = 0L
                for ((t, n) in recentDeltas) { sumTicks += t; sumNanos += n }
                val sumWallSec = sumNanos / 1_000_000_000.0
                if (sumWallSec > 0) {
                    val raw = sumTicks.toDouble() /
                        (clockTicksPerSec.toDouble() * sumWallSec)
                    cpuUsage = (raw * 100.0).toFloat()
                }
            }
        }
        prevCpuTicks = ticks
        prevSampleNanos = nowNanos
    }

    private fun readSelfCpuTicks(): Long? = try {
        val raw = File("/proc/self/stat").readText()
        val rparen = raw.lastIndexOf(')')
        if (rparen < 0 || rparen + 2 >= raw.length) {
            null
        } else {
            val tail = raw.substring(rparen + 2)
                .split(' ')
                .filter { it.isNotEmpty() }
            val utime = tail[11].toLong()
            val stime = tail[12].toLong()
            utime + stime
        }
    } catch (_: Throwable) { null }

    private fun sampleMemory(context: Context) {
        val pssKb = try {
            parseTotalPssKb(File("/proc/self/smaps_rollup").readText())
        } catch (_: Throwable) { null }
            ?: try {
                val mi = Debug.MemoryInfo()
                Debug.getMemoryInfo(mi)
                mi.totalPss.toLong()
            } catch (_: Throwable) { null }
        if (pssKb != null) memUsedBytes = pssKb * 1024L
        if (memTotalBytes == 0L) try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val sysMem = ActivityManager.MemoryInfo()
                am.getMemoryInfo(sysMem)
                memTotalBytes = sysMem.totalMem
            }
        } catch (_: Throwable) {  }
    }

    companion object {
        internal fun parseTotalPssKb(rollup: String): Long? {
            var pss: Long? = null
            var swapPss = 0L
            for (line in rollup.lineSequence()) {
                val t = line.trim()
                val value = t.substringAfter(':', "").trim().substringBefore(' ').toLongOrNull() ?: continue
                when (t.substringBefore(':')) {
                    "Pss" -> pss = value
                    "SwapPss" -> swapPss = value
                }
            }
            return pss?.let { it + swapPss }
        }
    }

    fun formattedCpu(): String =
        String.format("CPU %.0f%%", cpuUsage.coerceAtLeast(0f))

    fun formattedMem(compact: Boolean = false): String {
        val usedGB = memUsedBytes / 1_073_741_824.0
        if (compact) return String.format("MEM %.1fG", usedGB)
        val totalGB = memTotalBytes / 1_073_741_824.0
        return String.format("MEM %.1f/%.1f GB", usedGB, totalGB)
    }
}

@Composable
fun rememberSystemResourceMonitor(active: Boolean): SystemResourceMonitor {
    val context = LocalContext.current
    val monitor = remember { SystemResourceMonitor() }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(active) {
        if (!active) {
            monitor.reset()
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) { monitor.sampleOnce(context) }
        tick++
        while (isActive) {
            delay(2000)
            withContext(Dispatchers.IO) { monitor.sampleOnce(context) }
            tick++
        }
    }
    @Suppress("UNUSED_VARIABLE") val t = tick
    return monitor
}
