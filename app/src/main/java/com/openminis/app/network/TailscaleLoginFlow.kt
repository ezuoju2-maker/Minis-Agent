package com.openminis.app.network

import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TailscaleLoginFlow(
    private val scope: CoroutineScope,
    private val status: () -> TailscaleStatus,
    private val returnToApp: () -> Unit,
    private val publish: (TailscaleStatus) -> Unit = {},
    private val freshLogin: suspend () -> Unit = {},
    private val urlPollMs: Long = 400,
    private val urlTimeoutMs: Long = 20_000,
    private val loginPollMs: Long = 1_000,
    private val loginTimeoutMs: Long = 10 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface State {
        data object Idle : State
        data object OpeningPage : State
        data object InBrowser : State
        data class Failed(val reason: Reason) : State
    }

    enum class Reason {
        NO_SIGN_IN_PAGE,
        NO_BROWSER,
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _opens = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val opens: SharedFlow<String> = _opens.asSharedFlow()

    @Volatile var lastUrl: String? = null
        private set

    private var job: Job? = null
    private var leftApp = false

    private val opened = LinkedHashSet<String>()

    @Synchronized
    fun begin() {
        if (_state.value == State.OpeningPage) return
        job?.cancel()
        leftApp = false
        _state.value = State.OpeningPage
        AppLogger.info(TAG, "[Net][TS] login: requested, waiting for the sign-in page")
        job = scope.launch {
            var started = now()
            var restarted = false
            while (true) {
                val st = status()
                if (st.isLoggedIn) {
                    AppLogger.info(TAG, "[Net][TS] login: already signed in")
                    publish(st)
                    _state.value = State.Idle
                    return@launch
                }
                val url = st.authURL
                val stale = url != null && synchronized(opened) { url in opened }
                val failed = st.loginError
                if (!restarted && (stale || failed != null)) {
                    AppLogger.info(TAG, "[Net][TS] login: the last sign-in page can no longer complete (" +
                        (failed?.replace(LOGIN_LINK, "$1<redacted>") ?: "already opened once") + "); restarting the node for a new one")
                    restarted = true
                    freshLogin()
                    started = now()
                    continue
                }
                if (url != null && !stale) {
                    synchronized(opened) {
                        opened += url
                        if (opened.size > 8) opened.remove(opened.first())
                    }
                    lastUrl = url
                    AppLogger.info(TAG, "[Net][TS] login: sign-in page ready after ${now() - started}ms, opening the browser")
                    _state.value = State.InBrowser
                    _opens.tryEmit(url)
                    waitForSignIn()
                    return@launch
                }
                if (now() - started >= urlTimeoutMs) {
                    AppLogger.warning(TAG, "[Net][TS] login: no sign-in page after ${urlTimeoutMs}ms (state=${st.backendState.ifEmpty { "none" }}${st.error?.let { ", error" } ?: ""})")
                    _state.value = State.Failed(Reason.NO_SIGN_IN_PAGE)
                    return@launch
                }
                delay(urlPollMs)
            }
        }
    }

    private suspend fun waitForSignIn() {
        val started = now()
        while (true) {
            delay(loginPollMs)
            val st = status()
            if (st.isLoggedIn) {
                AppLogger.info(TAG, "[Net][TS] login: authorized after ${now() - started}ms, returning to the app")
                publish(st)
                _state.value = State.Idle
                returnToApp()
                return
            }
            if (now() - started >= loginTimeoutMs) {
                AppLogger.warning(TAG, "[Net][TS] login: timed out after ${loginTimeoutMs}ms in the browser")
                _state.value = State.Idle
                return
            }
        }
    }

    @Synchronized
    fun browserUnavailable() {
        job?.cancel()
        AppLogger.warning(TAG, "[Net][TS] login: no browser could open the sign-in page; offering the link to copy")
        _state.value = State.Failed(Reason.NO_BROWSER)
    }

    fun appHidden() {
        if (_state.value == State.InBrowser) leftApp = true
    }

    @Synchronized
    fun appShown() {
        if (_state.value != State.InBrowser || !leftApp) return
        leftApp = false
        job?.cancel()
        AppLogger.info(TAG, "[Net][TS] login: user returned without completing the sign-in")
        _state.value = State.Idle
        scope.launch { publish(status()) }
    }

    fun reset() {
        job?.cancel()
        _state.value = State.Idle
    }

    private fun now(): Long = clock()

    private companion object {
        const val TAG = "Network"
        val LOGIN_LINK = Regex("""(login\.tailscale\.com/a/)[A-Za-z0-9]+""")
    }
}
