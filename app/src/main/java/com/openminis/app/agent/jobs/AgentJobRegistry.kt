package com.openminis.app.agent.jobs

import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID


enum class AgentJobOrigin { TOOL, CLI, SHORTCUT, SCHEDULED }

sealed class AgentJobTrigger {
    object Immediate : AgentJobTrigger()
    data class Once(val afterMs: Long) : AgentJobTrigger()
    data class Loop(val intervalMs: Long, val count: Int?) : AgentJobTrigger()
    data class OnCompletion(val ofJobId: String) : AgentJobTrigger()

    val logLabel: String
        get() = when (this) {
            Immediate -> "immediate"
            is Once -> "once(+${afterMs / 1000}s)"
            is Loop -> "loop(${intervalMs / 1000}s×${count ?: "∞"})"
            is OnCompletion -> "onCompletion(${ofJobId.take(8)})"
        }
}

sealed class AgentJobTarget {
    object New : AgentJobTarget()
    data class FollowUp(val sessionId: String) : AgentJobTarget()
    data class Rerun(val sessionId: String, val messageId: String) : AgentJobTarget()
    data class ChildOfCurrent(val parentSessionId: String, val parentToolUseId: String?) : AgentJobTarget()

    val isChildOfCurrent: Boolean get() = this is ChildOfCurrent
    val parentSessionIdOrNull: String? get() = (this as? ChildOfCurrent)?.parentSessionId

    val logLabel: String
        get() = when (this) {
            New -> "new"
            is FollowUp -> "followUp(${sessionId.take(8)})"
            is Rerun -> "rerun(${sessionId.take(8)}/${messageId.take(8)})"
            is ChildOfCurrent -> "childOfCurrent(${parentSessionId.take(8)} tool=${parentToolUseId?.take(8) ?: "-"})"
        }
}

sealed class AgentJobThen {
    object None : AgentJobThen()
    data class FollowUpParent(val template: String?) : AgentJobThen()
}

enum class AgentJobState {
    PENDING, RUNNING, DONE, CANCELLED, FAILED,
    TIMEOUT;

    val wire: String get() = name.lowercase()
}

data class AgentJob(
    val id: String,
    val label: String?,
    val title: String,
    val origin: AgentJobOrigin,
    val trigger: AgentJobTrigger,
    val target: AgentJobTarget,
    val prompt: String?,
    val then: AgentJobThen,
    val state: AgentJobState = AgentJobState.PENDING,
    val firedCount: Int = 0,
    val remaining: Int? = (trigger as? AgentJobTrigger.Loop)?.count,
    val runSessionId: String? = null,
    val tierUsed: String? = null,
    val modelOrigin: String? = null,
    val agentName: String? = null,
    val wasResumed: Boolean = false,
    val progressLevel: String = "none",
    val summaryLine: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    val resultText: String? = null,
) {
    val isActive: Boolean get() = state == AgentJobState.RUNNING || state == AgentJobState.PENDING
    val elapsedMs: Long?
        get() = startedAtMs?.let { (finishedAtMs ?: System.currentTimeMillis()) - it }
    val logLabel: String
        get() = "job ${id.take(8)} '$title' origin=$origin trigger=${trigger.logLabel} target=${target.logLabel} state=$state"
}

data class AgentJobCompletion(
    val jobId: String,
    val state: AgentJobState,
    val dependentJobIds: List<String>,
)

object AgentJobRegistry {
    private const val TAG = "AgentJobRegistry"

    const val MAX_CONCURRENT_CHILD_JOBS_PER_PARENT = 3

    const val COMPLETION_RESULT_MAX_CHARS = 12_000

    @Volatile
    var maxConcurrentChildJobsPerParent: Int = MAX_CONCURRENT_CHILD_JOBS_PER_PARENT
        private set

    fun debugSetMaxConcurrentChildJobs(n: Int?): Int {
        if (!com.openminis.app.BuildConfig.DEV_TOOLS) return maxConcurrentChildJobsPerParent
        maxConcurrentChildJobsPerParent = (n ?: MAX_CONCURRENT_CHILD_JOBS_PER_PARENT).coerceIn(1, 32)
        AppLogger.info(TAG, "[T-loadtest-limits] maxConcurrentChildJobsPerParent=$maxConcurrentChildJobsPerParent")
        drainIfStalled()
        return maxConcurrentChildJobsPerParent
    }

