package com.openminis.app.data

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.openminis.app.BuildConfig
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object UpdateAutoChecker {

    private const val TAG = "UpdateAuto"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        UpdateStateStore.init(app)
        if (!BuildConfig.AUTO_UPDATE_CHECK) {
            AppLogger.info(TAG, "[UpdateCheck] auto checks off for this build (store)")
            return
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    maybeCheck(app, "foreground")
                }
            },
        )
    }

    fun maybeCheck(context: Context, reason: String) {
        val app = context.applicationContext
        UpdateStateStore.init(app)
        val decision = UpdatePolicy.autoDecision(
            now = System.currentTimeMillis(),
            state = UpdateStateStore.throttleState(app),
            channel = UpdateStateStore.channel.value,
            localCode = BuildConfig.VERSION_CODE,
            buildAllowsAuto = BuildConfig.AUTO_UPDATE_CHECK,
            userEnabled = UpdateStateStore.autoEnabled.value,
            inFlight = UpdateChecker.isChecking,
        )
        AppLogger.info(TAG, "[UpdateCheck] auto reason=$reason decision=$decision channel=${UpdateStateStore.channel.value.id}")
        if (decision != UpdatePolicy.AutoDecision.CHECK && decision != UpdatePolicy.AutoDecision.INVALIDATE_AND_CHECK) return
        scope.launch { UpdateChecker.check(app, reason = "auto-$reason") }
    }
}
