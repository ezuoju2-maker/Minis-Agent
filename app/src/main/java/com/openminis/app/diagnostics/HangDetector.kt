package com.openminis.app.diagnostics

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

object HangDetector {

    private const val TAG = "HangDetector"

    private const val HEARTBEAT_INTERVAL_MS = 1_000L

    private const val HANG_THRESHOLD_MS = 3_000L

    private const val MID_HANG_RESAMPLE_MS = 3_000L

    private const val HANG_LIMIT_FOR_BREAKER = 3

    private const val MAX_PLAUSIBLE_GAP_MS = 5 * 60_000L

    private const val PEER_THREAD_LIMIT = 16
    private const val PEER_STACK_DEPTH = 30

    private const val RESET_AFTER_QUIET_MS = 10_000L

    private const val PREFS_NAME = "hang_detector_prefs"
    private const val KEY_HANG_COUNT = "hang_count"
    private const val KEY_LAST_HANG_AT = "last_hang_at_ms"

    private const val STALL_LOG_DIR = "logs"
    private const val STALL_LOG_PREFIX = "stall-"

    internal const val STALL_STACK_DEPTH = 120

    internal const val APP_FRAME_PREFIX = "com.openminis.app."

    @Volatile
    var currentScreen: String = "unknown"
        private set

    fun noteScreen(route: String?) {
        currentScreen = route?.takeIf { it.isNotBlank() } ?: "unknown"
    }

    internal fun firstAppFrame(stack: Array<StackTraceElement>): String? {
        val frame = stack.firstOrNull { it.className.startsWith(APP_FRAME_PREFIX) } ?: return null
        return "${frame.className.removePrefix(APP_FRAME_PREFIX)}.${frame.methodName}:${frame.lineNumber}"
    }