    private val _jobs = MutableStateFlow<Map<String, AgentJob>>(emptyMap())
    val jobs: StateFlow<Map<String, AgentJob>> = _jobs.asStateFlow()

    private val _completions = MutableSharedFlow<AgentJobCompletion>(extraBufferCapacity = 16)
    val completions: SharedFlow<AgentJobCompletion> = _completions.asSharedFlow()

    private val cancelHooks = HashMap<String, () -> Unit>()
    private val completionHooks = HashMap<String, (AgentJob) -> Unit>()

    @Volatile
    var followUpDispatcher: ((parentSessionId: String, text: String, jobId: String) -> Unit)? = null

    fun register(
        title: String,
        label: String? = null,
        origin: AgentJobOrigin,
        trigger: AgentJobTrigger,
        target: AgentJobTarget,
        prompt: String?,
        then: AgentJobThen? = null,
        agentName: String? = null,
        runSessionId: String? = null,
        wasResumed: Boolean = false,
    ): AgentJob {
        val resolvedThen = then ?: if (target.isChildOfCurrent) AgentJobThen.FollowUpParent(null) else AgentJobThen.None
        val job = AgentJob(
            id = UUID.randomUUID().toString(), label = label, title = title, origin = origin,
            trigger = trigger, target = target, prompt = prompt, then = resolvedThen,
            agentName = agentName, runSessionId = runSessionId, wasResumed = wasResumed,
        )
        _jobs.value = _jobs.value + (job.id to job)
        AppLogger.info(TAG, "REGISTER ${job.logLabel} then=$resolvedThen")
        return job
    }

    fun job(id: String): AgentJob? = _jobs.value[id]

    fun jobByLabel(label: String): AgentJob? = _jobs.value.values.firstOrNull { it.label == label && it.isActive }

    fun list(): List<AgentJob> = _jobs.value.values.sortedByDescending { it.createdAtMs }

    fun activeChildren(parentSessionId: String): List<AgentJob> =
        _jobs.value.values
            .filter { it.target.parentSessionIdOrNull == parentSessionId && it.isActive }
            .sortedBy { it.createdAtMs }

    fun jobForSession(runSessionId: String): AgentJob? =
        _jobs.value.values.firstOrNull { it.runSessionId == runSessionId }

    fun runningChildJobCount(parentSessionId: String): Int =
        _jobs.value.values.count { it.target.parentSessionIdOrNull == parentSessionId && it.isActive }

    val totalRunningChildJobCount: Int
        get() = _jobs.value.values.count { it.target.isChildOfCurrent && it.isActive }

    fun runningChildJobCountsByParent(): Map<String, Int> =
        _jobs.value.values.filter { it.isActive }.mapNotNull { it.target.parentSessionIdOrNull }
            .groupingBy { it }.eachCount()

    fun canStartChildJob(parentSessionId: String): Boolean =
        runningChildJobCount(parentSessionId) < maxConcurrentChildJobsPerParent

