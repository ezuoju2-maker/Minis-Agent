package com.openminis.app

import android.app.LocaleManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.provider.Settings
import com.openminis.app.accessibility.AccessibilityRecoveryManager
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.openminis.app.offload.OffloadPermissionManager
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkCoordinator
import com.openminis.app.deeplink.DeepLinkHandler
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.ui.navigation.AppNavigation
import com.openminis.app.ui.navigation.Routes
import com.openminis.app.ui.navigation.safeNavigate
import com.openminis.app.ui.NewerDatabaseGuidanceScreen
import com.openminis.app.ui.settings.KEY_FONT_APP_BASE
import com.openminis.app.ui.settings.KEY_KEEP_SCREEN_AWAKE
import com.openminis.app.ui.settings.KEY_LANGUAGE
import com.openminis.app.ui.settings.KEY_LAUNCH_SESSION
import com.openminis.app.ui.settings.KEY_THEME_MODE
import com.openminis.app.ui.settings.PREF_APPEARANCE
import com.openminis.app.ui.settings.getAppearancePrefs
import com.openminis.app.ui.settings.fontScaleForLevel
import com.openminis.app.ui.settings.keepScreenAwakeEnabled
import com.openminis.app.ui.theme.MinisTheme

private const val KEY_CURRENT_CHAT_SESSION_ID = "minis.current_chat_session_id"

class MainActivity : ComponentActivity() {

    private var navController: NavHostController? = null

    private var currentChatSessionId: String? = null

    private var restoredChatSessionId: String? = null

    private var hasResumedFromBackground = false

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private lateinit var settingsLauncher: ActivityResultLauncher<Intent>

