package com.openminis.app

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import org.acra.ACRA
import org.acra.ReportField
import org.acra.config.CoreConfigurationBuilder
import org.acra.data.StringFormat
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.DatabaseVersionGuard
import com.openminis.app.data.repository.BackgroundSettingsRepository
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.MountedFoldersStore
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.WebAppShortcutRepository
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.notification.BackgroundTaskNotifier
import com.openminis.app.logging.AppLogger
import com.openminis.app.network.NetworkMonitor
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.MountedFolderCoordinator
import com.openminis.app.sandbox.NativeOffloadServer
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.sandbox.offload.AccessibilityOffloadHandler
import com.openminis.app.sandbox.offload.AlarmOffloadHandler
import com.openminis.app.sandbox.offload.BrowserUseOffloadHandler
import com.openminis.app.sandbox.offload.CalendarOffloadHandler
import com.openminis.app.sandbox.offload.ClipboardOffloadHandler
import com.openminis.app.sandbox.offload.ContactsOffloadHandler
import com.openminis.app.sandbox.offload.DeviceOffloadHandler
import com.openminis.app.sandbox.offload.LocationOffloadHandler
import com.openminis.app.sandbox.offload.ModelUseOffloadHandler
import com.openminis.app.sandbox.offload.SessionsOffloadHandler
import com.openminis.app.sandbox.offload.ShizukuOffloadHandler
import com.openminis.app.sandbox.offload.NotificationOffloadHandler
import com.openminis.app.sandbox.offload.OpenOffloadHandler
import com.openminis.app.sandbox.offload.PhotosOffloadHandler
import com.openminis.app.sandbox.offload.PlayerOffloadHandler
import com.openminis.app.sandbox.offload.SpeakOffloadHandler
import com.openminis.app.sandbox.offload.SpeechOffloadHandler
import com.openminis.app.sandbox.offload.WeatherOffloadHandler
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.ui.MinisImageFetcher
import kotlinx.coroutines.launch

class MinisApp : Application(), ImageLoaderFactory {
    @Volatile
    var subsystemsInitialized: Boolean = false
        private set

    @Volatile
    var dbVersionDecision: DatabaseVersionGuard.Decision =
        DatabaseVersionGuard.Decision.PROCEED
        private set

    val chatRepositoryOrNull: ChatRepository?
        get() = if (subsystemsInitialized) chatRepository else null

    val providerRepositoryOrNull: ProviderRepository?
        get() = if (subsystemsInitialized) providerRepository else null

    fun subsystemsReady(): Boolean = subsystemsInitialized

    lateinit var database: AppDatabase
        private set
    lateinit var chatRepository: ChatRepository
        private set
    lateinit var providerRepository: ProviderRepository
        private set
    lateinit var envVarRepository: EnvVarRepository
        private set
    lateinit var skillRepository: SkillRepository
        private set
    lateinit var mcpRepository: MCPRepository
        private set
    lateinit var memoryRepository: MemoryRepository
        private set
    lateinit var webAppShortcutRepository: WebAppShortcutRepository
        private set
    lateinit var backgroundSettingsRepository: BackgroundSettingsRepository
        private set
    lateinit var backgroundTaskNotifier: BackgroundTaskNotifier
        private set
    lateinit var mountedFoldersStore: MountedFoldersStore
        private set

    @Volatile
    private var foregroundActivityCount: Int = 0

    fun isAppForeground(): Boolean = foregroundActivityCount > 0

