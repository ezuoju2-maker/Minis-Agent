package com.openminis.app.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExitInfoCollector {

    private const val TAG = "ExitInfoCollector"
    private const val PREFS = "exit_info_collector"

    internal const val KEY_HIGH_WATER = "last_handled_timestamp_ms"

    internal const val MAX_RECORDS = 16

    internal const val MAX_TRACE_BYTES = 256 * 1024

    private val STAMP_FMT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    fun collect(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val records = am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS)
            if (records.isEmpty()) return

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val highWater = prefs.getLong(KEY_HIGH_WATER, 0L)
            var newest = highWater
            var written = 0

            for (info in records) {
                if (info.timestamp <= highWater) continue
                if (info.timestamp > newest) newest = info.timestamp
                if (!isInteresting(info.reason)) continue
                if (writeReport(context, info)) written++
            }

            if (newest > highWater) {
                prefs.edit().putLong(KEY_HIGH_WATER, newest).apply()
            }
            if (written > 0) {
                Log.i(TAG, "wrote $written exit report(s) to filesDir/logs")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "collect failed: ${t.message}")
        }
    }

    internal fun isInteresting(reason: Int): Boolean =
        reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            reason == ApplicationExitInfo.REASON_CRASH ||
            reason == ApplicationExitInfo.REASON_ANR

    internal fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "reason=$reason"
    }

    private fun writeReport(context: Context, info: ApplicationExitInfo): Boolean {
        val dir = File(context.filesDir, "logs").also { it.mkdirs() }
        val out = File(dir, "exitinfo-${STAMP_FMT.format(Date(info.timestamp))}.log")
        if (out.exists()) return false

        val body = buildString {
            appendLine("=== Minis Process Exit Report ===")
            appendLine("Collected: ${STAMP_FMT.format(Date())}")
            appendLine("Exit time: ${STAMP_FMT.format(Date(info.timestamp))}")
            appendLine("Reason: ${reasonName(info.reason)}")
            appendLine("Status: ${info.status}")
            appendLine("Importance: ${info.importance}")
            appendLine("PID: ${info.pid}  Process: ${info.processName}")
            appendLine("PSS: ${info.pss} kB  RSS: ${info.rss} kB")
            appendLine()
            appendLine("Abort message / description:")
            appendLine(info.description ?: "(none provided by the platform)")
            appendLine()
            appendLine(lastResourceSample(context, info))

            if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                val trace = readTrace(info)
                appendLine()
                appendLine("--- Tombstone summary (whole trace) ---")
                appendLine(trace?.summary ?: "(no trace available)")
                appendLine()
                appendLine("--- Tombstone (truncated to ${MAX_TRACE_BYTES / 1024} kB) ---")
                appendLine(trace?.head?.takeIf { it.isNotEmpty() }?.let { String(it, Charsets.UTF_8) } ?: "(no trace available)")
            }
        }

        return try {
            out.writeText(body)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "write failed for ${out.name}: ${t.message}")
            false
        }
    }

    internal fun lastResourceSample(context: Context, info: ApplicationExitInfo): String {
        val (text, modifiedMs) = com.openminis.app.diagnostics.ResourceWatermark.readWatermark(context.filesDir, info.pid)
            ?: return "Last resource sample: (none for pid ${info.pid})"
        return formatResourceSample(text, (info.timestamp - modifiedMs) / 1000)
    }

    internal fun formatResourceSample(text: String, secondsBeforeExit: Long): String =
        "Last resource sample (${secondsBeforeExit}s before exit):\n" + text.trimEnd()

    private fun readTrace(info: ApplicationExitInfo): TombstoneSummary.Result? = try {
        info.traceInputStream?.use { stream -> TombstoneSummary.read(stream.buffered(), MAX_TRACE_BYTES) }
    } catch (t: Throwable) {
        Log.w(TAG, "traceInputStream failed: ${t.message}")
        null
    }
}
