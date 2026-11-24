package com.openminis.app.agent.userask

import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object UserAskCenter {
    private const val TAG = "UserAsk"

    private val lock = Any()
    private val _asks = MutableStateFlow<Map<String, PendingAsk>>(emptyMap())

    val asks: StateFlow<Map<String, PendingAsk>> = _asks.asStateFlow()

    private val waiters = HashMap<String, CancellableContinuation<String>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null

    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

    @Volatile var onChanged: ((PendingAsk) -> Unit)? = null

    @Volatile var onResolvedWithoutWaiter: ((PendingAsk) -> Unit)? = null

    @Volatile var onDeferredResolved: ((PendingAsk) -> Unit)? = null

    private val inProcess = HashSet<String>()

    private val deferredUnstored = HashSet<String>()
    private val deferredHeld = HashSet<String>()


    fun ask(forToolUseId: String?): PendingAsk? =
        forToolUseId?.let { id -> _asks.value.values.firstOrNull { it.toolCallId == id } }

    fun pendingAsks(sessionId: String): List<PendingAsk> =
        _asks.value.values.filter { it.sessionId == sessionId && it.state == UserAskState.PENDING }

    fun hasPendingAsk(sessionId: String?): Boolean = sessionId != null && pendingAsks(sessionId).isNotEmpty()

    fun claim(requestId: String) = synchronized(lock) { inProcess += requestId }

    fun isInProcess(requestId: String): Boolean = synchronized(lock) { requestId in inProcess }


    fun begin(ask: PendingAsk, ownedByToolCall: Boolean = true) {
        var a = ask
        val first = a.questions.indices.firstOrNull { it !in a.submitted }
        if (first != null) a = PendingAsk.preselectRecommended(a.copy(currentIndex = first), first)
        synchronized(lock) {
            if (ownedByToolCall && !a.deferred) inProcess += a.requestId
            _asks.value = _asks.value + (a.requestId to a)
        }
        onChanged?.invoke(a)
        AppLogger.info(
            TAG,
            "[UserAsk] BEGIN req=${a.requestId.take(8)} tool=${a.toolCallId.take(16)} sid=${a.sessionId.take(8)} " +
                "questions=${a.questions.size} types=${a.questions.map { it.type.wire }} timeout=${a.timeoutMinutes}m " +
                "owned=$ownedByToolCall state=${a.state.wire}",
        )
        if (a.state == UserAskState.PENDING) startTicking()
    }

    fun beginDeferred(ask: PendingAsk) {
        val older = _asks.value.values.filter { it.sessionId == ask.sessionId && it.deferred && it.state == UserAskState.PENDING }
        for (o in older) dismiss(o.requestId, UserAskDismissReason.REPLACED_BY_NEW_ASK)
        synchronized(lock) { deferredUnstored += ask.requestId }
        begin(ask.copy(deferred = true), ownedByToolCall = false)
    }

    fun releaseUnstoredDeferred(sessionId: String) {
        val held: List<String> = synchronized(lock) {
            val mine = deferredUnstored.filter { id -> _asks.value[id]?.sessionId?.let { it == sessionId } ?: true }
            deferredUnstored -= mine.toSet()
            mine.filter { deferredHeld.remove(it) }
        }
        for (id in held) _asks.value[id]?.let { onDeferredResolved?.invoke(it) }
    }

    fun pendingDeferred(sessionId: String): List<PendingAsk> = pendingAsks(sessionId).filter { it.deferred }

    suspend fun wait(requestId: String): String {
        _asks.value[requestId]?.let { if (it.state.isTerminal && it.resultJson != null) return it.resultJson }
        return suspendCancellableCoroutine { cont ->
            val done: String? = synchronized(lock) {
                val a = _asks.value[requestId]
                if (a != null && a.state.isTerminal && a.resultJson != null) a.resultJson
                else { waiters[requestId] = cont; null }
            }
            if (done != null) cont.resume(done)
            cont.invokeOnCancellation {
                synchronized(lock) { waiters.remove(requestId) }
                dismiss(requestId, UserAskDismissReason.STOPPED)
            }
        }
    }


    fun updateAnswer(requestId: String, index: Int, answer: UserAskAnswer, userAction: Boolean = true) {
        mutate(requestId) { a ->
            val next = a.setAnswer(answer, index)
            if (userAction) next.noteInteraction(clock()) else next
        }
    }

    fun noteInteraction(requestId: String) = mutate(requestId) { it.noteInteraction(clock()) }

    fun goTo(requestId: String, index: Int) = mutate(requestId) { it.goTo(index, clock()) ?: it }

    fun submitQuestion(requestId: String, index: Int) {
        var complete = false
        mutate(requestId) { a ->
            val (next, outcome) = a.submit(index, clock())
            when (outcome) {
                is UserAskSubmitOutcome.Advanced -> PendingAsk.preselectRecommended(next, outcome.to)
                UserAskSubmitOutcome.Complete -> { complete = true; next }
                UserAskSubmitOutcome.Rejected -> a
            }
        }
        if (complete) resolve(requestId, "ANSWERED") { it.resolveAnswered(UserAskAnswerSource.APP) }
    }

    fun confirm(requestId: String, index: Int, choice: UserAskConfirmChoice, note: String? = null) {
        val current = _asks.value[requestId]?.answers?.getOrNull(index) ?: return
        var a = current.copy(confirmChoice = choice)
        if (choice == UserAskConfirmChoice.DENY && note != null) a = a.copy(note = note)
        updateAnswer(requestId, index, a)
        submitQuestion(requestId, index)
    }


    fun markNotified(requestId: String, index: Int) = mutate(requestId) { it.copy(notifiedIndex = index) }

    fun answerFromNotification(requestId: String, index: Int, actionId: String, text: String?): UserAskNotificationPlan.Outcome {
        var outcome = UserAskNotificationPlan.Outcome.NOT_FOUND
        val changed: PendingAsk? = synchronized(lock) {
            val cur = _asks.value[requestId] ?: return@synchronized null
            val (next, o) = UserAskNotificationPlan.apply(cur, index, actionId, text, clock())
            outcome = o
            if (next == cur) return@synchronized null
            _asks.value = _asks.value + (requestId to next)
            next
        }
        changed?.let { onChanged?.invoke(it) }
        AppLogger.info(
            TAG,
            "[UserAsk] NOTIFICATION action=${actionId.substringBefore(':')} q=${index + 1} req=${requestId.take(8)} " +
                "outcome=$outcome textLen=${text?.length ?: 0}",
        )
        if (outcome == UserAskNotificationPlan.Outcome.COMPLETE) {
            resolve(requestId, "ANSWERED") { it.resolveAnswered(UserAskAnswerSource.NOTIFICATION) }
        }
        return outcome
    }


    // ── Dismissal ──────────────────────────────────────────────────────────
    fun dismiss(requestId: String, reason: UserAskDismissReason) {
        resolve(requestId, "DISMISSED reason=${reason.wire}") { it.resolveDismissed(reason) }
    }

    fun dismissPending(sessionId: String, reason: UserAskDismissReason) {
        for (a in pendingAsks(sessionId)) {
            if (reason == UserAskDismissReason.STOPPED && a.deferred) continue
            dismiss(a.requestId, reason)
        }
    }


    internal fun tick(nowMs: Long = clock()) {
        for (a in _asks.value.values) {
            if (a.isExpired(nowMs)) resolve(a.requestId, "TIMED OUT - nothing applied") { it.resolveTimedOut() }
        }
    }

    private fun startTicking() {
        synchronized(lock) {
            if (tickJob?.isActive == true) return
            tickJob = scope.launch {
                while (isActive) {
                    delay(1_000)
                    tick()
                    if (_asks.value.values.none { it.state == UserAskState.PENDING }) break
                }
            }
        }
    }


    fun markDelivered(toolUseIds: Collection<String>) {
        for (id in toolUseIds) {
            val a = ask(forToolUseId = id) ?: continue
            if (a.deferred && !a.consumed) {
                val release = synchronized(lock) {
                    deferredUnstored -= a.requestId
                    deferredHeld.remove(a.requestId)
                }
                if (release) _asks.value[a.requestId]?.let { onDeferredResolved?.invoke(it) }
                continue
            }
            mutate(a.requestId) { it.copy(consumed = true) }
            synchronized(lock) { inProcess -= a.requestId }
            AppLogger.info(TAG, "[UserAsk] DELIVERED req=${a.requestId.take(8)} tool=${id.take(16)}")
        }
    }

    fun markDeferredConsumed(requestId: String): Boolean {
        var won = false
        mutate(requestId) { a -> if (a.consumed) a else { won = true; a.copy(consumed = true) } }
        return won
    }

    fun forget(requestId: String) {
        synchronized(lock) {
            _asks.value = _asks.value - requestId
            inProcess -= requestId
            deferredUnstored -= requestId
            deferredHeld -= requestId
            waiters.remove(requestId)
        }
    }


    private fun mutate(requestId: String, body: (PendingAsk) -> PendingAsk) {
        val changed: PendingAsk = synchronized(lock) {
            val cur = _asks.value[requestId] ?: return
            val next = body(cur)
            if (next == cur) return
            _asks.value = _asks.value + (requestId to next)
            next
        }
        onChanged?.invoke(changed)
    }

    private fun resolve(requestId: String, label: String, transition: (PendingAsk) -> PendingAsk?) {
        var resolved: PendingAsk? = null
        var waiter: CancellableContinuation<String>? = null
        synchronized(lock) {
            val cur = _asks.value[requestId] ?: return
            val next = transition(cur) ?: return
            _asks.value = _asks.value + (requestId to next)
            resolved = next
            waiter = waiters.remove(requestId)
        }
        val r = resolved ?: return
        AppLogger.info(
            TAG,
            "[UserAsk] $label req=${requestId.take(8)} source=${r.source?.wire ?: "-"} " +
                "statuses=${r.resultStatuses.map { it.wire }} resultLen=${r.resultJson?.length ?: 0}",
        )
        onChanged?.invoke(r)
        val w = waiter
        if (w != null) {
            if (w.isActive) w.resume(r.resultJson ?: UserAskResolver.encode(UserAskResolver.dismissed(r.questions)))
        } else if (r.deferred) {
            val held = synchronized(lock) {
                if (requestId in deferredUnstored) { deferredHeld += requestId; true } else false
            }
            if (held) {
                AppLogger.info(TAG, "[UserAsk] DEFERRED req=${requestId.take(8)} resolved before its batch was stored - held")
            } else {
                onDeferredResolved?.invoke(r)
            }
        } else if (!isInProcess(requestId)) {
            onResolvedWithoutWaiter?.invoke(r)
        }
    }

    internal fun resetForTest() {
        synchronized(lock) {
            _asks.value = emptyMap()
            waiters.clear()
            inProcess.clear()
            deferredUnstored.clear()
            deferredHeld.clear()
            tickJob?.cancel()
            tickJob = null
        }
        onChanged = null
        onResolvedWithoutWaiter = null
        onDeferredResolved = null
        clock = { System.currentTimeMillis() }
    }
}