    private fun markUnreadOnCompletion(sessionId: String, outcome: com.openminis.app.notification.BackgroundTurnOutcome) {
        if (outcome == com.openminis.app.notification.BackgroundTurnOutcome.INTERRUPTED) return
        if (com.openminis.app.service.SessionUnreadStore.isViewing(sessionId)) return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val session = runCatching { chatRepository.getSession(sessionId) }.getOrNull() ?: return@launch
            if (session.isChild) return@launch
            if (com.openminis.app.agent.jobs.AgentJobRegistry.hasAgentWork(sessionId)) return@launch
            com.openminis.app.service.SessionUnreadStore.markUnread(sessionId)
        }
    }

    private val _isAppForegroundFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
    val isAppForegroundFlow: kotlinx.coroutines.flow.StateFlow<Boolean>
        get() = _isAppForegroundFlow

    val networkMonitor: NetworkMonitor = NetworkMonitor()

    val sharedBrowserTabPool: BrowserTabPool by lazy {
        BrowserTabPool(this).also { it.setSession("minis-browser-use") }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        ACRA.init(
            this,
            CoreConfigurationBuilder()
                .withBuildConfigClass(BuildConfig::class.java)
                .withReportFormat(StringFormat.JSON)
                .withLogcatArguments(listOf("-t", "200", "-v", "time"))
                .withReportContent(
                    ReportField.APP_VERSION_NAME,
                    ReportField.APP_VERSION_CODE,
                    ReportField.ANDROID_VERSION,
                    ReportField.BUILD,
                    ReportField.PHONE_MODEL,
                    ReportField.BRAND,
                    ReportField.STACK_TRACE,
                    ReportField.LOGCAT,
                ),
        )
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun onCreate() {
        super.onCreate()
        com.openminis.app.service.OAuthWaitKeepAlive.install(this)
        com.openminis.app.data.model.CertificateErrorText.resolver = { kind, host ->
            getString(
                when (kind) {
                    com.openminis.app.provider.TlsFailure.Kind.UNTRUSTED -> R.string.error_tls_untrusted
                    com.openminis.app.provider.TlsFailure.Kind.EXPIRED_OR_CLOCK -> R.string.error_tls_expired_or_clock
                    com.openminis.app.provider.TlsFailure.Kind.HOSTNAME_MISMATCH -> R.string.error_tls_hostname_mismatch
                },
                host,
            )
        }
        androidx.compose.ui.contentcapture.ContentCaptureManager.isEnabled = false
        com.openminis.app.tools.AgentToolSwitch.migrateLegacyIfNeeded(this)

        if (ACRA.isACRASenderServiceProcess()) {
            Log.i("MinisApp", "skipping app init in :acra reporter process")
            return
        }

        AppLogger.primeContext(this)

        com.openminis.app.data.FastModePrefs.prime(this)

        com.openminis.app.data.AutoCompactPrefs.prime(this)

        try {
            com.openminis.app.crash.NativeCrashHandler.install(
                java.io.File(filesDir, "logs"),
            )
        } catch (t: Throwable) {
            Log.w("MinisApp", "NativeCrashHandler install failed: ${t.message}")
        }

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                com.openminis.app.scheduled.ScheduledTaskManager(this@MinisApp).rescheduleAll()
            }.onFailure {
                Log.w("MinisApp", "startup rescheduleAll failed: ${it.message}")
            }
        }

        try {
            val priorHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val isFgsTimeout = throwable.javaClass.name.endsWith(
                        "RemoteServiceException\$ForegroundServiceDidNotStopInTimeException",
                    ) || (throwable.message?.contains("foreground service of type") == true &&
                        throwable.message?.contains("did not stop within its timeout") == true)
                    if (isFgsTimeout) {
                        Log.w(
                            "MinisApp",
                            "FGS timeout caught; stopping service before deferring to ACRA: ${throwable.message}",
                        )
                        runCatching {
                            val intent = Intent(this, com.openminis.app.service.AgentForegroundService::class.java)
                            stopService(intent)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w("MinisApp", "FGS-timeout handler internal failure: ${t.message}")
                }
                priorHandler?.uncaughtException(thread, throwable)
            }
        } catch (t: Throwable) {
            Log.w("MinisApp", "install FGS-timeout handler failed: ${t.message}")
        }

        com.openminis.app.crash.CrashFrequencyDetector.checkAtLaunch(this)

        Thread {
            com.openminis.app.crash.ExitInfoCollector.collect(this)
        }.apply { isDaemon = true; name = "exit-info-collect" }.start()

        if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
            Log.w("MinisApp", "safe-mode ON — skipping app subsystem init")
            return
        }

        AppLogger.init(this)

        try {
            com.openminis.app.diagnostics.LaunchCycleBeacon.recordLaunch(this)
        } catch (t: Throwable) {
            Log.w("MinisApp", "LaunchCycleBeacon.recordLaunch failed: ${t.message}")
        }

        com.openminis.app.diagnostics.HangDetector.start(this)
        com.openminis.app.diagnostics.JankDiag.start(this)

        dbVersionDecision = DatabaseVersionGuard.evaluate(this)

        try {
        if (dbVersionDecision == DatabaseVersionGuard.Decision.SHOW_NEWER_DB_GUIDANCE) {
            error(
                "on-disk chat schema is newer than this build " +
                    "(code=${DatabaseVersionGuard.CODE_DB_VERSION}); refusing to open it. " +
                    "The database file is left completely untouched — upgrading restores everything."
            )
        }
        database = AppDatabase.getInstance(this)
        chatRepository = ChatRepository(database.chatDao())
        com.openminis.app.agent.jobs.SubAgentTerminationStore.install(filesDir)
        com.openminis.app.agent.jobs.AgentJobRegistry.followUpDispatcher = { parentId, text, jobId ->
            val terminations = com.openminis.app.agent.jobs.SubAgentTerminationStore.shared
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                runCatching {
                    if (chatRepository.getSession(parentId) == null) {
                        com.openminis.app.logging.AppLogger.warning("AgentJobRegistry", "followUp ${jobId.take(8)} dropped — parent ${parentId.take(8)} no longer exists")
                        terminations.setDeliveryForJob(jobId, com.openminis.app.agent.jobs.SubAgentTermination.Delivery.FAILED, "parent session no longer exists")
                        return@launch
                    }
                    val r = com.openminis.app.debug.HeadlessChatRunner.prompt(
                        context = this@MinisApp, sessionId = parentId, text = text,
                        attachments = emptyList(), thinkingLevel = null, wait = false, timeoutMs = 0L,
                    )
                    com.openminis.app.logging.AppLogger.info("AgentJobRegistry", "followUp ${jobId.take(8)} → parent ${parentId.take(8)} outcome=${r.status}")
                    when (r.status) {
                        "Running" -> terminations.setDeliveryForJob(jobId, com.openminis.app.agent.jobs.SubAgentTermination.Delivery.DELIVERED)
                        "Queued" -> terminations.setDeliveryForJob(jobId, com.openminis.app.agent.jobs.SubAgentTermination.Delivery.QUEUED, "waiting behind the parent's running turn")
                        else -> terminations.setDeliveryForJob(jobId, com.openminis.app.agent.jobs.SubAgentTermination.Delivery.FAILED, listOfNotNull(r.status, r.responseText).joinToString(": "))
                    }
                }.onFailure {
                    com.openminis.app.logging.AppLogger.warning("AgentJobRegistry", "followUp ${jobId.take(8)} failed: ${it.message}")
                    terminations.setDeliveryForJob(jobId, com.openminis.app.agent.jobs.SubAgentTermination.Delivery.FAILED, it.message ?: "delivery threw")
                }
            }
        }
        com.openminis.app.agent.jobs.AgentCallbackLabels.install(this)
        providerRepository = ProviderRepository(this)
        com.openminis.app.quota.QuotaStore.start(this, providerRepository)
        envVarRepository = EnvVarRepository(this)
        skillRepository = SkillRepository(this)
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                    skillRepository.requestReload("foreground")
                }
            }
        )
        mcpRepository = MCPRepository(this)
        memoryRepository = MemoryRepository(java.io.File(filesDir, "minis-global/memory"))
        webAppShortcutRepository = WebAppShortcutRepository(database.webAppShortcutDao())

        subsystemsInitialized = true
        runCatching { com.openminis.app.data.UpdateAutoChecker.install(this) }
            .onFailure { Log.w("MinisApp", "update auto-check install failed: ${it.message}") }
        } catch (t: Throwable) {
            Log.e("MinisApp", "subsystem init failed — app will start in degraded mode", t)
            return
        }

        com.openminis.app.agent.SoulStore.ensureExists(this)
        com.openminis.app.agent.SoulStore.refreshCache(this)

        com.openminis.app.config.MinisConfigPermissionStore.init(this)
        com.openminis.app.config.audit.ConfigAuditLog.init(this)
        com.openminis.app.config.ConfigRegistry.init(
            this, providerRepository, envVarRepository, chatRepository,
        )

        ModelsDevApi.init(this)

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { com.openminis.app.provider.ModelReleaseIndex.warmUp() }
                .onFailure {
                    com.openminis.app.logging.AppLogger.warning(
                        "MinisApp", "release-index warm-up failed: ${it.message}"
                    )
                }
        }


        RootfsManager.getInstance(this)
        ExecutionCoordinator.init(this)
        ExecutionCoordinator.envVarRepository = envVarRepository

        com.openminis.app.data.EnvVarPrivacyStore.init(this)
        com.openminis.app.data.EnvVarRedactor.envVarRepository = envVarRepository

        networkMonitor.start(this)

        com.openminis.app.network.NetworkHub.init(this)
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                    com.openminis.app.network.NetworkHub.onForeground()
                }

                override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                    com.openminis.app.network.NetworkHub.onBackground()
                }
            }
        )

        PRootKernel.registerGlobalBindMounts(this)

        mountedFoldersStore = MountedFoldersStore(this)
        PRootKernel.mountedFoldersStore = mountedFoldersStore
        mountedFoldersStore.onChange = {
            PRootKernel.applyMountedFoldersSnapshot(this)
            ExecutionCoordinator.stopCurrentCommand()
        }
        PRootKernel.applyMountedFoldersSnapshot(this)

        NativeOffloadServer.register("android-alarm", AlarmOffloadHandler(this))
        NativeOffloadServer.register("android-calendar", CalendarOffloadHandler(this))
        NativeOffloadServer.register("android-clipboard", ClipboardOffloadHandler(this))
        NativeOffloadServer.register("android-contacts", ContactsOffloadHandler(this))
        NativeOffloadServer.register("android-device", DeviceOffloadHandler(this))
        NativeOffloadServer.register("android-location", LocationOffloadHandler(this))
        NativeOffloadServer.register("android-notification", NotificationOffloadHandler(this))
        NativeOffloadServer.register("android-open", OpenOffloadHandler(this))
        NativeOffloadServer.register("android-photos", PhotosOffloadHandler(this))
        NativeOffloadServer.register("android-player", PlayerOffloadHandler())
        NativeOffloadServer.register("android-speak", SpeakOffloadHandler(this))
        NativeOffloadServer.register("android-speech", SpeechOffloadHandler(this))
        NativeOffloadServer.register("android-weather", WeatherOffloadHandler(this))
        NativeOffloadServer.register("android-a11y-cli", AccessibilityOffloadHandler(this))
        NativeOffloadServer.register("minis-model-use", ModelUseOffloadHandler(this, providerRepository))
        NativeOffloadServer.register(
            "minis-config",
            com.openminis.app.sandbox.offload.ConfigOffloadHandler(),
        )
        NativeOffloadServer.register("minis-browser-use", BrowserUseOffloadHandler(this))
        NativeOffloadServer.register(
            "minis-sessions-cli",
            SessionsOffloadHandler(
                chatRepository,
                titleRegenerator = { id ->
                    com.openminis.app.ui.sessions.SessionTitleRegenerator(chatRepository, providerRepository, this)
                        .regenerate(id, origin = "cli")
                },
            ),
        )
        NativeOffloadServer.register(
            "minis-scheduled",
            com.openminis.app.sandbox.offload.ScheduledTaskOffloadHandler(this),
        )
        NativeOffloadServer.register("android-shizuku-cli", ShizukuOffloadHandler(this))
        com.openminis.app.offload.ShizukuManager.init(this)

        if (BuildConfig.DEV_TOOLS) {
            NativeOffloadServer.register(
                "minis-debug",
                com.openminis.app.sandbox.offload.DebugOffloadHandler(this),
            )
        }

        try {
            NativeOffloadServer.start(RootfsManager.getInstance(this).rootfsDir)
        } catch (t: Throwable) {
            Log.e("MinisApp", "native offload server failed to start; continuing without it", t)
        }

        SessionActivityTracker.init(this)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
            com.openminis.app.scheduled.ScheduledSessionHold.observe(this@MinisApp).collect { held ->
                SessionActivityTracker.setScheduledSessions(held)
            }
        }
        com.openminis.app.scheduled.ScheduledInAppTimer.start(this)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
            com.openminis.app.scheduled.ScheduledWaitModel.observe(this@MinisApp).collect { wait ->
                val title = wait?.let { w ->
                    runCatching { chatRepository.getSession(w.sessionId)?.title }.getOrNull()?.takeIf { it.isNotBlank() }
                }
                SessionActivityTracker.setScheduledWait(wait?.copy(chatTitle = title))
            }
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
            com.openminis.app.scheduled.ScheduledWaitModel.observeFiring(this@MinisApp).collect {
                SessionActivityTracker.setScheduledFiring(it)
            }
        }

        com.openminis.app.service.SessionBadgeStore.init(this)

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val interrupted = runCatching { chatRepository.interruptedSessionIds() }.getOrElse { emptySet() }
            val active = SessionActivityTracker.activeSessions.value
            com.openminis.app.service.SessionBadgeStore.reconcileInterruptedSessions(interrupted - active)
        }

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                com.openminis.app.data.session.OrphanChildSweeper.sweep(this@MinisApp, chatRepository)
            }.onFailure {
                com.openminis.app.logging.AppLogger.warning("OrphanSweep", "sweep threw: ${it.message}")
            }
        }

        backgroundSettingsRepository = BackgroundSettingsRepository(this)
        backgroundTaskNotifier = BackgroundTaskNotifier(
            context = this,
            chatRepository = chatRepository,
            backgroundSettings = backgroundSettingsRepository,
            isAppForeground = ::isAppForeground,
        )
        SessionActivityTracker.setCompletionListener { sessionId, outcome ->
            backgroundTaskNotifier.notifyTaskCompleted(sessionId, outcome)
            markUnreadOnCompletion(sessionId, outcome)
        }

        val configConfirmNotifier = com.openminis.app.notification.ConfigConfirmNotifier(
            context = this,
            backgroundSettings = backgroundSettingsRepository,
            isAppForeground = ::isAppForeground,
        )
        com.openminis.app.config.confirm.ConfigConfirmationGate.backgroundNotifier = {
            configConfirmNotifier.notifyIfBackgrounded(it)
        }
        com.openminis.app.config.confirm.ConfigConfirmationGate.cancelNotification = {
            configConfirmNotifier.cancel(it)
        }
        com.openminis.app.notification.UserAskNotifier.install(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                val wasBackgrounded = foregroundActivityCount == 0
                foregroundActivityCount++
                if (wasBackgrounded) _isAppForegroundFlow.value = true
                if (wasBackgrounded && ::backgroundTaskNotifier.isInitialized) {
                    backgroundTaskNotifier.cancelAllCompletedNotifications()
                }
                if (wasBackgrounded) SessionActivityTracker.onAppForegrounded()
                if (wasBackgrounded) {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        val interrupted = runCatching { chatRepository.interruptedSessionIds() }.getOrElse { emptySet() }
                        val active = SessionActivityTracker.activeSessions.value
                        com.openminis.app.service.SessionBadgeStore.reconcileInterruptedSessions(interrupted - active)
                    }
                }
            }
            override fun onActivityResumed(activity: Activity) {
                com.openminis.app.crash.CrashFrequencyDetector.maybeShowOnActivity(activity)
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    runCatching { mountedFoldersStore.refreshWritability() }
                }
                providerRepository.refreshAllModelsIfNeeded(
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
                )
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                foregroundActivityCount = (foregroundActivityCount - 1).coerceAtLeast(0)
                if (foregroundActivityCount == 0) {
                    _isAppForegroundFlow.value = false
                    com.openminis.app.config.confirm.ConfigConfirmationGate.notifyPending()
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        OffloadPermissionManager.init(this)

        com.openminis.app.speech.SpeechRecognitionManager.init(this)

        providerRepository.refreshAllModelsIfNeeded(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        )

        val sandboxSystemReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
                when (intent.action) {
                    Intent.ACTION_TIMEZONE_CHANGED -> scope.launch {
                        try {
                            ExecutionCoordinator.broadcastTimezoneChange()
                        } catch (t: Throwable) {
                            Log.w("MinisApp", "broadcastTimezoneChange failed: ${t.message}")
                        }
                    }
                    android.net.Proxy.PROXY_CHANGE_ACTION -> scope.launch {
                        try {
                            ExecutionCoordinator.broadcastProxyChange()
                        } catch (t: Throwable) {
                            Log.w("MinisApp", "broadcastProxyChange failed: ${t.message}")
                        }
                    }
                }
            }
        }
        registerReceiver(
            sandboxSystemReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(android.net.Proxy.PROXY_CHANGE_ACTION)
            },
        )

        if (BuildConfig.DEV_TOOLS) {
            try {
                com.openminis.app.debug.DebugServer(this).start()
            } catch (e: Exception) {
                Log.w("MinisApp", "Failed to start debug server: ${e.message}")
            }
        }

        runCatching { migrateGhostAlarms() }
            .onFailure { Log.w("MinisApp", "ghost alarm migration failed: ${it.message}") }
    }

    private fun migrateGhostAlarms() {
        val prefs = getSharedPreferences("minis_alarms_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("alarms_json", null) ?: return
        if (raw.isBlank() || raw == "[]") return
        val arr = org.json.JSONArray(raw)
        if (arr.length() == 0) {
            prefs.edit().remove("alarms_json").apply()
            return
        }
        val now = System.currentTimeMillis()
        var migrated = 0
        var skipped = 0
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val triggerAt = entry.optLong("triggerAtMs", 0L)
            if (triggerAt in 1L..now && entry.optString("type") == "timer") {
                skipped++; continue
            }
            if (triggerAt in 1L..now && entry.optString("repeatMode", "ONCE") == "ONCE") {
                skipped++; continue
            }
            val migrationOk = runCatching {
                if (entry.optString("type") == "timer") {
                    val secs = entry.optInt("durationSec", -1)
                    val remaining = ((triggerAt - now) / 1000L).toInt()
                    if (remaining <= 0 && secs <= 0) return@runCatching false
                    val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_LENGTH, if (remaining > 0) remaining else secs)
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, entry.optString("label", "Timer"))
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    true
                } else {
                    val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_HOUR, entry.optInt("hour", 0))
                        putExtra(android.provider.AlarmClock.EXTRA_MINUTES, entry.optInt("minute", 0))
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, entry.optString("label", "Alarm"))
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    true
                }
            }.getOrDefault(false)
            if (migrationOk) migrated++ else skipped++
        }
        prefs.edit().remove("alarms_json").apply()
        Log.i("MinisApp", "T268 ghost alarm migration: migrated=$migrated skipped=$skipped (prefs cleared)")
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .callFactory {
                val base = okhttp3.OkHttpClient()
                okhttp3.Call.Factory { request -> com.openminis.app.network.NetworkHttp.client(com.openminis.app.network.NetworkHttp.DEFAULT_TAG, base).newCall(request) }
            }
            .components {
                add(MinisImageFetcher.Factory())
                add(MinisImageFetcher.UriFactory())
                add(MinisImageFetcher.MtimeKeyer())
                add(MinisImageFetcher.StringMtimeKeyer())
            }
            .build()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.openminis.app.debug.LoadTestDebugMethods.noteTrim(level)

        val dropFormulaCaches = when (level) {
            TRIM_MEMORY_RUNNING_LOW,
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_BACKGROUND,
            TRIM_MEMORY_MODERATE,
            TRIM_MEMORY_COMPLETE,
            -> true
            else -> false
        }
        if (!dropFormulaCaches) return

        Log.i("MinisApp", "onTrimMemory(level=$level): releasing formula bitmap caches")
        runCatching { com.openminis.app.ui.chat.KatexWebViewPool.evictAll() }
            .onFailure { Log.w("MinisApp", "KatexWebViewPool.evictAll failed: ${it.message}") }
        runCatching { com.openminis.app.ui.markdown.KaTeXRendererCache.evictAll() }
            .onFailure { Log.w("MinisApp", "KaTeXRendererCache.evictAll failed: ${it.message}") }

        runCatching {
            com.openminis.app.browser.BrowserTabPoolRegistry.handleMemoryPressure()
        }.onFailure { Log.w("MinisApp", "BrowserTabPoolRegistry.handleMemoryPressure failed: ${it.message}") }

        val dropCachedSessions = when (level) {
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_BACKGROUND,
            TRIM_MEMORY_MODERATE,
            TRIM_MEMORY_COMPLETE,
            -> true
            else -> false
        }
        if (dropCachedSessions) {
            runCatching {
                val freed = com.openminis.app.ui.chat.ChatViewModelStore.handleMemoryPressure()
                Log.i("MinisApp", "onTrimMemory(level=$level): released $freed cached session store(s)")
            }.onFailure { Log.w("MinisApp", "ChatViewModelStore.handleMemoryPressure failed: ${it.message}") }
        }

        if (level >= TRIM_MEMORY_COMPLETE) {
            Log.i("MinisApp", "onTrimMemory(level=$level): tearing down the offscreen KaTeX WebView")
            runCatching { com.openminis.app.ui.chat.KatexWebViewPool.releaseWebView() }
                .onFailure { Log.w("MinisApp", "KatexWebViewPool.releaseWebView failed: ${it.message}") }
        }
    }

    override fun onTerminate() {
        try {
            com.openminis.app.diagnostics.LaunchCycleBeacon.recordCleanExit(this)
        } catch (t: Throwable) {
            Log.w("MinisApp", "LaunchCycleBeacon.recordCleanExit failed: ${t.message}")
        }
        super.onTerminate()
    }

}
