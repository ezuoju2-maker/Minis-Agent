package com.openminis.app.service

import android.content.Context
import android.os.SystemClock
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class OAuthWaitFlows(private val maxHoldMs: Long) {

    private data class Flow(val reason: String, val deadline: Long)

    private val flows = LinkedHashMap<String, Flow>()

    val count: Int get() = flows.size

    fun add(id: String, reason: String, now: Long): Int {
        flows[id] = Flow(reason, now + maxHoldMs)
        return flows.size
    }

    fun remove(id: String): Boolean = flows.remove(id) != null

    fun prune(now: Long): List<String> {
        val expired = flows.filterValues { it.deadline <= now }
        expired.keys.forEach { flows.remove(it) }
        return expired.values.map { it.reason }
    }

    fun nextDeadline(): Long? = flows.values.minOfOrNull { it.deadline }

    fun clear(): Int {
        val n = flows.size
        flows.clear()
        return n
    }
}

object OAuthWaitKeepAlive {

    private const val TAG = "OAuthWait"

    internal const val MAX_HOLD_MS = 5 * 60_000L

    private val lock = Any()
    private val flows = OAuthWaitFlows(MAX_HOLD_MS)
    private val _active = MutableStateFlow(0)

    val active: StateFlow<Int> = _active.asStateFlow()

    @Volatile private var appContext: Context? = null
    private var seq = 0

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    fun begin(reason: String): String {
        val (id, n) = synchronized(lock) {
            val id = "w${++seq}"
            id to flows.add(id, "$reason#$id", now()).also { _active.value = it }
        }
        AppLogger.info(TAG, "[OAuthWait] start reason=$reason id=$id active=$n")
        val ctx = appContext
        if (ctx == null) {
            AppLogger.warning(TAG, "[OAuthWait] not installed - no keep-alive for id=$id")
        } else {
            OAuthWaitService.start(ctx)
        }
        return id
    }

    fun end(id: String, reason: String) {
        val n = synchronized(lock) {
            if (!flows.remove(id)) return
            flows.count.also { _active.value = it }
        }
        AppLogger.info(TAG, "[OAuthWait] stop reason=$reason id=$id active=$n")
    }

    internal fun prune(): Int {
        val (expired, n) = synchronized(lock) {
            val e = flows.prune(now())
            e to flows.count.also { _active.value = it }
        }
        for (r in expired) {
            AppLogger.warning(TAG, "[OAuthWait] stop reason=deadline(${MAX_HOLD_MS / 1000}s) flow=$r active=$n")
        }
        return n
    }

    internal fun nextDeadline(): Long? = synchronized(lock) { flows.nextDeadline() }

    internal fun clear(reason: String) {
        val dropped = synchronized(lock) { flows.clear().also { _active.value = 0 } }
        if (dropped > 0) AppLogger.info(TAG, "[OAuthWait] stop reason=$reason dropped=$dropped active=0")
    }

    internal fun now(): Long = SystemClock.elapsedRealtime()
}