    private var pendingCrashZip: java.io.File? = null
    private var pendingCrashSaveOnClosed: (() -> Unit)? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.openminis.app.i18n.LocaleWrap.wrap(newBase))
    }

    private fun finishAndRestartProcess() {
        try {
            android.widget.Toast.makeText(
                this,
                getString(R.string.crash_safe_mode_restart_needed),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        } catch (t: Throwable) {
            android.util.Log.w("MainActivity", "restart toast failed: ${t.message}")
        }
        finish()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 1200L)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val crashSaveLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val targetUri = result.data?.data
            val zip = pendingCrashZip
            pendingCrashZip = null
            if (result.resultCode != android.app.Activity.RESULT_OK || targetUri == null || zip == null) {
                pendingCrashSaveOnClosed?.invoke()
                pendingCrashSaveOnClosed = null
                return@registerForActivityResult
            }
            try {
                contentResolver.openOutputStream(targetUri)?.use { out ->
                    java.io.FileInputStream(zip).use { input -> input.copyTo(out) }
                } ?: throw java.io.IOException("openOutputStream returned null")
                try {
                    val view = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(targetUri, "application/zip")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(view)
                } catch (t: Throwable) {
                    android.util.Log.w("MainActivity", "ACTION_VIEW after save failed: ${t.message}")
                }
            } catch (t: Throwable) {
                android.util.Log.w("MainActivity", "crash zip save failed: ${t.message}")
            } finally {
                pendingCrashSaveOnClosed?.invoke()
                pendingCrashSaveOnClosed = null
            }
        }

        val minisApp = application as? MinisApp

        if (minisApp != null &&
            minisApp.dbVersionDecision ==
            com.openminis.app.data.db.DatabaseVersionGuard.Decision.SHOW_NEWER_DB_GUIDANCE
        ) {
            android.util.Log.w("MainActivity", "showing newer-database guidance screen")
            setContent { NewerDatabaseGuidanceScreen(onExit = { finishAndRemoveTask() }) }
            return
        }

        if (minisApp == null || !minisApp.subsystemsInitialized) {
            android.util.Log.w(
                "MainActivity",
                "app subsystems not initialized (safeMode=" +
                    "${com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()}) — " +
                    "showing crash share dialog and finishing",
            )
            com.openminis.app.crash.CrashFrequencyDetector.maybeShowOnActivity(
                activity = this,
                onClosed = { finishAndRestartProcess() },
                saveLauncher = { zip, onSaveDone ->
                    pendingCrashZip = zip
                    pendingCrashSaveOnClosed = onSaveDone
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/zip"
                        putExtra(Intent.EXTRA_TITLE, zip.name)
                    }
                    try {
                        crashSaveLauncher.launch(intent)
                    } catch (t: Throwable) {
                        android.util.Log.w("MainActivity", "CREATE_DOCUMENT launch failed: ${t.message}")
                        pendingCrashZip = null
                        pendingCrashSaveOnClosed = null
                        onSaveDone()
                    }
                },
            )
            return
        }

        restoredChatSessionId = savedInstanceState?.getString(KEY_CURRENT_CHAT_SESSION_ID)
            ?.takeUnless {
                com.openminis.app.crash.CrashFrequencyDetector.shouldForceHomeOnLaunch(this)
            }

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            val allGranted = results.isNotEmpty() && results.values.all { it }
            OffloadPermissionManager.respondToAndroidPermission(allGranted)
        }
        settingsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {  }

        lifecycleScope.launch {
            OffloadPermissionManager.pendingAndroidPermission
                .filterNotNull()
                .collect { req ->
                    val permanentlyDenied = req.permissions.any { permission ->
                        ContextCompat.checkSelfPermission(this@MainActivity, permission) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED &&
                            !ActivityCompat.shouldShowRequestPermissionRationale(
                                this@MainActivity, permission
                            ) &&
                            OffloadPermissionManager.hasAskedForPermission(this@MainActivity, permission)
                    }
                    if (permanentlyDenied) {
                        OffloadPermissionManager.respondToAndroidPermission(
                            OffloadPermissionManager.AndroidPermissionResult.DENIED
                        )
                    } else {
                        for (p in req.permissions) {
                            OffloadPermissionManager.markPermissionAsked(this@MainActivity, p)
                        }
                        permissionLauncher.launch(req.permissions.toTypedArray())
                    }
                }
        }

        lifecycleScope.launch {
            var shown: AlertDialog? = null
            OffloadPermissionManager.pendingSettingsGate
                .collect { gate ->
                    shown?.dismiss()
                    shown = null
                    if (gate == null) return@collect
                    shown = AlertDialog.Builder(this@MainActivity)
                        .setTitle(gate.title)
                        .setMessage(gate.message)
                        .setCancelable(false)
                        .setPositiveButton(gate.positiveLabel) { d, _ ->
                            d.dismiss()
                            if (!OffloadPermissionManager.respondToSettingsGate(
                                    gate, OffloadPermissionManager.SettingsGateDecision.OPEN,
                                )
                            ) return@setPositiveButton
                            val intent = Intent(gate.settingsAction).apply {
                                if (gate.requiresPackageUri) {
                                    data = Uri.fromParts("package", packageName, null)
                                }
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try {
                                settingsLauncher.launch(intent)
                            } catch (_: Throwable) {
                            }
                        }
                        .setNegativeButton(gate.negativeLabel) { d, _ ->
                            d.dismiss()
                            OffloadPermissionManager.respondToSettingsGate(
                                gate, OffloadPermissionManager.SettingsGateDecision.CANCEL,
                            )
                        }
                        .show()
                }
        }

        lifecycleScope.launch {
            AccessibilityRecoveryManager.pendingPrompt
                .filterNotNull()
                .collect { prompt ->
                    val b = AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.a11y_repair_dialog_title))
                        .setCancelable(true)
                        .setOnCancelListener {
                            AccessibilityRecoveryManager.respond(
                                AccessibilityRecoveryManager.Decision.CANCEL
                            )
                        }
                        .setNegativeButton(R.string.a11y_repair_cancel) { d, _ ->
                            d.dismiss()
                            AccessibilityRecoveryManager.respond(
                                AccessibilityRecoveryManager.Decision.CANCEL
                            )
                        }
                    if (prompt.shizukuAvailable) {
                        b.setMessage(getString(R.string.a11y_repair_dialog_message_shizuku))
                            .setPositiveButton(R.string.a11y_repair_action_repair) { d, _ ->
                                d.dismiss()
                                AccessibilityRecoveryManager.respond(
                                    AccessibilityRecoveryManager.Decision.REPAIR
                                )
                            }
                    } else {
                        b.setMessage(getString(R.string.a11y_repair_dialog_message_manual))
                            .setPositiveButton(R.string.a11y_repair_action_open_settings) { d, _ ->
                                d.dismiss()
                                try {
                                    startActivity(
                                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    )
                                } catch (_: Throwable) {
                                }
                                AccessibilityRecoveryManager.respond(
                                    AccessibilityRecoveryManager.Decision.OPEN_SETTINGS
                                )
                            }
                    }
                    b.show()
                }
        }

        applySavedLanguage()

        com.openminis.app.share.ShareCoordinator.processPendingShare(this)

        lifecycleScope.launch {
            SessionActivityTracker.activeSessions.collect { active ->
                applyKeepScreenAwakeFlag(active.isNotEmpty())
            }
        }

        if (BuildConfig.DEV_TOOLS) {
            com.openminis.app.debug.DebugRPCHandler.currentActivity = java.lang.ref.WeakReference(this)
        }

        val app = requireNotNull(application as? MinisApp)

        val explicitDeepLink = DeepLinkHandler.parse(intent?.data)
        val launchDeepLink = if (explicitDeepLink !is DeepLinkAction.Unknown) {
            explicitDeepLink
        } else {
            restoredChatSessionId
                ?.let { DeepLinkAction.OpenSession(it) }
                ?: DeepLinkAction.Unknown
        }

        setContent {
            val prefs = remember { getAppearancePrefs(this) }
            var themeMode by remember { mutableIntStateOf(prefs.getInt(KEY_THEME_MODE, 0)) }
            var appBaseLevel by remember { mutableIntStateOf(prefs.getInt(KEY_FONT_APP_BASE, 0)) }

            DisposableEffect(prefs) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
                    when (key) {
                        KEY_THEME_MODE -> themeMode = sp.getInt(KEY_THEME_MODE, 0)
                        KEY_FONT_APP_BASE -> appBaseLevel = sp.getInt(KEY_FONT_APP_BASE, 0)
                        KEY_KEEP_SCREEN_AWAKE -> applyKeepScreenAwakeFlag(
                            SessionActivityTracker.activeSessions.value.isNotEmpty()
                        )
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }

            val darkTheme = when (themeMode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            val fontScale = fontScaleForLevel(appBaseLevel)

            SideEffect {
                val barStyle = if (darkTheme) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
            }

            MinisTheme(darkTheme = darkTheme, fontScale = fontScale) {
                val navController = rememberNavController().also { this.navController = it }

                DisposableEffect(navController) {
                    val job = lifecycleScope.launch {
                        navController.currentBackStackEntryFlow.collect { entry ->
                            com.openminis.app.diagnostics.HangDetector.noteScreen(entry.destination.route)
                            com.openminis.app.diagnostics.JankDiag.setScreen(entry.destination.route)
                            val isChatRoute = entry.destination.route == Routes.CHAT
                            val sid = entry.arguments?.getString("sessionId").takeIf { isChatRoute }
                            val previous = currentChatSessionId
                            if (sid != previous) {
                                if (previous != null) {
                                    SessionActivityTracker.setAbsent(previous)
                                }
                                if (sid != null) {
                                    SessionActivityTracker.setPresent(sid)
                                }
                                currentChatSessionId = sid
                            }
                        }
                    }
                    onDispose { job.cancel() }
                }

                AppNavigation(
                    chatRepository = app.chatRepository,
                    providerRepository = app.providerRepository,
                    envVarRepository = app.envVarRepository,
                    skillRepository = app.skillRepository,
                    mcpRepository = app.mcpRepository,
                    memoryRepository = app.memoryRepository,
                    navController = navController,
                    initialDeepLink = launchDeepLink,
                )

                com.openminis.app.ui.settings.ConfigConfirmDialogHost()

                com.openminis.app.ui.settings.NetworkAlertBannerHost(navController)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentChatSessionId?.let {
            outState.putString(KEY_CURRENT_CHAT_SESSION_ID, it)
        }
    }

    override fun onStart() {
        super.onStart()
        if (!hasResumedFromBackground) {
            hasResumedFromBackground = true
            return
        }
        val mode = getAppearancePrefs(this).getInt(KEY_LAUNCH_SESSION, 0)
        if (mode != 2) return
        val nav = navController ?: return
        val newRoute = Routes.chat("__new__${java.util.UUID.randomUUID()}")
        AppLogger.info("LaunchSession", "resume → mode=NewChat, navigating to $newRoute")
        nav.safeNavigate(newRoute) {
            popUpTo(Routes.SESSION_LIST) { inclusive = false }
        }
    }

    override fun onDestroy() {
        currentChatSessionId?.let { SessionActivityTracker.setAbsent(it) }
        currentChatSessionId = null
        super.onDestroy()
    }

    private fun applyKeepScreenAwakeFlag(hasActiveSession: Boolean) {
        val want = keepScreenAwakeEnabled(this) && hasActiveSession
        if (!keepScreenOnNeedsChange(window.attributes.flags, want)) return
        if (want) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            AppLogger.info("KeepScreenAwake", "screen-on lock acquired (active sessions present)")
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            AppLogger.info(
                "KeepScreenAwake",
                "screen-on lock released (toggle=${keepScreenAwakeEnabled(this)}, active=$hasActiveSession)",
            )
        }
    }

    private fun applySavedLanguage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val code = getSharedPreferences(PREF_APPEARANCE, MODE_PRIVATE).getString(KEY_LANGUAGE, "") ?: ""
        val localeManager = getSystemService(LocaleManager::class.java) ?: return
        val desired = if (code.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(code)
        if (localeManager.applicationLocales.toLanguageTags() != desired.toLanguageTags()) {
            localeManager.applicationLocales = desired
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("shared_content", false)) {
            com.openminis.app.share.ShareCoordinator.processPendingShare(this)
        }
        handleDeepLink(intent.data)
    }

    private fun handleDeepLink(uri: Uri?) {
        val action = DeepLinkHandler.parse(uri)
        val nav = navController ?: return
        when (action) {
            is DeepLinkAction.OpenTerminal -> {
                nav.navigate(Routes.terminal(action.initCommand))
            }
            is DeepLinkAction.OpenSession -> {
                nav.navigate(Routes.chat(action.sessionId)) {
                    popUpTo(Routes.SESSION_LIST) {
                        inclusive = false
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            is DeepLinkAction.CreateEnvironmentVariable -> {
                DeepLinkCoordinator.setPendingEnvVarCreate(action.key, action.value, action.note)
                nav.navigate(Routes.ENV_VARS)
            }
            is DeepLinkAction.OpenSettingsScreen -> {
                nav.navigate(action.route)
            }
            is DeepLinkAction.OpenPermissionSettings -> {
                nav.navigate(Routes.PERMISSIONS)
            }
            is DeepLinkAction.OpenHtmlPreview -> {
                DeepLinkCoordinator.setPendingHtmlPreview(
                    action.sessionId,
                    action.resourcePath,
                    action.title,
                )
                nav.navigate(Routes.chat(action.sessionId))
            }
            is DeepLinkAction.NewChat,
            is DeepLinkAction.NewVoiceChat,
            is DeepLinkAction.NewCameraChat -> {
                when (action) {
                    is DeepLinkAction.NewVoiceChat -> DeepLinkCoordinator
                        .setPendingChatAction(DeepLinkCoordinator.ChatAction.START_VOICE)
                    is DeepLinkAction.NewCameraChat -> DeepLinkCoordinator
                        .setPendingChatAction(DeepLinkCoordinator.ChatAction.OPEN_CAMERA)
                    else -> {}
                }
                val newRoute = Routes.chat("__new__${java.util.UUID.randomUUID()}")
                nav.navigate(newRoute) {
                    popUpTo(Routes.SESSION_LIST) { inclusive = false }
                    launchSingleTop = true
                }
            }
            is DeepLinkAction.OpenAlarmList -> {
                val showAlarms = Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(showAlarms)
                } catch (_: android.content.ActivityNotFoundException) {
                    AppLogger.warning("DeepLink", "OpenAlarmList: no Clock app handles SHOW_ALARMS")
                }
            }
            else -> {}
        }
    }
}

internal fun keepScreenOnNeedsChange(flags: Int, want: Boolean): Boolean {
    val on = (flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
    return on != want
}
