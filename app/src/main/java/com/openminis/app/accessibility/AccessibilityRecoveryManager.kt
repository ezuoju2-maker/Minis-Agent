package com.openminis.app.accessibility

import android.content.Context
import android.provider.Settings
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

object AccessibilityRecoveryManager {

    private const val TAG = "A11yRecovery"

    const val PROMPT_TIMEOUT_MS: Long = 60_000L

    private const val REBIND_TIMEOUT_MS: Long = 5_000L
    private const val REBIND_POLL_MS: Long = 250L

    private const val CHECK_CACHE_TTL_MS: Long = 250L

    @Volatile private var cachedRevoked: Boolean? = null
    @Volatile private var cachedAtMs: Long = 0L

    enum class Decision { REPAIR, OPEN_SETTINGS, CANCEL }

    data class RepairPrompt(
        val shizukuAvailable: Boolean,
    )

    private val _pendingPrompt = MutableStateFlow<RepairPrompt?>(null)
    val pendingPrompt: StateFlow<RepairPrompt?> = _pendingPrompt.asStateFlow()

    private var continuation: Continuation<Decision>? = null

    private val _revoked = MutableStateFlow(false)
    val revoked: StateFlow<Boolean> = _revoked.asStateFlow()

    private fun componentId(context: Context): String =
        "${context.packageName}/${MinisAccessibilityService::class.java.name}"

    private const val PREFS = "a11y_recovery"
    private const val KEY_EVER_GRANTED = "ever_granted"

    fun hasEverBeenGranted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_EVER_GRANTED, false)

    fun markGranted(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(KEY_EVER_GRANTED, false)) {
            p.edit().putBoolean(KEY_EVER_GRANTED, true).apply()
            AppLogger.info(TAG, "grant latched (first successful connect)")
        }
    }

    fun isGrantRevoked(context: Context): Boolean {
        val now = System.currentTimeMillis()
        cachedRevoked?.let { if (now - cachedAtMs < CHECK_CACHE_TTL_MS) return it }

        val revoked = try {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            isRevokedIn(
                enabledValue = enabled,
                pkg = context.packageName,
                serviceClass = MinisAccessibilityService::class.java.name,
            )
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "isGrantRevoked read failed: ${t.message}")
            false
        }

        cachedRevoked = revoked
        cachedAtMs = now
        return revoked
    }

    fun isRevokedIn(enabledValue: String?, pkg: String, serviceClass: String): Boolean {
        if (enabledValue.isNullOrBlank()) return true
        val fq = "$pkg/$serviceClass"
        val short = "$pkg/.${serviceClass.removePrefix("$pkg.")}"
        return enabledValue.split(':').none { entry ->
            val e = entry.trim()
            e.equals(fq, ignoreCase = true) || e.equals(short, ignoreCase = true)
        }
    }

    fun invalidateCache() {
        cachedRevoked = null
        cachedAtMs = 0L
    }

    fun refreshRevokedState(context: Context) {
        invalidateCache()
        _revoked.value = isGrantRevoked(context)
    }

    suspend fun repairWithShizuku(context: Context): Boolean {
        if (!ShizukuManager.isReady()) {
            AppLogger.info(TAG, "repair skipped: Shizuku not ready")
            return false
        }
        val target = componentId(context)

        val existing = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )?.split(':')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        } catch (_: Throwable) { emptyList() }

        val merged = (existing + target).distinct().joinToString(":")

        val r1 = ShizukuManager.runProcess(
            arrayOf("settings", "put", "secure", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged)
        )
        if (r1.exitCode != 0) {
            AppLogger.warning(TAG, "repair failed writing service list: exit=${r1.exitCode} ${r1.combined}")
            return false
        }
        val r2 = ShizukuManager.runProcess(
            arrayOf("settings", "put", "secure", Settings.Secure.ACCESSIBILITY_ENABLED, "1")
        )
        if (r2.exitCode != 0) {
            AppLogger.warning(TAG, "repair failed writing accessibility_enabled: exit=${r2.exitCode} ${r2.combined}")
            return false
        }

        val bound = withTimeoutOrNull(REBIND_TIMEOUT_MS) {
            while (MinisAccessibilityService.getInstance() == null) delay(REBIND_POLL_MS)
            true
        } == true

        invalidateCache()
        _revoked.value = isGrantRevoked(context)
        AppLogger.info(TAG, "repair via Shizuku wrote grant; rebound=$bound")
        return true
    }

    private suspend fun awaitDecision(prompt: RepairPrompt): Decision {
        val decision = withTimeoutOrNull(PROMPT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Decision> { cont ->
                continuation = cont
                _pendingPrompt.value = prompt
                cont.invokeOnCancellation {
                    _pendingPrompt.value = null
                    continuation = null
                }
            }
        }
        if (decision == null) {
            _pendingPrompt.value = null
            continuation = null
            AppLogger.info(TAG, "repair prompt timed out after ${PROMPT_TIMEOUT_MS}ms — treating as cancel")
        }
        return decision ?: Decision.CANCEL
    }

    fun respond(decision: Decision) {
        _pendingPrompt.value = null
        val c = continuation
        continuation = null
        c?.resume(decision)
    }

    suspend fun ensureGrantOrPrompt(context: Context): Boolean {
        if (!isGrantRevoked(context)) return true

        val shizuku = ShizukuManager.isReady()
        AppLogger.info(TAG, "grant revoked — prompting (shizukuReady=$shizuku)")
        _revoked.value = true

        return when (awaitDecision(RepairPrompt(shizukuAvailable = shizuku))) {
            Decision.REPAIR -> repairWithShizuku(context)
            Decision.OPEN_SETTINGS -> false
            Decision.CANCEL -> false
        }
    }
}
