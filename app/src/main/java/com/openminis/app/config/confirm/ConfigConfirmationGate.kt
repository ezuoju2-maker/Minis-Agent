package com.openminis.app.config.confirm

import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object ConfigConfirmationGate {
    private const val TAG = "ConfigConfirmGate"

    const val TIMEOUT_MS: Long = 120_000

    @Volatile
    var backgroundNotifier: ((PendingConfigChange) -> Boolean)? = null

    @Volatile
    var cancelNotification: ((String) -> Unit)? = null

    private val notifiedIds = HashSet<String>()

    private val _pending = MutableStateFlow<PendingConfigChange?>(null)
    val pending: StateFlow<PendingConfigChange?> = _pending.asStateFlow()

    private val queue: ArrayDeque<QueuedRequest> = ArrayDeque()

    private val awaiters = HashMap<String, Continuation<ConfirmOutcome>>()
    private val timeouts = HashMap<String, Job>()

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class QueuedRequest(
        val change: PendingConfigChange,
        val cont: Continuation<ConfirmOutcome>,
    )

    suspend fun requestConfirmation(change: PendingConfigChange): ConfirmOutcome =
        suspendCoroutine { cont ->
            scope.launch {
                mutex.withLock {
                    awaiters[change.id] = cont
                    timeouts[change.id] = scope.launch {
                        delay(TIMEOUT_MS)
                        timeout(change.id)
                    }
                    if (_pending.value == null) {
                        _pending.value = change
                        notifyIfBackgrounded(change)
                    } else {
                        queue.addLast(QueuedRequest(change, cont))
                    }
                }
            }
        }

    fun userApprove(items: List<PendingConfigChangeItem>) {
        val current = _pending.value ?: return
        scope.launch { resolve(current.id, ConfirmOutcome.Approved(items)) }
    }

    fun userReject() {
        val current = _pending.value ?: return
        scope.launch { resolve(current.id, ConfirmOutcome.Rejected) }
    }

    private suspend fun timeout(id: String) {
        if (awaiters[id] == null) return
        AppLogger.info(TAG, "timeout for change $id")
        resolve(id, ConfirmOutcome.TimedOut)
    }

    private suspend fun resolve(id: String, outcome: ConfirmOutcome) {
        val cont: Continuation<ConfirmOutcome>?
        val advance: Boolean
        var advancedTo: PendingConfigChange? = null
        mutex.withLock {
            cont = awaiters.remove(id)
            timeouts.remove(id)?.cancel()
            notifiedIds.remove(id)
            advance = _pending.value?.id == id
            if (advance) {
                val next = queue.removeFirstOrNull()
                _pending.value = next?.change
                advancedTo = next?.change
            }
        }
        cancelNotification?.invoke(id)
        advancedTo?.let { notifyIfBackgrounded(it) }
        cont?.resume(outcome)
    }

    fun notifyPending() {
        _pending.value?.let { notifyIfBackgrounded(it) }
    }

    private fun notifyIfBackgrounded(change: PendingConfigChange) {
        val notifier = backgroundNotifier ?: return
        scope.launch {
            mutex.withLock {
                if (notifiedIds.contains(change.id)) return@launch
                if (_pending.value?.id != change.id) return@launch
                if (notifier(change)) notifiedIds.add(change.id)
            }
        }
    }
}
