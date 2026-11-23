package com.openminis.app.sandbox.offload

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.openminis.app.MinisApp
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.ui.chat.ChatViewModelStore
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking

internal object BrowserCliPools {

    private const val TAG = "BrowserCliPools"

    private val fallbackPools = ConcurrentHashMap<String, BrowserTabPool>()

    sealed interface Resolution<out P> {
        data class Found<P>(val pool: P, val via: String) : Resolution<P>
        data class SessionGone(val sessionId: String) : Resolution<Nothing>
    }

    internal fun <P> choose(
        callerSid: String?,
        mountedSid: String?,
        liveVm: (String) -> P?,
        fallback: (String) -> P?,
        sessionExists: (String) -> Boolean,
        create: (String) -> P,
        shared: () -> P,
    ): Resolution<P> {
        if (callerSid == null) return Resolution.Found(shared(), "shared(no-session)")
        liveVm(callerSid)?.let { return Resolution.Found(it, "vm") }
        val owner = mountedSid ?: callerSid
        if (owner != callerSid) liveVm(owner)?.let { return Resolution.Found(it, "vm(mounted)") }
        fallback(owner)?.let { return Resolution.Found(it, "fallback") }
        if (!sessionExists(owner)) return Resolution.SessionGone(owner)
        return Resolution.Found(create(owner), "fallback(new)")
    }

    fun resolve(app: MinisApp, callerSid: String?): Resolution<BrowserTabPool> {
        val mounted = callerSid?.let { ExecutionCoordinator.mountedSessionIdFor(it) }
        return choose(
            callerSid = callerSid,
            mountedSid = mounted,
            liveVm = { sid -> ChatViewModelStore.existing(sid)?.browserTabPool },
            fallback = { sid -> fallbackPools[sid] },
            sessionExists = { sid ->
                val dao = app.chatRepositoryOrNull?.dao
                dao != null && runCatching { runBlocking { dao.getSession(sid) } != null }.getOrDefault(false)
            },
            create = { sid ->
                fallbackPools.getOrPut(sid) {
                    Log.i(TAG, "fallback browser pool for session ${sid.take(8)} (no live chat view model)")
                    BrowserTabPool(app).also { it.setSession(sid) }
                }
            },
            shared = { app.sharedBrowserTabPool },
        )
    }

    fun release(sessionId: String) {
        val pool = fallbackPools.remove(sessionId) ?: return
        Handler(Looper.getMainLooper()).post { pool.destroyAllTabs() }
        Log.i(TAG, "released fallback browser pool for session ${sessionId.take(8)}")
    }
}