    internal fun attributionFields(screen: String, stack: Array<StackTraceElement>): String =
        " screen=$screen appFrame=${firstAppFrame(stack) ?: "none"}"
    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val TIMESTAMP_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)

    private val lastHeartbeatAt = AtomicLong(0L)
    private val lastLogAt = AtomicLong(0L)

    private var appContext: Context? = null

    @Volatile private var backgrounded = false

    internal fun notAHangReason(
        sinceMs: Long,
        backgrounded: Boolean,
        visibleNow: () -> Boolean,
        mainIdle: () -> Boolean,
    ): String? = when {
        backgrounded && !visibleNow() -> "app in the background (not visible)"
        sinceMs > MAX_PLAUSIBLE_GAP_MS -> "gap beyond ${MAX_PLAUSIBLE_GAP_MS}ms (process frozen/suspended)"
        mainIdle() -> "main thread idle in the looper"
        else -> null
    }

    internal const val WATCHDOG_SUSPENDED_MS = 5_000L

    internal fun judgedGapMs(now: Long, lastHeartbeat: Long, resumedAt: Long, hangActive: Boolean): Long =
        if (hangActive) now - lastHeartbeat else now - maxOf(lastHeartbeat, resumedAt)

    private fun visibleNow(): Boolean = try {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        info.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            info.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    } catch (t: Throwable) {
        false
    }

    private fun observeProcessLifecycle() {
        mainHandler.post {
            runCatching {
                val lifecycle = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle
                lifecycle.addObserver(
                    androidx.lifecycle.LifecycleEventObserver { source, _ ->
                        val fg = source.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
                        if (fg && backgrounded) lastHeartbeatAt.set(SystemClock.uptimeMillis())
                        if (backgrounded == !fg) return@LifecycleEventObserver
                        backgrounded = !fg
                        println("[T-HANG-DIAG] HangDetector ${if (fg) "foreground - watching" else "background - gaps not counted"}")
                    },
                )
            }.onFailure { println("[T-HANG-DIAG] HangDetector lifecycle observer failed: ${it.message}") }
        }
    }

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        appContext = context.applicationContext
        lastHeartbeatAt.set(SystemClock.uptimeMillis())
        scheduleHeartbeat()
        observeProcessLifecycle()
        thread(name = "HangDetector-watch", isDaemon = false) { watchLoop() }
        val banner = "[T-HANG-DIAG] HangDetector started: threshold=${HANG_THRESHOLD_MS}ms " +
            "interval=${HEARTBEAT_INTERVAL_MS}ms limit=$HANG_LIMIT_FOR_BREAKER"
        println(banner)
        Log.i(TAG, banner)
    }

    fun markHealthyTick() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_HANG_COUNT, 0) == 0) return
        val lastHangAt = prefs.getLong(KEY_LAST_HANG_AT, 0L)
        if (lastHangAt > 0 && System.currentTimeMillis() - lastHangAt < RESET_AFTER_QUIET_MS) return
        prefs.edit()
            .putInt(KEY_HANG_COUNT, 0)
            .putLong(KEY_LAST_HANG_AT, 0L)
            .apply()
        Log.i(TAG, "hang count reset after quiet period")
    }

    fun shouldForceHomeOnLaunch(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_HANG_COUNT, 0) >= HANG_LIMIT_FOR_BREAKER
    }

    fun resetHangCount(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_HANG_COUNT, 0)
            .putLong(KEY_LAST_HANG_AT, 0L)
            .apply()
    }

    fun currentHangCount(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_HANG_COUNT, 0)


    private fun scheduleHeartbeat() {
        mainHandler.postDelayed({
            lastHeartbeatAt.set(SystemClock.uptimeMillis())
            scheduleHeartbeat()
        }, HEARTBEAT_INTERVAL_MS)
    }

    private fun watchLoop() {
        println("[T-HANG-DIAG] HangDetector watchLoop entered")
        var ticks = 0L
        var hangActive = false
        var lastSampleAt = 0L
        var escalation = 0
        var episodePeakSinceMs = 0L
        var skipLogged = false
        var lastTickAt = SystemClock.uptimeMillis()
        var resumedAt = 0L
        while (true) {
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                return
            }
            ticks++
            val bg = backgrounded
            val now = SystemClock.uptimeMillis()
            val tickGap = now - lastTickAt
            lastTickAt = now
            if (tickGap > WATCHDOG_SUSPENDED_MS) {
                resumedAt = now
                println("[T-HANG-DIAG] HangDetector watchdog was suspended ${tickGap}ms (process frozen) - that time is not a hang")
            }
            val since = judgedGapMs(now, lastHeartbeatAt.get(), resumedAt, hangActive)
            if (ticks % 30L == 0L) {
                println("[T-HANG-DIAG] HangDetector tick=$ticks sinceHeartbeat=${since}ms")
            }
            if (since < HANG_THRESHOLD_MS) {
                skipLogged = false
                if (hangActive) {
                    hangActive = false
                    writeStallSample("post-recovery", episodePeakSinceMs, escalation)
                    println(
                        "[T-HANG-DIAG] hang episode ENDED peak=${episodePeakSinceMs}ms midHangSamples=${escalation + 1}",
                    )
                }
                continue
            }
            if (!hangActive) {
                val notAHang = notAHangReason(since, bg, ::visibleNow) { isIdleMainStack(mainThreadStack()) }
                if (notAHang != null) {
                    if (!skipLogged) {
                        skipLogged = true
                        println("[T-HANG-DIAG] heartbeat gap ${since}ms NOT counted: $notAHang")
                    }
                    continue
                }
                hangActive = true
                escalation = 0
                episodePeakSinceMs = since
                lastSampleAt = now
                lastLogAt.set(now)
                recordHang(durationMs = since)
                continue
            }
            episodePeakSinceMs = maxOf(episodePeakSinceMs, since)
            if (now - lastSampleAt >= MID_HANG_RESAMPLE_MS) {
                lastSampleAt = now
                escalation++
                writeStallSample("mid-hang", since, escalation)
            }
        }
    }

    private fun writeStallSample(label: String, durationMs: Long, escalation: Int) {
        val ctx = appContext ?: return
        val mainStack = try {
            Looper.getMainLooper().thread.stackTrace
        } catch (t: Throwable) {
            arrayOf<StackTraceElement>()
        }
        val ts = TIMESTAMP_FORMAT.format(Date())
        val date = DATE_FORMAT.format(Date())
        val builder = StringBuilder()
        builder.append(
            "===== HANG @ $ts (duration ~${durationMs}ms) sample=$label escalation=$escalation =====\n",
        )
        builder.append("thread: main screen=$currentScreen\n")
        for (frame in mainStack.take(STALL_STACK_DEPTH)) builder.append("  at $frame\n")
        builder.append("\n")

        val top5 = mainStack.take(5).joinToString(" <- ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
        val renderFields = ContentDiag.currentRenderLogFields()
        val attribution = attributionFields(currentScreen, mainStack)
        println(
            "[T-HANG-DIAG][JankDiag] sample=$label escalation=$escalation duration=${durationMs}ms top5: $top5$renderFields$attribution",
        )

        try {
            val dir = File(ctx.filesDir, STALL_LOG_DIR).also { it.mkdirs() }
            val file = File(dir, "$STALL_LOG_PREFIX$date.log")
            FileWriter(file,  true).use { it.write(builder.toString()) }
        } catch (t: Throwable) {
            val msg = "[T-HANG-DIAG] FAILED to write stall log: ${t.javaClass.simpleName}: ${t.message}"
            println(msg)
            Log.w(TAG, msg)
        }
    }

    private fun recordHang(durationMs: Long) {
        val ctx = appContext ?: return
        writeStallSample("mid-hang", durationMs, escalation = 0)

        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val newCount = prefs.getInt(KEY_HANG_COUNT, 0) + 1
        prefs.edit()
            .putInt(KEY_HANG_COUNT, newCount)
            .putLong(KEY_LAST_HANG_AT, System.currentTimeMillis())
            .apply()
        writePeerThreads()
        val tail = "hang detected duration=${durationMs}ms count=$newCount " +
            "launchBreaker=${newCount >= HANG_LIMIT_FOR_BREAKER}"
        println("[T-HANG-DIAG] $tail")
        Log.w(TAG, tail)
    }


    private fun mainThreadStack(): Array<StackTraceElement> = try {
        Looper.getMainLooper().thread.stackTrace
    } catch (t: Throwable) {
        arrayOf()
    }

    internal fun isIdleMainStack(stack: Array<StackTraceElement>): Boolean {
        val top = stack.firstOrNull() ?: return false
        return top.className == "android.os.MessageQueue" && top.methodName == "nativePollOnce"
    }

    private fun writePeerThreads() {
        val ctx = appContext ?: return
        try {
            val main = Looper.getMainLooper().thread
            val peers = Thread.getAllStackTraces().entries
                .filter { (t, st) ->
                    t !== main && st.isNotEmpty() &&
                        (t.state == Thread.State.BLOCKED || st.any { it.className.startsWith(APP_FRAME_PREFIX) })
                }
                .sortedBy { (t, _) -> if (t.state == Thread.State.BLOCKED) 0 else 1 }
                .take(PEER_THREAD_LIMIT)
            val b = StringBuilder("---- other app threads (${peers.size}) ----\n")
            for ((t, st) in peers) {
                b.append("thread: ${t.name} state=${t.state}\n")
                for (frame in st.take(PEER_STACK_DEPTH)) b.append("  at $frame\n")
            }
            b.append("\n")
            val dir = File(ctx.filesDir, STALL_LOG_DIR).also { it.mkdirs() }
            FileWriter(File(dir, "$STALL_LOG_PREFIX${DATE_FORMAT.format(Date())}.log"), true)
                .use { it.write(b.toString()) }
        } catch (t: Throwable) {
            println("[T-HANG-DIAG] peer thread dump failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
