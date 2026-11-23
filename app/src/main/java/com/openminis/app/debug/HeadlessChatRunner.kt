package com.openminis.app.debug

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.openminis.app.MinisApp
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.ChatViewModelStore
import com.openminis.app.ui.chat.InputAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal object HeadlessChatRunner {

    private class Cached(val store: ViewModelStore, val provider: ViewModelProvider)

    private val providers = mutableMapOf<String, Cached>()

    private fun app(context: Context): MinisApp =
        context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")

    @Synchronized
    private fun liveCached(sessionId: String): Cached? {
        val cached = providers[sessionId] ?: return null
        if (ChatViewModelStore.isLiveStore(sessionId, cached.store)) return cached
        providers.remove(sessionId)
        return null
    }

    @Synchronized
    private fun providerFor(
        context: Context,
        sessionId: String,
        kind: ChatViewModelStore.PoolKind = ChatViewModelStore.PoolKind.NORMAL,
    ): ViewModelProvider {
        liveCached(sessionId)?.let { return it.provider }
        val app = app(context)
        val owner = ChatViewModelStore.ownerFor(sessionId, kind)
        val provider = ViewModelProvider(
            owner,
            ChatViewModel.factory(
                sessionId = sessionId,
                chatRepository = app.chatRepository,
                providerRepository = app.providerRepository,
                appContext = app.applicationContext,
                memoryRepository = app.memoryRepository,
                skillRepository = app.skillRepository,
                mcpRepository = app.mcpRepository,
            ),
        )
        providers[sessionId] = Cached(owner.viewModelStore, provider)
        return provider
    }

    private fun viewModel(
        context: Context,
        sessionId: String,
        kind: ChatViewModelStore.PoolKind = ChatViewModelStore.PoolKind.NORMAL,
    ): ChatViewModel = providerFor(context, sessionId, kind)[ChatViewModel::class.java]

    fun viewModelFor(
        context: Context,
        sessionId: String,
        kind: ChatViewModelStore.PoolKind = ChatViewModelStore.PoolKind.NORMAL,
    ): ChatViewModel = viewModel(context, sessionId, kind)

    fun existingViewModel(sessionId: String): ChatViewModel? =
        com.openminis.app.ui.chat.ChatViewModelStore.existing(sessionId)

    suspend fun ensureSession(context: Context, modelId: String? = null): String =
        withContext(Dispatchers.IO) {
            val app = app(context)
            val resolvedModel = modelId
                ?: app.providerRepository.allVisibleEntries().firstOrNull()?.baseModel?.id
                ?: "unknown"
            val s = app.chatRepository.createSession(modelId = resolvedModel, title = null)
            app.chatRepository.dao.updateSource(s.id, "debug")
            s.id
        }

    suspend fun applyModelOverride(
        context: Context,
        sessionId: String,
        modelEntryId: String?,
        modelGroupId: String?,
    ): String? = withContext(Dispatchers.IO) {
        if (modelEntryId != null && modelGroupId != null) {
            throw RPCException(-32602, "modelEntryId and modelGroupId are mutually exclusive")
        }
        val app = app(context)
        val cfg = app.providerRepository.config.value
        val resolvedGroupId = modelGroupId ?: run {
            if (modelEntryId != null) return@run null
            cfg.defaultPrimaryGroupId?.takeIf { gid -> cfg.modelGroups.any { it.id == gid } }
        }
        if (modelEntryId == null && resolvedGroupId == null) return@withContext null
        if (modelEntryId != null) {
            val entry = cfg.modelEntries.firstOrNull { it.id == modelEntryId }
                ?: throw RPCException(-32602, "Entry not found: $modelEntryId")
            val instance = cfg.instances.firstOrNull { it.id == entry.providerInstanceId }
                ?: throw RPCException(-32602, "Provider instance for entry not found")
            if (!instance.isEnabled) throw RPCException(-32602, "Provider instance is disabled")
            val binding = """{"type":"entry","entryId":"${entry.id}"}"""
            app.chatRepository.updateSessionBinding(sessionId, binding, entry.baseModel.id)
            return@withContext entry.model.displayName
        }
        val group = cfg.modelGroups.firstOrNull { it.id == resolvedGroupId }
            ?: throw RPCException(-32602, "Group not found: $resolvedGroupId")
        val firstMemberId = group.memberEntryIds.firstOrNull()
        val firstMember = firstMemberId?.let { mid -> cfg.modelEntries.firstOrNull { it.id == mid } }
        val resolvedModelId = firstMember?.baseModel?.id ?: ""
        val groupBinding = """{"type":"group","groupId":"${group.id}"}"""
        app.chatRepository.updateSessionBinding(sessionId, groupBinding, resolvedModelId)
        return@withContext group.name
    }

    suspend fun prompt(
        context: Context,
        sessionId: String,
        text: String,
        attachments: List<InputAttachment> = emptyList(),
        thinkingLevel: ThinkingLevel? = null,
        wait: Boolean,
        timeoutMs: Long,
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
    ): PromptResult = ChatViewModelStore.holdingForSend(sessionId) {
        withContext(Dispatchers.Main) {
            val vm = viewModel(context, sessionId)
            if (thinkingLevel != null) {
                withContext(Dispatchers.Default) {
                    withTimeoutOrNull(3000L) {
                        vm.activeEntryId.first { it != null }
                    }
                }
                vm.setThinkingLevel(thinkingLevel)
            }
            val ready = withContext(Dispatchers.Default) {
                withTimeoutOrNull(5000L) {
                    vm.activeEntryId.first { it != null }
                }
            }
            if (ready == null) {
                return@withContext PromptResult(
                    status = "Error",
                    responseText = "no_provider_resolved_in_5s",
                    timedOut = false,
                )
            }
            val rowsBeforeSubmit = if (com.openminis.app.scheduled.ScheduledTaskMarker.isMarkerText(text)) {
                app(context).chatRepository.dao.loadMessages(sessionId).size
            } else -1
            val outcome = vm.submitPrompt(text, prefill, attachments)
            if (outcome is ChatViewModel.SubmitOutcome.Rejected) {
                return@withContext PromptResult(status = "Rejected", responseText = outcome.reason, timedOut = false)
            }
            if (!wait) {
                val status = if (outcome is ChatViewModel.SubmitOutcome.Queued) "Queued" else "Running"
                return@withContext PromptResult(status = status, responseText = null, timedOut = false)
            }

            val finished = withTimeoutOrNull(timeoutMs) {
                if (outcome is ChatViewModel.SubmitOutcome.Compacting) {
                    vm.isCompacting.first { !it }
                    withTimeoutOrNull(2_000L) { vm.isStreaming.first { it } }
                }
                if (vm.isStreaming.value) {
                    vm.isStreaming.first { !it }
                }
                true
            } ?: false

            val app = app(context)
            val msgs = app.chatRepository.dao.loadMessages(sessionId)
            val responseText = responseTextFor(text, msgs.map { it.role to it.partsJson }, rowsBeforeSubmit)
            PromptResult(
                status = if (finished) "Completed" else "Timeout",
                responseText = responseText,
                timedOut = !finished,
            )
        }
    }

    suspend fun retry(
        context: Context,
        sessionId: String,
        messageId: String?,
        wait: Boolean,
        timeoutMs: Long,
    ): PromptResult = ChatViewModelStore.holdingForSend(sessionId) {
        withContext(Dispatchers.Main) {
            val app = app(context)
            val vm = viewModel(context, sessionId)
            val targetMsgId = messageId ?: run {
                val msgs = app.chatRepository.dao.loadMessages(sessionId)
                msgs.lastOrNull { it.role == "user" }?.id
                    ?: throw RPCException(-32602, "Session has no user messages")
            }
            val all = app.chatRepository.dao.loadMessages(sessionId)
            val target = all.firstOrNull { it.id == targetMsgId }
                ?: throw RPCException(-32602, "Message not found in session")
            if (target.role != "user") throw RPCException(-32602, "Target is not a user message")
            val deletedCount = all.size - all.indexOf(target) - 1

            val ready = withContext(Dispatchers.Default) {
                withTimeoutOrNull(5000L) {
                    vm.activeEntryId.first { it != null }
                }
            }
            if (ready == null) {
                return@withContext PromptResult(
                    status = "Error",
                    responseText = "no_provider_resolved_in_5s",
                    timedOut = false,
                    deletedMessageCount = deletedCount,
                    retriedMessageId = targetMsgId,
                )
            }
            vm.retryFromMessage(targetMsgId)
            if (!wait) {
                return@withContext PromptResult(
                    status = "Retrying",
                    responseText = null,
                    timedOut = false,
                    deletedMessageCount = deletedCount,
                    retriedMessageId = targetMsgId,
                )
            }
            val finished = withTimeoutOrNull(timeoutMs) {
                if (vm.isStreaming.value) {
                    vm.isStreaming.first { !it }
                }
                true
            } ?: false
            val msgs = app.chatRepository.dao.loadMessages(sessionId)
            val lastAssistant = msgs.lastOrNull { it.role == "assistant" }
            val responseText = lastAssistant?.let { extractText(it.partsJson) }
            PromptResult(
                status = if (finished) "Completed" else "Timeout",
                responseText = responseText,
                timedOut = !finished,
                deletedMessageCount = deletedCount,
                retriedMessageId = targetMsgId,
            )
        }
    }

    suspend fun rerunFromToolBlock(
        context: Context,
        sessionId: String,
        assistantMessageId: String,
        blockId: String,
        wait: Boolean,
        timeoutMs: Long,
    ): PromptResult = withContext(Dispatchers.Main) {
        val app = app(context)
        val vm = viewModel(context, sessionId)
        val ready = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5000L) { vm.activeEntryId.first { it != null } }
        }
        if (ready == null) {
            return@withContext PromptResult(
                status = "Error",
                responseText = "no_provider_resolved_in_5s",
                timedOut = false,
                deletedMessageCount = 0,
                retriedMessageId = assistantMessageId,
            )
        }
        val before = app.chatRepository.dao.loadMessages(sessionId).size
        val liveAssistantId = vm.assistantMessageIdForToolBlock(blockId) ?: assistantMessageId
        val accepted = vm.rerunFromToolBlock(liveAssistantId, blockId)
        if (!accepted) {
            return@withContext PromptResult(
                status = "Error",
                responseText = "rerun_rejected (streaming / not_found / not_tool_block)",
                timedOut = false,
                deletedMessageCount = 0,
                retriedMessageId = assistantMessageId,
            )
        }
        if (!wait) {
            return@withContext PromptResult(
                status = "Rerunning",
                responseText = null,
                timedOut = false,
                deletedMessageCount = 0,
                retriedMessageId = assistantMessageId,
            )
        }
        val finished = withTimeoutOrNull(timeoutMs) {
            if (vm.isStreaming.value) vm.isStreaming.first { !it }
            true
        } ?: false
        val msgs = app.chatRepository.dao.loadMessages(sessionId)
        val lastAssistant = msgs.lastOrNull { it.role == "assistant" }
        PromptResult(
            status = if (finished) "Completed" else "Timeout",
            responseText = lastAssistant?.let { extractText(it.partsJson) },
            timedOut = !finished,
            deletedMessageCount = (before - msgs.size).coerceAtLeast(0),
            retriedMessageId = assistantMessageId,
        )
    }

    suspend fun compact(
        context: Context,
        sessionId: String,
        wait: Boolean,
        timeoutMs: Long,
        messageId: String? = null,
        includesBoundary: Boolean = true,
    ): CompactResult = withContext(Dispatchers.Main) {
        val vm = viewModel(context, sessionId)
        val ready = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5000L) {
                vm.activeEntryId.first { it != null }
            }
        }
        if (ready == null) {
            return@withContext CompactResult(
                status = "Error",
                summary = null,
                timedOut = false,
                error = "no_provider_resolved_in_5s",
            )
        }
        if (vm.isStreaming.value) {
            return@withContext CompactResult(
                status = "Error",
                summary = null,
                timedOut = false,
                error = "stream_in_progress",
            )
        }
        if (vm.isCompacting.value) {
            return@withContext CompactResult(
                status = "Error",
                summary = null,
                timedOut = false,
                error = "compact_already_in_progress",
            )
        }
        val priorSummary = vm.compactSummary.value
        if (messageId != null) {
            vm.compactBefore(messageId, includesBoundary)
        } else {
            vm.runCompactNow()
        }
        if (!wait) {
            return@withContext CompactResult(
                status = "Running",
                summary = null,
                timedOut = false,
                error = null,
            )
        }
        val flippedOn = withContext(Dispatchers.Default) {
            withTimeoutOrNull(2000L) {
                if (!vm.isCompacting.value) vm.isCompacting.first { it }
                true
            }
        }
        if (flippedOn != true) {
            return@withContext CompactResult(
                status = "NoOp",
                summary = vm.compactSummary.value,
                timedOut = false,
                error = "compact_skipped_no_change",
            )
        }
        val finished = withContext(Dispatchers.Default) {
            withTimeoutOrNull(timeoutMs) {
                vm.isCompacting.first { !it }
                true
            } ?: false
        }
        val newSummary = vm.compactSummary.value
        val changed = newSummary != priorSummary && !newSummary.isNullOrBlank()
        CompactResult(
            status = when {
                !finished -> "Timeout"
                changed -> "Completed"
                else -> "Completed"
            },
            summary = newSummary,
            timedOut = !finished,
            error = null,
        )
    }

    suspend fun revertCompact(context: Context, sessionId: String) = withContext(Dispatchers.Main) {
        val vm = viewModel(context, sessionId)
        val ready = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5000L) {
                vm.activeEntryId.first { it != null }
            }
        }
        if (ready == null) throw RPCException(-32000, "Session VM did not resolve a provider within 5s")
        vm.revertCompact()
    }

    suspend fun cancel(context: Context, sessionId: String): Boolean = withContext(Dispatchers.Main) {
        val cached = liveCached(sessionId) ?: return@withContext false
        val vm = cached.provider[ChatViewModel::class.java]
        val wasRunning = vm.isStreaming.value
        if (wasRunning) vm.cancelStream()
        wasRunning
    }

    suspend fun selectModel(context: Context, sessionId: String, entryId: String):
        Pair<String, String> = withContext(Dispatchers.Main) {
        val vm = viewModel(context, sessionId)
        vm.selectEntry(entryId)
        vm.modelName.value to vm.thinkingLevel.value.name
    }

    @Synchronized
    fun forget(sessionId: String) {
        providers.remove(sessionId)
    }

    internal fun responseTextFor(sentText: String, rows: List<Pair<String, String>>, fromIndex: Int): String? {
        val marker = com.openminis.app.scheduled.ScheduledTaskMarker.parse(sentText)
        if (marker == null || fromIndex < 0) {
            return rows.lastOrNull { it.first == "assistant" }?.let { extractText(it.second) }
        }
        val envelopeIdx = (fromIndex until rows.size).lastOrNull { i ->
            rows[i].first == "user" &&
                extractText(rows[i].second)?.let { com.openminis.app.scheduled.ScheduledTaskMarker.parse(it)?.taskId } == marker.taskId
        } ?: return null
        return (envelopeIdx + 1 until rows.size).asSequence()
            .filter { rows[it].first == "assistant" }
            .mapNotNull { extractText(rows[it].second)?.takeIf { t -> t.isNotBlank() } }
            .firstOrNull()
    }

    internal fun extractText(partsJson: String): String? {
        return try {
            val arr = org.json.JSONArray(partsJson)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("type") == "text") sb.append(o.optString("value", ""))
            }
            sb.toString().ifEmpty { null }
        } catch (_: Exception) { partsJson.ifEmpty { null } }
    }

    data class PromptResult(
        val status: String,
        val responseText: String?,
        val timedOut: Boolean,
        val deletedMessageCount: Int = 0,
        val retriedMessageId: String? = null,
    )

    data class CompactResult(
        val status: String,
        val summary: String?,
        val timedOut: Boolean,
        val error: String?,
    )
}
