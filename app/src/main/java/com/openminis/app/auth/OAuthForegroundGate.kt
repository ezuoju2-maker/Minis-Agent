package com.openminis.app.auth

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object OAuthForegroundGate {

    enum class Phase {
        AWAITING_BROWSER,

        COMPLETING,
    }

    private val _phase = MutableStateFlow<Phase?>(null)
    val phase: StateFlow<Phase?> = _phase.asStateFlow()

    fun begin() { _phase.value = Phase.AWAITING_BROWSER }

    fun end() { _phase.value = null }

    internal const val MAX_WAIT_MS = 5 * 60_000L

    internal fun isForeground(state: Lifecycle.State): Boolean = state.isAtLeast(Lifecycle.State.STARTED)

    suspend fun awaitForeground(tag: String) {
        _phase.value = Phase.COMPLETING
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val state = withContext(Dispatchers.Main.immediate) { lifecycle.currentState }
        if (isForeground(state)) return

        AppLogger.info(
            tag,
            "[OAuth] callback arrived with the app in the background ($state) — " +
                "holding the token exchange until Minis is foreground (background network is blocked)",
        )
        val started = System.currentTimeMillis()
        val arrived = withTimeoutOrNull(MAX_WAIT_MS) {
            withContext(Dispatchers.Main.immediate) { awaitStarted(lifecycle) }
        }
        val waited = System.currentTimeMillis() - started
        if (arrived == null) {
            AppLogger.warning(tag, "[OAuth] still background after ${waited}ms — attempting the exchange anyway")
        } else {
            AppLogger.info(tag, "[OAuth] foreground after ${waited}ms — exchanging")
        }
    }

    private suspend fun awaitStarted(lifecycle: Lifecycle): Unit =
        suspendCancellableCoroutine { cont ->
            if (isForeground(lifecycle.currentState)) {
                cont.resume(Unit)
                return@suspendCancellableCoroutine
            }
            val observer = object : LifecycleEventObserver {
                override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
                    if (isForeground(lifecycle.currentState) && cont.isActive) {
                        lifecycle.removeObserver(this)
                        cont.resume(Unit)
                    }
                }
            }
            lifecycle.addObserver(observer)
            cont.invokeOnCancellation {
                android.os.Handler(android.os.Looper.getMainLooper()).post { lifecycle.removeObserver(observer) }
            }
        }
}
