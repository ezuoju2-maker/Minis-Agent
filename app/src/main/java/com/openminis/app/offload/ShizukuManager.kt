package com.openminis.app.offload

import android.app.Application
import android.content.Context
import android.os.Build
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ShizukuManager {
    private const val TAG = "ShizukuManager"

    enum class State { NOT_INSTALLED, NOT_RUNNING, NEED_PERMISSION, READY }

    data class Snapshot(
        val state: State,
        val version: Int = -1,
        val uid: Int = -1,
        val isSui: Boolean = false,
    )

    data class ProcessResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val combined: String get() = if (stderr.isEmpty()) stdout else "$stdout\n$stderr".trimEnd()
    }

    @Volatile private var initialized = false
    @Volatile private var appContext: Context? = null
    private var backend: ShizukuBackend? = null

    private val _snapshot = MutableStateFlow(Snapshot(State.NOT_INSTALLED))
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    fun init(app: Application) {
        if (initialized) return
        initialized = true
        appContext = app.applicationContext
        backend = ShizukuBackend(app.applicationContext).also { b ->
            runCatching { b.registerListeners { recompute("listener") } }
                .onFailure { AppLogger.warning(TAG, "registerListeners failed: ${it.message}") }
        }
        recompute("init")
    }

    fun refresh() = recompute("manual-refresh")

    fun isReady(): Boolean = _snapshot.value.state == State.READY

    fun isInstalled(): Boolean = backend?.isInstalled() == true

    fun isSui(): Boolean = _snapshot.value.isSui

    fun installedManagerPackage(): String? = backend?.installedManagerPackage()

    fun requestPermission(): Boolean {
        val b = backend ?: return false
        if (!b.isBinderAlive()) return false
        b.requestPermission()
        return true
    }

    fun openShizukuApp(context: Context) {
        backend?.openManagerApp(context)
    }

    fun openInstallPage(context: Context) {
        backend?.openInstallPage(context, ShizukuBackend.SHIZUKU_GITHUB_URL)
    }

    fun openInstallPage(context: Context, url: String) {
        backend?.openInstallPage(context, url)
    }

    fun runProcess(
        argv: Array<String>,
        env: Array<String>? = null,
        cwd: String? = null,
        timeoutMs: Long = 5_000L,
    ): ProcessResult {
        if (!isReady()) {
            return ProcessResult(
                exitCode = 126,
                stdout = "",
                stderr = "shizuku not ready (state=${_snapshot.value.state})",
            )
        }
        return backend!!.runProcess(argv, env, cwd, timeoutMs)
    }

    fun deviceSdk(): Int = Build.VERSION.SDK_INT

    private fun recompute(reason: String) {
        val b = backend ?: run {
            _snapshot.value = Snapshot(State.NOT_INSTALLED)
            return
        }
        val snap = b.snapshot()
        val prev = _snapshot.value
        _snapshot.value = snap
        if (snap.state != prev.state || snap.version != prev.version ||
            snap.uid != prev.uid || snap.isSui != prev.isSui
        ) {
            val provider = when {
                snap.isSui -> "sui"
                else -> b.installedManagerPackage() ?: "none"
            }
            AppLogger.info(
                TAG,
                "state=${snap.state} ver=${snap.version} uid=${snap.uid} provider=$provider ($reason)",
            )
        }
    }
}
