package com.openminis.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.LinkedList
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

object SessionConcurrencyManager {
    const val MAX_CONCURRENT = 6

    @Volatile
    var maxConcurrent: Int = MAX_CONCURRENT
        private set

    fun debugSetMaxConcurrent(n: Int?): Int {
        if (!com.openminis.app.BuildConfig.DEV_TOOLS) return maxConcurrent
        maxConcurrent = (n ?: MAX_CONCURRENT).coerceIn(1, 32)
        while (true) {
            val next = synchronized(this) {
                if (_runningSessions.value.size < maxConcurrent) waitQueue.pollFirst() else null
            } ?: break
            _suspendedSessions.value = _suspendedSessions.value - next.sessionId
            _runningSessions.value = _runningSessions.value + next.sessionId
            next.continuation.resume(Unit)
        }
        println("[T-loadtest-limits] maxConcurrent=$maxConcurrent")
        return maxConcurrent
    }

    private const val SLOT_WAIT_WARN_MS = 10_000L

    private val _runningSessions = MutableStateFlow<Set<String>>(emptySet())
    val runningSessions: StateFlow<Set<String>> = _runningSessions.asStateFlow()

    private val _suspendedSessions = MutableStateFlow<List<String>>(emptyList())
    val suspendedSessions: StateFlow<List<String>> = _suspendedSessions.asStateFlow()

    private data class Waiter(val sessionId: String, val continuation: Continuation<Unit>)
    private val waitQueue = LinkedList<Waiter>()

    suspend fun acquireSlot(sessionId: String) {
        val fast = synchronized(this) {
            if (_runningSessions.value.size < maxConcurrent) {
                _runningSessions.value = _runningSessions.value + sessionId
                true
            } else false
        }
        if (fast) {
            println(
                "[T-STALL-DIAG] slot ACQUIRED-fast sid=$sessionId " +
                    "running=${_runningSessions.value.size}/$maxConcurrent " +
                    "holders=${_runningSessions.value.joinToString(",")}",
            )
            return
        }

        val holdersAtWait = _runningSessions.value.toList()
        val waitStartMs = android.os.SystemClock.elapsedRealtime()
        println(
            "[T-STALL-DIAG] slot WAIT-BEGIN sid=$sessionId " +
                "running=${holdersAtWait.size}/$maxConcurrent " +
                "holders=${holdersAtWait.joinToString(",")} " +
                "queueDepth=${_suspendedSessions.value.size}",
        )
        val watchdog = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            var waited = 0L
            while (true) {
                kotlinx.coroutines.delay(SLOT_WAIT_WARN_MS)
                waited += SLOT_WAIT_WARN_MS
                println(
                    "[T-STALL-DIAG] slot STILL-WAITING sid=$sessionId waitedMs=$waited " +
                        "holders=${_runningSessions.value.joinToString(",")} " +
                        "queueDepth=${_suspendedSessions.value.size} " +
                        "— if these holders are not live turns, slots have LEAKED",
                )
            }
        }

        synchronized(this) { _suspendedSessions.value = _suspendedSessions.value + sessionId }
        try {
            suspendCancellableCoroutine { cont ->
                val admitted = synchronized(this@SessionConcurrencyManager) {
                    if (_runningSessions.value.size < maxConcurrent) {
                        _runningSessions.value = _runningSessions.value + sessionId
                        _suspendedSessions.value = _suspendedSessions.value - sessionId
                        true
                    } else {
                        waitQueue.add(Waiter(sessionId, cont))
                        false
                    }
                }
                if (admitted) {
                    cont.resume(Unit)
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation {
                    synchronized(this@SessionConcurrencyManager) {
                        waitQueue.removeAll { it.sessionId == sessionId }
                        _suspendedSessions.value = _suspendedSessions.value - sessionId
                    }
                }
            }
        } finally {
            watchdog.cancel()
            println(
                "[T-STALL-DIAG] slot WAIT-END sid=$sessionId " +
                    "waitedMs=${android.os.SystemClock.elapsedRealtime() - waitStartMs}",
            )
        }
    }

    fun diagSnapshot(): String =
        "running=${_runningSessions.value.size}/$maxConcurrent " +
            "holders=${_runningSessions.value.joinToString(",")} " +
            "suspended=${_suspendedSessions.value.joinToString(",")}"

    @Synchronized
    fun releaseSlot(sessionId: String) {
        val had = sessionId in _runningSessions.value
        _runningSessions.value = _runningSessions.value - sessionId
        println(
            "[T-STALL-DIAG] slot RELEASED sid=$sessionId wasHeld=$had " +
                "running=${_runningSessions.value.size}/$maxConcurrent " +
                "holders=${_runningSessions.value.joinToString(",")}",
        )

        val next = synchronized(this) {
            if (_runningSessions.value.size < maxConcurrent) waitQueue.pollFirst() else null
        }
        if (next != null) {
            _suspendedSessions.value = _suspendedSessions.value - next.sessionId
            _runningSessions.value = _runningSessions.value + next.sessionId
            next.continuation.resume(Unit)
        }
    }

    fun isSuspended(sessionId: String): Boolean = sessionId in _suspendedSessions.value
}
