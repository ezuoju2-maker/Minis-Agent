package com.openminis.app.offload

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object OffloadPermissionManager {

    enum class PermissionLevel {
        BYPASS,
        ASK_ONCE,
        NOT_ALLOWED,
    }

    data class PermissionRequest(
        val toolName: String,
        val toolTitle: String,
        val description: String,
        val sessionId: String,
    )

    enum class PermissionCategory(val displayName: String) {
        PRIVACY("Privacy"),
        MEDIA("Media"),
        SYSTEM("System"),
        INTEGRATIONS("Integrations"),
    }

    data class ToolPermissionInfo(
        val toolName: String,
        val displayName: String,
        val category: PermissionCategory,
        val defaultLevel: PermissionLevel,
        val showInSettings: Boolean = true,
    )

    val toolRegistry: List<ToolPermissionInfo> = listOf(
        ToolPermissionInfo("calendar", "Calendar", PermissionCategory.PRIVACY, PermissionLevel.BYPASS),
        ToolPermissionInfo("location", "Location", PermissionCategory.PRIVACY, PermissionLevel.BYPASS),
        ToolPermissionInfo("clipboard", "Clipboard", PermissionCategory.PRIVACY, PermissionLevel.BYPASS),
        ToolPermissionInfo("contacts", "Contacts", PermissionCategory.PRIVACY, PermissionLevel.BYPASS),
        ToolPermissionInfo("photos", "Photos", PermissionCategory.PRIVACY, PermissionLevel.BYPASS),
        ToolPermissionInfo("speak", "Text-to-Speech", PermissionCategory.MEDIA, PermissionLevel.BYPASS, showInSettings = false),
        ToolPermissionInfo("media_player", "Media Player", PermissionCategory.MEDIA, PermissionLevel.BYPASS, showInSettings = false),
        ToolPermissionInfo("speech_recognition", "Speech Recognition", PermissionCategory.MEDIA, PermissionLevel.BYPASS, showInSettings = false),
        ToolPermissionInfo("alarm", "Alarms & Timers", PermissionCategory.SYSTEM, PermissionLevel.BYPASS, showInSettings = false),
        ToolPermissionInfo("weather", "Weather", PermissionCategory.SYSTEM, PermissionLevel.BYPASS, showInSettings = false),
        ToolPermissionInfo("notification", "Notifications", PermissionCategory.SYSTEM, PermissionLevel.BYPASS, showInSettings = false),
        ToolPermissionInfo("device_info", "Device Info", PermissionCategory.SYSTEM, PermissionLevel.BYPASS, showInSettings = false),
        ToolPermissionInfo("a11y_cli", "android-a11y-cli", PermissionCategory.INTEGRATIONS, PermissionLevel.NOT_ALLOWED),
        ToolPermissionInfo("shizuku_cli", "android-shizuku-cli", PermissionCategory.INTEGRATIONS, PermissionLevel.NOT_ALLOWED),
    )

    const val OFFLOAD_GLOBAL_SESSION_ID = "offload-global"

    private lateinit var prefs: SharedPreferences

    private val sessionGrants = mutableMapOf<String, MutableSet<String>>()

    private val sessionDenials = mutableMapOf<String, MutableSet<String>>()

    private val _pendingRequest = MutableStateFlow<PermissionRequest?>(null)
    val pendingRequest: StateFlow<PermissionRequest?> = _pendingRequest.asStateFlow()

    @Volatile private var pendingContinuation: kotlin.coroutines.Continuation<Response>? = null

    const val DECISION_TIMEOUT_MS: Long = 5 * 60_000L

    private val permissionDialogTurn = Mutex()
    private val settingsGateTurn = Mutex()

    private const val TAG = "OffloadPermission"


    enum class AndroidPermissionResult { GRANTED, DENIED, TIMEOUT }

    const val SYSTEM_DIALOG_TIMEOUT_MS: Long = 120_000L

    const val SETTINGS_GATE_TIMEOUT_MS: Long = 120_000L

    data class AndroidPermissionRequest(val permissions: List<String>)

    private val _pendingAndroidPermission = MutableStateFlow<AndroidPermissionRequest?>(null)
    val pendingAndroidPermission: StateFlow<AndroidPermissionRequest?> = _pendingAndroidPermission.asStateFlow()

    private var androidPermissionContinuation: kotlin.coroutines.Continuation<AndroidPermissionResult>? = null

    suspend fun requestAndroidPermission(permissions: List<String>): AndroidPermissionResult {
        val timed = withTimeoutOrNull(SYSTEM_DIALOG_TIMEOUT_MS) {
            suspendCancellableCoroutine<AndroidPermissionResult> { cont ->
                androidPermissionContinuation = cont
                _pendingAndroidPermission.value = AndroidPermissionRequest(permissions)
                cont.invokeOnCancellation {
                    _pendingAndroidPermission.value = null
                    androidPermissionContinuation = null
                }
            }
        }
        if (timed == null) {
            _pendingAndroidPermission.value = null
            androidPermissionContinuation = null
            return AndroidPermissionResult.TIMEOUT
        }
        return timed
    }

    fun respondToAndroidPermission(result: AndroidPermissionResult) {
        _pendingAndroidPermission.value = null
        androidPermissionContinuation?.resume(result)
        androidPermissionContinuation = null
    }

    fun respondToAndroidPermission(granted: Boolean) =
        respondToAndroidPermission(
            if (granted) AndroidPermissionResult.GRANTED else AndroidPermissionResult.DENIED
        )


    data class SettingsGateRequest(
        val id: String,
        val title: String,
        val message: String,
        val settingsAction: String,
        val requiresPackageUri: Boolean,
        val positiveLabel: String = "Open Settings",
        val negativeLabel: String = "Cancel",
    )

    enum class SettingsGateDecision { OPEN, CANCEL }

    private val _pendingSettingsGate = MutableStateFlow<SettingsGateRequest?>(null)
    val pendingSettingsGate: StateFlow<SettingsGateRequest?> = _pendingSettingsGate.asStateFlow()

    @Volatile private var settingsGateContinuation: kotlin.coroutines.Continuation<SettingsGateDecision>? = null
    private val settingsGateLock = Any()

    private suspend fun lockWithin(turn: Mutex): Boolean =
        withTimeoutOrNull(DECISION_TIMEOUT_MS) { turn.lock(); true } == true

    private fun timedOut(what: String) {
        runCatching {
            com.openminis.app.logging.AppLogger.warning(TAG, "[GH453] $what within ${DECISION_TIMEOUT_MS / 1000}s; answering as not granted")
        }
    }

    suspend fun requestSettingsGate(
        request: SettingsGateRequest,
        check: () -> Boolean,
    ): AndroidPermissionResult {
        if (!lockWithin(settingsGateTurn)) {
            timedOut("settings gate '${request.id}': no turn")
            return AndroidPermissionResult.TIMEOUT
        }
        try {
            if (check()) return AndroidPermissionResult.GRANTED
            return settingsGateLocked(request, check)
        } finally {
            settingsGateTurn.unlock()
        }
    }

    private suspend fun settingsGateLocked(
        request: SettingsGateRequest,
        check: () -> Boolean,
    ): AndroidPermissionResult {
        val decision = withTimeoutOrNull(DECISION_TIMEOUT_MS) {
            suspendCancellableCoroutine<SettingsGateDecision> { cont ->
                synchronized(settingsGateLock) {
                    settingsGateContinuation = cont
                    _pendingSettingsGate.value = request
                }
                cont.invokeOnCancellation {
                    synchronized(settingsGateLock) {
                        if (_pendingSettingsGate.value === request) {
                            _pendingSettingsGate.value = null
                            settingsGateContinuation = null
                        }
                    }
                }
            }
        }
        if (decision == null) {
            timedOut("settings gate '${request.id}': not answered")
            return AndroidPermissionResult.TIMEOUT
        }
        if (decision == SettingsGateDecision.CANCEL) return AndroidPermissionResult.DENIED

        val granted = withTimeoutOrNull(SETTINGS_GATE_TIMEOUT_MS) {
            while (true) {
                if (check()) return@withTimeoutOrNull true
                delay(500L)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        return when (granted) {
            true -> AndroidPermissionResult.GRANTED
            else -> AndroidPermissionResult.TIMEOUT
        }
    }

    suspend fun pollForPermissionGrant(
        check: () -> Boolean,
        timeoutMs: Long = 5_000L,
        intervalMs: Long = 500L,
    ): Boolean {
        val granted = withTimeoutOrNull(timeoutMs) {
            while (true) {
                if (check()) return@withTimeoutOrNull true
                delay(intervalMs)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        return granted == true
    }

    fun respondToSettingsGate(request: SettingsGateRequest, decision: SettingsGateDecision): Boolean {
        val cont = synchronized(settingsGateLock) {
            if (_pendingSettingsGate.value !== request) return false
            _pendingSettingsGate.value = null
            settingsGateContinuation.also { settingsGateContinuation = null }
        }
        cont?.resume(decision)
        return true
    }

    fun hasAskedForPermission(context: Context, permission: String): Boolean {
        val p = context.getSharedPreferences("offload_permissions_asked", Context.MODE_PRIVATE)
        return p.getBoolean(permission, false)
    }

    fun markPermissionAsked(context: Context, permission: String) {
        context.getSharedPreferences("offload_permissions_asked", Context.MODE_PRIVATE)
            .edit().putBoolean(permission, true).apply()
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences("offload_permissions", Context.MODE_PRIVATE)
    }

    fun getLevel(toolName: String): PermissionLevel {
        val info = toolRegistry.find { it.toolName == toolName }
            ?: return PermissionLevel.BYPASS

        val stored = prefs.getString("level_$toolName", null)
        return if (stored != null) {
            try { PermissionLevel.valueOf(stored) } catch (_: Exception) { info.defaultLevel }
        } else {
            info.defaultLevel
        }
    }

    fun setLevel(toolName: String, level: PermissionLevel) {
        prefs.edit().putString("level_$toolName", level.name).apply()
    }

    fun resetAll() {
        val editor = prefs.edit()
        for (tool in toolRegistry) {
            editor.remove("level_${tool.toolName}")
        }
        editor.apply()
    }

    enum class Response { ALLOW_SESSION, ALLOW_ONCE, DENY_SESSION }

    suspend fun checkPermission(toolName: String, toolTitle: String, sessionId: String): Boolean {
        val level = getLevel(toolName)
        return when (level) {
            PermissionLevel.BYPASS -> true
            PermissionLevel.NOT_ALLOWED -> false
            PermissionLevel.ASK_ONCE -> {
                val denials = sessionDenials.getOrPut(sessionId) { mutableSetOf() }
                if (toolName in denials) return false

                val grants = sessionGrants.getOrPut(sessionId) { mutableSetOf() }
                if (toolName in grants) return true

                val info = toolRegistry.find { it.toolName == toolName }
                if (!lockWithin(permissionDialogTurn)) {
                    timedOut("permission dialog for '$toolName': no turn")
                    return false
                }
                try {
                    if (toolName in denials) return false
                    if (toolName in grants) return true
                    val response = withTimeoutOrNull(DECISION_TIMEOUT_MS) {
                        suspendCancellableCoroutine<Response> { cont ->
                            pendingContinuation = cont
                            _pendingRequest.value = PermissionRequest(
                                toolName = toolName,
                                toolTitle = toolTitle,
                                description = "Allow ${info?.displayName ?: toolName} access?",
                                sessionId = sessionId,
                            )
                            cont.invokeOnCancellation {
                                _pendingRequest.value = null
                                pendingContinuation = null
                            }
                        }
                    }
                    when (response) {
                        null -> {
                            timedOut("permission dialog for '$toolName': not answered")
                            false
                        }
                        Response.ALLOW_SESSION -> {
                            grants.add(toolName)
                            true
                        }
                        Response.ALLOW_ONCE -> true
                        Response.DENY_SESSION -> {
                            denials.add(toolName)
                            false
                        }
                    }
                } finally {
                    permissionDialogTurn.unlock()
                }
            }
        }
    }

    fun respondToRequest(response: Response) {
        _pendingRequest.value = null
        pendingContinuation?.resume(response)
        pendingContinuation = null
    }

    fun clearSessionGrants(sessionId: String) {
        sessionGrants.remove(sessionId)
        sessionDenials.remove(sessionId)
    }
}
