package com.openminis.app.diagnostics

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.view.Display
import android.view.FrameMetrics
import android.view.Window
import com.openminis.app.AppBuildInfo
import com.openminis.app.logging.AppLogger
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object JankDiag {
    private const val TAG = "JankDiag"
    private const val WINDOW_MS = 5_000L
    private const val HEARTBEAT_EVERY = 6
    private const val STALL_MS = 100L
    private const val STACK_EVERY_MS = 10_000L
    private const val TRANSITION_MS = 1_500L
    private const val WATCH_MS = 50L
    private const val BACKGROUND_RES_EVERY = 10

    private val thread = HandlerThread("JankDiag", Process.THREAD_PRIORITY_BACKGROUND)
    private lateinit var bg: Handler
    private lateinit var app: Application
    @Volatile private var started = false

    @Volatile private var screen = "start"
    @Volatile private var routeLabel = "start"
    @Volatile private var overlayLabel: String? = null
    @Volatile private var streaming = false
    private val resumed = AtomicInteger(0)
    @Volatile private var msgStartNs = 0L
    private val msgSeq = AtomicLong(0)
    private val mainBusyNs = AtomicLong(0)
    @Volatile private var longestMsgNs = 0L
    private val streamChunks = AtomicInteger(0)
    private val streamPublishes = AtomicInteger(0)
    @Volatile private var streamChars = 0
    private val newestRecomposes = AtomicInteger(0)
    @Volatile private var cancelStartNs = 0L

    private val window = JankFrameWindow()
    private val beat = JankFrameWindow()
    private val transition = JankFrameWindow()
    private var transitionLabel: String? = null
    private var transitionEndsNs = 0L
    private val parse = JankMsSamples()
    private var windows = 0
    private var windowHadStreaming = false
    private var lastStackAtNs = 0L
    private var sampledSeq = -1L
    private val sampledStacks = HashMap<Long, String>()
    private var suppressedStalls = 0
    private var lastGc = LongArray(4)
    private var lastCpuMs = 0L
    private var pendingCancel: Pair<Long, Long>? = null
    private var displayChanges = 0
    private var lastDisplayLine = ""
    private var displayReportPending = false
    private val open = OpenTimeline()
    private val frameListeners = HashMap<Window, Window.OnFrameMetricsAvailableListener>()

    fun start(application: Application) {
        if (started) return
        started = true
        app = application
        thread.start()
        bg = Handler(thread.looper)
        installLooperMonitor()
        application.registerActivityLifecycleCallbacks(lifecycle)
        bg.post {
            banner()
            ResourceWatermark.pruneWatermarks(app.filesDir, Process.myPid())
            registerSystemListeners()
            bg.postDelayed(tick, WINDOW_MS)
            bg.postDelayed(watchdog, WATCH_MS)
        }
    }

    fun onLoggingStarted() {
        if (started) bg.post { banner() }
    }


    fun setScreen(route: String?) {
        routeLabel = jankScreenLabel(route)
        applyScreen()
    }

    fun enterScreen(label: String) {
        overlayLabel = label
        applyScreen()
    }

    fun leaveScreen(label: String) {
        if (overlayLabel == label) overlayLabel = null
        applyScreen()
    }

    private fun applyScreen() {
        val label = overlayLabel ?: routeLabel
        val from = screen
        if (label == from) return
        screen = label
        if (!started) return
        val now = System.nanoTime()
        bg.post {
            AppLogger.info(TAG, "[JankDiag] route $from -> $label streaming=$streaming")
            transition.reset()
            transitionLabel = "$from->$label"
            transitionEndsNs = now + TRANSITION_MS * 1_000_000
            bg.postDelayed({ reportTransition() }, TRANSITION_MS + 150)
        }
    }

    fun setStreaming(active: Boolean) { streaming = active }

    fun onStreamChunk(totalChars: Int) { streamChunks.incrementAndGet(); streamChars = totalChars }

    fun onStreamPublish() { streamPublishes.incrementAndGet() }

    fun onNewestRowRecompose() { newestRecomposes.incrementAndGet() }

    fun onStreamParse(fragLen: Int, ms: Double) {
        if (started) bg.post { parse.add(ms); streamChars = maxOf(streamChars, fragLen) }
    }

    fun beginOpen(sessionId: String) {
        if (!started) return
        val t = System.nanoTime()
        val busy = mainBusyNs.get()
        longestMsgNs = 0L
        bg.post { open.begin(sessionId, t, busy) }
    }

    fun openStage(sessionId: String, stage: String) {
        if (!started) return
        val t = System.nanoTime()
        bg.post { open.stage(sessionId, stage, t) }
    }

    fun openStable(sessionId: String, messages: Int, rows: Int, prewarmMs: Long) {
        if (!started) return
        val t = System.nanoTime(); val busy = mainBusyNs.get(); val longest = longestMsgNs
        bg.post { open.stable(sessionId, t, busy, longest, messages, rows, prewarmMs)?.let { AppLogger.info(TAG, it) } }
    }

    fun onCancelStreamStart() { cancelStartNs = System.nanoTime() }

    fun onCancelStreamEnd() {
        val start = cancelStartNs
        if (!started || start == 0L) return
        val end = System.nanoTime()
        bg.post { pendingCancel = start to end }
    }


    private fun installLooperMonitor() {
        Looper.getMainLooper().setMessageLogging { line ->
            if (line.startsWith(">>>>>")) {
                msgSeq.incrementAndGet()
                msgStartNs = System.nanoTime()
            } else if (line.startsWith("<<<<<")) {
                val start = msgStartNs
                msgStartNs = 0L
                if (start != 0L) {
                    val d = System.nanoTime() - start
                    mainBusyNs.addAndGet(d)
                    if (d > longestMsgNs) longestMsgNs = d
                    if (d > STALL_MS * 1_000_000) {
                        val seq = msgSeq.get()
                        val label = screen
                        val s = streaming
                        bg.post { reportStall(seq, d, label, s) }
                    }
                }
            }
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            val start = msgStartNs
            val now = System.nanoTime()
            val seq = msgSeq.get()
            if (start != 0L && now - start > STALL_MS * 1_000_000 && seq != sampledSeq &&
                now - lastStackAtNs > STACK_EVERY_MS * 1_000_000
            ) {
                sampledSeq = seq
                lastStackAtNs = now
                sampledStacks[seq] = jankStackLine(Looper.getMainLooper().thread.stackTrace)
            }
            bg.postDelayed(this, if (resumed.get() > 0) WATCH_MS else WATCH_MS * 20)
        }
    }

    private fun reportStall(seq: Long, durationNs: Long, label: String, streaming: Boolean) {
        val stack = sampledStacks.remove(seq)
        if (stack == null && durationNs < 1_000_000_000L) {
            suppressedStalls++
            return
        }
        AppLogger.info(
            TAG,
            "[JankDiag] mainStall ms=${durationNs / 1_000_000} screen=$label streaming=$streaming" +
                (stack?.let { " stack=$it" } ?: " stack=(not sampled)"),
        )
    }


    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumed.incrementAndGet()
            val w = activity.window
            if (frameListeners.containsKey(w)) return
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ -> onFrame(metrics) }
            frameListeners[w] = listener
            runCatching { w.addOnFrameMetricsAvailableListener(listener, bg) }
            val attrs = w.attributes
            val pref = attrs.preferredRefreshRate
            val mode = attrs.preferredDisplayModeId
            val name = activity.javaClass.simpleName
            bg.post { AppLogger.info(TAG, "[JankDiag] window activity=$name preferredRefreshRate=$pref preferredDisplayModeId=$mode") }
        }

        override fun onActivityPaused(activity: Activity) {
            resumed.decrementAndGet()
            val w = activity.window
            frameListeners.remove(w)?.let { runCatching { w.removeOnFrameMetricsAvailableListener(it) } }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private val stageBuf = LongArray(JankFrameWindow.Stage.entries.size)

    private fun onFrame(m: FrameMetrics) {
        if (m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return
        val st = JankFrameWindow.Stage.entries
        stageBuf[st.indexOf(JankFrameWindow.Stage.TOTAL)] = m.getMetric(FrameMetrics.TOTAL_DURATION)
        stageBuf[st.indexOf(JankFrameWindow.Stage.INPUT)] = m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION)
        stageBuf[st.indexOf(JankFrameWindow.Stage.ANIMATION)] = m.getMetric(FrameMetrics.ANIMATION_DURATION)
        stageBuf[st.indexOf(JankFrameWindow.Stage.LAYOUT_MEASURE)] = m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
        stageBuf[st.indexOf(JankFrameWindow.Stage.DRAW)] = m.getMetric(FrameMetrics.DRAW_DURATION)
        stageBuf[st.indexOf(JankFrameWindow.Stage.SYNC)] = m.getMetric(FrameMetrics.SYNC_DURATION)
        stageBuf[st.indexOf(JankFrameWindow.Stage.COMMAND_ISSUE)] = m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)
        stageBuf[st.indexOf(JankFrameWindow.Stage.SWAP)] = m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
        stageBuf[st.indexOf(JankFrameWindow.Stage.GPU)] =
            if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.GPU_DURATION) else 0L
        val deadline = if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.DEADLINE) else vsyncPeriodNs
        val vsync = m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        window.add(stageBuf, deadline, vsync)
        beat.add(stageBuf, deadline, vsync)
        if (transitionLabel != null && vsync in 1..transitionEndsNs) transition.add(stageBuf, deadline, vsync)
        if (streaming) windowHadStreaming = true
        pendingCancel?.let { (start, end) ->
            if (vsync >= start) {
                pendingCancel = null
                val frameDone = vsync + stageBuf[0]
                AppLogger.info(
                    TAG,
                    "[JankDiag] stop cancelStreamMs=${(end - start) / 1_000_000} tapToNextFrameMs=${(frameDone - start) / 1_000_000} screen=$screen",
                )
            }
        }
    }

    @Volatile private var vsyncPeriodNs = 16_666_666L

    private fun reportTransition() {
        val label = transitionLabel ?: return
        if (System.nanoTime() < transitionEndsNs) return
        if (transition.count > 0) AppLogger.info(TAG, "[JankDiag] transition $label ${transition.summary()}")
        transitionLabel = null
        transition.reset()
    }


    private val tick = object : Runnable {
        override fun run() {
            try {
                summarize()
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "[JankDiag] summary failed: ${t.message}")
            }
            bg.postDelayed(this, WINDOW_MS)
        }
    }

    private fun summarize() {
        windows++
        val chunks = streamChunks.getAndSet(0)
        val publishes = streamPublishes.getAndSet(0)
        val recomposes = newestRecomposes.getAndSet(0)
        val secs = WINDOW_MS / 1000.0
        val log = AppLogger.drainStats()
        if (window.worthReporting()) {
            AppLogger.info(
                TAG,
                "[JankDiag] frames screen=$screen streaming=$windowHadStreaming refresh=${currentRefresh()} ${window.summary()} " +
                    "logLines/s=${"%.0f".format(log.lines / secs)} mainLog calls=${log.mainCalls} ms=${"%.1f".format(log.mainNs / 1e6)} max=${"%.1f".format(log.mainMaxNs / 1e6)}",
            )
        }
        if (chunks > 0 || publishes > 0 || parse.total > 0) {
            AppLogger.info(
                TAG,
                "[JankDiag] stream screen=$screen chunks/s=${"%.1f".format(chunks / secs)} publishes/s=${"%.1f".format(publishes / secs)} " +
                    "chars=$streamChars parse(off-main) n=${parse.total} p50=${"%.1f".format(parse.percentile(.5))} " +
                    "p90=${"%.1f".format(parse.percentile(.9))} max=${"%.1f".format(parse.maxMs)} newestRecompose/s=${"%.1f".format(recomposes / secs)}",
            )
        }
        parse.reset()
        window.reset()
        windowHadStreaming = false
        if (displayReportPending) { displayReportPending = false; reportDisplay("changed x$displayChanges"); displayChanges = 0 }
        if (windows % HEARTBEAT_EVERY == 0) {
            val res = resources()
            if (resumed.get() > 0) heartbeat(log, res)
            else if (windows % (HEARTBEAT_EVERY * BACKGROUND_RES_EVERY) == 0) {
                AppLogger.info(TAG, "[JankDiag] res background ${res.fields()}")
            }
        }
    }


    private val alertGate = ResourceWatermark.AlertGate()
    private val clock = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
    private var lastBreakdown: String? = null
    private var lastMapsBreakdown: String? = null

    private fun resources(): ResourceWatermark.Sample {
        val s = ResourceWatermark.sample()
        val now = System.currentTimeMillis()
        try {
            if (alertGate.shouldReport(s, now)) {
                val names = ResourceWatermark.threadNames()
                val top = ResourceWatermark.breakdown(names)
                val offload = com.openminis.app.sandbox.NativeOffloadServer.inFlightSummary()
                lastBreakdown = "${clock.format(java.util.Date(now))} listed=${names.size} $top offloadInFlight=$offload"
                AppLogger.warning(
                    TAG,
                    "[JankDiag] threadsByName ${s.fields()} listed=${names.size} " +
                        "(alert at threads>${ResourceWatermark.THREAD_ALERT} or maps>${ResourceWatermark.MAPS_ALERT}) top=$top " +
                        "offloadInFlight=$offload",
                )
                if (s.maps > ResourceWatermark.MAPS_ALERT) {
                    val maps = ResourceWatermark.mapsBreakdown()
                    lastMapsBreakdown = "${clock.format(java.util.Date(now))} $maps"
                    AppLogger.warning(TAG, "[JankDiag] mapsByName ${s.fields()} top=$maps")
                }
            }
            val uptimeS = (android.os.SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000
            ResourceWatermark.writeWatermark(
                app.filesDir,
                Process.myPid(),
                "sampled=${clock.format(java.util.Date(now))} uptimeS=$uptimeS screen=$screen foreground=${resumed.get() > 0} ${s.fields()}\n" +
                    "threadsByName=${lastBreakdown ?: "(not taken: below threshold)"}\n" +
                    "mapsByName=${lastMapsBreakdown ?: "(not taken: below threshold)"}\n",
            )
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[JankDiag] resource sample failed: ${t.javaClass.simpleName}")
        }
        return s
    }

    private fun heartbeat(log: AppLogger.Stats, res: ResourceWatermark.Sample) {
        val gc = gcStats()
        val d = LongArray(4) { gc[it] - lastGc[it] }
        lastGc = gc
        val cpu = Process.getElapsedCpuTime()
        val cpuDelta = cpu - lastCpuMs
        lastCpuMs = cpu
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val pm = app.getSystemService(PowerManager::class.java)
        val thermal = if (Build.VERSION.SDK_INT >= 29) pm?.currentThermalStatus ?: -1 else -1
        AppLogger.info(
            TAG,
            "[JankDiag] tick ${WINDOW_MS * HEARTBEAT_EVERY / 1000}s screen=$screen ${beat.summary()} " +
                "gc+=${d[0]} gcMs+=${d[1]} blockingGc+=${d[2]} blockingGcMs+=${d[3]} heapMB=$usedMb/${rt.maxMemory() / (1024 * 1024)} " +
                "cpuMs+=$cpuDelta ${res.fields()} stallsUnsampled=$suppressedStalls " +
                "thermal=$thermal powerSave=${pm?.isPowerSaveMode} refresh=${currentRefresh()}",
        )
        suppressedStalls = 0
        beat.reset()
    }

    private fun gcStats(): LongArray = longArrayOf(
        stat("art.gc.gc-count"), stat("art.gc.gc-time"), stat("art.gc.blocking-gc-count"), stat("art.gc.blocking-gc-time"),
    )

    private fun stat(key: String): Long = runCatching { Debug.getRuntimeStat(key)?.toLong() ?: -1L }.getOrDefault(-1L)


    private fun display(): Display? = app.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)

    private fun currentRefresh(): String = display()?.let { "%.0f".format(it.refreshRate) } ?: "?"

    private fun banner() {
        val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        val code = if (Build.VERSION.SDK_INT >= 28) info?.longVersionCode else @Suppress("DEPRECATION") info?.versionCode?.toLong()
        val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= 30) app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName
            else @Suppress("DEPRECATION") app.packageManager.getInstallerPackageName(app.packageName)
        }.getOrNull()
        AppLogger.info(
            TAG,
            "[JankDiag] build=${AppBuildInfo.commit(app)} version=${info?.versionName}($code) built=${AppBuildInfo.buildDate(app)} " +
                "debuggable=$debuggable installer=$installer",
        )
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER}/${Build.SOC_MODEL}" else Build.HARDWARE
        AppLogger.info(
            TAG,
            "[JankDiag] device mfr=${Build.MANUFACTURER} model=${Build.MODEL} product=${Build.PRODUCT} soc=$soc " +
                "android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} rom=${Build.DISPLAY} cores=${Runtime.getRuntime().availableProcessors()}",
        )
        reportDisplay("start")
        reportPower("start")
        reportCompileState()
    }

    private fun reportDisplay(why: String) {
        val d = display() ?: return
        val mode = d.mode
        vsyncPeriodNs = (1e9 / d.refreshRate).toLong()
        val supported = d.supportedModes.joinToString(",") { "${it.modeId}:${it.physicalWidth}x${it.physicalHeight}@${"%.0f".format(it.refreshRate)}" }
        val line = "mode=${mode.modeId}:${mode.physicalWidth}x${mode.physicalHeight}@${"%.1f".format(mode.refreshRate)} " +
            "refresh=${"%.1f".format(d.refreshRate)} supported=[$supported]"
        if (why != "start" && line == lastDisplayLine) return
        lastDisplayLine = line
        AppLogger.info(TAG, "[JankDiag] display ($why) $line")
    }

    private fun reportPower(why: String) {
        val pm = app.getSystemService(PowerManager::class.java) ?: return
        val battery = app.getSystemService(BatteryManager::class.java)
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = battery?.isCharging
        val thermal = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else -1
        val headroom = if (Build.VERSION.SDK_INT >= 30) runCatching { "%.2f".format(pm.getThermalHeadroom(10)) }.getOrDefault("?") else "?"
        AppLogger.info(
            TAG,
            "[JankDiag] power ($why) saveMode=${pm.isPowerSaveMode} battery=$level% charging=$charging thermal=$thermal " +
                "headroom10s=$headroom sustainedPerfSupported=${pm.isSustainedPerformanceModeSupported} " +
                "idle=${if (Build.VERSION.SDK_INT >= 23) pm.isDeviceIdleMode else null}",
        )
    }

    private fun reportCompileState() {
        runCatching {
            val status = androidx.profileinstaller.ProfileVerifier.writeProfileVerification(app)
            AppLogger.info(
                TAG,
                "[JankDiag] art profileResult=${status.profileInstallResultCode and 0xFFFF} profileError=${status.profileInstallResultCode shr 16} " +
                    "compiledWithProfile=${status.isCompiledWithProfile} " +
                    "profileEnqueued=${status.hasProfileEnqueuedForCompilation()}",
            )
        }.onFailure { AppLogger.info(TAG, "[JankDiag] art status unavailable: ${it.javaClass.simpleName}") }
    }

    private fun registerSystemListeners() {
        app.getSystemService(DisplayManager::class.java)?.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) { displayChanges++; displayReportPending = true }
            }
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }, bg)
        val pm = app.getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                pm?.addThermalStatusListener(app.mainExecutor) { status ->
                    bg.post { AppLogger.info(TAG, "[JankDiag] thermal changed status=$status") }
                }
            }
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                app,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) { reportPower("changed") }
                },
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                null,
                bg,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    private class OpenTimeline {
        private var session: String? = null
        private var clickNs = 0L
        private var busyAtClick = 0L
        private val stages = LinkedHashMap<String, Long>()

        fun begin(sessionId: String, t: Long, busy: Long) {
            session = sessionId; clickNs = t; busyAtClick = busy; stages.clear()
        }

        fun stage(sessionId: String, name: String, t: Long) {
            if (session != sessionId) { session = sessionId; clickNs = 0L; stages.clear() }
            stages.putIfAbsent(name, t)
        }

        fun stable(sessionId: String, t: Long, busy: Long, longestNs: Long, messages: Int, rows: Int, prewarmMs: Long): String? {
            if (session != sessionId) return null
            val base = if (clickNs != 0L) clickNs else stages.values.firstOrNull() ?: return null
            val parts = stages.entries.joinToString(" ") { (k, v) -> "$k=+${(v - base) / 1_000_000}" }
            val line = "[JankDiag] open session=${sessionId.take(8)} click=${clickNs != 0L} msgs=$messages rows=$rows " +
                "prewarmMs=$prewarmMs $parts stable=+${(t - base) / 1_000_000} " +
                (if (clickNs != 0L) "mainBusyMs=${(busy - busyAtClick) / 1_000_000} " else "") +
                "longestMainMsgMs=${longestNs / 1_000_000}"
            session = null
            return line
        }
    }
}
