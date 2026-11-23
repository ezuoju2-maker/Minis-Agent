package com.openminis.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.openminis.app.MinisApp
import com.openminis.app.agent.SoulMetadata
import com.openminis.app.agent.SoulStore
import com.openminis.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AgentForegroundService : Service() {

    companion object {
        private val onStartCommandCalls = java.util.concurrent.atomic.AtomicInteger(0)

        private val processStartElapsedMs = android.os.SystemClock.elapsedRealtime()

        private const val TAG = "AgentForegroundService"
        private const val CHANNEL_ID = "agent_status"
        private const val CHANNEL_NAME = "Agent Status"
        private const val NOTIFICATION_ID = 9001

        internal fun elapsedRealtimeToWallClock(
            instantElapsedMs: Long,
            nowElapsedMs: Long,
            nowWallMs: Long,
        ): Long = nowWallMs - (nowElapsedMs - instantElapsedMs)

        internal fun startHasNothingToRun(action: String?, shouldRun: Boolean): Boolean =
            action != ACTION_STOP && !shouldRun

        private const val OVERLAY_NUDGE_CHANNEL_ID = "overlay_permission_nudge"
        private const val OVERLAY_NUDGE_NOTIFICATION_ID = 9002

        private const val COMPLETION_LINGER_TIMEOUT_MS = 5 * 60 * 1000L

        private const val EXTRA_SESSION_COUNT = "session_count"
        private const val EXTRA_TOOL_STATUS = "tool_status"
        private const val CHIP_TICK_MS = 1_000L

        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
        private const val ACTION_STOP = "com.openminis.app.STOP_AGENT_SERVICE"

        @Volatile
        var isAlive: Boolean = false
            private set

        fun startService(context: Context, sessionCount: Int, toolStatus: String): Boolean {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                putExtra(EXTRA_SESSION_COUNT, sessionCount)
                putExtra(EXTRA_TOOL_STATUS, toolStatus)
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: IllegalStateException) {
                val kind = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is android.app.ForegroundServiceStartNotAllowedException
                ) "ForegroundServiceStartNotAllowedException" else e.javaClass.simpleName
                com.openminis.app.logging.AppLogger.warning(
                    TAG,
                    "start refused ($kind): ${e.message} - the run continues without the foreground service; " +
                        "retried when the app comes to the foreground",
                )
                false
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java)
            context.stopService(intent)
        }
    }

    private var startTimeMs: Long = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    private var overlayController: ToolOverlayController? = null
    private val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var chipTickerJob: Job? = null
    private var lingerJob: Job? = null

    private var hasCompletionPending = false
    private var wasBusy = false

    override fun onCreate() {
        super.onCreate()
        isAlive = true
        if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
            Log.w(TAG, "safe-mode ON — skipping overlay/wake-lock bring-up")
            createNotificationChannel()
            return
        }
        createNotificationChannel()
        startTimeMs = SystemClock.elapsedRealtime()
        acquireWakeLock()
        startOverlayObserver()
        startAskingObserver()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val call = onStartCommandCalls.incrementAndGet()
        val active = SessionActivityTracker.activeSessions.value
        println(
            "[T-STALL-DIAG] FGS onStartCommand#$call pid=${android.os.Process.myPid()} " +
                "revival=${intent == null} flags=$flags startId=$startId " +
                "activeSessions=${active.size}[${active.joinToString(",")}] " +
                "processAliveMs=${android.os.SystemClock.elapsedRealtime() - processStartElapsedMs} " +
                "slots=${SessionConcurrencyManager.diagSnapshot()}",
        )
        if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
            try {
                val stub = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Minis")
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setOngoing(false)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        stub,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, stub)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "safe-mode stub startForeground failed: ${t.message}")
            }
            stopSelf()
            return START_NOT_STICKY
        }
        if (startHasNothingToRun(intent?.action, SessionActivityTracker.shouldRunServiceNow())) {
            Log.d(TAG, "start with nothing to run — satisfying the FG contract, then stopping")
            try {
                val stub = buildNotification(0, "Idle")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        stub,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, stub)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "FG-contract stub failed: ${t.message}")
            }
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            SessionActivityTracker.stopAllByUser()
            stopSelf()
            return START_NOT_STICKY
        }

        val sessionCount = intent?.getIntExtra(EXTRA_SESSION_COUNT, 0)
            ?: SessionActivityTracker.sessionCountForNotification()
        val toolStatus = intent?.getStringExtra(EXTRA_TOOL_STATUS)
            ?: SessionActivityTracker.statusForNotification()

        val notification = buildNotification(sessionCount, toolStatus)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        ensureChipTicker()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val activeAtRemoval = SessionActivityTracker.activeSessions.value
        println(
            "[T-STALL-DIAG] FGS onTaskRemoved pid=${android.os.Process.myPid()} " +
                "activeSessions=${activeAtRemoval.size}[${activeAtRemoval.joinToString(",")}] " +
                "scheduled=${SessionActivityTracker.scheduledSessions.value.size} " +
                "decision=${if (activeAtRemoval.isEmpty() && SessionActivityTracker.scheduledSessions.value.isEmpty()) "stopSelf" else "KEEP-ALIVE"} " +
                "slots=${SessionConcurrencyManager.diagSnapshot()}",
        )
        SessionActivityTracker.clearPresence()
        if (SessionActivityTracker.activeSessions.value.isEmpty() &&
            SessionActivityTracker.scheduledSessions.value.isEmpty()
        ) {
            Log.d(TAG, "onTaskRemoved with no active or scheduled sessions, stopping self")
            stopSelf()
            return
        }
        Log.d(TAG, "onTaskRemoved with ${SessionActivityTracker.activeSessions.value.size} active session(s) — keeping service alive")
        val notification = buildNotification(
            SessionActivityTracker.sessionCountForNotification(),
            SessionActivityTracker.statusForNotification(),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayController?.onConfigurationChanged()
    }

    override fun onDestroy() {
        isAlive = false
        releaseWakeLock()
        try {
            overlayController?.hide()
        } catch (_: Throwable) {}
        overlayController = null
        overlayScope.cancel()
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }

    private fun startAskingObserver() {
        overlayScope.launch {
            com.openminis.app.agent.userask.UserAskCenter.asks
                .map { askingStateFor(it.values) }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    refreshOngoingNotification()
                    ensureChipTicker()
                }
        }
    }

    private fun askingNow() = askingStateFor(com.openminis.app.agent.userask.UserAskCenter.asks.value.values)

    private fun startOverlayObserver() {
        val app = applicationContext as? MinisApp ?: return
        overlayController = ToolOverlayController(applicationContext).apply {
            onDismissByUser = {
                hasCompletionPending = false
                SessionActivityTracker.dismissOverlay()
            }
        }
        val backgroundRepo = app.backgroundSettingsRepository

        overlayScope.launch {
            combine(
                app.isAppForegroundFlow,
                SessionActivityTracker.currentToolName,
                SessionActivityTracker.currentToolStatus,
                SessionActivityTracker.isToolRunning,
                backgroundRepo.backgroundOverlayEnabled,
                SessionActivityTracker.lastToolOutcome,
                SessionActivityTracker.lastReplyExcerpt,
                SessionActivityTracker.currentSessionId,
                SessionActivityTracker.currentToolTitle,
                SessionActivityTracker.cameraSuppressActive,
                SessionActivityTracker.lastToolName,
                SessionActivityTracker.lastToolTitle,
                SessionActivityTracker.lastToolStatus,
                SessionActivityTracker.activeSessions,
                backgroundRepo.dynamicIslandEnabled,
                SoulStore.cachedMetadata,
                SessionActivityTracker.isThinking,
                SessionActivityTracker.scheduledWait,
            ) { values: Array<Any?> ->
                @Suppress("UNCHECKED_CAST")
                val activeSessions = values[13] as Set<String>
                OverlayState(
                    isForeground = values[0] as Boolean,
                    toolName = values[1] as String?,
                    toolStatus = values[2] as String,
                    isRunning = values[3] as Boolean,
                    enabled = values[4] as Boolean,
                    lastOutcome = values[5] as ToolOutcome,
                    lastReplyExcerpt = values[6] as String?,
                    currentSessionId = values[7] as String?,
                    toolTitle = values[8] as String?,
                    cameraSuppress = values[9] as Boolean,
                    lastToolName = values[10] as String?,
                    lastToolTitle = values[11] as String?,
                    lastToolStatus = values[12] as String?,
                    hasActiveStream = activeSessions.isNotEmpty(),
                    dynamicIslandEnabled = values[14] as Boolean,
                    soulIdentity = (values[15] as SoulMetadata).let { it.name to it.icon },
                    isThinking = values[16] as Boolean,
                    scheduledWait = values[17] as com.openminis.app.scheduled.ScheduledWait?,
                )
            }.distinctUntilChanged().collect { state -> applyOverlayState(state) }
        }
    }

    private data class OverlayState(
        val isForeground: Boolean,
        val toolName: String?,
        val toolStatus: String,
        val isRunning: Boolean,
        val enabled: Boolean,
        val lastOutcome: ToolOutcome,
        val lastReplyExcerpt: String?,
        val currentSessionId: String?,
        val toolTitle: String?,
        val cameraSuppress: Boolean,
        val lastToolName: String?,
        val lastToolTitle: String?,
        val lastToolStatus: String?,
        val hasActiveStream: Boolean,
        val dynamicIslandEnabled: Boolean,
        val soulIdentity: Pair<String, String>,
        val isThinking: Boolean,
        val scheduledWait: com.openminis.app.scheduled.ScheduledWait? = null,
    )

    private fun applyOverlayState(state: OverlayState) {
        val controller = overlayController ?: return
        val hasPerm = controller.hasOverlayPermission()

        if (DynamicIslandSupport.isDynamicIslandActive(this, state.dynamicIslandEnabled)) {
            hideOverlay()
            lingerJob?.cancel()
            lingerJob = null
            hasCompletionPending = false
            val stillWorking = state.hasActiveStream || state.isRunning
            if (!stillWorking) {
                SessionActivityTracker.setOverlayCompletionPending(false)
            }
            wasBusy = stillWorking
            if (SessionActivityTracker.activeSessions.value.isNotEmpty()) {
                refreshOngoingNotification()
            }
            Log.d(
                TAG,
                "applyOverlayState: dynamic-island active — overlay suppressed " +
                    "(dynamicIslandEnabled=${state.dynamicIslandEnabled})",
            )
            return
        }
        val isBusy = state.hasActiveStream || state.isRunning
        if (wasBusy && !isBusy && !state.isForeground && state.enabled &&
            hasPerm && !state.cameraSuppress
        ) {
            hasCompletionPending = true
            SessionActivityTracker.setOverlayCompletionPending(true)
        }
        wasBusy = isBusy
        val shouldShow = state.enabled && hasPerm && !state.isForeground &&
            !state.cameraSuppress &&
            (isBusy || hasCompletionPending)
        Log.d(
            TAG,
            "applyOverlayState fg=${state.isForeground} enabled=${state.enabled} " +
                "perm=$hasPerm streaming=${state.hasActiveStream} toolRunning=${state.isRunning} " +
                "cameraSuppress=${state.cameraSuppress} completionPending=$hasCompletionPending " +
                "toolName=${state.toolName} toolTitle=${state.toolTitle} shouldShow=$shouldShow shown=${controller.isShown}",
        )
        if (state.enabled && !hasPerm && isBusy && !state.isForeground) {
            maybePostOverlayPermissionNudge()
        } else if (hasPerm) {
            clearOverlayPermissionNudge()
        }
        if (state.isForeground || !state.enabled || !hasPerm || state.cameraSuppress) {
            if (state.isForeground) {
                hasCompletionPending = false
                SessionActivityTracker.setOverlayCompletionPending(false)
            }
            lingerJob?.cancel()
            lingerJob = null
            hideOverlay()
            return
        }

        if (shouldShow) {
            if (isBusy) {
                lingerJob?.cancel()
                lingerJob = null
            }
            val stillWorkingThisTurn = state.isRunning || state.isThinking || state.hasActiveStream
            val effectiveToolName =
                state.toolName ?: state.lastToolName?.takeIf { stillWorkingThisTurn }
            val effectiveToolTitle =
                state.toolTitle ?: state.lastToolTitle?.takeIf { stillWorkingThisTurn }
            val effectiveStatus = if (!state.toolStatus.equals("Idle", ignoreCase = true)) {
                state.toolStatus
            } else {
                state.lastToolStatus ?: state.toolStatus
            }
            val slot = SessionActivityTracker.focusedTaskSlot()
            if (isBusy) {
                controller.show(
                    toolName = effectiveToolName,
                    statusText = effectiveStatus,
                    isRunning = true,
                    outcome = ToolOutcome.Unknown,
                    replyExcerpt = null,
                    targetSessionId = state.currentSessionId,
                    toolTitle = effectiveToolTitle,
                    isToolRunning = state.isRunning,
                    isThinking = state.isThinking,
                    sessionTitle = slot?.first?.title,
                    taskIndex = slot?.second ?: 0,
                    taskTotal = slot?.third ?: 0,
                )
            } else if (overlayWaitFor(state.scheduledWait, state.currentSessionId) != null) {
                val wait = overlayWaitFor(state.scheduledWait, state.currentSessionId)!!
                lingerJob?.cancel()
                lingerJob = null
                controller.show(
                    toolName = null,
                    statusText = "",
                    isRunning = false,
                    outcome = ToolOutcome.Unknown,
                    replyExcerpt = null,
                    targetSessionId = state.currentSessionId ?: wait.sessionId,
                    toolTitle = null,
                    sessionTitle = slot?.first?.title ?: wait.chatTitle,
                    taskIndex = slot?.second ?: 0,
                    taskTotal = slot?.third ?: 0,
                    waiting = ToolOverlayController.Waiting(
                        statusText = waitStatusText(getString(R.string.scheduled_detail_next_run), wait.runCounter),
                        nextRunElapsedMs = wait.nextRunMs?.let {
                            wallClockToElapsedRealtime(it, System.currentTimeMillis(), SystemClock.elapsedRealtime())
                        },
                    ),
                )
            } else {
                if (lingerJob == null) {
                    lingerJob = overlayScope.launch {
                        delay(COMPLETION_LINGER_TIMEOUT_MS)
                        Log.d(TAG, "completion linger expired — releasing overlay keep-alive")
                        hasCompletionPending = false
                        lingerJob = null
                        SessionActivityTracker.setOverlayCompletionPending(false)
                        hideOverlay()
                    }
                }
                controller.show(
                    toolName = effectiveToolName,
                    statusText = effectiveStatus,
                    isRunning = false,
                    outcome = state.lastOutcome,
                    replyExcerpt = state.lastReplyExcerpt,
                    targetSessionId = state.currentSessionId,
                    toolTitle = effectiveToolTitle,
                    sessionTitle = slot?.first?.title,
                    taskIndex = slot?.second ?: 0,
                    taskTotal = slot?.third ?: 0,
                )
            }
            return
        }

        hideOverlay()
    }

    private fun hideOverlay() {
        overlayController?.let { if (it.isShown) it.hide() }
        SessionActivityTracker.dropFinishedOverlayTasks()
    }

    private fun ensureChipTicker() {
        chipTickerJob?.cancel()
        chipTickerJob = null
        if (!isPromotedChipActive() && waitingNow() == null) return
        chipTickerJob = overlayScope.launch {
            while (true) {
                val wait = waitingNow()
                val delayMs = if (wait != null) {
                    waitRefreshDelayMs(
                        wait.nextRunMs?.let { it - System.currentTimeMillis() },
                        promotedChip = isPromotedChipActive(),
                    ) ?: break
                } else {
                    if (!isPromotedChipActive()) break
                    askingNow()?.let { if (it.expiresAtMs == null) return@launch }
                    CHIP_TICK_MS
                }
                delay(delayMs)
                if (!SessionActivityTracker.shouldRunServiceNow()) break
                if (waitingNow() == null) {
                    if (presenceOnlyNow()) {
                        refreshOngoingNotification()
                        break
                    }
                    if (!isPromotedChipActive()) break
                    val finished = SessionActivityTracker.lastTaskFinishedAtMs.value != null &&
                        SessionActivityTracker.activeSessions.value.isEmpty()
                    if (finished) break
                }
                refreshOngoingNotification()
            }
        }
    }

    private fun presenceOnlyNow(): Boolean {
        val active = SessionActivityTracker.activeSessions.value
        val scheduledWait = SessionActivityTracker.scheduledWait.value?.takeIf { active.isEmpty() }
        val phase = resolveAgentPhase(
            isCompleted = scheduledWait == null &&
                SessionActivityTracker.lastTaskFinishedAtMs.value != null && active.isEmpty(),
            toolName = SessionActivityTracker.currentToolName.value,
            isThinking = SessionActivityTracker.isThinking.value,
            hasActiveSessions = active.isNotEmpty(),
            isScheduledWaiting = scheduledWait != null,
            isAwaitingAnswer = askingNow() != null,
        )
        return isPresenceOnly(phase, SessionActivityTracker.scheduledSessions.value.isNotEmpty())
    }

    private fun waitingNow() = SessionActivityTracker.scheduledWait.value
        ?.takeIf { SessionActivityTracker.activeSessions.value.isEmpty() }

    private fun isPromotedChipActive(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        if (presenceOnlyNow()) return false
        val minisApp = (applicationContext as? MinisApp)?.takeIf { it.subsystemsReady() }
        val userEnabled = minisApp?.backgroundSettingsRepository?.dynamicIslandEnabled?.value == true
        return DynamicIslandSupport.isDynamicIslandActive(this, userEnabled)
    }

    private fun refreshOngoingNotification() {
        try {
            val notification = buildNotification(
                SessionActivityTracker.sessionCountForNotification(),
                SessionActivityTracker.statusForNotification(),
            )
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "refreshOngoingNotification failed: ${t.message}")
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "minis:inference",
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "WakeLock acquired (PARTIAL_WAKE_LOCK)")
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquire failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WakeLock released")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock release failed: ${e.message}")
        } finally {
            wakeLock = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.bg_service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.bg_service_channel_description)
                setShowBadge(false)
            }
            val nudgeChannel = NotificationChannel(
                OVERLAY_NUDGE_CHANNEL_ID,
                getString(R.string.bg_overlay_nudge_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.bg_overlay_nudge_channel_description)
                setShowBadge(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            manager.createNotificationChannel(nudgeChannel)
        }
    }

    private var overlayNudgePosted: Boolean = false

    private fun clearOverlayPermissionNudge() {
        if (!overlayNudgePosted) return
        overlayNudgePosted = false
        try {
            getSystemService(NotificationManager::class.java)
                ?.cancel(OVERLAY_NUDGE_NOTIFICATION_ID)
            Log.d(TAG, "overlay permission nudge cleared (SAW now granted)")
        } catch (e: Throwable) {
            Log.w(TAG, "overlay permission nudge cancel failed: ${e.message}")
        }
    }

    private fun maybePostOverlayPermissionNudge() {
        if (overlayNudgePosted) return
        overlayNudgePosted = true
        try {
            val mgrIntent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName"),
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            val pi = PendingIntent.getActivity(
                this,
                2,
                mgrIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notif = NotificationCompat.Builder(this, OVERLAY_NUDGE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(getString(R.string.bg_overlay_nudge_title))
                .setContentText(getString(R.string.bg_overlay_nudge_body))
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(getString(R.string.bg_overlay_nudge_body)),
                )
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .build()
            val mgr = getSystemService(NotificationManager::class.java)
            mgr?.notify(OVERLAY_NUDGE_NOTIFICATION_ID, notif)
            Log.d(TAG, "overlay permission nudge posted (SAW not granted but toggle is ON)")
        } catch (e: Throwable) {
            Log.w(TAG, "overlay permission nudge failed: ${e.message}", e)
        }
    }

    private fun buildNotification(sessionCount: Int, toolStatus: String): Notification {
        val finishedAtMs = SessionActivityTracker.lastTaskFinishedAtMs.value
        val scheduledWait = SessionActivityTracker.scheduledWait.value
            ?.takeIf { SessionActivityTracker.activeSessions.value.isEmpty() }
        val asking = askingNow()
        val isCompleted = scheduledWait == null && asking == null &&
            finishedAtMs != null && SessionActivityTracker.activeSessions.value.isEmpty()
        val endMs = if (isCompleted) finishedAtMs!! else SystemClock.elapsedRealtime()
        val anchorMs = SessionActivityTracker.currentRunStartedAtMs.value ?: startTimeMs
        val elapsedMs = (endMs - anchorMs).coerceAtLeast(0L)
        val timeString = chipTimerText(elapsedMs)

        val nowElapsedMs = SystemClock.elapsedRealtime()
        val nowWallMs = System.currentTimeMillis()
        val runStartWallMs = elapsedRealtimeToWallClock(anchorMs, nowElapsedMs, nowWallMs)
        val finishedWallMs = elapsedRealtimeToWallClock(endMs, nowElapsedMs, nowWallMs)

        val mainIntent = Intent(this, Class.forName("com.openminis.app.MainActivity")).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AgentForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sessionLabel = resources.getQuantityString(
            R.plurals.bg_service_sessions, sessionCount, sessionCount,
        )

        val toolName = SessionActivityTracker.currentToolName.value
        val toolTitle = SessionActivityTracker.currentToolTitle.value
        val isToolRunning = SessionActivityTracker.isToolRunning.value && askingNow() == null
        val hasActiveSessions = SessionActivityTracker.activeSessions.value.isNotEmpty()

        val phase = resolveAgentPhase(
            isCompleted = isCompleted,
            toolName = toolName,
            isThinking = SessionActivityTracker.isThinking.value,
            hasActiveSessions = hasActiveSessions,
            isScheduledWaiting = scheduledWait != null,
            isAwaitingAnswer = asking != null,
        )
        val presenceOnly = isPresenceOnly(
            phase,
            hasScheduledHold = SessionActivityTracker.scheduledSessions.value.isNotEmpty(),
        )
        val smallIcon = notificationSmallIconFor(phase, toolName, waitHasClock = scheduledWait?.nextRunMs != null)
        val soulName = SoulStore.cachedMetadata.value.name.ifBlank { SoulMetadata.DEFAULT.name }
        val titleText = when (phase) {
            AgentPhase.COMPLETED -> getString(R.string.bg_service_notification_title_completed)
            AgentPhase.TOOL -> toolRowTitle(toolName!!, toolTitle)
            AgentPhase.THINKING, AgentPhase.GENERATING -> soulName
            AgentPhase.IDLE -> getString(R.string.bg_service_notification_title)
            AgentPhase.WAITING -> scheduledWait?.chatTitle
                ?: scheduledWait?.label?.takeIf { it.isNotBlank() }
                ?: getString(R.string.notif_scheduled_waiting)
            AgentPhase.ASKING -> asking?.sessionTitle ?: getString(R.string.user_ask_needs_answer)
        }
        val firingCounter = SessionActivityTracker.scheduledFiring.value
            ?.takeIf { it.sessionId in SessionActivityTracker.activeSessions.value }
            ?.runCounter
        val collapsedText = when (phase) {
            AgentPhase.COMPLETED -> if (sessionCount > 0) {
                getString(completedTextResFor(sessionCount), sessionLabel, timeString)
            } else {
                getString(completedTextResFor(sessionCount), timeString)
            }
            AgentPhase.TOOL -> getString(
                R.string.bg_service_notification_text_running,
                sessionLabel,
                withRunCounter(humanizeToolStatus(toolStatus, toolName), firingCounter),
            )
            AgentPhase.THINKING -> getString(
                R.string.bg_service_notification_text_running,
                sessionLabel,
                withRunCounter(getString(R.string.overlay_thinking), firingCounter),
            )
            AgentPhase.GENERATING -> getString(
                R.string.bg_service_notification_text_running,
                sessionLabel,
                withRunCounter(getString(R.string.overlay_streaming), firingCounter),
            )
            AgentPhase.IDLE -> getString(R.string.bg_service_notification_text_running, sessionLabel, toolStatus)
            AgentPhase.WAITING -> getString(
                R.string.bg_service_notification_text_running,
                sessionLabel,
                waitStatusText(getString(R.string.scheduled_detail_next_run), scheduledWait?.runCounter),
            )
            AgentPhase.ASKING -> getString(
                R.string.bg_service_notification_text_running,
                sessionLabel,
                getString(R.string.user_ask_waiting),
            )
        }
        val clock = notificationClockFor(
            phase = phase,
            runStartWallMs = runStartWallMs,
            finishedWallMs = finishedWallMs,
            nextRunWallMs = scheduledWait?.nextRunMs,
            nowWallMs = nowWallMs,
            presenceOnly = presenceOnly,
            askExpiresWallMs = asking?.expiresAtMs,
        )

        val shortCritical = when (phase) {
            AgentPhase.WAITING -> waitChipText(scheduledWait?.nextRunMs?.let { it - nowWallMs })
                ?: scheduledWait?.runCounter
                ?: "…"
            AgentPhase.ASKING -> askChipText(asking?.expiresAtMs?.let { it - nowWallMs })
            else -> chipTimerText(elapsedMs)
        }

        val minisApp = (applicationContext as? MinisApp)?.takeIf { it.subsystemsReady() }
        val dynamicIslandUserEnabled =
            minisApp?.backgroundSettingsRepository?.dynamicIslandEnabled?.value == true
        val dynamicIslandOn = DynamicIslandSupport.isDynamicIslandActive(
            this,
            dynamicIslandUserEnabled,
        )
        if (dynamicIslandOn && !presenceOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            return buildPromotedNotification(
                titleText = titleText,
                collapsedText = collapsedText,
                shortCritical = shortCritical,
                smallIcon = smallIcon,
                isToolRunning = isToolRunning,
                isCompleted = isCompleted,
                clock = clock,
                contentIntent = pendingIntent,
                stopIntent = stopPendingIntent,
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(titleText)
            .setContentText(collapsedText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(collapsedText))
            .setOngoing(true)
            .setShowWhen(clock.showWhen)
            .setWhen(clock.whenMs)
            .setUsesChronometer(clock.usesChronometer)
            .setChronometerCountDown(clock.countDown)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (!isCompleted) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.bg_service_stop_action),
                stopPendingIntent,
            )
        }

        if (isToolRunning) {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun buildPromotedNotification(
        titleText: String,
        collapsedText: String,
        shortCritical: String,
        smallIcon: Int,
        isToolRunning: Boolean,
        isCompleted: Boolean,
        clock: NotificationClock,
        contentIntent: PendingIntent,
        stopIntent: PendingIntent,
    ): Notification {
        val progressStyle = Notification.ProgressStyle()
            .addProgressSegment(Notification.ProgressStyle.Segment(100))
            .setStyledByProgress(false)
            .setProgressTrackerIcon(null)
            .setProgressIndeterminate(isToolRunning && !isCompleted)
            .setProgress(if (isCompleted) 100 else 0)

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(titleText)
            .setContentText(collapsedText)
            .setStyle(progressStyle)
            .setOngoing(true)
            .setShowWhen(clock.showWhen)
            .setWhen(clock.whenMs)
            .setUsesChronometer(clock.usesChronometer)
            .setChronometerCountDown(clock.countDown)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setColorized(false)

        builder.setShortCriticalText(shortCritical)

        if (!isCompleted) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    getString(R.string.bg_service_stop_action),
                    stopIntent,
                ).build(),
            )
        }

        builder.addExtras(android.os.Bundle().apply {
            putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        })

        val notification = builder.build()
        if (!notification.hasPromotableCharacteristics()) {
            val flags = notification.flags
            Log.w(
                TAG,
                "promoted notification lacks promotable characteristics — diag: " +
                    "requestPromotedOngoing=${notification.extras.getBoolean(EXTRA_REQUEST_PROMOTED_ONGOING)} " +
                    "FLAG_ONGOING_EVENT=${(flags and Notification.FLAG_ONGOING_EVENT) != 0} " +
                    "hasTitle=${!notification.extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrEmpty()} " +
                    "smallIcon=${notification.smallIcon != null} " +
                    "isGroupSummary=${(flags and Notification.FLAG_GROUP_SUMMARY) != 0} " +
                    "styleTemplate=${notification.extras.getString(Notification.EXTRA_TEMPLATE)}",
            )
        } else {
            Log.d(TAG, "promoted notification OK — hasPromotableCharacteristics=true")
        }
        return notification
    }
}
