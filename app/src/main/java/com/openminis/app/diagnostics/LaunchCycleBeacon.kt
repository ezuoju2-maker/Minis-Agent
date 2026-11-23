package com.openminis.app.diagnostics

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File

object LaunchCycleBeacon {

    private const val FILE_NAME = "launch-beacon.log"
    private const val TAG = "LaunchBeacon"

    @Volatile
    var lastCycleWasCrash: Boolean = false
        private set

    @Volatile
    var lastRestartCount: Int = 0
        private set

    const val RESTART_COUNT_FORCE_HOME_THRESHOLD: Int = 3

    fun shouldForceHomeOnLaunch(): Boolean =
        lastCycleWasCrash && lastRestartCount > RESTART_COUNT_FORCE_HOME_THRESHOLD

    fun recordLaunch(context: Context) {
        val file = beaconFile(context)
        val previousTail = readTail(file)
        val now = System.currentTimeMillis()
        val nowIso = isoLocal(now)

        val previousVerdict = classifyPrevious(previousTail, context, now)
        lastCycleWasCrash = previousVerdict.startsWith("crash_or_stall")
        AppLogger.info(TAG, "launch verdict for previous cycle: $previousVerdict")

        if (previousVerdict.startsWith("crash_or_stall")) {
            val restartCount = countRecentCrashLaunches(previousTail, context)
            lastRestartCount = restartCount
            val lastSessionId = readLastSessionId(context)
            AppLogger.warning(
                TAG,
                "[Perf][LongCtx] step=launchBeacon.crashOrStall " +
                    "reason=${previousVerdict.substringBefore(' ')} " +
                    "detail=${previousVerdict.substringAfter('(', "").substringBefore(')')} " +
                    "restartCount=$restartCount lastSessionId=$lastSessionId",
            )
        } else {
            lastRestartCount = 0
        }

        appendLine(file, "[$nowIso] launch pid=${android.os.Process.myPid()}")
    }

    private fun countRecentCrashLaunches(tail: String, context: Context): Int {
        val lines = tail.split('\n').filter { it.isNotBlank() }
        val launches = lines.count { it.contains(" launch ") }
        val cleanExits = lines.count { it.contains(" clean_exit ") }
        return (launches - cleanExits).coerceAtLeast(0)
    }

    private fun readLastSessionId(context: Context): String {
        return try {
            val logsDir = File(context.filesDir, "logs")
            val stall = logsDir.listFiles()
                ?.filter { it.name.startsWith("stall-") }
                ?.maxByOrNull { it.lastModified() }
                ?: return "unknown"
            val head = stall.bufferedReader().use { it.readText().take(2000) }
            Regex("session[=:]\\s*([0-9a-fA-F-]{8,})").find(head)?.groupValues?.get(1) ?: "unknown"
        } catch (_: Throwable) {
            "unknown"
        }
    }

    fun recordCleanExit(context: Context) {
        val now = System.currentTimeMillis()
        appendLine(beaconFile(context), "[${isoLocal(now)}] clean_exit pid=${android.os.Process.myPid()}")
    }

    private fun beaconFile(context: Context): File =
        File(File(context.filesDir, "logs").also { it.mkdirs() }, FILE_NAME)

    private fun readTail(file: File): String {
        if (!file.exists() || file.length() == 0L) return ""
        return try {
            val len = file.length()
            val start = (len - 16 * 1024).coerceAtLeast(0)
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val bytes = ByteArray((len - start).toInt())
                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "tail read failed: ${t.message}")
            ""
        }
    }

    private fun classifyPrevious(tail: String, context: Context, now: Long): String {
        if (tail.isBlank()) return "first_launch"
        val lines = tail.split('\n').filter { it.isNotBlank() }
        val lastLaunch = lines.lastOrNull { it.contains(" launch ") } ?: return "no_prior_launch"
        val lastCleanExit = lines.lastOrNull { it.contains(" clean_exit ") }
        val cleanExitAfterLaunch = lastCleanExit != null &&
            lines.indexOf(lastCleanExit) > lines.indexOf(lastLaunch)
        if (cleanExitAfterLaunch) return "clean_exit"

        val logsDir = File(context.filesDir, "logs")
        val previousLaunchMs = parseTs(lastLaunch) ?: return "ambiguous_no_timestamp"
        val crashLogs = logsDir.listFiles()?.filter { f ->
            val name = f.name
            (name.startsWith("crash-") || name.startsWith("native-crash-") || name.startsWith("stall-")) &&
                f.lastModified() in previousLaunchMs..now
        }.orEmpty()
        if (crashLogs.isNotEmpty()) {
            return "crash_or_stall (${crashLogs.joinToString { it.name }})"
        }
        val ageMs = now - previousLaunchMs
        return "silent_kill (uptime_was=${ageMs}ms)"
    }

    private fun parseTs(line: String): Long? {
        val open = line.indexOf('[')
        val close = line.indexOf(']')
        if (open != 0 || close <= 0) return null
        val iso = line.substring(1, close)
        return try {
            val fmt = java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                java.util.Locale.US,
            ).apply { timeZone = java.util.TimeZone.getDefault() }
            fmt.parse(iso)?.time
        } catch (_: Throwable) {
            null
        }
    }

    private fun isoLocal(ms: Long): String {
        val fmt = java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            java.util.Locale.US,
        ).apply { timeZone = java.util.TimeZone.getDefault() }
        return fmt.format(java.util.Date(ms))
    }

    private fun appendLine(file: File, line: String) {
        try {
            file.appendText(line + "\n")
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "append failed: ${t.message}")
        }
    }
}