    suspend fun awaitChildSlot(parentSessionId: String, timeoutMs: Long): Boolean {
        if (canStartChildJob(parentSessionId)) return true
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            jobs.first { canStartChildJob(parentSessionId) }
            true
        } ?: false
    }


    data class QueuedDelegation(
        val parentSessionId: String,
        val argsJson: String,
        val toolUseId: String,
        val queuedAtMs: Long = System.currentTimeMillis(),
    )

    const val MAX_QUEUED_DELEGATIONS_PER_PARENT = 10

    private val queuedDelegations = mutableListOf<QueuedDelegation>()

    private val queueRevision = MutableStateFlow(0)

    private val registryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val parentsWithAgentWork: StateFlow<Set<String>> = MutableStateFlow(emptySet<String>()).also { flow ->
        registryScope.launch {
            combine(_jobs, queueRevision) { jobs, _ -> jobs }.collect { flow.value = computeParentsWithAgentWork(it) }
        }
    }

    private fun computeParentsWithAgentWork(jobs: Map<String, AgentJob>): Set<String> {
        val running = jobs.values.filter { it.isActive }.mapNotNull { it.target.parentSessionIdOrNull }
        val queued = synchronized(queuedDelegations) { queuedDelegations.map { it.parentSessionId } }
        return (running + queued).toSet()
    }

    fun hasAgentWork(parentSessionId: String): Boolean =
        parentSessionId in computeParentsWithAgentWork(_jobs.value)

    private val queuedStarters = java.util.concurrent.ConcurrentHashMap<String, (QueuedDelegation) -> Boolean>()

    fun registerQueuedStarter(parentSessionId: String, starter: (QueuedDelegation) -> Boolean) {
        if (parentSessionId.isEmpty()) return
        queuedStarters[parentSessionId] = starter
    }

    fun unregisterQueuedStarter(parentSessionId: String) {
        queuedStarters.remove(parentSessionId)
    }

    fun enqueueDelegation(item: QueuedDelegation): Boolean = synchronized(queuedDelegations) {
        val forParent = queuedDelegations.count { it.parentSessionId == item.parentSessionId }
        if (forParent >= MAX_QUEUED_DELEGATIONS_PER_PARENT) return false
        queuedDelegations.add(item)
        queueRevision.value++
        AppLogger.info(
            TAG,
            "QUEUE + tool=${item.toolUseId.take(12)} parent=${item.parentSessionId.take(8)} " +
                "depth=${forParent + 1} total=${queuedDelegations.size}",
        )
        true
    }

    fun queuedCount(parentSessionId: String): Int = synchronized(queuedDelegations) {
        queuedDelegations.count { it.parentSessionId == parentSessionId }
    }

    fun isQueued(toolUseId: String): Boolean = synchronized(queuedDelegations) {
        queuedDelegations.any { it.toolUseId == toolUseId }
    }

    fun dropQueuedDelegations(parentSessionId: String, reason: String) {
        val removed = synchronized(queuedDelegations) {
            val hit = queuedDelegations.filter { it.parentSessionId == parentSessionId }
            queuedDelegations.removeAll(hit)
            queueRevision.value++
            hit.size
        }
        if (removed > 0) AppLogger.info(TAG, "QUEUE drop $removed for ${parentSessionId.take(8)} ($reason)")
    }

    private fun drainQueuedDelegations() {
        val startedFor = HashSet<String>()
        while (true) {
            val next = synchronized(queuedDelegations) {
                val i = queuedDelegations.indexOfFirst {
                    it.parentSessionId !in startedFor && canStartChildJob(it.parentSessionId)
                }
                if (i < 0) null else queuedDelegations.removeAt(i).also { queueRevision.value++ }
            } ?: return
            val starter = queuedStarters[next.parentSessionId]
            if (starter == null) {
                AppLogger.info(
                    TAG,
                    "QUEUE parent ${next.parentSessionId.take(8)} not live — dropping ${next.toolUseId.take(12)}",
                )
                continue
            }
            val started = runCatching { starter(next) }.getOrElse { e ->
                AppLogger.warning(TAG, "QUEUE start failed for ${next.toolUseId.take(12)}: ${e.message}")
                false
            }
            AppLogger.info(TAG, "QUEUE - tool=${next.toolUseId.take(12)} parent=${next.parentSessionId.take(8)} started=$started")
            if (started) startedFor.add(next.parentSessionId)
        }
    }

    fun siblingSummary(
        parentSessionId: String,
        excludingJobId: String,
        interruptedCount: Int,
    ): String? {
        val siblings = _jobs.value.values.filter {
            it.id != excludingJobId &&
                it.target.parentSessionIdOrNull == parentSessionId
        }
        val running = siblings.count { it.state == AgentJobState.RUNNING }
        val pending = siblings.count { it.state == AgentJobState.PENDING }
        val queued = queuedCount(parentSessionId)
        val parts = mutableListOf<String>()
        if (running + pending > 0) parts.add("${running + pending} still running")
        if (queued > 0) parts.add("$queued queued")
        if (interruptedCount > 0) {
            parts.add(
                "$interruptedCount interrupted (the app was restarted; " +
                    "resume with action=resume, or leave them)",
            )
        }
        if (parts.isEmpty()) return null
        return "Other sub agents in this conversation: " + parts.joinToString(", ") + "."
    }

    fun drainIfStalled() {
        val waiting = synchronized(queuedDelegations) {
            queuedDelegations.any { canStartChildJob(it.parentSessionId) }
        }
        if (!waiting) return
        AppLogger.info(TAG, "QUEUE stalled with a free slot — draining")
        drainQueuedDelegations()
    }

    private fun update(jobId: String, f: (AgentJob) -> AgentJob): AgentJob? {
        val cur = _jobs.value[jobId] ?: return null
        val next = f(cur)
        _jobs.value = _jobs.value + (jobId to next)
        return next
    }

    fun setTierUsed(jobId: String, tier: String) { update(jobId) { it.copy(tierUsed = tier) } }

    fun setModelOrigin(jobId: String, origin: String) { update(jobId) { it.copy(modelOrigin = origin) } }
    fun setProgressLevel(jobId: String, level: String) { update(jobId) { it.copy(progressLevel = level) } }
    fun setSummaryLine(jobId: String, line: String) { update(jobId) { it.copy(summaryLine = line) } }
    fun setCompletionHook(jobId: String, hook: (AgentJob) -> Unit) { completionHooks[jobId] = hook }
    fun setThen(jobId: String, then: AgentJobThen) { update(jobId) { it.copy(then = then) } }

    fun markRunning(jobId: String, sessionId: String?, onCancel: (() -> Unit)? = null) {
        val job = update(jobId) {
            it.copy(
                state = AgentJobState.RUNNING,
                startedAtMs = it.startedAtMs ?: System.currentTimeMillis(),
                firedCount = it.firedCount + 1,
                runSessionId = sessionId ?: it.runSessionId,
                remaining = it.remaining?.let { r -> maxOf(0, r - 1) },
            )
        } ?: return
        if (onCancel != null) cancelHooks[jobId] = onCancel
        AppLogger.info(TAG, "RUNNING ${job.logLabel} session=${sessionId?.take(8) ?: "nil"} fired=${job.firedCount}")
    }


    private val steerHooks = java.util.concurrent.ConcurrentHashMap<String, (String) -> Boolean>()

    private val missedSteers = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()

    fun registerSteerHook(jobId: String, hook: (String) -> Boolean) { steerHooks[jobId] = hook }

    private val missedSteerDrains = java.util.concurrent.ConcurrentHashMap<String, () -> List<String>>()

    fun registerMissedSteerDrain(jobId: String, drain: () -> List<String>) { missedSteerDrains[jobId] = drain }

    private val tabReleases = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    fun registerTabRelease(jobId: String, release: () -> Unit) { tabReleases[jobId] = release }

    fun steer(jobId: String, message: String): Boolean {
        val hook = steerHooks[jobId] ?: return false
        return runCatching { hook(message) }.getOrDefault(false)
    }

    fun recordMissedSteers(jobId: String, messages: List<String>) {
        if (messages.isEmpty()) return
        missedSteers.getOrPut(jobId) { mutableListOf() }.addAll(messages)
    }

    fun missedSteersFor(jobId: String): List<String> = missedSteers[jobId].orEmpty().toList()

    fun finish(jobId: String, state: AgentJobState, result: String?) {
        val cur = _jobs.value[jobId] ?: return
        if (!cur.isActive) return
        val job = update(jobId) {
            it.copy(state = state, finishedAtMs = System.currentTimeMillis(), resultText = result)
        } ?: return
        cancelHooks.remove(jobId)
        steerHooks.remove(jobId)
        tabReleases.remove(jobId)?.let { runCatching { it() } }
        missedSteerDrains.remove(jobId)?.let { drain ->
            recordMissedSteers(jobId, runCatching { drain() }.getOrDefault(emptyList()))
        }
        AppLogger.info(TAG, "FINISH ${job.logLabel} elapsed=${(job.elapsedMs ?: 0) / 1000}s result=${result?.length ?: 0}ch")
        completionHooks.remove(jobId)?.let { hook -> runCatching { hook(_jobs.value[jobId] ?: job) } }
        runThen(_jobs.value[jobId] ?: job)
        val dependents = _jobs.value.values.filter { dep ->
            dep.state == AgentJobState.PENDING &&
                (dep.trigger as? AgentJobTrigger.OnCompletion)?.ofJobId == jobId
        }.map { it.id }
        if (dependents.isNotEmpty()) {
            AppLogger.info(TAG, "onCompletion dependents ${dependents.map { it.take(8) }} armed by ${jobId.take(8)} — producer runs them")
        }
        _completions.tryEmit(AgentJobCompletion(jobId, state, dependents))
        drainQueuedDelegations()
    }

    fun cancel(jobId: String, reason: String) {
        val cur = _jobs.value[jobId] ?: return
        if (!cur.isActive) return
        AppLogger.info(TAG, "CANCEL ${cur.logLabel} reason=$reason")
        val hook = cancelHooks.remove(jobId)
        finish(jobId, AgentJobState.CANCELLED, null)
        hook?.let { runCatching { it() } }
    }

    fun cancelByLabel(label: String, reason: String) {
        _jobs.value.values.filter { it.label == label }.forEach { cancel(it.id, reason) }
    }

    fun cancelAll(parentSessionId: String, reason: String) {
        activeChildren(parentSessionId).forEach { cancel(it.id, reason) }
    }


    fun cancelSiblings(ofChildSessionId: String, reason: String) {
        val parent = jobForSession(ofChildSessionId)?.target?.parentSessionIdOrNull
        if (parent == null) {
            jobForSession(ofChildSessionId)?.let { if (it.isActive) cancel(it.id, reason) }
            return
        }
        muteDelegationResults(parent)
        dropQueuedDelegations(parent, reason)
        activeChildren(parent).forEach { cancel(it.id, reason) }
    }

    private val mutedParents = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun muteDelegationResults(parentSessionId: String) {
        mutedParents.add(parentSessionId)
        AppLogger.info(TAG, "delegation results muted for parent ${parentSessionId.take(8)}")
        AppLogger.info(TAG, "[stop-diag] mute ADD sid=$parentSessionId set=${mutedSnapshot()} caller=${stopDiagCaller()}")
    }

    fun clearDelegationMute(parentSessionId: String) {
        val removed = mutedParents.remove(parentSessionId)
        if (removed) {
            AppLogger.info(TAG, "delegation mute cleared for parent ${parentSessionId.take(8)}")
        }
        AppLogger.info(TAG, "[stop-diag] mute REMOVE sid=$parentSessionId removed=$removed set=${mutedSnapshot()} caller=${stopDiagCaller()}")
    }

    fun isDelegationMuted(parentSessionId: String): Boolean {
        val muted = mutedParents.contains(parentSessionId)
        if (muted) {
            AppLogger.info(TAG, "[stop-diag] mute HIT sid=$parentSessionId set=${mutedSnapshot()} caller=${stopDiagCaller()}")
        }
        return muted
    }

    fun mutedSnapshot(): List<String> = synchronized(mutedParents) { mutedParents.toList() }

    fun activeChildCount(parentSessionId: String): Int = activeChildren(parentSessionId).size

    internal fun stopDiagCaller(frames: Int = 6): String =
        Thread.currentThread().stackTrace
            .asSequence()
            .drop(1)
            .filterNot {
                it.className.startsWith("java.lang.Thread") ||
                    (it.className.endsWith("AgentJobRegistry") && it.methodName in STOP_DIAG_SELF)
            }
            .take(frames)
            .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

    private val STOP_DIAG_SELF = setOf(
        "stopDiagCaller", "stopDiagCaller\$default", "muteDelegationResults",
        "clearDelegationMute", "isDelegationMuted",
    )

    fun pruneFinished(olderThanMs: Long = 3_600_000L) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        _jobs.value = _jobs.value.filterValues { it.isActive || (it.finishedAtMs ?: it.createdAtMs) >= cutoff }
    }

    internal fun resetForTest() {
        _jobs.value = emptyMap(); cancelHooks.clear(); completionHooks.clear()
        AppLogger.info(TAG, "[stop-diag] mute CLEAR-ALL (test reset) set=${mutedSnapshot()}")
        followUpDispatcher = null; mutedParents.clear()
    }


    private fun runThen(job: AgentJob) {
        val then = job.then as? AgentJobThen.FollowUpParent ?: return
        val parent = job.target.parentSessionIdOrNull ?: return
        val terminations = SubAgentTerminationStore.shared
        if (isDelegationMuted(parent)) {
            AppLogger.info(TAG, "then.FollowUpParent ${job.id.take(8)} suppressed — parent ${parent.take(8)} stopped")
            terminations.setDeliveryForJob(job.id, SubAgentTermination.Delivery.FAILED, "withheld: the user stopped the parent's agents")
            return
        }
        val termination = terminations.terminalForJob(job.id)
        val result = if (termination != null) {
            SubAgentTerminationRules.helperResultText(job.resultText.orEmpty(), termination)
        } else {
            job.resultText?.takeIf { it.isNotBlank() } ?: HelperRunner.emptyResultNote(job.state.wire)
        }
        val text = then.template?.takeIf { it.isNotEmpty() }?.replace("{{result}}", result)
            ?: completionCallback(job, result).xml
        val dispatcher = followUpDispatcher
        if (dispatcher == null) {
            AppLogger.warning(TAG, "then.FollowUpParent ${job.id.take(8)} dropped — no dispatcher installed")
            terminations.setDeliveryForJob(job.id, SubAgentTermination.Delivery.FAILED, "no delivery path installed")
            return
        }
        AppLogger.info(TAG, "then.FollowUpParent ${job.id.take(8)} → parent ${parent.take(8)} state=${job.state.wire}")
        runCatching { dispatcher(parent, text, job.id) }
            .onFailure {
                AppLogger.warning(TAG, "then.FollowUpParent ${job.id.take(8)} failed: ${it.message}")
                terminations.setDeliveryForJob(job.id, SubAgentTermination.Delivery.FAILED, it.message ?: "delivery threw")
            }
    }

    private val interruptedCounters = java.util.concurrent.ConcurrentHashMap<String, () -> Int>()

    fun registerInterruptedCounter(parentSessionId: String, counter: () -> Int) {
        if (parentSessionId.isEmpty()) return
        interruptedCounters[parentSessionId] = counter
    }

    fun unregisterInterruptedCounter(parentSessionId: String) {
        interruptedCounters.remove(parentSessionId)
    }

    private fun siblingsFor(job: AgentJob): String? {
        val parent = job.target.parentSessionIdOrNull ?: return null
        val interrupted = runCatching { interruptedCounters[parent]?.invoke() ?: 0 }.getOrDefault(0)
        return siblingSummary(parent, job.id, interrupted)
    }

    fun completionCallback(job: AgentJob, result: String): AgentCallback = AgentCallback(
        kind = AgentCallback.Kind.FINISHED,
        jobId = job.id,
        childSessionId = job.runSessionId,
        title = job.title,
        status = job.state.wire,
        tier = job.modelOrigin ?: job.tierUsed,
        elapsed = elapsedClock(job.elapsedMs),
        agentName = job.agentName,
        summary = job.summaryLine?.removePrefix("Summary: "),
        siblings = siblingsFor(job),
        body = boundCallbackBody(result, job.runSessionId),
    )

    private fun boundCallbackBody(result: String, childSessionId: String?): String {
        if (result.length <= COMPLETION_RESULT_MAX_CHARS) return result
        val half = COMPLETION_RESULT_MAX_CHARS / 2
        val head = result.substring(0, half)
        val tail = result.substring(result.length - half)
        val sid = childSessionId.orEmpty()
        val hint = buildString {
            append("\n\n[... result truncated: ${result.length} chars total; showing head and tail ...]\n")
            append("This sub agent's FULL final answer is saved in its session transcript. ")
            if (sid.isNotEmpty()) {
                append("Retrieve it with: minis-sessions-cli messages --id $sid --full\n")
            } else {
                append("Retrieve it with: minis-sessions-cli list  (find this sub agent's session).\n")
            }
            append("Only read the full transcript if a detail in the omitted middle is actually needed.")
        }
        return head + hint + "\n[... tail ...]\n" + tail
    }

    fun elapsedClock(ms: Long?): String? {
        val s = (ms ?: return null) / 1000
        return if (s >= 60) "${s / 60}m${"%02d".format(s % 60)}s" else "${s}s"
    }
}
