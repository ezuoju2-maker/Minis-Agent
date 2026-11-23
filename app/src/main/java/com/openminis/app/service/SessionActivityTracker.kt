package com.openminis.app.service

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.notification.BackgroundTurnOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SessionActivityTracker {

    private const val TAG = "SessionTracker"

    private val _activeSessions = MutableStateFlow<Set<String>>(emptySet())
    val activeSessions: StateFlow<Set<String>> = _activeSessions.asStateFlow()

    private val _presentSessions = MutableStateFlow<Set<String>>(emptySet())
    val presentSessions: StateFlow<Set<String>> = _presentSessions.asStateFlow()

    private val _scheduledSessions = MutableStateFlow<Set<String>>(emptySet())
    val scheduledSessions: StateFlow<Set<String>> = _scheduledSessions.asStateFlow()

    private val _processingSessions = MutableStateFlow<Set<String>>(emptySet())
    val processingSessions: StateFlow<Set<String>> = _processingSessions.asStateFlow()

    fun isProcessing(sessionId: String): Boolean =
        sessionId in _activeSessions.value || sessionId in _scheduledSessions.value ||
            AgentJobRegistry.hasAgentWork(sessionId)

    private fun processingUnion(): Set<String> =
        _activeSessions.value + _scheduledSessions.value + AgentJobRegistry.parentsWithAgentWork.value

    private fun publishProcessing() {
        _processingSessions.value = processingUnion()
    }

    private val _currentToolStatus = MutableStateFlow("Idle")
    val currentToolStatus: StateFlow<String> = _currentToolStatus.asStateFlow()

    private val _lastTaskFinishedAtMs = MutableStateFlow<Long?>(null)
    val lastTaskFinishedAtMs: StateFlow<Long?> = _lastTaskFinishedAtMs.asStateFlow()

    private val _currentRunStartedAtMs = MutableStateFlow<Long?>(null)
    val currentRunStartedAtMs: StateFlow<Long?> = _currentRunStartedAtMs.asStateFlow()

    private val _currentToolName = MutableStateFlow<String?>(null)
    val currentToolName: StateFlow<String?> = _currentToolName.asStateFlow()

    private val _currentToolTitle = MutableStateFlow<String?>(null)
    val currentToolTitle: StateFlow<String?> = _currentToolTitle.asStateFlow()

    private val _isToolRunning = MutableStateFlow(false)
    val isToolRunning: StateFlow<Boolean> = _isToolRunning.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    private val _lastToolOutcome = MutableStateFlow(ToolOutcome.Unknown)
    val lastToolOutcome: StateFlow<ToolOutcome> = _lastToolOutcome.asStateFlow()

    private val _lastToolName = MutableStateFlow<String?>(null)
    val lastToolName: StateFlow<String?> = _lastToolName.asStateFlow()

    private val _lastToolTitle = MutableStateFlow<String?>(null)
    val lastToolTitle: StateFlow<String?> = _lastToolTitle.asStateFlow()

    private val _lastToolStatus = MutableStateFlow<String?>(null)
    val lastToolStatus: StateFlow<String?> = _lastToolStatus.asStateFlow()

    private val _lastReplyExcerpt = MutableStateFlow<String?>(null)
    val lastReplyExcerpt: StateFlow<String?> = _lastReplyExcerpt.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private const val REPLY_EXCERPT_MAX = 72

    data class OverlayTask(
        val sessionId: String,
        val title: String,
        val startedAtMs: Long,
        val finished: Boolean = false,
    )

    private val _overlayTasks = MutableStateFlow<List<OverlayTask>>(emptyList())

    val overlayTasks: StateFlow<List<OverlayTask>> = _overlayTasks.asStateFlow()

    private val _focusedTaskId = MutableStateFlow<String?>(null)
    val focusedTaskId: StateFlow<String?> = _focusedTaskId.asStateFlow()

    private val taskLock = Any()

    private fun upsertTask(sessionId: String, title: String?) {
        synchronized(taskLock) {
            val now = SystemClock.elapsedRealtime()
            val existing = _overlayTasks.value
            val hit = existing.firstOrNull { it.sessionId == sessionId }
            _overlayTasks.value = if (hit == null) {
                existing + OverlayTask(sessionId, title.orEmpty(), now)
            } else {
                existing.map {
                    if (it.sessionId == sessionId) {
                        it.copy(
                            title = title?.takeIf { t -> t.isNotBlank() } ?: it.title,
                            startedAtMs = now,
                            finished = false,
                        )
                    } else {
                        it
                    }
                }
            }
        }
    }

    private fun markTaskFinished(sessionId: String) {
        synchronized(taskLock) {
            _overlayTasks.value = _overlayTasks.value.map {
                if (it.sessionId == sessionId) it.copy(finished = true) else it
            }
        }
    }

    fun removeOverlayTask(sessionId: String) {
        synchronized(taskLock) {
            _overlayTasks.value = _overlayTasks.value.filterNot { it.sessionId == sessionId }
            if (_focusedTaskId.value == sessionId) _focusedTaskId.value = null
        }
    }

    fun dropFinishedOverlayTasks() {
        synchronized(taskLock) {
            val running = _activeSessions.value
            val kept = _overlayTasks.value.filter { !it.finished && it.sessionId in running }
            _overlayTasks.value = kept
            if (kept.none { it.sessionId == _focusedTaskId.value }) _focusedTaskId.value = null
        }
    }

    fun clearOverlayTasks() {
        synchronized(taskLock) {
            _overlayTasks.value = emptyList()
            _focusedTaskId.value = null
        }
    }

    fun cycleFocusedTask() {
        synchronized(taskLock) {
            val tasks = _overlayTasks.value
            if (tasks.size < 2) return
            val cur = _focusedTaskId.value?.takeIf { id -> tasks.any { it.sessionId == id } }
                ?: tasks.last().sessionId
            val i = tasks.indexOfFirst { it.sessionId == cur }
            _focusedTaskId.value = tasks[(i + 1) % tasks.size].sessionId
        }
    }

    fun focusedTaskSlot(): Triple<OverlayTask, Int, Int>? {
        val tasks = _overlayTasks.value
        if (tasks.isEmpty()) return null
        val id = _focusedTaskId.value
        val idx = tasks.indexOfFirst { it.sessionId == id }.takeIf { it >= 0 } ?: tasks.lastIndex
        return Triple(tasks[idx], idx + 1, tasks.size)
    }

    private val _cameraSuppressActive = MutableStateFlow(false)
    val cameraSuppressActive: StateFlow<Boolean> = _cameraSuppressActive.asStateFlow()

    fun setCameraSuppressActive(active: Boolean) {
        _cameraSuppressActive.value = active
    }

    private var appContext: Context? = null

    fun shouldRunServiceNow(): Boolean = shouldRunService()

    private fun shouldRunService(): Boolean =
        _activeSessions.value.isNotEmpty() || _presentSessions.value.isNotEmpty() ||
            _overlayCompletionPending.value ||
            _scheduledSessions.value.isNotEmpty()

    private val _overlayCompletionPending = MutableStateFlow(false)

    fun setOverlayCompletionPending(pending: Boolean) {
        if (_overlayCompletionPending.value == pending) return
        _overlayCompletionPending.value = pending
        Log.d(TAG, "overlayCompletionPending=$pending")
        if (!pending && !shouldRunService()) stopService()
    }

    private val streamCancellers = mutableMapOf<String, () -> Unit>()

    private val pendingErrorFlag = mutableSetOf<String>()

    private val pendingInterruptFlag = mutableSetOf<String>()

    private var completionListener: ((sessionId: String, outcome: BackgroundTurnOutcome) -> Unit)? = null

    fun setCompletionListener(listener: ((sessionId: String, outcome: BackgroundTurnOutcome) -> Unit)?) {
        completionListener = listener
    }

    fun publishLastReply(sessionId: String, fullText: String?) {
        val collapsed = fullText
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString(" ")
            ?.takeIf { it.isNotBlank() } ?: return
        val excerpt = if (collapsed.length > REPLY_EXCERPT_MAX) {
            collapsed.substring(0, REPLY_EXCERPT_MAX).trimEnd() + "…"
        } else {
            collapsed
        }
        _currentSessionId.value = sessionId
        _lastReplyExcerpt.value = excerpt
    }

    fun dismissOverlay() {
        setOverlayCompletionPending(false)
        clearOverlayTasks()
        _lastReplyExcerpt.value = null
        _lastToolOutcome.value = ToolOutcome.Unknown
        _lastToolName.value = null
        _lastToolTitle.value = null
        _lastToolStatus.value = null
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        if (agentWorkCollectorStarted.compareAndSet(false, true)) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                AgentJobRegistry.parentsWithAgentWork.collect { publishProcessing() }
            }
        }
    }

    private val agentWorkCollectorStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    fun setScheduledSessions(sessions: Set<String>) {
        if (_scheduledSessions.value == sessions) return
        _scheduledSessions.value = sessions
        publishProcessing()
        Log.d(TAG, "Scheduled sessions: ${sessions.size} [${sessions.joinToString(",") { it.take(8) }}]")
        if (!shouldRunService()) {
            if (AgentForegroundService.isAlive) stopService()
        } else if (AgentForegroundService.isAlive) {
            updateService()
        }
    }

    private val _scheduledWait = MutableStateFlow<com.openminis.app.scheduled.ScheduledWait?>(null)
    val scheduledWait: StateFlow<com.openminis.app.scheduled.ScheduledWait?> = _scheduledWait.asStateFlow()

    fun setScheduledWait(wait: com.openminis.app.scheduled.ScheduledWait?) {
        if (_scheduledWait.value == wait) return
        _scheduledWait.value = wait
        if (AgentForegroundService.isAlive && shouldRunService()) updateService()
    }

    private val _scheduledFiring = MutableStateFlow<com.openminis.app.scheduled.ScheduledFiring?>(null)
    val scheduledFiring: StateFlow<com.openminis.app.scheduled.ScheduledFiring?> = _scheduledFiring.asStateFlow()

    fun setScheduledFiring(firing: com.openminis.app.scheduled.ScheduledFiring?) {
        if (_scheduledFiring.value == firing) return
        _scheduledFiring.value = firing
        if (AgentForegroundService.isAlive && shouldRunService()) updateService()
    }

    fun stopAllByUser() {
        cancelAllActiveStreams()
        val context = appContext ?: return
        val manager = com.openminis.app.scheduled.ScheduledTaskManager(context)
        for (sid in _scheduledSessions.value) {
            manager.disableAllCreatedBy(sid, "user stopped from the notification")
        }
    }

    fun setActive(sessionId: String, onStop: (() -> Unit)? = null, sessionTitle: String? = null) {
        upsertTask(sessionId, sessionTitle)
        val wasIdle = !shouldRunService()
        val wasRunIdle = _activeSessions.value.isEmpty()
        synchronized(pendingErrorFlag) { pendingErrorFlag.remove(sessionId) }
        synchronized(pendingInterruptFlag) { pendingInterruptFlag.remove(sessionId) }
        _activeSessions.value = _activeSessions.value + sessionId
        publishProcessing()
        if (onStop != null) {
            synchronized(streamCancellers) { streamCancellers[sessionId] = onStop }
        }
        _currentSessionId.value = sessionId
        _lastReplyExcerpt.value = null
        _isThinking.value = false
        _lastToolName.value = null
        _lastToolTitle.value = null
        _lastToolStatus.value = null
        if (wasRunIdle) {
            _lastTaskFinishedAtMs.value = null
            _currentRunStartedAtMs.value = SystemClock.elapsedRealtime()
        }
        Log.d(TAG, "Session activated: $sessionId (total: ${_activeSessions.value.size})")

        if (wasIdle) {
            startServiceIfNeeded()
        } else {
            updateService()
        }
    }

    fun setInactive(sessionId: String) {
        val wasActive = sessionId in _activeSessions.value
        _activeSessions.value = _activeSessions.value - sessionId
        publishProcessing()
        markTaskFinished(sessionId)
        synchronized(streamCancellers) { streamCancellers.remove(sessionId) }
        val wasError = synchronized(pendingErrorFlag) { pendingErrorFlag.remove(sessionId) }
        val wasInterrupted = synchronized(pendingInterruptFlag) { pendingInterruptFlag.remove(sessionId) }
        Log.d(TAG, "Session deactivated: $sessionId (total: ${_activeSessions.value.size})")

        if (_activeSessions.value.isEmpty()) {
            _currentToolStatus.value = "Idle"
            _currentToolName.value = null
            _currentToolTitle.value = null
            _isToolRunning.value = false
            _isThinking.value = false
            if (wasActive) {
                _lastTaskFinishedAtMs.value = SystemClock.elapsedRealtime()
            }
            if (wasError) {
                _lastToolOutcome.value = ToolOutcome.Error
            }
        }
        if (!shouldRunService()) {
            stopService()
        } else {
            updateService()
        }
        if (wasActive) {
            completionListener?.invoke(
                sessionId,
                BackgroundTurnOutcome.decide(
                    streamError = wasError, turnError = false, canResume = wasInterrupted, userStopped = false,
                ),
            )
        }
    }

    fun markStreamError(sessionId: String) {
        synchronized(pendingErrorFlag) { pendingErrorFlag.add(sessionId) }
    }

    fun markStreamInterrupted(sessionId: String) {
        if (sessionId !in _activeSessions.value) return
        synchronized(pendingInterruptFlag) { pendingInterruptFlag.add(sessionId) }
    }

    fun setPresent(sessionId: String) {
        if (sessionId in _presentSessions.value) return
        val wasIdle = !shouldRunService()
        _presentSessions.value = _presentSessions.value + sessionId
        Log.d(TAG, "Presence set: $sessionId (present total: ${_presentSessions.value.size})")
        if (wasIdle) {
            startServiceIfNeeded()
        } else {
            updateService()
        }
    }

    fun setAbsent(sessionId: String) {
        if (sessionId !in _presentSessions.value) return
        _presentSessions.value = _presentSessions.value - sessionId
        Log.d(TAG, "Presence cleared: $sessionId (present total: ${_presentSessions.value.size})")
        if (!shouldRunService()) {
            stopService()
        } else {
            updateService()
        }
    }

    fun clearPresence() {
        if (_presentSessions.value.isEmpty()) return
        _presentSessions.value = emptySet()
        Log.d(TAG, "Presence cleared (all)")
        if (!shouldRunService()) {
            stopService()
        } else {
            updateService()
        }
    }

    fun cancelAllActiveStreams() {
        val snapshot = synchronized(streamCancellers) { streamCancellers.values.toList() }
        Log.d(TAG, "cancelAllActiveStreams: dispatching to ${snapshot.size} session(s)")
        for (cancel in snapshot) {
            try {
                cancel()
            } catch (e: Exception) {
                Log.w(TAG, "stream canceller threw: ${e.message}")
            }
        }
    }

    fun isActive(sessionId: String): Boolean = sessionId in _activeSessions.value

    fun updateToolStatus(status: String) {
        _currentToolStatus.value = status
        if (_activeSessions.value.isNotEmpty()) {
            updateService()
        }
    }

    fun updateToolStatus(status: String, toolName: String?, isRunning: Boolean) {
        updateToolStatus(status, toolName, isRunning, toolTitle = null)
    }

    fun updateToolStatus(status: String, toolName: String?, isRunning: Boolean, toolTitle: String?) {
        _currentToolStatus.value = status
        _currentToolName.value = toolName
        _currentToolTitle.value = toolTitle?.takeIf { it.isNotBlank() }
        _isToolRunning.value = isRunning
        if (isRunning) _isThinking.value = false
        if (isRunning) _lastToolOutcome.value = ToolOutcome.Unknown
        if (_activeSessions.value.isNotEmpty()) {
            updateService()
        }
    }

    private val inFlightTools = java.util.concurrent.atomic.AtomicInteger(0)

    fun toolStarted() { inFlightTools.incrementAndGet() }

    fun toolFinished() {
        if (inFlightTools.decrementAndGet() < 0) inFlightTools.set(0)
    }

    fun setThinking(thinking: Boolean) {
        if (_isThinking.value == thinking) return
        _isThinking.value = thinking
        if (_activeSessions.value.isNotEmpty()) {
            updateService()
        }
    }

    fun clearToolRunning(outcome: ToolOutcome = ToolOutcome.Unknown) {
        if (inFlightTools.get() > 1) return
        if (!_isToolRunning.value && _currentToolName.value == null) return
        _lastToolName.value = _currentToolName.value
        _lastToolTitle.value = _currentToolTitle.value
        _lastToolStatus.value = _currentToolStatus.value
            ?.takeIf { it.isNotBlank() && !it.equals("Idle", ignoreCase = true) }
        _currentToolName.value = null
        _currentToolTitle.value = null
        _isToolRunning.value = false
        _lastToolOutcome.value = outcome
        if (_activeSessions.value.isNotEmpty()) {
            updateService()
        }
    }

    private val serviceLock = Any()

    private var serviceWanted = false

    @Volatile
    var startRefused: Boolean = false
        private set

    private fun dispatchStart(context: Context) {
        val started = AgentForegroundService.startService(
            context,
            sessionCountForNotification(),
            statusForNotification(),
        )
        startRefused = !started
        if (!started) serviceWanted = false
    }

    fun onAppForegrounded() {
        if (!startRefused) return
        startRefused = false
        if (shouldRunService() && !AgentForegroundService.isAlive) {
            Log.d(TAG, "retrying the foreground service start refused in the background")
            startServiceIfNeeded()
        }
    }

    private fun startServiceIfNeeded() {
        val context = appContext ?: run {
            Log.w(TAG, "Context not initialized, cannot start service")
            return
        }
        synchronized(serviceLock) {
            if (!shouldRunService()) {
                Log.d(TAG, "start requested but nothing needs the service — skipped")
                return
            }
            serviceWanted = true
            dispatchStart(context)
        }
    }

    private fun updateService() {
        val context = appContext ?: return
        synchronized(serviceLock) {
            if (!shouldRunService()) {
                Log.d(TAG, "update requested after the service was stopped — skipped")
                return
            }
            serviceWanted = true
            dispatchStart(context)
        }
    }

    internal fun sessionCountForNotification(): Int =
        if (_activeSessions.value.isNotEmpty()) _activeSessions.value.size
        else (_presentSessions.value + _scheduledSessions.value).size

    internal fun statusForNotification(): String {
        val ctx = appContext
        val activeCount = _activeSessions.value.size
        return when {
            activeCount > 0 -> {
                val tool = _currentToolStatus.value
                if (tool.isNotBlank() && tool != "Idle") {
                    tool
                } else if (ctx != null) {
                    if (activeCount == 1) {
                        ctx.getString(com.openminis.app.R.string.notif_one_task_running)
                    } else {
                        ctx.getString(com.openminis.app.R.string.notif_n_tasks_running, activeCount)
                    }
                } else {
                    if (activeCount == 1) "1 task running" else "$activeCount tasks running"
                }
            }
            _scheduledSessions.value.isNotEmpty() ->
                ctx?.getString(com.openminis.app.R.string.notif_scheduled_waiting) ?: "Waiting for scheduled tasks"
            _presentSessions.value.isNotEmpty() ->
                ctx?.getString(com.openminis.app.R.string.notif_in_session) ?: "In session"
            else -> "Idle"
        }
    }

    private fun stopService() {
        val context = appContext ?: run {
            Log.w(TAG, "Context not initialized, cannot stop service")
            return
        }
        synchronized(serviceLock) {
            serviceWanted = false
            AgentForegroundService.stopService(context)
            Log.d(TAG, "All sessions complete, service stopped")
        }
    }
}
