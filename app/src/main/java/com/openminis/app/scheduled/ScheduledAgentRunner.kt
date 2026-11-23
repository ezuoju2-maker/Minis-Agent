package com.openminis.app.scheduled

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.AgentForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ScheduledAgentRunner {

    private const val TAG = "ScheduledAgentRunner"
    private const val RUN_TIMEOUT_MS = 10 * 60 * 1000L

    internal const val CHILD_SLOT_WAIT_MS = 30 * 60 * 1000L

    internal fun needsChildSlot(task: ScheduledTask): String? {
        val mode = task.targetMode as? ScheduledTargetMode.ChildOfCurrent ?: return null
        return mode.sessionId.takeIf { !com.openminis.app.agent.jobs.AgentJobRegistry.canStartChildJob(it) }
    }

    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun run(
        context: Context,
        task: ScheduledTask,
        waitForCompletion: Boolean = true,
        scheduledFire: Boolean = false,
    ): String? {
        val app = context.applicationContext as? MinisApp ?: run {
            AppLogger.error(TAG, "Application is not MinisApp — skipping task ${task.id}")
            return null
        }
        if (!app.subsystemsReady()) {
            AppLogger.error(
                TAG,
                "MinisApp subsystems not initialized (safe-mode or failed init) — skipping task ${task.id}",
            )
            return null
        }

        AgentForegroundService.startService(
            context = app,
            sessionCount = 1,
            toolStatus = "Scheduled: ${task.label.ifBlank { "task" }}",
        )

        needsChildSlot(task)?.let { parentSid ->
            val registry = com.openminis.app.agent.jobs.AgentJobRegistry
            AppLogger.info(
                TAG,
                "task ${task.id}: parent ${parentSid.take(8)} runs " +
                    "${registry.runningChildJobCount(parentSid)}/${registry.maxConcurrentChildJobsPerParent} " +
                    "sub agents — deferring this fire until a slot frees (wait=$waitForCompletion)",
            )
            if (!waitForCompletion) {
                bgScope.launch {
                    if (registry.awaitChildSlot(parentSid, CHILD_SLOT_WAIT_MS)) {
                        run(context, task, waitForCompletion = false, scheduledFire = scheduledFire)
                    } else {
                        AppLogger.warning(TAG, "task ${task.id}: no sub agent slot freed in ${CHILD_SLOT_WAIT_MS / 60_000} min for parent ${parentSid.take(8)} — fire skipped, cap not exceeded")
                    }
                }
                return parentSid
            }
            if (!registry.awaitChildSlot(parentSid, CHILD_SLOT_WAIT_MS)) {
                AppLogger.warning(TAG, "task ${task.id}: no sub agent slot freed in ${CHILD_SLOT_WAIT_MS / 60_000} min for parent ${parentSid.take(8)} — fire skipped, cap not exceeded")
                return null
            }
        }

        val firedAt = System.currentTimeMillis()
        val sessionId = withContext(Dispatchers.IO) {
            resolveSessionId(app, task)
        } ?: return null

        AppLogger.info(
            TAG,
            "running task=${task.id} label=\"${task.label}\" " +
                "mode=${task.targetMode.encode()} session=$sessionId wait=$waitForCompletion",
        )

        ScheduledSessionHold.beginRun(task.id)
        if (waitForCompletion) {
            val result = try {
                dispatch(app, task, sessionId, wait = true, firedAt = firedAt, scheduledFire = scheduledFire)
            } finally {
                ScheduledSessionHold.endRun(task.id)
            }
            val preview = (result.responseText ?: "").take(200).ifBlank { null }
            val ok = result.status != "Error" && result.status != "Timeout" && result.status != "Rejected"
            ScheduledTaskManager(app).markFired(task.id, sessionId, preview, ok = ok, firedAt = firedAt)
            if (scheduledFire) ScheduledCompletionTriggers.onScheduledRunFinished(app, task.id, result.responseText)
            return sessionId
        }

        bgScope.launch {
            val result = try {
                dispatch(app, task, sessionId, wait = true, firedAt = firedAt, scheduledFire = scheduledFire)
            } finally {
                ScheduledSessionHold.endRun(task.id)
            }
            val preview = (result.responseText ?: "").take(200).ifBlank { null }
            val ok = result.status != "Error" && result.status != "Timeout" && result.status != "Rejected"
            ScheduledTaskManager(app).markFired(task.id, sessionId, preview, ok = ok, firedAt = firedAt)
            if (scheduledFire) ScheduledCompletionTriggers.onScheduledRunFinished(app, task.id, result.responseText)
        }
        return sessionId
    }

    private suspend fun dispatch(
        app: MinisApp,
        task: ScheduledTask,
        sessionId: String,
        wait: Boolean,
        firedAt: Long,
        scheduledFire: Boolean,
    ): HeadlessChatRunner.PromptResult = runCatching {
        val prefill = listOfNotNull(task.prefillToolCall?.takeIf { it.validationError() == null })
        val marked = ScheduledTaskMarker.forFire(
            task = task,
            firedAtMs = firedAt,
            scheduledFire = scheduledFire,
            prefilledTool = prefill.firstOrNull()?.toolName,
        ).xml

        val mode = task.targetMode
        if (mode is ScheduledTargetMode.ChildOfCurrent) {
            val r = HeadlessChatRunner.prompt(
                context = app,
                sessionId = sessionId,
                text = marked,
                attachments = emptyList(),
                thinkingLevel = task.thinkingLevel,
                wait = wait,
                timeoutMs = com.openminis.app.agent.jobs.HelperRunner.MAX_MINUTES * 60_000L,
                prefill = prefill,
            )
            closeChildJob(app, sessionId, r)
            r
        } else if (mode is ScheduledTargetMode.RerunMessage) {
            HeadlessChatRunner.retry(
                context = app,
                sessionId = sessionId,
                messageId = mode.messageId,
                wait = wait,
                timeoutMs = RUN_TIMEOUT_MS,
            )
        } else {
            HeadlessChatRunner.prompt(
                context = app,
                sessionId = sessionId,
                text = marked,
                attachments = emptyList(),
                thinkingLevel = task.thinkingLevel,
                wait = wait,
                timeoutMs = RUN_TIMEOUT_MS,
                prefill = prefill,
            )
        }
    }.getOrElse { t ->
        AppLogger.error(TAG, "task ${task.id} dispatch failed: ${t.message}")
        HeadlessChatRunner.PromptResult(
            status = "Error",
            responseText = "Error: ${t.message}",
            timedOut = false,
        )
    }

    private suspend fun resolveSessionId(app: MinisApp, task: ScheduledTask): String? {
        return when (val mode = task.targetMode) {
            is ScheduledTargetMode.AppendToSession -> {
                if (app.chatRepository.getSession(mode.sessionId) == null) {
                    AppLogger.warning(TAG, "task ${task.id}: follow-up session ${mode.sessionId} gone — abort")
                    null
                } else {
                    mode.sessionId
                }
            }
            is ScheduledTargetMode.RerunMessage -> {
                if (app.chatRepository.getSession(mode.sessionId) == null) {
                    AppLogger.warning(TAG, "task ${task.id}: re-run session ${mode.sessionId} gone — abort")
                    null
                } else {
                    mode.sessionId
                }
            }
            is ScheduledTargetMode.ChildOfCurrent -> resolveChildOfCurrent(app, task, mode.sessionId)
            ScheduledTargetMode.NewSession -> {
                val healed = if (task.modelBinding == null) {
                    task.modelId?.let { raw ->
                        val cfg = app.providerRepository.config.value
                        ScheduledModelSource.healLegacy(
                            raw,
                            ScheduledModelSource.entryRefs(cfg.modelEntries, app.providerRepository.allVisibleEntries()),
                            ScheduledModelSource.groupRefs(cfg.modelGroups),
                        )
                    }
                } else null
                if (healed != null) {
                    AppLogger.info(TAG, "task ${task.id}: legacy --model '${task.modelId}' read as ${healed.display}")
                }
                val explicitBinding = healed?.bindingJson ?: task.modelBinding
                val pinnedModelId = if (healed != null) healed.modelId else task.modelId
                val defaultGroupId =
                    if (explicitBinding == null && pinnedModelId == null) {
                        app.providerRepository.defaultPrimaryGroupId
                    } else null

                val seedModelId: String = pinnedModelId
                    ?: run {
                        val groupIdForSeed: String? = explicitBinding
                            ?.let { parseGroupIdFromBinding(it) }
                            ?: defaultGroupId
                        val entryIdForSeed: String? = explicitBinding
                            ?.let { parseEntryIdFromBinding(it) }

                        entryIdForSeed
                            ?.let { eid -> app.providerRepository.config.value.modelEntries.firstOrNull { it.id == eid }?.model?.id }
                            ?: groupIdForSeed
                                ?.let { gid -> app.providerRepository.group(gid) }
                                ?.let { g -> app.providerRepository.availableMemberEntries(g).firstOrNull()?.model?.id }
                    }
                    ?: app.providerRepository.allVisibleEntries().firstOrNull()?.baseModel?.id
                    ?: run {
                        AppLogger.warning(TAG, "task ${task.id}: no provider — abort")
                        return null
                    }
                val title = task.label.ifBlank { "Scheduled task" }
                val memoryOn = com.openminis.app.data.MemoryGlobalPrefs.isGlobalEnabled(app)
                val session = app.chatRepository.createSession(
                    modelId = seedModelId,
                    title = title,
                    memoryEnabled = memoryOn,
                )
                app.chatRepository.dao.updateSource(session.id, "scheduled")

                val bindingToWrite: String? = explicitBinding
                    ?: defaultGroupId?.let { """{"type":"group","groupId":"$it"}""" }
                if (bindingToWrite != null) {
                    app.chatRepository.updateSessionBinding(session.id, bindingToWrite, seedModelId)
                }
                session.id
            }
        }
    }

    private suspend fun resolveChildOfCurrent(app: MinisApp, task: ScheduledTask, parentSid: String): String? {
        if (!com.openminis.app.tools.AgentToolSwitch.AGENTS.isEnabled(app)) {
            AppLogger.warning(TAG, "task ${task.id}: agents are turned off in settings — abort child-of-current")
            return null
        }
        val parentRow = app.chatRepository.getSession(parentSid)
        if (parentRow == null) {
            AppLogger.warning(TAG, "task ${task.id}: parent session ${parentSid} gone — abort")
            return null
        }
        val resolution = com.openminis.app.agent.jobs.ModelTierResolver.resolve(
            tier = com.openminis.app.agent.jobs.HelperModelTier.PRIMARY, repo = app.providerRepository,
            parentBindingJson = parentRow.modelBinding, parentModelId = parentRow.modelId, parentActiveEntryId = null,
        ) ?: run {
            AppLogger.warning(TAG, "task ${task.id}: parent has no model — abort")
            return null
        }
        val title = task.label.ifBlank { task.prompt.take(40) }
        val child = app.chatRepository.createSession(
            modelId = resolution.seedModelId,
            title = com.openminis.app.agent.jobs.HelperRunner.childSessionTitle(app, title),
            memoryEnabled = false,
            parentSessionId = parentSid,
            parentToolUseId = null,
        )
        app.chatRepository.dao.updateSource(child.id, "scheduled")
        resolution.bindingJson?.let { app.chatRepository.updateSessionBinding(child.id, it, resolution.seedModelId) }
        val registry = com.openminis.app.agent.jobs.AgentJobRegistry
        val job = registry.register(
            title = title, label = task.label.ifBlank { null },
            origin = com.openminis.app.agent.jobs.AgentJobOrigin.SCHEDULED,
            trigger = com.openminis.app.agent.jobs.AgentJobTrigger.Immediate,
            target = com.openminis.app.agent.jobs.AgentJobTarget.ChildOfCurrent(parentSid, null),
            prompt = task.prompt, then = com.openminis.app.agent.jobs.AgentJobThen.FollowUpParent(null),
        )
        registry.setTierUsed(job.id, resolution.tierUsed.wire)
        withContext(Dispatchers.Main) {
            val parentVm = HeadlessChatRunner.viewModelFor(app, parentSid)
            HeadlessChatRunner.viewModelFor(
                app, child.id, com.openminis.app.ui.chat.ChatViewModelStore.PoolKind.CHILD,
            ).also {
                it.helperConfig = com.openminis.app.agent.jobs.HelperConfig(
                    parentSessionId = parentSid, parentToolUseId = "", jobId = job.id,
                    maxTurns = com.openminis.app.agent.jobs.HelperRunner.MAX_TURNS, title = title, tierUsed = resolution.tierUsed,
                )
                it.adoptBrowserTabPool(parentVm.browserTabPool)
                registry.markRunning(job.id, child.id) { if (it.isStreaming.value) it.cancelStream() }
            }
        }
        AppLogger.info(TAG, "task ${task.id}: child ${child.id.take(8)} of ${parentSid.take(8)} job=${job.id.take(8)} tier=${resolution.tierUsed.wire}")
        return child.id
    }

    private suspend fun closeChildJob(app: MinisApp, childId: String, result: HeadlessChatRunner.PromptResult) {
        val registry = com.openminis.app.agent.jobs.AgentJobRegistry
        val job = registry.jobForSession(childId)?.takeIf { it.isActive } ?: return
        val hr = com.openminis.app.agent.jobs.HelperRunner
        if (result.timedOut) runCatching { HeadlessChatRunner.cancel(app, childId) }
        val facts = hr.childRunFacts(app.chatRepository.dao.loadMessages(childId))
        registry.setSummaryLine(job.id, hr.runSummaryLine(facts.toolNames, facts.turns, facts.input, facts.output, facts.cacheRead))
        val state = when {
            result.timedOut || result.status == "Timeout" -> com.openminis.app.agent.jobs.AgentJobState.TIMEOUT
            result.status == "Error" || result.status == "Rejected" -> com.openminis.app.agent.jobs.AgentJobState.FAILED
            else -> com.openminis.app.agent.jobs.AgentJobState.DONE
        }
        registry.finish(job.id, state, facts.lastText)
    }

    private fun parseGroupIdFromBinding(json: String): String? = runCatching {
        val o = org.json.JSONObject(json)
        if (o.optString("type") == "group") {
            o.optString("groupId").takeIf { it.isNotEmpty() }
        } else null
    }.getOrNull()

    private fun parseEntryIdFromBinding(json: String): String? = runCatching {
        val o = org.json.JSONObject(json)
        if (o.optString("type") == "entry") {
            o.optString("entryId").takeIf { it.isNotEmpty() }
        } else null
    }.getOrNull()
}
