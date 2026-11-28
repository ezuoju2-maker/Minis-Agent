package com.openminis.app.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import com.openminis.app.agent.Level
import com.openminis.app.agent.ToolLoopDetector
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.db.MessageEntity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.ContextOffload
import com.openminis.app.data.ContextOverflowGuard
import com.openminis.app.data.ContextPolicy
import com.openminis.app.data.ContextSizeMeter
import com.openminis.app.data.PayloadSizeAudit
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.R
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.DsmlLeak
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.catalogMaxThinkingLevel
import com.openminis.app.provider.withIdleTimeout
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.agent.shell.BashismDetector
import com.openminis.app.agent.shell.BashismReminder
import com.openminis.app.agent.shell.OnDemandBash
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.ShellExitCode
import com.openminis.app.terminal.MinisOpenUrlBroker
import com.openminis.app.terminal.MinisUrlMarker
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.FileEditTool
import com.openminis.app.tools.FileReadTool
import com.openminis.app.tools.FileWriteTool
import com.openminis.app.tools.MemoryTools
import com.openminis.app.tools.BatchToolOrder
import com.openminis.app.tools.ReadImageTool
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.ToolResultCache
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.ByteArrayOutputStream


class ChatViewModel(
    internal val sessionId: String,
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
    internal val context: Context,
    val memoryRepository: MemoryRepository? = null,
    val skillRepository: com.openminis.app.data.repository.SkillRepository? = null,
    val mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
) : ViewModel() {

    private val memoryFactRepository: com.openminis.app.data.repository.MemoryFactRepository by lazy {
        com.openminis.app.data.repository.MemoryFactRepository(
            java.io.File(context.filesDir, "minis-global/memory"),
        )
    }

    companion object {
        private val SHELL_FREE_TOOLS = setOf(
            com.openminis.app.agent.jobs.HelperRunner.TOOL_NAME,
            com.openminis.app.agent.userask.UserAskPromptText.TOOL_NAME,
        )

        internal fun fallbackMayRebindSession(classProviderNow: Any?, classProviderAtSend: Any?): Boolean =
            classProviderNow === classProviderAtSend

        internal fun modelSwitchToApply(pending: Boolean, chosen: Any?, loopProvider: Any?): Boolean =
            pending && chosen != null && chosen !== loopProvider

        const val MAX_CONCURRENT_TOOLS = 10

        const val SHELL_TIMEOUT_MAX_SEC = 3600
        internal const val TAG = "ChatViewModel"


        internal const val MAX_COMPACT_LLM_CALLS = 6

        internal const val COMPACT_IDLE_TIMEOUT_MS = 120_000L

        internal const val COMPACT_MAX_TOTAL_MS = 900_000L

        internal class CompactIdleTimeoutException(message: String) : Exception(message)

        internal fun looksLikeParameterRejection(error: LLMError.ProviderError): Boolean {
            val msg = error.message?.lowercase() ?: return false
            val sizeMarkers = listOf(
                "context_length", "context length", "too long", "too large",
                "maximum context", "max_tokens", "token limit", "exceeds",
                "reduce the length", "payload",
            )
            if (sizeMarkers.any { msg.contains(it) }) return false
            val paramMarkers = listOf(
                "is not supported", "unsupported parameter", "unsupported value",
                "invalid_request_error", "invalid parameter", "unrecognized request argument",
                "does not support parameter", "extra_forbidden",
            )
            return paramMarkers.any { msg.contains(it) }
        }

        internal fun shouldSplitOnError(error: Throwable): Boolean {
            if (error is CancellationException) return false
            if (error is CompactIdleTimeoutException) return false
            if (error is LLMError) {
                return when (error) {
                    is LLMError.Cancelled,
                    is LLMError.NetworkError,
                    is LLMError.CertificateError,
                    is LLMError.RateLimited,
                    is LLMError.TransientError,
                    is LLMError.InvalidApiKey,
                    -> false
                    is LLMError.ProviderError -> !looksLikeParameterRejection(error)
                    else -> true
                }
            }
            if (error is java.io.IOException) return false
            return true
        }

        internal fun warmUpScanIndices(
            history: List<LLMMessage>,
            priorIdx: Int,
            anchorIdx: Int,
            drop: Int?,
        ): Pair<List<Int>, Set<String>> {
            if (priorIdx < 0 || priorIdx > anchorIdx || anchorIdx >= history.size) return emptyList<Int>() to emptySet()
            val pruned = HashSet<String>()
            for (i in priorIdx..anchorIdx) {
                for (part in history[i].contentParts) {
                    if (part is AgentContentPart.ToolResult && part.content.length > 1000) pruned.add(part.id)
                }
            }
            val kept = ArrayList<Int>()
            for (i in priorIdx..anchorIdx) {
                val parts = history[i].contentParts
                val survives = parts.isEmpty() || parts.any { part ->
                    when (part) {
                        is AgentContentPart.ToolUse -> part.id !in pruned
                        is AgentContentPart.ToolResult -> part.id !in pruned
                        else -> true
                    }
                }
                if (survives) kept.add(i)
            }
            while (kept.isNotEmpty() && history[kept.first()].role != LLMMessage.Role.USER) kept.removeAt(0)
            val trimmed = drop?.let { kept.drop(minOf(it, kept.size)) } ?: kept
            return trimmed to pruned
        }

        internal fun joinDraftWithSnippet(draft: String, snippet: String): String? {
            val cleaned = snippet.trim()
            if (cleaned.isEmpty()) return null
            val startsWithFence = cleaned.startsWith("```") || cleaned.startsWith("~~~")
            val tail = if (cleaned.endsWith("```") || cleaned.endsWith("~~~")) "\n" else " "
            if (draft.isBlank()) return cleaned + tail
            val separator = if (startsWithFence) {
                if (draft.endsWith("\n")) "" else "\n"
            } else {
                if (draft.last().isWhitespace()) "" else " "
            }
            return draft + separator + cleaned + tail
        }

        internal fun userBubbleText(raw: String): String {
            if (!raw.contains("<system-reminder>", ignoreCase = true)) return raw
            return com.openminis.app.data.repository.InternalTags.stripSystemReminders(raw).ifBlank { raw }
        }

        internal fun promptSafe(raw: String, max: Int): String =
            raw.replace(Regex("[\"'\\[\\]{};\\\\]"), " ")
                .replace(Regex("[\\u2018\\u2019\\u201C\\u201D\\u300C\\u300D\\u300E\\u300F]"), " ")
                .replace(Regex("\\p{Cf}"), "")
                .replace(Regex("[\\s\\p{Z}\\u0085\\u2028\\u2029]+"), " ")
                .trim()
                .take(max)
                .trim()

        private val PREFLIGHT_NON_BLOCKING_FIELDS = setOf("tool_title")

        private val PREFLIGHT_EMPTY_STRING_ALLOWED_FIELDS: Map<String, Set<String>> = mapOf(
            "file_edit" to setOf("new_string"),
        )

        internal fun preflightEmptyStringAllowed(tool: String, field: String): Boolean =
            PREFLIGHT_EMPTY_STRING_ALLOWED_FIELDS[tool]?.contains(field) == true

        internal fun preflightValidateToolCallImpl(
            name: String,
            args: JSONObject,
            tools: List<AgentToolDefinition>,
        ): String? {
            val toolDef = tools.firstOrNull { it.name == name } ?: return null
            val enforced = toolDef.required.filter { it !in PREFLIGHT_NON_BLOCKING_FIELDS }
            if (args.length() == 0 && enforced.isNotEmpty()) {
                return "Tool '$name' was called with empty arguments {} but requires: ${enforced.joinToString(", ")}."
            }
            val missing = mutableListOf<String>()
            for (field in enforced) {
                if (!args.has(field) || args.isNull(field)) {
                    missing.add(field)
                    continue
                }
                val raw = args.opt(field)
                if (raw is String && raw.isEmpty() &&
                    !preflightEmptyStringAllowed(name, field)
                ) {
                    missing.add(field)
                }
            }
            if (missing.isNotEmpty()) {
                return "Tool '$name' is missing required parameter(s): ${missing.joinToString(", ")}."
            }
            return null
        }
        private const val NEWLINE_FLUSH_MIN_CHARS = 50
        private const val NEWLINE_FLUSH_MAX_LEN = 5_000
        const val INITIAL_VISIBLE_MESSAGE_CAP: Int = 200
        const val VISIBLE_MESSAGE_CAP_STEP: Int = 100
        const val LONG_SESSION_THRESHOLD: Int = 300
        private val IN_FLIGHT_TOOL_STATUSES = setOf(
            ToolBlockStatus.STREAMING,
            ToolBlockStatus.PENDING,
            ToolBlockStatus.RUNNING,
        )
        private const val TAG_STREAM = "ChatVMStream"

        const val UNTITLED_SESSION_TITLE = "New Chat"
        private const val MAX_AGENT_TURNS = com.openminis.app.agent.AgentLoopLimits.MAX_AGENT_TURNS
        private const val MIN_MAX_TOKENS = 1024
        private const val GLOBAL_MAX_TOKENS_CEILING = 128_000
        const val CANCELLED_MARKER =
            "<system-reminder>The user cancelled this operation. The returned result may be incomplete.</system-reminder>"

        private const val LEGACY_CANCELLED_MARKER = "[cancelled by user]"
        private const val COMPACT_KEEP_RECENT_USER_TURNS = 3
        private const val TOOL_INPUT_CHUNK_RING_MAX = 10
        internal val AUTO_RETRY_DELAYS_SEC = intArrayOf(1, 2, 4)

        fun factory(
            sessionId: String,
            chatRepository: ChatRepository,
            providerRepository: ProviderRepository,
            appContext: Context,
            memoryRepository: MemoryRepository?,
            skillRepository: com.openminis.app.data.repository.SkillRepository?,
            mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(
                    sessionId = sessionId,
                    chatRepository = chatRepository,
                    providerRepository = providerRepository,
                    context = appContext,
                    memoryRepository = memoryRepository,
                    skillRepository = skillRepository,
                    mcpRepository = mcpRepository,
                ) as T
            }
        }
    }

    private val mediaStore = com.openminis.app.data.storage.MediaStore(context)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()


    private val _visibleMessageCap = MutableStateFlow(INITIAL_VISIBLE_MESSAGE_CAP)
    val visibleMessageCap: StateFlow<Int> = _visibleMessageCap.asStateFlow()

    val uiMessages: StateFlow<List<ChatMessage>> =
        kotlinx.coroutines.flow.combine(_messages, _visibleMessageCap) { raw, cap ->
            val full = if (raw.any { it.isInternalBridge }) raw.filterNot { it.isInternalBridge } else raw
            if (full.size <= LONG_SESSION_THRESHOLD || full.size <= cap) full
            else full.subList(full.size - cap, full.size).toList()
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            emptyList(),
        )

    val hasOlderMessages: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(_messages, _visibleMessageCap) { full, cap ->
            full.size > LONG_SESSION_THRESHOLD && full.size > cap
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
        )

    fun loadOlderMessages() {
        val totalNow = _messages.value.size
        if (totalNow <= LONG_SESSION_THRESHOLD) return
        val next = (_visibleMessageCap.value + VISIBLE_MESSAGE_CAP_STEP).coerceAtMost(totalNow)
        if (next != _visibleMessageCap.value) {
            _visibleMessageCap.value = next
        }
    }

    private val _streamingById = MutableStateFlow<Map<String, StreamingDelta>>(emptyMap())
    val streamingById: StateFlow<Map<String, StreamingDelta>> = _streamingById.asStateFlow()

    private val liveTurn = LiveTurnGuard()

    private val foregroundTools = ForegroundToolInterrupts()

    @Volatile private var toolOnlyStopTurn: Long? = null

    private val delegateDetach = MutableStateFlow(0)

    private var cleanupPersistJob: Job? = null

    @Volatile private var userAskExposedThisRun = false

    @Volatile private var userAskDeferredThisRun = false

    @Volatile private var userAskAskedThisRun = false

    @Volatile private var userAskBatchSnapshot: Pair<String, List<String>>? = null

    private fun ensureUserAskRecovery() {
        com.openminis.app.agent.userask.UserAskRecoveryCoordinator.ensureInstalled(
            context, chatRepository,
            delivered = { sid, resume ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    val vm = ChatViewModelStore.existing(sid)
                        ?: if (resume) runCatching { com.openminis.app.debug.HeadlessChatRunner.viewModelFor(context, sid) }.getOrNull() else null
                    vm?.onUserAskRecovered(resume)
                }
            },
            deferredDelivered = { d ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    val vm = ChatViewModelStore.existing(d.sessionId)
                        ?: if (d.answerMessage != null) runCatching { com.openminis.app.debug.HeadlessChatRunner.viewModelFor(context, d.sessionId) }.getOrNull() else null
                    vm?.onUserAskDeferredDelivered(d)
                }
            },
        )
    }

    internal fun onUserAskRecovered(resume: Boolean) {
        if (_isStreaming.value) return
        val job = loadSession()
        viewModelScope.launch {
            job?.join()
            if (resume && !_isStreaming.value && _canResume.value) {
                AppLogger.info(TAG, "[UserAsk] RECOVER resuming run sid=${activeSessionId.take(8)}")
                resume()
            }
        }
    }

    private fun decideUserAskExposureForRun(interactive: Boolean, userAskAnswer: Boolean = false) {
        val d = com.openminis.app.agent.userask.UserAskExposure.decision(
            enabled = com.openminis.app.agent.userask.UserAskSettings.isEnabled(context),
            isHelper = isHelper,
            interactive = interactive,
            foreground = appInForeground(),
            userAskAnswer = userAskAnswer,
        )
        userAskExposedThisRun = d.exposed
        userAskDeferredThisRun = d.deferred
        userAskAskedThisRun = false
        AppLogger.info(TAG, "[UserAsk] exposure=${d.exposed} deferred=${d.deferred} reason=${d.reason} sid=${activeSessionId.take(8)}")
    }

    private fun appInForeground(): Boolean = runCatching {
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    }.getOrDefault(true)

    internal fun onUserAskDeferredDelivered(d: com.openminis.app.agent.userask.UserAskDeferredDelivery) {
        val patched = replaceUserAskResultInMemory(d.toolUseId, d.resultJson)
        AppLogger.info(TAG, "[UserAsk] DEFERRED delivered tool=${d.toolUseId.take(16)} history=$patched message=${d.answerMessage != null}")
        val message = d.answerMessage ?: return
        if (!com.openminis.app.agent.userask.UserAskSettings.isEnabled(context)) return
        viewModelScope.launch {
            val deadline = System.currentTimeMillis() + com.openminis.app.agent.userask.UserAskDeferred.COMPACTION_WAIT_MS
            while (_isCompacting.value && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(500)
            val outcome = submitUserAskAnswer(message)
            if (outcome is SubmitOutcome.Rejected) {
                AppLogger.error(TAG, "[UserAsk] DEFERRED answer NOT sent: ${outcome.reason} - the stored tool result carries it")
            } else {
                AppLogger.info(TAG, "[UserAsk] DEFERRED answered - sent as the next message: ${outcome.javaClass.simpleName}")
            }
        }
    }

    private val pendingHistoryPatches = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()

    private fun applyPendingHistoryPatches() {
        while (true) {
            val (id, output) = pendingHistoryPatches.poll() ?: break
            val replaced = patchHistoryToolResult(id, output)
            AppLogger.info(TAG, "[UserAsk] DEFERRED queued patch applied tool=${id.take(16)} replaced=$replaced")
        }
    }

    internal fun replaceUserAskResultInMemory(toolUseId: String, output: String): String {
        val history = if (_isStreaming.value) {
            pendingHistoryPatches.add(toolUseId to output)
            "queued"
        } else if (patchHistoryToolResult(toolUseId, output)) "patched" else "absent"
        val msgs = _messages.value
        if (msgs.any { m -> m.toolBlocks.any { it.id == toolUseId } }) {
            _messages.value = msgs.map { m ->
                if (m.toolBlocks.none { it.id == toolUseId }) m
                else m.copy(toolBlocks = m.toolBlocks.map { b -> if (b.id == toolUseId) b.copy(content = output) else b })
            }
        }
        return history
    }

    private fun patchHistoryToolResult(toolUseId: String, output: String): Boolean {
        var replaced = false
        for (i in agentHistory.indices) {
            val m = agentHistory[i]
            if (m.contentParts.none { it is AgentContentPart.ToolResult && it.id == toolUseId }) continue
            agentHistory[i] = m.copy(contentParts = m.contentParts.map { part ->
                if (part is AgentContentPart.ToolResult && part.id == toolUseId) { replaced = true; part.copy(content = output) } else part
            })
        }
        return replaced
    }

    internal fun submitUserAskAnswer(text: String): SubmitOutcome =
        sendMessage(text, skipContextCheck = true, headless = true, userAskAnswer = true)

    private fun supersedeDeferredAsks(sessionId: String) {
        for (a in com.openminis.app.agent.userask.UserAskCenter.pendingDeferred(sessionId)) {
            com.openminis.app.agent.userask.UserAskCenter.dismiss(
                a.requestId, com.openminis.app.agent.userask.UserAskDismissReason.SUPERSEDED_BY_USER_MESSAGE,
            )
            com.openminis.app.agent.userask.UserAskCenter.asks.value[a.requestId]?.resultJson?.let {
                replaceUserAskResultInMemory(a.toolCallId, it)
            }
        }
    }

    private val stopLateDrops = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private class StreamFlushState {
        var lastFlushMs: Long = 0L
        var lastFlushedLen: Int = 0
        var trailingJob: Job? = null
        var pendingContent: String? = null
        var pendingBlocks: List<AssistantBlock> = emptyList()
        var pendingAwaiting: Boolean = false
    }
    private val streamFlushStates = HashMap<String, StreamFlushState>()

    private fun clearStreamFlushState(id: String) {
        streamFlushStates.remove(id)?.trailingJob?.cancel()
    }
    private fun clearAllStreamFlushStates() {
        streamFlushStates.values.forEach { it.trailingJob?.cancel() }
        streamFlushStates.clear()
    }
    private fun retainStreamFlushStates(keptIds: Set<String>) {
        val drop = streamFlushStates.keys.filter { it !in keptIds }
        for (id in drop) streamFlushStates.remove(id)?.trailingJob?.cancel()
    }

    private fun streamFlushThrottleMs(len: Int): Long = when {
        len < 500 -> 200L
        len < 2_000 -> 300L
        len < 32_000 -> 500L
        len < 64_000 -> 1_000L
        len < 128_000 -> 1_500L
        else -> 2_000L
    }

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    internal val _pendingCaret = MutableStateFlow<Int?>(null)
    val pendingCaret: StateFlow<Int?> = _pendingCaret.asStateFlow()

    fun consumePendingCaret(): Int? {
        val c = _pendingCaret.value
        _pendingCaret.value = null
        return c
    }

    val listState: LazyListState = LazyListState(0, 0)

    fun setInputText(value: String) {
        _inputText.value = value
    }

    fun appendToInputText(snippet: String) {
        val joined = joinDraftWithSnippet(_inputText.value, snippet) ?: return
        _inputText.value = joined
    }

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    internal val _selectedToolDetailId = MutableStateFlow<String?>(null)
    val selectedToolDetailId: StateFlow<String?> = _selectedToolDetailId.asStateFlow()


    private val _canResume = MutableStateFlow(false)
    val canResume: StateFlow<Boolean> = _canResume.asStateFlow()

    @Volatile private var redetectingInterruptedTailGen: Long = 0L
    @Volatile private var consumedRedetectGen: Long = 0L

    private fun markRedetectingInterruptedTail() {
        redetectingInterruptedTailGen += 1
    }

    private fun markLiveInterruption() {
        consumedRedetectGen = redetectingInterruptedTailGen
    }

    private val _editingMessageId = MutableStateFlow<String?>(null)
    val editingMessageId: StateFlow<String?> = _editingMessageId.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _modelName = MutableStateFlow("")
    val modelName: StateFlow<String> = _modelName.asStateFlow()

    private val sessionLoaded = MutableStateFlow(false)

    internal val sessionLoadedState: StateFlow<Boolean> = sessionLoaded.asStateFlow()

    internal val isDraftSession: Boolean get() = isDraft

    internal suspend fun awaitSessionLoaded(timeoutMs: Long = 5_000L): Boolean =
        sessionLoaded.value || withTimeoutOrNull(timeoutMs) { sessionLoaded.first { it } } != null

    private val _sessionTitle = MutableStateFlow(UNTITLED_SESSION_TITLE)
    val sessionTitle: StateFlow<String> = _sessionTitle.asStateFlow()

    private fun overlaySessionTitle(): String? =
        _sessionTitle.value.takeIf { it.isNotBlank() && it != UNTITLED_SESSION_TITLE }

    private val _sessionCategory = MutableStateFlow<String?>(null)
    val sessionCategory: StateFlow<String?> = _sessionCategory.asStateFlow()

    internal val _attachments = MutableStateFlow<List<InputAttachment>>(emptyList())
    val attachments: StateFlow<List<InputAttachment>> = _attachments.asStateFlow()

    val hasUnsentDraft: Boolean
        get() = ChatViewModelStore.isUnsentDraft(
            isDraft = isDraft,
            realSessionId = realSessionId,
            text = _inputText.value,
            hasAttachments = _attachments.value.isNotEmpty(),
            busy = hasWorkInFlight(),
        )

    private val _pastedTexts = MutableStateFlow<List<PastedText>>(emptyList())
    val pastedTexts: StateFlow<List<PastedText>> = _pastedTexts.asStateFlow()

    private var nextPasteId: Int = 1

    fun stashPastedText(text: String): String {
        val entry = PastedText(id = nextPasteId++, text = text)
        _pastedTexts.value = _pastedTexts.value + entry
        AppLogger.info(TAG, "[Paste] stashed #${entry.id} (${text.length} chars)")
        return entry.placeholder
    }

    fun stashPastedTextAsFile(text: String): InputAttachment? {
        val dir = java.io.File(context.cacheDir, "pasted_text").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val name = "Pasted_$stamp-${java.util.UUID.randomUUID().toString().take(8)}.txt"
        val file = java.io.File(dir, name)
        return try {
            file.writeText(text)
            val attachment = InputAttachment(
                fileName = name,
                uri = android.net.Uri.fromFile(file),
                mimeType = "text/plain",
                kind = InputAttachment.Kind.DOCUMENT,
            )
            addAttachment(attachment)
            AppLogger.info(
                TAG,
                "[Paste] oversize paste -> file attachment $name (${text.length} chars)",
            )
            attachment
        } catch (e: Exception) {
            AppLogger.warning(TAG, "[Paste] failed to write oversize paste: ${e.message}")
            null
        }
    }

    fun removePastedText(id: Int) {
        _pastedTexts.value = _pastedTexts.value.filterNot { it.id == id }
    }

    private val _imageBudgetEvent = MutableSharedFlow<ImageBudget.BudgetResult>(extraBufferCapacity = 4)
    val imageBudgetEvent: SharedFlow<ImageBudget.BudgetResult> = _imageBudgetEvent.asSharedFlow()

    private val _requestBudgetEvent = MutableSharedFlow<ImageBudget.RequestBudgetPlan>(extraBufferCapacity = 4)
    val requestBudgetEvent: SharedFlow<ImageBudget.RequestBudgetPlan> = _requestBudgetEvent.asSharedFlow()

    private val _forceScrollToBottom = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val forceScrollToBottom: SharedFlow<Unit> = _forceScrollToBottom.asSharedFlow()

    private val _stopStaleReadAloud = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val stopStaleReadAloud: SharedFlow<Unit> = _stopStaleReadAloud.asSharedFlow()

    private val _availableGroups = MutableStateFlow<List<ModelGroup>>(emptyList())
    val availableGroups: StateFlow<List<ModelGroup>> = _availableGroups.asStateFlow()

    private val _selectedGroupId = MutableStateFlow<String?>(null)
    val selectedGroupId: StateFlow<String?> = _selectedGroupId.asStateFlow()

    private val _selectedGroupName = MutableStateFlow("")
    val selectedGroupName: StateFlow<String> = _selectedGroupName.asStateFlow()

    private val _providerName = MutableStateFlow("")
    val providerName: StateFlow<String> = _providerName.asStateFlow()

    private val _fallbackTrigger = MutableStateFlow(0)
    val fallbackTrigger: StateFlow<Int> = _fallbackTrigger.asStateFlow()

    private val _activeEntryId = MutableStateFlow<String?>(null)
    val activeEntryId: StateFlow<String?> = _activeEntryId.asStateFlow()

    internal var helperConfig: com.openminis.app.agent.jobs.HelperConfig? = null
    internal val isHelper: Boolean get() = helperConfig != null

    private val browserOwnerId: String? get() = activeSessionId.ifEmpty { null }
    @Volatile internal var helperWrapUpRequested = false

    @Volatile internal var helperClosingAttempted = false

    internal fun agentHistorySnapshot(): List<LLMMessage> {
        repeat(3) { runCatching { return agentHistory.toList() } }
        return emptyList()
    }

    private val pendingSteerMessages = java.util.Collections.synchronizedList(mutableListOf<String>())

    internal fun enqueueSteer(message: String): Boolean {
        if (helperConfig == null) return false
        val wasIdle = !_isStreaming.value
        pendingSteerMessages.add(message)
        if (wasIdle) {
            AppLogger.info(TAG, "[subagent_task] steer arrived while the child was idle — nudging its loop")
            viewModelScope.launch {
                runCatching {
                    submitPrompt(com.openminis.app.agent.jobs.HelperRunner.STEER_NUDGE_PROMPT)
                }.onFailure {
                    AppLogger.warning(TAG, "[subagent_task] steer nudge failed: ${it.message}")
                }
            }
        }
        return true
    }

    internal fun drainMissedSteers(): List<String> = synchronized(pendingSteerMessages) {
        val out = pendingSteerMessages.toList()
        pendingSteerMessages.clear()
        out
    }
    internal val fsSessionId: String get() = helperConfig?.parentSessionId ?: activeSessionId
    private val delegateBlockOverrides = java.util.concurrent.ConcurrentHashMap<String, AssistantBlock>()

    private val _promptQueue = MutableStateFlow<List<QueuedPrompt>>(emptyList())
    val promptQueue: StateFlow<List<QueuedPrompt>> = _promptQueue.asStateFlow()

    private val _lastTurnContextTokens = MutableStateFlow(0)
    val lastTurnContextTokens: StateFlow<Int> = _lastTurnContextTokens.asStateFlow()

    private val contextCalibrationRatios = mutableMapOf<String, Double>()
    private var lastLearnedCalibration: Double? = null
    private var calibrationSessionId: String? = null
    private val warmUpDropByMarker = mutableMapOf<String, Int>()
    private var contextFixedTokens = 0
    private var lastDispatchEstimate = 0
    private var lastDispatchRatio = 1.0
    @Volatile private var pendingRevertLogMarker: String? = null
    private var lastDispatchPredicted = 0
    private var lastCalibratedPair: Triple<Int, Int, String?>? = null
    private var sentPastExtrapolatedLimitThisLoop = false
    private var lastInLoopCompactionMadeNoProgress = false

    private fun contextCalibrationRatio(modelId: String? = currentModel?.id): Double =
        ContextSizeMeter.ratioFor(modelId, contextCalibrationRatios, lastLearnedCalibration)

    private fun measureOutboundContextTokens(ratio: Double? = null): Int = contextMeasurement(ratio).measured

    private data class ContextMeasurement(val history: Int, val fixed: Int, val ratio: Double, val source: String, val measured: Int)

    private fun contextMeasurement(override: Double? = null): ContextMeasurement {
        val history = ContextSizeMeter.estimateTokens(effectiveAgentHistoryUncounted(), reasoningEchoed())
        val model = currentModel?.id
        val ratio = override ?: contextCalibrationRatio(model)
        val source = if (override != null) "forced"
            else ContextSizeMeter.ratioSource(model, contextCalibrationRatios, lastLearnedCalibration)
        return ContextMeasurement(history, contextFixedTokens, ratio, source,
            ContextSizeMeter.calibrated(history + contextFixedTokens, ratio))
    }

    private fun logContextDecision(site: String, m: ContextMeasurement, threshold: Int, window: Int, result: String) {
        AppLogger.info(
            TAG,
            "[CtxMeter] decide site=$site model=${currentModel?.id ?: "?"} history=${m.history} fixed=${m.fixed} " +
                "ratio=${"%.3f".format(m.ratio)}(${m.source}) measured=${m.measured} threshold=$threshold " +
                "window=$window → $result",
        )
    }

    private fun ensureContextFixedTokens() {
        if (contextFixedTokens > 0) return
        contextFixedTokens = ContextSizeMeter.estimateFixedTokens(buildSystemPrompt(), agentTools)
    }

    private var lastDispatchModel: LLMModel? = null
    private var lastDispatchWindow = 0

    private fun recordContextDispatch(history: List<LLMMessage>, model: LLMModel? = currentModel): Int {
        lastDispatchModel = model
        lastDispatchWindow = resolvedContextWindow(model)?.first ?: 0
        lastDispatchEstimate = ContextSizeMeter.estimateTokens(history, reasoningEchoed()) + contextFixedTokens
        lastDispatchRatio = contextCalibrationRatio(model?.id)
        lastDispatchPredicted = ContextSizeMeter.calibrated(lastDispatchEstimate, lastDispatchRatio)
        return lastDispatchPredicted
    }


    private fun reasoningEchoed(): Boolean =
        currentProvider?.echoesReasoningContent(_thinkingLevel.value) == true

    private var trimState: ContextTrimPlanner.State? = null
    private var trimStateSid: String? = null

    private var lastTrimPlanEscalates = false

    private var trimStateWindow: Int? = null

    private var trimStateMarkerId: String? = null

    private var trimReplanPending = false

    private var trimCollapseUnlockedSid: String? = null

    private val FIXED_RATIO_MAX = 1.5

    private val SIMULATED_SUMMARY_TOKENS = 3_000

    private val trimOffloadPaths = HashMap<String, String>()

    private val trimOffloader = ContextTrimPlanner.Offloader { content, toolId, toolName ->
        val sid = activeSessionId
        val key = "$sid|$toolName|$toolId"
        trimOffloadPaths[key] ?: com.openminis.app.data.ContextOffload
            .offloadContent(context, sid, content, toolId, toolName)
            .takeIf { it.isNotEmpty() }
            ?.also { trimOffloadPaths[key] = it }
    }

    private fun currentWatermarks(): ContextTrimPlanner.Watermarks? {
        val window = effectiveContextWindowTokens()?.takeIf { it > 0 } ?: return null
        ensureContextFixedTokens()
        val ratio = contextCalibrationRatio().coerceAtMost(FIXED_RATIO_MAX)
        val fixed = ContextSizeMeter.calibrated(contextFixedTokens, ratio)
        val provider = currentProvider
        val model = currentModel
        val maxOut = if (provider != null && model != null) {
            minOf(GLOBAL_MAX_TOKENS_CEILING, provider.effectiveMaxOutputTokens(model))
        } else {
            16_384
        }
        return ContextTrimPlanner.watermarks(window, fixed, maxOut)
    }

    private fun trimMeasure(): (List<LLMMessage>) -> Int {
        val ratio = contextCalibrationRatio()
        val echo = reasoningEchoed()
        val fixed = contextFixedTokens
        return { history -> ContextSizeMeter.calibrated(ContextSizeMeter.estimateTokens(history, echo) + fixed, ratio) }
    }

    private fun applyFrozenTrim(compacted: List<LLMMessage>): List<LLMMessage> {
        val state = trimState?.takeIf { trimStateSid == activeSessionId } ?: return compacted
        return ContextTrimPlanner.apply(compacted, state, trimOffloader) ?: run {
            AppLogger.info(TAG, "[ctx-trim] frozen plan no longer matches the history — dropped, will re-plan")
            trimState = null
            trimReplanPending = true
            compacted
        }
    }

    private fun replanTrim(site: String, allowCollapse: Boolean): ContextTrimPlanner.Result? {
        lastTrimPlanEscalates = false
        val wm = currentWatermarks() ?: return null
        val planned = trimStateSid == activeSessionId && (trimState != null || trimCollapseUnlockedSid == activeSessionId)
        if (planned && !ContextTrimPlanner.planFitsWindow(trimStateWindow, wm.window)) {
            AppLogger.info(TAG, "[ctx-trim] the frozen plan was made for a ${trimStateWindow ?: 0} window, now ${wm.window} — dropped, will re-plan")
            trimState = null
            trimCollapseUnlockedSid = null
            trimReplanPending = true
        }
        val markerId = _cachedLatestMarker?.id
        if (trimState != null && trimStateSid == activeSessionId && !ContextTrimPlanner.planFitsMarker(trimStateMarkerId, markerId)) {
            AppLogger.info(TAG, "[ctx-trim] the frozen plan was made on compaction marker ${trimStateMarkerId ?: "none"}, now ${markerId ?: "none"} — dropped, will re-plan")
            trimState = null
            trimReplanPending = true
        }
        val compacted = RequestContextBuilder.compacted(agentHistory, currentCompaction(), COMPACT_KEEP_RECENT_USER_TURNS)
        val measure = trimMeasure()
        val applied = applyFrozenTrim(compacted)
        val size = measure(applied)
        val trigger = ContextTrimPlanner.trigger(currentContextPolicy()?.first?.compactThreshold, wm)
        val tight = wm.mode != ContextTrimPlanner.Mode.NORMAL
        if (!tight && !trimReplanPending && size <= trigger) return null
        trimReplanPending = false
        if (!tight && size <= wm.target) return null
        val collapse = allowCollapse || tight || trimCollapseUnlockedSid == activeSessionId
        if (collapse) trimCollapseUnlockedSid = activeSessionId
        val options = ContextTrimPlanner.Options(
            stripReasoning = reasoningEchoed(),
            reasoningFloor = if (tight) 0 else 1,
            keepRecentIterations = if (tight) 1 else 3,
            allowCollapse = collapse,
        )
        val result = ContextTrimPlanner.plan(compacted, wm.target, options, measure, trimOffloader)
        trimState = result.state.takeUnless { it.isEmpty }
        trimStateSid = activeSessionId
        trimStateWindow = wm.window
        trimStateMarkerId = markerId
        lastTrimPlanEscalates = !collapse && ContextTrimPlanner.shouldEscalate(result.after, wm.target, trigger)
        AppLogger.info(
            TAG,
            "[ctx-trim] site=$site mode=${wm.mode} window=${wm.window} fixed=${wm.fixed} hard=${wm.hard} " +
                "trigger=$trigger target=${wm.target} before=${result.before} after=${result.after} " +
                "steps=${result.steps} collapse=${options.allowCollapse} reached=${result.reachedTarget} " +
                "echo=${options.stripReasoning} state=${result.state}",
        )
        return result
    }

    private fun summaryWouldFreeLittle(measured: Int): Boolean {
        val wm = currentWatermarks() ?: return false
        val raw = RequestContextBuilder.simulatedSummaryGain(
            agentHistory, currentCompaction(), COMPACT_KEEP_RECENT_USER_TURNS, SIMULATED_SUMMARY_TOKENS,
        ) ?: return false
        val gain = ContextSizeMeter.calibrated(raw, contextCalibrationRatio())
        val needed = measured - wm.target
        val little = gain < needed / 2
        AppLogger.info(TAG, "[ctx-trim] summary would free ~$gain of $needed needed — ${if (little) "collapse first" else "compact"}")
        return little
    }

    private fun trimmedOverHard(): Boolean {
        val wm = currentWatermarks() ?: return false
        val compacted = RequestContextBuilder.compacted(agentHistory, currentCompaction(), COMPACT_KEEP_RECENT_USER_TURNS)
        val trimmed = applyFrozenTrim(compacted)
        val calibrated = trimMeasure()(trimmed)
        val raw = ContextSizeMeter.estimateTokens(trimmed, reasoningEchoed()) + contextFixedTokens
        return calibrated > wm.hard && raw > wm.hard
    }

    private fun calibrateContextSize(reportedTokens: Int): Boolean {
        val sample = ContextSizeMeter.calibrationRatio(reportedTokens, lastDispatchEstimate) ?: return false
        val model = lastDispatchModel?.id ?: currentModel?.id
        val own = model?.let { contextCalibrationRatios[it] }
        val updated = ContextSizeMeter.smoothed(own, sample)
        if (model != null) contextCalibrationRatios[model] = updated
        lastLearnedCalibration = updated
        lastCalibratedPair = Triple(lastDispatchEstimate, contextFixedTokens, model)
        val err = if (lastDispatchPredicted > 0) (lastDispatchPredicted - reportedTokens) * 100.0 / reportedTokens else 0.0
        AppLogger.info(
            TAG,
            "[CtxMeter] actual model=${model ?: "?"} predicted=$lastDispatchPredicted reported=$reportedTokens " +
                "err=${"%+.1f".format(err)}% estimate=$lastDispatchEstimate sample=${"%.3f".format(sample)} " +
                "ratio=${"%.3f".format(lastDispatchRatio)}→${"%.3f".format(updated)}" +
                if (own == null) " (first own sample)" else "",
        )
        return true
    }

    private fun noteContextOverflow(detail: String) {
        val model = lastDispatchModel?.id ?: currentModel?.id
        val window = lastDispatchWindow.takeIf { lastDispatchModel != null && it > 0 }
            ?: effectiveContextWindowTokens() ?: 0
        val current = contextCalibrationRatio(model)
        val requested = ContextSizeMeter.requestedTokens(detail)
        val raised = ContextSizeMeter.ratioAfterOverflow(current, lastDispatchEstimate, requested, window)
        if (model != null) contextCalibrationRatios[model] = raised
        lastLearnedCalibration = raised
        sentPastExtrapolatedLimitThisLoop = true
        AppLogger.warning(
            TAG,
            "[CtxMeter] rejected predicted=$lastDispatchPredicted — provider rejected the request as too long — calibration model=${model ?: "?"} " +
                "${"%.2f".format(current)} → ${"%.2f".format(raised)} (estimated=$lastDispatchEstimate " +
                "statedTokens=${requested ?: "none"} window=$window); the next attempt will compact",
        )
    }

    private fun seedContextCalibration(usageJsons: List<String>): Boolean {
        val sid = realSessionId.ifEmpty { sessionId }
        val learned = if (sid.isNotEmpty() && calibrationSessionId == sid) {
            ContextSizeMeter.CalibrationState(contextCalibrationRatios.toMap(), lastLearnedCalibration, contextFixedTokens)
        } else {
            null
        }
        val seeded = ContextSizeMeter.replayCalibration(usageJsons.mapNotNull(ContextSizeMeter::calibrationSample))
        val state = learned?.let { seeded.carryingOver(it) } ?: seeded
        contextCalibrationRatios.clear()
        contextCalibrationRatios.putAll(state.ratios)
        lastLearnedCalibration = state.lastLearned
        contextFixedTokens = state.fixedTokens
        lastDispatchEstimate = 0
        lastDispatchModel = null
        lastDispatchWindow = 0
        lastCalibratedPair = null
        if (learned == null) sentPastExtrapolatedLimitThisLoop = false
        calibrationSessionId = sid
        AppLogger.info(
            TAG,
            "[CtxMeter] seed session=${sid.take(8)} samples=${seeded.samples} keptInMemory=${learned?.ratios?.size ?: 0} " +
                "ratios=${state.ratios.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${"%.3f".format(it.value)}" }}",
        )
        return seeded.samples > 0 || !learned?.ratios.isNullOrEmpty()
    }

    private fun publishMeasuredContextUsage() {
        ensureContextFixedTokens()
        val measured = measureOutboundContextTokens()
        if (measured > 0) _lastTurnContextTokens.value = measured
    }

    private fun currentWarmUpBudget(): Int? = warmUpBudget(
        window = effectiveContextWindowTokens(),
        compactThreshold = currentContextPolicy()?.first?.compactThreshold,
        ratio = contextCalibrationRatio(),
        fixedTokens = contextFixedTokens,
    )

    private fun calibrationJsonFields(): String = lastCalibratedPair?.let { (est, fixed, model) ->
        val modelField = model?.let { ""","calibrationModelId":${JSONObject.quote(it)}""" } ?: ""
        ""","estimatedRequestTokens":$est,"estimatedFixedTokens":$fixed$modelField,"estimatorVersion":${ContextSizeMeter.ESTIMATOR_VERSION}"""
    } ?: ""


    internal val contextUsage: StateFlow<ContextUsage?> =
        _lastTurnContextTokens
            .map { tokens ->
                ContextUsage.from(tokens, effectiveContextWindowTokens())
            }
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.Eagerly,
                null,
            )

    private val _contextUsageHint = MutableStateFlow<ContextUsageHint?>(null)
    internal val contextUsageHint: StateFlow<ContextUsageHint?> = _contextUsageHint.asStateFlow()

    private var contextHintGeneration = 0

    private val contextTierTracker = ContextTierCrossingTracker()

    private var lastTurnWasCancelled = false

    private fun publishContextUsage(crossingOnly: Boolean) {
        if (isHelper) return
        val usage = ContextUsage.from(
            usedTokens = _lastTurnContextTokens.value,
            windowTokens = effectiveContextWindowTokens(),
        )
        if (usage == null) return

        val now = System.currentTimeMillis()
        val crossed = contextTierTracker.observe(usage.tier, now)
        if (usage.tier == ContextUsage.Tier.NORMAL) return
        if (crossingOnly && !crossed) return
        if (!crossingOnly && !crossed &&
            contextTierTracker.recentlyFired(usage.tier, now)
        ) return

        contextHintGeneration += 1
        _contextUsageHint.value = ContextUsageHint(
            generation = contextHintGeneration,
            text = "",
            highlights = listOf(
                "${usage.percent}%",
                TokenCountFormatter.sizePair(usage),
            ),
            tier = usage.tier,
        ).withQuota(composerQuotaPart())
        contextTierTracker.markFired(usage.tier, now)
        AppLogger.info(
            TAG,
            "[ContextUsage] hint gen=$contextHintGeneration tier=${usage.tier} " +
                "${usage.percent}% (${usage.usedTokens}/${usage.windowTokens}) " +
                "crossingOnly=$crossingOnly crossed=$crossed",
        )
    }

    private fun composerQuotaPart(): com.openminis.app.quota.QuotaComposerHintRules.Segment? {
        val entryId = _activeEntryId.value ?: return null
        val entry = providerRepository.config.value.modelEntries.find { it.id == entryId } ?: return null
        val st = com.openminis.app.quota.QuotaStore.state.value
        val locale = context.resources.configuration.locales[0] ?: java.util.Locale.getDefault()
        return com.openminis.app.quota.QuotaComposerHintRules.segment(
            st.modelView(entry.providerInstanceId, entry.model.id), st.pools,
            locale = locale, strings = com.openminis.app.ui.quota.QuotaResourceStrings(context),
        )
    }

    private fun ContextUsageHint.withQuota(seg: com.openminis.app.quota.QuotaComposerHintRules.Segment?): ContextUsageHint {
        seg ?: return this
        return copy(
            quotaText = seg.text,
            quotaHighlight = seg.highlight,
            quotaTier = when (seg.level) {
                com.openminis.app.quota.QuotaLevel.normal -> ContextUsage.Tier.NORMAL
                com.openminis.app.quota.QuotaLevel.low -> ContextUsage.Tier.WARNING
                com.openminis.app.quota.QuotaLevel.depleted -> ContextUsage.Tier.CRITICAL
            },
        )
    }

    internal fun debugShowContextUsageHint(
        percent: Int,
        windowTokens: Int,
        quota: DebugUsageHint.Quota?,
        useLiveQuota: Boolean,
    ): ContextUsageHint? {
        if (!com.openminis.app.BuildConfig.DEV_TOOLS) return null
        contextHintGeneration += 1
        val base = DebugUsageHint.build(contextHintGeneration, percent, windowTokens, quota)
        val hint = if (useLiveQuota) base.withQuota(composerQuotaPart()) else base
        _contextUsageHint.value = hint
        AppLogger.info(TAG, "[ContextUsage] DEBUG hint gen=$contextHintGeneration tier=${hint.tier} percent=$percent quota=${hint.quotaText != null}")
        return hint
    }

    private fun announceContextUsageAfterCompaction() {
        if (isHelper) return
        val usage = ContextUsage.from(
            usedTokens = _lastTurnContextTokens.value,
            windowTokens = effectiveContextWindowTokens(),
        ) ?: return
        contextTierTracker.observe(usage.tier, System.currentTimeMillis())
        contextHintGeneration += 1
        _contextUsageHint.value = ContextUsageHint(
            generation = contextHintGeneration,
            text = "",
            highlights = listOf(
                "${usage.percent}%",
                TokenCountFormatter.sizePair(usage),
            ),
            tier = usage.tier,
        ).withQuota(composerQuotaPart())
        AppLogger.info(
            TAG,
            "[ContextUsage] post-compact hint gen=$contextHintGeneration tier=${usage.tier} " +
                "${usage.percent}% (${usage.usedTokens}/${usage.windowTokens})",
        )
    }

    private val _compactSummary = MutableStateFlow<String?>(null)
    val compactSummary: StateFlow<String?> = _compactSummary.asStateFlow()

    private val _isCompacting = MutableStateFlow(false)
    val isCompacting: StateFlow<Boolean> = _isCompacting.asStateFlow()

    data class CompactProgress(
        val startedAtMs: Long,
        val depth: Int = 0,
        val callsIssued: Int = 0,
        val callBudget: Int = MAX_COMPACT_LLM_CALLS,
        val timeoutSeconds: Int = 0,
        val modelName: String? = null,
    )

    private val _compactProgress = MutableStateFlow<CompactProgress?>(null)
    val compactProgress: StateFlow<CompactProgress?> = _compactProgress.asStateFlow()

    private val compactCallsIssued = java.util.concurrent.atomic.AtomicInteger(0)

    private var compactJob: Job? = null

    fun cancelCompact() {
        val job = compactJob ?: return
        if (!job.isActive) return
        AppLogger.info(TAG, "[Compact] cancelled by user")
        job.cancel(CancellationException("compact cancelled by user"))
    }

    private val _autoRetryAttempt = MutableStateFlow(0)
    val autoRetryAttempt: StateFlow<Int> = _autoRetryAttempt.asStateFlow()

    private val _autoRetryCountdown = MutableStateFlow(0)
    val autoRetryCountdown: StateFlow<Int> = _autoRetryCountdown.asStateFlow()

    val autoRetryMax: Int get() = AUTO_RETRY_DELAYS_SEC.size

    private val _retryLog = MutableStateFlow<List<RetryRecord>>(emptyList())
    val retryLog: StateFlow<List<RetryRecord>> = _retryLog.asStateFlow()

    @Suppress("unused")
    private val retryLogReset = viewModelScope.launch {
        _isStreaming.collect { streaming ->
            if (!streaming && _retryLog.value.isNotEmpty()) _retryLog.value = emptyList()
        }
    }

    @Volatile
    private var streamJob: Job? = null
    private var currentProvider: LLMProvider? = null
    private var currentModel: LLMModel? = null

    private val currentModelHasNativeVision: Boolean
        get() = currentModel?.hasImageInput == true

    private fun currentModelSnapshot(): com.openminis.app.data.model.ModelAttributionSnapshot? {
        val model = currentModel ?: return null
        val entry = _activeEntryId.value?.let { id ->
            providerRepository.config.value.modelEntries.find { it.id == id }
        }
        val instance = entry?.let { providerRepository.instance(it.providerInstanceId) }
        return com.openminis.app.data.model.ModelAttributionSnapshot(
            modelId = model.id,
            displayName = model.displayName,
            providerTypeRaw = instance?.providerType?.name ?: "",
            providerInstanceId = entry?.providerInstanceId,
        )
    }

    private val agentHistory = mutableListOf<LLMMessage>()

    private val agentTools: List<AgentToolDefinition>
        get() = AgentTools.makeAgentTools(
            supportsImageInput = currentModelHasNativeVision,
            visionGroupConfigured = com.openminis.app.tools.VisionGroupResolver.isConfigured(
                providerRepository, context,
            ),
            memoryEnabled = _memoryEnabled.value,
            isHelper = isHelper,
            delegateEnabled = com.openminis.app.tools.AgentToolSwitch.AGENTS.isEnabled(context),
            browserEnabled = com.openminis.app.tools.AgentToolSwitch.BROWSER.isEnabled(context),
            rosterNames = providerRepository.subAgents.map { it.name },
            userAskExposed = userAskExposedThisRun,
            skillsAvailable = skillRepository?.skills?.value?.any {
                skillRepository.isEnabledForSession(it.id, activeSessionId)
            } ?: false,
            mcpTools = com.openminis.app.tools.McpToolRegistry.definitions(),
        )

    private fun executeResumeAgents(argsJson: String): ToolExecutionResult {
        val o = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val toolTitle = o.optString("tool_title", "").ifEmpty { "resume sub agents" }
        val hr = com.openminis.app.agent.jobs.HelperRunner
        if (isHelper) {
            return ToolExecutionResult(
                hr.rejectionJson("depth_limit", "A sub agent cannot resume another."),
                false, toolTitle = toolTitle,
            )
        }
        val wanted = o.optString("child_session_id", "").trim()
        val candidates = interruptedChildIds(activeSessionId)
            .let { all -> if (wanted.isEmpty()) all else all.filter { it == wanted } }
        if (candidates.isEmpty()) {
            return ToolExecutionResult(
                JSONObject()
                    .put("ok", true).put("resumed", 0)
                    .put(
                        "detail",
                        if (wanted.isEmpty()) {
                            "No interrupted sub agents in this conversation."
                        } else {
                            "That sub agent is not interrupted; nothing to resume."
                        },
                    ).toString(),
                true, toolTitle = toolTitle,
            )
        }
        val registry = com.openminis.app.agent.jobs.AgentJobRegistry
        val resumed = org.json.JSONArray()
        val deferred = org.json.JSONArray()
        val queued = org.json.JSONArray()
        for (childId in candidates) {
            when {
                registry.canStartChildJob(activeSessionId) -> {
                    if (resumeInterruptedChild(childId)) resumed.put(childId) else deferred.put(childId)
                }
                enqueueResume(childId) -> queued.put(childId)
                else -> deferred.put(childId)
            }
        }
        return ToolExecutionResult(
            JSONObject()
                .put("ok", resumed.length() > 0 || queued.length() > 0)
                .put("resumed", resumed.length())
                .put("child_session_ids", resumed)
                .also { if (deferred.length() > 0) it.put("failed", deferred) }
                .also { if (queued.length() > 0) it.put("queued", queued) }
                .put(
                    "detail",
                    buildList {
                        if (resumed.length() > 0) {
                            add(
                                "${resumed.length()} sub agent(s) restarted; each reports back as a new " +
                                    "message when it finishes. Do not re-delegate them. Elapsed and turn " +
                                    "counts cover only the resumed part.",
                            )
                        }
                        if (queued.length() > 0) {
                            add("All slots are busy; this resume is queued and starts when one frees.")
                        }
                        if (deferred.length() > 0 && resumed.length() == 0 && queued.length() == 0) {
                            add("None could be resumed.")
                        }
                    }.joinToString(" "),
                )
                .toString(),
            true, toolTitle = toolTitle,
        )
    }

    private fun createChildViewModel(
        childId: String,
        parentSid: String,
        toolId: String,
        jobId: String,
        subAgent: com.openminis.app.data.model.SubAgentDefinition,
        maxTurns: Int,
        title: String,
    ): ChatViewModel =
        com.openminis.app.debug.HeadlessChatRunner.viewModelFor(
            context, childId, com.openminis.app.ui.chat.ChatViewModelStore.PoolKind.CHILD,
        ).also {
            it.helperConfig = com.openminis.app.agent.jobs.HelperConfig(
                parentSessionId = parentSid, parentToolUseId = toolId, jobId = jobId,
                maxTurns = maxTurns, title = title,
                tierUsed = com.openminis.app.agent.jobs.HelperModelTier.PRIMARY,
                agentName = subAgent.name.takeIf { !subAgent.isBuiltIn },
                agentInstructions = subAgent.instructions,
            )
            it.adoptBrowserTabPool(browserTabPool)
        }

    private fun enqueueResume(childId: String): Boolean {
        val registry = com.openminis.app.agent.jobs.AgentJobRegistry
        val parentSid = activeSessionId
        if (parentSid.isEmpty()) return false
        registry.registerQueuedStarter(parentSid) { item ->
            startQueuedDelegation(item.argsJson, item.toolUseId)
        }
        registry.registerInterruptedCounter(parentSid) { countInterruptedChildren(parentSid) }
        val args = try {
            JSONObject()
                .put(com.openminis.app.agent.jobs.HelperRunner.QUEUED_RESUME_CHILD_KEY, childId)
                .toString()
        } catch (_: Exception) {
            return false
        }
        return registry.enqueueDelegation(
            com.openminis.app.agent.jobs.AgentJobRegistry.QueuedDelegation(
                parentSessionId = parentSid,
                argsJson = args,
                toolUseId = resumeQueueKey(childId),
            ),
        )
    }

    private fun resumeInterruptedChild(childId: String): Boolean {
        val hr = com.openminis.app.agent.jobs.HelperRunner
        val registry = com.openminis.app.agent.jobs.AgentJobRegistry
        val parentSid = activeSessionId
        var toolId = ""
        var title = ""
        var agentName: String? = null
        outer@ for (m in _messages.value) {
            if (m.role != "assistant") continue
            for (b in m.toolBlocks) {
                if (b.kind != "tool_use") continue
                if (!hr.isSubAgentToolName(b.toolName)) continue
                val o = runCatching { JSONObject(b.content) }.getOrNull() ?: continue
                if (o.optString("child_session_id") != childId) continue
                toolId = b.id
                title = b.toolTitle.ifEmpty { o.optString("title", "") }
                agentName = o.optString("agent", "").ifEmpty { null }
                break@outer
            }
        }
        if (toolId.isEmpty()) {
            AppLogger.warning(TAG, "[subagent_task] resume: no parent block for child ${childId.take(8)}")
            return false
        }
        val roster = providerRepository.subAgents
        val subAgent = com.openminis.app.data.model.SubAgentRoster.resolve(agentName, roster)
            ?: roster.firstOrNull()
            ?: return false

        val job = registry.register(
            title = title.ifEmpty { "agent" },
            origin = com.openminis.app.agent.jobs.AgentJobOrigin.TOOL,
            trigger = com.openminis.app.agent.jobs.AgentJobTrigger.Immediate,
            target = com.openminis.app.agent.jobs.AgentJobTarget.ChildOfCurrent(parentSid, toolId),
            prompt = null,
            then = com.openminis.app.agent.jobs.AgentJobThen.FollowUpParent(null),
            agentName = subAgent.name.takeIf { !subAgent.isBuiltIn },
            runSessionId = childId,
            wasResumed = true,
        )
        registry.registerInterruptedCounter(parentSid) { countInterruptedChildren(parentSid) }
        registry.registerQueuedStarter(parentSid) { item ->
            startQueuedDelegation(item.argsJson, item.toolUseId)
        }
        viewModelScope.launch {
            try {
                val childVm = withContext(Dispatchers.Main) {
                    createChildViewModel(
                        childId, parentSid, toolId, job.id, subAgent, hr.MAX_TURNS, title,
                    )
                }
                registry.markRunning(job.id, childId) {
                    viewModelScope.launch(Dispatchers.Main) { childVm.cancelStream() }
                }
                childVm.helperClosingAttempted = false
                com.openminis.app.agent.jobs.SubAgentTerminationStore.shared.begin(childId, job.id, parentSid)
                registry.registerSteerHook(job.id) { msg -> childVm.enqueueSteer(msg) }
                registry.registerMissedSteerDrain(job.id) { childVm.drainMissedSteers() }
                writeDelegateBlock(
                    toolId,
                    hr.progressJson(childId, title, "", "", 0L, background = true),
                    ToolBlockStatus.RUNNING,
                )
                registry.setCompletionHook(job.id) { finished ->
                    val termination = recordChildTermination(
                        childVm, childId, finished.id, parentSid,
                        loopStatus = hr.statusWord(finished.state),
                        budgetExpired = childVm.helperWrapUpRequested,
                        errorText = childVm.messages.value.lastOrNull { it.role == "assistant" }?.error,
                        resultText = finished.resultText ?: "",
                        delivery = com.openminis.app.agent.jobs.SubAgentTermination.Delivery.PENDING,
                    )
                    viewModelScope.launch(Dispatchers.IO) {
                        val facts = hr.childRunFacts(chatRepository.dao.loadMessages(childId))
                        val summary = hr.runSummaryLine(facts.toolNames, facts.turns, facts.input, facts.output, facts.cacheRead)
                        registry.setSummaryLine(finished.id, summary)
                        val result = finished.resultText ?: ""
                        val status = hr.reportedStatus(hr.statusWord(finished.state), result, termination)
                        val json = hr.resultJson(
                            status, result, childVm.modelName.value,
                            com.openminis.app.agent.jobs.HelperModelTier.PRIMARY, "",
                            facts.turns, finished.elapsedMs ?: 0L, childId, finished.id,
                            summary, deliveredAs = "new turn in this conversation",
                            agentName = subAgent.name.takeIf { !subAgent.isBuiltIn },
                            wasResumed = true,
                            errorText = childVm.messages.value.lastOrNull { it.role == "assistant" }?.error,
                            thinkingLevel = chatRepository.getSession(childId)?.thinkingOverride,
                            termination = termination,
                        )
                        val blockStatus = when (finished.state) {
                            com.openminis.app.agent.jobs.AgentJobState.DONE -> ToolBlockStatus.SUCCESS
                            com.openminis.app.agent.jobs.AgentJobState.CANCELLED -> ToolBlockStatus.CANCELLED
                            com.openminis.app.agent.jobs.AgentJobState.TIMEOUT -> ToolBlockStatus.TIMEOUT
                            else -> ToolBlockStatus.FAILED
                        }
                        withContext(Dispatchers.Main) { writeDelegateBlock(toolId, json, blockStatus) }
                        persistFinalDelegateResult(toolId, json, finished.state == com.openminis.app.agent.jobs.AgentJobState.DONE)
                        AppLogger.info(TAG, "[subagent_task] RESUME END job=${finished.id.take(8)} state=${finished.state.wire}")
                    }
                }
                val outcome = withContext(Dispatchers.Main) { childVm.submitPrompt(hr.resumeNotice()) }
                if (outcome is SubmitOutcome.Rejected) {
                    AppLogger.warning(TAG, "[subagent_task] resume not started for ${childId.take(8)}: ${outcome.reason}")
                    registry.finish(job.id, com.openminis.app.agent.jobs.AgentJobState.FAILED, null)
                    return@launch
                }
                AppLogger.info(TAG, "[subagent_task] resumed child=${childId.take(8)} job=${job.id.take(8)}")
                if (outcome is SubmitOutcome.Compacting) {
                    childVm.isCompacting.first { !it }
                    withTimeoutOrNull(5_000L) { childVm.isStreaming.first { it } }
                }
                childVm.isStreaming.first { !it }
                val facts = hr.childRunFacts(chatRepository.dao.loadMessages(childId))
                val state = if (childVm.messages.value.lastOrNull { it.role == "assistant" }?.error != null) {
                    com.openminis.app.agent.jobs.AgentJobState.FAILED
                } else {
                    com.openminis.app.agent.jobs.AgentJobState.DONE
                }
                registry.setSummaryLine(job.id, hr.runSummaryLine(facts.toolNames, facts.turns, facts.input, facts.output, facts.cacheRead))
                registry.finish(job.id, state, facts.lastText)
            } catch (e: kotlinx.coroutines.CancellationException) {
                registry.finish(job.id, com.openminis.app.agent.jobs.AgentJobState.CANCELLED, null)
                throw e
            } catch (e: Exception) {
                AppLogger.warning(TAG, "[subagent_task] resume failed for ${childId.take(8)}: ${e.message}")
                registry.finish(job.id, com.openminis.app.agent.jobs.AgentJobState.FAILED, null)
            }
        }
        return true
    }

    private fun interruptedChildIds(parentSessionId: String): List<String> {
        if (parentSessionId.isEmpty()) return emptyList()
        val registry = com.openminis.app.agent.jobs.AgentJobRegistry
        val out = mutableListOf<String>()
        for (m in _messages.value) {
            if (m.role != "assistant") continue
            for (b in m.toolBlocks) {
                if (b.kind != "tool_use") continue
                if (!com.openminis.app.agent.jobs.HelperRunner.isSubAgentToolName(b.toolName)) continue
                val o = runCatching { JSONObject(b.content) }.getOrNull() ?: continue
                if (o.optString("status") != "running") continue
                val childId = o.optString("child_session_id", "")
                if (childId.isEmpty() || childId in out) continue
                if (registry.jobForSession(childId) != null) continue
                out.add(childId)
            }
        }
        return out
    }

    internal fun countInterruptedChildren(parentSessionId: String): Int {
        if (parentSessionId.isEmpty()) return 0
        val registry = com.openminis.app.agent.jobs.AgentJobRegistry
        var n = 0
        for (m in _messages.value) {
            if (m.role != "assistant") continue
            for (b in m.toolBlocks) {
                if (b.kind != "tool_use") continue
                if (!com.openminis.app.agent.jobs.HelperRunner.isSubAgentToolName(b.toolName)) continue
                val o = try {
                    JSONObject(b.content)
                } catch (_: Exception) {
                    continue
                }
                if (o.optString("status") != "running") continue
                val childId = o.optString("child_session_id", "")
                if (childId.isEmpty()) continue
                if (registry.jobForSession(childId) != null) continue
                n += 1
            }
        }
        return n
    }

    fun startNeverStartedDelegation(block: AssistantBlock): Boolean {
        val args = block.toolArgs
        if (args.isBlank()) return false
        if (com.openminis.app.agent.jobs.HelperRunner.isControlOnly(args, block.content.ifEmpty { null })) {
            return false
        }
        return startQueuedDelegation(args, block.id)
    }

    enum class CardResume { STARTED, QUEUED, REFUSED }

    private fun resumeQueueKey(childId: String) = "resume:$childId"

    fun resumeInterruptedFromCard(block: AssistantBlock): CardResume {
        val childId = com.openminis.app.agent.jobs.HelperRunner.childSessionIdFrom(block.content)
            ?: return CardResume.REFUSED
        if (com.openminis.app.agent.jobs.HelperRunner.isQueuedLive(resumeQueueKey(childId))) {
            return CardResume.QUEUED
        }
        if (childId !in interruptedChildIds(activeSessionId)) {
            AppLogger.info(TAG, "[subagent_task] card resume: ${childId.take(8)} is not interrupted")
            return CardResume.REFUSED
        }
        val outcome = when {
            com.openminis.app.agent.jobs.AgentJobRegistry.canStartChildJob(activeSessionId) ->
                if (resumeInterruptedChild(childId)) CardResume.STARTED else CardResume.REFUSED
            enqueueResume(childId) -> CardResume.QUEUED
            else -> CardResume.REFUSED
        }
        AppLogger.info(TAG, "[subagent_task] card resume child=${childId.take(8)} -> $outcome")
        return outcome
    }

    fun startQueuedDelegation(argsJson: String, toolUseId: String): Boolean {
        if (activeSessionId.isEmpty()) return false
        val resumeChildId = runCatching {
            JSONObject(argsJson)
                .optString(com.openminis.app.agent.jobs.HelperRunner.QUEUED_RESUME_CHILD_KEY, "")
        }.getOrDefault("").ifEmpty { null }
        if (resumeChildId != null) {
            return resumeInterruptedChild(resumeChildId)
        }
        val args = try {
            JSONObject(argsJson)
                .put(com.openminis.app.agent.jobs.HelperRunner.QUEUED_REENTRY_KEY, true)
                .put("wait", false)
                .toString()
        } catch (_: Exception) {
            return false
        }
        val scratch = mutableListOf(
            AssistantBlock(
                id = toolUseId,
                kind = "tool_use",
                content = "",
                toolName = com.openminis.app.agent.jobs.HelperRunner.TOOL_NAME,
            ),
        )
        viewModelScope.launch {
            runCatching {
                executeDelegateTask(args, toolUseId, scratch, "", "")
            }.onFailure {
                AppLogger.warning(TAG, "[subagent_task] queued re-entry failed: ${it.message}")
            }
        }
        return true
    }

    private fun subAgentRosterPromptSection(): String {
        if (isHelper) return ""
        if (!com.openminis.app.tools.AgentToolSwitch.AGENTS.isEnabled(context)) return ""
        return com.openminis.app.agent.jobs.HelperRunner.subAgentRosterSection(
            roster = providerRepository.subAgents,
            groupName = { id -> providerRepository.group(id)?.name },
        )
    }

    private val toolLoopDetector = ToolLoopDetector()

    @Volatile
    private var _browserTabPoolRef: BrowserTabPool? = null

    val browserTabPool: BrowserTabPool
        get() = _browserTabPoolRef ?: synchronized(this) {
            _browserTabPoolRef ?: BrowserTabPool(context).also {
                it.setSession(activeSessionId)
                it.onDownloadEvent = { text ->
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        appendSystemInfo(text, "info")
                    }
                }
                _browserTabPoolRef = it
            }
        }

    internal fun adoptBrowserTabPool(pool: BrowserTabPool) { _browserTabPoolRef = pool }

    internal val _showBrowserSheet = MutableStateFlow(false)
    val showBrowserSheet: StateFlow<Boolean> = _showBrowserSheet.asStateFlow()


    internal val _showMemorySheet = MutableStateFlow(false)
    val showMemorySheet: StateFlow<Boolean> = _showMemorySheet.asStateFlow()

    private val _clearChatConfirmRequested = MutableStateFlow(false)
    val clearChatConfirmRequested: StateFlow<Boolean> = _clearChatConfirmRequested.asStateFlow()

    fun ackClearChatConfirmRequest() {
        _clearChatConfirmRequested.value = false
    }

    private val _memoryToolRecords = MutableStateFlow<List<MemoryToolRecord>>(emptyList())
    val memoryToolRecords: StateFlow<List<MemoryToolRecord>> = _memoryToolRecords.asStateFlow()

    fun revokeMemoryRecord(record: MemoryToolRecord): com.openminis.app.data.repository.MemoryRepository.EntryMutationResult {
        val repo = memoryRepository
            ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.IOError("Memory not available")
        val written = record.writtenContent
            ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.NotFound
        val result = repo.revokeEntry(written)
        if (result is com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.Success) {
            _memoryToolRecords.value = _memoryToolRecords.value - record
        }
        return result
    }

    private fun revokeMemoryWritesInDeletedMessages(deletedMessages: List<ChatMessage>) {
        if (memoryRepository == null) return
        val deletedContents = mutableListOf<String>()
        for (msg in deletedMessages) {
            for (block in msg.toolBlocks) {
                if (block.kind != "tool_use") continue
                if (block.toolName != "memory_write") continue
                val content = try {
                    JSONObject(block.toolArgs).optString("content", "")
                } catch (_: Exception) { "" }
                if (content.isNotBlank()) deletedContents.add(content)
            }
        }
        if (deletedContents.isEmpty()) return
        Log.i(TAG, "revokeMemoryWritesInDeletedMessages: ${deletedContents.size} write(s) to revoke")
        for (content in deletedContents.asReversed()) {
            val record = _memoryToolRecords.value.lastOrNull {
                it.isWrite && it.writtenContent == content
            } ?: continue
            val result = revokeMemoryRecord(record)
            Log.i(TAG, "  revoke result: ${result::class.simpleName}")
        }
    }

    fun replaceMemoryRecord(
        record: MemoryToolRecord,
        newContent: String,
    ): com.openminis.app.data.repository.MemoryRepository.EntryMutationResult {
        val repo = memoryRepository
            ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.IOError("Memory not available")
        val old = record.writtenContent
            ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.NotFound
        val result = repo.replaceEntryBody(old, newContent)
        if (result is com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.Success) {
            _memoryToolRecords.value = _memoryToolRecords.value.map {
                if (it === record) it.copy(
                    writtenContent = newContent,
                    preview = newContent.lines().firstOrNull { line -> line.isNotBlank() }?.take(100) ?: "",
                ) else it
            }
        }
        return result
    }


    internal val _memoryEnabled =
        MutableStateFlow(com.openminis.app.data.MemoryGlobalPrefs.isGlobalEnabled(context))
    val memoryEnabled: StateFlow<Boolean> = _memoryEnabled.asStateFlow()

    internal val _revealedUsageIds = MutableStateFlow<Set<String>>(emptySet())
    val revealedUsageIds: StateFlow<Set<String>> = _revealedUsageIds.asStateFlow()

    fun toggleUsageCapsule(messageId: String) {
        val cur = _revealedUsageIds.value
        _revealedUsageIds.value = if (messageId in cur) cur - messageId else cur + messageId
    }

    internal val _thinkingLevel = MutableStateFlow(ThinkingLevel.OFF)
    val thinkingLevel: StateFlow<ThinkingLevel> = _thinkingLevel.asStateFlow()

    internal val _enhancedCacheEnabled = MutableStateFlow(false)
    val enhancedCacheEnabled: StateFlow<Boolean> = _enhancedCacheEnabled.asStateFlow()

    val showEnhancedCacheToggle: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(
            _activeEntryId,
            providerRepository.config,
        ) { entryId, config ->
            val entry = entryId?.let { id -> config.modelEntries.find { it.id == id } }
            val instance = entry?.let { e -> config.instances.find { it.id == e.providerInstanceId } }
            instance != null &&
                instance.providerType == com.openminis.app.data.model.ProviderType.anthropic &&
                instance.customBaseURL.isNullOrBlank()
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
        )

    fun isEnhancedCacheConfirmed(): Boolean =
        com.openminis.app.data.EnhancedCachePrefs.isConfirmed(context)

    fun confirmAndEnableEnhancedCache() {
        com.openminis.app.data.EnhancedCachePrefs.setConfirmed(context)
        _enhancedCacheEnabled.value = true
    }

    fun setEnhancedCacheEnabled(enabled: Boolean) {
        _enhancedCacheEnabled.value = enabled
    }

    internal val _speedTier =
        MutableStateFlow(com.openminis.app.data.FastModePrefs.tier())
    val speedTier: StateFlow<com.openminis.app.data.SpeedTier> = _speedTier.asStateFlow()

    fun setSpeedTier(tier: com.openminis.app.data.SpeedTier) {
        com.openminis.app.data.FastModePrefs.setTier(context, tier)
        _speedTier.value = tier
    }

    val fastModeEnabled: StateFlow<Boolean> =
        _speedTier.map { it != com.openminis.app.data.SpeedTier.STANDARD }
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.Eagerly,
                com.openminis.app.data.FastModePrefs.isEnabled(),
            )

    fun setFastModeEnabled(enabled: Boolean) {
        setSpeedTier(
            if (enabled) com.openminis.app.data.SpeedTier.FAST else com.openminis.app.data.SpeedTier.STANDARD,
        )
    }

    internal val _autoCompactEnabled =
        MutableStateFlow(com.openminis.app.data.AutoCompactPrefs.isEnabled())
    val autoCompactEnabled: StateFlow<Boolean> = _autoCompactEnabled.asStateFlow()

    fun setAutoCompactEnabled(enabled: Boolean) {
        com.openminis.app.data.AutoCompactPrefs.setEnabled(context, enabled)
        _autoCompactEnabled.value = enabled
    }

    private val speedEligibility: StateFlow<SpeedEligibility> =
        kotlinx.coroutines.flow.combine(
            _activeEntryId,
            providerRepository.config,
        ) { entryId, config ->
            val entry = entryId?.let { id -> config.modelEntries.find { it.id == id } }
            val instance = entry?.let { e -> config.instances.find { it.id == e.providerInstanceId } }
            val isCodexOAuth = instance != null &&
                instance.providerType == com.openminis.app.data.model.ProviderType.openAI &&
                instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth &&
                instance.customBaseURL.isNullOrBlank()
            val isXAI = instance?.providerType == com.openminis.app.data.model.ProviderType.xAI
            val responsesGpt = entry != null && instance != null && !isXAI &&
                entry.model.id.contains("gpt", ignoreCase = true) &&
                (instance.useResponsesAPI || isCodexOAuth)
            SpeedEligibility(
                fast = entry != null && instance != null && (isXAI || responsesGpt),
                ultrafast = responsesGpt && com.openminis.app.data.SpeedTiers.supportsUltrafast(entry!!.model),
            )
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            SpeedEligibility(fast = false, ultrafast = false),
        )

    val showFastModeToggle: StateFlow<Boolean> =
        speedEligibility.map { it.fast }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    val showUltrafastOption: StateFlow<Boolean> =
        speedEligibility.map { it.ultrafast }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    val displayedSpeedTier: StateFlow<com.openminis.app.data.SpeedTier> =
        kotlinx.coroutines.flow.combine(_speedTier, speedEligibility) { tier, elig ->
            com.openminis.app.data.SpeedTiers.displayed(tier, elig.fast, elig.ultrafast)
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            com.openminis.app.data.SpeedTier.STANDARD,
        )


    internal val _showSlashMenu = MutableStateFlow(false)
    val showSlashMenu: StateFlow<Boolean> = _showSlashMenu.asStateFlow()

    internal val _slashFilter = MutableStateFlow("")
    val slashFilter: StateFlow<String> = _slashFilter.asStateFlow()

    internal val _slashMenuSelectedIndex = MutableStateFlow(-1)
    val slashMenuSelectedIndex: StateFlow<Int> = _slashMenuSelectedIndex.asStateFlow()

    internal var savedInputBeforeSlash: String? = null

    val fileMentionIndex: FileMentionIndex by lazy {
        FileMentionIndex(
            filesDir = java.io.File(context.applicationContext.filesDir, "minis-global"),
            mountsProvider = {
                com.openminis.app.sandbox.PRootKernel
                    .mountEntriesForIndex(context.applicationContext)
            },
        )
    }

    internal val _showMentionMenu = MutableStateFlow(false)
    val showMentionMenu: StateFlow<Boolean> = _showMentionMenu.asStateFlow()

    internal val _mentionFilter = MutableStateFlow("")
    val mentionFilter: StateFlow<String> = _mentionFilter.asStateFlow()

    internal val _mentionAnchor = MutableStateFlow(-1)

    val mentionEntries: StateFlow<List<FileMentionIndex.Entry>> = combine(
        fileMentionIndex.entries,
        _mentionFilter,
    ) { _, filter -> fileMentionIndex.matches(filter, limit = 50) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isMentionScanning: StateFlow<Boolean>
        get() = fileMentionIndex.isScanning

    internal val _mentionSelectedIndex = MutableStateFlow(-1)
    val mentionSelectedIndex: StateFlow<Int> = _mentionSelectedIndex.asStateFlow()

    val currentModelSupportsReasoning: Boolean
        get() = currentModel?.supportsReasoning == true

    private val currentModelMaxThinkingLevel: ThinkingLevel
        get() {
            val entry = _activeEntryId.value?.let { id ->
                providerRepository.config.value.modelEntries.find { it.id == id }
            }
            if (entry != null) {
                return entry.effectiveMaxThinkingLevel
            }
            val model = currentModel ?: return ThinkingLevel.XHIGH
            return model.catalogMaxThinkingLevel
        }

    val availableThinkingLevels: List<ThinkingLevel>
        get() {
            val ceiling = currentModelMaxThinkingLevel
            return ThinkingLevel.entries.filter { it != ThinkingLevel.OFF && it.rank <= ceiling.rank }
        }

    val currentModelContextWindow: Int?
        get() = effectiveContextWindowTokens()

    private fun effectiveContextWindowTokens(): Int? = resolvedContextWindow()?.first

    private fun resolvedContextWindow(forModel: LLMModel? = null): Pair<Int, Boolean>? {
        val config = providerRepository.config.value
        val liveModel = forModel ?: _activeEntryId.value
            ?.let { id -> config.modelEntries.find { it.id == id }?.model }
            ?: currentModel
        val window = liveModel?.contextWindowTokens ?: return null
        val groupLimit = _selectedGroupId.value
            ?.let { gid -> config.modelGroups.find { it.id == gid }?.contextLimitTokens }
            ?.takeIf { it > 0 }
            ?: return window to false
        return if (groupLimit < window) groupLimit to true else window to false
    }

    private fun currentContextPolicy(): Pair<ContextPolicy, Int>? {
        val (window, isUserCap) = resolvedContextWindow() ?: return null
        val policy = if (isUserCap) {
            ContextPolicy.forUserCap(window)
        } else {
            ContextPolicy.forContextWindow(window)
        }
        return policy to window
    }

    val currentModelMaxOutputTokens: Int?
        get() = currentModel?.maxOutputTokens


    data class SessionTokenStats(
        val input: Long,
        val output: Long,
        val cacheRead: Long,
        val cacheWrite: Long,
        val context: Int,
        val loopCount: Int,
        val streamMs: Long = 0L,
        val streamOutput: Long = 0L,
    ) {
        val outputTokensPerSecond: Double?
            get() = if (streamMs > 0 && streamOutput > 0) {
                streamOutput.toDouble() / (streamMs / 1000.0)
            } else {
                null
            }

        val cacheHitRate: Double?
            get() {
                val totalInput = input + cacheRead + cacheWrite
                return if (totalInput > 0 && cacheRead > 0) {
                    cacheRead.toDouble() / totalInput.toDouble() * 100.0
                } else {
                    null
                }
            }
    }

    data class ThinkingInfo(
        val supported: Boolean,
        val enabled: Boolean,
        val level: String,
    )

    fun thinkingInfo(): ThinkingInfo? {
        val model = currentModel ?: return null
        val supported = model.supportsReasoning == true
        val level = _thinkingLevel.value
        val enabled = supported && level.isEnabled
        val levelText = if (enabled) level.displayName else "—"
        return ThinkingInfo(supported, enabled, levelText)
    }

    suspend fun loadSessionTokenStats(): SessionTokenStats {
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isEmpty()) return SessionTokenStats(0, 0, 0, 0, 0, 0)
        val usages = chatRepository.sessionTokenUsages(sid)
        var input = 0L
        var output = 0L
        var cacheRead = 0L
        var cacheWrite = 0L
        var context = 0
        var streamMs = 0L
        var streamOutput = 0L
        for (json in usages) {
            try {
                val obj = org.json.JSONObject(json)
                input += obj.optLong("inputTokens", 0L)
                val turnOutput = obj.optLong("outputTokens", 0L)
                output += turnOutput
                cacheRead += obj.optLong("cacheReadTokens", 0L)
                cacheWrite += obj.optLong("cacheCreationTokens", 0L)
                val ctx = obj.optInt("latestContextTokens", 0)
                if (ctx > 0) context = ctx
                val turnMs = obj.optLong("streamMs", 0L)
                if (turnMs > 0 && turnOutput > 0) {
                    streamMs += turnMs
                    streamOutput += turnOutput
                }
            } catch (_: Exception) {  }
        }
        val snapshot = _messages.value
        val assistantCount = snapshot.count { it.role == "assistant" }
        val toolCalls = snapshot.filter { it.role == "assistant" }
            .sumOf { msg -> msg.toolBlocks.count { it.kind != "text" && it.kind != "info" } }
        val loops = maxOf(toolCalls, assistantCount)
        return SessionTokenStats(input, output, cacheRead, cacheWrite, context, loops, streamMs, streamOutput)
    }



    internal val availableSlashCommands: List<SlashCommand> = listOf(
        SlashCommand(
            id = "clear",
            icon = Icons.Default.Delete,
            title = "Clear",
            subtitle = "",
        ),
        SlashCommand(
            id = "compact",
            icon = Icons.Default.Compress,
            title = "Compact",
            subtitle = "",
        ),
        SlashCommand(
            id = "memory",
            icon = Icons.Default.Psychology,
            title = "Memory",
            subtitle = "",
        ),
        SlashCommand(
            id = "thinking",
            icon = Icons.Default.Lightbulb,
            title = "Thinking",
            subtitle = "",
        ),
    )



    fun executeSlashCommand(cmd: SlashCommand, currentInput: String = ""): String {
        val saved = savedInputBeforeSlash
        if (cmd.isSkill || cmd.isMcp) {
            val kind = if (cmd.isMcp) "mcp" else "skill"
            AppLogger.info(TAG, "[Slash] tap $kind id=${cmd.id} title=${cmd.title} → composer fill only")
            savedInputBeforeSlash = null
            _showSlashMenu.value = false
            _slashMenuSelectedIndex.value = -1
            val prefix = "/${cmd.title} "
            return if (saved != null) {
                _pendingCaret.value = prefix.length
                prefix + saved
            } else {
                prefix
            }
        }
        AppLogger.info(TAG, "[Slash] tap id=${cmd.id} title=${cmd.title} streaming=${_isStreaming.value} compacting=${_isCompacting.value}")
        savedInputBeforeSlash = null
        _showSlashMenu.value = false
        _slashMenuSelectedIndex.value = -1

        when (cmd.id) {
            "compact" -> compactAll()
            "memory" -> toggleMemoryEnabled()
            "thinking" -> toggleThinking()
            "clear" -> _clearChatConfirmRequested.value = true
            else -> AppLogger.info(TAG, "[Slash] unrecognized id=${cmd.id} — no dispatch")
        }
        if (saved != null) {
            _pendingCaret.value = saved.length
            return saved
        }
        return ""
    }

    private fun toggleMemoryEnabled() {
        val newValue = !_memoryEnabled.value
        _memoryEnabled.value = newValue
        viewModelScope.launch {
            val sid = ensureSession()
            chatRepository.dao.updateMemoryEnabled(sid, if (newValue) 1 else 0)
        }
        appendSystemInfo(
            text = "Memory writes ${if (newValue) "enabled" else "disabled"}. Reads are unaffected.",
            iconKind = "memory",
        )
    }

    private fun toggleThinking() {
        if (!currentModelSupportsReasoning) {
            appendSystemInfo(
                text = "The current model does not support deep thinking.",
                iconKind = "thinking",
            )
            return
        }
        val newLevel = if (_thinkingLevel.value.isEnabled) ThinkingLevel.OFF else ThinkingLevel.MEDIUM
        _thinkingLevel.value = newLevel
        persistThinkingOverride(newLevel)
        appendSystemInfo(
            text = "Thinking set to ${newLevel.displayName.lowercase()}.",
            iconKind = "thinking",
        )
    }

    fun setThinkingLevel(level: ThinkingLevel) {
        if (!currentModelSupportsReasoning) return
        val ceiling = currentModelMaxThinkingLevel
        val clamped = if (level.rank > ceiling.rank) ceiling else level
        if (_thinkingLevel.value == clamped) return
        _thinkingLevel.value = clamped
        persistThinkingOverride(clamped)
    }

    private fun persistThinkingOverride(level: ThinkingLevel) {
        val sid = realSessionId
        if (sid.isEmpty()) return
        viewModelScope.launch {
            chatRepository.dao.updateThinkingOverride(sid, level.name)
        }
    }

    fun tryExecuteInputAsSlashCommand(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val first = trimmed[0]
        if (first != '/' && first != '／') return false
        val name = trimmed.drop(1).lowercase()
        val cmd = availableSlashCommands.firstOrNull { it.title.lowercase() == name }
            ?: return false
        executeSlashCommand(cmd)
        return true
    }

    private fun appendSystemInfo(text: String, iconKind: String, payload: String? = null) {
        val block = AssistantBlock(
            id = "sysinfo_${System.currentTimeMillis()}",
            kind = "info",
            content = text,
            toolName = iconKind,
            toolArgs = payload.orEmpty(),
        )
        _messages.value = _messages.value + ChatMessage(
            id = "sysinfo_${System.currentTimeMillis()}",
            role = "system",
            content = "",
            toolBlocks = listOf(block),
        )
    }

    fun runCompactNow() {
        compactAll()
    }

    fun compactBefore(dbMessageId: String, includesBoundary: Boolean = false) {
        AppLogger.info(
            TAG,
            "[Compact] compactBefore() id=${dbMessageId.take(8)} includesBoundary=$includesBoundary " +
                "(v2: includesBoundary ignored — caller-supplied id becomes the anchor)",
        )
        val history = agentHistory.toList()
        val idx = history.indexOfLast { it.dbMessageId == dbMessageId }
        if (idx < 0) {
            AppLogger.warning(
                TAG,
                "[Compact] compactBefore: id=${dbMessageId.take(8)} not in agentHistory — falling back to compactAll()",
            )
            compactAll(anchorIdxOverride = null)
            return
        }
        compactAll(anchorIdxOverride = idx)
    }

    private fun compactAll(
        anchorIdxOverride: Int? = null,
        allowDuringProcessing: Boolean = false,
        onFinished: ((Boolean) -> Unit)? = null,
    ) {
        var started = false
        compactAllImpl(anchorIdxOverride, allowDuringProcessing, onFinished) { started = true }
        if (!started) onFinished?.invoke(false)
    }

    private inline fun compactAllImpl(
        anchorIdxOverride: Int?,
        allowDuringProcessing: Boolean,
        noinline onFinished: ((Boolean) -> Unit)?,
        markStarted: () -> Unit,
    ) {
        AppLogger.info(TAG, "[Compact] compactAll() invoked streaming=${_isStreaming.value} compacting=${_isCompacting.value} historySize=${agentHistory.size} anchorOverride=$anchorIdxOverride inLoop=$allowDuringProcessing")
        if (_isStreaming.value && !allowDuringProcessing) {
            AppLogger.info(TAG, "[Compact] aborted: stream in progress")
            appendSystemInfo(
                text = "Cannot compact while a turn is in progress. Stop the current response first.",
                iconKind = "compact",
            )
            return
        }
        if (_isCompacting.value) {
            AppLogger.info(TAG, "[Compact] aborted: another compact already in flight")
            appendSystemInfo(
                text = "A compact is already in progress. Please wait for it to finish.",
                iconKind = "compact",
            )
            return
        }
        val provider = currentProvider ?: run {
            appendSystemInfo("No provider configured. Cannot compact.", "compact")
            return
        }
        val history = agentHistory.toList()
        if (history.isEmpty()) {
            appendSystemInfo("Nothing to compact — the session is empty.", "compact")
            return
        }
        val anchorIdx: Int = if (anchorIdxOverride != null) {
            var i = anchorIdxOverride.coerceIn(0, history.lastIndex)
            while (i >= 0 && history[i].dbMessageId.isNullOrEmpty()) i -= 1
            i
        } else {
            var i = history.lastIndex
            while (i >= 0 && history[i].dbMessageId.isNullOrEmpty()) i -= 1
            i
        }
        if (anchorIdx < 0) {
            appendSystemInfo("Cannot compact: no persisted messages yet.", "compact")
            return
        }

        val prev = _cachedLatestMarker
        val effectiveStartIdx: Int = if (prev == null) {
            0
        } else {
            val prevAnchorOrFirstKept: String? = if (prev.version >= 2) {
                prev.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
            } else {
                prev.firstKeptMessageId?.takeIf { it.isNotEmpty() }
                    ?: prev.boundaryMessageId?.takeIf { it.isNotEmpty() }
            }
            val prevIdx = prevAnchorOrFirstKept?.let { id ->
                history.indexOfFirst { it.dbMessageId == id }
            } ?: -1
            if (prevIdx < 0) 0
            else if (prev.version >= 2) prevIdx + 1
            else prevIdx
        }
        if (effectiveStartIdx > anchorIdx) {
            appendSystemInfo("Already compacted up to this point.", "compact")
            return
        }
        val toCompact = history.subList(effectiveStartIdx, anchorIdx + 1)
        if (toCompact.isEmpty()) {
            appendSystemInfo("Nothing to compact.", "compact")
            return
        }
        markStarted()
        val compactMeasuredBefore = runCatching { measureOutboundContextTokens() }.getOrDefault(-1)
        _isCompacting.value = true
        val transcriptChars = buildConversationTextForSummary(toCompact).length
        val timeoutMs = COMPACT_MAX_TOTAL_MS
        compactCallsIssued.set(0)
        _compactProgress.value = CompactProgress(
            startedAtMs = System.currentTimeMillis(),
            depth = 0,
            callsIssued = 0,
            callBudget = MAX_COMPACT_LLM_CALLS,
            timeoutSeconds = (timeoutMs / 1000L).toInt(),
            modelName = currentModel?.displayName,
        )
        AppLogger.info(
            TAG,
            "[Compact] starting: ${toCompact.size} entries, ${transcriptChars} transcript chars, " +
                "idleTimeout=${COMPACT_IDLE_TIMEOUT_MS / 1000}s, " +
                "maxTotal=${timeoutMs / 1000}s, callBudget=$MAX_COMPACT_LLM_CALLS",
        )
        compactJob = viewModelScope.launch(Dispatchers.IO) {
            var compactSucceeded = false
            var rollbackMarkerId: String? = null
            var previousMarker: CompactMarkerEntity? = null
            val priorMarkerBeforeCompact: CompactMarkerEntity? = _cachedLatestMarker
            var timedOut = false
            try {
                val existing = _compactSummary.value
                var summary = ""
                run {
                    val strategy = _selectedGroupId.value
                        ?.let { gid ->
                            providerRepository.config.value.modelGroups
                                .find { it.id == gid }?.fallbackStrategy
                        }
                        ?: com.openminis.app.data.model.FallbackStrategy.default
                    val tried = mutableSetOf<String>()
                    _activeEntryId.value?.let { tried.add(it) }
                    var attempt = 0
                    while (true) {
                        attempt++
                        try {
                            summary = withTimeout(timeoutMs) {
                                generateCompactSummaryWithSplitting(
                                    messages = toCompact,
                                    previousSummary = existing,
                                    depth = 0,
                                )
                            }.trim()
                            break
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            val actual = unwrapFlowException(e)
                            val err = actual as? com.openminis.app.data.model.LLMError
                            val worthSwitching = err?.isFallbackable == true ||
                                err?.isHttpServerError == true ||
                                actual is com.openminis.app.data.model.LLMError.RateLimited ||
                                strategy == com.openminis.app.data.model.FallbackStrategy.always
                            val next = if (!worthSwitching) null else {
                                buildFallbackProviders(
                                    currentProvider ?: throw e,
                                ).firstOrNull { it.entryId !in tried }
                            }
                            if (next == null) throw e
                            tried.add(next.entryId)
                            val from = currentModel?.displayName ?: "?"
                            withContext(Dispatchers.Main) {
                                adoptFallbackCandidate(next)
                                _compactProgress.value = _compactProgress.value
                                    ?.copy(modelName = next.provider.model.displayName)
                            }
                            AppLogger.warning(
                                TAG,
                                "[Compact] attempt $attempt on $from failed " +
                                    "(${actual.javaClass.simpleName}) — retrying on " +
                                    next.provider.model.displayName,
                            )
                            compactCallsIssued.set(0)
                        }
                    }
                }
                if (summary.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        appendSystemInfo("Compaction produced no output — try again later.", "compact")
                    }
                    return@launch
                }

                val sid = realSessionId.ifEmpty { sessionId }
                val rawDbIds: Set<String> = try {
                    chatRepository.dao.loadMessages(sid).map { it.id }.toSet()
                } catch (e: Exception) {
                    Log.w(TAG, "[Compact] loadMessages for raw-id verify failed: ${e.message}")
                    emptySet()
                }
                val verifiedAnchorIdx: Int = if (rawDbIds.isEmpty()) {
                    anchorIdx
                } else {
                    var i = anchorIdx
                    while (i >= 0) {
                        val id = history[i].dbMessageId
                        if (!id.isNullOrEmpty() && id in rawDbIds) break
                        i -= 1
                    }
                    i
                }
                if (verifiedAnchorIdx < 0) {
                    Log.w(TAG, "[Compact] No agentHistory entry has a DB-persisted dbMessageId; aborting")
                    withContext(Dispatchers.Main) {
                        appendSystemInfo("Compact failed: could not anchor to a persisted message.", "compact")
                    }
                    return@launch
                }
                if (verifiedAnchorIdx != anchorIdx) {
                    AppLogger.warning(
                        TAG,
                        "[Compact] anchor walked back from idx=$anchorIdx to idx=$verifiedAnchorIdx " +
                            "(closest with id in rawDbIds). Unsynced tail entries will fall on the active side of the divider.",
                    )
                }
                val lastCompactedDbId = history[verifiedAnchorIdx].dbMessageId
                    ?: run {
                        Log.w(TAG, "[Compact] verified anchor at idx=$verifiedAnchorIdx lost dbMessageId; aborting")
                        withContext(Dispatchers.Main) {
                            appendSystemInfo("Compact failed: anchor message id unavailable.", "compact")
                        }
                        return@launch
                    }
                val marker = CompactMarkerEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    sessionId = sid,
                    summary = summary,
                    firstKeptSortOrder = Int.MAX_VALUE,
                    compactedCount = toCompact.size,
                    createdAt = System.currentTimeMillis(),
                    uiBoundarySortOrder = null,
                    boundaryMessageId = null,
                    firstKeptMessageId = null,
                    lastCompactedMessageId = lastCompactedDbId,
                    version = 2,
                )
                val markerPersisted = runCatching {
                    chatRepository.dao.insertCompactMarker(marker)
                }.onFailure {
                    Log.w(TAG, "Failed to persist compact marker: ${it.message}")
                }.isSuccess
                if (markerPersisted) {
                    rollbackMarkerId = marker.id
                    previousMarker = priorMarkerBeforeCompact
                }
                _compactSummary.value = summary
                _cachedLatestMarker = marker
                withContext(Dispatchers.Main) {
                    val cutoffId: String = lastCompactedDbId
                    var passedCutoff = false
                    val cleaned = _messages.value
                        .filterNot { msg ->
                            msg.role == "system" &&
                                msg.toolBlocks.firstOrNull()?.toolName == "compact"
                        }
                        .map { msg ->
                            if (msg.role == "system") msg
                            else if (passedCutoff) msg
                            else {
                                val grayed = if (msg.isCompactedHistory) msg
                                    else msg.copy(isCompactedHistory = true)
                                if (msg.id == cutoffId) passedCutoff = true
                                grayed
                            }
                        }
                    val cutoffIdx = cleaned.indexOfLast { it.id == cutoffId }
                    val compactedUICount = if (cutoffIdx < 0) {
                        cleaned.count { it.role != "system" }
                    } else {
                        cleaned.take(cutoffIdx + 1).count { it.role != "system" }
                    }
                    val dividerBlock = AssistantBlock(
                        id = "sysinfo_${System.currentTimeMillis()}",
                        kind = "info",
                        content = "$compactedUICount messages compacted",
                        toolName = "compact",
                        toolArgs = summary,
                    )
                    val dividerRow = ChatMessage(
                        id = "sysinfo_${System.currentTimeMillis()}",
                        role = "system",
                        content = "",
                        toolBlocks = listOf(dividerBlock),
                    )
                    val withDivider = cleaned.toMutableList()
                    val dividerAt = if (cutoffIdx < 0) withDivider.size else cutoffIdx + 1
                    withDivider.add(dividerAt.coerceIn(0, withDivider.size), dividerRow)
                    _messages.value = withDivider
                    AppLogger.info(
                        TAG,
                        "[Compact] divider: $compactedUICount UI bubbles compacted " +
                            "(history entries: ${toCompact.size}) inserted at row $dividerAt " +
                            "of ${withDivider.size} (cutoffIdx=$cutoffIdx)",
                    )
                }
                compactSucceeded = true
            } catch (e: TimeoutCancellationException) {
                rollbackFailedCompactMarker(rollbackMarkerId, previousMarker)
                timedOut = true
                val elapsed = (timeoutMs / 1000L).toInt()
                val calls = compactCallsIssued.get()
                Log.w(TAG, "[Compact] hit the ${elapsed}s total ceiling ($calls model call(s) issued)")
                withContext(Dispatchers.Main) {
                    appendSystemInfo(
                        text = "Compaction stopped after ${elapsed / 60} minutes " +
                            "($calls model call(s) attempted) — the model kept producing " +
                            "output without finishing. Try compacting again, or start a " +
                            "new session if it keeps happening.",
                        iconKind = "compact",
                    )
                }
            } catch (e: CompactIdleTimeoutException) {
                timedOut = true
                val calls = compactCallsIssued.get()
                Log.w(TAG, "[Compact] idle timeout: ${e.message} ($calls model call(s) issued)")
                withContext(Dispatchers.Main) {
                    appendSystemInfo(
                        text = "Compaction stalled — no response from the model for " +
                            "${COMPACT_IDLE_TIMEOUT_MS / 1000}s " +
                            "($calls model call(s) attempted). Check your connection " +
                            "and try compacting again.",
                        iconKind = "compact",
                    )
                }
            } catch (e: CancellationException) {
                if (compactJob?.isCancelled == true) {
                    runCatching {
                        withContext(NonCancellable + Dispatchers.Main) {
                            appendSystemInfo("Compaction cancelled.", "compact")
                        }
                    }
                }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Compact failed", e)
                rollbackFailedCompactMarker(rollbackMarkerId, previousMarker)
                withContext(Dispatchers.Main) {
                    appendSystemInfo(
                        text = "Compaction failed: ${e.message ?: e.javaClass.simpleName}",
                        iconKind = "compact",
                    )
                }
            } finally {
                _isCompacting.value = false
                _compactProgress.value = null
                AppLogger.info(
                    TAG,
                    "[Compact] finished: success=$compactSucceeded timedOut=$timedOut " +
                        "calls=${compactCallsIssued.get()}",
                )
                if (compactSucceeded) runCatching {
                    publishMeasuredContextUsage()
                    announceContextUsageAfterCompaction()
                    AppLogger.info(TAG, "[CtxMeter] compacted marker=${_cachedLatestMarker?.id?.take(8)} measured $compactMeasuredBefore→${measureOutboundContextTokens()}")
                }
                onFinished?.invoke(compactSucceeded)
            }
            if (compactSucceeded && _promptQueue.value.isNotEmpty()) {
                AppLogger.info(TAG, "[Compact] success with ${_promptQueue.value.size} queued prompt(s) — kicking drain")
                resumeQueueAfterCancel()
            }
        }
    }

    private suspend fun rollbackFailedCompactMarker(
        markerId: String?,
        previousMarker: CompactMarkerEntity?,
    ) {
        if (markerId == null) return
        val removed = runCatching { chatRepository.dao.deleteCompactMarker(markerId) }
            .onFailure { Log.w(TAG, "[Compact] rollback: delete failed: ${it.message}") }
            .getOrNull() ?: 0
        _cachedLatestMarker = previousMarker
        _compactSummary.value = previousMarker?.summary
        AppLogger.info(
            TAG,
            "[Compact] rolled back failed marker ${markerId.take(8)} (rows=$removed) — " +
                "restored previous=${previousMarker?.id?.take(8) ?: "none"}",
        )
    }

    fun revertCompact() {
        if (_isStreaming.value) {
            appendSystemInfo("Cannot revert compact while a response is in progress.", "compact")
            return
        }
        if (_isCompacting.value) {
            appendSystemInfo("Cannot revert compact while compaction is in progress.", "compact")
            return
        }
        val current = _cachedLatestMarker ?: run {
            appendSystemInfo("Nothing to revert — no compact marker on this session.", "compact")
            return
        }
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            AppLogger.info(TAG, "[Compact] ━━━ REVERT ━━━ session=${sid.take(8)} markerId=${current.id.take(8)} v=${current.version}")
            val removed = runCatching { chatRepository.dao.deleteCompactMarker(current.id) }.getOrNull() ?: 0
            if (removed <= 0) {
                Log.w(TAG, "[Compact] revert: deleteCompactMarker returned 0 rows for id=${current.id.take(8)}")
                withContext(Dispatchers.Main) {
                    appendSystemInfo("Revert failed: marker not found in DB.", "compact")
                }
                return@launch
            }

            val next = chatRepository.dao.latestCompactMarker(sid)
            _cachedLatestMarker = next
            _compactSummary.value = next?.summary

            withContext(Dispatchers.Main) {
                _messages.value = _messages.value.filterNot { msg ->
                    msg.role == "system" &&
                        msg.toolBlocks.firstOrNull()?.toolName == "compact"
                }
            }

            pendingRevertLogMarker = current.id.take(8)
            withContext(Dispatchers.Main) {
                reloadSessionFromDb()
            }

            if (next != null) {
                AppLogger.info(TAG, "[Compact] revert DONE: now showing previous marker id=${next.id.take(8)} v=${next.version}")
            } else {
                AppLogger.info(TAG, "[Compact] revert DONE: no remaining markers, full history active")
            }
        }
    }

    private fun reloadSessionFromDb() {
        if (realSessionId.isEmpty() && sessionId.isEmpty()) return
        loadSession()
    }

    private fun auditOutgoingPayload(messages: List<LLMMessage>): List<LLMMessage> =
        RequestContextBuilder.auditOutgoingPayload(messages, ::effectiveContextWindowTokens)

    private fun offloadLargestPartForOverflow(): Boolean {
        val sid = activeSessionId
        if (sid.isEmpty()) return false
        val protectedCount = minOf(4, agentHistory.size)
        val upper = agentHistory.size - protectedCount
        if (upper <= 0) return false

        var bestMsg = -1
        var bestPart = -1
        var bestBytes = 0
        var bestKind = ""
        for (msgIdx in 0 until upper) {
            for ((partIdx, part) in agentHistory[msgIdx].contentParts.withIndex()) {
                val (bytes, kind) = when (part) {
                    is AgentContentPart.ImageData -> base64Size(part.data.size) to "image"
                    is AgentContentPart.ToolResult -> {
                        if (ContextOffload.isOffloadPlaceholder(part.content)) continue
                        (part.content.length + base64Size(part.imageData?.size ?: 0)) to "tool_result"
                    }
                    else -> continue
                }
                if (bytes > bestBytes) {
                    bestBytes = bytes; bestMsg = msgIdx; bestPart = partIdx; bestKind = kind
                }
            }
        }
        if (bestMsg < 0 || bestBytes < PayloadSizeAudit.MIN_BYTES_TO_AUDIT) return false

        val msg = agentHistory[bestMsg]
        val parts = msg.contentParts.toMutableList()
        val part = parts[bestPart]
        val tokens = countPartTokens(part)
        val linuxPath = when (part) {
            is AgentContentPart.ImageData -> ContextOffload.offloadImage(
                context, sid, part.data,
                toolId = "overflow_${bestMsg}_$bestPart",
                mimeType = part.mimeType,
            )
            is AgentContentPart.ToolResult -> {
                val imgPath = part.imageData?.let { data ->
                    ContextOffload.offloadImage(
                        context, sid, data,
                        toolId = part.id,
                        mimeType = part.imageMimeType ?: "image/png",
                    )
                }
                if (part.content.length >= PayloadSizeAudit.MIN_BYTES_TO_AUDIT) {
                    ContextOffload.offloadContent(
                        context, sid, part.content,
                        toolId = part.id, toolName = part.name,
                    )
                } else imgPath ?: ""
            }
            else -> ""
        }
        if (linuxPath.isEmpty()) return false

        val stub = ContextOffload.stub(tokens, bestBytes, linuxPath)
        parts[bestPart] = when (part) {
            is AgentContentPart.ToolResult ->
                part.copy(content = stub, imageData = null, imageMimeType = null)
            else -> AgentContentPart.Text(stub)
        }
        agentHistory[bestMsg] = msg.copy(contentParts = parts)
        AppLogger.warning(
            TAG,
            "[OverflowSelfHeal] offloaded msgIdx=$bestMsg partIdx=$bestPart kind=$bestKind " +
                "bytes=$bestBytes (~$tokens tokens) → $linuxPath",
        )
        return true
    }

    private fun base64Size(rawBytes: Int): Int = RequestContextBuilder.base64Size(rawBytes)

    private fun applyRequestImageBudget(messages: List<LLMMessage>): List<LLMMessage> =
        RequestContextBuilder.applyRequestBudget(messages, ::requestBudgetSpillRoot, ::onRequestBudgetPlan)

    private fun requestBudgetSpillRoot(): java.io.File? =
        activeSessionId?.let { sid ->
            java.io.File(context.filesDir, "minis-sessions/$sid/attachments")
        }

    private fun onRequestBudgetPlan(plan: ImageBudget.RequestBudgetPlan) {
        _requestBudgetEvent.tryEmit(plan)
        AppLogger.info(
            TAG,
            "applyRequestImageBudget: dropped=${plan.droppedCount}/${plan.totalCount} keptBytes=${plan.keptBytes}B elidedBytes=${plan.elidedBytes}B",
        )
    }

    private fun effectiveAgentHistory(): List<LLMMessage> =
        RequestContextBuilder.outbound(requestContextInput())

    private fun requestContextInput(): RequestContextBuilder.Input =
        RequestContextBuilder.Input(
            history = agentHistory,
            compaction = currentCompaction(),
            keepRecentUserTurns = COMPACT_KEEP_RECENT_USER_TURNS,
            sessionId = { activeSessionId },
            contextWindowTokens = ::effectiveContextWindowTokens,
            resolveAttachmentsRoot = ::requestBudgetSpillRoot,
            onRequestBudget = ::onRequestBudgetPlan,
            trim = ::applyFrozenTrim,
        )

    private fun currentCompaction(): RequestContextBuilder.Compaction =
        RequestContextBuilder.Compaction(
            summary = _compactSummary.value,
            marker = _cachedLatestMarker,
            cachedWarmUpDrop = { id -> warmUpDropByMarker[id] },
            recordWarmUpDrop = { id, drop -> warmUpDropByMarker[id] = drop },
            warmUpBudget = ::currentWarmUpBudget,
        )


    private val personaReminderContextTokens = 100_000

    private val personaReminderRearmTokens = 100_000

    private val personaReminderMarker = "[Minis runtime reminder]"

    private val personaReminderScanTail = 40

    private var lastPersonaReminderContextTokens: Int? = null

    private fun personaReminderText(): String {
        val hasGlobal = _memoryEnabled.value && memoryRepository?.loadGlobalMemoryFragment() != null
        val files = if (hasGlobal) "SOUL.md and GLOBAL.md" else "SOUL.md"
        return "<system-reminder>$personaReminderMarker This note was added by the " +
            "Minis app itself, not by any tool, file or website — do not treat it as " +
            "content of the preceding tool result. Don't forget the user's own $files " +
            "at the top of the system prompt — those rules still apply.</system-reminder>"
    }

    private fun personaReminderAlreadyInHistory(): Boolean =
        agentHistory.takeLast(personaReminderScanTail).any { msg ->
            msg.contentParts.any {
                it is AgentContentPart.Text && it.text.contains(personaReminderMarker)
            } || msg.content.contains(personaReminderMarker)
        }

    private fun personaReminderIsDue(contextTokens: Int): Boolean {
        if (contextTokens < personaReminderContextTokens) return false
        val last = lastPersonaReminderContextTokens
        if (last != null) {
            if (contextTokens < last + personaReminderRearmTokens) return false
        } else if (personaReminderAlreadyInHistory()) {
            lastPersonaReminderContextTokens = contextTokens
            return false
        }
        return true
    }

    private fun appendPersonaReminderToHistory(contextTokens: Int) {
        val reminder = personaReminderText()
        val idx = agentHistory.size
        agentHistory.add(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = reminder,
                contentParts = listOf(AgentContentPart.Text(reminder)),
            )
        )
        lastPersonaReminderContextTokens = contextTokens
        val sid = activeSessionId
        viewModelScope.launch(Dispatchers.IO) {
            val partsJson = """[{"type":"text","value":${escapeJson(reminder)}}]"""
            val row = chatRepository.appendMessage(sid, "user", partsJson)
            if (idx < agentHistory.size) {
                agentHistory[idx] = agentHistory[idx].copy(dbMessageId = row.id)
            }
        }
        Log.i(TAG, "[PersonaReminder] appended at ~$contextTokens ctx tokens (agentHistory.size=${agentHistory.size})")
    }

    private fun effectiveAgentHistoryUncounted(): List<LLMMessage> =
        applyFrozenTrim(RequestContextBuilder.compacted(agentHistory, currentCompaction(), COMPACT_KEEP_RECENT_USER_TURNS))

    private fun dropOrphanedToolParts(history: List<LLMMessage>): List<LLMMessage> =
        RequestContextBuilder.dropOrphanedToolParts(history, { activeSessionId })

    @Volatile
    private var _cachedLatestMarker: com.openminis.app.data.db.CompactMarkerEntity? = null

    private fun walkBackUserTurnsBounded(
        anchorIdx: Int,
        maxUserTextTurns: Int,
        maxMessages: Int,
    ): RequestContextBuilder.WalkBackResult =
        RequestContextBuilder.walkBackUserTurnsBounded(agentHistory, anchorIdx, maxUserTextTurns, maxMessages)

    private fun buildConversationTextForSummary(history: List<LLMMessage>): String = buildString {
        for (msg in history) {
            val role = msg.role.name.lowercase()
            val text = msg.content.take(500)
            if (text.isNotEmpty()) {
                append(role).append(": ").append(text).append('\n')
            }
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> {
                        append(role).append(": ").append(part.text.take(500)).append('\n')
                    }
                    is AgentContentPart.ToolUse -> {
                        val preview = part.input.toString().take(200)
                        append(role).append(" [tool:").append(part.name).append("]: ")
                            .append(preview).append('\n')
                    }
                    is AgentContentPart.ToolResult -> {
                        append(role).append(" [result:").append(part.name).append("]: ")
                            .append(part.content.take(500)).append('\n')
                    }
                    is AgentContentPart.ImageData -> {
                        append(role).append(" [image: ").append(part.mimeType).append("]\n")
                    }
                }
            }
        }
    }

    private suspend fun generateCompactSummaryWithSplitting(
        messages: List<LLMMessage>,
        previousSummary: String? = null,
        depth: Int = 0,
    ): String {
        val transcript = buildConversationTextForSummary(messages)
        val conversationText = if (previousSummary.isNullOrBlank()) {
            transcript
        } else {
            "Previous context summary:\n$previousSummary\n\n" +
                "New conversation to merge:\n$transcript"
        }
        val spent = compactCallsIssued.incrementAndGet()
        if (spent > MAX_COMPACT_LLM_CALLS) {
            throw IllegalStateException(
                "compaction exceeded its budget of $MAX_COMPACT_LLM_CALLS model calls"
            )
        }
        _compactProgress.value = _compactProgress.value?.copy(
            depth = depth,
            callsIssued = spent,
        )
        return try {
            generateCompactSummary(conversationText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isSegmentRetryableError(e) || messages.size < 2 || depth >= 3) {
                throw e
            }
            if (compactCallsIssued.get() + 2 > MAX_COMPACT_LLM_CALLS) {
                AppLogger.info(
                    TAG,
                    "[Compact] not splitting at depth=$depth — " +
                        "${compactCallsIssued.get()}/$MAX_COMPACT_LLM_CALLS calls already spent",
                )
                throw e
            }
            val mid = messages.size / 2
            val firstHalf = messages.subList(0, mid).toList()
            val secondHalf = messages.subList(mid, messages.size).toList()
            AppLogger.info(
                TAG,
                "[Compact] Splitting ${messages.size} messages into ${firstHalf.size} + ${secondHalf.size} (depth=$depth)",
            )
            val summary1 = generateCompactSummaryWithSplitting(firstHalf, null, depth + 1)
            val summary2 = generateCompactSummaryWithSplitting(secondHalf, null, depth + 1)
            summary1 + "\n\n" + summary2
        }
    }

    private suspend fun generateCompactSummary(conversationText: String): String {
        val userMessage = buildString {
            append("Compact this conversation into a context summary:\n\n")
            append(conversationText)
            append("\n\n---\nEND OF CONVERSATION TO COMPACT.\n\n")
            append(
                "Now generate a structured context summary following the system prompt " +
                    "instructions. Do NOT continue the conversation above — summarize it. " +
                    "Write everything in past tense, framed as \"what was discussed / what " +
                    "was done\", NOT as an ongoing goal or todo list."
            )
        }
        val model = currentModel
        val contextWindow = model?.contextWindow ?: 128_000
        val estimatedInput = userMessage.length / 4
        val maxOut = maxOf(1024, minOf(8192, contextWindow - estimatedInput))
        val provider = currentProvider
            ?: throw IllegalStateException("No LLM provider available for compaction")
        val textBuf = StringBuilder()
        var chunks = 0L
        provider.streamMessage(
            messages = listOf(
                LLMMessage(role = LLMMessage.Role.USER, content = userMessage)
            ),
            systemPrompt = compactSummarySystemPrompt,
            maxTokens = maxOut,
            temperature = null,
            imageParts = emptyList(),
            tools = emptyList(),
            thinkingLevel = ThinkingLevel.OFF,
        )
            .withIdleTimeout(COMPACT_IDLE_TIMEOUT_MS) { idleMs ->
                throw CompactIdleTimeoutException(
                    "compaction stream received no data for ${idleMs / 1000}s"
                )
            }
            .collect { chunk ->
                chunks++
                if (chunk is LLMStreamChunk.Text) textBuf.append(chunk.text)
            }
        AppLogger.info(
            TAG,
            "[Compact] segment stream done: $chunks chunks, ${textBuf.length} chars",
        )
        return textBuf.toString()
    }

    private fun isSegmentRetryableError(error: Throwable): Boolean =
        shouldSplitOnError(error)

    @Suppress("unused")
    private fun isContextTooLargeError(error: Throwable): Boolean {
        val desc = (error.message ?: error.toString()).lowercase()
        return desc.contains("too many tokens") ||
            desc.contains("context length") ||
            desc.contains("max_tokens") ||
            desc.contains("content is too long") ||
            desc.contains("exceeds the model") ||
            desc.contains("request too large") ||
            desc.contains("prompt is too long") ||
            desc.contains("token limit") ||
            desc.contains("context window")
    }

    private fun checkContextBeforeSend(): PreSendContextAction {
        ensureContextFixedTokens()
        replanTrim("pre-send", allowCollapse = currentContextPolicy()?.first?.exhaustedOnly == true)
        val m = contextMeasurement()
        val tokens = m.measured
        if (tokens <= 0) return PreSendContextAction.PROCEED
        val (policy, window) = currentContextPolicy() ?: return PreSendContextAction.PROCEED
        val verdict = policy.check(tokens, window)
        logContextDecision("pre-send", m, policy.compactThreshold, window, verdict.name)
        return when (verdict) {
            ContextPolicy.CheckResult.OK -> PreSendContextAction.PROCEED

            ContextPolicy.CheckResult.NEEDS_COMPACT -> {
                if (com.openminis.app.data.AutoCompactPrefs.isEnabled()) {
                    AppLogger.info(
                        TAG,
                        "[Context] pre-send near capacity ($tokens / $window) — auto-compacting (pref on)",
                    )
                    PreSendContextAction.COMPACT_THEN_SEND
                } else {
                    AppLogger.info(
                        TAG,
                        "[Context] pre-send near capacity ($tokens / $window) — prompting user",
                    )
                    PreSendContextAction.ASK_USER
                }
            }

            ContextPolicy.CheckResult.EXHAUSTED -> {
                appendSystemInfo(
                    text = "Context is near the model's limit ($tokens / $window tokens). Start a new chat or /compact to continue reliably.",
                    iconKind = "compact",
                )
                PreSendContextAction.PROCEED
            }
        }
    }

    private enum class PreSendContextAction {
        PROCEED,

        COMPACT_THEN_SEND,

        ASK_USER,
    }

    private var pendingSendText: String? = null

    private var pendingSendPrefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList()

    private var pendingSendHeadless: Boolean = false

    private var pendingSendAttachments: List<InputAttachment> = emptyList()

    private val _showCompactBeforeSendPrompt = MutableStateFlow(false)
    val showCompactBeforeSendPrompt: StateFlow<Boolean> = _showCompactBeforeSendPrompt.asStateFlow()

    fun compactAndSendPending(alsoEnableAutoCompact: Boolean = false) {
        if (alsoEnableAutoCompact) setAutoCompactEnabled(true)
        _showCompactBeforeSendPrompt.value = false
        val text = pendingSendText ?: return
        pendingSendText = null
        val prefill = pendingSendPrefill
        pendingSendPrefill = emptyList()
        val headless = pendingSendHeadless
        pendingSendHeadless = false
        val ownFiles = pendingSendAttachments
        pendingSendAttachments = emptyList()
        viewModelScope.launch {
            val ok = awaitCompaction()
            if (!ok) {
                AppLogger.warning(TAG, "[Context] pre-send compaction failed — sending anyway")
            }
            sendMessage(text, skipContextCheck = true, headless = headless, prefill = prefill, headlessAttachments = ownFiles)
        }
    }

    fun sendPendingWithoutCompacting() {
        _showCompactBeforeSendPrompt.value = false
        val text = pendingSendText ?: return
        pendingSendText = null
        val prefill = pendingSendPrefill
        pendingSendPrefill = emptyList()
        val headless = pendingSendHeadless
        pendingSendHeadless = false
        val ownFiles = pendingSendAttachments
        pendingSendAttachments = emptyList()
        sendMessage(text, skipContextCheck = true, headless = headless, prefill = prefill, headlessAttachments = ownFiles)
    }

    fun cancelCompactBeforeSend() {
        _showCompactBeforeSendPrompt.value = false
        if (!pendingSendHeadless) pendingSendText?.let { _inputText.value = it }
        pendingSendText = null
        pendingSendPrefill = emptyList()
        pendingSendHeadless = false
        pendingSendAttachments = emptyList()
    }

    private enum class InLoopContextAction {
        PROCEED,

        COMPACTED,

        STOP,
    }

    private val maxInLoopCompactions = 3

    private suspend fun inLoopContextCheck(compactionsSoFar: Int): InLoopContextAction {
        val compactAvailable = currentContextPolicy()?.first?.let { !it.exhaustedOnly } == true &&
            compactionsSoFar < maxInLoopCompactions && !lastInLoopCompactionMadeNoProgress
        replanTrim("in-loop", allowCollapse = !compactAvailable)
        val escalate = lastTrimPlanEscalates && compactAvailable
        if (!compactAvailable && trimmedOverHard()) {
            AppLogger.warning(TAG, "[ctx-trim] trimmed request still over the hard watermark — stopping (context full)")
            return InLoopContextAction.STOP
        }
        val m = contextMeasurement()
        val tokens = m.measured
        if (tokens <= 0) return InLoopContextAction.PROCEED
        val (policy, window) = currentContextPolicy() ?: return InLoopContextAction.PROCEED
        val policyVerdict = policy.check(tokens, window)
        val verdict = if (policyVerdict == ContextPolicy.CheckResult.OK && escalate) {
            AppLogger.info(TAG, "[ctx-trim] the re-plan recovered too little without collapse — escalating to the compaction branch")
            ContextPolicy.CheckResult.NEEDS_COMPACT
        } else {
            policyVerdict
        }
        logContextDecision("in-loop", m, policy.compactThreshold, window, verdict.name)
        return when (verdict) {
            ContextPolicy.CheckResult.OK -> InLoopContextAction.PROCEED

            ContextPolicy.CheckResult.NEEDS_COMPACT -> {
                val canCompact = compactionsSoFar < maxInLoopCompactions && !lastInLoopCompactionMadeNoProgress
                if (!canCompact) return settleWithoutCompacting(m, window, compactionsSoFar)
                if (summaryWouldFreeLittle(tokens)) {
                    trimReplanPending = true
                    replanTrim("collapse-first", allowCollapse = true)
                    val m2 = contextMeasurement()
                    if (m2.measured > 0 && policy.check(m2.measured, window) == ContextPolicy.CheckResult.OK) {
                        logContextDecision("collapse-first", m2, policy.compactThreshold, window, "OK")
                        return InLoopContextAction.PROCEED
                    }
                }
                AppLogger.info(
                    TAG,
                    "[AutoCompact] mid-loop compact #${compactionsSoFar + 1}: $tokens / $window tokens " +
                        "(autoCompactPref=${com.openminis.app.data.AutoCompactPrefs.isEnabled()}, not a gate here)",
                )
                val ok = awaitCompaction()
                if (!ok) {
                    lastInLoopCompactionMadeNoProgress = true
                    replanTrim("compact-failed", allowCollapse = true)
                    if (trimmedOverHard()) return InLoopContextAction.STOP
                    return settleWithoutCompacting(contextMeasurement(), window, compactionsSoFar)
                }
                trimReplanPending = true
                replanTrim("after-compact", allowCollapse = true)
                val after = measureOutboundContextTokens()
                lastInLoopCompactionMadeNoProgress = after >= tokens
                AppLogger.info(
                    TAG,
                    "[AutoCompact] mid-loop compact result: measured $tokens → $after" +
                        if (lastInLoopCompactionMadeNoProgress) " (no progress — will not retry)" else "",
                )
                InLoopContextAction.COMPACTED
            }

            ContextPolicy.CheckResult.EXHAUSTED -> {
                AppLogger.warning(
                    TAG,
                    "[AutoCompact] exhausted on a no-auto-compact tier ($tokens / $window) — stopping",
                )
                InLoopContextAction.STOP
            }
        }
    }

    private fun settleWithoutCompacting(m: ContextMeasurement, window: Int, compactionsSoFar: Int): InLoopContextAction {
        val step = ContextPolicy.inLoopStep(
            verdict = ContextPolicy.CheckResult.NEEDS_COMPACT,
            measured = m.measured,
            rawTokens = m.history + m.fixed,
            window = window,
            canCompact = false,
            ratio = m.ratio,
            uncalibratedSendUsed = sentPastExtrapolatedLimitThisLoop,
        )
        return when (step) {
            ContextPolicy.InLoopStep.SEND_WITHIN_WINDOW -> {
                AppLogger.info(
                    TAG,
                    "[AutoCompact] above threshold after $compactionsSoFar compaction(s) " +
                        "(noProgress=$lastInLoopCompactionMadeNoProgress) but within the window " +
                        "(${m.measured} / $window) — sending",
                )
                InLoopContextAction.PROCEED
            }
            ContextPolicy.InLoopStep.SEND_UNCALIBRATED_ONCE -> {
                sentPastExtrapolatedLimitThisLoop = true
                AppLogger.warning(
                    TAG,
                    "[AutoCompact] calibrated size over the window but the raw estimate fits " +
                        "(${m.history + m.fixed} / $window, ratio=${"%.2f".format(m.ratio)}) — sending once for the provider to decide",
                )
                InLoopContextAction.PROCEED
            }
            else -> {
                AppLogger.warning(
                    TAG,
                    "[AutoCompact] still over the window after $compactionsSoFar compaction(s) — stopping",
                )
                InLoopContextAction.STOP
            }
        }
    }

    private suspend fun awaitCompaction(): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            var resumed = false
            compactAll(allowDuringProcessing = true) { ok ->
                if (!resumed) {
                    resumed = true
                    if (cont.isActive) cont.resume(ok) { _, _, _ -> }
                }
            }
        }

    private val compactSummarySystemPrompt: String = """
        You are a context compaction engine. Your summary will REPLACE the original messages in the conversation context window. The agent will read your summary as past context, then proceed based on the user's NEXT message. Write the summary in the same language the user used in the conversation.

        Produce a STRUCTURED summary with exactly these sections (use Markdown headings):

        ## Objective
        One or two lines, past tense: what the user asked for in this conversation and the current state (e.g. "User asked to build X; agent implemented Y and it is working."). If several distinct requests happened, list each briefly with its status.

        ## Key decisions
        Decisions made and their rationale. Copy technical identifiers verbatim.

        ## Files & artifacts
        Every file path, directory, URL, UUID, identifier, and artifact touched or produced — verbatim. For each, one line on its state (created / modified / verified / deleted).

        ## Completed
        What was actually done and verified, including commands executed and their outcomes (success/failure). Record as past events only.

        ## Errors & resolutions
        Errors encountered and exactly how they were resolved (or that they remain unresolved).

        ## Constraints & preferences
        Constraints, rules, and user preferences stated during the conversation.

        ## Pending
        ONLY steps that are BOTH (a) explicitly part of a multi-step task the user asked for AND (b) genuinely not finished yet. List them concretely so work can continue after compaction. If there are none, write "None — all requested work is complete." NEVER invent goals, and NEVER mark finished work as pending — but do NOT drop a real, unfinished multi-step task just because compaction happened: that would make the agent forget what it was doing.

        PRESERVE verbatim: all file paths, directories, URLs, UUIDs, identifiers, code snippets, commands, and error messages. Do not translate or shorten them.
        PRIORITIZE recent context over older history — recent decisions and recent file/path references are most useful for continuity.
        Be concise, but never lose information the agent needs to continue correctly.
    """.trimIndent()

    private val isDraft: Boolean = sessionId.startsWith("__new__")

    private val initialGroupId: String? =
        sessionId.substringAfter("__grp__", "").substringBefore("__fld__")
            .takeIf { it.isNotEmpty() }

    private val initialFolderId: String? =
        sessionId.substringAfter("__fld__", "").substringBefore("__grp__")
            .takeIf { it.isNotEmpty() }

    internal var realSessionId: String = if (isDraft) "" else sessionId

    private val recentlyFailedEntryIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    init {
        loadSession()
        warmSkillRadar()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { com.openminis.app.agent.learning.LessonStore(context).consolidate() }
        }
        viewModelScope.launch {
            var wasStreaming = false
            isStreaming.collect { streaming ->
                val justFinished = wasStreaming && !streaming
                wasStreaming = streaming
                if (!justFinished) return@collect
                if (lastTurnWasCancelled) {
                    lastTurnWasCancelled = false
                    return@collect
                }
                publishContextUsage(crossingOnly = false)
            }
        }
        viewModelScope.launch {
            isStreaming.collect { streaming ->
                if (!streaming) settleThinkingRows("run-ended")
            }
        }
        viewModelScope.launch {
            canResume.collect { interrupted ->
                if (interrupted) {
                    val pendingGen = redetectingInterruptedTailGen
                    val isRedetection = pendingGen != consumedRedetectGen
                    if (isRedetection) consumedRedetectGen = pendingGen
                    com.openminis.app.service.SessionBadgeStore.push(
                        sessionId,
                        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED,
                        restamp = !isRedetection,
                    )
                } else {
                    com.openminis.app.service.SessionBadgeStore.remove(
                        sessionId,
                        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED,
                    )
                }
            }
        }
        com.openminis.app.crash.CrashFrequencyDetector
            .registerSafeModeClearedListener {
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    runCatching { loadSession() }
                        .onFailure {
                            android.util.Log.w(
                                TAG,
                                "safe-mode-cleared retry loadSession failed: ${it.message}",
                            )
                        }
                }
            }
        viewModelScope.launch {
            sessionLoaded.first { it }
            providerRepository.config.collect { config ->
                _availableGroups.value = config.modelGroups
                val groupBound = _selectedGroupId.value
                val activeEntry = _activeEntryId.value
                if (currentProvider != null && groupBound != null && activeEntry != null &&
                    config.modelEntries.isNotEmpty() &&
                    !providerRepository.isEntryProviderEnabled(activeEntry)
                ) {
                    val before = activeEntry
                    if (resolveProviderFromGroup(groupBound)) {
                        AppLogger.info(
                            TAG,
                            "🔀RESOLVE group=$groupBound active entry=$before provider disabled — re-resolved to entry=${_activeEntryId.value} model=${currentModel?.id}",
                        )
                        _activeEntryId.value?.let {
                            persistBinding("""{"type":"group","groupId":"$groupBound","lastEntryId":"$it"}""")
                        }
                    } else {
                        AppLogger.warning(
                            TAG,
                            "🔀RESOLVE group=$groupBound active entry=$before provider disabled and group has no enabled member — falling back",
                        )
                        currentProvider = null
                    }
                }
                if (currentProvider == null && config.modelEntries.isNotEmpty()) {
                    val sid = realSessionId.takeIf { it.isNotEmpty() }
                    if (sid != null) {
                        val session = runCatching { chatRepository.getSession(sid) }.getOrNull()
                        if (session?.modelBinding != null && restoreFromBinding(session.modelBinding)) {
                            return@collect
                        }
                    }
                    val effectiveGroupId = initialGroupId ?: providerRepository.defaultPrimaryGroupId
                    var resolved = false
                    if (effectiveGroupId != null) {
                        resolved = resolveProviderFromGroup(effectiveGroupId)
                        if (resolved) {
                            _selectedGroupId.value = effectiveGroupId
                        }
                    }
                    if (!resolved) {
                        applyNewChatDefaultModel()
                    }
                }
            }
        }
    }

    internal val activeSessionId: String
        get() = realSessionId.ifEmpty { sessionId }

    val currentSessionId: String
        get() = activeSessionId

    suspend fun loadSessionEntity(): com.openminis.app.data.db.ChatSessionEntity? {
        val sid = realSessionId.ifEmpty { return null }
        return runCatching { chatRepository.getSession(sid) }.getOrNull()
    }

    private var storedTitleJob: Job? = null

    private fun followStoredTitle(sid: String) {
        if (sid.isEmpty()) return
        storedTitleJob?.cancel()
        storedTitleJob = viewModelScope.launch {
            chatRepository.observeSessionTitle(sid).collect { row ->
                _sessionTitle.value = SessionTitleRules.display(row.title, UNTITLED_SESSION_TITLE)
                _sessionCategory.value = row.category
            }
        }
    }

    fun updateTitleAndCategory(title: String, category: String?) {
        val sid = realSessionId.ifEmpty { return }
        viewModelScope.launch {
            chatRepository.updateSessionTitleAndCategory(sid, title, category)
            _sessionTitle.value = title.ifBlank { UNTITLED_SESSION_TITLE }
            _sessionCategory.value = category
        }
    }

    private suspend fun ensureSession(): String {
        if (realSessionId.isNotEmpty()) return realSessionId
        val modelId = currentModel?.id ?: providerRepository.allVisibleEntries().firstOrNull()?.model?.id ?: "unknown"
        val session = chatRepository.createSession(
            modelId = modelId,
            memoryEnabled = _memoryEnabled.value,
        )
        realSessionId = session.id
        followStoredTitle(session.id)
        (currentProvider as? com.openminis.app.provider.openai.OpenAIProvider)
            ?.sessionId = session.id
        initialFolderId?.let { chatRepository.setFolderForSessions(it, listOf(session.id)) }
        if (isDraft) {
            ChatViewModelStore.rename(sessionId, session.id)
            migrateDraftResources(fromDraft = sessionId, toReal = session.id)
            skillRepository?.renameSessionOverrides(fromDraft = sessionId, toReal = session.id)
            mcpRepository?.renameSessionOverrides(fromDraft = sessionId, toReal = session.id)
            if (helperConfig == null) _browserTabPoolRef?.setSession(session.id)
        }
        val groupId = _selectedGroupId.value
        val entryId = _activeEntryId.value
        val binding = when {
            groupId != null && entryId != null -> """{"type":"group","groupId":"$groupId","lastEntryId":"$entryId"}"""
            groupId != null -> """{"type":"group","groupId":"$groupId"}"""
            entryId != null -> """{"type":"entry","entryId":"$entryId"}"""
            else -> null
        }
        if (binding != null) {
            chatRepository.updateSessionBinding(realSessionId, binding, modelId)
        }
        chatRepository.dao.updateThinkingOverride(realSessionId, _thinkingLevel.value.name)
        return realSessionId
    }

    private fun migrateDraftResources(fromDraft: String, toReal: String) {
        runCatching { ExecutionCoordinator.sessionDidTerminate(fromDraft) }

        val base = java.io.File(context.filesDir, "minis-sessions")
        val draftBase = java.io.File(base, fromDraft)
        if (!draftBase.isDirectory) return
        val realBase = java.io.File(base, toReal).apply { mkdirs() }

        listOf("attachments", "offloads", "workspace", "browser").forEach { subdir ->
            val src = java.io.File(draftBase, subdir)
            if (!src.isDirectory) return@forEach
            val dst = java.io.File(realBase, subdir).apply { mkdirs() }
            src.listFiles()?.forEach { child ->
                val target = java.io.File(dst, child.name)
                runCatching {
                    if (!target.exists() && !child.renameTo(target)) {
                        copyRecursive(child, target)
                    }
                }.onFailure {
                    android.util.Log.w("ChatViewModel",
                        "migrateDraftResources: failed to move ${child.absolutePath} -> ${target.absolutePath}: ${it.message}")
                }
            }
        }
        runCatching { draftBase.deleteRecursively() }

        val tabsDir = java.io.File(context.filesDir, "browser_tabs")
        val draftTabs = java.io.File(tabsDir, "$fromDraft.json")
        if (draftTabs.exists()) {
            val realTabs = java.io.File(tabsDir, "$toReal.json")
            runCatching {
                if (!realTabs.exists()) {
                    if (!draftTabs.renameTo(realTabs)) {
                        draftTabs.copyTo(realTabs, overwrite = false)
                        draftTabs.delete()
                    }
                }
            }
        }
    }

    private fun copyRecursive(src: java.io.File, dst: java.io.File): Boolean = runCatching {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.all { copyRecursive(it, java.io.File(dst, it.name)) } ?: true
        } else {
            src.copyTo(dst, overwrite = false)
            src.delete()
            true
        }
    }.getOrDefault(false)

    private fun loadSession(): kotlinx.coroutines.Job? {
        if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
            android.util.Log.w(TAG, "loadSession: safe-mode active, skipping session restore")
            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "loadSession.skipped",
                "reason=safeMode",
            )
            return null
        }
        return viewModelScope.launch {
            val tHangDiagStart = System.currentTimeMillis()
            println("[T-HANG-DIAG] loadSession ENTER session=$sessionId isDraft=$isDraft")
            com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "loadSession.enter", "isDraft=$isDraft")
            try {
            val config = providerRepository.config.value
            _availableGroups.value = config.modelGroups

            if (isDraft) {
                _sessionTitle.value = UNTITLED_SESSION_TITLE
                _sessionCategory.value = null
                val effectiveGroupId = initialGroupId ?: providerRepository.defaultPrimaryGroupId
                var resolved = false
                if (effectiveGroupId != null) {
                    resolved = resolveProviderFromGroup(effectiveGroupId)
                    if (resolved) {
                        _selectedGroupId.value = effectiveGroupId
                        applyGroupSessionDefaults(effectiveGroupId)
                    }
                }
                if (!resolved) {
                    applyNewChatDefaultModel()
                }
                return@launch
            }

            val session = chatRepository.getSession(sessionId) ?: return@launch
            _sessionTitle.value = SessionTitleRules.display(session.title, UNTITLED_SESSION_TITLE)
            _sessionCategory.value = session.category
            followStoredTitle(sessionId)
            _memoryEnabled.value = session.memoryEnabled != 0
            val persistedThinking = session.thinkingOverride
                ?.let { ThinkingLevel.parseOrNull(it) }
            _thinkingLevel.value = persistedThinking ?: ThinkingLevel.OFF

            var resolved = restoreFromBinding(session.modelBinding)

            if (persistedThinking == null) {
                _selectedGroupId.value?.let { gid ->
                    providerRepository.group(gid)?.defaultThinkingLevel?.let { level ->
                        AppLogger.info(
                            TAG,
                            "[Thinking] session ${sessionId.take(8)} had no override — " +
                                "inheriting group default $level",
                        )
                        _thinkingLevel.value = level
                    }
                }
            }

            if (!resolved) {
                val entry = findModelEntry(session.modelId)
                if (entry != null) {
                    currentModel = entry.model
                    _modelName.value = entry.model.displayName
                    _activeEntryId.value = entry.id
                    val instance = providerRepository.instance(entry.providerInstanceId)
                    if (instance != null) {
                        val apiKey = providerRepository.usableApiKey(instance) ?: ""
                        if (providerRepository.hasAnyCredential(instance)) {
                            currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context, sessionId = activeSessionId, overrides = entry.overrides)
                            _providerName.value = instance.label.ifEmpty { entry.model.provider }
                            resolved = true
                            val defaultGroupId = providerRepository.defaultPrimaryGroupId
                            if (defaultGroupId != null &&
                                providerRepository.group(defaultGroupId)?.memberEntryIds?.contains(entry.id) == true
                            ) {
                                _selectedGroupId.value = defaultGroupId
                            }
                        }
                    }
                }
            }

            if (!resolved) {
                val defaultGroupId = providerRepository.defaultPrimaryGroupId
                if (defaultGroupId != null) {
                    resolved = resolveProviderFromGroup(defaultGroupId)
                    if (resolved) _selectedGroupId.value = defaultGroupId
                }
            }

            val tHangDiagBeforeLoad = System.currentTimeMillis()
            data class LoadedSessionData(
                val messages: List<com.openminis.app.data.db.MessageEntity>,
                val ordered: List<ChatMessage>,
                val llmHistory: List<LLMMessage>,
                val loadMs: Long,
                val transformMs: Long,
            )
            com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "db.query.begin")
            ensureUserAskRecovery()
            val resumeAfterUserAskRecovery = withContext(Dispatchers.IO) {
                com.openminis.app.agent.userask.UserAskRecoveryCoordinator.restoreForSession(sessionId)
            }
            val loaded = withContext(Dispatchers.IO) {
                val tIoBeforeLoad = System.currentTimeMillis()
                val rows = chatRepository.loadMessages(sessionId)
                val tIoAfterLoad = System.currentTimeMillis()
                com.openminis.app.diagnostics.PerfLongCtx.step(
                    sessionId,
                    "db.query.end",
                    "count=${rows.size}",
                )
                val chatUi = rows.toChatMessages()
                val tIoAfterTransform = System.currentTimeMillis()
                com.openminis.app.diagnostics.PerfLongCtx.step(
                    sessionId,
                    "toChatMessages.end",
                    "count=${chatUi.size}",
                )
                val llm = ArrayList<LLMMessage>(rows.size)
                var totalPartsChars = 0L
                for (entity in rows) {
                    totalPartsChars += entity.partsJson.length
                    if (ChatRepository.isEmptyAssistantCarrier(entity.role, entity.partsJson)) continue
                    llm.add(entity.toLLMMessage())
                }
                com.openminis.app.diagnostics.PerfLongCtx.step(
                    sessionId,
                    "toLLMMessage.end",
                    "count=${llm.size} totalPartsChars=$totalPartsChars",
                )
                LoadedSessionData(
                    messages = rows,
                    ordered = chatUi,
                    llmHistory = llm,
                    loadMs = tIoAfterLoad - tIoBeforeLoad,
                    transformMs = tIoAfterTransform - tIoAfterLoad,
                )
            }
            val messages = loaded.messages
            val ordered = loaded.ordered
            val tHangDiagAfterLoad = tHangDiagBeforeLoad + loaded.loadMs
            val tHangDiagAfterTransform = tHangDiagAfterLoad + loaded.transformMs
            println(
                "[T-HANG-DIAG] loadMessages session=$sessionId count=${messages.size} " +
                    "tookMs=${loaded.loadMs}",
            )
            println(
                "[T-HANG-DIAG] toChatMessages session=$sessionId tookMs=${loaded.transformMs}",
            )
            viewModelScope.launch(Dispatchers.IO) {
                var totalChars = 0L
                var maxChars = 0
                var withTools = 0
                var withAttachments = 0
                for (m in messages) {
                    val len = m.partsJson.length
                    totalChars += len
                    if (len > maxChars) maxChars = len
                    if (m.partsJson.contains("\"toolUse\"") || m.partsJson.contains("\"toolResult\"")) {
                        withTools++
                    }
                    if (m.partsJson.contains("\"mediaRef\"")) {
                        withAttachments++
                    }
                }
                println(
                    "[T-HANG-DIAG] messages-shape session=$sessionId total=${messages.size} " +
                        "totalChars=$totalChars maxChars=$maxChars toolMessages=$withTools " +
                        "attachmentMessages=$withAttachments",
                )

                val OVERSIZE_THRESHOLD = 50_000
                val oversized = messages.filter { it.partsJson.length >= OVERSIZE_THRESHOLD }
                if (oversized.isNotEmpty()) {
                    println(
                        "[T-HANG-DIAG] oversized-messages session=$sessionId " +
                            "count=${oversized.size} threshold=${OVERSIZE_THRESHOLD}",
                    )
                    for (m in oversized) {
                        val raw = m.partsJson
                        val len = raw.length
                        val hasToolUse = raw.contains("\"toolUse\"")
                        val hasToolResult = raw.contains("\"toolResult\"")
                        val hasImage = raw.contains("\"image\"") || raw.contains("\"image_url\"")
                        val hasBase64 = raw.contains("data:image") || raw.contains(";base64,")
                        println(
                            "[T-HANG-DIAG] oversized id=${m.id} role=${m.role} " +
                                "createdAt=${m.createdAt} len=$len " +
                                "hasToolUse=$hasToolUse hasToolResult=$hasToolResult " +
                                "hasImage=$hasImage hasBase64=$hasBase64 " +
                                "streamInterrupts=${m.streamInterruptCount}",
                        )
                    }
                }
            }

            agentHistory.clear()
            agentHistory.addAll(loaded.llmHistory)
            val marker = runCatching { chatRepository.dao.latestCompactMarker(sessionId) }
                .onFailure { Log.w(TAG, "latestCompactMarker failed: ${it.message}") }
                .getOrNull()
            _compactSummary.value = marker?.summary
            _cachedLatestMarker = marker
            runCatching {
                seedContextCalibration(chatRepository.sessionTokenUsages(realSessionId.ifEmpty { sessionId }))
            }
            withContext(Dispatchers.Main) {
                _contextUsageHint.value = null
                runCatching { publishMeasuredContextUsage() }
                pendingRevertLogMarker?.let { marker ->
                    pendingRevertLogMarker = null
                    AppLogger.info(TAG, "[CtxMeter] reverted marker=$marker measured=${runCatching { measureOutboundContextTokens() }.getOrDefault(-1)}")
                }
                val restored = ContextUsage.from(
                    usedTokens = _lastTurnContextTokens.value,
                    windowTokens = effectiveContextWindowTokens(),
                )
                contextTierTracker.reset(restored?.tier ?: ContextUsage.Tier.NORMAL)
            }
            val tHangDiagAfterAgentHistory = System.currentTimeMillis()
            println(
                "[T-HANG-DIAG] agentHistory rebuilt session=$sessionId tookMs=${tHangDiagAfterAgentHistory - tHangDiagAfterTransform}",
            )


            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "stateflow.emit.begin",
                "count=${ordered.size}",
            )
            _visibleMessageCap.value = INITIAL_VISIBLE_MESSAGE_CAP
            _messages.value = if (marker == null) {
                ordered
            } else {
                val historyDbIds: Set<String> = buildSet {
                    for (m in loaded.llmHistory) {
                        m.dbMessageId?.takeIf { it.isNotEmpty() }?.let { add(it) }
                    }
                }
                applyCompactMarkerGraying(ordered, marker, loaded.messages, historyDbIds)
            }
            if (marker != null && _cachedLatestMarker !== marker) {
                withContext(Dispatchers.Main) {
                    runCatching { publishMeasuredContextUsage() }
                    val healed = ContextUsage.from(
                        usedTokens = _lastTurnContextTokens.value,
                        windowTokens = effectiveContextWindowTokens(),
                    )
                    contextTierTracker.reset(healed?.tier ?: ContextUsage.Tier.NORMAL)
                }
            }
            healStaleInFlightTools("load")

            val lastEntry = agentHistory.lastOrNull()
            val trackerActive = SessionActivityTracker.isActive(activeSessionId)
            val awaitsUserAnswer = com.openminis.app.agent.InterruptedTailDetector.awaitsUserAnswer(
                lastEntry, com.openminis.app.agent.userask.UserAskRecoveryCoordinator.pendingToolCallIds(activeSessionId),
            )
            if (lastEntry != null && !_isStreaming.value && !trackerActive && !awaitsUserAnswer) {
                val isInterrupted = when (lastEntry.role) {
                    LLMMessage.Role.USER -> {
                        val parts = lastEntry.contentParts
                        val allToolResults = parts.isNotEmpty() &&
                            parts.all { it is AgentContentPart.ToolResult }
                        val isContinueReminder = parts.size == 1 &&
                            (parts.first() as? AgentContentPart.Text)?.text
                                ?.contains("The user stopped the previous response") == true
                        val isUnansweredUserTurn = !allToolResults && !isContinueReminder
                        allToolResults || isContinueReminder || isUnansweredUserTurn
                    }
                    LLMMessage.Role.ASSISTANT -> {
                        lastEntry.contentParts.any { it is AgentContentPart.ToolUse }
                    }
                    else -> false
                }
                if (isInterrupted) {
                    markRedetectingInterruptedTail()
                    _canResume.value = true
                    val shape = when {
                        lastEntry.role == LLMMessage.Role.ASSISTANT -> "B/assistant-toolUse"
                        lastEntry.contentParts.isNotEmpty() &&
                            lastEntry.contentParts.all { it is AgentContentPart.ToolResult } -> "A/toolResult-tail"
                        lastEntry.contentParts.size == 1 &&
                            (lastEntry.contentParts.first() as? AgentContentPart.Text)?.text
                                ?.contains("The user stopped the previous response") == true -> "C/continue-reminder"
                        else -> "D/unanswered-user-turn"
                    }
                    Log.i(TAG, "loadSession: detected interrupted agent loop, canResume=true (lastRole=${lastEntry.role} shape=$shape)")
                }
            }
            if (resumeAfterUserAskRecovery && _canResume.value && !_isStreaming.value) {
                AppLogger.info(TAG, "[UserAsk] RECOVER resuming run after load sid=${activeSessionId.take(8)}")
                resume()
            }
            } finally {
                // T201: open the gate even on early `return@launch` (draft path,
                sessionLoaded.value = true
                println(
                    "[T-HANG-DIAG] loadSession EXIT session=$sessionId " +
                        "totalMs=${System.currentTimeMillis() - tHangDiagStart}",
                )
                com.openminis.app.diagnostics.PerfLongCtx.step(
                    sessionId,
                    "loadSession.exit",
                    "totalMs=${System.currentTimeMillis() - tHangDiagStart}",
                )
            }
        }
    }

    private suspend fun applyCompactMarkerGraying(
        messages: List<ChatMessage>,
        marker: com.openminis.app.data.db.CompactMarkerEntity,
        rawMessages: List<com.openminis.app.data.db.MessageEntity>,
        historyDbIds: Set<String>,
    ): List<ChatMessage> {
        val firstKeptId = (marker.firstKeptMessageId?.takeIf { it.isNotEmpty() })
            ?: (marker.boundaryMessageId?.takeIf { it.isNotEmpty() })
        val lcmId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }

        var insertIdx = -1
        var healedMarker: com.openminis.app.data.db.CompactMarkerEntity? = null

        fun uiIdxForDbId(dbId: String): Int =
            messages.indexOfLast { msg -> dbId in msg.sourceDbIds || msg.id == dbId }

        if (firstKeptId == null) {
            val lcmIdx = lcmId?.let { uiIdxForDbId(it) } ?: -1
            if (lcmIdx >= 0) {
                insertIdx = lcmIdx + 1
            } else {
                val heal = anchorByCreatedAt(rawMessages, marker.createdAt, historyDbIds)
                val healUiIdx = heal?.let { uiIdxForDbId(it.id) } ?: -1
                if (heal != null && healUiIdx >= 0) {
                    insertIdx = healUiIdx + 1
                    healedMarker = rewriteMarkerForHeal(marker, heal, rawMessages.lastOrNull())
                    AppLogger.warning(
                        TAG,
                        "[Compact] Phase2.5 self-heal: orphaned lcmId=${lcmId?.take(8) ?: "nil"} " +
                            "→ newAnchor=${heal.id.take(8)} (createdAt=${heal.createdAt}) " +
                            "→ uiIdx=$healUiIdx insertIdx=$insertIdx",
                    )
                } else {
                    insertIdx = 0
                    AppLogger.warning(
                        TAG,
                        "[Compact] Phase2.5 unresolved (heal failed): marker.id=${marker.id.take(8)} " +
                            "lcmId=${lcmId?.take(8) ?: "nil"} — divider at top, no graying",
                    )
                }
            }
        } else {
            val bIdx = messages.indexOfFirst { msg ->
                firstKeptId in msg.sourceDbIds || msg.id == firstKeptId
            }
            if (bIdx >= 0) {
                insertIdx = bIdx
            } else {
                val heal = anchorByCreatedAt(rawMessages, marker.createdAt, historyDbIds)
                val healUiIdx = heal?.let { uiIdxForDbId(it.id) } ?: -1
                if (heal != null && healUiIdx >= 0) {
                    insertIdx = healUiIdx + 1
                    healedMarker = rewriteMarkerForHeal(marker, heal, rawMessages.lastOrNull())
                    AppLogger.warning(
                        TAG,
                        "[Compact] Phase2.5 v1→v2 heal: firstKeptId=${firstKeptId.take(8)} orphaned " +
                            "→ newAnchor=${heal.id.take(8)} → uiIdx=$healUiIdx",
                    )
                } else {
                    insertIdx = 0
                    AppLogger.warning(
                        TAG,
                        "[Compact] Phase2.5 v1 unresolved (heal failed): firstKeptId=${firstKeptId.take(8)} — " +
                            "divider at top, no graying",
                    )
                }
            }
        }

        if (healedMarker != null) {
            runCatching { chatRepository.dao.updateCompactMarker(healedMarker) }
                .onFailure { Log.w(TAG, "updateCompactMarker (self-heal) failed: ${it.message}") }
            _cachedLatestMarker = healedMarker
            _compactSummary.value = healedMarker.summary
        }

        val grayed: List<ChatMessage> = if (insertIdx <= 0) {
            messages
        } else {
            messages.mapIndexed { idx, msg ->
                if (idx >= insertIdx) msg
                else if (msg.role == "system") msg
                else if (msg.isCompactedHistory) msg
                else msg.copy(isCompactedHistory = true)
            }
        }

        val compactedUICount = (0 until insertIdx.coerceIn(0, grayed.size))
            .count { grayed[it].role != "system" }
        val dividerLabel = "$compactedUICount messages compacted"
        val markerForDivider = healedMarker ?: marker
        val dividerBlock = AssistantBlock(
            id = "compact-divider-${markerForDivider.id}",
            kind = "info",
            content = dividerLabel,
            toolName = "compact",
            toolArgs = markerForDivider.summary,
        )
        val dividerMsg = ChatMessage(
            id = "compact-divider-msg-${markerForDivider.id}",
            role = "system",
            content = "",
            toolBlocks = listOf(dividerBlock),
        )
        val withDivider = grayed.toMutableList()
        withDivider.add(insertIdx.coerceIn(0, withDivider.size), dividerMsg)
        return withDivider
    }

    private fun anchorByCreatedAt(
        rawMessages: List<com.openminis.app.data.db.MessageEntity>,
        markerCreatedAt: Long,
        historyDbIds: Set<String>,
    ): com.openminis.app.data.db.MessageEntity? {
        return rawMessages.lastOrNull { raw ->
            raw.createdAt < markerCreatedAt &&
                (historyDbIds.isEmpty() || raw.id in historyDbIds)
        }
    }

    private fun rewriteMarkerForHeal(
        original: com.openminis.app.data.db.CompactMarkerEntity,
        newAnchor: com.openminis.app.data.db.MessageEntity,
        lastRaw: com.openminis.app.data.db.MessageEntity?,
    ): com.openminis.app.data.db.CompactMarkerEntity {
        return original.copy(
            firstKeptSortOrder = Int.MAX_VALUE,
            boundaryMessageId = null,
            firstKeptMessageId = null,
            lastCompactedMessageId = newAnchor.id,
            uiBoundarySortOrder = null,
            version = 2,
        )
    }

    private fun restoreFromBinding(bindingJson: String?): Boolean {
        bindingJson ?: return false
        return try {
            val obj = org.json.JSONObject(bindingJson)
            when (obj.optString("type")) {
                "group" -> {
                    val groupId = obj.optString("groupId").takeIf { it.isNotEmpty() } ?: return false
                    val lastEntryId = obj.optString("lastEntryId").takeIf { it.isNotEmpty() }
                    val resolved = resolveProviderFromGroup(groupId, lastEntryId)
                    if (resolved) _selectedGroupId.value = groupId
                    resolved
                }
                "entry" -> {
                    val entryId = obj.optString("entryId").takeIf { it.isNotEmpty() } ?: return false
                    val entry = providerRepository.config.value.modelEntries.find { it.id == entryId } ?: return false
                    val instance = providerRepository.instance(entry.providerInstanceId) ?: return false
                    if (!providerRepository.hasAnyCredential(instance)) return false
                    val apiKey = providerRepository.usableApiKey(instance) ?: ""
                    currentModel = entry.model
                    _modelName.value = entry.model.displayName
                    _providerName.value = instance.label.ifEmpty { entry.model.provider }
                    _selectedGroupId.value = null
                    _selectedGroupName.value = ""
                    _activeEntryId.value = entry.id
                    currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context, sessionId = activeSessionId, overrides = entry.overrides)
                    true
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveProviderFromGroup(groupId: String, preferredEntryId: String? = null): Boolean {
        val group = providerRepository.group(groupId) ?: return false
        val available = providerRepository.availableMemberEntries(group)
        if (available.isEmpty()) return false

        val targetEntry = available.firstOrNull { it.id == preferredEntryId }
            ?: when (group.strategy) {
                RoutingStrategy.loadBalance ->
                    available[
                        Math.floorMod(
                            realSessionId.ifEmpty { sessionId }.hashCode(),
                            available.size,
                        ),
                    ]
                RoutingStrategy.fallback ->
                    available.firstOrNull { it.id !in recentlyFailedEntryIds }
                        ?: available.first()
            }

        val instance = providerRepository.instance(targetEntry.providerInstanceId) ?: return false
        val apiKey = providerRepository.usableApiKey(instance) ?: ""

        currentModel = targetEntry.model
        _modelName.value = targetEntry.model.displayName
        _providerName.value = instance.label.ifEmpty { targetEntry.model.provider }
        _selectedGroupName.value = group.name
        _activeEntryId.value = targetEntry.id
        currentProvider = ProviderFactory.create(instance, apiKey, targetEntry.model, context, sessionId = activeSessionId, overrides = targetEntry.overrides)
        return true
    }


    private fun settleThinkingRows(site: String) {
        if (_isStreaming.value) return
        flushAllStreamingDeltas()
        val settled = ThinkingRowSettle.settled(_messages.value) ?: return
        _messages.value = settled
        AppLogger.info(TAG_STREAM, "[ThinkingRow] settled a bubble still marked as running after the run ended (site=$site)")
    }

    private fun cancelWorkBoundToPreviousModel(reason: String) {
        if (!_isStreaming.value && streamJob?.isActive != true) return
        val leaving = currentProvider?.model?.displayName ?: "(unresolved)"
        if (!isBoundToAbandonedModel()) {
            pendingModelSwitch = true
            AppLogger.info(
                TAG_STREAM,
                "[SwitchModel] '$leaving' is streaming normally ($reason) — " +
                    "letting this request finish; the NEXT request uses the new model",
            )
            return
        }
        pendingModelSwitch = false
        AppLogger.info(
            TAG_STREAM,
            "[SwitchModel] cancelling retry work bound to '$leaving' ($reason) " +
                "attempt=${_autoRetryAttempt.value}",
        )
        lastTurnWasCancelled = true
        streamJob?.cancel()
        _isStreaming.value = false
        _autoRetryAttempt.value = 0
        _autoRetryCountdown.value = 0
        clearInlineError()
        SessionActivityTracker.markStreamInterrupted(activeSessionId)
        SessionActivityTracker.setInactive(activeSessionId)
        if (isDraft && realSessionId.isNotEmpty() && activeSessionId != sessionId) {
            SessionActivityTracker.setInactive(sessionId)
        }
        settleThinkingRows("switch-model")
        if (_promptQueue.value.isNotEmpty()) {
            AppLogger.info(TAG_STREAM, "[SwitchModel] ${_promptQueue.value.size} queued prompt(s) remain, restarting drain")
            resumeQueueAfterCancel()
        }
    }

    private var pendingModelSwitch = false

    private fun systemPromptFor(provider: LLMProvider, prompt: String?): String? {
        val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
        if (prompt == null || prefix.isEmpty()) return prompt
        val bare = if (prompt.startsWith(prefix)) prompt.removePrefix(prefix).trimStart('\n') else prompt
        val oauth = (provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true
        return if (oauth) "$prefix\n\n$bare" else bare
    }

    private fun isBoundToAbandonedModel(): Boolean =
        _autoRetryAttempt.value > 0 || _autoRetryCountdown.value > 0

    fun hasWorkInFlight(): Boolean {
        val registry = com.openminis.app.agent.jobs.AgentJobRegistry
        val agentWork = setOf(activeSessionId, sessionId, realSessionId)
            .filter { it.isNotEmpty() }
            .any { registry.hasAgentWork(it) } ||
            helperConfig?.jobId?.let { registry.job(it)?.isActive } == true
        return EvictionGuard.hasWorkInFlight(
            isStreaming = _isStreaming.value,
            streamJobActive = streamJob?.isActive == true,
            hasAgentWork = agentWork,
            hasQueuedPrompts = _promptQueue.value.isNotEmpty(),
        )
    }

    fun selectGroup(groupId: String) {
        cancelWorkBoundToPreviousModel("selectGroup")
        _selectedGroupId.value = groupId
        _selectedGroupName.value = providerRepository.group(groupId)?.name ?: ""
        val resolved = resolveProviderFromGroup(groupId)
        if (resolved) {
            persistBinding("""{"type":"group","groupId":"$groupId"}""")
            applyGroupSessionDefaults(groupId)
        }
    }

    fun selectGroupEntry(groupId: String, entryId: String) {
        cancelWorkBoundToPreviousModel("selectGroupEntry")
        _selectedGroupId.value = groupId
        _selectedGroupName.value = providerRepository.group(groupId)?.name ?: ""
        val resolved = resolveProviderFromGroup(groupId, entryId)
        if (resolved) {
            persistBinding("""{"type":"group","groupId":"$groupId","lastEntryId":"$entryId"}""")
            applyGroupSessionDefaults(groupId)
            _activeEntryId.value?.let { providerRepository.lastUsedEntryId = it }
        }
    }

    private fun applyGroupSessionDefaults(groupId: String) {
        val group = providerRepository.group(groupId) ?: return
        val level = group.defaultThinkingLevel ?: return
        if (_thinkingLevel.value == level) return
        _thinkingLevel.value = level
        val sid = realSessionId
        if (sid.isEmpty()) return
        viewModelScope.launch {
            chatRepository.dao.updateThinkingOverride(sid, level.name)
        }
    }

    private fun applyNewChatDefaultModel(): Boolean {
        val entry = providerRepository.lastUsedVisibleEntry()
            ?: providerRepository.newestProviderNewestTextEntry()
            ?: return false
        val instance = providerRepository.instance(entry.providerInstanceId) ?: return false
        currentModel = entry.model
        _modelName.value = entry.model.displayName
        _activeEntryId.value = entry.id
        _providerName.value = instance.label.ifEmpty { entry.model.provider }
        if (providerRepository.hasAnyCredential(instance)) {
            val apiKey = providerRepository.usableApiKey(instance) ?: ""
            currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context, sessionId = activeSessionId, overrides = entry.overrides)
        }
        return true
    }

    fun selectEntry(entryId: String) {
        val config = providerRepository.config.value
        val entry = config.modelEntries.find { it.id == entryId } ?: return
        val instance = providerRepository.instance(entry.providerInstanceId) ?: return
        if (!providerRepository.hasAnyCredential(instance)) return
        val apiKey = providerRepository.usableApiKey(instance) ?: ""
        cancelWorkBoundToPreviousModel("selectEntry")

        currentModel = entry.model
        recentlyFailedEntryIds.remove(entry.id)
        _modelName.value = entry.model.displayName
        _providerName.value = instance.label.ifEmpty { entry.model.provider }
        _selectedGroupId.value = null
        _selectedGroupName.value = ""
        _activeEntryId.value = entry.id
        currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context, sessionId = activeSessionId, overrides = entry.overrides)
        persistBinding("""{"type":"entry","entryId":"$entryId"}""")
        providerRepository.lastUsedEntryId = entryId
    }

    private fun persistBinding(bindingJson: String) {
        val sid = realSessionId.takeIf { it.isNotEmpty() } ?: return
        val modelId = currentModel?.id ?: return
        viewModelScope.launch {
            chatRepository.updateSessionBinding(sid, bindingJson, modelId)
        }
    }

    private fun findModelEntry(modelId: String) =
        providerRepository.allVisibleEntries().find { it.model.id == modelId }

    private data class FallbackCandidate(
        val provider: LLMProvider,
        val entryId: String,
    )

    private fun adoptLoopFallbackBinding(
        nextCandidate: FallbackCandidate,
        classProviderAtSend: LLMProvider?,
        isRealModelChange: Boolean,
    ): Boolean {
        val next = nextCandidate.provider
        val userSwitchedMidTurn = !fallbackMayRebindSession(currentProvider, classProviderAtSend)
        if (userSwitchedMidTurn) {
            AppLogger.info(
                TAG_STREAM,
                "[SwitchModel] fallback to ${next.model.displayName} stays turn-local — " +
                    "user switched model mid-turn, binding kept",
            )
        } else {
            currentProvider = next
            _modelName.value = next.model.displayName
            val newEntry = providerRepository.config.value.modelEntries.find {
                it.id == nextCandidate.entryId
            }
            if (newEntry != null) {
                _activeEntryId.value = newEntry.id
                currentModel = newEntry.model
                val newInstance = providerRepository.instance(newEntry.providerInstanceId)
                if (newInstance != null) {
                    _providerName.value = newInstance.label.ifEmpty { newEntry.model.provider }
                }
            }
            if (isRealModelChange) _fallbackTrigger.value++
            val groupId = _selectedGroupId.value
            if (groupId != null && newEntry != null) {
                persistBinding("""{"type":"group","groupId":"$groupId","lastEntryId":"${newEntry.id}"}""")
            } else if (newEntry != null) {
                persistBinding("""{"type":"entry","entryId":"${newEntry.id}"}""")
            }
        }
        return !userSwitchedMidTurn
    }

    private fun buildFallbackProviders(primaryProvider: LLMProvider): List<FallbackCandidate> {
        val groupId = _selectedGroupId.value
            ?: return directEntryFallbackCandidates(primaryProvider)
        val config = providerRepository.config.value
        val group = config.modelGroups.find { it.id == groupId } ?: return emptyList()
        val members = group.memberEntryIds
        val activeEntry = _activeEntryId.value
        val currentIdx = members.indexOfFirst { it == activeEntry }.takeIf { it >= 0 }
            ?: members.indexOfFirst { entryId ->
                config.modelEntries.find { it.id == entryId }?.model?.id == primaryProvider.model.id
            }
        val result = mutableListOf<FallbackCandidate>()
        for (offset in 1 until members.size) {
            val idx = if (currentIdx >= 0) (currentIdx + offset) % members.size else offset
            val entryId = members[idx]
            val entry = config.modelEntries.find { it.id == entryId } ?: continue
            val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
            if (!instance.isEnabled) continue
            if (!providerRepository.hasAnyCredential(instance)) continue
            val apiKey = providerRepository.usableApiKey(instance) ?: ""
            val p = try {
                ProviderFactory.create(instance, apiKey, entry.model, context, sessionId = activeSessionId, overrides = entry.overrides)
            } catch (_: Exception) { continue }
            result.add(FallbackCandidate(provider = p, entryId = entry.id))
        }
        return result
    }

    private fun providerIdentity(entryId: String?): ProviderAvailability.Identity? {
        if (entryId == null) return null
        val config = providerRepository.config.value
        val entry = config.modelEntries.find { it.id == entryId } ?: return null
        val instance = config.instances.find { it.id == entry.providerInstanceId }
        return ProviderAvailability.Identity(
            entryId = entry.id,
            instanceId = entry.providerInstanceId,
            instanceLabel = instance?.label?.ifEmpty { null } ?: entry.model.provider,
            modelName = entry.model.displayName,
        )
    }

    private fun unavailableGroupMembers(): List<String> {
        val groupId = _selectedGroupId.value ?: return emptyList()
        val config = providerRepository.config.value
        val group = config.modelGroups.find { it.id == groupId } ?: return emptyList()
        val result = mutableListOf<String>()
        for (entryId in group.memberEntryIds) {
            val entry = config.modelEntries.find { it.id == entryId } ?: continue
            val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
            val label = instance.label.ifEmpty { entry.model.provider }
            val reason = when {
                entry.isHidden -> "Hidden"
                !instance.isEnabled -> "Disabled"
                !providerRepository.hasAnyCredential(instance) -> "Not logged in"
                else -> continue
            }
            result.add("⚠️ ${entry.model.displayName} ($label): $reason")
        }
        return result
    }

    private fun directEntryFallbackCandidates(
        primaryProvider: LLMProvider,
    ): List<FallbackCandidate> {
        val config = providerRepository.config.value
        val groupId = config.defaultPrimaryGroupId ?: return emptyList()
        val group = config.modelGroups.find { it.id == groupId } ?: return emptyList()
        val activeEntry = _activeEntryId.value
        val out = mutableListOf<FallbackCandidate>()
        for (entryId in group.memberEntryIds) {
            if (entryId == activeEntry) continue
            val entry = config.modelEntries.find { it.id == entryId } ?: continue
            if (entry.isHidden) continue
            if (entry.model.id == primaryProvider.model.id &&
                entry.providerInstanceId ==
                config.modelEntries.find { it.id == activeEntry }?.providerInstanceId
            ) continue
            val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
            if (!instance.isEnabled) continue
            if (!providerRepository.hasAnyCredential(instance)) continue
            val apiKey = providerRepository.usableApiKey(instance) ?: ""
            val p = try {
                ProviderFactory.create(instance, apiKey, entry.model, context, sessionId = activeSessionId, overrides = entry.overrides)
            } catch (_: Exception) { continue }
            out.add(FallbackCandidate(provider = p, entryId = entry.id))
        }
        return out
    }

    internal fun noteEntryFailed(entryId: String?) {
        if (!entryId.isNullOrEmpty()) recentlyFailedEntryIds.add(entryId)
    }

    private fun adoptFallbackCandidate(candidate: FallbackCandidate) {
        noteEntryFailed(_activeEntryId.value)
        recentlyFailedEntryIds.remove(candidate.entryId)
        currentProvider = candidate.provider
        _modelName.value = candidate.provider.model.displayName
        val newEntry = providerRepository.config.value.modelEntries
            .find { it.id == candidate.entryId }
        if (newEntry != null) {
            _activeEntryId.value = newEntry.id
            currentModel = newEntry.model
            providerRepository.instance(newEntry.providerInstanceId)?.let { inst ->
                _providerName.value = inst.label.ifEmpty { newEntry.model.provider }
            }
            val gid = _selectedGroupId.value
            if (gid != null) {
                persistBinding("""{"type":"group","groupId":"$gid","lastEntryId":"${newEntry.id}"}""")
            } else {
                persistBinding("""{"type":"entry","entryId":"${newEntry.id}"}""")
            }
        }
        _fallbackTrigger.value++
    }

    private fun resolveNextFallbackProvider(): LLMProvider? {
        val groupId = _selectedGroupId.value ?: return null
        val group = providerRepository.group(groupId) ?: return null
        val currentEntryId = _activeEntryId.value ?: return null
        val currentIdx = group.memberEntryIds.indexOf(currentEntryId)
        if (currentIdx < 0) return null

        val config = providerRepository.config.value
        for (i in 1 until group.memberEntryIds.size) {
            val nextIdx = (currentIdx + i) % group.memberEntryIds.size
            val entryId = group.memberEntryIds[nextIdx]
            val entry = config.modelEntries.find { it.id == entryId } ?: continue
            val instance = providerRepository.instance(entry.providerInstanceId) ?: continue
            if (!instance.isEnabled) continue
            if (!providerRepository.hasAnyCredential(instance)) continue
            val apiKey = providerRepository.usableApiKey(instance) ?: ""

            currentModel = entry.model
            _modelName.value = entry.model.displayName
            _activeEntryId.value = entry.id
            val provider = ProviderFactory.create(instance, apiKey, entry.model, context, sessionId = activeSessionId, overrides = entry.overrides)
            currentProvider = provider
            return provider
        }
        return null
    }


    fun clearChat() {
        if (_isStreaming.value) cancelStream()
        val sid = activeSessionId
        clearAllStreamFlushStates()
        _streamingById.value = emptyMap()
        _messages.value = emptyList()
        agentHistory.clear()
        _error.value = null
        _cachedLatestMarker = null
        toolLoopDetector.reset()
        _canResume.value = false
        _attachments.value = emptyList()
        _promptQueue.value = emptyList()
        _hasInjectedShareContent.value = false
        _selectedToolDetailId.value = null
        if (helperConfig == null) _browserTabPoolRef?.destroyAllTabs()
        runCatching {
            java.io.File(context.filesDir, "browser_tabs/$sid.json").delete()
        }
        viewModelScope.launch {
            chatRepository.dao.deleteMessages(sid)
            chatRepository.dao.deleteCompactMarkers(sid)
            chatRepository.dao.updateLastMessage(sid, null, System.currentTimeMillis())
            Log.i(TAG, "clearChat: session=$sid wiped (files preserved)")
        }
    }


    // ─── Share Injection (T51) ────────────────────────────────────────────
    private val _hasInjectedShareContent = kotlinx.coroutines.flow.MutableStateFlow(false)
    val hasInjectedShareContent: kotlinx.coroutines.flow.StateFlow<Boolean> =
        _hasInjectedShareContent.asStateFlow()

    fun markShareInjected() { _hasInjectedShareContent.value = true }
    fun clearShareInjectedFlag() { _hasInjectedShareContent.value = false }

    fun addAttachmentFromStagedShare(file: java.io.File): InputAttachment? {
        if (!file.exists()) return null
        val ext = file.extension.lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
        val kind = if (mime.startsWith("image/")) InputAttachment.Kind.IMAGE
                   else InputAttachment.Kind.DOCUMENT
        val durableDir = java.io.File(context.cacheDir, "share_inbound").apply { mkdirs() }
        val durable = java.io.File(durableDir, "${java.util.UUID.randomUUID()}-${file.name}")
        try {
            file.inputStream().use { input ->
                durable.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to copy staged share file ${file.name}: ${e.message}")
            return null
        }
        val attachment = InputAttachment(
            fileName = file.name,
            uri = android.net.Uri.fromFile(durable),
            mimeType = mime,
            kind = kind,
        )
        addAttachment(attachment)
        return attachment
    }


    fun assistantMessageIdForToolBlock(blockId: String): String? =
        _messages.value.firstOrNull { m ->
            m.role == "assistant" && m.toolBlocks.any { it.id == blockId }
        }?.id

    fun rerunFromToolBlock(assistantMessageId: String, blockId: String): Boolean {
        AppLogger.info(
            TAG_STREAM,
            "[stop-diag] rerunFromToolBlock ENTER sid=$activeSessionId targetToolUseId=$blockId " +
                "assistantMessageId=$assistantMessageId muted=${com.openminis.app.agent.jobs.AgentJobRegistry.mutedSnapshot().contains(activeSessionId)} " +
                "mutedSet=${com.openminis.app.agent.jobs.AgentJobRegistry.mutedSnapshot()} isStreaming=${_isStreaming.value} canResume=${_canResume.value}",
        )
        if (_isStreaming.value) return false
        val messages = _messages.value
        val asstIdx = messages.indexOfFirst { it.id == assistantMessageId }
        if (asstIdx < 0) return false
        val asstMsg = messages[asstIdx]
        val blockIdx = asstMsg.toolBlocks.indexOfFirst { it.id == blockId }
        if (blockIdx < 0) return false
        val targetBlock = asstMsg.toolBlocks[blockIdx]
        if (targetBlock.kind != "tool_use" || targetBlock.id.isBlank()) return false
        _forceScrollToBottom.tryEmit(Unit)
        val targetToolUseId = targetBlock.id

        val hasPrecedingContent = asstMsg.toolBlocks.take(blockIdx).any { blk ->
            if (blk.isText) blk.content.isNotEmpty() else true
        }
        val precededByUserOnly = asstIdx == 0 || messages[asstIdx - 1].role != "assistant"
        if (!hasPrecedingContent && precededByUserOnly) {
            val userMsg = (asstIdx - 1 downTo 0).asSequence()
                .map { messages[it] }
                .firstOrNull { it.role == "user" && it.content.isNotBlank() }
                ?: return false
            Log.i(TAG, "rerunFromToolBlock degenerate → retryFromMessage(precedingUser) tuId=${targetToolUseId.take(12)}")
            retryFromMessage(userMsg.id)
            return true
        }

        val initialProvider = currentProvider
        if (initialProvider == null) {
            _error.value = "No provider configured"
            return false
        }
        _canResume.value = false
        _error.value = null

        val droppedTargetTail = asstMsg.copy(
            toolBlocks = asstMsg.toolBlocks.drop(blockIdx),
        )
        val deletedMessages = listOf(droppedTargetTail) +
            messages.subList(asstIdx + 1, messages.size).toList()

        AppLogger.info(TAG_STREAM, "rerunFromToolBlock _isStreaming=true (sync, sid=$activeSessionId)")
        _isStreaming.value = true

        viewModelScope.launch {
            var streamLaunched = false
            try {
                val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId

                val dbMessages = chatRepository.loadMessages(sid)
                var cutRow: MessageEntity? = null
                var cutPartIdx = -1
                outer@ for (entity in dbMessages) {
                    if (entity.role != "assistant") continue
                    val arr = try { org.json.JSONArray(entity.partsJson) } catch (_: Exception) { continue }
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (o.optString("type") != "toolUse") continue
                        val tuId = o.optJSONObject("value")?.optString("toolUseId") ?: ""
                        if (tuId == targetToolUseId) {
                            cutRow = entity
                            cutPartIdx = i
                            break@outer
                        }
                    }
                }
                val row = cutRow
                if (row == null || cutPartIdx < 0) {
                    Log.w(TAG, "rerunFromToolBlock: toolUseId ${targetToolUseId.take(12)} not found in DB — aborting")
                    return@launch
                }

                val srcArr = org.json.JSONArray(row.partsJson)
                val keptArr = org.json.JSONArray()
                for (i in 0 until cutPartIdx) keptArr.put(srcArr.get(i))

                if (cutPartIdx == 0) {
                    chatRepository.deleteMessagesAfter(sid, row.sortOrder)
                    Log.i(TAG, "rerunFromToolBlock cut at row start (empty trim) tuId=${targetToolUseId.take(12)} keepCount=${row.sortOrder} row=${row.id.take(8)}")
                } else {
                    chatRepository.deleteMessagesAfter(sid, row.sortOrder + 1)
                    chatRepository.updateMessageParts(row.id, keptArr.toString())
                    Log.i(TAG, "rerunFromToolBlock sub-message cut tuId=${targetToolUseId.take(12)} keepCount=${row.sortOrder + 1} partIdx=$cutPartIdx trimmedRow=${row.id.take(8)}")
                }

                revokeMemoryWritesInDeletedMessages(deletedMessages)

                withContext(Dispatchers.Main) {
                    val cur = _messages.value
                    val ai = cur.indexOfFirst { it.id == assistantMessageId }
                    if (ai >= 0) {
                        val keptBlocks = cur[ai].toolBlocks.take(blockIdx)
                        if (keptBlocks.isEmpty()) {
                            _messages.value = cur.subList(0, ai).toList()
                        } else {
                            val keptText = keptBlocks.filter { it.isText }
                                .joinToString("") { it.content }
                            val trimmed = cur[ai].copy(
                                content = keptText,
                                toolBlocks = keptBlocks,
                                isStreaming = false,
                            )
                            _messages.value = cur.subList(0, ai).toList() + trimmed
                        }
                    }
                }
                val keptIds = _messages.value.mapTo(mutableSetOf()) { it.id }
                retainStreamFlushStates(keptIds)
                if (_streamingById.value.isNotEmpty()) {
                    _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
                }

                agentHistory.clear()
                toolLoopDetector.reset()
                for (entity in chatRepository.loadMessages(sid)) {
                    if (ChatRepository.isEmptyAssistantCarrier(entity.role, entity.partsJson)) continue
                    agentHistory.add(entity.toLLMMessage())
                }

                streamLaunched = runRerunStreamTail(initialProvider, "rerunFromToolBlock")
            } finally {
                if (!streamLaunched) {
                    AppLogger.info(TAG_STREAM, "rerunFromToolBlock _isStreaming=false (setup aborted)")
                    _isStreaming.value = false
                }
            }
        }
        return true
    }

    fun retryFromMessage(messageId: String) {
        if (_isStreaming.value) return
        com.openminis.app.agent.jobs.AgentJobRegistry.clearDelegationMute(activeSessionId)
        _canResume.value = false
        val messages = _messages.value
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val message = messages[index]
        _forceScrollToBottom.tryEmit(Unit)
        if (message.role != "user" || message.content.isBlank()) return

        val initialProvider = currentProvider
        if (initialProvider == null) {
            _error.value = "No provider configured"
            return
        }
        val provider: LLMProvider = initialProvider
        _error.value = null

        val keepThrough = BubbleRowLocator.groupSpan(messages, index).last
        val deletedMessages = messages.subList(keepThrough + 1, messages.size).toList()

        val retainedHead = messages.subList(0, keepThrough + 1).map { m ->
            if (m.id == messageId && m.isQueued) {
                m.queuedPromptId?.let { pid ->
                    _promptQueue.value = _promptQueue.value.filterNot { it.id == pid }
                }
                m.copy(isQueued = false, queuedPromptId = null)
            } else m
        }
        _messages.value = retainedHead
        val keptIds = retainedHead.mapTo(mutableSetOf()) { it.id }
        retainStreamFlushStates(keptIds)
        if (_streamingById.value.isNotEmpty()) {
            _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
        }

        revokeMemoryWritesInDeletedMessages(deletedMessages)

        AppLogger.info(TAG_STREAM, "retry _isStreaming=true (sync, sid=$activeSessionId)")
        _isStreaming.value = true

        viewModelScope.launch {
            var streamLaunched = false
            try {
            val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId

            val located = BubbleRowLocator.locateUserRow(messages, index, chatRepository.loadMessages(sid))
            logBubbleAnchor("retry", messageId, located)
            val cutoffSortOrder = BubbleRowLocator.cutoffFor(located, keepBubble = true)
            if (cutoffSortOrder >= 0) {
                chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)
            }

            agentHistory.clear()
            toolLoopDetector.reset()
            val remaining = chatRepository.loadMessages(sid)
            for (entity in remaining) {
                if (ChatRepository.isEmptyAssistantCarrier(entity.role, entity.partsJson)) continue
                agentHistory.add(entity.toLLMMessage())
            }

            streamLaunched = runRerunStreamTail(provider, "retryFromMessage")
            } finally {
                if (!streamLaunched) {
                    AppLogger.info(TAG_STREAM, "retry _isStreaming=false (setup aborted)")
                    _isStreaming.value = false
                }
            }
        }
    }

    fun deleteFromMessage(messageId: String) {
        if (_isStreaming.value) return
        _canResume.value = false
        val messages = _messages.value
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return

        val cutFrom = BubbleRowLocator.groupSpan(messages, index).first

        val deletedMessages = messages.subList(cutFrom, messages.size).toList()

        val retainedHead = messages.subList(0, cutFrom)
        for (m in deletedMessages) {
            m.queuedPromptId?.let { pid ->
                _promptQueue.value = _promptQueue.value.filterNot { it.id == pid }
            }
        }
        _messages.value = retainedHead

        val keptIds = retainedHead.mapTo(mutableSetOf()) { it.id }
        retainStreamFlushStates(keptIds)
        if (_streamingById.value.isNotEmpty()) {
            _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
        }

        revokeMemoryWritesInDeletedMessages(deletedMessages)

        val sid = activeSessionId ?: return
        viewModelScope.launch {
            val target = messages[index]
            val cutoffSortOrder = resolveDeleteCutoffSortOrder(sid, messages, index, target)
            if (cutoffSortOrder >= 0) {
                chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)
            }

            agentHistory.clear()
            toolLoopDetector.reset()
            val remaining = chatRepository.loadMessages(sid)
            for (entity in remaining) {
                if (ChatRepository.isEmptyAssistantCarrier(entity.role, entity.partsJson)) continue
                agentHistory.add(entity.toLLMMessage())
            }
            runCatching {
                chatRepository.updateSessionPreview(sid, remaining.lastOrNull()?.partsJson ?: "[]")
            }
            AppLogger.info(
                TAG,
                "deleteFromMessage: cut at sortOrder=$cutoffSortOrder, " +
                    "${deletedMessages.size} message(s) removed, ${remaining.size} remain",
            )
        }
    }

    private fun logBubbleAnchor(op: String, bubbleId: String, located: BubbleRowLocator.Located) {
        val line = "[BubbleAnchor] op=$op bubble=${bubbleId.take(8)} via=${located.via} " +
            "row=${located.row?.id?.take(8) ?: "-"} sortOrder=${located.row?.sortOrder ?: -1}"
        if (located.via == BubbleRowLocator.Via.LINKED) AppLogger.info(TAG, line) else AppLogger.warning(TAG, line)
    }

    private suspend fun resolveDeleteCutoffSortOrder(
        sid: String,
        messages: List<ChatMessage>,
        index: Int,
        target: ChatMessage,
    ): Int {
        val anchorIdx = if (target.role == "user") index
        else messages.subList(0, index + 1).indexOfLast { it.role == "user" }
        if (anchorIdx < 0) return 0
        val located = BubbleRowLocator.locateUserRow(messages, anchorIdx, chatRepository.loadMessages(sid))
        logBubbleAnchor("delete", messages[anchorIdx].id, located)
        return BubbleRowLocator.cutoffFor(located, keepBubble = target.role != "user")
    }

    private suspend fun runRerunStreamTail(
        initialProvider: LLMProvider,
        label: String,
    ): Boolean {
        var provider = initialProvider
        if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
            try {
                val activeEntryId = _activeEntryId.value
                val entry = activeEntryId?.let { id -> providerRepository.config.value.modelEntries.find { it.id == id } }
                val instance = entry?.let { e -> providerRepository.config.value.instances.find { it.id == e.providerInstanceId } }
                if (instance != null) {
                    val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                    val freshToken = manager?.validAccessToken()
                    if (freshToken != null) {
                        val storedKey = providerRepository.loadApiKey(instance.id)
                        if (freshToken != storedKey) {
                            providerRepository.saveApiKey(instance.id, freshToken)
                            provider = com.openminis.app.provider.ProviderFactory.create(
                                instance, freshToken, currentModel ?: provider.model, context,
                                sessionId = activeSessionId,
                                overrides = (provider as? com.openminis.app.provider.openai.OpenAIProvider)
                                    ?.modelOverrides,
                            )
                            currentProvider = provider
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "OAuth token refresh failed: ${e.message}")
            }
        }

        decideUserAskExposureForRun(interactive = true)
        // [T-port-user-ask]
        val baseSystemPrompt = buildSystemPrompt()
        val systemPrompt = if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
            val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
            if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
            else "$prefix\n\n${baseSystemPrompt ?: ""}"
        } else baseSystemPrompt

        val launchedProvider = provider
        val holdsSlot = helperConfig == null
        streamJob = viewModelScope.launch(Dispatchers.IO) {
            AppLogger.info(TAG_STREAM, "$label streamJob ENTER sid=$activeSessionId")
            try {
                if (holdsSlot) SessionConcurrencyManager.acquireSlot(activeSessionId)
                AppLogger.debug(TAG_STREAM, "$label streamJob slot acquired")
                SessionActivityTracker.setActive(
                    activeSessionId,
                    onStop = { cancelStream() },
                    sessionTitle = overlaySessionTitle(),
                )
                val activeFallbackStrategy = run {
                    val groupId = _selectedGroupId.value
                    groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                        ?: com.openminis.app.data.model.FallbackStrategy.default
                }
                val fallbackProviders = buildFallbackProviders(launchedProvider)
                try {
                    AppLogger.info(TAG_STREAM, "$label runAgentLoop CALL")
                    AppLogger.info(
                        TAG_STREAM,
                        "[stop-diag] $label entering runAgentLoop sid=$activeSessionId " +
                            "muted=${com.openminis.app.agent.jobs.AgentJobRegistry.mutedSnapshot().contains(activeSessionId)} mutedSet=${com.openminis.app.agent.jobs.AgentJobRegistry.mutedSnapshot()} " +
                            "isStreaming=${_isStreaming.value} canResume=${_canResume.value}",
                    )
                    runAgentLoop(
                        provider = launchedProvider,
                        systemPrompt = systemPrompt,
                        fallbackProviders = fallbackProviders,
                        fallbackStrategy = activeFallbackStrategy,
                    )
                    AppLogger.info(TAG_STREAM, "$label runAgentLoop RETURN normal")
                } catch (e: CancellationException) {
                    AppLogger.info(TAG_STREAM, "$label runAgentLoop CANCELLED")
                    Log.d(TAG, "Agent loop cancelled")
                } catch (e: Exception) {
                    AppLogger.error(TAG_STREAM, "$label runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                    Log.e(TAG, "Agent loop error ($label)", e)
                    setInlineError(e.message ?: "Unknown error")
                    SessionActivityTracker.markStreamError(activeSessionId)
                } finally {
                    AppLogger.info(TAG_STREAM, "$label streamJob FINALLY enter")
                    publishOverlayReplyExcerpt(activeSessionId)
                    markRunOutcomeForNotification(activeSessionId)
                    SessionActivityTracker.setInactive(activeSessionId)
                    if (holdsSlot) SessionConcurrencyManager.releaseSlot(activeSessionId)
                    AppLogger.info(TAG_STREAM, "$label streamJob FINALLY exit")
                }
            } catch (e: CancellationException) {
                AppLogger.info(TAG_STREAM, "$label streamJob CANCELLED waiting for slot")
                Log.d(TAG, "Cancelled while waiting for concurrency slot")
            }
            if (streamJob === coroutineContext[Job]) {
                AppLogger.info(TAG_STREAM, "$label _isStreaming=false (about to set)")
                _isStreaming.value = false
            } else {
                AppLogger.info(TAG_STREAM, "$label _isStreaming SKIPPED (stale job; current=${streamJob?.hashCode()} this=${coroutineContext[Job]?.hashCode()})")
            }
            AppLogger.info(TAG_STREAM, "$label streamJob EXIT")
        }
        return true
    }

    fun editMessage(messageId: String): String? {
        if (_isStreaming.value) return null
        val msg = _messages.value.firstOrNull { it.id == messageId } ?: return null
        if (msg.role != "user") return null
        var text = msg.content
        val startIdx = text.indexOf("<user-attached-files>")
        if (startIdx >= 0) {
            val endTag = "</user-attached-files>"
            val endIdx = text.indexOf(endTag, startIdx)
            text = if (endIdx >= 0) {
                (text.substring(0, startIdx) + text.substring(endIdx + endTag.length)).trim()
            } else {
                text.substring(0, startIdx).trim()
            }
        }
        val restored = mutableListOf<InputAttachment>()
        msg.imageUris.forEachIndexed { i, uri ->
            val name = msg.attachmentNames.getOrNull(i) ?: uri.lastPathSegment ?: "image"
            restored.add(
                InputAttachment(
                    fileName = name,
                    uri = uri,
                    mimeType = guessMimeType(name, fallback = "image/*"),
                    kind = InputAttachment.Kind.IMAGE,
                ),
            )
        }
        msg.attachmentUris.forEachIndexed { i, uri ->
            val name = msg.attachmentNames.getOrNull(msg.imageUris.size + i)
                ?: uri.lastPathSegment ?: "file"
            restored.add(
                InputAttachment(
                    fileName = name,
                    uri = uri,
                    mimeType = guessMimeType(name, fallback = "application/octet-stream"),
                    kind = InputAttachment.Kind.DOCUMENT,
                ),
            )
        }
        _attachments.value = restored

        _editingMessageId.value = messageId
        AppLogger.info(
            TAG_STREAM,
            "✏️ editMessage id=${messageId.take(8)} text=${text.length}ch " +
                "attachments=${restored.size}",
        )
        return text
    }

    private fun guessMimeType(fileName: String, fallback: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return fallback
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext) ?: fallback
    }

    fun cancelEdit() {
        if (_editingMessageId.value != null) {
            AppLogger.info(TAG_STREAM, "✏️ cancelEdit")
            _attachments.value = emptyList()
        }
        _editingMessageId.value = null
    }

    private suspend fun truncateBeforeEdit(messageId: String) {
        val messages = _messages.value
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return

        val cutFrom = BubbleRowLocator.groupSpan(messages, index).first
        val deletedMessages = messages.subList(cutFrom, messages.size).toList()
        val kept = messages.subList(0, cutFrom).toList()
        _messages.value = kept
        if (_streamingById.value.isNotEmpty()) {
            val keptIds = kept.mapTo(mutableSetOf()) { it.id }
            retainStreamFlushStates(keptIds)
            _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
        }
        revokeMemoryWritesInDeletedMessages(deletedMessages)

        val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId
        val located = BubbleRowLocator.locateUserRow(messages, index, chatRepository.loadMessages(sid))
        logBubbleAnchor("edit", messageId, located)
        val cutoffSortOrder = BubbleRowLocator.cutoffFor(located, keepBubble = false)
        if (cutoffSortOrder >= 0) {
            chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)
        }
        agentHistory.clear()
        toolLoopDetector.reset()
        val remaining = chatRepository.loadMessages(sid)
        for (entity in remaining) {
            if (ChatRepository.isEmptyAssistantCarrier(entity.role, entity.partsJson)) continue
            agentHistory.add(entity.toLLMMessage())
        }
        AppLogger.info(
            TAG_STREAM,
            "✏️ truncateBeforeEdit cutoffSortOrder=$cutoffSortOrder remaining=${remaining.size}"
        )
    }

    fun enqueuePrompt(
        text: String,
        origin: QueuedPromptOrigin = QueuedPromptOrigin.USER,
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
        ownsComposer: Boolean = origin == QueuedPromptOrigin.USER,
        ownAttachments: List<InputAttachment> = emptyList(),
    ) {
        val trimmed = text.trim()
        val pendingAttachments = PromptAttachments.of(ownsComposer, ownAttachments, _attachments.value)
        if ((trimmed.isBlank() && pendingAttachments.isEmpty()) || !_isStreaming.value) return
        if (ownsComposer) {
            com.openminis.app.agent.userask.UserAskCenter.dismissPending(
                activeSessionId, com.openminis.app.agent.userask.UserAskDismissReason.SUPERSEDED_BY_USER_MESSAGE,
            )
        }

        val prompt = QueuedPrompt(
            id = "queued_${System.currentTimeMillis()}_${(Math.random() * 1_000_000).toInt()}",
            text = trimmed,
            attachments = pendingAttachments,
            origin = origin,
            prefill = prefill,
            scheduledTaskId = if (origin == QueuedPromptOrigin.PROGRAMMATIC) {
                com.openminis.app.scheduled.ScheduledTaskMarker.parse(trimmed)?.taskId
            } else null,
        )
        prompt.scheduledTaskId?.let { taskId ->
            val superseded = QueuedPromptBatching.supersededBy(_promptQueue.value, taskId).toSet()
            if (superseded.isNotEmpty()) {
                _promptQueue.value = _promptQueue.value.filterNot { it.id in superseded }
                _messages.value = _messages.value.filterNot { it.queuedPromptId in superseded }
                Log.i(TAG, "Scheduled task $taskId fired again before delivery — replaced ${superseded.size} older queued fire(s)")
            }
        }
        _promptQueue.value = _promptQueue.value + prompt

        val attachmentNames = pendingAttachments.map { it.fileName }
        val imageUris = pendingAttachments.filter { it.isImage }.map { it.uri }
        val attachmentUris = pendingAttachments.filterNot { it.isImage }.map { it.uri }
        val chatMsg = ChatMessage(
            id = "queued_msg_${prompt.id}",
            role = "user",
            content = userBubbleText(trimmed),
            imageUris = imageUris,
            attachmentNames = attachmentNames,
            attachmentUris = attachmentUris,
            isQueued = true,
            queuedPromptId = prompt.id,
        )
        _messages.value = _messages.value + chatMsg
        if (PromptAttachments.clearsComposer(ownsComposer)) clearAttachments()
        Log.i(TAG, "Enqueued prompt (${trimmed.length}ch, ${pendingAttachments.size} attachments, origin=$origin), queue=${_promptQueue.value.size}")
    }

    fun removeQueuedPrompt(promptId: String) {
        _promptQueue.value = _promptQueue.value.filterNot { it.id == promptId }
        _messages.value = _messages.value.filterNot { it.queuedPromptId == promptId }
    }

    fun withdrawQueuedMessage(messageId: String) {
        val msg = _messages.value.firstOrNull { it.id == messageId } ?: return
        if (!msg.isQueued) return
        val pid = msg.queuedPromptId ?: return
        _promptQueue.value = _promptQueue.value.filterNot { it.id == pid }
        _messages.value = _messages.value.filterNot { it.id == messageId }
        Log.i(TAG, "Withdrew queued message, queue=${_promptQueue.value.size}")
    }

    private fun insertedScheduledEnvelope(envelopeText: String): String =
        com.openminis.app.scheduled.ScheduledTaskMarker.parse(envelopeText)
            ?.copy(insertedMidTask = true)?.xml
            ?: envelopeText

    private data class InjectedTurn(
        val newAssistantId: String,
        val prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
    )

    private suspend fun injectQueuedPromptsAsNewTurn(
        finishedAssistantId: String,
        finishedAccumulatedText: String,
        finishedAllToolBlocks: List<AssistantBlock>,
        batch: List<QueuedPrompt>,
    ): InjectedTurn? {
        com.openminis.app.agent.jobs.AgentJobRegistry.clearDelegationMute(activeSessionId)
        if (batch.isEmpty()) return null
        val queued = batch
        val scheduledInsert = queued.size == 1 && queued[0].isScheduledFire
        if (scheduledInsert) toolLoopDetector.exemptNextCallForScheduledFire()

        val queuedIds = queued.map { it.id }.toSet()

        val sid = ensureSession()
        val combinedAttachments = queued.flatMap { it.attachments }
        val prepared = prepareUserAttachments(combinedAttachments, sid)

        val queuedTurn = ChatAttachmentIngest.queuedTurn(
            queued.map { prompt -> if (scheduledInsert) insertedScheduledEnvelope(prompt.text) else prompt.text },
            prepared,
            ::visionPlaceholderFor,
        )
        val combinedParts = queuedTurn.parts

        if (combinedParts.isEmpty()) {
            AppLogger.warning(
                TAG_STREAM,
                "injectQueuedPromptsAsNewTurn: ${queued.size} queued prompt(s) produced no content, skipping",
            )
            _promptQueue.value = _promptQueue.value.filterNot { it.id in queuedIds }
            return null
        }


        val userText = queuedTurn.text
        val queuedPaste = buildPastedParts(userText, sid)
        if (queuedPaste != null) {
            _pastedTexts.value = _pastedTexts.value.filterNot { it.id in queuedPaste.consumedIds }
        }
        val userPartsJson = buildUserPartsJson(
            userText,
            prepared.mediaRefPartsJson,
            prepared.attachedFilesXml,
            bodyPartsJson = queuedPaste?.partsJson,
        )
        val userEntity = chatRepository.appendMessage(sid, "user", userPartsJson)
        _promptQueue.value = _promptQueue.value.filterNot { it.id in queuedIds }
        agentHistory.add(
            ChatAttachmentIngest.queuedHistoryMessage(
                userText = userText,
                pastedModelText = queuedPaste?.modelText,
                imageParts = prepared.imageParts,
                combinedParts = combinedParts,
                interruptReminder = if (scheduledInsert) null else ChatMessage.QUEUED_INTERRUPT_REMINDER_TEXT,
                dbMessageId = userEntity.id,
            ),
        )

        val newAssistantId = "assistant_${System.currentTimeMillis()}"
        withContext(Dispatchers.Main) {
            _messages.value = _messages.value.filterNot { m ->
                m.queuedPromptId != null && queuedIds.contains(m.queuedPromptId)
            }
            updateAssistantMessage(
                finishedAssistantId,
                finishedAccumulatedText,
                false,
                finishedAllToolBlocks,
                isAwaitingModelResponse = false,
            )
            val queuedUserMsg = ChatMessage(
                id = userEntity.id,
                role = "user",
                content = userBubbleText(userText),
                imageUris = prepared.imageUris,
                attachmentNames = prepared.attachmentNames,
                attachmentUris = prepared.nonImageUris,
            )
            val nextAssistantMsg = ChatMessage(
                id = newAssistantId,
                role = "assistant",
                content = "",
                isStreaming = true,
                isAwaitingModelResponse = true,
                thinkingLevel = _thinkingLevel.value,
            )
            _messages.value = _messages.value + queuedUserMsg + nextAssistantMsg
        }

        AppLogger.info(
            TAG_STREAM,
            "injectQueuedPromptsAsNewTurn: injected ${queued.size} queued prompt(s) as new turn, " +
                "finishedId=$finishedAssistantId newId=$newAssistantId",
        )
        return InjectedTurn(newAssistantId, queued.flatMap { it.prefill })
    }

    private suspend fun drainQueuedPrompts(
        provider: LLMProvider,
        systemPrompt: String?,
        fallbackProviders: List<FallbackCandidate>,
        fallbackStrategy: com.openminis.app.data.model.FallbackStrategy,
    ) {
        while (_promptQueue.value.isNotEmpty()) {
            com.openminis.app.agent.jobs.AgentJobRegistry.clearDelegationMute(activeSessionId)
            val queued = QueuedPromptBatching.nextDrainBatch(_promptQueue.value)
            if (queued.any { it.isScheduledFire }) toolLoopDetector.exemptNextCallForScheduledFire()
            Log.i(TAG, "📨[DRAIN] Draining ${queued.size} queued prompt(s): " +
                queued.joinToString(", ") { "${it.id}=\"${it.text.take(20)}...\"" })
            val queuedIds = queued.map { it.id }.toSet()

            val sid = ensureSession()
            val combinedAttachments = queued.flatMap { it.attachments }
            val prepared = prepareUserAttachments(combinedAttachments, sid)

            val queuedTurn = ChatAttachmentIngest.queuedTurn(queued.map { it.text }, prepared, ::visionPlaceholderFor)
            val combinedParts = queuedTurn.parts

            val userText = queuedTurn.text
            val drainPaste = buildPastedParts(userText, sid)
            if (drainPaste != null) {
                _pastedTexts.value =
                    _pastedTexts.value.filterNot { it.id in drainPaste.consumedIds }
            }
            val userPartsJson = buildUserPartsJson(
                userText,
                prepared.mediaRefPartsJson,
                prepared.attachedFilesXml,
                bodyPartsJson = drainPaste?.partsJson,
            )
            val drainedRow = chatRepository.appendMessage(sid, "user", userPartsJson)
            _promptQueue.value = _promptQueue.value.filterNot { it.id in queuedIds }
            _messages.value = _messages.value.map { m ->
                if (m.queuedPromptId != null && queuedIds.contains(m.queuedPromptId)) {
                    m.copy(isQueued = false, queuedPromptId = null, sourceDbIds = listOf(drainedRow.id))
                } else m
            }

            agentHistory.add(ChatAttachmentIngest.queuedHistoryMessage(
                userText = userText,
                pastedModelText = drainPaste?.modelText,
                imageParts = prepared.imageParts,
                combinedParts = combinedParts,
                interruptReminder = null,
                dbMessageId = null,
            ))

            try {
                runAgentLoop(
                    provider = provider,
                    systemPrompt = systemPrompt,
                    fallbackProviders = fallbackProviders,
                    fallbackStrategy = fallbackStrategy,
                    prefill = queued.flatMap { it.prefill },
                )
            } catch (e: CancellationException) {
                Log.d(TAG, "Agent loop (queued-drain) cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Agent loop (queued-drain) error", e)
                setInlineError(e.message ?: "Unknown error")
                break
            }
        }
    }

    @Volatile private var pendingProviderEdits: Set<String> = emptySet()
    private var providerInstancesSeen: Map<String, com.openminis.app.data.model.ProviderInstance>? = null

    private fun watchProviderEdits(): kotlinx.coroutines.Job =
        viewModelScope.launch {
            providerRepository.config.collect { cfg ->
                val now = cfg.instances.associateBy { it.id }
                val before = providerInstancesSeen
                providerInstancesSeen = now
                if (before == null) return@collect
                val edited = editedProviderInstanceIds(before, now)
                if (edited.isNotEmpty()) pendingProviderEdits = pendingProviderEdits + edited
            }
        }

    private val providerEditWatch = watchProviderEdits()

    private val providerKeyWatch = viewModelScope.launch {
        com.openminis.app.data.repository.ProviderCredentialEdits.ids.collect { id ->
            pendingProviderEdits = pendingProviderEdits + id
        }
    }

    private fun rebuildProviderIfEdited() {
        if (pendingProviderEdits.isEmpty() || _isStreaming.value) return
        val entryId = _activeEntryId.value ?: return
        val entry = providerRepository.config.value.modelEntries.find { it.id == entryId } ?: return
        val id = entry.providerInstanceId
        if (id !in pendingProviderEdits) return
        pendingProviderEdits = pendingProviderEdits - id
        val instance = providerRepository.instance(id) ?: return
        if (!providerRepository.hasAnyCredential(instance)) return
        val apiKey = providerRepository.usableApiKey(instance) ?: ""
        currentProvider = ProviderFactory.create(
            instance, apiKey, currentModel ?: entry.model, context,
            sessionId = activeSessionId, overrides = entry.overrides,
        )
        _providerName.value = instance.label.ifEmpty { entry.model.provider }
        AppLogger.info(TAG, "[provider-edit] rebuilt provider for edited instance ${id.take(8)} (responses=${instance.useResponsesAPI})")
    }

    @Volatile private var skillScoutText: String? = null
    @Volatile private var skillScoutFor: String? = null
    @Volatile private var skillScoutCache: String? = null
    @Volatile private var skillRadarEntries: List<com.openminis.app.skills.radar.SkillCandidate>? = null
    @Volatile private var skillRadarWarmupStarted = false

    private fun skillScoutLine(): String? {
        val text = skillScoutText ?: return null
        if (text == skillScoutFor) return skillScoutCache
        val entries = skillRadarEntries ?: return null
        val installed = skillRepository?.skills?.value?.mapNotNull { it.sourceURL }?.toSet() ?: emptySet()
        val fragment = com.openminis.app.skills.radar.SkillScout.fragment(text, entries, installed)
        skillScoutFor = text
        skillScoutCache = fragment
        return fragment
    }

    @Volatile private var lessonFor: String? = null
    @Volatile private var lessonBlock: String? = null

    private val lessonStore by lazy { com.openminis.app.agent.learning.LessonStore(context) }
    @Volatile private var lessonWritesSinceReview = 0

    private fun lessonPromptSection(): String? {
        val text = skillScoutText ?: return null
        if (text == lessonFor) return lessonBlock
        val block = buildString {
            val lessons = lessonStore.search(text, 5)
            if (lessons.isNotEmpty()) {
                append("Notebook — lessons from earlier sessions. Check them BEFORE you act, follow them silently, " +
                    "never recite them:\n")
                lessons.forEach { l ->
                    append("- [").append(label(l.kind)).append(']').append(' ').append(l.text.take(140)).append('\n')
                }
                lessonStore.markUsed(lessons)
            }
            val pending = lessonStore.pendingPromotions().take(2)
            if (pending.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append("Promotion: ")
                pending.forEach { l ->
                    append("#").append(l.id).append(" repeated ").append(l.hits)
                        .append("x (\"").append(l.text.take(60)).append("\"). ")
                }
                append("Ask the user once whether to keep it permanently — only if they say yes, call " +
                    "lesson_write action=promote id=<id>.")
            }
            if (com.openminis.app.agent.learning.CorrectionDetector.detect(text)) {
                if (isNotEmpty()) append('\n')
                append(com.openminis.app.agent.learning.CorrectionDetector.nudge())
            }
        }
        lessonFor = text
        lessonBlock = block.ifEmpty { null }
        return lessonBlock
    }

    private fun label(kind: com.openminis.app.agent.learning.LessonStore.Kind): String = when (kind) {
        com.openminis.app.agent.learning.LessonStore.Kind.ERROR -> "error"
        com.openminis.app.agent.learning.LessonStore.Kind.CORRECTION -> "correction"
        com.openminis.app.agent.learning.LessonStore.Kind.BEST -> "best practice"
        com.openminis.app.agent.learning.LessonStore.Kind.GAP -> "gap"
    }

    private fun captureCorrection(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                lessonStore.add(
                    com.openminis.app.agent.learning.LessonStore.Kind.CORRECTION,
                    com.openminis.app.agent.learning.LessonStore.inferCategory(text),
                    text.take(180),
                    listOf("auto"),
                    initialConfidence = 0.45,
                )
                lessonFor = null
            }
        }
    }

    private fun captureToolFailure(tool: String, output: String) {
        val normalized = com.openminis.app.agent.learning.LessonStore.normalizeFailure(tool, output) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                lessonStore.add(
                    com.openminis.app.agent.learning.LessonStore.Kind.ERROR,
                    com.openminis.app.agent.learning.LessonStore.Category.TECHNICAL,
                    normalized,
                    listOf(tool),
                    initialConfidence = 0.5,
                )
                lessonFor = null
            }
        }
    }

    private fun executeLessonWrite(argsJson: String): ToolExecutionResult {
        val title = runCatching { JSONObject(argsJson).optString("tool_title", "Lesson") }
            .getOrDefault("Lesson")
        val args = try { JSONObject(argsJson) } catch (_: Exception) {
            return ToolExecutionResult("Error: invalid arguments", false, toolTitle = title)
        }
        val action = args.optString("action", "write").trim().lowercase()
        return when (action) {
            "write", "" -> {
                val text = args.optString("text", "").trim()
                if (text.isEmpty()) {
                    return ToolExecutionResult(
                        "Error: `text` is required — one imperative rule, ≤120 chars.",
                        false, toolTitle = title,
                    )
                }
                val kind = com.openminis.app.agent.learning.LessonStore.Kind.parse(args.optString("kind", "correction"))
                val category = com.openminis.app.agent.learning.LessonStore.Category
                    .parse(args.optString("category", ""))
                    ?: com.openminis.app.agent.learning.LessonStore.inferCategory(text)
                val tags = args.optJSONArray("tags")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it, null) }.filter { it.isNotBlank() }
                } ?: emptyList()
                val saved = lessonStore.add(kind, category, text, tags)
                    ?: return ToolExecutionResult(
                        "Error: lesson must be 3–240 characters of plain text.",
                        false, toolTitle = title,
                    )
                com.openminis.app.observability.TraceLog.record(
                    kind = "learning", name = "lesson_write", durationMs = 0, ok = true,
                    summary = "save ${saved.kind.id}/${saved.id}: ${saved.text.take(60)}",
                )
                lessonFor = null
                lessonWritesSinceReview += 1
                if (lessonWritesSinceReview >= 8) {
                    lessonWritesSinceReview = 0
                    runCatching { lessonStore.consolidate() }
                }
                ToolExecutionResult(
                    "Saved to ${saved.kind.file} as #${saved.id} (${saved.category.id}, confidence " +
                        "${"%.2f".format(saved.confidence)}, seen ${saved.hits}x). " + lessonStore.stats(),
                    true, toolTitle = title,
                )
            }
            "list" -> {
                val lessons = lessonStore.all().sortedWith(
                    compareByDescending<com.openminis.app.agent.learning.LessonStore.Lesson> { it.promoted }
                        .thenByDescending { it.confidence },
                )
                if (lessons.isEmpty()) {
                    ToolExecutionResult("The notebook is empty — nothing has been learned yet.", true, toolTitle = title)
                } else {
                    val body = buildString {
                        append("Notebook ").append(lessonStore.stats()).append(":\n")
                        lessons.take(40).forEach { l ->
                            append("- #").append(l.id).append(" [").append(label(l.kind)).append('/')
                                .append(l.category.id).append("] (").append("%.2f".format(l.confidence))
                            if (l.hits > 1) append(", ").append(l.hits).append("x")
                            if (l.promoted) append(", permanent")
                            append(") ").append(l.text).append('\n')
                        }
                    }
                    ToolExecutionResult(body.trimEnd(), true, toolTitle = title)
                }
            }
            "stats" -> ToolExecutionResult(lessonStore.stats(), true, toolTitle = title)
            "forget" -> {
                val target = args.optString("id", "").trim().ifEmpty { args.optString("text", "").trim() }
                if (target.isEmpty()) {
                    return ToolExecutionResult("Error: give `id` or `text` to forget.", false, toolTitle = title)
                }
                val ok = lessonStore.forget(target)
                lessonFor = null
                ToolExecutionResult(
                    if (ok) "Forgot \"${target.take(60)}\". Now: ${lessonStore.stats()}."
                    else "No entry matched \"${target.take(60)}\". Try action=list.",
                    ok, toolTitle = title,
                )
            }
            "promote" -> {
                val id = args.optString("id", "").trim()
                if (id.isEmpty()) return ToolExecutionResult("Error: give `id`.", false, toolTitle = title)
                val promoted = lessonStore.promote(id)
                    ?: return ToolExecutionResult("No entry with id #$id.", false, toolTitle = title)
                runCatching {
                    memoryFactRepository.upsert(
                        subject = "self-improvement",
                        predicate = "rule",
                        value = promoted.text,
                        sourceSessionId = activeSessionId,
                    )
                }
                lessonFor = null
                ToolExecutionResult(
                    "Promoted #${promoted.id} to permanent and wrote it into long-term memory. " +
                        "It will also keep appearing in the notebook.",
                    true, toolTitle = title,
                )
            }
            else -> ToolExecutionResult("Error: unknown action \"$action\".", false, toolTitle = title)
        }
    }

    private suspend fun executeGitHub(argsJson: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        val title = runCatching { JSONObject(argsJson).optString("tool_title", "GitHub") }
            .getOrDefault("GitHub")
        val args = try { JSONObject(argsJson) } catch (_: Exception) {
            return@withContext ToolExecutionResult("Error: invalid arguments", false, toolTitle = title)
        }
        val auth = com.openminis.app.github.GitHubAuth(context)
        when (val action = args.optString("action", "status").trim().lowercase()) {
            "login" -> {
                val token = args.optString("token", "").trim()
                if (token.isEmpty()) {
                    return@withContext ToolExecutionResult(
                        "Error: `token` is required. Ask the user to create a GitHub token (Settings → Developer " +
                            "settings → Personal access tokens) with the scopes they want: " +
                            com.openminis.app.github.GitHubAuth.MAX_SCOPES_HINT,
                        false, toolTitle = title,
                    )
                }
                val reply = com.openminis.app.github.GitHubApi(token).request("GET", "/user")
                if (!reply.ok) {
                    val reason = if (reply.code == 401) "the token was rejected (401)" else "HTTP ${reply.code}"
                    return@withContext ToolExecutionResult(
                        "Error: GitHub did not accept the token — $reason. It may be expired, revoked, or " +
                            "copied incompletely.",
                        false, toolTitle = title,
                    )
                }
                val user = runCatching { JSONObject(reply.body) }.getOrNull()
                val login = user?.optString("login", "") ?: ""
                val name = user?.optString("name", "") ?: ""
                val scopes = reply.scopes.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val kind = if (token.startsWith("github_pat_")) "fine-grained" else "classic"
                auth.save(token, login, name, scopes, kind)
                com.openminis.app.observability.TraceLog.record(
                    kind = "github", name = "login", durationMs = 0, ok = true,
                    summary = "connected @${login} (${kind}, ${scopes.size} scopes)",
                )
                ToolExecutionResult(
                    "Connected to GitHub as @${login.ifEmpty { "unknown" }}" +
                        (if (name.isNotEmpty()) " ($name)" else "") + " · $kind token" +
                        (if (scopes.isEmpty()) ", scopes hidden by GitHub" else ", scopes: ${scopes.joinToString(", ")}") +
                        ". The connector now acts with all of this token's permissions.",
                    true, toolTitle = title,
                )
            }
            "logout" -> {
                auth.disconnect()
                ToolExecutionResult("Disconnected. GitHub calls now run anonymously (60 requests/hour).", true, toolTitle = title)
            }
            "status" -> {
                val session = auth.session()
                if (session == null) {
                    ToolExecutionResult(
                        "GitHub is not connected yet — public calls only, 60 requests/hour.\n" +
                            "Best path: tell the user to open Settings → GitHub and tap Connect GitHub — that runs " +
                            "the device flow (a code plus a link they can open on this or another phone), and the " +
                            "token never passes through the chat.\n" +
                            "Fallback: ask the user for a token. Create one at GitHub → Settings → Developer " +
                            "settings → Personal access tokens (classic) with these scopes for maximum access: " +
                            com.openminis.app.github.GitHubAuth.MAX_SCOPES_HINT +
                            ". Then call github action=login token=<token>. Fine-grained tokens work too; pick the " +
                            "repository permissions and org permissions needed.",
                        false, toolTitle = title,
                    )
                } else {
                    val rate = com.openminis.app.github.GitHubApi(session.token).request("GET", "/rate_limit")
                    val core = runCatching {
                        JSONObject(rate.body).optJSONObject("resources")?.optJSONObject("core")
                    }.getOrNull()
                    val remaining = core?.optInt("remaining", -1) ?: -1
                    val limit = core?.optInt("limit", -1) ?: -1
                    ToolExecutionResult(
                        "Connected as @${session.login.ifEmpty { "unknown" }}" +
                            (if (session.name.isNotEmpty()) " (${session.name})" else "") + " · ${session.kind} token" +
                            (if (session.scopes.isEmpty()) ", scopes hidden (fine-grained)"
                            else ", scopes: ${session.scopes.joinToString(", ")}") +
                            (if (limit > 0) " · rate $remaining/$limit per hour" else "") +
                            ". Token stored encrypted on this device; logout clears it.",
                        true, toolTitle = title,
                    )
                }
            }
            "api" -> {
                val method = args.optString("method", "GET").trim().uppercase().ifEmpty { "GET" }
                val path = args.optString("path", "").trim()
                if (path.isEmpty()) {
                    return@withContext ToolExecutionResult("Error: `path` is required, e.g. /user or /repos/owner/name.",
                        false, toolTitle = title)
                }
                if (com.openminis.app.github.GitHubApi.isRepoDelete(path, method) && !args.optBoolean("confirm", false)) {
                    return@withContext ToolExecutionResult(
                        "Refused: deleting a repository is irreversible. Ask the user to confirm explicitly, then " +
                            "call again with confirm=true.",
                        false, toolTitle = title,
                    )
                }
                val body = args.optString("body", "").trim().ifBlank { null }
                if (body != null) {
                    val bad = com.openminis.app.github.GitHubApi.invalidBody(body)
                    if (bad != null) {
                        return@withContext ToolExecutionResult(
                            "Error: $bad — pass a JSON object/array string.",
                            false, toolTitle = title,
                        )
                    }
                }
                val session = auth.session()
                val reply = com.openminis.app.github.GitHubApi(session?.token).request(method, path, body)
                com.openminis.app.observability.TraceLog.record(
                    kind = "github", name = "api", durationMs = 0, ok = reply.ok,
                    summary = "$method $path -> ${reply.code}",
                )
                ToolExecutionResult(
                    com.openminis.app.github.GitHubApi.summarize(reply) + hintFor(reply, session != null),
                    reply.ok, toolTitle = title,
                )
            }
            "graphql" -> {
                val query = args.optString("query", "").trim()
                if (query.isEmpty()) {
                    return@withContext ToolExecutionResult("Error: `query` is required for GraphQL.", false, toolTitle = title)
                }
                val session = auth.session()
                    ?: return@withContext ToolExecutionResult("Error: GraphQL needs a connected token — action=login first.",
                        false, toolTitle = title)
                val payload = JSONObject().put("query", query).toString()
                val reply = com.openminis.app.github.GitHubApi(session.token).request("POST", "/graphql", payload)
                com.openminis.app.observability.TraceLog.record(
                    kind = "github", name = "graphql", durationMs = 0, ok = reply.ok,
                    summary = "query ${query.take(40)} -> ${reply.code}",
                )
                ToolExecutionResult(
                    com.openminis.app.github.GitHubApi.summarize(reply) + hintFor(reply, true),
                    reply.ok, toolTitle = title,
                )
            }
            else -> ToolExecutionResult("Error: unknown action \"$action\".", false, toolTitle = title)
        }
    }

    private fun hintFor(reply: com.openminis.app.github.GitHubApi.Reply, connected: Boolean): String = when {
        reply.code == 401 -> "\n(token rejected — ask the user to reconnect with github action=login)"
        reply.code == 403 && reply.rateRemaining == "0" ->
            "\n(hourly rate limit exhausted" + (if (connected) " for this token" else " for anonymous calls") +
                " — wait for the reset or connect a token)"
        reply.code == 404 -> "\n(nothing there: wrong owner/repo/path, or the token cannot see it)"
        else -> ""
    }

    private fun warmSkillRadar() {
        val repo = skillRepository ?: return
        if (skillRadarWarmupStarted) return
        skillRadarWarmupStarted = true
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val radar = com.openminis.app.skills.radar.SkillRadar(context, repo)
                if (!radar.hasIndex()) radar.refresh(false)
                skillRadarEntries = radar.snapshot()?.entries
            }
        }
    }

    fun sendMessage(text: String) {
        com.openminis.app.agent.jobs.AgentJobRegistry.clearDelegationMute(activeSessionId)
        sendMessage(text, skipContextCheck = false, headless = false)
    }

    sealed class SubmitOutcome {
        object Sent : SubmitOutcome()
        object Queued : SubmitOutcome()
        object Compacting : SubmitOutcome()
        data class Rejected(val reason: String) : SubmitOutcome()
    }

    internal fun submitPrompt(
        text: String,
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
        attachments: List<InputAttachment> = emptyList(),
    ): SubmitOutcome =
        sendMessage(text, skipContextCheck = false, headless = true, prefill = prefill, headlessAttachments = attachments)

    private fun sendMessage(
        text: String,
        skipContextCheck: Boolean,
        headless: Boolean = false,
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
        userAskAnswer: Boolean = false,
        headlessAttachments: List<InputAttachment> = emptyList(),
    ): SubmitOutcome {
        com.openminis.app.agent.jobs.AgentJobRegistry.clearDelegationMute(activeSessionId)
        val promptAttachments = PromptAttachments.of(ownsComposer = !headless, own = headlessAttachments, composer = _attachments.value)
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            skillScoutText = trimmed
            warmSkillRadar()
            if (com.openminis.app.agent.learning.CorrectionDetector.detect(trimmed)) {
                captureCorrection(trimmed)
            }
        }
        if (_isStreaming.value) {
            val before = _promptQueue.value.size
            enqueuePrompt(
                text,
                if (headless && !userAskAnswer) QueuedPromptOrigin.PROGRAMMATIC else QueuedPromptOrigin.USER,
                prefill,
                ownsComposer = !headless,
                ownAttachments = if (headless) headlessAttachments else emptyList(),
            )
            return if (_promptQueue.value.size == before + 1) SubmitOutcome.Queued
            else SubmitOutcome.Rejected("enqueue_declined")
        }
        if (trimmed.isBlank() && promptAttachments.isEmpty()) return SubmitOutcome.Rejected("empty_prompt")
        if (_isCompacting.value) {
            appendSystemInfo(
                text = "Wait for the current compact to finish before sending.",
                iconKind = "compact",
            )
            return SubmitOutcome.Rejected("session_compacting")
        }
        if (!skipContextCheck) {
            when (checkContextBeforeSend()) {
                PreSendContextAction.PROCEED -> {}
                PreSendContextAction.COMPACT_THEN_SEND -> {
                    pendingSendText = text
                    pendingSendPrefill = prefill
                    pendingSendHeadless = headless
                    pendingSendAttachments = if (headless) headlessAttachments else emptyList()
                    if (!headless) _inputText.value = ""
                    compactAndSendPending()
                    return SubmitOutcome.Compacting
                }
                PreSendContextAction.ASK_USER -> {
                    if (headless) return SubmitOutcome.Rejected("context_near_capacity")
                    pendingSendText = text
                    pendingSendHeadless = false
                    _inputText.value = ""
                    _showCompactBeforeSendPrompt.value = true
                    return SubmitOutcome.Rejected("awaiting_user_compact_decision")
                }
            }
        }
        _canResume.value = false
        if (_hasInjectedShareContent.value) _hasInjectedShareContent.value = false

        rebuildProviderIfEdited()
        val initialProvider = currentProvider
        if (initialProvider == null) {
            _error.value = "No provider configured"
            return SubmitOutcome.Rejected("no_provider")
        }
        var provider: LLMProvider = initialProvider

        _error.value = null

        val currentAttachments = promptAttachments
        if (PromptAttachments.clearsComposer(ownsComposer = !headless)) clearAttachments()

        if (com.openminis.app.scheduled.ScheduledTaskMarker.parse(text) != null) {
            toolLoopDetector.exemptNextCallForScheduledFire()
        }

        AppLogger.info(TAG_STREAM, "send _isStreaming=true (sync, sid=$activeSessionId)")
        _isStreaming.value = true

        if (_streamingById.value.isNotEmpty()) {
            AppLogger.warning(TAG_STREAM, "send: sweeping ${_streamingById.value.size} orphan streaming delta(s) before new turn")
            flushAllStreamingDeltas()
        }

        val editingId = if (headless) null else _editingMessageId.value
        if (editingId != null) _editingMessageId.value = null

        viewModelScope.launch {
            var streamLaunched = false
            try {
            if (!sessionLoaded.value && !com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
                AppLogger.info(TAG_STREAM, "send waiting for session load (sid=${this@ChatViewModel.activeSessionId})")
                sessionLoaded.first { it }
            }
            val activeSessionId = ensureSession()
            if (!headless) {
                com.openminis.app.agent.userask.UserAskRecoveryCoordinator.supersedeRecovered(activeSessionId)?.let { parts ->
                    agentHistory.add(LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = parts))
                }
                supersedeDeferredAsks(activeSessionId)
            }

            if (editingId != null) {
                truncateBeforeEdit(editingId)
            }

            val prepared = prepareUserAttachments(currentAttachments, activeSessionId)

            val pasted = buildPastedParts(trimmed, activeSessionId)
            if (pasted != null) {
                _pastedTexts.value = _pastedTexts.value.filterNot { it.id in pasted.consumedIds }
            }

            val userPartsJson = buildUserPartsJson(
                trimmed,
                prepared.mediaRefPartsJson,
                prepared.attachedFilesXml,
                bodyPartsJson = pasted?.partsJson,
            )
            val persistedUser = chatRepository.appendMessage(activeSessionId, "user", userPartsJson)

            val userMsg = ChatMessage(
                id = persistedUser.id,
                role = "user",
                content = userBubbleText(pasted?.let { p ->
                    p.consumedIds.fold(trimmed) { acc, id ->
                        acc.replace(PastedText.placeholderFor(id), "")
                    }.trim()
                } ?: trimmed),
                imageUris = prepared.imageUris,
                attachmentNames = prepared.attachmentNames + (pasted?.uiNames ?: emptyList()),
                attachmentUris = prepared.nonImageUris + (pasted?.uiUris ?: emptyList()),
            )
            _messages.value = _messages.value + userMsg
            val imageParts = prepared.imageParts

            val modelBody = pasted?.modelText ?: trimmed

            agentHistory.add(ChatAttachmentIngest.liveUserTurn(
                modelBody, imageParts, prepared.imageUploadPaths, prepared.imageModelNotes,
                prepared.attachedFilesXml, persistedUser.id, ::visionPlaceholderFor,
            ))

            if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
                try {
                    val activeEntryId = _activeEntryId.value
                    val entry = activeEntryId?.let { id -> providerRepository.config.value.modelEntries.find { it.id == id } }
                    val instance = entry?.let { e -> providerRepository.config.value.instances.find { it.id == e.providerInstanceId } }
                    if (instance != null) {
                        val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                        val freshToken = manager?.validAccessToken()
                        if (freshToken != null) {
                            val storedKey = providerRepository.loadApiKey(instance.id)
                            if (freshToken != storedKey) {
                                providerRepository.saveApiKey(instance.id, freshToken)
                                provider = com.openminis.app.provider.ProviderFactory.create(
                                    instance, freshToken, currentModel ?: provider.model, context,
                                    sessionId = activeSessionId,
                                    overrides = (provider as? com.openminis.app.provider.openai.OpenAIProvider)
                                        ?.modelOverrides,
                                )
                                currentProvider = provider
                                android.util.Log.i(TAG, "OAuth token refreshed before send")
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "OAuth token refresh failed: ${e.message}")
                }
            }

            decideUserAskExposureForRun(interactive = !headless, userAskAnswer = userAskAnswer)
            // [T-port-user-ask]
            val baseSystemPrompt = buildSystemPrompt(trimmed)
            val systemPrompt = if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
                val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
                if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
                else "$prefix\n\n${baseSystemPrompt ?: ""}"
            } else baseSystemPrompt

            streamLaunched = true
            val holdsSlot = helperConfig == null
            streamJob = launch(Dispatchers.IO) {
                AppLogger.info(TAG_STREAM, "send streamJob ENTER sid=$activeSessionId")
                try {
                    println(
                        "[T-STALL-DIAG] send PRE-ACQUIRE sid=$activeSessionId " +
                            SessionConcurrencyManager.diagSnapshot(),
                    )
                    if (holdsSlot) SessionConcurrencyManager.acquireSlot(activeSessionId)
                    AppLogger.debug(TAG_STREAM, "send streamJob slot acquired")
                    SessionActivityTracker.setActive(
                    activeSessionId,
                    onStop = { cancelStream() },
                    sessionTitle = overlaySessionTitle(),
                )

                    val activeFallbackStrategy = run {
                        val groupId = _selectedGroupId.value
                        groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                            ?: com.openminis.app.data.model.FallbackStrategy.default
                    }

                    val fallbackProviders = buildFallbackProviders(provider)

                    try {
                        AppLogger.info(TAG_STREAM, "send runAgentLoop CALL")
                        runAgentLoop(
                            provider = provider,
                            systemPrompt = systemPrompt,
                            fallbackProviders = fallbackProviders,
                            fallbackStrategy = activeFallbackStrategy,
                            prefill = prefill,
                        )
                        AppLogger.info(TAG_STREAM, "send runAgentLoop RETURN normal")
                        drainQueuedPrompts(provider, systemPrompt, fallbackProviders, activeFallbackStrategy)
                        AppLogger.info(TAG_STREAM, "send drainQueuedPrompts RETURN")
                    } catch (e: CancellationException) {
                        AppLogger.info(TAG_STREAM, "send runAgentLoop CANCELLED")
                        Log.d(TAG, "Agent loop cancelled")
                    } catch (e: Exception) {
                        AppLogger.error(TAG_STREAM, "send runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                        Log.e(TAG, "Agent loop error (all fallbacks exhausted)", e)
                        val imageHint = if (ImageInputPreflight.isLikelyImageRejection(e, imageParts.size)) {
                            "\n" + context.getString(R.string.image_input_rejected_hint, imageParts.size)
                        } else ""
                        setInlineError((e.message ?: "Unknown error") + imageHint)
                        SessionActivityTracker.markStreamError(activeSessionId)
                    } finally {
                        AppLogger.info(TAG_STREAM, "send streamJob FINALLY enter")
                        publishOverlayReplyExcerpt(activeSessionId)
                        markRunOutcomeForNotification(activeSessionId)
                        SessionActivityTracker.setInactive(activeSessionId)
                        if (holdsSlot) SessionConcurrencyManager.releaseSlot(activeSessionId)
                        AppLogger.info(TAG_STREAM, "send streamJob FINALLY exit")
                    }
                } catch (e: CancellationException) {
                    AppLogger.info(TAG_STREAM, "send streamJob CANCELLED waiting for slot")
                    Log.d(TAG, "Cancelled while waiting for concurrency slot")
                }
                if (streamJob === coroutineContext[Job]) {
                    AppLogger.info(TAG_STREAM, "send _isStreaming=false (about to set)")
                    _isStreaming.value = false
                } else {
                    AppLogger.info(TAG_STREAM, "send _isStreaming SKIPPED (stale job)")
                }
                AppLogger.info(TAG_STREAM, "send streamJob EXIT")
            }
            } finally {
                if (!streamLaunched) {
                    AppLogger.info(TAG_STREAM, "send _isStreaming=false (setup aborted)")
                    _isStreaming.value = false
                }
            }
        }
        return SubmitOutcome.Sent
    }

    private fun markRunOutcomeForNotification(sid: String) {
        val outcome = com.openminis.app.notification.BackgroundTurnOutcome.decide(
            streamError = false,
            turnError = com.openminis.app.notification.BackgroundTurnOutcome.currentTurnError(_messages.value) != null,
            canResume = _canResume.value,
            userStopped = false,
        )
        when (outcome) {
            com.openminis.app.notification.BackgroundTurnOutcome.FAILED -> SessionActivityTracker.markStreamError(sid)
            com.openminis.app.notification.BackgroundTurnOutcome.INTERRUPTED -> SessionActivityTracker.markStreamInterrupted(sid)
            com.openminis.app.notification.BackgroundTurnOutcome.COMPLETED -> Unit
        }
    }

    private fun setInlineError(errorText: String) {
        val safeError = errorText.ifBlank { context.getString(R.string.error_empty_response_generic) }
        flushAllStreamingDeltas()
        val msgs = _messages.value.toMutableList()
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx >= 0) {
            val msg = msgs[lastAssistantIdx]
            msgs[lastAssistantIdx] = msg.copy(
                error = safeError,
                isStreaming = false,
                isAwaitingModelResponse = false,
            )
            _messages.value = msgs
            val sid = realSessionId.ifEmpty { sessionId }
            if (sid.isNotEmpty()) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val write = chatRepository.persistTurnError(sid, safeError)
                        AppLogger.info(TAG, "[ErrorPersist] sid=${sid.take(8)} $write")
                    } catch (e: Exception) { Log.w(TAG, "persist error_info failed: ${e.message}") }
                }
            }
        } else {
            _error.value = safeError
        }
    }

    private fun clearInlineError() {
        val msgs = _messages.value.toMutableList()
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx < 0) return
        val msg = msgs[lastAssistantIdx]
        if (msg.error == null) return
        msgs[lastAssistantIdx] = msg.copy(error = null)
        _messages.value = msgs
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isNotEmpty()) {
            val dbIds = msg.sourceDbIds
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    if (dbIds.isNotEmpty()) {
                        dbIds.forEach { chatRepository.updateMessageErrorInfo(it, null) }
                    } else {
                        chatRepository.updateLastAssistantError(sid, null)
                    }
                } catch (e: Exception) { Log.w(TAG, "clear error_info failed: ${e.message}") }
            }
        }
    }

    private fun clearPersistedLastAssistantError() {
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try { chatRepository.updateLastAssistantError(sid, null) }
            catch (e: Exception) { Log.w(TAG, "clear error_info (persisted) failed: ${e.message}") }
        }
    }

    fun retryLast() {
        if (_isStreaming.value) return
        com.openminis.app.agent.jobs.AgentJobRegistry.clearDelegationMute(activeSessionId)
        flushAllStreamingDeltas()
        val msgs = _messages.value.toMutableList()
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx < 0) return
        _forceScrollToBottom.tryEmit(Unit)

        val lastMsg = msgs[lastAssistantIdx]
        val keptToolBlocks = lastMsg.toolBlocks.filter { block ->
            block.toolStatus !in IN_FLIGHT_TOOL_STATUSES
        }
        msgs[lastAssistantIdx] = lastMsg.copy(
            error = null,
            isStreaming = false,
            isAwaitingModelResponse = false,
            toolBlocks = keptToolBlocks,
        )
        _messages.value = msgs
        clearPersistedLastAssistantError()

        val poppedAssistant = if (agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT) {
            val last = agentHistory.removeAt(agentHistory.size - 1)
            last
        } else null

        val liveToolUseIds = agentHistory.flatMap { m ->
            m.contentParts.filterIsInstance<AgentContentPart.ToolUse>().map { it.id }
        }.toSet()
        for (i in agentHistory.indices.reversed()) {
            val m = agentHistory[i]
            if (m.role != LLMMessage.Role.USER) continue
            val cleanedParts = m.contentParts.filter { p ->
                p !is AgentContentPart.ToolResult || p.id in liveToolUseIds
            }
            when {
                cleanedParts.isEmpty() && m.contentParts.isNotEmpty() ->
                    agentHistory.removeAt(i)
                cleanedParts.size < m.contentParts.size ->
                    agentHistory[i] = m.copy(contentParts = cleanedParts)
            }
        }

        rebuildProviderIfEdited()
        val initialProvider = currentProvider ?: return
        var provider: LLMProvider = initialProvider
        _error.value = null
        _canResume.value = false

        AppLogger.info(TAG_STREAM, "retryLast _isStreaming=true (sync, sid=$activeSessionId)")
        _isStreaming.value = true

        viewModelScope.launch {
            var streamLaunched = false
            try {
            val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId

            if (poppedAssistant != null) {
                val dbMessages = chatRepository.loadMessages(sid)
                val trailingAssistantSortOrder = dbMessages
                    .lastOrNull { it.role == "assistant" }?.sortOrder
                if (trailingAssistantSortOrder != null) {
                    chatRepository.deleteMessagesAfter(sid, trailingAssistantSortOrder)
                    AppLogger.info(
                        TAG_STREAM,
                        "retryLast: deleted trailing assistant row sortOrder=$trailingAssistantSortOrder, kept ${trailingAssistantSortOrder} prior rows",
                    )
                }
            } else {
                AppLogger.info(
                    TAG_STREAM,
                    "retryLast: agentHistory tail was user(tool_result) — no DB cleanup needed",
                )
            }

            if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
                try {
                    val activeEntryId = _activeEntryId.value
                    val entry = activeEntryId?.let { id -> providerRepository.config.value.modelEntries.find { it.id == id } }
                    val instance = entry?.let { e -> providerRepository.config.value.instances.find { it.id == e.providerInstanceId } }
                    if (instance != null) {
                        val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                        val freshToken = manager?.validAccessToken()
                        if (freshToken != null) {
                            val storedKey = providerRepository.loadApiKey(instance.id)
                            if (freshToken != storedKey) {
                                providerRepository.saveApiKey(instance.id, freshToken)
                                provider = ProviderFactory.create(instance, freshToken, currentModel ?: provider.model, context, sessionId = activeSessionId, overrides = (provider as? com.openminis.app.provider.openai.OpenAIProvider)?.modelOverrides)
                                currentProvider = provider
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "OAuth token refresh failed: ${e.message}")
                }
            }

            decideUserAskExposureForRun(interactive = true)
            // [T-port-user-ask]
            val baseSystemPrompt = buildSystemPrompt()
            val systemPrompt = if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
                val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
                if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
                else "$prefix\n\n${baseSystemPrompt ?: ""}"
            } else baseSystemPrompt

            streamLaunched = true
            val holdsSlot = helperConfig == null
            streamJob = launch(Dispatchers.IO) {
                AppLogger.info(TAG_STREAM, "retryLast streamJob ENTER sid=$activeSessionId")
                try {
                    if (holdsSlot) SessionConcurrencyManager.acquireSlot(activeSessionId)
                    AppLogger.debug(TAG_STREAM, "retryLast streamJob slot acquired")
                    SessionActivityTracker.setActive(
                    activeSessionId,
                    onStop = { cancelStream() },
                    sessionTitle = overlaySessionTitle(),
                )
                    val activeFallbackStrategy = run {
                        val groupId = _selectedGroupId.value
                        groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                            ?: com.openminis.app.data.model.FallbackStrategy.default
                    }
                    val fallbackProviders = buildFallbackProviders(provider)
                    try {
                        AppLogger.info(TAG_STREAM, "retryLast runAgentLoop CALL")
                        runAgentLoop(
                            provider = provider,
                            systemPrompt = systemPrompt,
                            fallbackProviders = fallbackProviders,
                            fallbackStrategy = activeFallbackStrategy,
                        )
                        AppLogger.info(TAG_STREAM, "retryLast runAgentLoop RETURN normal")
                        drainQueuedPrompts(provider, systemPrompt, fallbackProviders, activeFallbackStrategy)
                        AppLogger.info(TAG_STREAM, "retryLast drainQueuedPrompts RETURN")
                    } catch (e: CancellationException) {
                        AppLogger.info(TAG_STREAM, "retryLast runAgentLoop CANCELLED")
                        Log.d(TAG, "Agent loop cancelled")
                    } catch (e: Exception) {
                        AppLogger.error(TAG_STREAM, "retryLast runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                        Log.e(TAG, "Agent loop error (retryLast)", e)
                        setInlineError(e.message ?: "Unknown error")
                        SessionActivityTracker.markStreamError(activeSessionId)
                    } finally {
                        AppLogger.info(TAG_STREAM, "retryLast streamJob FINALLY enter")
                        publishOverlayReplyExcerpt(activeSessionId)
                        markRunOutcomeForNotification(activeSessionId)
                        SessionActivityTracker.setInactive(activeSessionId)
                        if (holdsSlot) SessionConcurrencyManager.releaseSlot(activeSessionId)
                        AppLogger.info(TAG_STREAM, "retryLast streamJob FINALLY exit")
                    }
                } catch (e: CancellationException) {
                    AppLogger.info(TAG_STREAM, "retryLast streamJob CANCELLED waiting for slot")
                    Log.d(TAG, "Cancelled while waiting for concurrency slot")
                }
                if (streamJob === coroutineContext[Job]) {
                    AppLogger.info(TAG_STREAM, "retryLast _isStreaming=false (about to set)")
                    _isStreaming.value = false
                } else {
                    AppLogger.info(TAG_STREAM, "retryLast _isStreaming SKIPPED (stale job)")
                }
                AppLogger.info(TAG_STREAM, "retryLast streamJob EXIT")
            }
            } finally {
                if (!streamLaunched) {
                    AppLogger.info(TAG_STREAM, "retryLast _isStreaming=false (setup aborted)")
                    _isStreaming.value = false
                }
            }
        }
    }

    private fun sanitizeAgentHistory() {
        var i = 0
        while (i < agentHistory.size) {
            val msg = agentHistory[i]
            if (msg.role != LLMMessage.Role.ASSISTANT) { i++; continue }

            val toolUses = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
            if (toolUses.isEmpty()) { i++; continue }

            val toolUseIds = toolUses.map { com.openminis.app.provider.ToolPairing.key(it.id) }.toSet()

            val next = agentHistory.getOrNull(i + 1)
            val nextResultIds = next?.contentParts
                ?.filterIsInstance<AgentContentPart.ToolResult>()
                ?.map { com.openminis.app.provider.ToolPairing.key(it.id) }?.toSet() ?: emptySet()

            val missingIds = toolUseIds - nextResultIds
            if (missingIds.isEmpty()) { i++; continue }

            val placeholders = toolUses.filter { com.openminis.app.provider.ToolPairing.key(it.id) in missingIds }.map { use ->
                AgentContentPart.ToolResult(
                    id = use.id, name = use.name,
                    content = "Tool execution was interrupted by an unexpected error.",
                    isError = true,
                )
            }
            Log.w(TAG, "sanitize: injecting ${placeholders.size} placeholder tool_result(s) after history[$i]")

            if (next != null && next.role == LLMMessage.Role.USER &&
                next.contentParts.any { it is AgentContentPart.ToolResult }) {
                agentHistory[i + 1] = next.copy(
                    contentParts = next.contentParts + placeholders
                )
            } else {
                agentHistory.add(i + 1, LLMMessage(
                    role = LLMMessage.Role.USER, content = "",
                    contentParts = placeholders,
                ))
            }
            i++
        }

        val allToolUseIds = agentHistory.flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolUse>().map { com.openminis.app.provider.ToolPairing.key(it.id) }.toSet()
        val iter = agentHistory.listIterator()
        while (iter.hasNext()) {
            val msg = iter.next()
            if (msg.role != LLMMessage.Role.USER) continue
            val cleaned = msg.contentParts.filter { part ->
                part !is AgentContentPart.ToolResult || com.openminis.app.provider.ToolPairing.key(part.id) in allToolUseIds
            }
            if (cleaned.isEmpty() && msg.content.isBlank()) {
                iter.remove()
            } else if (cleaned.size < msg.contentParts.size) {
                iter.set(msg.copy(contentParts = cleaned))
            }
        }
    }

    private fun unwrapFlowException(e: Throwable): Throwable {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is com.openminis.app.data.model.LLMError) return cause
            cause = cause.cause
        }
        return e
    }

    private fun dynamicMaxTokens(provider: LLMProvider, lastContextTokens: Int = 0): Int {
        val model = currentModel ?: return minOf(GLOBAL_MAX_TOKENS_CEILING, provider.defaultMaxOutputTokens)
        val maxOutputCeiling = minOf(GLOBAL_MAX_TOKENS_CEILING, provider.effectiveMaxOutputTokens(model))
        val contextWindow = effectiveContextWindowTokens()?.takeIf { it > 0 }
            ?: model.contextWindowTokens
        if (contextWindow <= 0) return maxOutputCeiling
        val inputTokens = if (lastContextTokens > 0) lastContextTokens else 0
        val remaining = contextWindow - inputTokens
        if (remaining <= 0) {
            android.util.Log.w(
                TAG,
                "dynamicMaxTokens: input $inputTokens EXCEEDS window $contextWindow " +
                    "(over by ${inputTokens - contextWindow}) — compaction guard should have fired",
            )
        }
        val clamped = maxOf(remaining, MIN_MAX_TOKENS)
        val result = minOf(maxOutputCeiling, clamped)
        if (result < maxOutputCeiling) {
            android.util.Log.i(TAG, "dynamicMaxTokens: $result (remaining=$remaining, ceiling=$maxOutputCeiling, window=$contextWindow, input=$inputTokens, model=${model.id})")
        }
        return result
    }


    private fun estimateContextTokens(): Int {
        var totalChars = 0
        var imageTokens = 0
        for (msg in agentHistory) {
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> totalChars += part.text.length
                    is AgentContentPart.ToolUse -> totalChars += part.input.toString().length
                    is AgentContentPart.ToolResult -> {
                        totalChars += part.content.length
                        part.imageData?.let { imageTokens += BPETokenizer.countImageTokens(it) }
                    }
                    is AgentContentPart.ImageData -> {
                        imageTokens += BPETokenizer.countImageTokens(part.data)
                    }
                }
            }
        }
        return (totalChars / 3.5).toInt() + imageTokens
    }

    private fun countPartTokens(part: AgentContentPart): Int = when (part) {
        is AgentContentPart.Text -> BPETokenizer.countTokens(part.text)
        is AgentContentPart.ToolUse -> BPETokenizer.countTokens(part.input.toString())
        is AgentContentPart.ToolResult -> {
            BPETokenizer.countTokens(part.content) +
                (part.imageData?.let { BPETokenizer.countImageTokens(it) } ?: 0)
        }
        is AgentContentPart.ImageData -> BPETokenizer.countImageTokens(part.data)
    }

    private fun offloadScanStartIndex(): Int {
        if (_compactSummary.value.isNullOrBlank()) return 0
        val marker = _cachedLatestMarker ?: return 0
        if (marker.version < 2) return 0
        val anchorId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() } ?: return 0
        val anchorIdx = agentHistory.indexOfLast { it.dbMessageId == anchorId }
        return if (anchorIdx < 0) 0 else anchorIdx + 1
    }

    private fun offloadWarmUpScanPlan(): Pair<List<Int>, Set<String>> {
        val none = emptyList<Int>() to emptySet<String>()
        if (_compactSummary.value.isNullOrBlank()) return none
        val marker = _cachedLatestMarker ?: return none
        if (marker.version < 2) return none
        val anchorId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() } ?: return none
        val anchorIdx = agentHistory.indexOfLast { it.dbMessageId == anchorId }
        if (anchorIdx < 0) return none
        val priorIdx = walkBackUserTurnsBounded(
            anchorIdx = anchorIdx,
            maxUserTextTurns = COMPACT_KEEP_RECENT_USER_TURNS,
            maxMessages = 100,
        ).priorIdx ?: return none
        if (priorIdx > anchorIdx) return none
        return warmUpScanIndices(agentHistory, priorIdx, anchorIdx, warmUpDropByMarker[marker.id])
    }

    private data class OffloadCandidate(
        val msgIdx: Int,
        val partIdx: Int,
        val tokens: Int,
        val bytes: Int,
        val toolId: String,
        val toolName: String,
    )

    private fun offloadContextIfNeeded(
        contextWindow: Int,
        lastContextTokens: Int,
        force: Boolean = false,
    ) {
        val sid = activeSessionId
        val policy = currentContextPolicy()?.takeIf { it.second == contextWindow }?.first
            ?: ContextPolicy.forContextWindow(contextWindow)

        if (!force && policy.offloadThreshold == 0) {
            return
        }

        val effectiveTokens =
            if (lastContextTokens > 0) lastContextTokens else estimateContextTokens()

        if (!force && effectiveTokens < policy.offloadThreshold) {
            return
        }

        val targetTokens = if (force) 0 else policy.offloadTarget
        val beforeTokens = effectiveTokens
        var currentTokens = effectiveTokens
        val pct = (effectiveTokens.toLong() * 100 / contextWindow.coerceAtLeast(1)).toInt()
        val remaining = contextWindow - beforeTokens

        AppLogger.info(TAG, "━━━ Context Offload Triggered ━━━")
        AppLogger.info(TAG, "  Window: $contextWindow tokens")
        AppLogger.info(TAG, "  Before: $beforeTokens tokens ($pct% of window, ~$remaining remaining)")
        if (force) {
            AppLogger.info(TAG, "  Mode: FORCE — offloading all eligible candidates")
        } else {
            AppLogger.info(TAG, "  Threshold: ${policy.offloadThreshold} → Target: $targetTokens")
            AppLogger.info(TAG, "  Need to free: ~${beforeTokens - targetTokens} tokens")
        }
        AppLogger.info(TAG, "  Agent history: ${agentHistory.size} messages")

        val protectedCount = minOf(4, agentHistory.size)
        val candidateUpper = agentHistory.size - protectedCount
        val scanStart = minOf(offloadScanStartIndex(), candidateUpper)
        AppLogger.info(
            TAG,
            "  Scanning messages $scanStart..<$candidateUpper (last $protectedCount protected, " +
                "$scanStart before the compaction anchor skipped)",
        )
        val calibrationRatio = contextCalibrationRatio()

        val candidates = mutableListOf<OffloadCandidate>()
        var skippedAlreadyOffloaded = 0
        var skippedTooSmall = 0
        var skippedUnanswered = 0
        val warmUp = offloadWarmUpScanPlan()
        val warmUpIndices = warmUp.first.filter { it < scanStart }
        val answeredFrom = minOf(warmUpIndices.firstOrNull() ?: scanStart, scanStart)
        if (warmUpIndices.isNotEmpty()) {
            AppLogger.info(
                TAG,
                "  Warm-up before the anchor: scanning ${warmUpIndices.size} sent message(s) " +
                    "(${warmUp.second.size} pruned tool id(s) skipped)",
            )
        }
        val answeredToolUseIds: Set<String> = buildSet {
            for (m in agentHistory.subList(answeredFrom, agentHistory.size)) {
                for (pt in m.contentParts) {
                    if (pt is AgentContentPart.ToolResult) add(pt.id)
                }
            }
        }

        for (msgIdx in warmUpIndices + (scanStart until candidateUpper)) {
            val msg = agentHistory[msgIdx]
            val inWarmUp = msgIdx < scanStart
            for ((partIdx, part) in msg.contentParts.withIndex()) {
                if (inWarmUp) {
                    val prunedId = when (part) {
                        is AgentContentPart.ToolUse -> part.id
                        is AgentContentPart.ToolResult -> part.id
                        else -> null
                    }
                    if (prunedId != null && prunedId in warmUp.second) continue
                }
                when (part) {
                    is AgentContentPart.ToolResult -> {
                        if (ContextOffload.isOffloadPlaceholder(part.content)) {
                            skippedAlreadyOffloaded++
                            continue
                        }
                        val hasLargeContent = part.content.length > 500
                        val hasLargeImage = (part.imageData?.size ?: 0) > 1024
                        if (!hasLargeContent && !hasLargeImage) {
                            skippedTooSmall++
                            continue
                        }
                        val tokens = ContextSizeMeter.estimateTokens(part)
                        val bytes = part.content.toByteArray(Charsets.UTF_8).size +
                            (part.imageData?.size ?: 0)
                        candidates.add(OffloadCandidate(msgIdx, partIdx, tokens, bytes, part.id, part.name))
                    }
                    is AgentContentPart.ToolUse -> {
                        if (part.name != "file_write" && part.name != "file_edit") continue
                        val content = part.input.optString("content", "")

                        if (part.isOffloadedArgument ||
                            ContextOffload.isOffloadPlaceholder(content)
                        ) {
                            skippedAlreadyOffloaded++
                            continue
                        }

                        if (part.id !in answeredToolUseIds) {
                            skippedUnanswered++
                            continue
                        }

                        if (content.length <= 500) continue
                        val tokens = ContextSizeMeter.estimateTokens(part)
                        val bytes = content.toByteArray(Charsets.UTF_8).size
                        candidates.add(OffloadCandidate(msgIdx, partIdx, tokens, bytes, part.id, part.name))
                    }
                    is AgentContentPart.ImageData -> {
                        if (part.data.size <= 1024) {
                            skippedTooSmall++
                            continue
                        }
                        val tokens = ContextSizeMeter.estimateTokens(part)
                        val synthId = "img${msgIdx}_$partIdx"
                        candidates.add(OffloadCandidate(msgIdx, partIdx, tokens, part.data.size, synthId, "image"))
                    }
                    is AgentContentPart.Text -> Unit
                }
            }
        }

        candidates.sortByDescending { it.tokens }
        val totalCandidateTokens = candidates.sumOf { it.tokens }
        AppLogger.info(TAG, "  Candidates: ${candidates.size} parts (~$totalCandidateTokens est. tokens total)")
        AppLogger.info(
            TAG,
            "  Skipped: $skippedAlreadyOffloaded already offloaded, $skippedTooSmall too small, " +
                "$skippedUnanswered unanswered tool calls",
        )

        var offloadedCount = 0
        var freedTokens = 0

        for (candidate in candidates) {
            if (currentTokens <= targetTokens) break
            val candidateTokens = ContextSizeMeter.calibrated(candidate.tokens, calibrationRatio)

            val msg = agentHistory[candidate.msgIdx]
            val parts = msg.contentParts.toMutableList()
            val part = parts[candidate.partIdx]
            var linuxPath = ""

            val newPart: AgentContentPart? = when (part) {
                is AgentContentPart.ToolResult -> {
                    val readbackPath = if (part.name == "file_read") {
                        ContextOffload.offloadReadbackPath(part.content)
                    } else {
                        null
                    }
                    if (readbackPath != null) {
                        linuxPath = readbackPath
                        AppLogger.info(
                            TAG,
                            "  offload readback: reusing existing $readbackPath " +
                                "(no duplicate file written)",
                        )
                    } else if (part.content.length > 500) {
                        linuxPath = ContextOffload.offloadContent(
                            context, sid, part.content,
                            toolId = part.id, toolName = part.name,
                        )
                    }
                    val imgPath = part.imageData?.let { data ->
                        if (data.size > 1024) {
                            ContextOffload.offloadImage(
                                context, sid, data,
                                toolId = part.id,
                                mimeType = part.imageMimeType ?: "image/png",
                            )
                        } else ""
                    } ?: ""
                    if (linuxPath.isEmpty()) linuxPath = imgPath
                    val stub = ContextOffload.stub(candidateTokens, candidate.bytes, linuxPath)
                    part.copy(content = stub, imageData = null, imageMimeType = null)
                }
                is AgentContentPart.ToolUse -> {
                    val content = part.input.optString("content", "")
                    linuxPath = ContextOffload.offloadContent(
                        context, sid, content,
                        toolId = part.id, toolName = part.name,
                    )
                    val newInput = org.json.JSONObject(part.input.toString())
                    newInput.put(
                        "content",
                        ContextOffload.prunedArgumentNotice(
                            candidateTokens, candidate.bytes, linuxPath,
                        ),
                    )
                    part.copy(input = newInput, isOffloadedArgument = true)
                }
                is AgentContentPart.ImageData -> {
                    linuxPath = ContextOffload.offloadImage(
                        context, sid, part.data,
                        toolId = candidate.toolId,
                        mimeType = part.mimeType,
                    )
                    AgentContentPart.Text(
                        ContextOffload.stub(candidateTokens, candidate.bytes, linuxPath),
                    )
                }
                is AgentContentPart.Text -> null
            }

            if (newPart == null) continue
            parts[candidate.partIdx] = newPart
            agentHistory[candidate.msgIdx] = msg.copy(contentParts = parts)

            currentTokens -= candidateTokens
            freedTokens += candidateTokens
            offloadedCount++
            val afterPct = (currentTokens.toLong() * 100 / contextWindow.coerceAtLeast(1)).toInt()
            AppLogger.info(
                TAG,
                "  ✂ Offloaded #$offloadedCount: [${candidate.toolName}] id:${candidate.toolId.take(8)} ~$candidateTokens tokens (${candidate.bytes} bytes) → $linuxPath [now $currentTokens ($afterPct%)]",
            )
        }

        if (offloadedCount > 0) {
            val afterPct = (currentTokens.toLong() * 100 / contextWindow.coerceAtLeast(1)).toInt()
            AppLogger.info(TAG, "━━━ Context Offload Complete ━━━")
            AppLogger.info(TAG, "  Parts offloaded: $offloadedCount")
            AppLogger.info(TAG, "  Tokens freed: ~$freedTokens")
            AppLogger.info(TAG, "  Before: $beforeTokens/$contextWindow ($pct%)")
            AppLogger.info(TAG, "  After:  $currentTokens/$contextWindow ($afterPct%)")
            AppLogger.info(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }
    }

    private fun scriptedTurnFor(
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall>,
    ): com.openminis.app.scheduled.ScriptedToolTurn? {
        if (prefill.isEmpty()) return null
        val available = agentTools.map { it.name }.toSet()
        val turn = com.openminis.app.scheduled.ScriptedToolTurn.from(prefill, available)
        if (turn == null || turn.calls.size < prefill.size) {
            AppLogger.warning(
                TAG_STREAM,
                "[ScheduledPrefill] dropped ${prefill.size - (turn?.calls?.size ?: 0)} of ${prefill.size} " +
                    "prefilled call(s) [${prefill.joinToString { it.toolName }}] — tool unavailable or invalid; " +
                    "the model decides for itself",
            )
        }
        return turn
    }

    private suspend fun runAgentLoop(
        provider: LLMProvider,
        systemPrompt: String?,
        fallbackProviders: List<FallbackCandidate> = emptyList(),
        fallbackStrategy: com.openminis.app.data.model.FallbackStrategy = com.openminis.app.data.model.FallbackStrategy.default,
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
    ) {
        val turnGen = liveTurn.begin()
        try {
            runAgentLoopBody(provider, systemPrompt, fallbackProviders, fallbackStrategy, prefill, turnGen)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            AppLogger.info(TAG_STREAM, "[stop-diag] runAgentLoop EXIT reason=cancelled sid=$activeSessionId cause=${ce.message}")
            throw ce
        } catch (t: Throwable) {
            AppLogger.warning(TAG_STREAM, "[stop-diag] runAgentLoop EXIT reason=error sid=$activeSessionId error=${t.javaClass.simpleName}: ${t.message?.take(200)}")
            throw t
        } finally {
            liveTurn.end(turnGen)
            com.openminis.app.agent.userask.UserAskCenter.releaseUnstoredDeferred(activeSessionId)
        }
    }

    private suspend fun runAgentLoopBody(
        provider: LLMProvider,
        systemPrompt: String?,
        fallbackProviders: List<FallbackCandidate>,
        fallbackStrategy: com.openminis.app.data.model.FallbackStrategy,
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall>,
        turnGen: Long,
    ) {
        AppLogger.info(TAG_STREAM, "runAgentLoop ENTER provider=${provider.javaClass.simpleName} historySize=${agentHistory.size}")
        runCatching {
            var chars = 0L
            var maxOne = 0
            var toolResults = 0
            var images = 0
            var imageBytes = 0L
            var audioChars = 0L
            var biggestRole = ""
            for (m in agentHistory) {
                var perMsg = m.content.length
                for (p in m.contentParts) {
                    when (p) {
                        is AgentContentPart.ToolResult -> {
                            perMsg += p.content.length
                            toolResults++
                            p.imageData?.let { images++; imageBytes += it.size }
                        }
                        is AgentContentPart.Text -> perMsg += p.text.length
                        is AgentContentPart.ImageData -> { images++; imageBytes += p.data.size }
                        else -> {}
                    }
                }
                for (a in m.audioParts) audioChars += a.base64Data.length
                images += m.imageParts.size
                chars += perMsg
                if (perMsg > maxOne) { maxOne = perMsg; biggestRole = m.role.name }
            }
            AppLogger.info(
                TAG_STREAM,
                "[CtxShape] historySize=${agentHistory.size} totalChars=$chars " +
                    "maxMsgChars=$maxOne maxMsgRole=$biggestRole toolResultParts=$toolResults " +
                    "imageParts=$images imageBytes=$imageBytes audioB64Chars=$audioChars " +
                    "approxTokens=${chars / 4} " +
                    "${com.openminis.app.diagnostics.MemorySnapshot.capture().toLogString()}",
            )
        }
        var assistantId = "assistant_${System.currentTimeMillis()}"
        liveTurn.markAssistant(turnGen, assistantId)
        val allToolBlocks = java.util.Collections.synchronizedList(mutableListOf<AssistantBlock>())
        val toolInputChunkRings: MutableMap<String, MutableList<String>> =
            java.util.concurrent.ConcurrentHashMap()
        var accumulatedText = ""
        var lastContextTokens = 0

        var lastUiUpdateMs = 0L
        var lastFlushedLen = 0
        val pendingChunkSb = StringBuilder()
        var lastFileToolInputMs = 0L
        var lastOtherToolInputMs = 0L
        fun textDeltaThrottleMs(len: Int): Long = when {
            len < 500     -> 150L
            len < 2_000   -> 300L
            len < 32_000  -> 500L
            len < 64_000  -> 1_000L
            len < 128_000 -> 1_500L
            else          -> 2_000L
        }

        var loopSystemPrompt = systemPrompt
        withContext(Dispatchers.Main) {
            if (this@ChatViewModel.currentProvider === provider) pendingModelSwitch = false
        }

        var currentProvider = provider
        var classProviderAtSend = this@ChatViewModel.currentProvider
        val remainingFallbacks = fallbackProviders.toMutableList()
        var loopFallbackStrategy = fallbackStrategy
        val fallbackReasons = mutableListOf<String>()
        val triedEntryIds = mutableSetOf<String>()
        _activeEntryId.value?.let { triedEntryIds.add(it) }
        val emptyStreakEntryIds = mutableSetOf<String>()
        var currentIdentity = providerIdentity(_activeEntryId.value)
        var hasAttemptedOverflowSelfHeal = false

        val allToolInputs = mutableMapOf<String, String>()

        val turnThinkingLevel = _thinkingLevel.value
        withContext(Dispatchers.Main) {
            _messages.value = _messages.value + ChatMessage(
                id = assistantId, role = "assistant", content = "", isStreaming = true,
                isAwaitingModelResponse = true,
                thinkingLevel = turnThinkingLevel,
            )
        }

        var loopExitedNormally = false
        var stopDiagExit = "turn-cap"
        var inLoopCompactions = 0
        lastInLoopCompactionMadeNoProgress = false
        var didInjectEmptyToolReminder = false
        var dsmlLeakRetries = 0
        // [T-android-scheduled-resume-nudge] Set when a scheduled fire was
        var scheduledResumeNudgeOwed = false
        var didStopStaleReadAloud = false
        val turnCap = helperConfig?.maxTurns ?: MAX_AGENT_TURNS
        var helperWrapUpInjected = false
        var helperTurnWarningInjected = false
        var helperClosingNudgeSent = false
        var pendingScriptedTurn: com.openminis.app.scheduled.ScriptedToolTurn? = scriptedTurnFor(prefill)
        for (turn in 0 until turnCap) {
            AppLogger.info(TAG_STREAM, "[stop-diag] turn=$turn START sid=$activeSessionId — requesting the model")
            if (pendingModelSwitch) {
                val switched = withContext(Dispatchers.Main) {
                    val c = this@ChatViewModel.currentProvider
                    if (c != null && modelSwitchToApply(pendingModelSwitch, c, currentProvider)) {
                        pendingModelSwitch = false
                        val strategy = _selectedGroupId.value
                            ?.let { gid -> providerRepository.config.value.modelGroups.find { it.id == gid }?.fallbackStrategy }
                            ?: com.openminis.app.data.model.FallbackStrategy.default
                        Triple(c, buildFallbackProviders(c), strategy)
                    } else null
                }
                val chosen = switched?.first
                if (switched != null && chosen != null) {
                    remainingFallbacks.clear()
                    remainingFallbacks.addAll(switched.second)
                    loopFallbackStrategy = switched.third
                    val from = currentProvider.model.displayName
                    currentProvider = chosen
                    classProviderAtSend = chosen
                    currentIdentity = providerIdentity(_activeEntryId.value)
                    // A new model starts a new failure streak.
                    triedEntryIds.clear()
                    _activeEntryId.value?.let { triedEntryIds.add(it) }
                    emptyStreakEntryIds.clear()
                    loopSystemPrompt = systemPromptFor(chosen, loopSystemPrompt)
                    AppLogger.info(
                        TAG_STREAM,
                        "[SwitchModel] applied at next request: $from → ${chosen.model.displayName} turn=${turn + 1}",
                    )
                }
            }
            applyPendingHistoryPatches()
            sanitizeAgentHistory()
            if (helperConfig != null && !helperWrapUpInjected && (helperWrapUpRequested || turn == turnCap - 1)) {
                helperWrapUpInjected = true
                val reason = if (helperWrapUpRequested) com.openminis.app.agent.jobs.HelperWrapUpReason.BUDGET else com.openminis.app.agent.jobs.HelperWrapUpReason.TURNS
                val note = com.openminis.app.agent.jobs.HelperRunner.wrapUpPrompt(reason)
                val lastIdx = agentHistory.lastIndex
                if (lastIdx >= 0 && agentHistory[lastIdx].role == LLMMessage.Role.USER) {
                    val last = agentHistory[lastIdx]
                    agentHistory[lastIdx] = if (last.contentParts.isEmpty()) last.copy(content = (last.content + "\n\n" + note).trim())
                    else last.copy(contentParts = last.contentParts + AgentContentPart.Text(note))
                } else {
                    agentHistory.add(LLMMessage(role = LLMMessage.Role.USER, content = note))
                }
                AppLogger.info(TAG, "[delegate_task] wrap-up turn injected reason=$reason turn=${turn + 1}/$turnCap")
            }
            if (helperConfig != null && !helperWrapUpInjected && !helperTurnWarningInjected) {
                val remaining = turnCap - 1 - turn
                if (com.openminis.app.agent.jobs.HelperRunner.shouldWarn(remaining, turnCap)) {
                    helperTurnWarningInjected = true
                    val note = com.openminis.app.agent.jobs.HelperRunner.turnBudgetWarning(remaining)
                    val lastIdx = agentHistory.lastIndex
                    if (lastIdx >= 0 && agentHistory[lastIdx].role == LLMMessage.Role.USER) {
                        val last = agentHistory[lastIdx]
                        agentHistory[lastIdx] = if (last.contentParts.isEmpty()) last.copy(content = (last.content + "\n\n" + note).trim())
                        else last.copy(contentParts = last.contentParts + AgentContentPart.Text(note))
                    } else {
                        agentHistory.add(LLMMessage(role = LLMMessage.Role.USER, content = note))
                    }
                    AppLogger.info(TAG, "[subagent_task] turn budget warning injected remaining=$remaining turn=${turn + 1}/$turnCap")
                }
            }
            if (helperConfig != null) {
                val steers = synchronized(pendingSteerMessages) {
                    val out = pendingSteerMessages.toList()
                    pendingSteerMessages.clear()
                    out
                }
                if (steers.isNotEmpty()) {
                    val note = steers.joinToString("\n") {
                        "[Course correction from the delegating agent] $it"
                    }
                    val steerRow = runCatching {
                        chatRepository.appendMessage(
                            activeSessionId, "user",
                            """[{"type":"text","value":${escapeJson(note)}}]""",
                        )
                    }.onFailure {
                        AppLogger.warning(TAG, "[subagent_task] steer persist failed: ${it.message}")
                    }.getOrNull()
                    agentHistory.add(LLMMessage(role = LLMMessage.Role.USER, content = note, dbMessageId = steerRow?.id))
                    val steerUi = ChatMessage(
                        id = steerRow?.id ?: "steer_${System.currentTimeMillis()}",
                        role = "user",
                        content = note,
                    )
                    withContext(Dispatchers.Main) {
                        val cur = _messages.value
                        val at = cur.indexOfLast { it.id == assistantId }
                        _messages.value = if (at >= 0) {
                            cur.subList(0, at) + steerUi + cur.subList(at, cur.size)
                        } else {
                            cur + steerUi
                        }
                    }
                    AppLogger.info(TAG, "[subagent_task] steer delivered count=${steers.size} turn=${turn + 1}/$turnCap")
                }
            }

            contextFixedTokens = ContextSizeMeter.estimateFixedTokens(
                loopSystemPrompt,
                if (helperWrapUpInjected) emptyList() else agentTools,
            )
            effectiveContextWindowTokens()?.takeIf { it > 0 }?.let { window ->
                offloadContextIfNeeded(
                    contextWindow = window,
                    lastContextTokens = measureOutboundContextTokens(),
                )
            }

            when (inLoopContextCheck(inLoopCompactions)) {
                InLoopContextAction.PROCEED -> {}
                InLoopContextAction.COMPACTED -> {
                    inLoopCompactions++

                    val sealedId = assistantId
                    val freshAssistantId = "assistant_${System.currentTimeMillis()}"
                    withContext(Dispatchers.Main) {
                        updateAssistantMessage(
                            sealedId,
                            accumulatedText,
                            false,
                            allToolBlocks,
                            isAwaitingModelResponse = false,
                        )
                        val sealed = _messages.value.firstOrNull { it.id == sealedId }
                        if (sealed != null &&
                            sealed.content.isEmpty() &&
                            sealed.toolBlocks.isEmpty()
                        ) {
                            _messages.value = _messages.value.filterNot { it.id == sealedId }
                            AppLogger.info(
                                TAG,
                                "[Compact] in-loop: dropped empty sealed bubble $sealedId",
                            )
                        }
                        _messages.value = _messages.value + ChatMessage(
                            id = freshAssistantId,
                            role = "assistant",
                            content = "",
                            isStreaming = true,
                            isAwaitingModelResponse = true,
                            thinkingLevel = turnThinkingLevel,
                        )
                    }
                    clearStreamFlushState(sealedId)
                    if (_streamingById.value.containsKey(sealedId)) {
                        _streamingById.value = _streamingById.value - sealedId
                    }
                    assistantId = freshAssistantId
                    liveTurn.markAssistant(turnGen, assistantId)
                    accumulatedText = ""
                    allToolBlocks.clear()
                    allToolInputs.clear()
                    toolInputChunkRings.clear()
                    AppLogger.info(
                        TAG,
                        "[Compact] in-loop: sealed $sealedId, continuing in $freshAssistantId below the divider",
                    )
                    continue
                }
                InLoopContextAction.STOP -> {
                    AppLogger.warning(
                        TAG,
                        "[AutoCompact] stopping turn: context exhausted and compaction cannot recover",
                    )
                    withContext(Dispatchers.Main) {
                        updateAssistantMessage(
                            assistantId, accumulatedText, false, allToolBlocks,
                            isAwaitingModelResponse = false,
                        )
                        clearStreamFlushState(assistantId)
                        if (_streamingById.value.containsKey(assistantId)) {
                            _streamingById.value = _streamingById.value - assistantId
                        }
                    }
                    appendSystemInfo(
                        text = "Context is full and could not be reduced further. " +
                            "Tap Continue to resume, or start a new chat.",
                        iconKind = "compact",
                    )
                    markLiveInterruption()
                    _canResume.value = true
                    stopDiagExit = "context-full"
                    loopExitedNormally = true
                    break
                }
            }

            val turnStartBlockIndex = allToolBlocks.size
            val turnTextSb = StringBuilder()
            var currentTextBlockSb: StringBuilder? = null
            var turnTextBlockIdx = -1
            var loggedPostToolTextMerge = false
            fun materializeActiveTextBlock() {
                val sb = currentTextBlockSb ?: return
                val idx = if (currentProvider.streamTextIsMonolithic) turnTextBlockIdx else allToolBlocks.lastIndex
                if (idx >= 0 && idx < allToolBlocks.size && allToolBlocks[idx].kind == "text") {
                    allToolBlocks[idx] = allToolBlocks[idx].copy(content = sb.toString())
                }
            }
            val turnThinking = StringBuilder()
            var turnReasoningBlob: String? = null
            var turnFinishReason: String? = null
            var turnRefusalCategory: String? = null
            var turnRefusalExplanation: String? = null
            var lastUsage: LLMUsage? = null
            var attemptUsage: LLMUsage? = null
            var turnStreamMs = 0L
            val toolCalls = mutableListOf<Triple<String, String, JSONObject>>()
            val toolCallSignatures = mutableMapOf<String, String>()

            val dedupeStartCounts = mutableMapOf<String, Int>()
            val dedupeCompleteCounts = mutableMapOf<String, Int>()
            val inFlightRenamedId = mutableMapOf<String, String>()
            fun dedupeToolStartId(raw: String): String {
                val n = (dedupeStartCounts[raw] ?: 0) + 1
                dedupeStartCounts[raw] = n
                val renamed = if (n == 1) raw else "$raw-$n"
                if (n > 1) {
                    AppLogger.warning(TAG_STREAM, "[ToolDedupe] duplicate tool_call id on stream start: '$raw' #$n -> renamed '$renamed'")
                }
                inFlightRenamedId[raw] = renamed
                return renamed
            }
            fun dedupeToolInputId(raw: String): String =
                inFlightRenamedId[raw] ?: raw
            fun dedupeToolCompleteId(raw: String): String {
                val n = (dedupeCompleteCounts[raw] ?: 0) + 1
                dedupeCompleteCounts[raw] = n
                return if (n == 1) raw else "$raw-$n"
            }

            var collectDone = false
            var retryAttempt = 0
            var callbackReminder: QueuedCallbackReminder.Plan? = null
            while (!collectDone) {
                val liveProblem = currentIdentity?.let { id ->
                    val inst = providerRepository.instance(id.instanceId)
                    ProviderAvailability.problem(instanceExists = inst != null, instanceEnabled = inst?.isEnabled == true)
                }
                if (liveProblem != null) {
                    val id = currentIdentity!!
                    AppLogger.warning(
                        TAG_STREAM,
                        "[ProviderLiveCheck] ${id.modelName} (${id.instanceLabel}) $liveProblem — " +
                            "group=${_selectedGroupId.value != null}",
                    )
                    if (_selectedGroupId.value == null) {
                        throw com.openminis.app.data.model.LLMError.ProviderError(
                            when (liveProblem) {
                                ProviderAvailability.Problem.REMOVED ->
                                    context.getString(R.string.provider_live_check_removed_message, id.instanceLabel, id.modelName)
                                ProviderAvailability.Problem.DISABLED ->
                                    context.getString(R.string.provider_live_check_disabled_message, id.instanceLabel, id.modelName)
                            },
                        )
                    }
                }
                try {
                    if (liveProblem != null) {
                        throw com.openminis.app.data.model.LLMError.ProviderError(
                            context.getString(
                                if (liveProblem == ProviderAvailability.Problem.REMOVED) R.string.provider_live_check_removed
                                else R.string.provider_live_check_disabled,
                            ),
                        )
                    }
                    (currentProvider as? com.openminis.app.provider.anthropic.AnthropicProvider)
                        ?.enhancedCache = _enhancedCacheEnabled.value
                    val streamStartMs = android.os.SystemClock.elapsedRealtime()
                    val firstChunkSeen = java.util.concurrent.atomic.AtomicBoolean(false)
                    val diagSid = activeSessionId
                    println(
                        "[T-STALL-DIAG] stream REQUEST-OUT sid=$diagSid turn=$turn " +
                            "provider=${currentProvider.javaClass.simpleName} " +
                            "historySize=${agentHistory.size}",
                    )
                    val firstChunkWatchdog = viewModelScope.launch(Dispatchers.IO) {
                        var waited = 0L
                        while (!firstChunkSeen.get()) {
                            kotlinx.coroutines.delay(10_000L)
                            if (firstChunkSeen.get()) break
                            waited += 10_000L
                            println(
                                "[T-STALL-DIAG] stream NO-FIRST-CHUNK sid=$diagSid turn=$turn " +
                                    "waitedMs=$waited provider=${currentProvider.javaClass.simpleName} " +
                                    "— request sent, provider has returned NOTHING (not even message_start)",
                            )
                        }
                    }
                    try {
                    val scriptedTurn = pendingScriptedTurn?.takeUnless { helperWrapUpInjected }
                    pendingScriptedTurn = null
                    val turnChunks: kotlinx.coroutines.flow.Flow<LLMStreamChunk> = if (scriptedTurn != null) {
                        AppLogger.info(
                            TAG_STREAM,
                            "[ScheduledPrefill] turn=$turn streaming ${scriptedTurn.calls.size} prefilled tool call(s) " +
                                "[${scriptedTurn.calls.joinToString { it.toolName + ":" + it.id }}] in place of a model request",
                        )
                        kotlinx.coroutines.flow.flowOf(*scriptedTurn.asStreamChunks().toTypedArray())
                    } else {
                        if (personaReminderIsDue(lastContextTokens)) {
                            appendPersonaReminderToHistory(lastContextTokens)
                        }
                        var dispatchInputTokens = 0
                        val requestContext = RequestContextBuilder.build(requestContextInput()) { outboundHistory ->
                            dispatchInputTokens = recordContextDispatch(outboundHistory, currentProvider.model)
                            AppLogger.info(
                                TAG,
                                "[CtxMeter] dispatch model=${currentModel?.id ?: "?"} estimate=$lastDispatchEstimate " +
                                    "ratio=${"%.3f".format(lastDispatchRatio)} predicted=$dispatchInputTokens " +
                                    "maxTokens=${dynamicMaxTokens(currentProvider, dispatchInputTokens)}",
                            )
                        }
                        var requestMessages = requestContext.messages
                        if (!helperWrapUpInjected) {
                            val reminder = callbackReminder ?: QueuedCallbackReminder.plan(_promptQueue.value)
                            val withReminder = reminder?.let { QueuedCallbackReminder.inject(requestMessages, it.text) }
                            if (reminder != null && withReminder != null) {
                                AppLogger.info(
                                    TAG_STREAM,
                                    "[CallbackReminder] turn=$turn request carries a reminder for ${reminder.promptIds.size} queued " +
                                        "sub agent result(s)${if (callbackReminder != null) " (retry, same text)" else ""}",
                                )
                                callbackReminder = reminder
                                requestMessages = withReminder
                            }
                        }
                        currentProvider.streamMessage(
                            requestMessages,
                            loopSystemPrompt, dynamicMaxTokens(currentProvider, dispatchInputTokens),
                            tools = if (helperWrapUpInjected) emptyList() else agentTools,
                            thinkingLevel = if (currentProvider.model.supportsReasoning == true) _thinkingLevel.value else ThinkingLevel.OFF,
                        )
                    }
                    attemptUsage = null
                    turnChunks.collect { chunk ->
                if (firstChunkSeen.compareAndSet(false, true)) {
                    callbackReminder?.let { sent ->
                        _promptQueue.update { QueuedCallbackReminder.markSent(it, sent) }
                        AppLogger.info(TAG_STREAM, "[CallbackReminder] turn=$turn marked ${sent.promptIds.size} queued sub agent result(s) as reminded")
                    }
                    _autoRetryAttempt.value = 0
                    _autoRetryCountdown.value = 0
                    println(
                        "[T-STALL-DIAG] stream FIRST-CHUNK sid=$diagSid turn=$turn " +
                            "ttfbMs=${android.os.SystemClock.elapsedRealtime() - streamStartMs} " +
                            "kind=${chunk.javaClass.simpleName}",
                    )
                }
                when (chunk) {
                    is LLMStreamChunk.ThinkingDelta -> {
                        turnThinking.append(chunk.text)
                        SessionActivityTracker.setThinking(true)
                        val thinkIdx = allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
                        if (thinkIdx < 0) {
                            allToolBlocks.add(AssistantBlock(
                                id = "thinking_$turn",
                                kind = "thinking",
                                content = turnThinking.toString(),
                                toolTitle = "Thinking",
                            ))
                        } else {
                            allToolBlocks[thinkIdx] = allToolBlocks[thinkIdx].copy(content = turnThinking.toString())
                        }
                        withContext(Dispatchers.Main) {
                            updateAssistantMessage(assistantId, accumulatedText + turnTextSb.toString(), true, allToolBlocks)
                        }
                    }
                    is LLMStreamChunk.Text -> {
                        SessionActivityTracker.setThinking(false)
                        if (!didStopStaleReadAloud) {
                            didStopStaleReadAloud = true
                            _stopStaleReadAloud.tryEmit(Unit)
                        }
                        val thinkIdx = allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
                        if (thinkIdx >= 0 && allToolBlocks[thinkIdx].toolStatus != ToolBlockStatus.SUCCESS) {
                            allToolBlocks[thinkIdx] = allToolBlocks[thinkIdx].copy(toolStatus = ToolBlockStatus.SUCCESS)
                        }
                        turnTextSb.append(chunk.text)
                        val lastIdx = allToolBlocks.lastIndex
                        val monolithic = currentProvider.streamTextIsMonolithic
                        val activeSb = if (monolithic && turnTextBlockIdx >= 0 && currentTextBlockSb != null) {
                            if (!loggedPostToolTextMerge &&
                                allToolBlocks.subList(turnTextBlockIdx + 1, allToolBlocks.size).any { it.kind == "tool_use" }
                            ) {
                                loggedPostToolTextMerge = true
                                AppLogger.info(
                                    TAG_STREAM,
                                    "[T-android-tool-splits-reply-fix] post-tool_calls content delta merged into pre-tool text block (model=${currentProvider.model.id})",
                                )
                            }
                            currentTextBlockSb!!.append(chunk.text)
                            currentTextBlockSb!!
                        } else if (!monolithic && lastIdx >= 0 && allToolBlocks[lastIdx].kind == "text" && currentTextBlockSb != null) {
                            currentTextBlockSb!!.append(chunk.text)
                            currentTextBlockSb!!
                        } else {
                            val freshSb = StringBuilder(chunk.text)
                            currentTextBlockSb = freshSb
                            val block = AssistantBlock(
                                id = "text_${turn}_${allToolBlocks.size}",
                                kind = "text",
                                content = chunk.text,
                            )
                            if (monolithic) {
                                val firstToolIdx = (turnStartBlockIndex until allToolBlocks.size)
                                    .firstOrNull { allToolBlocks[it].kind == "tool_use" }
                                if (firstToolIdx != null) {
                                    allToolBlocks.add(firstToolIdx, block)
                                    turnTextBlockIdx = firstToolIdx
                                } else {
                                    allToolBlocks.add(block)
                                    turnTextBlockIdx = allToolBlocks.lastIndex
                                }
                            } else {
                                allToolBlocks.add(block)
                            }
                            freshSb
                        }
                        pendingChunkSb.append(chunk.text)
                        val len = turnTextSb.length
                        val unflushed = len - lastFlushedLen
                        val throttle = textDeltaThrottleMs(len)
                        val newlineFlush = len < 5_000 && chunk.text.contains('\n') && unflushed >= 50
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastUiUpdateMs >= throttle || newlineFlush) {
                            lastUiUpdateMs = nowMs
                            lastFlushedLen = len
                            pendingChunkSb.setLength(0)
                            materializeActiveTextBlock()
                            val turnSnap = turnTextSb.toString()
                            withContext(Dispatchers.Main) {
                                com.openminis.app.diagnostics.StreamJitterProbe.publish("publish", accumulatedText.length + turnSnap.length)
                                updateAssistantMessage(assistantId, accumulatedText + turnSnap, true, allToolBlocks)
                            }
                        }
                    }
                    is LLMStreamChunk.ToolUseStart -> {
                        val toolUseId = dedupeToolStartId(chunk.id)
                        android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolUseStart id=$toolUseId name=${chunk.name}")
                        val thinkIdx = allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
                        if (thinkIdx >= 0 && allToolBlocks[thinkIdx].toolStatus != ToolBlockStatus.SUCCESS) {
                            allToolBlocks[thinkIdx] = allToolBlocks[thinkIdx].copy(toolStatus = ToolBlockStatus.SUCCESS)
                        }
                        if (turnTextSb.isNotEmpty() && pendingChunkSb.isNotEmpty()) {
                            pendingChunkSb.setLength(0)
                            lastUiUpdateMs = System.currentTimeMillis()
                            lastFlushedLen = turnTextSb.length
                            materializeActiveTextBlock()
                            if (!currentProvider.streamTextIsMonolithic) {
                                currentTextBlockSb = null
                            }
                            val turnSnap = turnTextSb.toString()
                            withContext(Dispatchers.Main) {
                                com.openminis.app.diagnostics.StreamJitterProbe.publish("pretool-flush", accumulatedText.length + turnSnap.length)
                                updateAssistantMessage(assistantId, accumulatedText + turnSnap, true, allToolBlocks)
                            }
                            yield()
                        }
                        lastFileToolInputMs = 0L
                        lastOtherToolInputMs = 0L
                        if (allToolBlocks.none { it.id == toolUseId }) {
                            allToolBlocks.add(AssistantBlock(
                                id = toolUseId,
                                kind = "tool_use",
                                toolName = chunk.name,
                                toolStatus = ToolBlockStatus.STREAMING,
                                toolTitle = friendlyToolTitle(chunk.name),
                                startTimeMs = System.currentTimeMillis(),
                            ))
                            withContext(Dispatchers.Main) {
                                updateAssistantMessage(assistantId, accumulatedText + turnTextSb.toString(), true, allToolBlocks)
                            }
                        }
                    }
                    is LLMStreamChunk.ToolInputDelta -> {
                        val toolInputId = dedupeToolInputId(chunk.id)
                        if (AppLogger.traceEnabled) {
                            android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolInputDelta id=$toolInputId len=${chunk.accumulated.length}")
                        }
                        val ring = toolInputChunkRings.getOrPut(toolInputId) { mutableListOf() }
                        ring.add(chunk.accumulated)
                        if (ring.size > TOOL_INPUT_CHUNK_RING_MAX) {
                            ring.subList(0, ring.size - TOOL_INPUT_CHUNK_RING_MAX).clear()
                        }
                        val idx = allToolBlocks.indexOfFirst { it.id == toolInputId }
                        if (idx >= 0) {
                            val prev = allToolBlocks[idx]
                            val partialTitle = extractPartialStringValue("tool_title", chunk.accumulated)
                            val liveTitle = when {
                                !partialTitle.isNullOrEmpty() -> partialTitle
                                prev.toolTitle.isNotEmpty() && prev.toolTitle != prev.toolName -> prev.toolTitle
                                else -> friendlyToolTitle(prev.toolName)
                            }
                            allToolBlocks[idx] = prev.copy(
                                toolArgs = chunk.accumulated,
                                toolTitle = liveTitle,
                                content = "",
                            )
                            val toolName = prev.toolName
                            val isHeavyFileTool = toolName == "file_write" || toolName == "file_edit"
                            val gateMs = if (isHeavyFileTool) 1_000L else 200L
                            val nowMs = System.currentTimeMillis()
                            val lastTs = if (isHeavyFileTool) lastFileToolInputMs else lastOtherToolInputMs
                            if (nowMs - lastTs >= gateMs) {
                                if (isHeavyFileTool) lastFileToolInputMs = nowMs
                                else lastOtherToolInputMs = nowMs
                                withContext(Dispatchers.Main) {
                                    updateAssistantMessage(assistantId, accumulatedText + turnTextSb.toString(), true, allToolBlocks)
                                }
                            }
                        }
                    }
                    is LLMStreamChunk.ToolCallComplete -> {
                        val toolCompleteId = dedupeToolCompleteId(chunk.id)
                        android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolCallComplete id=$toolCompleteId name=${chunk.name} args=${chunk.args.toString().take(300)}")
                        toolCalls.add(Triple(toolCompleteId, chunk.name, chunk.args))
                        chunk.thoughtSignature?.let { toolCallSignatures[toolCompleteId] = it }
                        val idx = allToolBlocks.indexOfFirst { it.id == toolCompleteId }
                        if (idx >= 0) {
                            val providedTitle = chunk.args.optString("tool_title", "").takeIf { it.isNotEmpty() }
                            val title = providedTitle ?: friendlyToolTitle(chunk.name)
                            allToolBlocks[idx] = allToolBlocks[idx].copy(
                                toolStatus = ToolBlockStatus.PENDING,
                                toolTitle = title,
                                toolArgs = chunk.args.toString(),
                                content = "",
                                thoughtSignature = chunk.thoughtSignature ?: allToolBlocks[idx].thoughtSignature,
                            )
                            withContext(Dispatchers.Main) {
                                updateAssistantMessage(assistantId, accumulatedText + turnTextSb.toString(), true, allToolBlocks)
                            }
                        }
                    }
                    is LLMStreamChunk.Usage -> {
                        attemptUsage = com.openminis.app.usage.UsageChunkMerger.merge(attemptUsage, chunk.usage)
                        lastUsage = attemptUsage
                        if (chunk.usage.latestContextTokens > 0) {
                            lastContextTokens = chunk.usage.latestContextTokens
                        } else if (chunk.usage.inputTokens > 0) {
                            lastContextTokens = chunk.usage.inputTokens +
                                (chunk.usage.cacheReadInputTokens ?: 0) +
                                (chunk.usage.cacheCreationInputTokens ?: 0)
                        }
                        if (lastContextTokens > 0) {
                            _lastTurnContextTokens.value = lastContextTokens
                            calibrateContextSize(lastContextTokens)
                            lastInLoopCompactionMadeNoProgress = false
                            sentPastExtrapolatedLimitThisLoop = false
                            withContext(Dispatchers.Main) {
                                publishContextUsage(crossingOnly = true)
                            }
                        }
                    }
                    is LLMStreamChunk.ReasoningContent -> {
                        turnReasoningBlob = chunk.content
                    }
                    is LLMStreamChunk.Finished -> {
                        turnFinishReason = chunk.stopReason
                        turnRefusalCategory = chunk.refusalCategory
                        turnRefusalExplanation = chunk.refusalExplanation
                    }
                    is LLMStreamChunk.Started -> {  }
                    is LLMStreamChunk.MediaAttachment -> {
                    }
                }
                    }
                    if (pendingChunkSb.isNotEmpty()) {
                        pendingChunkSb.setLength(0)
                        materializeActiveTextBlock()
                        val turnSnap = turnTextSb.toString()
                        withContext(Dispatchers.Main) {
                            com.openminis.app.diagnostics.StreamJitterProbe.publish("final-flush", accumulatedText.length + turnSnap.length)
                            updateAssistantMessage(assistantId, accumulatedText + turnSnap, true, allToolBlocks)
                        }
                    }
                    lastFlushedLen = 0
                    lastUiUpdateMs = 0L
                    lastFileToolInputMs = 0L
                    lastOtherToolInputMs = 0L
                    collectDone = true
                    if (triedEntryIds.size > 1) {
                        triedEntryIds.clear()
                        _activeEntryId.value?.let { triedEntryIds.add(it) }
                    }
                    if (_autoRetryAttempt.value != 0 || _autoRetryCountdown.value != 0) {
                        _autoRetryAttempt.value = 0
                        _autoRetryCountdown.value = 0
                    }
                    } finally {
                        firstChunkWatchdog.cancel()
                        if (!firstChunkSeen.get()) {
                            println(
                                "[T-STALL-DIAG] stream ENDED-WITHOUT-CHUNK sid=$diagSid turn=$turn " +
                                    "elapsedMs=${android.os.SystemClock.elapsedRealtime() - streamStartMs}",
                            )
                        }
                        turnStreamMs += android.os.SystemClock.elapsedRealtime() - streamStartMs
                    }
                } catch (e: Exception) {
                    if (e is CancellationException && e.cause == null) throw e
                    val actual = unwrapFlowException(e)
                    val isRateLimit = actual is com.openminis.app.data.model.LLMError.RateLimited
                    val is5xx = (actual as? com.openminis.app.data.model.LLMError)
                        ?.isHttpServerError == true
                    val isTransient = actual is com.openminis.app.data.model.LLMError.NetworkError ||
                        actual is com.openminis.app.data.model.LLMError.TransientError
                    if (isTransient && retryAttempt < AUTO_RETRY_DELAYS_SEC.size) {
                        if (!kotlin.coroutines.coroutineContext.isActive) throw CancellationException("stopped before retry countdown")
                        val delaySec = AUTO_RETRY_DELAYS_SEC[retryAttempt]
                        retryAttempt += 1
                        val errDesc = actual.message ?: actual.javaClass.simpleName
                        Log.w(TAG, "🔁 Transient error on ${currentProvider.model.displayName}, retry $retryAttempt/${AUTO_RETRY_DELAYS_SEC.size} in ${delaySec}s: $errDesc")
                        withContext(Dispatchers.Main) {
                            _autoRetryAttempt.value = retryAttempt
                            _retryLog.value = RetryLog.append(
                                _retryLog.value,
                                attempt = retryAttempt,
                                maxAttempts = AUTO_RETRY_DELAYS_SEC.size,
                                atMs = System.currentTimeMillis(),
                                error = errDesc,
                                model = currentProvider.model.displayName,
                                endpoint = _providerName.value.ifBlank { null },
                                delaySec = delaySec,
                            )
                            updateAssistantMessage(
                                assistantId, accumulatedText + turnTextSb.toString(), true, allToolBlocks,
                                isAwaitingModelResponse = true,
                            )
                        }
                        try {
                            for (remaining in delaySec downTo 1) {
                                _autoRetryCountdown.value = remaining
                                kotlinx.coroutines.delay(1000)
                            }
                        } catch (ce: CancellationException) {
                            _autoRetryAttempt.value = 0
                            throw ce
                        } finally {
                            _autoRetryCountdown.value = 0
                        }
                        withContext(Dispatchers.Main) {
                            clearInlineError()
                        }
                        if (allToolBlocks.size > turnStartBlockIndex) {
                            while (allToolBlocks.size > turnStartBlockIndex) {
                                allToolBlocks.removeAt(allToolBlocks.size - 1)
                            }
                            // [T-retry-badge-awaiting] Still awaiting the retried
                            withContext(Dispatchers.Main) {
                                updateAssistantMessage(
                                    assistantId, accumulatedText + turnTextSb.toString(), true, allToolBlocks,
                                    isAwaitingModelResponse = true,
                                )
                            }
                        }
                        turnTextSb.setLength(0)
                        currentTextBlockSb = null
                        turnTextBlockIdx = -1
                        turnThinking.clear()
                        toolCalls.clear()
                        toolCallSignatures.clear()
                        pendingChunkSb.setLength(0)
                        lastUiUpdateMs = 0L
                        lastFlushedLen = 0
                        lastFileToolInputMs = 0L
                        lastOtherToolInputMs = 0L
                        continue
                    }
                    _autoRetryAttempt.value = 0
                    _autoRetryCountdown.value = 0
                    withContext(Dispatchers.Main) { clearInlineError() }
                    val overflowErr = actual as? com.openminis.app.data.model.LLMError.ProviderError
                    if (!hasAttemptedOverflowSelfHeal &&
                        ContextOverflowGuard.isContextOverflow(
                            overflowErr?.httpStatus,
                            overflowErr?.detail,
                        )
                    ) {
                        hasAttemptedOverflowSelfHeal = true
                        noteContextOverflow(overflowErr?.detail ?: "")
                        val healed = offloadLargestPartForOverflow()
                        if (healed) {
                            AppLogger.warning(
                                TAG,
                                "[OverflowSelfHeal] GH#352 context-length 400 — offloaded the " +
                                    "largest history part and retrying. detail=" +
                                    (overflowErr?.detail?.take(200) ?: ""),
                            )
                            turnTextSb.setLength(0)
                            currentTextBlockSb = null
                            turnTextBlockIdx = -1
                            turnThinking.clear()
                            toolCalls.clear()
                            toolCallSignatures.clear()
                            continue
                        }
                        AppLogger.warning(
                            TAG,
                            "[OverflowSelfHeal] GH#352 context-length 400 but nothing was " +
                                "offloadable — surfacing the original error.",
                        )
                    }
                    val isFallbackableError =
                        (actual as? com.openminis.app.data.model.LLMError)?.isFallbackable == true
                    val isDirectEntryOverflow = _selectedGroupId.value == null &&
                        ContextOverflowGuard.isContextOverflow(overflowErr?.httpStatus, overflowErr?.detail)
                    val shouldFallback = !isDirectEntryOverflow && (isRateLimit || is5xx || isFallbackableError ||
                        loopFallbackStrategy == com.openminis.app.data.model.FallbackStrategy.always)
                    val nextCandidate = if (!shouldFallback) {
                        null
                    } else {
                        _activeEntryId.value?.let { triedEntryIds.add(it) }
                        remainingFallbacks.removeFirstOrNull()
                            ?.also { triedEntryIds.add(it.entryId) }
                            ?: buildFallbackProviders(currentProvider)
                                .firstOrNull { it.entryId !in triedEntryIds }
                                ?.also {
                                    triedEntryIds.add(it.entryId)
                                    Log.i(
                                        TAG,
                                        "🔁 fallback chain refilled — retrying ${it.provider.model.displayName} " +
                                            "(tried=${triedEntryIds.size} this streak)",
                                    )
                                }
                    }
                    val next = nextCandidate?.provider
                    if (actual is com.openminis.app.data.model.LLMError.TransientError &&
                        actual.message?.startsWith("Server returned an empty response") == true
                    ) {
                        AppLogger.warning(
                            TAG_STREAM,
                            "[B13-diag] silent empty stream after ${retryAttempt} same-model retries strategy=$loopFallbackStrategy " +
                                "model=${currentProvider.model.displayName} shouldFallback=$shouldFallback " +
                                "next=${next?.model?.displayName ?: "none"} queued=${remainingFallbacks.size} tried=${triedEntryIds.size}",
                        )
                    }
                    if (next != null && nextCandidate != null) {
                        val reason = when {
                            isRateLimit -> "Rate limited"
                            actual is com.openminis.app.data.model.LLMError.ProviderError -> actual.detail
                            else -> actual.message ?: "Error"
                        }
                        val isRealModelChange = next.model.id != currentProvider.model.id
                        fallbackReasons.add("⚠️ ${currentProvider.model.displayName}: $reason")
                        Log.i(TAG, "🔀 $reason on ${currentProvider.model.displayName}, switching to ${next.model.displayName} (realModelChange=$isRealModelChange)")
                        currentProvider = next
                        currentIdentity = providerIdentity(nextCandidate.entryId)
                        retryAttempt = 0
                        if (adoptLoopFallbackBinding(nextCandidate, classProviderAtSend, isRealModelChange)) {
                            classProviderAtSend = next
                        }
                        val infoText = fallbackReasons.joinToString("\n") + "\n🔄 Switched to ${currentProvider.model.displayName}"
                        allToolBlocks.removeAll { it.kind == "info" }
                        allToolBlocks.add(0, AssistantBlock(
                            id = "fallback_info_$turn",
                            kind = "info",
                            content = infoText,
                            toolTitle = "Switched model",
                            toolStatus = ToolBlockStatus.SUCCESS,
                        ))
                        withContext(Dispatchers.Main) {
                            updateAssistantMessage(assistantId, accumulatedText + turnTextSb.toString(), true, allToolBlocks)
                        }
                        turnTextSb.setLength(0)
                        currentTextBlockSb = null
                        turnTextBlockIdx = -1
                        turnThinking.clear()
                        toolCalls.clear()
                        toolCallSignatures.clear()
                    } else {
                        // All fallbacks exhausted. Surface the trail of tried
                        // [T-compact-last-resort-removed] A forced-compaction
                        if (shouldFallback) {
                            val skipped = unavailableGroupMembers()
                            if (fallbackReasons.isNotEmpty() || skipped.isNotEmpty()) {
                                val trail = (fallbackReasons + skipped).joinToString("\n")
                                val finalDesc = actual.message ?: actual.toString()
                                throw com.openminis.app.data.model.LLMError.ProviderError("$trail\n$finalDesc")
                            }
                        }
                        throw actual
                    }
                }
            }  // end while (!collectDone)

            val turnText = turnTextSb.toString()
            accumulatedText += turnText

            if (com.openminis.app.provider.StopReasons.isRefusal(turnFinishReason) && toolCalls.isNotEmpty()) {
                val refusedIds = toolCalls.map { it.first }.toSet()
                AppLogger.warning(
                    TAG_STREAM,
                    "Refused response carried ${toolCalls.size} tool call(s) " +
                        "[${toolCalls.joinToString(",") { it.second }}] — dropped, not executed",
                )
                allToolBlocks.removeAll { it.kind == "tool_use" && it.id in refusedIds }
                toolCalls.clear()
                withContext(Dispatchers.Main) {
                    updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                }
            }

            // Build assistant contentParts for history
            val assistantParts = mutableListOf<AgentContentPart>()
            if (turnText.isNotEmpty()) {
                assistantParts.add(AgentContentPart.Text(turnText))
            }
            for ((id, name, args) in toolCalls) {
                assistantParts.add(AgentContentPart.ToolUse(id, name, args, thoughtSignature = toolCallSignatures[id]))
            }

            toolCalls.forEach { (id, _, args) -> allToolInputs[id] = args.toString() }
            val toolInputMap = allToolInputs
            val turnReasoningContent: String? = turnReasoningBlob
                ?: turnThinking.toString().takeIf { it.isNotEmpty() }

            agentHistory.add(LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = turnText,
                contentParts = assistantParts,
                reasoningContent = turnReasoningContent,
            ))

            if (toolCalls.isNotEmpty() || EmptyTurnRecovery.turnHasText(turnText, allToolBlocks, turnStartBlockIndex)) {
                emptyStreakEntryIds.clear()
            }

            if (turnText.isEmpty() && toolCalls.isEmpty()) {
                AppLogger.warning(
                    TAG_STREAM,
                    "empty turn detected: turn=$turn finishReason=$turnFinishReason " +
                        "reasoningLen=${turnThinking.length} reasoningBlobLen=${turnReasoningBlob?.length ?: -1} " +
                        "model=${provider.model.id} provider=${provider.name}"
                )
            }

            // If no tool calls, we're done
            if (toolCalls.isEmpty()) {
                AppLogger.info(TAG_STREAM, "runAgentLoop turn=$turn no tool calls → break (finishReason=$turnFinishReason)")
                val dsmlReasoning = turnReasoningContent ?: turnThinking.toString()
                val dsmlMarker = DsmlLeak.marker(dsmlReasoning)
                if (dsmlMarker != null && DsmlLeak.isLeak(
                        turnHasText = EmptyTurnRecovery.turnHasText(turnText, allToolBlocks, turnStartBlockIndex),
                        hasToolCalls = false,
                        finishReason = turnFinishReason,
                        reasoning = dsmlReasoning,
                    )
                ) {
                    agentHistory.removeAt(agentHistory.size - 1)
                    while (allToolBlocks.size > turnStartBlockIndex) allToolBlocks.removeAt(allToolBlocks.size - 1)
                    val completionTokens = lastUsage?.outputTokens
                    val reasoningTokens = lastUsage?.reasoningTokens
                    val gap = if (completionTokens != null && reasoningTokens != null) completionTokens - reasoningTokens else null
                    when (val step = DsmlLeak.next(dsmlLeakRetries)) {
                        is DsmlLeak.Step.Retry -> {
                            dsmlLeakRetries = step.attempt
                            AppLogger.warning(
                                TAG_STREAM,
                                "[dsml-leak] turn=$turn retry=${step.attempt}/${DsmlLeak.MAX_RETRIES} marker=$dsmlMarker " +
                                    "completion=$completionTokens reasoning=$reasoningTokens gap=$gap " +
                                    "reasoningLen=${dsmlReasoning.length} finishReason=$turnFinishReason " +
                                    "model=${provider.model.id} — resending the same request",
                            )
                            withContext(Dispatchers.Main) {
                                updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                            }
                            continue
                        }
                        DsmlLeak.Step.GiveUp -> {
                            AppLogger.warning(
                                TAG_STREAM,
                                "[dsml-leak] turn=$turn retries exhausted (${DsmlLeak.MAX_RETRIES}) marker=$dsmlMarker " +
                                    "completion=$completionTokens reasoning=$reasoningTokens gap=$gap " +
                                    "model=${provider.model.id} — stopping with a notice, Resume offered",
                            )
                            withContext(Dispatchers.Main) {
                                updateAssistantMessage(assistantId, accumulatedText, false, allToolBlocks)
                            }
                            val leakParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, toolInputMap)
                            val leakMeta = allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
                            persistAssistantTurn(leakParts, lastUsage, null, leakMeta, uiAssistantId = assistantId, streamMs = turnStreamMs)
                            withContext(Dispatchers.Main) {
                                setInlineError(context.getString(R.string.chat_error_dsml_tool_call_leak))
                            }
                            markLiveInterruption()
                            _canResume.value = true
                            stopDiagExit = "dsml-retry-exhausted"
                            loopExitedNormally = true
                            break
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    updateAssistantMessage(assistantId, accumulatedText, false, allToolBlocks)
                }
                val turnParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, toolInputMap)
                val blockMeta = allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
                persistAssistantTurn(turnParts, lastUsage, turnReasoningContent, blockMeta, uiAssistantId = assistantId, streamMs = turnStreamMs)
                val turnHasText = EmptyTurnRecovery.turnHasText(turnText, allToolBlocks, turnStartBlockIndex)
                val closingRequestDue = com.openminis.app.agent.jobs.HelperRunner.shouldSendClosingRequest(
                    isHelper = helperConfig != null,
                    alreadySent = helperClosingNudgeSent,
                    turnHasText = turnHasText,
                    turn = turn,
                    turnCap = turnCap,
                ) && !com.openminis.app.provider.StopReasons.isRefusal(turnFinishReason)

                if (turnFinishReason == null && turnHasText && !closingRequestDue) {
                    AppLogger.warning(
                        TAG_STREAM,
                        "stream closed without a finish reason after ${accumulatedText.length} chars — " +
                            "surfacing as an interrupted reply (turn=$turn)",
                    )
                    withContext(Dispatchers.Main) {
                        setInlineError(
                            context.getString(R.string.chat_error_stream_dropped_partial),
                        )
                    }
                    markLiveInterruption()
                    _canResume.value = true
                    stopDiagExit = "stream-dropped-partial"
                    loopExitedNormally = true
                    break
                }

                if (com.openminis.app.provider.StopReasons.isRefusal(turnFinishReason)) {
                    AppLogger.warning(
                        TAG_STREAM,
                        "Agent loop ended with stop_reason=$turnFinishReason — model declined the request " +
                            "(turn=$turn model=${provider.model.id} provider=${provider.name} textLen=${accumulatedText.length})",
                    )
                    val refusalText = com.openminis.app.provider.StopReasons.refusalErrorText(
                        categoryMessage = turnRefusalCategory?.let {
                            context.getString(R.string.chat_error_model_refused_category, it)
                        },
                        genericMessage = context.getString(R.string.chat_error_model_refused),
                        explanation = turnRefusalExplanation,
                    )
                    withContext(Dispatchers.Main) {
                        setInlineError(refusalText)
                    }
                    _canResume.value = false
                    stopDiagExit = "refusal"
                    loopExitedNormally = true
                    break
                }

                if (com.openminis.app.provider.StopReasons.isMaxTokens(turnFinishReason) && !closingRequestDue) {
                    AppLogger.warning(
                        TAG_STREAM,
                        "Agent loop ended at max_tokens with no tool calls " +
                            "(stop_reason=$turnFinishReason turn=$turn textLen=${accumulatedText.length} " +
                            "reasoningLen=${turnThinking.length} model=${provider.model.id})",
                    )
                    withContext(Dispatchers.Main) {
                        setInlineError(context.getString(R.string.chat_error_max_tokens_truncated))
                    }
                    markLiveInterruption()
                    _canResume.value = true
                    stopDiagExit = "max-tokens"
                    loopExitedNormally = true
                    break
                }

                val deferredAskShown = userAskDeferredThisRun && userAskAskedThisRun
                val isEmptyTurn = EmptyTurnRecovery.shouldClassifyAsEmptyTurn(
                    hasText = turnHasText || deferredAskShown,
                    hasToolCall = false,
                    stopReason = turnFinishReason,
                )
                if (isEmptyTurn) {
                    AppLogger.warning(
                        TAG_STREAM,
                        "[B13-diag] empty turn turn=$turn strategy=$loopFallbackStrategy " +
                            "group=${_selectedGroupId.value?.take(8) ?: "none"} entry=${_activeEntryId.value?.take(8) ?: "?"} " +
                            "model=${currentProvider.model.displayName} startModel=${provider.model.displayName} " +
                            "reminderSpent=$didInjectEmptyToolReminder queued=${remainingFallbacks.size} tried=${triedEntryIds.size} " +
                            "helper=${helperConfig != null} closingDue=$closingRequestDue finish=$turnFinishReason",
                    )
                    val plan = EmptyTurnRecovery.plan(agentHistory, didInjectEmptyToolReminder)
                    fun injectEmptyTurnReminder(): Boolean = when (plan) {
                        is EmptyTurnRecovery.Plan.NudgeExistingPart -> {
                            didInjectEmptyToolReminder = true
                            AppLogger.warning(TAG_STREAM, "empty turn — nudging tail part (isToolResult=${plan.isToolResult}) and retrying one round (turn=$turn)")
                            agentHistory.removeAt(agentHistory.size - 1)
                            val tail = agentHistory[plan.tailIndex]
                            val newParts = tail.contentParts.toMutableList()
                            newParts[plan.partIndex] = when (val part = newParts[plan.partIndex]) {
                                is AgentContentPart.ToolResult -> part.copy(content = part.content + EmptyTurnRecovery.REMINDER)
                                is AgentContentPart.Text -> AgentContentPart.Text(part.text + EmptyTurnRecovery.REMINDER)
                                else -> part
                            }
                            agentHistory[plan.tailIndex] = tail.copy(contentParts = newParts)
                            true
                        }
                        is EmptyTurnRecovery.Plan.AppendStandalone -> {
                            didInjectEmptyToolReminder = true
                            AppLogger.warning(TAG_STREAM, "empty turn (user tail, no appendable part) — appending standalone <system-reminder> and retrying (turn=$turn)")
                            agentHistory.removeAt(agentHistory.size - 1)
                            agentHistory.add(
                                LLMMessage(
                                    role = LLMMessage.Role.USER,
                                    content = EmptyTurnRecovery.REMINDER,
                                    contentParts = listOf(AgentContentPart.Text(EmptyTurnRecovery.REMINDER)),
                                ),
                            )
                            true
                        }
                        is EmptyTurnRecovery.Plan.GiveUp -> false
                    }
                    val reminderAlreadySent = didInjectEmptyToolReminder
                    _activeEntryId.value?.let { emptyStreakEntryIds.add(it) }
                    val inGroup = _selectedGroupId.value != null
                    var emptyRoute = EmptyTurnFallback.route(
                        loopFallbackStrategy, inGroup, plan !is EmptyTurnRecovery.Plan.GiveUp, hasNextMember = true,
                    )
                    val emptyNext = if (EmptyTurnFallback.movesMember(emptyRoute)) {
                        buildFallbackProviders(currentProvider).firstOrNull {
                            it.entryId !in emptyStreakEntryIds && it.entryId !in triedEntryIds
                        }
                    } else null
                    if (emptyNext == null && EmptyTurnFallback.movesMember(emptyRoute)) {
                        emptyRoute = EmptyTurnFallback.route(
                            loopFallbackStrategy, inGroup, plan !is EmptyTurnRecovery.Plan.GiveUp, hasNextMember = false,
                        )
                    }
                    if (emptyRoute == EmptyTurnFallback.Route.SAME_MODEL_REMINDER && injectEmptyTurnReminder()) continue
                    if (emptyNext != null && EmptyTurnFallback.movesMember(emptyRoute)) {
                        if (emptyRoute == EmptyTurnFallback.Route.NEXT_MEMBER_WITH_REMINDER) {
                            injectEmptyTurnReminder()
                        } else {
                            agentHistory.removeAt(agentHistory.size - 1)
                        }
                        val next = emptyNext.provider
                        val reasoningOnly = !turnReasoningContent.isNullOrEmpty() || turnThinking.isNotEmpty()
                        val reason = EmptyTurnFallback.trailReason(reasoningOnly, afterReminder = reminderAlreadySent)
                        val isRealModelChange = next.model.id != currentProvider.model.id
                        fallbackReasons.add("⚠️ ${currentProvider.model.displayName}: $reason")
                        AppLogger.warning(
                            TAG_STREAM,
                            "🔀 empty turn on ${currentProvider.model.displayName}, switching to ${next.model.displayName} " +
                                "(route=$emptyRoute strategy=$loopFallbackStrategy reasoningOnly=$reasoningOnly " +
                                "spent=${emptyStreakEntryIds.size} turn=$turn)",
                        )
                        currentProvider = next
                        currentIdentity = providerIdentity(emptyNext.entryId)
                        loopSystemPrompt = systemPromptFor(next, loopSystemPrompt)
                        triedEntryIds.add(emptyNext.entryId)
                        remainingFallbacks.removeAll { it.entryId == emptyNext.entryId || it.entryId in emptyStreakEntryIds }
                        if (adoptLoopFallbackBinding(emptyNext, classProviderAtSend, isRealModelChange)) {
                            classProviderAtSend = next
                        }
                        val infoText = fallbackReasons.joinToString("\n") + "\n🔄 Switched to ${next.model.displayName}"
                        allToolBlocks.removeAll { it.kind == "info" }
                        allToolBlocks.add(0, AssistantBlock(
                            id = "fallback_info_$turn",
                            kind = "info",
                            content = infoText,
                            toolTitle = "Switched model",
                            toolStatus = ToolBlockStatus.SUCCESS,
                        ))
                        withContext(Dispatchers.Main) {
                            updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                        }
                        continue
                    }
                    AppLogger.warning(
                        TAG_STREAM,
                        "[B13-diag] empty turn give-up turn=$turn strategy=$loopFallbackStrategy " +
                            "model=${currentProvider.model.displayName} reminderSpent=$didInjectEmptyToolReminder " +
                            "queued=${remainingFallbacks.size} spent=${emptyStreakEntryIds.size} closingDue=$closingRequestDue " +
                            "inGroup=$inGroup — no member left",
                    )
                    val window = effectiveContextWindowTokens()
                    val usedCtx = lastUsage?.latestContextTokens ?: 0
                    val contextNearFull = window != null && window > 0 && usedCtx > 0 &&
                        usedCtx.toDouble() / window.toDouble() > 0.70
                    if (!closingRequestDue) {
                        val hint = when {
                            didInjectEmptyToolReminder ->
                                context.getString(R.string.error_empty_response_after_tool)
                            contextNearFull ->
                                context.getString(R.string.error_empty_response_context_large)
                            else ->
                                context.getString(R.string.error_empty_response_generic)
                        }
                        withContext(Dispatchers.Main) { setInlineError(hint) }
                        markLiveInterruption()
                        _canResume.value = true
                    }
                }
                // [T-subagent-termination] The one-shot closing request (see
                if (closingRequestDue) {
                    helperClosingNudgeSent = true
                    helperClosingAttempted = true
                    helperWrapUpInjected = true
                    val tail = agentHistory.lastOrNull()
                    if (tail != null && tail.role == LLMMessage.Role.ASSISTANT && tail.content.isBlank() &&
                        tail.contentParts.none { it is AgentContentPart.ToolUse || (it is AgentContentPart.Text && it.text.isNotBlank()) }
                    ) {
                        agentHistory.removeAt(agentHistory.size - 1)
                    }
                    val nudge = com.openminis.app.agent.jobs.HelperRunner.CLOSING_NUDGE_TEXT
                    agentHistory.add(
                        LLMMessage(
                            role = LLMMessage.Role.USER,
                            content = nudge,
                            contentParts = listOf(AgentContentPart.Text(nudge)),
                        ),
                    )
                    val nudgeSid = activeSessionId
                    viewModelScope.launch(Dispatchers.IO) {
                        chatRepository.appendMessage(nudgeSid, "user", """[{"type":"text","value":${escapeJson(nudge)}}]""")
                    }
                    withContext(Dispatchers.Main) {
                        updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                    }
                    _canResume.value = false
                    AppLogger.warning(
                        TAG_STREAM,
                        "[subagent_term] empty turn end (stopReason=$turnFinishReason) — sent the one-shot closing request, tools withdrawn, turn=${turn + 1}/$turnCap",
                    )
                    continue
                }
                val cleanStop = turnFinishReason == "stop" || turnFinishReason == "end_turn"
                val userPromptWaiting = _promptQueue.value.any { it.origin == QueuedPromptOrigin.USER }
                if (scheduledResumeNudgeOwed && !isEmptyTurn && cleanStop && !userPromptWaiting) {
                    scheduledResumeNudgeOwed = false
                    val nudge = ChatMessage.SCHEDULED_RESUME_NUDGE_TEXT
                    agentHistory.add(
                        LLMMessage(
                            role = LLMMessage.Role.USER,
                            content = nudge,
                            contentParts = listOf(AgentContentPart.Text(nudge)),
                        ),
                    )
                    val nudgeSid = activeSessionId
                    viewModelScope.launch(Dispatchers.IO) {
                        chatRepository.appendMessage(nudgeSid, "user", """[{"type":"text","value":${escapeJson(nudge)}}]""")
                    }
                    withContext(Dispatchers.Main) {
                        updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                    }
                    AppLogger.info(
                        TAG_STREAM,
                        "⏰[ScheduledPreempt] model stopped after an inserted scheduled fire — sent the one-shot resume nudge (turn=$turn)",
                    )
                    continue
                }
                if (com.openminis.app.agent.jobs.HelperRunner.shouldContinueForSteer(
                        isHelper = helperConfig != null,
                        isEmptyTurn = isEmptyTurn,
                        turn = turn,
                        turnCap = turnCap,
                        pendingSteers = pendingSteerMessages.size,
                    )
                ) {
                    AppLogger.info(TAG, "[subagent_task] steer arrived at loop end — continuing for another turn (turn=${turn + 1}/$turnCap)")
                    withContext(Dispatchers.Main) {
                        updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                    }
                    _canResume.value = false
                    continue
                }
                // Auto-title after first exchange
                if (turn == 0) generateSessionTitleIfNeeded()
                stopDiagExit = if (isEmptyTurn) "empty-turn" else "no-tool-calls"
                loopExitedNormally = true
                break
            }
            AppLogger.info(TAG_STREAM, "runAgentLoop turn=$turn dispatching ${toolCalls.size} tool call(s), continuing")

            run {
                val livePreviewParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, toolInputMap)
                val liveMeta = allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
                if (livePreviewParts.isNotEmpty()) {
                    val livePartsJson = buildAssistantPartsJson(livePreviewParts, liveMeta)
                    chatRepository.updateSessionPreview(realSessionId.ifEmpty { sessionId }, livePartsJson)
                    userAskBatchSnapshot = livePartsJson to toolCalls.map { it.first }
                }
            }

            val resultParts = mutableListOf<AgentContentPart>()
            val slots = kotlinx.coroutines.sync.Semaphore(MAX_CONCURRENT_TOOLS)
            val slotResults = arrayOfNulls<List<AgentContentPart>>(toolCalls.size)
            val titledTools = agentTools.filter { ToolTitleReminder.declaresTitle(it.parameters.keys) }
                .map { it.name }.toSet()
            val batchNames = toolCalls.map { it.second }
            val slotJobs = arrayOfNulls<kotlinx.coroutines.Job>(toolCalls.size)
            kotlinx.coroutines.coroutineScope {
                toolCalls.mapIndexed { slotIndex, (id, name, args) ->
                    async {
                        val earlierWriters = BatchToolOrder.writersBefore(slotIndex, batchNames)
                        if (earlierWriters.isNotEmpty()) {
                            val waitStart = System.currentTimeMillis()
                            earlierWriters.forEach { slotJobs[it]?.join() }
                            android.util.Log.i(
                                "ToolChain[VM]",
                                "[BatchOrder] $name [$slotIndex] waited ${System.currentTimeMillis() - waitStart}ms for writer(s) $earlierWriters in the same batch",
                            )
                        }
                        val resultParts = mutableListOf<AgentContentPart>()
                        slots.withPermit {
                            SessionActivityTracker.toolStarted()
                            try {
                        val dispatchToolTitle = try {
                            args.optString("tool_title", "").takeIf { it.isNotBlank() }
                        } catch (_: Exception) { null }
                        SessionActivityTracker.updateToolStatus(
                            status = "Running: $name",
                            toolName = name,
                            isRunning = true,
                            toolTitle = dispatchToolTitle,
                        )
                        val repairs = com.openminis.app.provider.ToolJsonRepair.repair(
                            name, args, toolInputChunkRings[id]?.lastOrNull(), agentTools,
                        )
                        if (repairs.isNotEmpty()) {
                            AppLogger.warning(
                                "ToolPreflight",
                                "[ToolRepair] REPAIRED tool=$name id=$id strategies=[${repairs.joinToString(", ")}] " +
                                    "argsKeys=[${args.keys().asSequence().toList().sorted().joinToString(",")}] " +
                                    "rawTail=<<<${toolInputChunkRings[id]?.lastOrNull()?.take(500) ?: ""}>>>"
                            )
                        }
                        val truncationRepairTag: String? = repairs.firstOrNull { it.startsWith("truncation+") }

                        if (truncationRepairTag != null && (name == "file_write" || name == "file_edit")) {
                            val path = args.optString("path", "").ifBlank { args.optString("file_path", "") }
                            AppLogger.warning(
                                "ToolPreflight",
                                "[ToolRepair] REFUSED truncated write tool=$name id=$id strategy=$truncationRepairTag path=$path"
                            )
                            val modelMessage = buildString {
                                append("Error: This call was NOT executed. Its argument stream was truncated ")
                                append("in transit (repair strategy: $truncationRepairTag), so the `content` ")
                                append("your client sent was cut short and would have written an incomplete file")
                                if (path.isNotBlank()) append(" to $path")
                                append(". Nothing was written to disk — the target file is unchanged.\n\n")
                                append("The most likely cause is the response hitting its output-token limit ")
                                append("mid-argument. Re-issue this write in smaller pieces: write the first ")
                                append("part, then append the rest with follow-up calls, rather than repeating ")
                                append("the same oversized call.")
                            }
                            val refusedIdx = allToolBlocks.indexOfFirst { it.id == id }
                            if (refusedIdx >= 0) {
                                val elapsed = System.currentTimeMillis() - allToolBlocks[refusedIdx].startTimeMs
                                allToolBlocks[refusedIdx] = allToolBlocks[refusedIdx].copy(
                                    toolStatus = ToolBlockStatus.FAILED,
                                    content = "Blocked: arguments were truncated in transit",
                                    durationMs = elapsed,
                                )
                                withContext(Dispatchers.Main) {
                                    updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                                }
                            }
                            toolLoopDetector.record(name, parseToolParams(args.toString()),
                                result = null, errorMessage = modelMessage, toolCallId = id)
                            resultParts.add(AgentContentPart.ToolResult(
                                id = id, name = name,
                                content = modelMessage,
                                isError = true,
                            ))
                            toolInputChunkRings.remove(id)
                            return@withPermit
                        }
                        val args = agentTools.firstOrNull { it.name == name }?.coerceNestedJsonStrings(args) ?: args
                        val argsStr = args.toString()
                        val paramsMap = parseToolParams(argsStr)
                        val preIdx = allToolBlocks.indexOfFirst { it.id == id }
                        if (preIdx >= 0 && allToolBlocks[preIdx].toolStatus == ToolBlockStatus.PENDING) {
                            allToolBlocks[preIdx] = allToolBlocks[preIdx].copy(toolStatus = ToolBlockStatus.RUNNING)
                            withContext(Dispatchers.Main) {
                                updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                            }
                        }

                        val precheck = if (com.openminis.app.scheduled.ScriptedToolTurn.isScriptedId(id)) {
                            com.openminis.app.agent.LoopCheckResult.NONE
                        } else {
                            toolLoopDetector.check(name, paramsMap)
                        }
                        if (precheck.level == Level.CRITICAL) {
                            val blockedMsg = precheck.message ?: "[LOOP BLOCKED] tool execution blocked"
                            android.util.Log.w("ToolChain[VM]",
                                "[turn=$turn] tool BLOCKED by loop detector name=$name msg=$blockedMsg")
                            AppLogger.warning("ChatViewModel",
                                "tool blocked by loop detector name=$name reason=$blockedMsg")
                            val blockIdx = allToolBlocks.indexOfFirst { it.id == id }
                            if (blockIdx >= 0) {
                                val elapsed = System.currentTimeMillis() - allToolBlocks[blockIdx].startTimeMs
                                allToolBlocks[blockIdx] = allToolBlocks[blockIdx].copy(
                                    toolStatus = ToolBlockStatus.FAILED,
                                    content = blockedMsg,
                                    durationMs = elapsed,
                                )
                            }
                            toolLoopDetector.record(name, paramsMap,
                                result = null, errorMessage = blockedMsg, toolCallId = id)
                            resultParts.add(AgentContentPart.ToolResult(
                                id = id, name = name,
                                content = blockedMsg,
                                isError = true,
                            ))
                            return@withPermit
                        }

                        val preflightError = preflightValidateToolCall(name, args, agentTools)
                        if (preflightError != null) {
                            val chunkRing: List<String> = toolInputChunkRings.remove(id) ?: emptyList()
                            AppLogger.warning(
                                "ToolPreflight",
                                "BLOCKED tool=$name id=$id reason=\"$preflightError\" " +
                                    "argsKeys=[${args.keys().asSequence().toList().sorted().joinToString(",")}] " +
                                    "chunkCount=${chunkRing.size} " +
                                    "lastChunk=<<<${chunkRing.lastOrNull()?.take(500) ?: ""}>>>"
                            )
                            chunkRing.forEachIndexed { i, snap ->
                                AppLogger.warning(
                                    "ToolPreflight",
                                    "  chunk[$i] bytes=${snap.toByteArray(Charsets.UTF_8).size} raw=<<<${snap.take(500)}>>>"
                                )
                            }
                            val uiMessage = "Blocked invalid tool call"
                            val modelMessage = "Error: Tool call rejected before execution. $preflightError The arguments your client sent were empty or missing required fields — re-issue the call with all required parameters filled in. Do not retry with the same empty arguments."
                            val blockIdxPre = allToolBlocks.indexOfFirst { it.id == id }
                            if (blockIdxPre >= 0) {
                                val elapsedPre = System.currentTimeMillis() - allToolBlocks[blockIdxPre].startTimeMs
                                allToolBlocks[blockIdxPre] = allToolBlocks[blockIdxPre].copy(
                                    toolStatus = ToolBlockStatus.FAILED,
                                    content = uiMessage,
                                    durationMs = elapsedPre,
                                )
                            }
                            toolLoopDetector.record(
                                toolName = name, params = paramsMap,
                                result = null, errorMessage = modelMessage, toolCallId = id
                            )
                            resultParts.add(AgentContentPart.ToolResult(
                                id = id, name = name,
                                content = modelMessage,
                                isError = true,
                            ))
                            withContext(Dispatchers.Main) {
                                updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                            }
                            return@withPermit
                        }

                        if (toolOnlyStopTurn == turnGen) {
                            AppLogger.info(TAG_STREAM, "[stop-tool] turn=$turn $name ${id.take(16)} not started: tools were stopped while it waited")
                            val blockIdxStop = allToolBlocks.indexOfFirst { it.id == id }
                            if (blockIdxStop >= 0) {
                                allToolBlocks[blockIdxStop] = allToolBlocks[blockIdxStop].copy(
                                    toolStatus = ToolBlockStatus.CANCELLED,
                                    durationMs = System.currentTimeMillis() - allToolBlocks[blockIdxStop].startTimeMs,
                                )
                            }
                            resultParts.add(AgentContentPart.ToolResult(
                                id = id, name = name,
                                content = ForegroundToolInterrupts.INTERRUPTED_RESULT,
                                isError = true,
                            ))
                            withContext(Dispatchers.Main) {
                                updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                            }
                            return@withPermit
                        }

                        android.util.Log.d("ToolChain[VM]", "[turn=$turn] executeTool START name=$name args=${argsStr.take(200)}")
                        val isDelegation = name == com.openminis.app.agent.jobs.HelperRunner.TOOL_NAME
                        val toolRun = foregroundTools.run(
                            id, name,
                            cancellable = name != "shell_execute" && !isDelegation,
                            onInterrupted = { ToolExecutionResult(ForegroundToolInterrupts.INTERRUPTED_RESULT, false) },
                        ) { executeToolCached(name, argsStr, id, allToolBlocks, assistantId, accumulatedText) }
                        val toolInterrupted = toolRun.interrupted && !isDelegation
                        val result = if (toolInterrupted && toolRun.value.output != ForegroundToolInterrupts.INTERRUPTED_RESULT) {
                            toolRun.value.copy(output = ForegroundToolInterrupts.withInterruptNote(toolRun.value.output), success = false)
                        } else {
                            toolRun.value
                        }
                        if (toolInterrupted) AppLogger.info(TAG_STREAM, "[stop-tool] turn=$turn $name ${id.take(16)} stopped by the user (tool only)")
                        android.util.Log.d("ToolChain[VM]", "[turn=$turn] executeTool END name=$name success=${result.success} title=${result.toolTitle} outputLen=${result.output.length} output=${result.output.take(200)}")

                        val errMsgForDetector = if (!result.success) result.output else null
                        val postRecord = toolLoopDetector.record(
                            toolName = name,
                            params = paramsMap,
                            result = if (result.success) result.output else null,
                            errorMessage = errMsgForDetector,
                            toolCallId = id,
                        )
                        val outputWithLoopWarning = if (postRecord.level == Level.WARNING && postRecord.message != null) {
                            AppLogger.debug("ChatViewModel",
                                "appending loop-warning to tool result name=$name key=${postRecord.warningKey}")
                            "${result.output}\n\n${postRecord.message}"
                        } else {
                            result.output
                        }
                        val outputForLlmBase = if (toolLoopDetector.recordToolTitle(args, name in titledTools) != null) {
                            Log.i(TAG, "[ToolTitleReminder] $name came back without tool_title — soft reminder appended")
                            ToolTitleReminder.appending(outputWithLoopWarning)
                        } else {
                            outputWithLoopWarning
                        }
                        val llmLimits = if (name == "shell_execute") {
                            240 to 40_000
                        } else {
                            300 to 28_000
                        }
                        val trimmedForModel = com.openminis.app.tools.ToolResultTrim.trim(
                            outputForLlmBase, maxLines = llmLimits.first, maxChars = llmLimits.second,
                        )
                        val spillPath = if (trimmedForModel.wasTrimmed && outputForLlmBase.length <= 8_000_000) {
                            ContextOffload.offloadContent(
                                context, activeSessionId, outputForLlmBase, id, name,
                            )
                        } else {
                            ""
                        }
                        val outputForLLM = if (!trimmedForModel.wasTrimmed) {
                            trimmedForModel.text
                        } else {
                            trimmedForModel.text + "\n\n" + trimmedForModel.noticeWithSpill(spillPath)
                        }

                        val blockIdx = allToolBlocks.indexOfFirst { it.id == id }
                        if (blockIdx >= 0) {
                            val elapsed = System.currentTimeMillis() - allToolBlocks[blockIdx].startTimeMs
                            val existingContent = allToolBlocks[blockIdx].content
                            val resultContent = if (name == "shell_execute") {
                                com.openminis.app.tools.ToolResultTrim.trim(
                                    result.output, maxLines = 80, maxChars = 16_000,
                                ).text
                            } else {
                                result.output
                            }
                            val finalContent = if (existingContent.length > resultContent.length) existingContent else resultContent
                            val finalStatus = when {
                                toolInterrupted -> ToolBlockStatus.CANCELLED
                                name == com.openminis.app.agent.jobs.HelperRunner.TOOL_NAME &&
                                    com.openminis.app.agent.jobs.HelperRunner.isRunningPayload(result.output) -> ToolBlockStatus.RUNNING
                                result.success && truncationRepairTag != null -> ToolBlockStatus.FAILED
                                result.success -> ToolBlockStatus.SUCCESS
                                result.timedOut -> ToolBlockStatus.TIMEOUT
                                else -> ToolBlockStatus.FAILED
                            }
                            val toolOutcome = when (finalStatus) {
                                ToolBlockStatus.SUCCESS -> com.openminis.app.service.ToolOutcome.Success
                                ToolBlockStatus.TIMEOUT -> com.openminis.app.service.ToolOutcome.Timeout
                                ToolBlockStatus.FAILED -> com.openminis.app.service.ToolOutcome.Error
                                ToolBlockStatus.CANCELLED -> com.openminis.app.service.ToolOutcome.Cancelled
                                else -> com.openminis.app.service.ToolOutcome.Unknown
                            }
                            SessionActivityTracker.clearToolRunning(toolOutcome)
                            android.util.Log.d("ToolChain[VM]", "[turn=$turn] block[$blockIdx] status→$finalStatus title=${result.toolTitle} contentLen=${finalContent.length}")
                            allToolBlocks[blockIdx] = allToolBlocks[blockIdx].copy(
                                toolStatus = finalStatus,
                                content = finalContent,
                                toolTitle = result.toolTitle.ifEmpty { allToolBlocks[blockIdx].toolTitle },
                                durationMs = elapsed,
                                browserURL = result.pageURL ?: allToolBlocks[blockIdx].browserURL,
                                imageFilePath = result.imageFilePath ?: allToolBlocks[blockIdx].imageFilePath,
                            )
                        }

                        val outputForLLMWithNote = if (truncationRepairTag != null) {
                            outputForLLM + "\n\n<system-reminder>The argument stream for this call was " +
                                "truncated in transit and auto-closed by the client (repair strategy: " +
                                "$truncationRepairTag) before execution. The arguments actually used may be " +
                                "incomplete — verify the result and re-issue the call with complete " +
                                "arguments if anything is missing.</system-reminder>"
                        } else {
                            outputForLLM
                        }

                        resultParts.add(ToolResultWrap.part(id, name, outputForLLMWithNote, result))
                            } finally {
                                SessionActivityTracker.toolFinished()
                            }
                        }
                        slotResults[slotIndex] = resultParts
                    }.also { slotJobs[slotIndex] = it }
                }.awaitAll()
            }
            for (parts in slotResults) parts?.let { resultParts.addAll(it) }

            withContext(Dispatchers.Main) {
                updateAssistantMessage(
                    assistantId, accumulatedText, true, allToolBlocks,
                    isAwaitingModelResponse = true,
                )
            }

            val turnParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, toolInputMap)
            val blockMeta = allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
            val assistantDbId = persistAssistantTurn(turnParts, lastUsage, turnReasoningContent, blockMeta, uiAssistantId = assistantId, streamMs = turnStreamMs)
            if (assistantDbId != null) {
                val lastIdx = agentHistory.indexOfLast { it.role == LLMMessage.Role.ASSISTANT && it.dbMessageId == null }
                if (lastIdx >= 0) {
                    agentHistory[lastIdx] = agentHistory[lastIdx].copy(dbMessageId = assistantDbId)
                }
            }

            val toolResultDbId = persistToolResultMessage(resultParts, blockMeta.mapValues { it.value.durationMs })
            com.openminis.app.agent.userask.UserAskCenter.markDelivered(
                resultParts.filterIsInstance<AgentContentPart.ToolResult>().map { it.id },
            )

            agentHistory.add(ToolResultWrap.historyMessage(resultParts, toolResultDbId))

            if (turn == 0) {
                generateSessionTitleIfNeeded()
            }

            val stopDiagMuted = com.openminis.app.agent.jobs.AgentJobRegistry.mutedSnapshot().contains(activeSessionId)
            AppLogger.info(
                TAG_STREAM,
                "[stop-diag] post-tools turn=$turn sid=$activeSessionId muted=$stopDiagMuted " +
                    "mutedSet=${com.openminis.app.agent.jobs.AgentJobRegistry.mutedSnapshot()} tools=${toolCalls.map { it.second }} " +
                    "activeChildJobs=${com.openminis.app.agent.jobs.AgentJobRegistry.activeChildCount(activeSessionId)} " +
                    "decision=${if (stopDiagMuted) "stop (delegation-muted)" else "continue -> next model request"}",
            )
            if (com.openminis.app.agent.jobs.AgentJobRegistry.isDelegationMuted(activeSessionId)) {
                val stale = _promptQueue.value.filter {
                    it.origin == QueuedPromptOrigin.PROGRAMMATIC && !it.isScheduledFire &&
                        it.text.contains("<agent_callback")
                }
                AppLogger.info(
                    TAG_STREAM,
                    "[delegate_task] turn=$turn parent stops here — its sub agents were stopped by the user; " +
                        "results kept, not sent (dropped ${stale.size} queued delegation notice(s))",
                )
                withContext(Dispatchers.Main) {
                    stale.forEach { removeQueuedPrompt(it.id) }
                    updateAssistantMessage(
                        assistantId, accumulatedText, false, allToolBlocks,
                        isAwaitingModelResponse = false,
                    )
                }
                stopDiagExit = "delegation-muted"
                loopExitedNormally = true
                break
            }

            val insertBatch = QueuedPromptBatching.nextInsertBatch(_promptQueue.value)
            val toolStopHere = toolOnlyStopTurn == turnGen
            if (toolStopHere) toolOnlyStopTurn = null
            if (StopScopePolicy.endsTurnAtBoundary(toolStopHere, insertPending = insertBatch.isNotEmpty())) {
                AppLogger.info(
                    TAG_STREAM,
                    "[stop-tool] turn=$turn tools stopped by the user and nothing queued to insert — " +
                        "ending the turn (queue=${_promptQueue.value.size}, activeChildJobs=" +
                        "${com.openminis.app.agent.jobs.AgentJobRegistry.activeChildCount(activeSessionId)})",
                )
                withContext(Dispatchers.Main) {
                    updateAssistantMessage(
                        assistantId, accumulatedText, false, allToolBlocks,
                        isAwaitingModelResponse = false,
                    )
                }
                stopDiagExit = "tool-only-stop"
                loopExitedNormally = true
                break
            }
            if (insertBatch.isEmpty() && _promptQueue.value.isNotEmpty()) {
                AppLogger.info(
                    TAG_STREAM,
                    "📨[QueueHold] turn=$turn ${_promptQueue.value.size} programmatic prompt(s) queued — holding until the loop converges",
                )
            }
            if (insertBatch.isNotEmpty()) {
                val scheduledFire = insertBatch.singleOrNull()?.takeIf { it.isScheduledFire }
                AppLogger.info(
                    TAG_STREAM,
                    if (scheduledFire != null) {
                        "📨[ScheduledInsert] turn=$turn inserting scheduled task ${scheduledFire.scheduledTaskId} " +
                            "between tool calls (prefill=${scheduledFire.prefill.size}, queue=${_promptQueue.value.size})"
                    } else {
                        "📨[QueueInterrupt] turn=$turn ${insertBatch.size} queued prompt(s) (user follow-up) — interrupting after current tool call to start a standalone turn"
                    },
                )
                val handled = try {
                    injectQueuedPromptsAsNewTurn(
                        finishedAssistantId = assistantId,
                        finishedAccumulatedText = accumulatedText,
                        finishedAllToolBlocks = allToolBlocks,
                        batch = insertBatch,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "injectQueuedPromptsAsNewTurn failed", e)
                    null
                }
                if (handled != null) {
                    scheduledResumeNudgeOwed = scheduledFire != null
                    assistantId = handled.newAssistantId
                    liveTurn.markAssistant(turnGen, assistantId)
                    scriptedTurnFor(handled.prefill)?.let { pendingScriptedTurn = it }
                    accumulatedText = ""
                    allToolBlocks.clear()
                    allToolInputs.clear()
                    toolInputChunkRings.clear()
                    _canResume.value = false
                    continue
                }
            }
        }
        AppLogger.info(
            TAG_STREAM,
            "[stop-diag] runAgentLoop EXIT reason=${if (loopExitedNormally) stopDiagExit else "turn-cap"} " +
                "sid=$activeSessionId muted=${com.openminis.app.agent.jobs.AgentJobRegistry.mutedSnapshot().contains(activeSessionId)}",
        )
        if (!loopExitedNormally) {
            AppLogger.warning(
                TAG_STREAM,
                "runAgentLoop EXIT — hit turn cap=$turnCap${if (helperConfig != null) " (helper)" else ""}, finalizing as resumable",
            )
            withContext(Dispatchers.Main) {
                finalizeAtTurnLimit(assistantId, accumulatedText, allToolBlocks)
            }
        } else {
            AppLogger.info(TAG_STREAM, "runAgentLoop EXIT (loop body ended naturally)")
        }
    }

    private fun finalizeAtTurnLimit(
        assistantId: String,
        text: String,
        blocks: List<AssistantBlock>,
    ) {
        updateAssistantMessage(
            assistantId, text, false, blocks,
            isAwaitingModelResponse = false,
        )
        clearStreamFlushState(assistantId)
        if (_streamingById.value.containsKey(assistantId)) {
            _streamingById.value = _streamingById.value - assistantId
        }
        val cap = helperConfig?.maxTurns ?: MAX_AGENT_TURNS
        setInlineError(
            if (helperConfig != null)
                "The agent stopped after its $cap tool rounds without a final answer."
            else
                "Stopped after $cap agent turns to prevent runaway " +
                    "tool use. The model kept calling tools without finishing — tap " +
                    "Resume to continue from here, or send a new message to start over.",
        )
        markLiveInterruption()
        _canResume.value = true
    }

    private var lastMcpToolRefreshMs = 0L

    private var mcpToolRefreshRunning = false

    private fun maybeRefreshMcpTools() {
        val repo = mcpRepository ?: return
        val now = System.currentTimeMillis()
        if (mcpToolRefreshRunning || now - lastMcpToolRefreshMs < 120_000L) return
        val servers = repo.servers.value
            .filter { it.enabled && repo.isEnabledForSession(it.id, activeSessionId) }
            .map { it.id }
        if (servers.isEmpty()) return
        lastMcpToolRefreshMs = now
        mcpToolRefreshRunning = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                com.openminis.app.tools.McpToolRegistry.refresh(servers) { server ->
                    val out = com.openminis.app.sandbox.ExecutionCoordinator.execute(
                        sessionId = activeSessionId,
                        command = "minis-mcp-cli tools " + com.openminis.app.tools.McpNativeTools.quote(server),
                        timeout = 20_000L,
                    ).output
                    out.takeIf { it.contains("\"tools\"") }
                }
            } catch (_: Exception) {
                android.util.Log.i(TAG, "[mcp-tools] refresh failed, keeping previous list")
            } finally {
                mcpToolRefreshRunning = false
            }
        }
    }

    private fun preflightValidateToolCall(
        name: String,
        args: JSONObject,
        tools: List<AgentToolDefinition>,
    ): String? = preflightValidateToolCallImpl(name, args, tools)

    private suspend fun executeMcpCall(
        name: String,
        argsJson: String,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>,
        assistantId: String,
        currentText: String,
    ): ToolExecutionResult {
        val obj = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        val ref = if (name == com.openminis.app.tools.McpNativeTools.GATEWAY) {
            val server = obj.optString("server", "").trim()
            val tool = obj.optString("tool", "").trim()
            if (server.isEmpty() || tool.isEmpty()) {
                return ToolExecutionResult(
                    "Error: mcp_call needs both `server` and `tool` (exact names from the MCP section of " +
                        "your instructions). Run `minis-mcp-cli tools <server>` once if unsure.",
                    false,
                    toolTitle = obj.optString("tool_title", ""),
                )
            }
            com.openminis.app.tools.McpNativeTools.Ref(server, tool)
        } else {
            com.openminis.app.tools.McpNativeTools.parseToolName(name)
                ?: return ToolExecutionResult(
                    "Error: malformed MCP tool name '" + name + "'. Expected mcp__<server>__<tool>.",
                    false,
                    toolTitle = obj.optString("tool_title", ""),
                )
        }
        val arguments = com.openminis.app.tools.McpNativeTools.argumentsJson(obj)
        val command = com.openminis.app.tools.McpNativeTools.shellCommand(ref.server, ref.tool, arguments)
        val shellArgs = JSONObject()
            .put("command", command)
            .put("tool_title", obj.optString("tool_title", name))
            .toString()
        val result = executeShellCommand(shellArgs, toolId, toolBlocks, assistantId, currentText)
        return result.copy(toolTitle = obj.optString("tool_title", name))
    }

    private val writeCheckpoint = com.openminis.app.sandbox.checkpoint.SessionCheckpoint()

    private val approvalCoordinator = com.openminis.app.agent.approval.ApprovalCoordinator()

    fun checkpointForSession(): com.openminis.app.sandbox.checkpoint.SessionCheckpoint = writeCheckpoint

    private fun refreshToolBlockNote(blockId: String, note: String): String? {
        var hit = false
        var ownerId: String? = null
        val updated = _messages.value.map { msg ->
            if (msg.toolBlocks.none { it.id == blockId }) msg else {
                msg.copy(
                    toolBlocks = msg.toolBlocks.map { b ->
                        if (b.id != blockId) b else {
                            hit = true
                            ownerId = msg.id
                            val head = b.content.substringBefore("Changes:\n").trimEnd()
                                .substringBefore("（已按段重选：").trimEnd()
                            b.copy(content = "$head\n（已按段重选：$note）")
                        }
                    },
                )
            }
        }
        if (hit) _messages.value = updated
        return ownerId
    }

    private suspend fun persistHunkNote(messageId: String, blockId: String, note: String) {
        runCatching {
            val sid = realSessionId.ifEmpty { sessionId }
            val entity = chatRepository.loadMessages(sid).firstOrNull { it.id == messageId } ?: return@runCatching
            val parts = org.json.JSONArray(entity.partsJson)
            var changed = false
            for (i in 0 until parts.length()) {
                val o = parts.optJSONObject(i) ?: continue
                if (o.optString("type") != "toolResult") continue
                val v = o.optJSONObject("value") ?: continue
                if (v.optString("toolUseId") != blockId) continue
                val out = v.optString("output", "")
                val head = out.substringBefore("Changes:\n").trimEnd()
                    .substringBefore("（已按段重选：").trimEnd()
                v.put("output", "$head\n（已按段重选：$note）")
                changed = true
            }
            if (changed) chatRepository.updateMessageParts(messageId, parts.toString())
        }
    }

    fun applyHunkSelection(block: AssistantBlock, selected: Set<Int>, onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val msg = try {
                val args = JSONObject(block.toolArgs)
                val path = args.optString("path", "").trim()
                val oldStr = args.optString("old_string", "")
                val newStr = args.optString("new_string", "")
                if (path.isEmpty() || newStr.isEmpty()) {
                    "无法读取这次编辑的参数"
                } else {
                    val plan = com.openminis.app.tools.patch.PatchSplitter.plan(oldStr, newStr, context = 1)
                    when {
                        !plan.complete -> "这次编辑太长，无法按段重写"
                        selected.isEmpty() -> "没有选中任何段"
                        else -> {
                            val target = plan.apply(selected)
                            val file = com.openminis.app.sandbox.PRootKernel.resolveSessionHostPath(fsSessionId, path, context)
                            when {
                                file == null -> "找不到文件：$path"
                                !file.exists() -> "文件已不存在：$path"
                                else -> {
                                    val content = file.readText()
                                    if (content.indexOf(newStr) < 0) {
                                        "文件内容已变化，未应用。请让助手重新执行这次编辑。"
                                    } else {
                                        file.writeText(content.replaceFirst(newStr, target))
                                        val noteText = "保留 ${selected.size}/${plan.hunks.size} 段 · ${target.toByteArray(Charsets.UTF_8).size} bytes"
                                        val ownerId = withContext(Dispatchers.Main) {
                                            refreshToolBlockNote(block.id, noteText)
                                        }
                                        if (ownerId != null) persistHunkNote(ownerId, block.id, noteText)
                                        "已保留 ${selected.size}/${plan.hunks.size} 段，写回 $path"
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                "应用失败：${e.message}"
            }
            withContext(Dispatchers.Main) { onResult(msg) }
        }
    }

    private fun executeCheckpointRestore(argsJson: String): ToolExecutionResult {
        val path = try { JSONObject(argsJson).optString("path", "").trim() } catch (_: Exception) { "" }
        val ops = if (path.isEmpty()) writeCheckpoint.restorePlan() else listOfNotNull(writeCheckpoint.revertPlan(path))
        if (ops.isEmpty()) {
            return ToolExecutionResult(
                "Nothing to restore: no file changes were recorded in this session.",
                false,
                toolTitle = "Restore checkpoint",
            )
        }
        var restored = 0
        var failed = 0
        for (op in ops) {
            val file = java.io.File(op.path)
            val ok = runCatching {
                if (op.content == null) file.delete() || !file.exists() else {
                    file.parentFile?.mkdirs()
                    file.writeText(op.content)
                }
                true
            }.getOrDefault(false)
            if (ok) restored++ else failed++
        }
        if (path.isEmpty()) writeCheckpoint.clear() else writeCheckpoint.forget(path)
        val detail = buildString {
            append("Restored $restored file(s)")
            if (failed > 0) append(", $failed failed")
            append(".")
            if (ops.any { it.truncated }) append(" Some snapshots were truncated and may be incomplete.")
        }
        return ToolExecutionResult(detail, failed == 0, toolTitle = "Restore checkpoint")
    }

    private fun conversationRecords(): List<com.openminis.app.agent.continuity.HistorySearch.Record> {
        val out = ArrayList<com.openminis.app.agent.continuity.HistorySearch.Record>()
        for (m in _messages.value) {
            val body = m.content.trim()
            when (m.role) {
                "user" -> if (body.isNotEmpty()) {
                    out.add(com.openminis.app.agent.continuity.HistorySearch.Record(m.id, "user", body))
                }

                "assistant" -> {
                    if (body.isNotEmpty()) {
                        out.add(com.openminis.app.agent.continuity.HistorySearch.Record(m.id, "assistant", body))
                    }
                    for (b in m.toolBlocks) {
                        val content = b.content.trim()
                        if (content.isEmpty()) continue
                        val label = b.toolName.ifEmpty { b.toolTitle.ifEmpty { "tool" } }
                        out.add(
                            com.openminis.app.agent.continuity.HistorySearch.Record(
                                "${m.id}#${b.id}",
                                "tool",
                                "$label: $content",
                            ),
                        )
                    }
                }
            }
        }
        return out
    }

    private suspend fun executeFindSkills(argsJson: String): ToolExecutionResult {
        val title = runCatching { JSONObject(argsJson).optString("tool_title", "Find skills") }
            .getOrDefault("Find skills")
        val args = try { JSONObject(argsJson) } catch (_: Exception) {
            return ToolExecutionResult("Error: invalid arguments", false, toolTitle = title)
        }
        val repository = skillRepository
            ?: return ToolExecutionResult("Error: Skills are not available in this session.", false, toolTitle = title)
        val radar = com.openminis.app.skills.radar.SkillRadar(context, repository)
        return when (val action = args.optString("action", "search").trim().lowercase()) {
            "search", "" -> {
                val query = args.optString("query", "").trim()
                if (query.isEmpty()) {
                    return ToolExecutionResult(
                        "Error: `query` is required for a search (e.g. \"pdf form filling\").",
                        false, toolTitle = title,
                    )
                }
                val limit = args.optInt("limit", 6).coerceIn(1, 12)
                val (candidates, note) = radar.search(query, limit, autoRefresh = true)
                com.openminis.app.observability.TraceLog.record(
                    kind = "skill", name = "find_skills", durationMs = 0, ok = candidates.isNotEmpty(),
                    summary = "search \"${query.take(60)}\" -> ${candidates.size}",
                )
                if (candidates.isEmpty()) {
                    ToolExecutionResult(
                        "No skill in the ecosystem matched \"$query\".$note " +
                            "Say so plainly and use your own tools instead; you can retry with broader words " +
                            "or action=refresh if the index looks stale.",
                        true, toolTitle = title,
                    )
                } else {
                    val body = buildString {
                        append("Skills matching \"").append(query).append("\" — ").append(note).append('\n')
                        candidates.forEachIndexed { i, c ->
                            append(i + 1).append(". ").append(c.name)
                            if (c.description.isNotEmpty()) {
                                append(" — ").append(c.description.replace('\n', ' ').take(110))
                            }
                            append(" [").append(c.repo).append("] id=").append(c.id).append('\n')
                        }
                        append("\nInstall the one that actually fits: find_skills action=install id=<id>, ")
                        append("then skill_load it. Do not install a loosely related skill.")
                    }
                    ToolExecutionResult(body.trimEnd(), true, toolTitle = title)
                }
            }
            "install" -> {
                val id = args.optString("id", "").trim()
                val url = args.optString("url", "").trim()
                if (id.isEmpty() && url.isEmpty()) {
                    return ToolExecutionResult("Error: give `id` from a search result or a SKILL.md `url`.", false, toolTitle = title)
                }
                val message = radar.install(if (id.isNotEmpty()) id else url)
                val ok = !message.startsWith("Error") && !message.startsWith("Install failed")
                com.openminis.app.observability.TraceLog.record(
                    kind = "skill", name = "find_skills", durationMs = 0, ok = ok,
                    summary = "install ${(id.ifEmpty { url }).take(80)}",
                )
                ToolExecutionResult(message, ok, toolTitle = title)
            }
            "refresh" -> {
                val report = radar.refresh(force = true)
                ToolExecutionResult(report, !report.startsWith("Refresh failed"), toolTitle = title)
            }
            "sources" -> {
                val lines = buildString {
                    append("Sources (index: ").append(radar.indexAge()).append("):\n")
                    radar.sources().forEach { s ->
                        append("- ").append(s.repo).append(" @").append(s.branch)
                        if (s.official) append(" (official)")
                        append('\n')
                    }
                    append("Refresh happens automatically when the index is older than 24h.")
                }
                ToolExecutionResult(lines, true, toolTitle = title)
            }
            "add_source" -> ToolExecutionResult(radar.addSource(args.optString("source", "")), true, toolTitle = title)
            "remove_source" -> ToolExecutionResult(radar.removeSource(args.optString("source", "")), true, toolTitle = title)
            else -> ToolExecutionResult("Error: unknown action \"$action\".", false, toolTitle = title)
        }
    }

    private fun executeSandboxMode(argsJson: String): ToolExecutionResult {
        val title = runCatching { JSONObject(argsJson).optString("tool_title", "Sandbox mode") }
            .getOrDefault("Sandbox mode")
        val args = try { JSONObject(argsJson) } catch (_: Exception) {
            return ToolExecutionResult("Error: invalid arguments", false, toolTitle = title)
        }
        val action = args.optString("action", "show").trim().lowercase()
        val current = com.openminis.app.agent.guard.SandboxMode.get(context)
        if (action == "show" || action.isEmpty()) {
            return ToolExecutionResult("Sandbox mode: ${current.id} — ${current.label()}.", true, toolTitle = title)
        }
        if (action != "set") return ToolExecutionResult("Error: unknown action \"$action\"", false, toolTitle = title)
        val target = com.openminis.app.agent.guard.SandboxMode.parse(args.optString("mode", ""))
            ?: return ToolExecutionResult(
                "Error: `mode` must be read_only, workspace_write or full", false, toolTitle = title,
            )
        com.openminis.app.agent.guard.SandboxMode.set(context, target)
        com.openminis.app.observability.TraceLog.record(
            kind = "guard", name = "sandbox_mode", durationMs = 0, ok = true,
            summary = "${current.id} -> ${target.id}",
        )
        return ToolExecutionResult(
            "Sandbox mode switched to ${target.id} — ${target.label()}.",
            true,
            toolTitle = title,
        )
    }

    private fun executeAgentForge(argsJson: String): ToolExecutionResult {
        val title = runCatching { JSONObject(argsJson).optString("tool_title", "Forge sub agent") }
            .getOrDefault("Forge sub agent")
        val args = try { JSONObject(argsJson) } catch (e: Exception) {
            return ToolExecutionResult("Error: invalid arguments (${e.javaClass.simpleName})", false, toolTitle = title)
        }
        val action = args.optString("action", "create").trim().lowercase()
        val roster = providerRepository.subAgents
        return when (action) {
            "list" -> ToolExecutionResult(
                com.openminis.app.agent.fleet.AgentForge.renderRoster(roster), true, toolTitle = title,
            )

            "remove" -> {
                val target = args.optString("target", "").trim()
                if (target.isEmpty()) {
                    return ToolExecutionResult("Error: `target` is required for remove", false, toolTitle = title)
                }
                val def = com.openminis.app.agent.fleet.AgentForge.match(roster, target)
                    ?: return ToolExecutionResult("Error: no sub agent named \"$target\"", false, toolTitle = title)
                if (def.isBuiltIn) {
                    return ToolExecutionResult(
                        "Error: the built-in sub agent cannot be removed", false, toolTitle = title,
                    )
                }
                providerRepository.deleteSubAgent(def.id)
                com.openminis.app.observability.TraceLog.record(
                    kind = "forge", name = "remove", durationMs = 0, ok = true,
                    summary = "removed sub agent \"${def.name}\"",
                )
                ToolExecutionResult("Removed \"${def.name}\". The roster now holds " +
                    "${providerRepository.subAgents.count { !it.isBuiltIn }} custom sub agents.", true, toolTitle = title)
            }

            "create", "preview" -> {
                val req = com.openminis.app.agent.fleet.AgentForge.Request(
                    name = args.optString("name", ""),
                    whenToUse = args.optString("when_to_use", ""),
                    goal = args.optString("goal", ""),
                    steps = stringList(args.optJSONArray("steps")),
                    constraints = stringList(args.optJSONArray("constraints")),
                    outputFormat = args.optString("output_format", ""),
                    instructions = args.optString("instructions", ""),
                    thinking = args.optString("thinking", ""),
                )
                com.openminis.app.agent.fleet.AgentForge.validate(req, roster)?.let {
                    return ToolExecutionResult(it, false, toolTitle = title)
                }
                val def = com.openminis.app.agent.fleet.AgentForge.build(req)
                val body = buildString {
                    append(if (action == "preview") "Preview (not saved) — " else "Saved — ")
                    append("sub agent \"").append(def.name).append("\" · ")
                    append(providerRepository.subAgents.count { !it.isBuiltIn }).append(" custom in roster\n\n")
                    append("When to use: ").append(def.description).append("\n\n")
                    append("Instructions (").append(def.instructions.length).append(" chars):\n")
                    append(def.instructions).append("\n\n")
                    append("Delegate to it with subagent_task and `agent: ").append(def.name).append("`.")
                }
                if (action == "create") {
                    providerRepository.upsertSubAgent(def)
                    com.openminis.app.observability.TraceLog.record(
                        kind = "forge", name = "create", durationMs = 0, ok = true,
                        summary = "sub agent \"${def.name}\" (${def.instructions.length} chars)",
                    )
                }
                ToolExecutionResult(body, true, toolTitle = title)
            }

            else -> ToolExecutionResult("Error: unknown action \"$action\"", false, toolTitle = title)
        }
    }

    private fun stringList(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "").trim()
            if (v.isNotEmpty()) out.add(v)
        }
        return out
    }

    private fun executeInspectRunStatus(argsJson: String): ToolExecutionResult {
        val title = runCatching { JSONObject(argsJson).optString("tool_title", "Inspect run status") }
            .getOrDefault("Inspect run status")
        val goal = com.openminis.app.agent.goal.SessionGoals.load(context, activeSessionId)
        val counts = com.openminis.app.tools.TodoTools.counts(context, activeSessionId)
        val runningJobs = runCatching {
            com.openminis.app.agent.jobs.AgentJobRegistry.jobs.value.count { it.value.isActive }
        }.getOrDefault(0)
        val records = conversationRecords()
        return ToolExecutionResult(buildString {
            append("Goal: ").append(goal?.let { "\"${it.text}\"" } ?: "none").append('\n')
            append("Task plan: ")
            if (counts == null) append("none") else {
                append(counts.completed).append('/').append(counts.total).append(" completed · ")
                append(counts.inProgress).append(" in progress · ").append(counts.pending).append(" pending")
            }
            append('\n')
            append("Background jobs running: ").append(runningJobs).append('\n')
            append("Messages in context: ").append(_messages.value.size).append('\n')
            append("Indexed records for history_search: ").append(records.size).append('\n')
            append("Model: ").append(currentModel?.id ?: "unknown")
            currentWatermarks()?.let { wm ->
                append(" · window ").append(wm.window).append(" · usable ").append(wm.usable)
                append(" · compaction at ").append(wm.trigger)
                if (wm.mode != com.openminis.app.ui.chat.ContextTrimPlanner.Mode.NORMAL) {
                    append(" (").append(wm.mode.name.lowercase()).append(" budget)")
                }
            }
            append(" · output ceiling ").append(currentModelMaxOutputTokens ?: "provider default")
            if (goal == null && counts == null) {
                append("\n\nNothing is tracked as unfinished. Do not claim completion beyond what the transcript shows.")
            }
        }, true, toolTitle = title)
    }

    private fun withWriteCheckpoint(argsJson: String, block: () -> ToolExecutionResult): ToolExecutionResult {
        val path = try { JSONObject(argsJson).optString("path", "").trim() } catch (_: Exception) { "" }
        if (path.isEmpty()) return block()
        val file = java.io.File(path)
        val existedBefore = file.isFile
        val before = if (existedBefore) runCatching { file.readText() }.getOrNull() else null
        if (existedBefore && before == null) return block()
        val result = block()
        if (!result.success) return result
        val after = runCatching { file.readText() }.getOrNull() ?: return result
        writeCheckpoint.record(messages.value.size, path, before, after)
        return result
    }

    private suspend fun executeToolCached(
        name: String,
        argsJson: String,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>,
        assistantId: String,
        currentText: String,
    ): ToolExecutionResult {
        val cacheKey = ToolResultCache.keyFor(name, argsJson)
        if (cacheKey != null) {
            val hit = ToolResultCache.get(cacheKey)
            if (hit != null) {
                AppLogger.info(TAG_STREAM, "[tool-cache] served $name from cache id=${toolId.take(16)}")
                val title = try { JSONObject(argsJson).optString("tool_title", "") } catch (_: Exception) { "" }
                return ToolExecutionResult(hit + ToolResultCache.HIT_NOTE, true, toolTitle = title)
            }
        } else if (!ToolResultCache.isReadOnly(name)) {
            ToolResultCache.invalidateAll()
        }
        val startedAt = System.currentTimeMillis()
        val fresh = executeTool(name, argsJson, toolId, toolBlocks, assistantId, currentText)
        if (!fresh.success) captureToolFailure(name, fresh.output)
        com.openminis.app.observability.TraceLog.record(
            kind = "tool",
            name = name,
            durationMs = System.currentTimeMillis() - startedAt,
            ok = fresh.success,
            summary = fresh.output,
        )
        if (cacheKey != null && fresh.success) ToolResultCache.put(cacheKey, fresh.output)
        return fresh
    }

    private fun guardWriteTarget(
        name: String,
        argsJson: String,
        toolTitle: String,
    ): ToolExecutionResult? {
        if (name != FileWriteTool.NAME && name != FileEditTool.NAME) return null
        if (com.openminis.app.agent.guard.SandboxMode.get(context) == com.openminis.app.agent.guard.SandboxMode.READ_ONLY) {
            com.openminis.app.observability.TraceLog.record(
                kind = "guard", name = name, durationMs = 0, ok = false,
                summary = "read-only mode refused $name",
            )
            return ToolExecutionResult(
                "Refused: the session sandbox mode is read-only, so no file can be created or edited. " +
                    "Report what you found; the user can switch to workspace-write to allow edits.",
                false,
                toolTitle = toolTitle,
            )
        }
        val path = try { JSONObject(argsJson).optString("path", "") } catch (_: Exception) { "" }
        if (path.isBlank()) return null
        val body = try {
            val o = JSONObject(argsJson)
            when (name) {
                FileWriteTool.NAME -> o.optString("content", "")
                FileEditTool.NAME -> o.optString("new_string", "")
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
        com.openminis.app.agent.guard.SecretScan.first(body)?.let { hit ->
            com.openminis.app.observability.TraceLog.record(
                kind = "guard", name = name, durationMs = 0, ok = false,
                summary = "secret refused in ${hit.kind}",
            )
            return ToolExecutionResult(
                com.openminis.app.agent.guard.SecretScan.guidance(hit), false, toolTitle = toolTitle,
            )
        }
        val rootfsHost = try {
            com.openminis.app.sandbox.RootfsManager.getInstance(context).rootfsDir.absolutePath
        } catch (_: Exception) {
            null
        }
        val verdict = com.openminis.app.agent.guard.PathAccessPolicy.evaluate(path, rootfsHost)
        if (!verdict.blocked) return null
        com.openminis.app.observability.TraceLog.record(
            kind = "guard",
            name = name,
            durationMs = 0,
            ok = false,
            summary = "denied write to $path (zone=${verdict.zone.label})",
        )
        return ToolExecutionResult(verdict.reason, false, toolTitle = toolTitle)
    }

    private suspend fun executeTool(
        name: String,
        argsJson: String,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>,
        assistantId: String,
        currentText: String,
    ): ToolExecutionResult {
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", name) } catch (_: Exception) { name }

        if (name == com.openminis.app.tools.McpNativeTools.GATEWAY ||
            name.startsWith(com.openminis.app.tools.McpNativeTools.PREFIX)
        ) {
            return executeMcpCall(name, argsJson, toolId, toolBlocks, assistantId, currentText)
        }

        guardWriteTarget(name, argsJson, toolTitle)?.let { return it }

        return when (name) {
            FileReadTool.NAME -> {
                val result = FileReadTool.execute(argsJson, fsSessionId, context)
                if (result.success) {
                    runCatching {
                        val readPath = JSONObject(argsJson).optString("path", "")
                        if (readPath.isNotEmpty()) {
                            skillRepository?.skillIdFromPath(readPath)?.let { sid ->
                                skillRepository.recordSkillUse(sid)
                            }
                        }
                    }
                }
                result
            }
            FileWriteTool.NAME -> withWriteCheckpoint(argsJson) {
                FileWriteTool.execute(argsJson, fsSessionId, context).also {
                    if (it.success) maybeReloadSkillsForPath(argsJson)
                }
            }
            FileEditTool.NAME -> withWriteCheckpoint(argsJson) {
                FileEditTool.execute(argsJson, fsSessionId, context).also {
                    if (it.success) maybeReloadSkillsForPath(argsJson)
                }
            }
            com.openminis.app.browser.lightpanda.LightPandaTool.NAME ->
                com.openminis.app.browser.lightpanda.LightPandaTool.execute(context, argsJson, toolTitle)
            com.openminis.app.tools.CheckpointRestoreTool.NAME -> executeCheckpointRestore(argsJson)
            com.openminis.app.tools.ApplyPatchTool.NAME ->
                com.openminis.app.tools.ApplyPatchTool.execute(argsJson, fsSessionId, context)
            com.openminis.app.tools.RepoMapTool.NAME ->
                com.openminis.app.tools.RepoMapTool.execute(argsJson, fsSessionId, context)
            ReadImageTool.NAME -> executeReadImageTool(argsJson)
            "shell_execute" -> executeShellCommand(argsJson, toolId, toolBlocks, assistantId, currentText).also {
                skillRepository?.requestReload("shell_execute")
            }
            "browser_use" ->
                if (com.openminis.app.tools.AgentToolSwitch.BROWSER.isEnabled(context)) {
                    executeBrowserUseTool(argsJson)
                } else {
                    toolDisabledResult("browser_use")
                }
            "memory_write" -> executeMemoryWriteTool(argsJson)
            com.openminis.app.agent.jobs.HelperRunner.TOOL_NAME,
            com.openminis.app.agent.jobs.HelperRunner.LEGACY_TOOL_NAME,
            com.openminis.app.agent.jobs.HelperRunner.LEGACY_STATUS_TOOL_NAME ->
                if (com.openminis.app.tools.AgentToolSwitch.AGENTS.isEnabled(context)) {
                    val action = try {
                        JSONObject(argsJson).optString("action", "").trim().lowercase()
                    } catch (_: Exception) {
                        ""
                    }
                    val isLegacyStatus =
                        name == com.openminis.app.agent.jobs.HelperRunner.LEGACY_STATUS_TOOL_NAME
                    when {
                        action == "resume" -> executeResumeAgents(argsJson)
                        action == "status" || action == "steer" || action == "cancel" ||
                            (isLegacyStatus && action.isEmpty()) -> executeAgentStatus(argsJson)
                        else ->
                            executeDelegateTask(argsJson, toolId, toolBlocks, assistantId, currentText)
                    }
                } else {
                    ToolExecutionResult(
                        com.openminis.app.agent.jobs.HelperRunner.rejectionJson(
                            "tools_disabled",
                            "Agents are turned off in this app's settings; delegate_task cannot be used. " +
                                "Do not try it again in this conversation.",
                        ),
                        false,
                    )
                }
            "memory_get" -> executeMemoryGetTool(argsJson)
            "memory_fact" -> executeMemoryFactTool(argsJson)
            com.openminis.app.tools.TodoTools.NAME ->
                com.openminis.app.tools.TodoTools.execute(context, activeSessionId, argsJson)
            com.openminis.app.tools.ContinuityTools.GOAL ->
                com.openminis.app.tools.ContinuityTools.executeGoal(context, activeSessionId, argsJson)
            com.openminis.app.tools.ContinuityTools.HISTORY_SEARCH ->
                com.openminis.app.tools.ContinuityTools.executeHistorySearch(conversationRecords(), argsJson)
            com.openminis.app.tools.ContinuityTools.HISTORY_READ ->
                com.openminis.app.tools.ContinuityTools.executeHistoryRead(conversationRecords(), argsJson)
            com.openminis.app.tools.ContinuityTools.RUN_STATUS -> executeInspectRunStatus(argsJson)
            com.openminis.app.tools.AgentForgeTool.NAME -> executeAgentForge(argsJson)
            com.openminis.app.tools.SandboxModeTool.NAME -> executeSandboxMode(argsJson)
            com.openminis.app.tools.FindSkillsTool.NAME -> executeFindSkills(argsJson)
            com.openminis.app.tools.LessonTool.NAME -> executeLessonWrite(argsJson)
            com.openminis.app.tools.GitHubTool.NAME -> executeGitHub(argsJson)
            com.openminis.app.tools.SkillLoadTool.NAME -> {
                val repo = skillRepository
                if (repo == null) ToolExecutionResult("Error: Skills not available", false)
                else com.openminis.app.tools.SkillLoadTool.execute(context, repo, argsJson)
            }
            com.openminis.app.tools.SkillSaveTool.NAME -> {
                val repo = skillRepository
                if (repo == null) ToolExecutionResult("Error: Skills not available", false)
                else com.openminis.app.tools.SkillSaveTool.execute(context, repo, argsJson)
            }
            com.openminis.app.agent.userask.UserAskPromptText.TOOL_NAME -> if (!userAskExposedThisRun) {
                ToolExecutionResult(com.openminis.app.agent.userask.UserAskPromptText.NOT_AVAILABLE, false, toolTitle = "")
            } else if (userAskDeferredThisRun) {
                userAskAskedThisRun = true
                com.openminis.app.agent.userask.UserAskToolRunner.executeDeferred(
                    argsJson = argsJson,
                    toolCallId = toolId,
                    sessionId = activeSessionId,
                    sessionTitle = overlaySessionTitle(),
                    beforeShow = { ask ->
                        ensureUserAskRecovery()
                        val snap = userAskBatchSnapshot
                        com.openminis.app.agent.userask.UserAskRecoveryCoordinator.saveNew(
                            com.openminis.app.agent.userask.UserAskRecord(
                                ask = ask,
                                assistantPartsJson = snap?.first,
                                siblingToolUseIds = snap?.second?.filter { it != toolId } ?: emptyList(),
                            ),
                        )
                    },
                )
            } else
                com.openminis.app.agent.userask.UserAskToolRunner.execute(
                    argsJson = argsJson,
                    toolCallId = toolId,
                    sessionId = activeSessionId,
                    sessionTitle = overlaySessionTitle(),
                    timeoutMinutes = com.openminis.app.agent.userask.UserAskSettings.timeoutMinutes(context),
                    beforeShow = { ask ->
                        ensureUserAskRecovery()
                        val snap = userAskBatchSnapshot
                        com.openminis.app.agent.userask.UserAskRecoveryCoordinator.saveNew(
                            com.openminis.app.agent.userask.UserAskRecord(
                                ask = ask,
                                assistantPartsJson = snap?.first,
                                siblingToolUseIds = snap?.second?.filter { it != toolId } ?: emptyList(),
                            ),
                        )
                    },
                )
            else -> ToolExecutionResult("Unknown tool: $name", false)
        }
    }

    private fun childLiveMessages(childVm: ChatViewModel): List<ChatMessage> {
        val msgs = childVm.messages.value
        val stream = childVm.streamingById.value
        return if (stream.isEmpty()) msgs else mergeStreamingOverlay(msgs, stream)
    }

    private fun childLiveToolBlock(childVm: ChatViewModel): AssistantBlock? =
        childLiveMessages(childVm).lastOrNull { it.role == "assistant" }
            ?.toolBlocks?.lastOrNull { it.kind == "tool_use" }

    private suspend fun executeDelegateTask(
        argsJson: String,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>,
        assistantId: String,
        currentText: String,
    ): ToolExecutionResult {
        val hr = com.openminis.app.agent.jobs.HelperRunner
        val detachSeen = delegateDetach.value
        val args = hr.parseArgs(argsJson)
        val title = args.title.ifEmpty { "agent" }
        fun reject(reason: String, detail: String): ToolExecutionResult {
            AppLogger.warning(TAG, "[delegate_task] REJECTED $reason: $detail")
            return ToolExecutionResult(hr.rejectionJson(reason, detail), false, toolTitle = title)
        }
        if (args.task.isEmpty()) return reject("empty_task", "`task` is required and must describe the whole job.")
        if (isHelper) return reject("depth_limit", "Helpers cannot delegate further (max delegation depth is 1). Do the work yourself.")
        if (!com.openminis.app.agent.jobs.AgentSettings.isEnabled(context)) {
            return reject("disabled", "Delegation is turned off in Settings › Agents.")
        }
        val roster = providerRepository.subAgents
        val subAgent = com.openminis.app.data.model.SubAgentRoster.resolve(args.agentName, roster)
            ?: return reject(
                "unknown_agent",
                "No sub agent named \"${args.agentName.orEmpty()}\". " +
                    "Available: ${roster.joinToString(", ") { it.name }}. " +
                    "Omit `agent` to use the general one.",
            )
        val parentSid = activeSessionId
        val registry = com.openminis.app.agent.jobs.AgentJobRegistry
        val fromQueue = try {
            JSONObject(argsJson).optBoolean(hr.QUEUED_REENTRY_KEY, false)
        } catch (_: Exception) {
            false
        }
        val myIdx = toolBlocks.indexOfFirst { it.id == toolId }
        val priorDelegates = toolBlocks.take(myIdx.coerceAtLeast(0))
            .count {
                it.kind == "tool_use" && hr.isSubAgentToolName(it.toolName) &&
                    !hr.isControlOnly(it.toolArgs.ifEmpty { null }, it.content.ifEmpty { null })
            }
        val overTurnAllowance = !fromQueue && priorDelegates >= hr.MAX_PER_ASSISTANT_TURN

        if (overTurnAllowance || !registry.canStartChildJob(parentSid)) {
            registry.registerQueuedStarter(parentSid) { item ->
                startQueuedDelegation(item.argsJson, item.toolUseId)
            }
            registry.registerInterruptedCounter(parentSid) { countInterruptedChildren(parentSid) }
            val queued = registry.enqueueDelegation(
                com.openminis.app.agent.jobs.AgentJobRegistry.QueuedDelegation(
                    parentSessionId = parentSid,
                    argsJson = argsJson,
                    toolUseId = toolId,
                ),
            )
            if (!queued) {
                return reject(
                    "helper_limit",
                    "${com.openminis.app.agent.jobs.AgentJobRegistry.maxConcurrentChildJobsPerParent} sub agents are " +
                        "already running in this conversation and its queue is full " +
                        "(${com.openminis.app.agent.jobs.AgentJobRegistry.MAX_QUEUED_DELEGATIONS_PER_PARENT} waiting). " +
                        "Wait for some to finish, then delegate this task again — it was NOT queued.",
                )
            }
            val detail = if (overTurnAllowance) {
                "More than ${hr.MAX_PER_ASSISTANT_TURN} delegations in one turn. This task is QUEUED and will " +
                    "start automatically as slots free; its result arrives as a new message like any other. " +
                    "Do not re-delegate it."
            } else {
                "All ${com.openminis.app.agent.jobs.AgentJobRegistry.maxConcurrentChildJobsPerParent} sub agent " +
                    "slots of this conversation are busy. " +
                    "This task is QUEUED and will start automatically when one frees; its result arrives as a " +
                    "new message like any other. Do not re-delegate it."
            }
            val json = hr.queuedJson(
                queuedBehind = registry.runningChildJobCount(parentSid) + registry.queuedCount(parentSid) - 1,
                detail = detail,
            )
            withContext(Dispatchers.Main) {
                val i = toolBlocks.indexOfFirst { it.id == toolId }
                if (i >= 0) {
                    toolBlocks[i] = toolBlocks[i].copy(content = json, toolStatus = ToolBlockStatus.RUNNING)
                    updateAssistantMessage(assistantId, currentText, true, toolBlocks)
                }
            }
            AppLogger.info(TAG, "[subagent_task] QUEUED tool=${toolId.take(12)} behind ${registry.runningChildJobCount(parentSid)} running in ${parentSid.take(8)}")
            return ToolExecutionResult(json, true, toolTitle = title)
        }
        val parentRow = chatRepository.getSession(parentSid)
        val resolution = com.openminis.app.agent.jobs.ModelTierResolver.resolveForSubAgent(
            pinnedGroupId = subAgent.modelGroupId,
            choice = args.modelChoice,
            repo = providerRepository,
            parentBindingJson = parentRow?.modelBinding, parentModelId = parentRow?.modelId ?: currentModel?.id,
            parentActiveEntryId = _activeEntryId.value,
        ) ?: return reject("no_model", "No model is configured for a helper to run on.")

        val subAgentWireName = subAgent.name
        val subAgentOrigin = resolution.origin
        val subAgentGroupUnavailable = resolution.modelGroupUnavailable

        val child = chatRepository.createSession(
            modelId = resolution.seedModelId,
            title = hr.childSessionTitle(context, title, subAgent.name.takeIf { !subAgent.isBuiltIn }),
            memoryEnabled = false,
            parentSessionId = parentSid,
            parentToolUseId = toolId,
        )
        parentRow?.source?.let { chatRepository.dao.updateSource(child.id, it) }
        resolution.bindingJson?.let { chatRepository.updateSessionBinding(child.id, it, resolution.seedModelId) }

        var seededThinking: String? = null
        run {
            val pinnedGroupLevel = subAgent.modelGroupId
                ?.let { providerRepository.group(it) }?.defaultThinkingLevel
            val level = subAgent.thinkingLevelOverride
                ?: pinnedGroupLevel
                ?: parentRow?.thinkingOverride?.let { ThinkingLevel.decoded(it) }
            if (level != null && level.isEnabled) {
                chatRepository.dao.updateThinkingOverride(child.id, level.name)
                seededThinking = level.name
            }
        }

        val job = registry.register(
            title = title, origin = com.openminis.app.agent.jobs.AgentJobOrigin.TOOL,
            trigger = com.openminis.app.agent.jobs.AgentJobTrigger.Immediate,
            target = com.openminis.app.agent.jobs.AgentJobTarget.ChildOfCurrent(parentSid, toolId),
            prompt = args.task,
            then = if (args.wait) com.openminis.app.agent.jobs.AgentJobThen.None
            else com.openminis.app.agent.jobs.AgentJobThen.FollowUpParent(null),
            agentName = subAgentWireName,
        )
        registry.setModelOrigin(job.id, resolution.origin.wire)
        registry.registerInterruptedCounter(parentSid) { countInterruptedChildren(parentSid) }
        registry.setTierUsed(job.id, resolution.tierUsed.wire)
        registry.setProgressLevel(job.id, if (args.wait) "none" else args.progressLevel)

        val childVm = withContext(Dispatchers.Main) {
            com.openminis.app.debug.HeadlessChatRunner.viewModelFor(
                context, child.id, com.openminis.app.ui.chat.ChatViewModelStore.PoolKind.CHILD,
            ).also {
                it.helperConfig = com.openminis.app.agent.jobs.HelperConfig(
                    parentSessionId = parentSid, parentToolUseId = toolId, jobId = job.id,
                    maxTurns = args.maxTurns, title = title, tierUsed = resolution.tierUsed,
                    agentName = subAgent.name.takeIf { !subAgent.isBuiltIn },
                    agentInstructions = subAgent.instructions,
                )
                it.adoptBrowserTabPool(browserTabPool)
            }
        }
        registry.registerSteerHook(job.id) { msg -> childVm.enqueueSteer(msg) }
        registry.registerMissedSteerDrain(job.id) { childVm.drainMissedSteers() }
        registry.registerTabRelease(job.id) {
            viewModelScope.launch { browserTabPool.releaseTabs(child.id) }
        }
        val startedAt = System.currentTimeMillis()
        suspend fun writeBlock(content: String, status: ToolBlockStatus? = null) {
            if (assistantId.isEmpty()) {
                writeDelegateBlock(toolId, content, status)
                return
            }
            val i = toolBlocks.indexOfFirst { it.id == toolId }
            if (i >= 0) toolBlocks[i] = toolBlocks[i].copy(content = content, toolTitle = toolBlocks[i].toolTitle.ifEmpty { title })
            withContext(Dispatchers.Main) {
                writeDelegateBlock(toolId, content, status)
                if (i >= 0) updateAssistantMessage(assistantId, currentText, true, toolBlocks)
            }
        }
        fun childCurrentTool(): Pair<String, String> {
            val last = childLiveToolBlock(childVm) ?: return "" to ""
            return last.toolName to last.toolTitle
        }
        writeBlock(hr.progressJson(child.id, title, "", "starting", 0, background = !args.wait), ToolBlockStatus.RUNNING)

        val ready = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5_000L) { childVm.activeEntryId.first { it != null } }
        }
        if (ready == null) {
            registry.setThen(job.id, com.openminis.app.agent.jobs.AgentJobThen.None)
            registry.finish(job.id, com.openminis.app.agent.jobs.AgentJobState.FAILED, null)
            clearDelegateOverride(toolId)
            return reject("child_start_failed", "The helper session could not resolve a provider.")
        }
        val outcome = withContext(Dispatchers.Main) { childVm.submitPrompt(hr.childPrompt(args)) }
        if (outcome !is SubmitOutcome.Sent) {
            registry.setThen(job.id, com.openminis.app.agent.jobs.AgentJobThen.None)
            registry.finish(job.id, com.openminis.app.agent.jobs.AgentJobState.FAILED, null)
            clearDelegateOverride(toolId)
            return reject("child_start_failed", "The helper session did not start ($outcome).")
        }
        registry.markRunning(job.id, child.id) {
            if (childVm.isStreaming.value) childVm.cancelStream()
        }
        com.openminis.app.agent.jobs.SubAgentTerminationStore.shared.begin(child.id, job.id, parentSid)
        AppLogger.info(TAG, "[delegate_task] START job=${job.id.take(8)} child=${child.id.take(8)} parent=${parentSid.take(8)} tier=${resolution.tierUsed.wire} model=${resolution.modelLabel} budget=${args.minutes}m wait=${args.wait} progress=${args.progressLevel}")

        if (!args.wait) {
            return startBackgroundHelper(job.id, childVm, child.id, toolId, resolution, args, title, startedAt, converted = false, subAgentWireName = subAgentWireName)
        }

        // ── Wait mode ────────────────────────────────────────────────────
        var finalStatus = "completed"
        var wrapUpAskedAt: Long? = null
        var wrapUpToolStopped = false
        try {
            while (childVm.isStreaming.value) {
                val now = System.currentTimeMillis()
                if (now - startedAt >= args.minutes * 60_000L) {
                    val asked = wrapUpAskedAt
                    if (asked == null) {
                        wrapUpAskedAt = now
                        childVm.helperWrapUpRequested = true
                        AppLogger.warning(TAG, "[delegate_task] budget over after ${args.minutes}m — asking child ${child.id.take(8)} to wrap up")
                    } else if (now - asked >= hr.WRAP_UP_GRACE_MS) {
                        finalStatus = "timeout"
                        AppLogger.warning(TAG, "[delegate_task] TIMEOUT after ${args.minutes}m (+grace) — cancelling child ${child.id.take(8)}")
                        withContext(Dispatchers.Main) { childVm.cancelStream() }
                        withTimeoutOrNull(5_000L) { childVm.isStreaming.first { !it } }
                        break
                    } else if (now - asked >= hr.WRAP_UP_TOOL_PATIENCE_MS && !wrapUpToolStopped) {
                        wrapUpToolStopped = true
                        AppLogger.warning(TAG, "[delegate_task] child ${child.id.take(8)} still inside a tool — stopping it for the wrap-up turn")
                        ExecutionCoordinator.stopCurrentCommand(child.id)
                    }
                }
                val userFollowUp = _promptQueue.value.any { it.origin == QueuedPromptOrigin.USER }
                if (userFollowUp || delegateDetach.value != detachSeen) {
                    AppLogger.info(
                        TAG,
                        "[delegate_task] ${if (userFollowUp) "user follow-up queued" else "user stopped the current tool"} " +
                            "while waiting — converting job ${job.id.take(8)} to background",
                    )
                    registry.setThen(job.id, com.openminis.app.agent.jobs.AgentJobThen.FollowUpParent(null))
                    return startBackgroundHelper(job.id, childVm, child.id, toolId, resolution, args, title, startedAt, converted = true, subAgentWireName = subAgentWireName)
                }
                val (tool, activity) = childCurrentTool()
                writeBlock(hr.progressJson(child.id, title, tool, activity, now - startedAt))
                withTimeoutOrNull(1_000L) { delegateDetach.first { it != detachSeen } }
            }
        } catch (e: CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                if (childVm.isStreaming.value) childVm.cancelStream()
                registry.finish(job.id, com.openminis.app.agent.jobs.AgentJobState.CANCELLED, null)
                recordChildTermination(
                    childVm, child.id, job.id, parentSid, loopStatus = "cancelled",
                    budgetExpired = childVm.helperWrapUpRequested,
                    errorText = null, resultText = "",
                    delivery = com.openminis.app.agent.jobs.SubAgentTermination.Delivery.INLINE,
                )
                clearDelegateOverride(toolId)
            }
            throw e
        }

        val elapsed = System.currentTimeMillis() - startedAt
        val facts = hr.childRunFacts(chatRepository.dao.loadMessages(child.id))
        val childError = childVm.messages.value.lastOrNull { it.role == "assistant" }?.error
        if (finalStatus == "completed") {
            finalStatus = when {
                registry.job(job.id)?.state == com.openminis.app.agent.jobs.AgentJobState.CANCELLED -> "cancelled"
                childError != null -> "failed"
                else -> "completed"
            }
        }
        val termination = recordChildTermination(
            childVm, child.id, job.id, parentSid,
            loopStatus = finalStatus,
            budgetExpired = wrapUpAskedAt != null || childVm.helperWrapUpRequested,
            errorText = childError,
            resultText = facts.lastText,
            delivery = com.openminis.app.agent.jobs.SubAgentTermination.Delivery.INLINE,
        )
        finalStatus = hr.reportedStatus(finalStatus, facts.lastText, termination)
        val summary = hr.runSummaryLine(facts.toolNames, facts.turns, facts.input, facts.output, facts.cacheRead)
        registry.setSummaryLine(job.id, summary)
        registry.finish(
            job.id,
            when (finalStatus) {
                "completed", com.openminis.app.agent.jobs.HelperRunner.NO_DELIVERABLE ->
                    com.openminis.app.agent.jobs.AgentJobState.DONE
                "cancelled" -> com.openminis.app.agent.jobs.AgentJobState.CANCELLED
                "timeout" -> com.openminis.app.agent.jobs.AgentJobState.TIMEOUT
                else -> com.openminis.app.agent.jobs.AgentJobState.FAILED
            },
            facts.lastText,
        )
        val json = hr.resultJson(
            finalStatus, facts.lastText, resolution.modelLabel, resolution.tierUsed, args.tierRequested,
            facts.turns, elapsed, child.id, job.id, summary,
                    agentName = subAgentWireName,
                    modelOrigin = resolution.origin,
                    modelGroupUnavailable = resolution.modelGroupUnavailable,
                    modelGroupName = resolution.modelGroupName,
                    wasResumed = job.wasResumed,
                    errorText = childError,
                    resolvedEntryId = resolution.resolvedEntryId,
                    resolvedProviderLabel = resolution.resolvedProviderLabel,
                    resolvedProviderType = resolution.resolvedProviderType,
                    resolvedModelId = resolution.resolvedModelId,
                    resolvedModelName = resolution.resolvedModelName,
                    thinkingLevel = seededThinking,
                    termination = termination,
        )
        clearDelegateOverride(toolId)
        AppLogger.info(TAG, "[delegate_task] END job=${job.id.take(8)} status=$finalStatus turns=${facts.turns} elapsed=${elapsed / 1000}s result=${facts.lastText.length}ch")
        return ToolExecutionResult(json, finalStatus == "completed", toolTitle = title)
    }

    private fun recordChildTermination(
        childVm: ChatViewModel,
        childId: String,
        jobId: String,
        parentSessionId: String?,
        loopStatus: String,
        budgetExpired: Boolean,
        errorText: String?,
        resultText: String,
        delivery: com.openminis.app.agent.jobs.SubAgentTermination.Delivery,
    ): com.openminis.app.agent.jobs.SubAgentTermination? = runCatching {
        val hr = com.openminis.app.agent.jobs.HelperRunner
        val tool = hr.lastToolSnapshot(childVm.messages.value)
        val t = hr.terminationOf(
            childSessionId = childId, jobId = jobId, parentSessionId = parentSessionId,
            loopStatus = loopStatus, budgetExpired = budgetExpired, errorText = errorText,
            history = childVm.agentHistorySnapshot(), resultText = resultText,
            lastTool = tool, closingAttempted = childVm.helperClosingAttempted, delivery = delivery,
        )
        com.openminis.app.agent.jobs.SubAgentTerminationStore.shared.end(t)
        AppLogger.info(
            TAG,
            "[subagent_term] child=${childId.take(8)} job=${jobId.take(8)} reason=${t.reason.wire} " +
                "lastTool=${tool?.name ?: "-"}/${tool?.status ?: "-"} partial=${t.partialOutputAvailable} " +
                "closing=${t.closingAttempted} delivery=${delivery.wire}",
        )
        t
    }.getOrElse {
        AppLogger.warning(TAG, "[subagent_term] could not record child=${childId.take(8)}: ${it.message}")
        null
    }

    private fun startBackgroundHelper(
        jobId: String,
        childVm: ChatViewModel,
        childId: String,
        toolId: String,
        resolution: com.openminis.app.agent.jobs.HelperModelResolution,
        args: com.openminis.app.agent.jobs.DelegateTaskArgs,
        title: String,
        startedAt: Long,
        converted: Boolean,
        subAgentWireName: String?,
    ): ToolExecutionResult {
        val hr = com.openminis.app.agent.jobs.HelperRunner
        val registry = com.openminis.app.agent.jobs.AgentJobRegistry
        fun childCurrentTool(): Pair<String, String> {
            val last = childLiveToolBlock(childVm) ?: return "" to ""
            return last.toolName to last.toolTitle
        }

        val mirror = viewModelScope.launch(Dispatchers.Default) {
            while (isActive && childVm.isStreaming.value) {
                kotlinx.coroutines.delay(1_000L)
                com.openminis.app.agent.jobs.AgentJobRegistry.drainIfStalled()
                if (!isActive || !childVm.isStreaming.value) return@launch
                val (tool, activity) = childCurrentTool()
                withContext(Dispatchers.Main) {
                    writeDelegateBlock(
                        toolId,
                        hr.progressJson(childId, title, tool, activity.ifEmpty { "running in background" }, System.currentTimeMillis() - startedAt, background = true),
                        ToolBlockStatus.RUNNING,
                    )
                }
            }
        }

        registry.setCompletionHook(jobId) { finished ->
            mirror.cancel()
            val termination = recordChildTermination(
                childVm, childId, finished.id, finished.target.parentSessionIdOrNull,
                loopStatus = hr.statusWord(finished.state),
                budgetExpired = childVm.helperWrapUpRequested,
                errorText = childVm.messages.value.lastOrNull { it.role == "assistant" }?.error,
                resultText = finished.resultText ?: "",
                delivery = com.openminis.app.agent.jobs.SubAgentTermination.Delivery.PENDING,
            )
            viewModelScope.launch(Dispatchers.IO) {
                val facts = hr.childRunFacts(chatRepository.dao.loadMessages(childId))
                val summary = hr.runSummaryLine(facts.toolNames, facts.turns, facts.input, facts.output, facts.cacheRead)
                registry.setSummaryLine(finished.id, summary)
                val result = finished.resultText ?: ""
                val status = hr.reportedStatus(hr.statusWord(finished.state), result, termination)
                val bgError = childVm.messages.value.lastOrNull { it.role == "assistant" }?.error
                val json = hr.resultJson(
                    status, result, resolution.modelLabel, resolution.tierUsed, args.tierRequested,
                    facts.turns, finished.elapsedMs ?: (System.currentTimeMillis() - startedAt), childId, finished.id,
                    summary, deliveredAs = "new turn in this conversation",
                    agentName = subAgentWireName,
                    modelOrigin = resolution.origin,
                    modelGroupUnavailable = resolution.modelGroupUnavailable,
                    modelGroupName = resolution.modelGroupName,
                    wasResumed = finished.wasResumed,
                    errorText = bgError,
                    resolvedEntryId = resolution.resolvedEntryId,
                    resolvedProviderLabel = resolution.resolvedProviderLabel,
                    resolvedProviderType = resolution.resolvedProviderType,
                    resolvedModelId = resolution.resolvedModelId,
                    resolvedModelName = resolution.resolvedModelName,
                    thinkingLevel = chatRepository.getSession(childId)?.thinkingOverride,
                    termination = termination,
                )
                val blockStatus = when (finished.state) {
                    com.openminis.app.agent.jobs.AgentJobState.DONE -> ToolBlockStatus.SUCCESS
                    com.openminis.app.agent.jobs.AgentJobState.CANCELLED -> ToolBlockStatus.CANCELLED
                    com.openminis.app.agent.jobs.AgentJobState.TIMEOUT -> ToolBlockStatus.TIMEOUT
                    else -> ToolBlockStatus.FAILED
                }
                withContext(Dispatchers.Main) { writeDelegateBlock(toolId, json, blockStatus) }
                persistFinalDelegateResult(toolId, json, finished.state == com.openminis.app.agent.jobs.AgentJobState.DONE)
                AppLogger.info(TAG, "[delegate_task] BACKGROUND END job=${finished.id.take(8)} state=${finished.state.wire}")
            }
        }

        viewModelScope.launch(Dispatchers.Default) {
            val finishedInTime = withTimeoutOrNull(args.minutes * 60_000L) { childVm.isStreaming.first { !it }; true }
            var timedOut = false
            if (finishedInTime == null && childVm.isStreaming.value) {
                AppLogger.warning(TAG, "[delegate_task] BACKGROUND budget over after ${args.minutes}m — asking child ${childId.take(8)} to wrap up")
                childVm.helperWrapUpRequested = true
                val idleAfterPatience = withTimeoutOrNull(hr.WRAP_UP_TOOL_PATIENCE_MS) { childVm.isStreaming.first { !it }; true } != null
                if (!idleAfterPatience && childVm.isStreaming.value) {
                    AppLogger.warning(TAG, "[delegate_task] BACKGROUND child ${childId.take(8)} still inside a tool — stopping it for the wrap-up turn")
                    ExecutionCoordinator.stopCurrentCommand(childId)
                    val idleAfterGrace = withTimeoutOrNull(hr.WRAP_UP_GRACE_MS - hr.WRAP_UP_TOOL_PATIENCE_MS) { childVm.isStreaming.first { !it }; true } != null
                    if (!idleAfterGrace && childVm.isStreaming.value) {
                        AppLogger.warning(TAG, "[delegate_task] BACKGROUND TIMEOUT (+grace) — cancelling child ${childId.take(8)}")
                        timedOut = true
                        withContext(Dispatchers.Main) { childVm.cancelStream() }
                        withTimeoutOrNull(5_000L) { childVm.isStreaming.first { !it } }
                    }
                }
            }
            val facts = hr.childRunFacts(chatRepository.dao.loadMessages(childId))
            val state = when {
                timedOut -> com.openminis.app.agent.jobs.AgentJobState.TIMEOUT
                childVm.messages.value.lastOrNull { it.role == "assistant" }?.error != null -> com.openminis.app.agent.jobs.AgentJobState.FAILED
                else -> com.openminis.app.agent.jobs.AgentJobState.DONE
            }
            registry.setSummaryLine(jobId, hr.runSummaryLine(facts.toolNames, facts.turns, facts.input, facts.output, facts.cacheRead))
            registry.finish(jobId, state, facts.lastText)
        }

        startProgressReporter(jobId, childVm, childId, title, startedAt, args.progressLevel)

        val json = hr.backgroundStartJson(
            jobId, childId, resolution.modelLabel, resolution.tierUsed, args.minutes, converted,
            resolution = resolution,
        )
        AppLogger.info(TAG, "[delegate_task] BACKGROUND START job=${jobId.take(8)} child=${childId.take(8)} budget=${args.minutes}m converted=$converted")
        return ToolExecutionResult(json, true, toolTitle = title)
    }

    private fun startProgressReporter(jobId: String, childVm: ChatViewModel, childId: String, title: String, startedAt: Long, level: String) {
        val hr = com.openminis.app.agent.jobs.HelperRunner
        val interval = when (level) {
            "frequent" -> hr.PROGRESS_INTERVAL_FREQUENT_MS
            "moderate" -> hr.PROGRESS_INTERVAL_MODERATE_MS
            else -> return
        }
        val onlyOnChange = level == "frequent"
        val registry = com.openminis.app.agent.jobs.AgentJobRegistry
        var lastSignature = ""
        var pendingPromptId: String? = null
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                kotlinx.coroutines.delay(interval)
                val job = registry.job(jobId) ?: return@launch
                if (!childVm.isStreaming.value || job.state != com.openminis.app.agent.jobs.AgentJobState.RUNNING) return@launch
                val lastAssistant = childLiveMessages(childVm).lastOrNull { it.role == "assistant" }
                val tool = lastAssistant?.toolBlocks?.lastOrNull { it.kind == "tool_use" }
                val lastMessage = lastAssistant?.toolBlocks?.filter { it.kind == "text" }?.joinToString("\n") { it.content }
                    ?.ifBlank { null } ?: lastAssistant?.content.orEmpty()
                val turn = childVm.messages.value.count { it.role == "assistant" }
                val signature = "${tool?.toolName}|${tool?.toolTitle}|$turn|${lastMessage.take(200)}"
                if (onlyOnChange && signature == lastSignature) {
                    AppLogger.info(TAG, "[delegate_task] progress ${jobId.take(8)} unchanged — skipped")
                    continue
                }
                lastSignature = signature
                val elapsedMs = System.currentTimeMillis() - startedAt
                val facts = hr.childRunFacts(chatRepository.dao.loadMessages(childId))
                val summary = hr.runSummaryLine(facts.toolNames, facts.turns, facts.input, facts.output, facts.cacheRead)
                registry.setSummaryLine(jobId, summary)
                val text = com.openminis.app.agent.jobs.AgentCallback(
                    kind = com.openminis.app.agent.jobs.AgentCallback.Kind.PROGRESS,
                    jobId = jobId, childSessionId = childId, title = title, status = "running",
                    tier = job.tierUsed, elapsed = registry.elapsedClock(elapsedMs),
                    tool = tool?.toolName, activity = tool?.toolTitle?.take(80), turn = turn,
                    agentName = job.agentName,
                    summary = summary.removePrefix("Summary: "),
                    siblings = registry.siblingSummary(
                        parentSessionId = activeSessionId,
                        excludingJobId = jobId,
                        interruptedCount = countInterruptedChildren(activeSessionId),
                    ),
                    body = if (lastMessage.isBlank()) "(no message from the agent yet)" else lastMessage.take(hr.PROGRESS_LAST_MESSAGE_MAX_CHARS),
                ).xml
                withContext(Dispatchers.Main) {
                    pendingPromptId?.let { pid -> if (_promptQueue.value.any { it.id == pid }) removeQueuedPrompt(pid) }
                    pendingPromptId = null
                    val outcome = submitPrompt(text)
                    if (outcome is SubmitOutcome.Queued) pendingPromptId = _promptQueue.value.lastOrNull()?.id
                    AppLogger.info(TAG, "[delegate_task] progress ${jobId.take(8)} level=$level elapsed=${elapsedMs / 1000}s outcome=$outcome")
                }
            }
        }
    }

    private suspend fun persistFinalDelegateResult(toolId: String, content: String, success: Boolean) {
        val muted = com.openminis.app.agent.jobs.AgentJobRegistry.isDelegationMuted(activeSessionId)
        if (muted) AppLogger.info(TAG, "[delegate_task] final result ${toolId.take(12)} kept out of agentHistory — batch stopped")
        if (!muted) withContext(Dispatchers.Main) {
            for (i in agentHistory.indices) {
                val m = agentHistory[i]
                if (m.contentParts.none { it is AgentContentPart.ToolResult && it.id == toolId }) continue
                agentHistory[i] = m.copy(contentParts = m.contentParts.map { p ->
                    if (p is AgentContentPart.ToolResult && p.id == toolId) p.copy(content = content, isError = !success) else p
                })
            }
        }
        val sid = realSessionId.ifEmpty { sessionId }
        val rows = runCatching { chatRepository.dao.loadMessages(sid) }.getOrElse { return }
        for (row in rows) {
            if (row.role != "user" || !row.partsJson.contains(toolId)) continue
            val arr = runCatching { org.json.JSONArray(row.partsJson) }.getOrNull() ?: continue
            var hit = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("type") != "toolResult") continue
                val v = o.optJSONObject("value") ?: continue
                if (v.optString("toolUseId") != toolId) continue
                v.put("output", content); v.put("success", success)
                v.optJSONObject("snapshot")?.let { snap -> if (snap.optString("type") == "text") snap.put("text", content) }
                hit = true
            }
            if (hit) {
                chatRepository.updateMessageParts(row.id, arr.toString())
                AppLogger.info(TAG, "[delegate_task] persisted final result into message ${row.id.take(8)} tool=${toolId.take(12)}")
                return
            }
        }
        AppLogger.warning(TAG, "[delegate_task] final result NOT persisted — no stored tool_result for ${toolId.take(12)}")
    }

    private fun executeAgentStatus(argsJson: String): ToolExecutionResult {
        val registry = com.openminis.app.agent.jobs.AgentJobRegistry
        val o = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val action = o.optString("action", "status").lowercase()
        val jobIdArg = o.optString("job_id", "").trim()
        val toolTitle = o.optString("tool_title", "").ifEmpty { "agent_status" }
        val sid = activeSessionId
        var jobs = registry.list().filter { it.target.parentSessionIdOrNull == sid }
        if (jobIdArg.isNotEmpty()) {
            jobs = jobs.filter { it.id == jobIdArg || it.id.startsWith(jobIdArg) }
            if (jobs.isEmpty()) {
                return ToolExecutionResult(JSONObject().put("ok", false).put("error", "job_not_found").put("job_id", jobIdArg).toString(), false, toolTitle = toolTitle)
            }
        }
        if (action == "steer") {
            val message = o.optString("message", "").trim()
            if (jobIdArg.isEmpty()) {
                return ToolExecutionResult(
                    JSONObject().put("ok", false).put("error", "job_id_required_for_steer").toString(),
                    false, toolTitle = toolTitle,
                )
            }
            if (message.isEmpty()) {
                return ToolExecutionResult(
                    JSONObject().put("ok", false).put("error", "message_required_for_steer").toString(),
                    false, toolTitle = toolTitle,
                )
            }
            val target = jobs.firstOrNull()
                ?: return ToolExecutionResult(
                    JSONObject().put("ok", false).put("error", "job_not_found").put("job_id", jobIdArg).toString(),
                    false, toolTitle = toolTitle,
                )
            if (!target.isActive) {
                return ToolExecutionResult(
                    JSONObject().put("ok", false).put("status", "rejected")
                        .put("job_id", target.id)
                        .put("reason", "already_finished")
                        .put("state", target.state.wire)
                        .put("detail", "That sub agent has already finished — its result stands. Delegate a new task instead.")
                        .toString(),
                    false, toolTitle = toolTitle,
                )
            }
            val delivered = registry.steer(target.id, message)
            if (!delivered) {
                return ToolExecutionResult(
                    JSONObject().put("ok", false).put("status", "rejected")
                        .put("job_id", target.id)
                        .put("reason", "child_not_running")
                        .put("detail", "That sub agent has no live session to steer.")
                        .toString(),
                    false, toolTitle = toolTitle,
                )
            }
            return ToolExecutionResult(
                JSONObject().put("ok", true).put("status", "queued")
                    .put("job_id", target.id)
                    .also { j -> target.agentName?.let { j.put("agent", it) } }
                    .put(
                        "detail",
                        "Queued. The sub agent reads it at its next turn; a tool call already running " +
                            "is not interrupted. It may finish before consuming it.",
                    )
                    .toString(),
                true, toolTitle = toolTitle,
            )
        }
        if (action == "cancel") {
            if (jobIdArg.isEmpty()) {
                return ToolExecutionResult(JSONObject().put("ok", false).put("error", "job_id_required_for_cancel").toString(), false, toolTitle = toolTitle)
            }
            for (job in jobs) if (job.isActive) registry.cancel(job.id, "agent_status cancel by parent model")
        }
        val entries = org.json.JSONArray()
        for (job0 in jobs) {
            val job = registry.job(job0.id) ?: job0
            val missed = registry.missedSteersFor(job.id)
            val d = JSONObject()
                .put("job_id", job.id)
                .put("title", job.title)
                .also { j -> job.agentName?.let { j.put("agent", it) } }
                .also { j ->
                    if (missed.isNotEmpty()) {
                        j.put("missed_steer", org.json.JSONArray(missed))
                        j.put(
                            "missed_steer_note",
                            "The run ended before reading these — the result does not reflect them.",
                        )
                    }
                }
                .put("state", job.state.wire)
                .put("model_origin", job.modelOrigin ?: JSONObject.NULL)
                .put("tier_used", job.tierUsed ?: JSONObject.NULL)
                .put("elapsed_s", (job.elapsedMs ?: 0L) / 1000)
                .put("child_session_id", job.runSessionId ?: JSONObject.NULL)
                .put("delivery", if (job.then is com.openminis.app.agent.jobs.AgentJobThen.None) "tool_result" else "new_message_when_done")
            val cid = job.runSessionId
            if (cid != null && job.state == com.openminis.app.agent.jobs.AgentJobState.RUNNING) {
                val cvm = runCatching {
                    com.openminis.app.debug.HeadlessChatRunner.existingViewModel(cid)
                }.getOrNull()
                val msgs = cvm?.messages?.value.orEmpty()
                val tool = cvm?.let { childLiveToolBlock(it) }
                if (tool != null) { d.put("current_tool", tool.toolName); d.put("current_status", tool.toolTitle.take(120)) }
                d.put("loop_iteration", msgs.count { it.role == "assistant" })
            }
            if (!job.isActive) job.resultText?.let { d.put("result", it.take(2000)) }
            entries.put(d)
        }
        val queuedWaiting = registry.queuedCount(sid)
        val baseNote = "Running agents deliver their final result automatically as a new message in this conversation; you do not need to poll for it."
        val out = JSONObject()
            .put("ok", true)
            .put("action", action)
            .put("count", entries.length())
            .put("agents", entries)
            .put("queued", queuedWaiting)
            .put(
                "note",
                if (queuedWaiting > 0) {
                    "$baseNote $queuedWaiting more are queued and will start as slots free — do not re-delegate them."
                } else {
                    baseNote
                },
            )
        return ToolExecutionResult(out.toString(), true, toolTitle = toolTitle)
    }

    private fun visionPlaceholderFor(path: String?): String? {
        if (currentModelHasNativeVision) return null
        val configured = com.openminis.app.tools.VisionGroupResolver
            .isConfigured(providerRepository, context)
        return com.openminis.app.tools.VisionGroupResolver
            .noVisionImagePlaceholder(path, visionGroupConfigured = configured)
    }

    private suspend fun executeReadImageTool(argsJson: String): ToolExecutionResult {
        val base = ReadImageTool.execute(argsJson, fsSessionId, context)
        val customPrompt = try {
            JSONObject(argsJson).optString("prompt", "").trim().ifEmpty { null }
        } catch (_: Exception) { null }
        if (!base.success || base.imageData == null) return base
        if (currentModelHasNativeVision) {
            return if (customPrompt != null) {
                base.copy(output = base.output + "\n\n[Requested focus: " + customPrompt + "]")
            } else base
        }

        val bytes = base.imageData
        val mime = base.imageMimeType ?: "image/jpeg"
        val result = com.openminis.app.tools.VisionGroupResolver.describe(
            repo = providerRepository,
            context = context,
            imageData = bytes,
            mimeType = mime,
            seed = kotlin.math.abs(argsJson.hashCode()),
            customPrompt = customPrompt,
            sessionId = activeSessionId,
            onAttempt = { a ->
                android.util.Log.i(
                    "VisionGroup",
                    "[Vision] attempt ${a.index}/${a.total} via ${a.modelName}",
                )
            },
        )
        val framed = when (result) {
            is com.openminis.app.tools.VisionGroupResolver.VisionResult.Success ->
                com.openminis.app.tools.VisionGroupResolver.framedDescription(
                    result,
                    com.openminis.app.tools.VisionGroupResolver.groupName(providerRepository),
                    question = customPrompt,
                )
            is com.openminis.app.tools.VisionGroupResolver.VisionResult.Failure ->
                com.openminis.app.tools.VisionGroupResolver.failureText(result.reason)
        }
        return base.copy(
            output = base.output + "\n\n" + framed,
            imageData = null,
            imageMimeType = null,
        )
    }

    private fun maybeReloadSkillsForPath(argsJson: String) {
        runCatching {
            val path = JSONObject(argsJson).optString("path", "")
            if (path.contains("/skills/") && path.endsWith("SKILL.md")) {
                skillRepository?.requestReload("file_write", force = true)
            }
        }
    }

    private val BASH_MISSING_SENTINEL = 119

    private fun stoppedBeforeStart(
        toolBlocks: MutableList<AssistantBlock>,
        toolId: String,
        toolTitle: String,
        phase: String,
    ): ToolExecutionResult {
        val idx = toolBlocks.indexOfFirst { it.id == toolId }
        if (idx >= 0) toolBlocks[idx] = toolBlocks[idx].copy(content = "")
        AppLogger.info(TAG_STREAM, "[stop-tool] shell ${toolId.take(16)} stopped $phase - command not run")
        return ToolExecutionResult(
            ExecutionCoordinator.NOT_STARTED_OUTPUT + "\n\n" + ForegroundToolInterrupts.INTERRUPTED_RESULT,
            false,
            toolTitle = toolTitle,
        )
    }

    private fun wrapForBash(script: String): String {
        val normalized = if (script.endsWith("\n")) script else script + "\n"
        val b64 = android.util.Base64.encodeToString(
            normalized.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        return "( command -v bash >/dev/null 2>&1 || exit $BASH_MISSING_SENTINEL; " +
            "printf %s '$b64' | base64 -d > /tmp/.minis-exec-\$\$.sh && " +
            "bash /tmp/.minis-exec-\$\$.sh; rc=\$?; rm -f /tmp/.minis-exec-\$\$.sh; exit \$rc )"
    }

    private suspend fun askApprovalForShell(
        command: String,
        reasons: List<String>,
        toolId: String,
    ): String? {
        val submission = approvalCoordinator.submit(
            command = command,
            risk = com.openminis.app.agent.approval.CommandRisk.WARN,
            nowMs = System.currentTimeMillis(),
        )
        when (submission) {
            is com.openminis.app.agent.approval.Submission.Run -> return null
            is com.openminis.app.agent.approval.Submission.Refused -> return submission.reason
            is com.openminis.app.agent.approval.Submission.NeedsUser -> Unit
        }
        val pending = (submission as com.openminis.app.agent.approval.Submission.NeedsUser).pending
        val allowOnce = "允许，本次会话内记住"
        val allowAlways = "始终允许"
        val deny = "拒绝"
        val options = org.json.JSONArray()
            .put(JSONObject().put("label", allowOnce).put("description", "本会话内同一类命令不再询问"))
            .put(JSONObject().put("label", allowAlways).put("description", "永久允许这一类命令"))
            .put(JSONObject().put("label", deny).put("description", "不执行，并让模型换更安全的做法"))
        val question = JSONObject()
            .put("type", "options")
            .put("question", "需要你确认这条命令：\n`" + command.take(160) + "`")
            .put("options", options)
        if (reasons.isNotEmpty()) question.put("header", reasons.joinToString("; ").take(80))
        val argsJson = JSONObject().put("questions", org.json.JSONArray().put(question)).toString()
        val result = try {
            com.openminis.app.agent.userask.UserAskToolRunner.execute(
                argsJson = argsJson,
                toolCallId = toolId,
                sessionId = activeSessionId,
                sessionTitle = overlaySessionTitle(),
                timeoutMinutes = 5,
            )
        } catch (e: Exception) {
            AppLogger.warning(TAG_STREAM, "[shell-approval] prompt failed: ${e.javaClass.simpleName}")
            null
        }
        val answer = result?.let {
            com.openminis.app.agent.userask.UserAskResolver.parseResults(it.output).firstOrNull()?.answer.orEmpty()
        }.orEmpty()
        val mode = when {
            answer.any { it.contains("始终") } -> com.openminis.app.agent.approval.ApprovalMode.ALLOW_ALWAYS
            answer.any { it.contains("允许") } -> com.openminis.app.agent.approval.ApprovalMode.ALLOW_SESSION
            else -> com.openminis.app.agent.approval.ApprovalMode.DENY
        }
        val resolution = approvalCoordinator.resolve(pending, mode, System.currentTimeMillis())
        return when (resolution) {
            is com.openminis.app.agent.approval.Resolution.Approved -> null
            is com.openminis.app.agent.approval.Resolution.Denied ->
                approvalCoordinator.feedback(resolution, com.openminis.app.agent.approval.CommandRisk.WARN)
        }
    }

    private suspend fun executeShellCommand(
        argsJson: String,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>,
        assistantId: String,
        currentText: String,
    ): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            var command = args.optString("command", "")
            val timeoutSec = if (args.has("timeout")) {
                args.optInt("timeout", 900).coerceIn(1, SHELL_TIMEOUT_MAX_SEC)
            } else {
                (com.openminis.app.sandbox.ShellTimeoutPolicy.forCommand(command) / 1000L)
                    .toInt().coerceIn(1, SHELL_TIMEOUT_MAX_SEC)
            }
            val delaySec = args.optInt("delay", 0).coerceAtLeast(0)
            val toolTitle = args.optString("tool_title", "shell_execute")

            if (command.isBlank()) {
                return ToolExecutionResult("Error: 'command' is required", false, toolTitle = toolTitle)
            }

            val sandbox = com.openminis.app.agent.guard.SandboxGuard.assess(
                com.openminis.app.agent.guard.SandboxMode.get(context),
                command,
            )
            if (sandbox.blocked) {
                AppLogger.warning("Sandbox", "shell refused by mode: ${command.take(160)}")
                com.openminis.app.observability.TraceLog.record(
                    kind = "guard", name = "shell_execute", durationMs = 0, ok = false, summary = sandbox.reason,
                )
                return ToolExecutionResult(sandbox.reason, false, toolTitle = toolTitle)
            }

            val commandRisk = com.openminis.app.tools.CommandRiskPolicy.assess(command)
            if (commandRisk.blocked) {
                AppLogger.warning(
                    TAG_STREAM,
                    "[shell-risk] BLOCKED command (${commandRisk.reasons.joinToString()}) cmd=${command.take(160)}",
                )
                return ToolExecutionResult(
                    "Error: command blocked by the safety policy — " +
                        commandRisk.reasons.joinToString("; ") + ".\n" +
                        (commandRisk.guidance ?: "Rewrite the command to target a specific path.") +
                        "\nNothing was executed. Do NOT retry this command or a variant of it; " +
                        "choose the scoped alternative described above.",
                    false, toolTitle = toolTitle,
                )
            }
            if (commandRisk.level == com.openminis.app.tools.CommandRiskPolicy.Level.WARN) {
                val refusal = askApprovalForShell(command, commandRisk.reasons, toolId)
                if (refusal != null) {
                    AppLogger.info(TAG_STREAM, "[shell-approval] denied cmd=${command.take(120)}")
                    return ToolExecutionResult(refusal + "\nNothing was executed.", false, toolTitle = toolTitle)
                }
            }
            val commandRiskNotice = if (commandRisk.level == com.openminis.app.tools.CommandRiskPolicy.Level.WARN) {
                AppLogger.info(
                    TAG_STREAM,
                    "[shell-risk] warned (${commandRisk.reasons.joinToString()}) cmd=${command.take(160)}",
                )
                commandRisk.guidance
            } else null


            if (delaySec > 0) {
                for (remaining in delaySec downTo 1) {
                    if (foregroundTools.isInterrupted(toolId)) {
                        return stoppedBeforeStart(toolBlocks, toolId, toolTitle, "during its ${delaySec}s delay")
                    }
                    val idx = toolBlocks.indexOfFirst { it.id == toolId }
                    if (idx >= 0) {
                        val mm = remaining / 60
                        val ss = remaining % 60
                        val countdown = if (mm > 0) String.format("%d:%02d", mm, ss) else "${ss}s"
                        toolBlocks[idx] = toolBlocks[idx].copy(content = "⏳ Waiting $countdown before executing...")
                        withContext(Dispatchers.Main) {
                            updateAssistantMessage(assistantId, currentText, true, toolBlocks)
                        }
                    }
                    kotlinx.coroutines.delay(1000)
                }
                val idx = toolBlocks.indexOfFirst { it.id == toolId }
                if (idx >= 0) {
                    toolBlocks[idx] = toolBlocks[idx].copy(content = "")
                }
            }

            val dispatchSessionId = activeSessionId
            val stopEpoch = ExecutionCoordinator.stopEpoch(dispatchSessionId)
            if (foregroundTools.isInterrupted(toolId)) {
                return stoppedBeforeStart(toolBlocks, toolId, toolTitle, "before it started")
            }
            val turnGen = liveTurn.liveGeneration()
            android.util.Log.w("ShellExecDiag",
                "executeShell dispatch=$dispatchSessionId rawSessionId=$sessionId realSessionId=$realSessionId isDraft=$isDraft cmd=${command.take(120).replace('\n', ' ')}")

            BashismDetector.ensureLoaded(context)
            val bashism = BashismDetector.detect(command)
            var bashReminder: String? = null
            val originalCommand = command
            var bashScript: String? = null
            if (bashism.needsBash) {
                val executor = OnDemandBash.Executor { c, t ->
                    ExecutionCoordinator.execute(sessionId = dispatchSessionId, command = c, timeout = t, fsSessionId = fsSessionId, stopEpoch = stopEpoch).exitCode
                }
                when (val outcome = OnDemandBash.ensureBash(context, executor)) {
                    is OnDemandBash.Outcome.Available -> {
                        if (bashism.mustSwitchInterpreter) {
                            command = wrapForBash(command)
                            bashScript = originalCommand
                        }
                    }
                    is OnDemandBash.Outcome.Unavailable ->
                        bashReminder = BashismReminder.build(bashism.hits, outcome.reason)
                }
            }

            if (foregroundTools.isInterrupted(toolId) || ExecutionCoordinator.stoppedSince(dispatchSessionId, stopEpoch)) {
                return stoppedBeforeStart(toolBlocks, toolId, toolTitle, "while preparing bash")
            }
            var result = ExecutionCoordinator.execute(
                sessionId = dispatchSessionId,
                stopEpoch = stopEpoch,
                fsSessionId = fsSessionId,
                command = command,
                timeout = timeoutSec * 1000L,
                lineCallback = lc@{ rawLine ->
                    val (cleanedLine, capturedUrls) = MinisUrlMarker.extract(rawLine)
                    for (raw in capturedUrls) MinisOpenUrlBroker.offer(raw)
                    if (cleanedLine.isEmpty() && rawLine.isNotEmpty()) return@lc

                    if (turnGen != null && !liveTurn.isLive(turnGen)) {
                        noteStopLateLine(toolId, cleanedLine, "reader")
                        return@lc
                    }
                    val idx = toolBlocks.indexOfFirst { it.id == toolId }
                    if (idx >= 0) {
                        val current = toolBlocks[idx].content
                        val updated = if (current.isEmpty()) cleanedLine else "$current\n$cleanedLine"
                        val trimmed = updated.lines().takeLast(50).joinToString("\n")
                        toolBlocks[idx] = toolBlocks[idx].copy(content = trimmed)
                        viewModelScope.launch(Dispatchers.Main) {
                            if (turnGen != null && !liveTurn.isLive(turnGen)) {
                                noteStopLateLine(toolId, cleanedLine, "main")
                                return@launch
                            }
                            updateAssistantMessage(assistantId, currentText, true, toolBlocks)
                        }
                    }
                },
            )

            if ((result.exitCode == BASH_MISSING_SENTINEL ||
                    result.exitCode == (BASH_MISSING_SENTINEL shl 8)) && bashScript != null) {
                OnDemandBash.markDisappeared()
                val executor = OnDemandBash.Executor { c, t ->
                    ExecutionCoordinator.execute(sessionId = dispatchSessionId, command = c, timeout = t, fsSessionId = fsSessionId, stopEpoch = stopEpoch).exitCode
                }
                val healed = OnDemandBash.ensureBash(context, executor)
                command = if (healed is OnDemandBash.Outcome.Available) wrapForBash(bashScript!!) else bashScript!!
                result = ExecutionCoordinator.execute(
                    sessionId = dispatchSessionId, command = command, timeout = timeoutSec * 1000L, fsSessionId = fsSessionId,
                    stopEpoch = stopEpoch)
            }

            val (cleanedOutput, oneShotUrls) = MinisUrlMarker.extract(result.output)
            for (raw in oneShotUrls) MinisOpenUrlBroker.offer(raw)
            val output = if (cleanedOutput.isBlank()) "(no output)" else cleanedOutput
            val withExit = if (result.exitCode != 0) ShellExitCode.ensureSuffix(output, result.exitCode) else output
            val timedOut = result.exitCode == 124

            val finalOutput = withExit
            val (redactedOut, redactHits) = com.openminis.app.data.EnvVarRedactor.redactIfEnabled(finalOutput)
            if (redactHits > 0) {
                android.util.Log.i("EnvVarRedact", "shell_execute: masked $redactHits env-var value(s) in tool result")
            }

            if (result.exitCode == 127 && bashism.mustSwitchInterpreter) {
                OnDemandBash.markDisappeared()
            }
            val withBashReminder = bashReminder?.let { rem ->
                if (result.exitCode != 0 || bashism.hasSilent) "$redactedOut\n\n$rem" else redactedOut
            } ?: redactedOut
            val withReminder0 = result.queueNote?.let { "$withBashReminder\n\n$it" } ?: withBashReminder
            val withReminder = commandRiskNotice?.let { "$withReminder0\n\n⚠ Safety note: $it" } ?: withReminder0

            ToolExecutionResult(
                output = withReminder,
                success = result.exitCode == 0,
                toolTitle = toolTitle,
                timedOut = timedOut,
            )
        } catch (e: Exception) {
            ToolExecutionResult("Error: ${e.message}", false)
        }
    }

    private fun toolDisabledResult(name: String): ToolExecutionResult =
        ToolExecutionResult(
            "The $name tool is disabled in this app's settings and cannot be used. " +
                "Do not try it again in this conversation; continue without it, " +
                "or tell the user it is turned off if the task requires it.",
            false,
        )

    private suspend fun executeBrowserUseTool(argsJson: String): ToolExecutionResult {
        val input = BrowserActionInput.parse(argsJson)
            ?: return ToolExecutionResult("Error: Invalid browser_use input", false)

        return try {
            val result = browserTabPool.execute(input, owner = browserOwnerId)
            val toolTitle = try {
                JSONObject(argsJson).optString("tool_title", "browser_use")
            } catch (_: Exception) { "browser_use" }

            BrowserArtifacts.toolResult(
                result = result,
                toolTitle = toolTitle,
                decodeBase64 = { android.util.Base64.decode(it, android.util.Base64.DEFAULT) },
                clockMs = System::currentTimeMillis,
                persist = ::persistBrowserArtifact,
            )
        } catch (e: Exception) {
            ToolExecutionResult("Error: ${e.message}", false)
        }
    }

    private fun persistBrowserArtifact(filename: String, data: ByteArray): String? =
        BrowserArtifacts.persist(activeSessionId, { context.filesDir }, filename, data)

    private fun linuxPathToMinisURL(path: String): String? = BrowserArtifacts.linuxPathToMinisURL(path)

    private fun resizeJpegToMaxEdge(data: ByteArray, maxEdge: Int): ByteArray? =
        ImageBudget.resizeJpegToMaxEdge(data, maxEdge)

    private fun executeMemoryWriteTool(argsJson: String): ToolExecutionResult {
        val repo = memoryRepository ?: return ToolExecutionResult("Error: Memory not available", false)
        if (!_memoryEnabled.value) {
            val msg = "Memory writes are disabled for this session (user toggled /memory off). Reads remain available."
            return ToolExecutionResult(msg, false, toolTitle = "Memory (disabled)")
        }
        val result = MemoryTools.executeMemoryWrite(argsJson, repo)
        val content = try {
            JSONObject(argsJson).optString("content", "")
        } catch (_: Exception) { "" }
        _memoryToolRecords.value = _memoryToolRecords.value + MemoryToolRecord(
            title = result.toolTitle,
            isWrite = true,
            preview = content.lines().firstOrNull { it.isNotBlank() }?.take(100) ?: "",
            output = result.output,
            writtenContent = content,
        )
        return ToolExecutionResult(result.output, result.success, toolTitle = result.toolTitle)
    }

    private fun executeMemoryGetTool(argsJson: String): ToolExecutionResult {
        val repo = memoryRepository ?: return ToolExecutionResult("Error: Memory not available", false)
        val result = MemoryTools.executeMemoryGet(argsJson, repo)
        val keywords = try {
            JSONObject(argsJson).optString("keywords", "")
        } catch (_: Exception) { "" }
        _memoryToolRecords.value = _memoryToolRecords.value + MemoryToolRecord(
            title = result.toolTitle,
            isWrite = false,
            preview = if (keywords.isNotBlank()) "Search: $keywords" else result.output.take(100),
            output = result.output,
            keywords = keywords,
        )
        return ToolExecutionResult(result.output, result.success, toolTitle = result.toolTitle)
    }

    private fun executeMemoryFactTool(argsJson: String): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (_: Exception) {
            return ToolExecutionResult("Error: malformed arguments", false)
        }
        val action = args.optString("action", "").trim().lowercase()
        val title = args.optString("tool_title", "").ifBlank { "Remember fact" }
        if (!_memoryEnabled.value) {
            return ToolExecutionResult(
                "Memory facts are disabled for this session (/memory off).", false, toolTitle = title,
            )
        }
        return when (action) {
            "remember" -> {
                if (isHelper) {
                    ToolExecutionResult("Helpers cannot write long-term facts.", false, toolTitle = title)
                } else {
                    val subject = args.optString("subject", "")
                    val predicate = args.optString("predicate", "")
                    val value = args.optString("value", "")
                    if (subject.isBlank() || predicate.isBlank() || value.isBlank()) {
                        ToolExecutionResult(
                            "Error: 'subject', 'predicate' and 'value' are required for remember.",
                            false, toolTitle = title,
                        )
                    } else {
                        val out = memoryFactRepository.upsert(subject, predicate, value, sessionId)
                        ToolExecutionResult(out, true, toolTitle = title)
                    }
                }
            }
            "search" -> {
                val keywords = args.optString("keywords", "")
                val out = memoryFactRepository.search(keywords)
                ToolExecutionResult(out, true, toolTitle = title)
            }
            else -> ToolExecutionResult(
                "Error: 'action' must be 'remember' or 'search'.", false, toolTitle = title,
            )
        }
    }


    private fun mergeDelegateOverrides(blocks: List<AssistantBlock>): List<AssistantBlock> {
        if (delegateBlockOverrides.isEmpty()) return blocks
        return blocks.map { b ->
            delegateBlockOverrides[b.id]?.let { o ->
                val status = if (OrphanedRunningToolGate.overrideMayApply(
                        o.toolStatus, isLiveRun = _isStreaming.value, jobIsAlive = delegateJobIsAlive(o.content),
                    )
                ) {
                    o.toolStatus ?: b.toolStatus
                } else {
                    b.toolStatus
                }
                b.copy(content = o.content, toolStatus = status)
            } ?: b
        }
    }

    private fun delegateJobIsAlive(content: String): Boolean {
        val jobId = runCatching { org.json.JSONObject(content).optString("job_id", "") }.getOrNull()
        if (jobId.isNullOrEmpty()) return false
        return com.openminis.app.agent.jobs.AgentJobRegistry.job(jobId)?.isActive == true
    }

    internal fun writeDelegateBlock(toolId: String, content: String, status: ToolBlockStatus?) {
        val prev = delegateBlockOverrides[toolId]
        delegateBlockOverrides[toolId] = AssistantBlock(
            id = toolId, kind = "tool_use", content = content,
            toolStatus = status ?: prev?.toolStatus, toolName = com.openminis.app.agent.jobs.HelperRunner.TOOL_NAME,
        )
        fun patch(blocks: List<AssistantBlock>): List<AssistantBlock>? {
            if (blocks.none { it.id == toolId }) return null
            return blocks.map { if (it.id == toolId) it.copy(content = content, toolStatus = status ?: it.toolStatus) else it }
        }
        _messages.value = _messages.value.map { m ->
            if (m.role != "assistant") m else patch(m.toolBlocks)?.let { m.copy(toolBlocks = it) } ?: m
        }
        val overlay = _streamingById.value
        if (overlay.isNotEmpty()) {
            var changed = false
            val next = overlay.mapValues { (_, d) -> patch(d.toolBlocks)?.let { changed = true; d.copy(toolBlocks = it) } ?: d }
            if (changed) _streamingById.value = next
        }
    }

    internal fun clearDelegateOverride(toolId: String) { delegateBlockOverrides.remove(toolId) }

    private fun updateAssistantMessage(
        id: String,
        content: String,
        isStreaming: Boolean,
        toolBlocksIn: List<AssistantBlock>,
        isAwaitingModelResponse: Boolean = false,
    ) {
        val toolBlocks = mergeDelegateOverrides(toolBlocksIn)
        if (isStreaming) {
            if (!liveTurn.acceptsStreamingWrite(id)) {
                noteStopLateOverlay(id, toolBlocks)
                return
            }
            com.openminis.app.diagnostics.JankDiag.onStreamChunk(content.length)
            val toolBlocksImmutable = toolBlocks.toList()

            val st = streamFlushStates.getOrPut(id) {
                StreamFlushState().also { it.lastFlushedLen = 0 }
            }
            val prev = _streamingById.value[id]
            val toolStatusChanged = prev != null &&
                prev.toolBlocks.size == toolBlocksImmutable.size &&
                toolBlocksImmutable.indices.any { i ->
                    prev.toolBlocks[i].toolStatus != toolBlocksImmutable[i].toolStatus
                }
            val structuralChange = prev == null ||
                prev.toolBlocks.size != toolBlocksImmutable.size ||
                prev.isAwaitingModelResponse != isAwaitingModelResponse ||
                toolStatusChanged
            val now = System.currentTimeMillis()
            val elapsed = now - st.lastFlushMs
            val throttle = streamFlushThrottleMs(content.length)
            val newChunk = if (content.length > st.lastFlushedLen) {
                content.substring(st.lastFlushedLen.coerceAtMost(content.length))
            } else ""
            val unflushed = content.length - st.lastFlushedLen
            val newlineFlush = content.length < NEWLINE_FLUSH_MAX_LEN &&
                newChunk.contains('\n') &&
                unflushed >= NEWLINE_FLUSH_MIN_CHARS

            fun publish(text: String, blocks: List<AssistantBlock>, awaiting: Boolean) {
                if (!liveTurn.acceptsStreamingWrite(id)) return
                com.openminis.app.diagnostics.JankDiag.onStreamPublish()
                _streamingById.value = _streamingById.value + (
                    id to StreamingDelta(
                        content = text,
                        toolBlocks = blocks,
                        isAwaitingModelResponse = awaiting,
                    )
                )
                st.lastFlushMs = System.currentTimeMillis()
                st.lastFlushedLen = text.length
            }

            if (structuralChange || elapsed >= throttle || newlineFlush) {
                st.trailingJob?.cancel()
                st.trailingJob = null
                st.pendingContent = null
                publish(content, toolBlocksImmutable, isAwaitingModelResponse)
            } else {
                st.pendingContent = content
                st.pendingBlocks = toolBlocksImmutable
                st.pendingAwaiting = isAwaitingModelResponse
                if (st.trailingJob == null) {
                    val wait = (throttle - elapsed).coerceAtLeast(16L)
                    st.trailingJob = viewModelScope.launch {
                        kotlinx.coroutines.delay(wait)
                        val pc = st.pendingContent
                        if (pc != null) {
                            publish(pc, st.pendingBlocks, st.pendingAwaiting)
                            st.pendingContent = null
                        }
                        st.trailingJob = null
                    }
                }
            }
            val canonical = _messages.value
            val canonicalIdx = canonical.indexOfLast { it.id == id }
            if (canonicalIdx >= 0 && canonical[canonicalIdx].error != null) {
                val updated = canonical.toMutableList()
                updated[canonicalIdx] = canonical[canonicalIdx].copy(error = null)
                _messages.value = updated
            }
            return
        }
        clearStreamFlushState(id)
        val current = _messages.value
        val idx = current.indexOfLast { it.id == id }
        if (idx < 0) {
            if (_streamingById.value.containsKey(id)) {
                _streamingById.value = _streamingById.value - id
            }
            return
        }
        val updated = current.toMutableList()
        updated[idx] = current[idx].copy(
            content = content,
            isStreaming = false,
            toolBlocks = toolBlocks.toList(),
            isAwaitingModelResponse = isAwaitingModelResponse,
        )
        _messages.value = updated
        if (_streamingById.value.containsKey(id)) {
            _streamingById.value = _streamingById.value - id
        }
    }

    internal fun effectiveContent(id: String): String? {
        val delta = _streamingById.value[id]
        if (delta != null) return delta.content
        return _messages.value.firstOrNull { it.id == id }?.content
    }

    private fun flushStreamingDelta(id: String) {
        val delta = _streamingById.value[id] ?: return
        val current = _messages.value
        val idx = current.indexOfLast { it.id == id }
        if (idx >= 0) {
            val updated = current.toMutableList()
            updated[idx] = current[idx].copy(
                content = delta.content,
                isStreaming = false,
                toolBlocks = InFlightToolHeal.mergeOverlayBlocks(current[idx].toolBlocks, delta.toolBlocks),
                isAwaitingModelResponse = delta.isAwaitingModelResponse,
            )
            _messages.value = updated
        }
        clearStreamFlushState(id)
        _streamingById.value = _streamingById.value - id
    }

    private fun flushAllStreamingDeltas() {
        clearAllStreamFlushStates()
        val pending = _streamingById.value
        if (pending.isEmpty()) return
        val current = _messages.value.toMutableList()
        var changed = false
        for ((id, delta) in pending) {
            val idx = current.indexOfLast { it.id == id }
            if (idx < 0) continue
            current[idx] = current[idx].copy(
                content = delta.content,
                isStreaming = false,
                toolBlocks = InFlightToolHeal.mergeOverlayBlocks(current[idx].toolBlocks, delta.toolBlocks),
                isAwaitingModelResponse = delta.isAwaitingModelResponse,
            )
            changed = true
        }
        if (changed) _messages.value = current
        _streamingById.value = emptyMap()
    }

    private fun buildTurnParts(
        allToolBlocks: List<AssistantBlock>,
        turnStartBlockIndex: Int,
        toolCallInputs: Map<String, String>,
    ): List<AgentContentPart> {
        if (turnStartBlockIndex >= allToolBlocks.size) return emptyList()
        val out = mutableListOf<AgentContentPart>()
        for (i in turnStartBlockIndex until allToolBlocks.size) {
            val block = allToolBlocks[i]
            when (block.kind) {
                "text" -> if (block.content.isNotEmpty()) {
                    out.add(AgentContentPart.Text(block.content))
                }
                "tool_use" -> {
                    val name = block.toolName
                    if (name.isBlank()) continue
                    val inputStr = toolCallInputs[block.id] ?: "{}"
                    val inputJson = try { JSONObject(inputStr) } catch (_: Exception) { JSONObject() }
                    out.add(AgentContentPart.ToolUse(block.id, name, inputJson, thoughtSignature = block.thoughtSignature))
                }
                else -> {  }
            }
        }
        return out
    }

    private fun buildAssistantPartsJson(
        parts: List<AgentContentPart>,
        toolBlockMeta: Map<String, AssistantBlock>,
    ): String = buildString {
        append("[")
        parts.forEachIndexed { index, part ->
            if (index > 0) append(",")
            when (part) {
                is AgentContentPart.Text -> {
                    append("""{"type":"text","value":${escapeJson(part.text)}}""")
                }
                is AgentContentPart.ToolUse -> {
                    val name = part.name
                    if (name.isBlank()) return@forEachIndexed
                    val inputStr = part.input.toString()
                    val meta = toolBlockMeta[part.id]
                    val desc = meta?.toolTitle ?: ""
                    val pageURL = meta?.browserURL ?: ""
                    val imgPath = meta?.imageFilePath ?: ""
                    val sigJson = part.thoughtSignature?.let { escapeJson(it) } ?: "null"
                    append("""{"type":"toolUse","value":{"toolUseId":${escapeJson(part.id)},"name":${escapeJson(name)},"input":${escapeJson(inputStr)},"description":${escapeJson(desc)},"pageURL":${escapeJson(pageURL)},"imageFilePath":${escapeJson(imgPath)},"thoughtSignature":$sigJson}}""")
                }
                else -> {  }
            }
        }
        append("]")
    }

    private suspend fun persistAssistantTurn(
        parts: List<AgentContentPart>,
        usage: LLMUsage?,
        reasoningContent: String? = null,
        toolBlockMeta: Map<String, AssistantBlock> = emptyMap(),
        uiAssistantId: String? = null,
        streamMs: Long = 0L,
    ): String? {
        if (parts.isEmpty()) return null
        val partsJson = buildAssistantPartsJson(parts, toolBlockMeta)
        val tokenJson = usage?.let {
            """{"inputTokens":${it.inputTokens},"outputTokens":${it.outputTokens},"cacheCreationTokens":${it.cacheCreationInputTokens ?: 0},"cacheReadTokens":${it.cacheReadInputTokens ?: 0},"latestContextTokens":${it.latestContextTokens},"streamMs":$streamMs${com.openminis.app.usage.StoredUsageJson.extraFields(it)}${calibrationJsonFields()}}"""
        }
        val entity = chatRepository.appendMessage(
            realSessionId.ifEmpty { sessionId }, "assistant", partsJson, tokenJson,
            reasoningContent = reasoningContent,
            modelSnapshot = currentModelSnapshot(),
        )

        if (uiAssistantId != null) {
            val stampedUsage = ChatTokenUsage.parse(entity.tokenUsage)
            withContext(Dispatchers.Main) {
                var hit = false
                _messages.value = _messages.value.map { m ->
                    if (m.id == uiAssistantId) { hit = true; m.copy(tokenUsage = stampedUsage ?: m.tokenUsage, completedAt = entity.createdAt) } else m
                }
                AppLogger.info(
                    TAG_STREAM,
                    "[UsageCapsule] live stamp ui=${uiAssistantId.take(20)} hit=$hit " +
                        "ctx=${stampedUsage?.latestContextTokens ?: -1} db=${entity.id.take(8)}",
                )
            }
        }
        return entity.id
    }

    @Deprecated("Use persistAssistantTurn(parts, ...) for per-turn delta persistence")
    private suspend fun persistAssistantMessage(
        text: String,
        usage: LLMUsage?,
        toolBlocks: List<AssistantBlock>? = null,
        toolCallInputs: Map<String, String> = emptyMap()
        ,
        reasoningContent: String? = null,
    ) {
        if (text.isEmpty() && (toolBlocks == null || toolBlocks.isEmpty())) return

        val partsJson = buildString {
            append("[")
            var first = true
            if (text.isNotEmpty()) {
                append("""{"type":"text","value":${escapeJson(text)}}""")
                first = false
            }
            toolBlocks?.forEach { block ->
                if (block.kind != "tool_use") return@forEach
                if (block.toolName.isBlank()) return@forEach
                if (!first) append(",")
                first = false
                val inputJson = toolCallInputs[block.id]?.let { escapeJson(it) } ?: "\"\""
                val pUrl = block.browserURL ?: ""
                val iPath = block.imageFilePath ?: ""
                append("""{"type":"toolUse","value":{"toolUseId":${escapeJson(block.id)},"name":${escapeJson(block.toolName)},"input":$inputJson,"description":${escapeJson(block.toolTitle)},"pageURL":${escapeJson(pUrl)},"imageFilePath":${escapeJson(iPath)},"thoughtSignature":null}}""")
            }
            append("]")
        }
        val tokenJson = usage?.let {
            """{"inputTokens":${it.inputTokens},"outputTokens":${it.outputTokens},"cacheCreationTokens":${it.cacheCreationInputTokens ?: 0},"cacheReadTokens":${it.cacheReadInputTokens ?: 0},"latestContextTokens":${it.latestContextTokens}${com.openminis.app.usage.StoredUsageJson.extraFields(it)}${calibrationJsonFields()}}"""
        }
        chatRepository.appendMessage(
            realSessionId.ifEmpty { sessionId }, "assistant", partsJson, tokenJson,
            reasoningContent = reasoningContent,
            modelSnapshot = currentModelSnapshot(),
        )
    }

    private suspend fun persistToolResultMessage(parts: List<AgentContentPart>, durationsMs: Map<String, Long> = emptyMap()): String? =
        ToolResultWrap.persist(parts, durationsMs, { realSessionId.ifEmpty { sessionId } }) { sid, role, partsJson ->
            chatRepository.appendMessage(sid, role, partsJson).id
        }

    private fun buildSystemPrompt(query: String = ""): String? {
        val today = java.time.LocalDate.now()
        val dateStr = today.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val tzId = java.util.TimeZone.getDefault().id
        val lang = context.resources.configuration.locales[0].toLanguageTag()

        val modelUseCount = try { providerRepository.resolvedAgentLoopEntries().size } catch (_: Exception) { 0 }

        val browserToolEnabled = com.openminis.app.tools.AgentToolSwitch.BROWSER.isEnabled(context)
        val identitySection = helperConfig?.let {
            com.openminis.app.agent.jobs.HelperRunner.identitySection(it, browserEnabled = browserToolEnabled)
        }
            ?: com.openminis.app.agent.SystemPromptBuilder.identitySection(context)
        val memoryOn = _memoryEnabled.value
        val toolListMemoryBullets = if (memoryOn) {
            """
${com.openminis.app.agent.jobs.HelperRunner.systemPromptBullet(!isHelper && com.openminis.app.agent.jobs.AgentSettings.isEnabled(context))}${subAgentRosterPromptSection()}- memory_write: Save a memory entry to today's daily log (YYYY-MM-DD.md). Use proactively to note user preferences, project patterns, and important context.
- memory_get: Recall memories with keyword search. Check memory at the start of new topics to leverage past knowledge."""
        } else {
            ""
        }
        val memorySystemSection = if (memoryOn) {
            """

Memory system (currently ENABLED):
- memory_write writes to today's daily log (YYYY-MM-DD.md) — use it for session notes, key facts, project context, things learned, and action items.
- GLOBAL.md (/var/minis/memory/GLOBAL.md) stores persistent preferences, settings, and general-purpose conventions. To read it, use file_read (NOT memory_get). To update it, use file_read first then file_edit. If GLOBAL.md does not exist yet, use file_write to create it directly.
- IMPORTANT: Only write to GLOBAL.md when the user explicitly asks (e.g. 'remember this globally', 'save to global memory'). Before editing, deduplicate and clean up — avoid ambiguity, repetition, or daily-log-style entries. GLOBAL.md should contain only concise, reusable knowledge (preferences, settings, conventions), NOT session logs or transient context.
- Use memory_get to recall past knowledge before starting tasks — check if there are relevant memories that can help.
- Proactively save memories (via memory_write to daily log) when you discover user preferences or important patterns — don't wait to be asked.
- When the user says 'remember this' or similar, use memory_write to persist to the daily log. Only write to GLOBAL.md if the user specifically asks for global/persistent storage.
- What NOT to remember: passwords, API keys, tokens, secrets, or any sensitive credentials. Warn the user about the risk first; only proceed if they explicitly confirm.
- Keep memories concise, factual, and general-purpose — avoid noise that won't be useful later."""
        } else {
            """

Memory system (currently DISABLED):
- The user has turned OFF memory injection and memory tools for this session. GLOBAL.md and recent daily logs are NOT included in this prompt, and the memory_write / memory_get tools are NOT available — do not attempt to call them.
- If the user asks why earlier memories aren't visible, or asks you to save something, tell them memory is currently disabled and point them at the /memory slash command or [Settings → Memory](minis://settings/memory) to re-enable it.
- SOUL.md (personality / identity) is unaffected by this toggle; the persona section above still applies."""
        }
        val delegationOffered = !isHelper &&
            com.openminis.app.agent.jobs.AgentSettings.isEnabled(context)
        val scheduledDelegateNote = if (delegationOffered) {
            " (Do NOT run minis-scheduled to delegate work you could hand to subagent_task.)"
        } else ""
        val modelUseDelegateNote = if (delegationOffered) {
            " For delegating a whole task that needs tools and multiple rounds, use the subagent_task tool instead."
        } else ""
        val browserPromptBullet = if (browserToolEnabled) """- browser_use: Web browsing (navigate, screenshot, click, type, get_text, scroll, scroll_and_collect, get_readable, get_backbone, fetch, etc.). Starts with a desktop Chrome user agent. PREFER STRUCTURED TEXT over screenshots: use `get_text` / `get_readable` to read page content and `get_backbone` (DOM structure) + `find` to locate elements, then click/type by selector — these are fast and cheap. Only take a `screenshot` when you genuinely need visual layout, images, or canvas content that text/DOM cannot express; screenshots cost far more tokens and a vision round-trip.
  当 browser_use 触达 Google 登录 / OAuth 页（accounts.google.com、signin.google.com、myaccount.google.com、oauth2.googleapis.com 等）或网页返回 "disallowed_useragent" / 403 包含 "browser is not secure" 字样时，**不要重试或尝试登录** — Google 永久禁止 in-app WebView 完成登录，重试只会浪费 turn。改为告诉用户："此页面需要在系统 Chrome 完成登录" 并给出可点击的 Markdown link [在 Chrome 中打开](https://accounts.google.com/...)。点该 link 时 app 会跳出 Custom Tab；用户在 Chrome 完成操作后，请他**把所需结果（邮件正文 / 文档摘要 / 表格数据）粘贴回 chat**，你再继续帮他处理。这是 Android 平台限制，不是 bug。${toolListMemoryBullets}""" else ""
        val userAskPromptLine = when {
            !userAskExposedThisRun -> ""
            browserPromptBullet.isEmpty() -> com.openminis.app.agent.userask.UserAskPromptText.TOOL_LIST_LINE
            else -> "\n" + com.openminis.app.agent.userask.UserAskPromptText.TOOL_LIST_LINE
        }
        val minisUrlNote = if (browserToolEnabled) """IMPORTANT: minis:// URLs are app-internal — they are NOT web URLs. Do NOT pass minis:// action URLs (open_terminal, views, settings) to browser_use — those are app deep links, use Markdown links in chat instead. However, minis:// resource URLs CAN be opened in browser_use with navigate. All directories under /var/minis/ are accessible: workspace, attachments, offloads, shared, etc. The built-in browser fully supports minis:// — HTML pages and all sub-resources (JS, CSS, images, fonts, etc.) referenced via minis:// absolute URLs or relative paths resolve correctly within the current session. When building multi-file web projects, use file_write to create files in the same directory (e.g. /var/minis/workspace/myapp/), then reference sub-resources with relative paths in HTML (e.g. <link href="style.css">, <script src="app.js">, <img src="logo.png">). The browser resolves relative paths against the minis:// base URL automatically. Cross-directory references also work with absolute minis:// URLs (e.g. <img src="minis://attachments/photo.png"> from a workspace HTML page). Navigate to the entry HTML to preview, e.g. minis://workspace/myapp/index.html.""" else """IMPORTANT: minis:// URLs are app-internal — they are NOT web URLs. Do NOT treat minis:// action URLs (open_terminal, views, settings) as web links — those are app deep links, use Markdown links in chat instead."""
        val base = identitySection + """You should proactively use shell commands to accomplish the user's tasks — installing packages (apt-get install), writing and running scripts, managing files, networking, and any other operations a Linux terminal can perform.

Verification before finishing (self-check): Before you declare a task complete, verify the outcome rather than assuming it — re-read a file you edited, re-run the command whose result matters, or check the generated artifact exists and is correct. For multi-step work, confirm each critical step actually succeeded before moving on; a tool returning success is not the same as the goal being achieved. If a verification fails, fix it and re-verify instead of reporting success. Do not loop forever on verification — a reasonable check for each critical result is enough.

Long-running tasks: keep making forward progress and avoid aimless exploration. For big jobs, work in stages, update your task plan as you go, and produce a concrete deliverable for each stage so partial work is never lost. If you find yourself repeating the same kind of action without getting closer to the goal, change approach or report the blocker instead of grinding on. Periodically re-read your task plan to remind yourself what is done and what remains — the plan persists across context compaction and restarts, so it is your reliable progress record.

Available tools:
- shell_execute: Run any shell command. Each invocation is an isolated process with stdout/stderr captured. Prefer this for most tasks — it is a real Linux environment with persistent filesystem. Common tools (python3, pip, curl, wget, git, ssh, etc.) can be installed via apt-get install; Python packages via pip install. Use `which <cmd>` to check if a tool is already installed before running apt-get install — many packages persist across sessions. When you need to wait before checking results (e.g. polling, waiting for a process), use the `delay` parameter instead of `sleep` in the command — delay blocks the agent flow without occupying the shell, so other concurrent tasks can use it during the wait. This avoids resource contention. Execution discipline for long-running or dispatched work: make tool calls immediately instead of describing intentions, and keep working until the task is complete. Within a turn, `delay` is your wait mechanism — to follow up on something still running, chain delay-then-check calls at a task-appropriate interval until you have the result or hit a sensible retry cap. For a check that should happen AFTER this turn ends, register it by running `minis-scheduled create …` via shell_execute (see the CLI list) — it fires a new turn at the scheduled time through a system alarm. NEVER end a turn with a promise of future action: 'I'll keep monitoring', 'will sync the result later', and ending right after a single still-running status check with 'let's keep waiting' are all the same violation — once your turn ends, NOTHING runs until the user's next message. If polling to completion is genuinely not worth blocking the turn, close honestly instead: either register a `minis-scheduled` follow-up (say so, with its task id) or state that you will only learn the outcome when the user next messages; see 'Scheduled tasks' later in this prompt.
- file_read: Read file contents (faster than cat).
- file_write: Create new files or overwrite existing files (faster than echo/tee).
- file_edit: Edit existing files with exact string replacement (old_string → new_string). Preferred over file_write for modifications — always file_read first.
- apply_patch: Apply ONE atomic multi-file change set (`*** Begin Patch` … `*** End Patch`). Use it instead of several file_edit calls whenever a change spans more than one hunk or file: every hunk is validated before anything is written, and a failure rolls the whole set back. Supports Update / Add / Delete / Move. Read the files first; context lines must match current content.
- repo_map: Cheap outline of a whole tree (paths ranked by importance + class/function declarations with line numbers, no file bodies). Use it FIRST on an unfamiliar or large project instead of blind `find`/`ls` + full-file reads, then file_read only what the map points at.
- todo_write: Track a plan for complex multi-step tasks (3+ steps). Call it up front with all steps, keep exactly one as in_progress while you work, and update it as you go. The current plan is shown in Runtime context. Skip it for trivial requests.
- skill_load: When a task matches one of the available skills listed below, call skill_load with its name to load the full instructions before proceeding — do not guess the SKILL.md path.
- skill_save: After you solve a non-trivial, repeatable task, save the proven procedure as a reusable skill with skill_save(name, description, body) so future sessions can reuse it in one cheap step instead of rediscovering it — this is how you get better over time. Use it ONLY for generalizable multi-step methods (a reliable CLI recipe, a workflow you worked out), never for one-off facts, summaries, or anything an existing skill already covers; keep the body short and concrete with the exact commands/parameters that worked.
- memory_fact: Store durable facts with action 'remember' as subject · predicate = value (e.g. user · prefers editor = vim; project Alpha · database = PostgreSQL). Repeating a fact STRENGTHENS it, so record preferences/conventions as soon as you learn them — the strongest facts are injected into every future session automatically. Use action 'search' to recall. Never store secrets. The currently remembered facts are listed under 'Remembered facts' below.
${browserPromptBullet}${userAskPromptLine}

Shared directory /var/minis/ (bidirectional read/write between shell and app):
  /var/minis/attachments/ — Media files (images, audio, video). Display inline with ![desc](minis://attachments/filename).
  /var/minis/workspace/   — Working files (scripts, data, configs). Link with [name](minis://workspace/filename).
  /var/minis/offloads/    — Auto-saved large outputs. Read with file_read.
  /var/minis/browser/     — Browser screenshots and extracts.
  /var/minis/shared/      — Cross-session shared storage for artifacts and documents. Organize by project or topic (e.g. shared/myproject/, shared/datasets/). Do NOT store temporary files here.
  /var/minis/memory/GLOBAL.md    — Persistent global memory (writable: the user edits it in Settings, and you may update it when the user asks).
  /var/minis/memory/YYYY-MM-DD.md — Daily memory log.
  /var/minis/mounts/<name>/      — User-mounted external folders from Settings → Mount External Folders. Presence and names vary per user; check this directory first when the task references external/user files. Some mounts may be read-only — file_write / file_edit will reject writes with a clear error message.

The minis:// URL scheme:
  minis://attachments/file.png  →  /var/minis/attachments/file.png
  minis://workspace/data.csv    →  /var/minis/workspace/data.csv
  minis://shared/project/f.txt  →  /var/minis/shared/project/f.txt

${minisUrlNote}
To display a minis:// URL in chat, write it as a Markdown link or image (e.g. [name](minis://...)) — the app handles it when the user taps it.
IMPORTANT: minis:// URLs MUST be percent-encoded. Non-ASCII characters (Chinese, emoji, spaces, etc.) in filenames will break Markdown rendering if not encoded. Use the minis_url from tool results directly — it is already encoded. If you construct a minis:// URL manually, percent-encode the filename (e.g. %E4%B8%AD%E6%96%87 for non-ASCII characters).
When you write files to /var/minis/, the tool result includes a minis_url you can embed directly in Markdown.
Inline media — use the ![desc](minis://...) image syntax for ALL of images, audio, AND video. The same ![]() syntax renders an inline audio player or video player, not just images:
  - Images: ![chart](minis://attachments/chart.png)   → inline image (.png/.jpg/.gif/.webp)
  - Audio:  ![song](minis://attachments/song.mp3)     → inline audio player (.mp3/.m4a/.wav)
  - Video:  ![clip](minis://attachments/clip.mp4)     → inline video player (.mp4/.mov/.m4v)
Do NOT use the [text](url) link form for audio/video when you want them to play inline — that only produces a tappable link. Use ![]() to embed an actual player.
For non-media files, use Markdown links: [filename](minis://workspace/filename).
Tappable link previews: text/code (.py/.json/.md/etc), images, audio, video, HTML, and PDF files open native previews when the user taps a [name](minis://...) link.
Use Markdown links for all non-media minis:// files — the user can tap to preview them directly in chat.

File creation guidelines:
- Use file_write to CREATE new files. Use file_edit to MODIFY existing files. The shell is Ubuntu 24.04 /bin/sh (dash): heredoc syntax (cat << EOF, python3 << 'EOF') may mis-parse braces, quotes, or special characters and execute abnormally — avoid it whenever possible, and prefer file_write over echo/printf for writing file contents. When you hit escaping or parsing errors with long inline content, write the content to a file first (file_write), then pass or execute the file (e.g. `python3 /tmp/script.py`).
- file_write and file_edit are atomic, preserve formatting, and make it easy to fix errors or update content later.
- shell_execute is for RUNNING commands, not for writing files.
- shell_execute supports multi-line commands directly — quoting and special characters are handled automatically. However, commands MUST NOT exceed 1000 characters. If longer, write a script file with file_write first, then run it.
- ICMP is blocked by the PRoot sandbox — `ping` will hang indefinitely. Use `curl` or `wget` to test network connectivity instead.
- Also (/bin/sh is dash, NOT bash): brace expansion ({a,b,c}) and bash arrays (arr=(...), ${'$'}{arr[@]}) are unsupported — use space-separated strings with a for loop or multiple arguments instead.
- Python packages: Ubuntu 24.04 aarch64 has manylinux wheels for common packages (numpy, pandas, scipy, pillow, etc.). Install them with `apt-get install -y python3-numpy python3-pandas python3-matplotlib python3-pil python3-scipy python3-requests` or with `pip install` (add `--break-system-packages` if apt-managed Python refuses). For matplotlib, always set `matplotlib.use('Agg')` before importing pyplot — there is no display server in the sandbox.
- Java: install a JDK with `apt-get install -y openjdk-17-jdk` (openjdk-21-jdk / openjdk-11-jdk for other projects). javac needs the `-d` output directory to exist.
- Background services: each shell_execute runs in an isolated process. When starting a background server (e.g. `python3 -m http.server &`), you MUST redirect stdout/stderr to avoid SIGPIPE when the shell exits: `python3 -m http.server 8765 > /dev/null 2>&1 &`. Without redirection the server dies silently after the command finishes.
- File search & text scanning: use ripgrep (`rg`) for EVERY scan of files — finding names, keyword/regex search, field extraction, log analysis — on one file or a whole tree. Never use grep/awk/sed/find here, rg is far faster. Contents: `rg -n 'pattern' <dir>` (`-i` ignore case, `-w` whole word, `-F` literal, `-l` file names only, `-t py` or `-g '*.md'` to filter files). File names: `rg --files <dir> -g '*.pdf'`, or `rg --files <dir> | rg 'name'`. rg skips hidden, .gitignored and binary files by default; add `-uuu` to search everything. If `which rg` finds nothing, install it once with `apt-get install -y ripgrep`. `**` recursive glob (globstar) is not supported by the shell — use rg instead. When looking for user files, do NOT scan the whole filesystem: search /var/minis/ first (workspace/attachments/shared for the current session, mounts/* for user-provided external folders), and widen the scope only if the file is clearly not there.

Tool call style:
- Default: do not narrate routine, low-risk tool calls — just call the tool directly.
- Narrate only when it helps: multi-step work, complex problems, sensitive actions, or when the user explicitly asks.
- Keep narration brief and value-dense; avoid repeating obvious steps.
- When a tool exists for an action, use it directly instead of explaining what you plan to do.
- Use reasonable defaults and contextual inference to fill in missing details (e.g. 'tonight' means today, 'remind me' implies creating a reminder immediately). Only ask for clarification when genuinely ambiguous.

Tone and style:
- Reply in the language that best matches the user's input. Only switch languages when the user explicitly asks.
- Be concise. Prefer action over explanation — when the user asks for something that can be done via shell, do it directly.

Android framework commands (available to run via shell_execute):
CLI tools at /usr/local/bin with the `android-` prefix give you access to Android framework capabilities and on-device control. Invoke them from shell_execute like any other binary — they are already on PATH. Each tool prints JSON (or a short human-readable line) and supports --help for full usage. Tools gated by Shizuku or AccessibilityService return permission_denied when not granted — handle that gracefully and point the user at [Settings → Permissions](minis://settings/permissions).
- android-alarm — schedule alarms/timers in the system Clock app (`schedule <HH:MM> --label <L> [--repeat ONCE|DAILY|WEEKDAYS]`, `timer <seconds> --label <L>`, `open`). Alarms/timers are saved into the user's Android Clock — list/cancel are not supported (no system query API); tell the user to manage them from the Clock app's Alarms/Timers tabs (or `android-alarm open` / minis://views/alarm).
- android-calendar — read/write the device calendar (`list --start YYYY-MM-DD [--end ...] [--max N]`; `create --title <T> --start <ISO> [--end <ISO>] [--description <D>] [--location <L>] [--all-day]`).
- android-clipboard — `get | set <text> [--label L] | clear`.
- android-contacts — `list [--max N] | search <query> [--max N] | get <id> | delete <id>`. Requires READ_CONTACTS (delete also needs WRITE_CONTACTS).
- android-device — `[all|info|battery|storage]` — model, OS version, battery, storage (JSON).
- android-location — `current` for device location with reverse-geocoded address; `geocode <lat> <lon>` for reverse, `forward --address "<addr>"` for forward geocoding.
- android-notification — `send --title <T> [--body <B>] | clear | list [--max N]`. `send` triggers the system permission prompt on Android 13+ if POST_NOTIFICATIONS isn't granted. `list` reads active status-bar notifications and requires Notification Access (one-time setup; the first `list` call opens that page automatically).
- android-open <url> — open a URL via the system handler (http/https, tel:, mailto:, geo:, market:, intent:, etc.). Use shell_execute to run `android-open <url>` when you want to open something immediately. To offer a tappable link instead, write a standard Markdown link with the URL directly — the app handles system URL schemes natively.
- android-photos — `list [--max N] | stats | near <lat> <lon> [--radius KM] [--max N]` — query the device photo library via MediaStore.
- android-player — audio playback sessions (`play <session> <path>`, `pause/resume/seek/stop/status <session>`, `list`).
- android-speak — device TTS (`<text> [--rate F] [--pitch F] [--volume F]`; `--stop | --status`).
- android-speech — speech transcription (`transcribe [--engine system|provider|auto] [--source mic|<file>] [--language BCP47] [--duration SEC, max 60] [--max N] [--on-device]`; `listen` is an alias). `--engine provider` uses the Voice Input model instead (audio is uploaded to that service; also takes `--source <audio file>`), `--engine auto` falls back to it when the system recognizer cannot be reached and says `audio_sent_to`; default `--engine system`. Default is the regular recognizer; `--on-device` prefers the on-device one; either falls back to the other once and the result names `recognizer` / `fallback_used`. `languages` lists what the recognizer reports (error `unavailable` when it reports nothing); `status [--probe]` says whether recognition can work and why not. JSON envelope {ok, data|error{code,message}}; non-zero exit on failure. Requires RECORD_AUDIO.
- android-weather <latitude> <longitude> — Open-Meteo forecast (current + hourly + daily). No API key needed.
- android-shizuku-cli — invoke privileged Android system APIs (package management, settings, system commands) via Shizuku when granted. Curated subcommands return structured JSON; for anything not covered, fall back to `android-shizuku-cli exec <any shell command>` which runs the command via `sh -c` with Shizuku privilege (same surface as `adb shell`). Run with no args (or --help) for the subcommand list.
- android-a11y-cli — drive system UI (read screen, tap, type, swipe, scroll) via the Android AccessibilityService when enabled. STRONGLY PREFER the structured UI tree over screenshots for screen control: run `android-a11y-cli ui dump --compact` to read the current screen as a JSON tree of nodes (each with text / contentDesc / clickable / center / nodeId) — it is far faster, more precise, and consumes far fewer tokens than a screenshot+vision round-trip. Then act semantically by node id / text / resource-id: `android-a11y-cli tap node <nodeId>`, `tap text <label>`, `tap id <resourceId>`, `input text <s> [--node <id>]`, `scroll to-text <s>`. Use `find <text>` / `extract text|form|list` to locate specific elements and `wait appear|stable` / `dialog detect|dismiss` to synchronise. Only fall back to `ui screenshot` or a vision model when the UI has no accessible nodes (e.g. a canvas game, a rendered image, WebView content without accessibility) — screenshots are slower and expensive. Run with no args (or --help) for the full subcommand list.
- minis-open <url-or-path>: Opens a resource inside Minis without leaving the chat. Accepts http/https URLs (→ built-in WebKit preview) and chat-resource file paths under /var/minis/** (→ built-in file preview, routed by extension: images to the image viewer, .md to markdown preview, .html to HTML preview, .pdf/office docs to QuickLook, audio/video to the media player, else share sheet). Examples: minis-open https://example.com, minis-open /var/minis/workspace/report.md, minis-open /var/minis/attachments/chart.png. Prefer this over android-open for anything that can be previewed in-app so the user doesn't lose conversation context. Run android-open via shell_execute for non-web schemes (tel:, mailto:, geo:, intent:, etc.) or when the user explicitly wants the system handler.
- minis-sessions-cli: Read chat history. `list` recent or by date range, `search --keywords` cross-session, `messages --id` to read (add `--tools` for each tool call's exact input, e.g. the shell command, and its output). Run --help for full options.
- minis-model-use: Invoke other LLM models pre-configured by the user. Run `minis-model-use list` via shell_execute to see them (includes each model's modality capabilities like image_output, audio_output, etc.), `minis-model-use search <query>` to filter by name/provider. `minis-model-use run --model <id_or_name>` sends an OpenAI-compatible messages request; pass input via --input <json_file> or stdin, output goes to stdout or --output <path>. The OpenAI shape is the PRIMARY input for every model and modality; standard params are auto-converted to the underlying provider, so do not hand-write provider-native bodies as the primary input. For provider-specific extras the standard schema doesn't model (web-search plugins, image-to-image fields, TTS/video or other custom endpoints), escape hatches exist for OpenAI-compatible providers (they error or are ignored on Anthropic/Gemini models): `extra_body` (object merged verbatim into the request body), a custom `endpoint` path, and a top-level `passthrough` envelope for fully verbatim requests with RAW (unparsed) responses. Results may carry `warnings` (fields that were ignored/downgraded and why) and `applied_extras` (which extras actually took effect) — read them to self-correct. Run --help for the full contract before using these. Models may support multimodal output (image generation, TTS/audio, video) — check the modalities field in list output. For image_output models, pass generation params in the input JSON: top-level `n`/`size`/`quality`/`prompt` (OpenAI /images/generations style) or `generation_config.{aspect_ratio,image_size,number_of_images,person_generation}` (Gemini). Run with --help for full usage.${modelUseDelegateNote}
- minis-config: Read or change DeepSeek settings programmatically. Run `minis-config --help` for subcommands and `minis-config topic-help <topic>` for details on a specific area. For array-valued fields (e.g. `models`, `groups`, `envvars`, `defaults.agentLoopEntries`) the `get` subcommand accepts `--filter <keywords>` (whitespace-AND, case-insensitive substring match against each element's JSON) and `--page <N> --page-size <N>` (default 20, max 100) — use these instead of dumping the full list when you only need a subset, and check the response's `pagination` / `agent_hint` fields for the next-page command. Every write triggers an in-app confirmation sheet and is logged to a revertable audit (1000-entry rolling log). After a successful change the response includes a `user_message` field — relay it (or paraphrase) so the user knows how to review or revert via Settings → Logs → Config Changes. If the call returns `permission_denied`, the user has disabled minis-config in [Settings → Permissions](minis://settings/permissions); relay that message and don't retry. You CAN add new providers and write their `apiKey` (literal string OR a `${'$'}${'$'}ENV_VAR` reference to copy from an env var at write time), but `get` never echoes API keys / OAuth tokens / env var values back — those reads return `permission_denied` by design. OAuth tokens and env var values are not settable via this tool; for an env var, point the user at [Set ENV_NAME](minis://settings/environments?create_key=ENV_NAME&create_value=) so they enter the value themselves. Network routing and live Tailscale status live under the `network` topic (`minis-config get network.tailscale.status` returns the state and the tailnet device list).
- minis-scheduled: Create and manage scheduled tasks — prompts that run automatically later. `minis-scheduled create --prompt "..." [--label L]` plus ONE trigger: `--after 30m` (once, after a delay), `--interval 10m [--count N]` (repeat every interval, >= 60s, N times or until deleted), `--time HH:MM [--repeat once|daily|weekdays|custom --days mon,tue,...] [--start YYYY-MM-DD] [--end YYYY-MM-DD]` (at a time of day; default once), or `--trigger on-completion --of <taskId|jobId>` (once, when that scheduled task or background sub agent job finishes; `{{result}}` in --prompt becomes its result). Options: `[--target follow-up|new|rerun --session <id> --message <id>] [--model <modelId>] [--thinking off|low|medium|high|xhigh]`; `list` shows existing tasks (with nextTriggerMs and run history), `delete --id <taskId>`, `enable`/`disable --id <taskId>`, and `run --id <taskId>` fires one immediately. Target modes: `follow-up` (DEFAULT when you run it: each fire is a new turn in THIS chat, so the result comes back here; `--session <id>` picks another chat) | `new` (each fire opens a separate new chat and the result does NOT come back here — use it only when the user explicitly wants a separate chat) | `rerun` re-runs an existing chat (--session) from a chosen user message (--message). `create` prints a `delivery` line naming where the fires will land — read it, and if it is not what the user asked for, `delete --id` and create again with the right --target. When every fire starts with the same shell command, add `--command "<shell command>"` (optionally `--command-timeout 2m`), or the general `--tool shell_execute --tool-args '{"command":"…"}'`: the command runs automatically at each fire and you receive its real output as an already-completed tool call, so you only summarise/act on it instead of deciding to run it. Run it via shell_execute when the user asks to "remind me / do X every morning / run this later / schedule a task". Run --help for full usage.${scheduledDelegateNote}
Interactive terminal: minis://open_terminal opens a terminal for tasks that require interactive stdin (passwords, ssh, TUI apps like htop/vi). Write it as a Markdown link in your response — the app opens it when tapped. The optional init_command parameter pre-fills (NOT executes) a command; it MUST be fully percent-encoded (spaces → %20, & → %26, | → %7C, etc.). Only use this for genuinely interactive sessions — for everything else, use shell_execute. Examples: [Open Terminal](minis://open_terminal), [Login to SSH](minis://open_terminal?init_command=ssh%20user%40host).

Environment variables:
- Shell environment variables may contain sensitive API keys, tokens, or passwords. NEVER echo, print, cat, or otherwise output their values to stdout/stderr. Always reference them by variable name (e.g. ${'$'}API_KEY) inside scripts or commands — never inline the literal value.
- When a skill or task requires an environment variable that is not set, tell the user which variable is missing and provide a tappable deep link to create it: [Set ENV_NAME](minis://settings/environments?create_key=ENV_NAME&create_value=) — the user can tap it to open the Environment Variables page with the key pre-filled.
- Settings deep links: when you tell the user "go to Settings → X" or want to point them at a specific setting, prefer a Markdown link `[Label](minis://settings/<path>)` over plain prose. Available paths: providers (list), providers/<instanceId> (one provider), model-groups (incl. Agent Loop), model-groups/<groupId>, usage (token usage), skills, memory, storage, shared-folders (Shared Folders: /var/minis/{shared,skills,memory}), mount-external (Mount External Folders), logs, appearance, background, about, permissions, environments[?create_key=K&create_value=V[&create_note=N]], rootfs (also reachable as mirrors), network (Network Routing: Restore Direct Connections, Diagnose Network, default route, per-feature and per-provider routes), tailscale, proxy (upstream proxies), dns (Shell Container DNS), github (GitHub connector: device-flow sign-in and token paste), learning (learning notebook: the four journals, where entries can be deleted or promoted). Unknown paths fall back to Settings home, but prefer the exact path so users land where they want. These settings/action links are app deep links — render them as Markdown links in chat (same action-vs-resource rule as the minis:// section above: only /var/minis resource URLs may go to browser_use).
- To check if a variable is set, use `[ -n "${'$'}VAR" ] && echo 'set' || echo 'not set'`. NEVER use echo ${'$'}VAR, printenv VAR, or any command that would output the actual value into the conversation context.${memorySystemSection}

Scheduled tasks: crontab / at / nohup loops will stop when the app is suspended, so in-app scheduled scripts may not run as expected. For follow-ups and recurring prompts, run `minis-scheduled create …` via shell_execute: it fires through a system alarm, so it runs a new turn even when DeepSeek is in the background or closed (a force-stop cancels pending tasks until the app is next opened). For a plain reminder that only needs to notify the user, running `android-alarm` via shell_execute (the system Clock app) or a system-level schedule (Google Calendar event, Tasker automation, etc.) also works. (Waiting or polling WITHIN the current turn is different — that is what shell_execute `delay` chains are for, per the shell_execute notes above.)"""

        val skillFragment = skillRepository?.skillPromptFragment(activeSessionId)
        val todoFragment = com.openminis.app.tools.TodoTools.promptFragment(context, activeSessionId)
        mcpRepository?.reloadFromDisk()
        maybeRefreshMcpTools()
        val mcpFragment = mcpRepository?.mcpPromptFragment(activeSessionId)
        val globalMemoryFragment = if (memoryOn) memoryRepository?.loadGlobalMemoryFragment() else null
        val dailyMemoryFragment = if (memoryOn) memoryRepository?.loadRecentDailyMemoryFragment() else null
        val factFragment = if (memoryOn) memoryFactRepository.recallForPrompt(query) else ""
        AppLogger.info(
            "XSessionDiag",
            "[XSessionDiag] prompt/memory: session=${activeSessionId.take(8)} " +
                "memoryEnabled=$memoryOn " +
                "globalChars=${globalMemoryFragment?.length ?: 0} " +
                "dailyChars=${dailyMemoryFragment?.length ?: 0}",
        )

        return buildString {
            append(base)
            append("\n\n")
            append(
                "File access guard: file_write, file_edit and apply_patch may only write under " +
                    com.openminis.app.agent.guard.PathAccessPolicy.writableRoots() +
                    ". Writes to system directories (/etc, /usr/bin, /proc, /sys, /dev, /var/lib …) are refused " +
                    "before anything touches disk — install or change system files through shell_execute and a " +
                    "package manager instead.",
            )
            append("\n\n")
            append(
                "Skills ecosystem: `find_skills` searches the live public skill ecosystem (Anthropic Skills, " +
                    "Superpowers, ECC, Composio's awesome-claude-skills, plus any source the user added) and " +
                    "installs what it finds. Reach for it when the user asks whether you can do something " +
                    "specialized, or how to do it, and before telling them a capability is missing — the index " +
                    "refreshes itself every 24 hours, and keeps discovering new GitHub skill repos on its own " +
                    "(including WorkBuddy / OpenClaw community packs). After installing, skill_load the skill to " +
                    "follow its steps. You install on your own initiative the moment a task needs a capability " +
                    "you do not have — the user never has to ask whether a skill exists.\n\n" +
                    "Self-improvement: a four-journal notebook (errors, corrections, best practices, knowledge " +
                    "gaps) persists across sessions. Correcting you, a standing rule, a failed approach worth " +
                    "remembering or a better way you found → write ONE imperative sentence with lesson_write. " +
                    "Relevant entries are recalled automatically before similar tasks: apply them silently, never " +
                    "recite them. When an entry is flagged as repeated 3+ times, ask the user once whether to keep " +
                    "it permanently, and only then promote it.",
            )
            skillScoutLine()?.let { line ->
                append("\n\n")
                append(line)
            }
            lessonPromptSection()?.let { section ->
                append("\n\n")
                append(section)
            }
            if (skillFragment != null) {
                append("\n\n")
                append(skillFragment)
            }
            if (mcpFragment != null) {
                append("\n\n")
                append(mcpFragment)
            }
            if (globalMemoryFragment != null) {
                append("\n\n")
                append(globalMemoryFragment)
            }
            if (dailyMemoryFragment != null) {
                append("\n\n")
                append(dailyMemoryFragment)
            }
            if (factFragment.isNotBlank()) {
                append("\n\nRemembered facts (learned across sessions, stronger when repeated):\n")
                append(factFragment)
            }
            val projectRules = runCatching {
                val ws = com.openminis.app.sandbox.PRootKernel.resolveSessionHostPath(
                    activeSessionId.ifEmpty { realSessionId.ifEmpty { sessionId } },
                    "/var/minis/workspace",
                    context,
                )
                if (ws != null && ws.isDirectory) com.openminis.app.tools.ProjectRules.section(ws) else ""
            }.getOrDefault("")
            if (projectRules.isNotBlank()) append(projectRules.trimEnd()).append('\n')
            append("\n\nRuntime context:\n")
            append("- Current date: ").append(dateStr).append(" (").append(tzId).append(")\n")
            append("- Device language: ").append(lang).append("\n")
            append("- minis-model-use models available: ").append(modelUseCount)
            if (todoFragment != null) append(todoFragment)
            com.openminis.app.agent.goal.SessionGoals.promptFragment(context, activeSessionId)?.let { append(it) }
        }
    }


    fun executeMemoryWrite(argsJson: String): MemoryTools.ToolResult {
        val repo = memoryRepository ?: return MemoryTools.ToolResult("Error: Memory not available", false)
        if (!_memoryEnabled.value) {
            return MemoryTools.ToolResult(
                "Memory writes are disabled for this session. Reads are still available. The user can re-enable writes via the /memory slash command.",
                false,
            )
        }
        val result = MemoryTools.executeMemoryWrite(argsJson, repo)
        val content = try {
            JSONObject(argsJson).optString("content", "")
        } catch (_: Exception) { "" }
        _memoryToolRecords.value = _memoryToolRecords.value + MemoryToolRecord(
            title = result.toolTitle,
            isWrite = true,
            preview = content.lines().firstOrNull { it.isNotBlank() }?.take(100) ?: "",
            output = result.output,
            writtenContent = content,
        )
        return result
    }

    fun executeMemoryGet(argsJson: String): MemoryTools.ToolResult {
        val repo = memoryRepository ?: return MemoryTools.ToolResult("Error: Memory not available", false)
        val result = MemoryTools.executeMemoryGet(argsJson, repo)
        val keywords = try {
            JSONObject(argsJson).optString("keywords", "")
        } catch (_: Exception) { "" }
        _memoryToolRecords.value = _memoryToolRecords.value + MemoryToolRecord(
            title = result.toolTitle,
            isWrite = false,
            preview = if (keywords.isNotBlank()) "Search: $keywords" else result.output.take(100),
            output = result.output,
            keywords = keywords,
        )
        return result
    }

    suspend fun executeBrowserUse(argsJson: String): BrowserToolResult {
        val input = BrowserActionInput.parse(argsJson)
            ?: return BrowserToolResult(text = "Error: Invalid browser_use input. Required: 'action' parameter.", success = false)

        return try {
            val result = browserTabPool.execute(input, owner = browserOwnerId)
            BrowserToolResult(
                text = result.text,
                success = result.success,
                base64Image = result.base64Image,
                imageFilePath = result.imageFilePath,
                pageURL = result.pageURL,
            )
        } catch (e: Exception) {
            BrowserToolResult(text = "Error: ${e.message}", success = false)
        }
    }

    data class BrowserToolResult(
        val text: String,
        val success: Boolean,
        val base64Image: String? = null,
        val imageFilePath: String? = null,
        val pageURL: String? = null,
    )


    private fun MutableList<AgentContentPart>.addModelImage(
        part: LLMMessage.ImagePart,
        path: String?,
        note: String?,
    ) = ChatAttachmentIngest.addModelImage(this, part, path, note, ::visionPlaceholderFor)

    private fun prepareUserAttachments(
        attachments: List<InputAttachment>,
        sessionId: String,
    ): PreparedAttachments =
        ChatAttachmentIngest.prepare(
            attachments = attachments,
            sessionId = sessionId,
            filesDir = context.filesDir,
            mediaStore = mediaStore,
            openStream = { context.contentResolver.openInputStream(it.uri) },
            clockMs = System::currentTimeMillis,
            onBudgetMutated = { budgetResult ->
                AppLogger.info(
                    TAG,
                    "[ImageBudget] compose: in=${budgetResult.keptBytes.size + budgetResult.droppedCount} kept=${budgetResult.keptBytes.size} compressed=${budgetResult.compressedCount} dropped=${budgetResult.droppedCount} totalBytes=${budgetResult.totalBytes}",
                )
                _imageBudgetEvent.tryEmit(budgetResult)
            },
            fileUri = Uri::fromFile,
        )

    private fun uniqueUploadFileName(dir: java.io.File, original: String): String =
        ChatAttachmentIngest.uniqueUploadFileName(dir, original)

    private fun buildMediaRefPartJson(
        ref: com.openminis.app.data.model.MediaRef,
        linuxPath: String? = null,
    ): String = ChatPartsJson.buildMediaRefPartJson(ref, linuxPath)


    private fun buildPastedParts(text: String, sessionId: String): PastedParts? =
        ChatAttachmentIngest.pastedParts(text, { _pastedTexts.value }, sessionId, mediaStore, Uri::fromFile)

    private fun buildUserPartsJson(
        text: String,
        mediaRefPartsJson: List<String>,
        attachedFilesXml: String? = null,
        bodyPartsJson: List<String>? = null,
    ): String = ChatPartsJson.buildUserPartsJson(text, mediaRefPartsJson, attachedFilesXml, bodyPartsJson)

    private var titleGenerationAttempts = 0
    private var titleGenerationInFlight = false
    private val TITLE_MAX_ATTEMPTS = 3

    private val GROUP_CONTEXT_MAX = 30
    private val GROUP_NAME_MAX = 40
    private val GROUP_DESC_MAX = 100

    private fun generateSessionTitleIfNeeded() {
        AppLogger.info(
            "TitleGen",
            "enter session=${realSessionId.ifEmpty { sessionId }} attempts=$titleGenerationAttempts/$TITLE_MAX_ATTEMPTS " +
                "inFlight=$titleGenerationInFlight currentTitle='${_sessionTitle.value.take(200)}'",
        )
        if (titleGenerationInFlight || titleGenerationAttempts >= TITLE_MAX_ATTEMPTS) {
            AppLogger.info(
                "TitleGen",
                "skip guard=${if (titleGenerationInFlight) "inFlight" else "max-attempts ($titleGenerationAttempts/$TITLE_MAX_ATTEMPTS)"}",
            )
            return
        }
        if (_sessionTitle.value != UNTITLED_SESSION_TITLE && _sessionTitle.value.isNotEmpty()) {
            AppLogger.info("TitleGen", "skip guard=title-already-set title='${_sessionTitle.value.take(200)}'")
            return
        }
        val titleSid = realSessionId.ifEmpty { sessionId }
        val primarySource = _selectedGroupId.value
            ?.let { TitleCandidates.PrimarySource.Group(it, _activeEntryId.value) }
            ?: _activeEntryId.value?.let { TitleCandidates.PrimarySource.Entry(it) }
        val candidates = TitleCandidates.forSession(providerRepository, titleSid, primarySource, currentModel?.id)
        val lastResortProvider = if (candidates.isEmpty()) currentProvider else null
        AppLogger.info(
            "TitleGen",
            "candidates=${candidates.size} [${candidates.take(TitleCandidates.MAX_TRIES).joinToString { it.model.id }}]" +
                (if (lastResortProvider != null) " lastResort=${lastResortProvider.model.id}" else ""),
        )
        if (candidates.isEmpty() && lastResortProvider == null) {
            AppLogger.warning("TitleGen", "no provider available (no candidate + no current provider) — fallback-to-first-message path")
            viewModelScope.launch(Dispatchers.IO) {
                applyFallbackTitleFromFirstMessage("no provider available")
            }
            return
        }

        titleGenerationInFlight = true
        titleGenerationAttempts++

        val msgs = _messages.value
        val userMessages = msgs.filter { it.role == "user" }
        val firstUser = userMessages.firstOrNull()
        if (firstUser == null) {
            AppLogger.warning("TitleGen", "skip guard=no-user-message (nothing to summarize)")
            titleGenerationInFlight = false
            return
        }
        val userText = firstUser.content.take(200)
        val assistantTextMessages = msgs.filter { it.role == "assistant" && it.content.isNotBlank() }
        val firstAssistantText = assistantTextMessages.firstOrNull()?.content?.take(200) ?: ""
        val hasMultipleUserTurns = userMessages.size > 1
        val lastUserText = if (hasMultipleUserTurns) userMessages.lastOrNull()?.content?.take(200) ?: "" else ""
        val lastAssistantText = if (hasMultipleUserTurns) assistantTextMessages.lastOrNull()?.content?.take(200) ?: "" else ""

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val autoGroupOn = com.openminis.app.ui.settings
                    .autoGroupingEnabled(context)
                var groupTitleExamples = false
                val groupContext: List<String> = if (!autoGroupOn) emptyList() else {
                    val rendered = chatRepository.listFolders()
                        .map { f -> f to promptSafe(f.name, GROUP_NAME_MAX) }
                        .filter { (_, n) -> n.isNotEmpty() }
                    val ambiguous = rendered
                        .groupingBy { (_, n) -> n.lowercase() }
                        .eachCount()
                        .filterValues { it > 1 }
                        .keys
                    val offered = rendered
                        .filterNot { (_, n) -> n.lowercase() in ambiguous }
                        .take(GROUP_CONTEXT_MAX)
                    val plainLines = offered.map { (f, n) ->
                        val d = f.description?.takeIf { it.isNotBlank() }
                            ?.let { promptSafe(it, GROUP_DESC_MAX) }
                            ?.takeIf { it.isNotEmpty() }
                        if (d != null) "\"$n\" — $d" else "\"$n\""
                    }
                    if (offered.isEmpty()) plainLines else {
                        val recent = chatRepository.recentSessionTitlesByFolder()
                        val (lines, withTitles) = FolderOfferFormat.lines(offered.map { it.first.id }, plainLines, recent)
                        groupTitleExamples = withTitles > 0
                        AppLogger.info("TitleGen", "[TitleGen] Folder offer: folders=${offered.size} withTitles=$withTitles")
                        lines
                    }
                }
                val prompt = buildString {
                    append("Based on the following conversation, generate a short title (max 6 words) that captures the topic. ")
                    append("Also pick a task category from: code, writing, research, analysis, creative, chat, math, translation, health, finance, travel, education, design, productivity, support, other.\n\n")
                    if (groupContext.isNotEmpty()) {
                        append("The user organizes chats into groups. Existing groups: [")
                        append(groupContext.joinToString("; "))
                        append("]. ")
                        if (groupTitleExamples) append(FolderOfferFormat.EXAMPLES_NOTE)
                        append("If this conversation clearly belongs to one of these groups, ")
                        append("set \"folder\" to that exact group name. ")
                        append("If it does not clearly match any group, or you are unsure, set \"folder\" to null. ")
                        append("Never invent a new group name.\n\n")
                    }
                    append("You MUST respond with valid JSON only. Example:\n")
                    if (groupContext.isEmpty()) {
                        append("{\"title\": \"Debug Login Page Issue\", \"category\": \"code\"}\n\n")
                    } else {
                        append("{\"title\": \"Debug Login Page Issue\", \"category\": \"code\", \"folder\": null}\n\n")
                    }
                    append("Conversation:\n")
                    append("User: $userText\n")
                    if (firstAssistantText.isNotEmpty()) append("Assistant: $firstAssistantText\n")
                    if (lastUserText.isNotEmpty()) append("User: $lastUserText\n")
                    if (lastAssistantText.isNotEmpty()) append("Assistant: $lastAssistantText\n")
                    append(titleLanguageDirective())
                }
                val effectiveSystemPrompt = TITLE_GEN_SYSTEM_PROMPT

                suspend fun ask(provider: LLMProvider): TitleGenResult? {
                    AppLogger.info(
                        "TitleGen",
                        "dispatch attempt=$titleGenerationAttempts provider=${provider.javaClass.simpleName} model=${provider.model.id}",
                    )
                    val titleMaxTokens = if (provider.model.supportsReasoning == true) 2048 else 100
                    val response = provider.sendMessage(
                        messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
                        systemPrompt = effectiveSystemPrompt,
                        maxTokens = titleMaxTokens,
                        temperature = null,
                        thinkingLevel = ThinkingLevel.OFF,
                    )

                    AppLogger.info(
                        "TitleGen",
                        "response stopReason=${response.stopReason} textLen=${response.text.length} " +
                            "raw='${response.text.take(200).replace("\n", "\\n")}'",
                    )
                    val parsed = parseTitleResponse(response.text)
                    if (parsed.title.isEmpty()) {
                        AppLogger.warning(
                            "TitleGen",
                            "outcome=no-title model=${provider.model.id} (empty / unparseable response) " +
                                "stopReason=${response.stopReason} textLen=${response.text.length}",
                        )
                        return null
                    }
                    return parsed
                }
                val parsed: TitleGenResult? = if (lastResortProvider != null) {
                    try {
                        ask(lastResortProvider)
                    } catch (e: Exception) {
                        if (e is CancellationException && TitleCandidates.isRealCancellation(e)) throw e
                        AppLogger.warning(
                            "TitleGen",
                            "outcome=exception model=${lastResortProvider.model.id} ${e.javaClass.simpleName}: ${e.message?.take(200)}",
                        )
                        null
                    }
                } else {
                    TitleCandidates.walk(
                        candidates,
                        origin = "auto",
                        pauseMs = TitleCandidates.AUTO_PAUSE_MS,
                        shouldStop = {
                            val t = chatRepository.getSession(titleSid)?.title?.trim()
                            !t.isNullOrEmpty() && t != UNTITLED_SESSION_TITLE
                        },
                    ) { entry ->
                        val p = TitleCandidates.providerFor(providerRepository, context, entry, activeSessionId)
                            ?: return@walk null
                        ask(p)
                    }
                }
                if (parsed != null) {
                    val (title, category, folderName) = parsed
                    val sid = realSessionId.ifEmpty { sessionId }
                    val storedNow = runCatching { chatRepository.getSession(sid)?.title }.getOrNull()
                    if (!SessionTitleRules.autoTitleMayReplace(storedNow, UNTITLED_SESSION_TITLE)) {
                        AppLogger.info("TitleGen", "outcome=dropped reason=title-set-meanwhile stored='${storedNow?.take(200)}'")
                        return@launch
                    }
                    chatRepository.updateSessionTitleAndCategory(sid, title, category)
                    withContext(Dispatchers.Main) {
                        _sessionTitle.value = title
                        _sessionCategory.value = category
                    }
                    AppLogger.info("TitleGen", "outcome=set title='$title' category='$category'")
                    if (!folderName.isNullOrEmpty()) {
                        val match = chatRepository.findFolderByName(folderName)
                            ?: chatRepository.listFolders().filter {
                                promptSafe(it.name, GROUP_NAME_MAX)
                                    .equals(folderName.trim(), ignoreCase = true)
                            }.singleOrNull()
                        if (match != null) {
                            val applied = chatRepository.setFolderIfUnfiled(match.id, sid)
                            AppLogger.info(
                                "TitleGen",
                                "auto-group '$folderName' -> ${match.id.take(8)} applied=$applied",
                            )
                        } else {
                            AppLogger.info(
                                "TitleGen",
                                "auto-group: model offered '$folderName' but no group matches — leaving ungrouped",
                            )
                        }
                    }
                } else {
                    // [T-titlegen-retry-not-retrying] Every candidate failed
                    titleGenerationAttempts = TITLE_MAX_ATTEMPTS
                    AppLogger.warning("TitleGen", "outcome=gave-up (all title candidates failed) — applying first-message fallback title")
                    applyFallbackTitleFromFirstMessage("all title candidates failed")
                }
            } catch (e: Exception) {
                AppLogger.warning(
                    "TitleGen",
                    "outcome=exception attempt=$titleGenerationAttempts/$TITLE_MAX_ATTEMPTS " +
                        "${e.javaClass.simpleName}: ${e.message?.take(200)}",
                )
                if (titleGenerationAttempts >= TITLE_MAX_ATTEMPTS) {
                    AppLogger.warning("TitleGen", "outcome=gave-up ($titleGenerationAttempts/$TITLE_MAX_ATTEMPTS) — applying first-message fallback title")
                    applyFallbackTitleFromFirstMessage("request failed: ${e.message?.take(200)}")
                }
            } finally {
                titleGenerationInFlight = false
                if (!isActive) {
                    AppLogger.warning(
                        "TitleGen",
                        "outcome=cancelled attempt=$titleGenerationAttempts/$TITLE_MAX_ATTEMPTS " +
                            "session=${(realSessionId.ifEmpty { sessionId }).take(8)} " +
                            "reason=scope-cancelled — applying first-message fallback",
                    )
                    withContext(NonCancellable) {
                        applyFallbackTitleFromFirstMessage("scope cancelled")
                    }
                }
            }
        }
    }

    private suspend fun applyFallbackTitleFromFirstMessage(reason: String) {
        val sidForCheck = realSessionId.ifEmpty { sessionId }
        val existing = chatRepository.getSession(sidForCheck)?.title?.trim()
        if (!existing.isNullOrEmpty() && existing != UNTITLED_SESSION_TITLE) {
            AppLogger.info(
                "TitleGen",
                "outcome=fallback-skipped session=${sidForCheck.take(8)} reason=already-titled",
            )
            return
        }
        val raw = _messages.value.firstOrNull { it.role == "user" }?.content
        var text = raw ?: ""
        val startIdx = text.indexOf("<user-attached-files>")
        if (startIdx >= 0) {
            val endTag = "</user-attached-files>"
            val endIdx = text.indexOf(endTag, startIdx)
            text = if (endIdx >= 0) {
                text.substring(0, startIdx) + text.substring(endIdx + endTag.length)
            } else {
                text.substring(0, startIdx)
            }
        }
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) {
            AppLogger.warning(
                "TitleGen",
                "outcome=fallback-unavailable session=${sidForCheck.take(8)} " +
                    "reason=first-user-message-empty-after-cleanup ($reason)",
            )
            return
        }
        val fallbackTitle = if (cleaned.length > 30) cleaned.take(30).trimEnd() + "…" else cleaned
        chatRepository.updateSessionTitle(sidForCheck, fallbackTitle)
        withContext(Dispatchers.Main) {
            _sessionTitle.value = fallbackTitle
        }
        AppLogger.info(
            "TitleGen",
            "outcome=fallback session=${sidForCheck.take(8)} titleLen=${fallbackTitle.length} reason=$reason",
        )
    }

    private fun parseTitleResponse(text: String): TitleGenResult {
        val cleaned = text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        try {
            val json = JSONObject(cleaned)
            val title = json.optString("title", "").trim()
            val category = json.optString("category", "").trim().ifEmpty { null }
            val folder = json.optString("folder", "").trim()
                .takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            if (title.isNotEmpty()) return TitleGenResult(title, category, folder)
        } catch (_: Exception) {}
        val titleMatch = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)
        val catMatch = Regex("\"category\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)
        val folderMatch = Regex("\"folder\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)
        if (titleMatch != null) {
            return TitleGenResult(
                titleMatch.groupValues[1].trim(),
                catMatch?.groupValues?.getOrNull(1)?.trim(),
                folderMatch?.groupValues?.getOrNull(1)?.trim()
                    ?.takeIf { !it.equals("null", ignoreCase = true) },
            )
        }
        val firstLine = cleaned.lines().firstOrNull()?.trim() ?: ""
        return TitleGenResult(firstLine.take(50), null, null)
    }

    private data class TitleGenResult(
        val title: String,
        val category: String?,
        val folder: String?,
    )

    private fun publishOverlayReplyExcerpt(sessionId: String) {
        val snapshot = _messages.value
        val text = snapshot.asReversed().firstOrNull { msg ->
            msg.role == "assistant" && msg.content.isNotBlank()
        }?.content
        SessionActivityTracker.publishLastReply(sessionId, text)
    }

    private fun ownSessionIds(): Set<String> =
        setOf(activeSessionId, sessionId, realSessionId).filterTo(LinkedHashSet()) { it.isNotEmpty() }

    fun liveTurnToken(): Long? = liveTurn.liveGeneration()

    fun stopByUser() {
        val manager = com.openminis.app.scheduled.ScheduledTaskManager(context)
        val ids = ownSessionIds()
        val cancelledLabels = manager.list().filter { it.enabled && it.creatorSessionId in ids }.map { it.label }
        for (id in ownSessionIds()) manager.disableAllCreatedBy(id, "user stopped the conversation")
        val registry = com.openminis.app.agent.jobs.AgentJobRegistry
        if (_isStreaming.value || ownSessionIds().any { registry.hasAgentWork(it) }) cancelStream()
        StopScopePolicy.scheduledCancelledNotice(cancelledLabels)?.let { noteForNextTurn(it, "scheduled-cancelled") }
    }

    private fun noteForNextTurn(note: String, reason: String) {
        agentHistory.add(
            LLMMessage(role = LLMMessage.Role.USER, content = note, contentParts = listOf(AgentContentPart.Text(note))),
        )
        val sid = activeSessionId
        val after = cleanupPersistJob
        viewModelScope.launch(Dispatchers.IO) {
            after?.join()
            runCatching {
                chatRepository.appendMessage(sid, "user", """[{"type":"text","value":${escapeJson(note)}}]""")
            }.onFailure { AppLogger.warning(TAG_STREAM, "[stop-choice] note ($reason) not stored: ${it.message}") }
        }
        AppLogger.info(TAG_STREAM, "[stop-choice] note for the next turn ($reason) sid=$sid")
    }

    fun stopSummary(): StopSummary {
        val ids = ownSessionIds()
        val registry = com.openminis.app.agent.jobs.AgentJobRegistry
        val now = System.currentTimeMillis()
        return StopSummary(
            runningTools = foregroundTools.runningNames(),
            subAgentsRunning = ids.sumOf { registry.runningChildJobCount(it) },
            subAgentsQueued = ids.sumOf { registry.queuedCount(it) },
            scheduledTasks = com.openminis.app.scheduled.ScheduledTaskManager(context).list().count { t ->
                t.creatorSessionId in ids && com.openminis.app.scheduled.ScheduledSessionHold.isLive(t, now)
            },
        )
    }

    fun stopCurrentToolOnly() {
        val running = foregroundTools.runningNames()
        val mode = StopScopePolicy.toolOnlyStop(toolsRunning = running.isNotEmpty())
        AppLogger.info(
            TAG_STREAM,
            "[stop-tool] stopCurrentToolOnly mode=$mode tools=$running queue=${_promptQueue.value.size} " +
                "_isStreaming=${_isStreaming.value} (sid=$activeSessionId)",
        )
        when (mode) {
            ToolOnlyStop.INTERRUPT_TOOLS -> interruptForegroundTools(ids = null)
            ToolOnlyStop.END_TURN -> cancelStream(keepBackground = true)
        }
    }

    private fun interruptForegroundTools(ids: Collection<String>?) {
        toolOnlyStopTurn = liveTurn.liveGeneration()
        val names = ids?.mapNotNull { foregroundTools.nameOf(it) }?.toSet()
        fun targets(tool: String) = names == null || tool in names
        if (targets(com.openminis.app.agent.userask.UserAskPromptText.TOOL_NAME)) {
            com.openminis.app.agent.userask.UserAskCenter.dismissPending(
                activeSessionId, com.openminis.app.agent.userask.UserAskDismissReason.STOPPED,
            )
        }
        if (names == null || names.any { it !in SHELL_FREE_TOOLS }) {
            ExecutionCoordinator.interruptForegroundCommands(activeSessionId)
            if (isDraft && realSessionId.isNotEmpty() && activeSessionId != sessionId) {
                ExecutionCoordinator.interruptForegroundCommands(sessionId)
            }
        }
        if (targets(com.openminis.app.agent.jobs.HelperRunner.TOOL_NAME)) {
            delegateDetach.value = delegateDetach.value + 1
        }
        foregroundTools.interrupt(ids)
    }

    private fun idsStoppedWith(blockId: String): List<String> {
        if (foregroundTools.nameOf(blockId) in SHELL_FREE_TOOLS) return listOf(blockId)
        return (listOf(blockId) + foregroundTools.idsNamed("shell_execute")).distinct()
    }

    fun cancelStream(keepBackground: Boolean = false) {
        com.openminis.app.diagnostics.JankDiag.onCancelStreamStart()
        val wasStreaming = _isStreaming.value
        val stoppedBubble = liveTurn.cancel()
        AppLogger.info(
            TAG_STREAM,
            "cancelStream invoked _isStreaming=$wasStreaming liveBubble=${stoppedBubble ?: "-"} " +
                "keepBackground=$keepBackground (sid=$activeSessionId)",
        )
        lastTurnWasCancelled = true
        com.openminis.app.agent.userask.UserAskCenter.dismissPending(
            activeSessionId, com.openminis.app.agent.userask.UserAskDismissReason.STOPPED,
        )
        if (!keepBackground) {
            com.openminis.app.agent.jobs.AgentJobRegistry.muteDelegationResults(activeSessionId)
            com.openminis.app.agent.jobs.AgentJobRegistry.dropQueuedDelegations(activeSessionId, "user-stopped")
            com.openminis.app.agent.jobs.AgentJobRegistry.cancelAll(activeSessionId, "user-stopped")
        }
        streamJob?.cancel()
        _isStreaming.value = false
        flushAllStreamingDeltas()
        publishOverlayReplyExcerpt(activeSessionId)
        SessionActivityTracker.clearToolRunning(com.openminis.app.service.ToolOutcome.Cancelled)
        SessionActivityTracker.markStreamInterrupted(activeSessionId)
        SessionActivityTracker.setInactive(activeSessionId)
        if (isDraft && realSessionId.isNotEmpty() && activeSessionId != sessionId) {
            SessionActivityTracker.setInactive(sessionId)
        }
        if (keepBackground) {
            ExecutionCoordinator.interruptForegroundCommands(activeSessionId)
        } else {
            ExecutionCoordinator.stopCurrentCommand(activeSessionId)
        }
        if (isDraft && realSessionId.isNotEmpty() && activeSessionId != sessionId) {
            if (keepBackground) {
                ExecutionCoordinator.interruptForegroundCommands(sessionId)
            } else {
                ExecutionCoordinator.stopCurrentCommand(sessionId)
            }
        }
        if (_streamingById.value.isNotEmpty()) {
            AppLogger.warning(TAG_STREAM, "[StopLate] cancelStream: draining ${_streamingById.value.size} overlay entr(ies) left after the kill")
            flushAllStreamingDeltas()
        }
        if (_autoRetryAttempt.value != 0 || _autoRetryCountdown.value != 0) {
            _autoRetryAttempt.value = 0
            _autoRetryCountdown.value = 0
            clearInlineError()
        }
        handleUserCancelledCleanup()

        val pending = _promptQueue.value
        if (pending.isNotEmpty()) {
            AppLogger.info(TAG_STREAM, "cancel — ${pending.size} queued prompt(s) remain, restarting drain")
            resumeQueueAfterCancel()
        }
        com.openminis.app.diagnostics.JankDiag.onCancelStreamEnd()
    }

    fun stopTool(blockId: String) {
        val liveId = liveTurn.liveAssistantId()
        val streaming = _isStreaming.value
        val liveBlocks = when {
            liveId != null -> _streamingById.value[liveId]?.toolBlocks
                ?: _messages.value.lastOrNull { it.id == liveId }?.toolBlocks.orEmpty()
            streaming -> _messages.value.lastOrNull { it.role == "assistant" }?.let { m ->
                _streamingById.value[m.id]?.toolBlocks ?: m.toolBlocks
            }.orEmpty()
            else -> emptyList()
        }
        val shown = _streamingById.value.values.firstNotNullOfOrNull { d -> d.toolBlocks.firstOrNull { it.id == blockId } }
            ?: _messages.value.firstNotNullOfOrNull { m -> m.toolBlocks.firstOrNull { it.id == blockId } }
        val action = InFlightToolHeal.stopAction(blockId, streaming, liveBlocks, shown?.toolStatus)
        AppLogger.info(
            TAG_STREAM,
            "[StopLate] stopTool block=${blockId.take(16)} status=${shown?.toolStatus} " +
                "_isStreaming=$streaming liveBubble=${liveId ?: "-"} -> $action (sid=$activeSessionId)",
        )
        when (action) {
            InFlightToolHeal.StopAction.STOP_TURN -> if (foregroundTools.isRunning(blockId)) {
                interruptForegroundTools(idsStoppedWith(blockId))
            } else {
                stopCurrentToolOnly()
            }
            InFlightToolHeal.StopAction.CANCEL_STALE_BLOCK -> cancelStaleBlock(blockId)
            InFlightToolHeal.StopAction.NONE -> Unit
        }
    }

    private fun cancelStaleBlock(blockId: String) {
        val liveId = liveTurn.liveAssistantId()
        val owner = _messages.value.lastOrNull { m -> m.role == "assistant" && m.toolBlocks.any { it.id == blockId } }
        _messages.value = _messages.value.map { m ->
            if (m.role != "assistant" || m.id == liveId) m else {
                val fixed = InFlightToolHeal.cancelBlock(m.toolBlocks, blockId)
                if (fixed === m.toolBlocks) m else m.copy(toolBlocks = fixed)
            }
        }
        for (id in _streamingById.value.keys.filter { it != liveId }) flushStreamingDelta(id)
        if (owner != null && owner.id != liveId) {
            owner.toolBlocks.firstOrNull { it.id == blockId && it.kind == "tool_use" }?.let {
                persistCancelledIfUnanswered(listOf(it.id to it.toolName), "stop-tool")
            }
        }
    }

    internal fun healStaleInFlightTools(reason: String, keep: Set<String> = emptySet()): Int {
        val liveId = liveTurn.liveAssistantId()
        if (_isStreaming.value && liveId == null) return 0
        if (!_isStreaming.value && SessionActivityTracker.isActive(activeSessionId)) return 0
        val orphans = _streamingById.value.keys.filter { it != liveId }
        for (id in orphans) flushStreamingDelta(id)
        val healed = InFlightToolHeal.sweep(_messages.value, keep + setOfNotNull(liveId)) { b ->
            com.openminis.app.agent.jobs.HelperRunner.isSubAgentToolName(b.toolName)
        }
        if (healed.blockCount > 0) _messages.value = healed.messages
        if (orphans.isNotEmpty() || healed.blockCount > 0) {
            AppLogger.warning(
                TAG_STREAM,
                "[StopLate] heal($reason): drained ${orphans.size} orphan overlay entr(ies), " +
                    "${healed.blockCount} in-flight block(s) -> CANCELLED " +
                    "[${healed.toolUses.joinToString(",") { it.second.take(16) }}] (sid=$activeSessionId)",
            )
        }
        if (healed.toolUses.isNotEmpty()) {
            persistCancelledIfUnanswered(healed.toolUses.map { it.second to it.third }, reason)
        }
        return healed.blockCount
    }

    private fun persistCancelledIfUnanswered(toolUses: List<Pair<String, String>>, reason: String) {
        if (toolUses.isEmpty()) return
        val sid = realSessionId.ifEmpty { sessionId }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val rows = chatRepository.loadMessages(sid).map { it.partsJson }
                val ok = InFlightToolHeal.appendableCancelResults(rows, toolUses.map { it.first })
                val parts = toolUses.filter { it.first in ok }.map { (id, name) ->
                    AgentContentPart.ToolResult(id = id, name = name, content = CANCELLED_MARKER, isError = true)
                }
                if (parts.isNotEmpty()) persistToolResultMessage(parts)
                AppLogger.info(
                    TAG_STREAM,
                    "[StopLate] persist($reason): stored ${parts.size} cancelled result(s), " +
                        "${toolUses.size - parts.size} already answered or not appendable (sid=$sid)",
                )
            }.onFailure { AppLogger.warning(TAG_STREAM, "[StopLate] persist($reason) failed: ${it.message}") }
        }
    }

    private fun noteStopLateLine(toolId: String, line: String, where: String) {
        val n = stopLateDrops.merge("line:$toolId", 1, Int::plus) ?: 1
        if (n <= 5 || n % 100 == 0) {
            AppLogger.info(
                TAG_STREAM,
                "[StopLate] dropped line #$n for ${toolId.take(16)} after stop ($where): ${line.take(160)}",
            )
        }
    }

    private fun noteStopLateOverlay(id: String, blocks: List<AssistantBlock>) {
        val n = stopLateDrops.merge("overlay:$id", 1, Int::plus) ?: 1
        if (n == 1 || n % 50 == 0) {
            val inFlight = blocks.count { InFlightToolHeal.isInFlight(it.toolStatus) }
            AppLogger.info(
                TAG_STREAM,
                "[StopLate] dropped overlay write #$n for $id (live=${liveTurn.liveAssistantId() ?: "-"}, " +
                    "inFlightBlocks=$inFlight)",
            )
        }
    }

    private fun resumeQueueAfterCancel() {
        viewModelScope.launch {
            kotlinx.coroutines.delay(200)
            if (_promptQueue.value.isEmpty()) return@launch
            if (_isStreaming.value) return@launch
            if (_isCompacting.value) {
                AppLogger.info(TAG, "resumeQueueAfterCancel: compact in flight — deferring to its completion kick")
                return@launch
            }

            val initialProvider = currentProvider
            if (initialProvider == null) {
                AppLogger.warning(TAG, "resumeQueueAfterCancel: no provider, dropping queue")
                _promptQueue.value = emptyList()
                _messages.value = _messages.value.filterNot { it.isQueued }
                return@launch
            }
            var provider: LLMProvider = initialProvider

            if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
                try {
                    val activeEntryId = _activeEntryId.value
                    val entry = activeEntryId?.let { id -> providerRepository.config.value.modelEntries.find { it.id == id } }
                    val instance = entry?.let { e -> providerRepository.config.value.instances.find { it.id == e.providerInstanceId } }
                    if (instance != null) {
                        val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                        val freshToken = manager?.validAccessToken()
                        if (freshToken != null) {
                            val storedKey = providerRepository.loadApiKey(instance.id)
                            if (freshToken != storedKey) {
                                providerRepository.saveApiKey(instance.id, freshToken)
                                provider = com.openminis.app.provider.ProviderFactory.create(
                                    instance, freshToken, currentModel ?: provider.model, context,
                                    sessionId = activeSessionId,
                                    overrides = (provider as? com.openminis.app.provider.openai.OpenAIProvider)
                                        ?.modelOverrides,
                                )
                                currentProvider = provider
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "OAuth token refresh failed (resumeQueueAfterCancel): ${e.message}")
                }
            }

            decideUserAskExposureForRun(interactive = _promptQueue.value.any { it.origin == QueuedPromptOrigin.USER })
            // [T-port-user-ask]
            val baseSystemPrompt = buildSystemPrompt()
            val systemPrompt = if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
                val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
                if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
                else "$prefix\n\n${baseSystemPrompt ?: ""}"
            } else baseSystemPrompt

            AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel _isStreaming=true (sync, sid=$activeSessionId)")
            _isStreaming.value = true
            _canResume.value = false
            _error.value = null

            val holdsSlot = helperConfig == null
            streamJob = launch(Dispatchers.IO) {
                AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel streamJob ENTER sid=$activeSessionId")
                try {
                    if (holdsSlot) SessionConcurrencyManager.acquireSlot(activeSessionId)
                    AppLogger.debug(TAG_STREAM, "resumeQueueAfterCancel streamJob slot acquired")
                    SessionActivityTracker.setActive(
                    activeSessionId,
                    onStop = { cancelStream() },
                    sessionTitle = overlaySessionTitle(),
                )

                    val activeFallbackStrategy = run {
                        val groupId = _selectedGroupId.value
                        groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                            ?: com.openminis.app.data.model.FallbackStrategy.default
                    }
                    val fallbackProviders = buildFallbackProviders(provider)

                    try {
                        AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel drainQueuedPrompts CALL")
                        drainQueuedPrompts(
                            provider = provider,
                            systemPrompt = systemPrompt,
                            fallbackProviders = fallbackProviders,
                            fallbackStrategy = activeFallbackStrategy,
                        )
                        AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel drainQueuedPrompts RETURN")
                    } catch (e: CancellationException) {
                        AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel drain CANCELLED")
                    } catch (e: Exception) {
                        AppLogger.error(TAG_STREAM, "resumeQueueAfterCancel drain EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                        Log.e(TAG, "Queued drain error (resumeQueueAfterCancel)", e)
                        setInlineError(e.message ?: "Unknown error")
                    } finally {
                        AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel streamJob FINALLY enter")
                        publishOverlayReplyExcerpt(activeSessionId)
                        markRunOutcomeForNotification(activeSessionId)
                        SessionActivityTracker.setInactive(activeSessionId)
                        if (holdsSlot) SessionConcurrencyManager.releaseSlot(activeSessionId)
                        AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel streamJob FINALLY exit")
                    }
                } catch (e: CancellationException) {
                    AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel streamJob CANCELLED waiting for slot")
                }
                if (streamJob === coroutineContext[Job]) {
                    AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel _isStreaming=false (about to set)")
                    _isStreaming.value = false
                } else {
                    AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel _isStreaming SKIPPED (stale job)")
                }
                AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel streamJob EXIT")
            }
        }
    }

    private fun handleUserCancelledCleanup() {
        healStaleInFlightTools(
            "cancel",
            keep = setOfNotNull(_messages.value.lastOrNull { it.role == "assistant" }?.id),
        )
        val msgs = _messages.value.toMutableList()
        val lastIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastIdx < 0) return
        var last = msgs[lastIdx]

        if (last.isAwaitingModelResponse) {
            last = last.copy(isAwaitingModelResponse = false)
            msgs[lastIdx] = last
            _messages.value = msgs
        }

        val cancelledIds = mutableListOf<Pair<String, String>>()
        val updatedBlocks = last.toolBlocks.map { b ->
            val s = b.toolStatus
            if (s == ToolBlockStatus.STREAMING || s == ToolBlockStatus.PENDING || s == ToolBlockStatus.RUNNING) {
                if (b.kind == "tool_use") cancelledIds.add(b.id to b.toolName)
                b.copy(toolStatus = ToolBlockStatus.CANCELLED)
            } else b
        }
        val hadInflightTools = cancelledIds.isNotEmpty()
        if (hadInflightTools) {
            msgs[lastIdx] = last.copy(toolBlocks = updatedBlocks)
            _messages.value = msgs
            val parts = cancelledIds.map { (id, name) ->
                AgentContentPart.ToolResult(
                    id = id,
                    name = name,
                    content = CANCELLED_MARKER,
                    isError = true,
                )
            }
            cleanupPersistJob = viewModelScope.launch(Dispatchers.IO) {
                persistToolResultMessage(parts)
            }
            markLiveInterruption()
            _canResume.value = true
            return
        }

        val partialText = buildString {
            if (last.content.isNotEmpty()) append(last.content)
            for (b in last.toolBlocks) {
                if (b.kind == "text" && b.content.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append(b.content)
                }
            }
        }
        val historyEndsWithAssistant =
            agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT

        val hasAnyToolUse = last.toolBlocks.any { it.kind == "tool_use" }
        if (partialText.isEmpty() && !hasAnyToolUse && !historyEndsWithAssistant) {
            msgs.removeAt(lastIdx)
            _messages.value = msgs
            return
        }

        if (partialText.isNotEmpty() && !historyEndsWithAssistant) {
            val parts = listOf<AgentContentPart>(
                AgentContentPart.Text(partialText),
                AgentContentPart.Text(
                    "<system-reminder>The user stopped this response. Content may be incomplete.</system-reminder>"
                ),
            )
            agentHistory.add(
                LLMMessage(
                    role = LLMMessage.Role.ASSISTANT,
                    content = partialText,
                    contentParts = parts,
                )
            )
            cleanupPersistJob = viewModelScope.launch(Dispatchers.IO) {
                val partsJson = buildAssistantPartsJson(parts)
                chatRepository.appendMessage(activeSessionId, "assistant", partsJson)
            }
            markLiveInterruption()
            _canResume.value = true
        } else if (historyEndsWithAssistant) {
            markLiveInterruption()
            _canResume.value = true
        }
    }

    private fun buildAssistantPartsJson(parts: List<AgentContentPart>): String {
        val sb = StringBuilder("[")
        var first = true
        for (p in parts) {
            if (p !is AgentContentPart.Text) continue
            if (!first) sb.append(',') else first = false
            sb.append("""{"type":"text","value":""")
            sb.append(escapeJson(p.text))
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    fun resume() {
        if (_isStreaming.value || !_canResume.value) return
        com.openminis.app.agent.jobs.AgentJobRegistry.clearDelegationMute(activeSessionId)
        rebuildProviderIfEdited()
        val provider = currentProvider ?: run {
            _error.value = "No provider configured"
            return
        }
        _canResume.value = false
        _error.value = null
        clearPersistedLastAssistantError()
        AppLogger.info(TAG, "▶️ resume: continuing partial assistant message (no new header emitted)")
        _forceScrollToBottom.tryEmit(Unit)

        val historyEndsWithAssistant =
            agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT
        if (historyEndsWithAssistant) {
            val planSnapshot = try {
                com.openminis.app.tools.TodoTools.promptFragment(context, activeSessionId).orEmpty()
            } catch (_: Exception) { "" }
            val reminder = buildString {
                append("<system-reminder>The previous run was interrupted and is now resuming. ")
                append("Pick up exactly where you left off: continue the current task plan from the in_progress item (or highest-priority pending item), and do NOT repeat already-completed steps. ")
                append("Note: live state from the previous run is gone — browser tabs are closed, background shell processes have ended, and unsaved in-memory context is lost; files you wrote under /var/minis/workspace and other /var/minis directories are still on disk. ")
                append("Re-verify any artifact or process you depend on before continuing.</system-reminder>")
                if (planSnapshot.isNotBlank()) append("\n$planSnapshot")
            }
            val parts = listOf<AgentContentPart>(AgentContentPart.Text(reminder))
            agentHistory.add(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = reminder,
                    contentParts = parts,
                )
            )
            viewModelScope.launch(Dispatchers.IO) {
                val partsJson = """[{"type":"text","value":${escapeJson(reminder)}}]"""
                chatRepository.appendMessage(activeSessionId, "user", partsJson)
            }
        }

        viewModelScope.launch {
            decideUserAskExposureForRun(interactive = true)
            // [T-port-user-ask]
            val baseSystemPrompt = buildSystemPrompt()
            val systemPrompt =
                if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
                    val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
                    if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
                    else "$prefix\n\n${baseSystemPrompt ?: ""}"
                } else baseSystemPrompt

            AppLogger.info(TAG_STREAM, "resume _isStreaming=true (sid=$activeSessionId)")
            _isStreaming.value = true
            val holdsSlot = helperConfig == null
            streamJob = launch(Dispatchers.IO) {
                AppLogger.info(TAG_STREAM, "resume streamJob ENTER sid=$activeSessionId")
                try {
                    if (holdsSlot) SessionConcurrencyManager.acquireSlot(activeSessionId)
                    AppLogger.debug(TAG_STREAM, "resume streamJob slot acquired")
                    SessionActivityTracker.setActive(
                    activeSessionId,
                    onStop = { cancelStream() },
                    sessionTitle = overlaySessionTitle(),
                )
                    val activeFallbackStrategy = run {
                        val groupId = _selectedGroupId.value
                        groupId?.let {
                            providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy
                        } ?: com.openminis.app.data.model.FallbackStrategy.default
                    }
                    val fallbackProviders = buildFallbackProviders(provider)
                    try {
                        AppLogger.info(TAG_STREAM, "resume runAgentLoop CALL")
                        runAgentLoop(
                            provider = provider,
                            systemPrompt = systemPrompt,
                            fallbackProviders = fallbackProviders,
                            fallbackStrategy = activeFallbackStrategy,
                        )
                        AppLogger.info(TAG_STREAM, "resume runAgentLoop RETURN normal")
                        drainQueuedPrompts(provider, systemPrompt, fallbackProviders, activeFallbackStrategy)
                        AppLogger.info(TAG_STREAM, "resume drainQueuedPrompts RETURN")
                    } catch (e: CancellationException) {
                        AppLogger.info(TAG_STREAM, "resume runAgentLoop CANCELLED")
                        Log.d(TAG, "Agent loop cancelled (resume)")
                    } catch (e: Exception) {
                        AppLogger.error(TAG_STREAM, "resume runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                        Log.e(TAG, "Agent loop error (resume)", e)
                        setInlineError(e.message ?: "Unknown error")
                    } finally {
                        AppLogger.info(TAG_STREAM, "resume streamJob FINALLY enter")
                        publishOverlayReplyExcerpt(activeSessionId)
                        markRunOutcomeForNotification(activeSessionId)
                        SessionActivityTracker.setInactive(activeSessionId)
                        if (holdsSlot) SessionConcurrencyManager.releaseSlot(activeSessionId)
                        AppLogger.info(TAG_STREAM, "resume streamJob FINALLY exit")
                    }
                } catch (e: CancellationException) {
                    AppLogger.info(TAG_STREAM, "resume streamJob CANCELLED waiting for slot")
                    Log.d(TAG, "Cancelled while waiting for concurrency slot (resume)")
                }
                if (streamJob === coroutineContext[Job]) {
                    AppLogger.info(TAG_STREAM, "resume _isStreaming=false (about to set)")
                    _isStreaming.value = false
                } else {
                    AppLogger.info(TAG_STREAM, "resume _isStreaming SKIPPED (stale job)")
                }
                AppLogger.info(TAG_STREAM, "resume streamJob EXIT")
            }
        }
    }

    override fun onCleared() {
        for (key in setOf(activeSessionId, sessionId, realSessionId)) {
            if (key.isEmpty()) continue
            com.openminis.app.agent.jobs.AgentJobRegistry.unregisterQueuedStarter(key)
            com.openminis.app.agent.jobs.AgentJobRegistry.unregisterInterruptedCounter(key)
        }
        super.onCleared()
        ExecutionCoordinator.sessionDidTerminate(activeSessionId)
        if (activeSessionId != sessionId) {
            ExecutionCoordinator.sessionDidTerminate(sessionId)
        }
    }

    fun cleanupIfEmptyOnExit() {
        val sid = realSessionId
        if (sid.isEmpty()) return
        if (_isStreaming.value) return
        if (_attachments.value.isNotEmpty()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val count = chatRepository.messageCount(sid)
                if (count > 0) return@launch
                AppLogger.info(
                    TAG,
                    "cleanupIfEmptyOnExit: deleting empty session $sid (no persisted messages)",
                )
                com.openminis.app.data.session.SessionDeleter.deleteTree(context, chatRepository, sid, "empty-on-exit")
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "cleanupIfEmptyOnExit failed for $sid: ${t.message}")
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    private fun escapeJson(text: String): String = ChatPartsJson.escapeJson(text)

    private val systemReminderRegex =
        Regex("\\s*<system-reminder>.*?</system-reminder>\\s*", RegexOption.DOT_MATCHES_ALL)

    private fun stripSystemReminders(text: String): String =
        if (!text.contains("<system-reminder>")) text
        else systemReminderRegex.replace(text, "")

    private fun stripAttachedFilesXml(text: String): String {
        val startIdx = text.indexOf("<user-attached-files>")
        if (startIdx < 0) return text
        val endTag = "</user-attached-files>"
        val endIdx = text.indexOf(endTag, startIdx)
        return if (endIdx >= 0) {
            text.substring(0, startIdx) + text.substring(endIdx + endTag.length)
        } else {
            text.substring(0, startIdx)
        }
    }

    private fun List<MessageEntity>.toChatMessages(): List<ChatMessage> {
        val toolResultMap = mutableMapOf<String, ToolResultData>()
        for (entity in this) {
            if (entity.role != "user") continue
            try {
                val array = org.json.JSONArray(entity.partsJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    if (obj.optString("type") == "toolResult") {
                        val value = obj.getJSONObject("value")
                        val toolUseId = value.optString("toolUseId", "")
                        if (toolUseId.isNotEmpty()) {
                            toolResultMap[toolUseId] = ToolResultData(
                                output = value.optString("output", ""),
                                success = value.optBoolean("success", true),
                                durationMs = ToolDurationWire.restoreMs(value),
                            )
                        }
                    }
                }
            } catch (_: Exception) {  }
        }

        return mapNotNull { entity ->
            var text = ""
            val blocks = mutableListOf<AssistantBlock>()
            val restoredImageUris = mutableListOf<Uri>()
            val restoredImageNames = mutableListOf<String>()
            val restoredFileNames = mutableListOf<String>()
            val restoredAttachmentUris = mutableListOf<Uri>()

            if (entity.role == "assistant" && !entity.reasoningContent.isNullOrEmpty()) {
                blocks.add(AssistantBlock(
                    id = "thinking_restored_${entity.id}",
                    kind = "thinking",
                    content = entity.reasoningContent,
                    toolTitle = "Thinking",
                    toolStatus = ToolBlockStatus.SUCCESS,
                ))
            }

            try {
                val array = org.json.JSONArray(entity.partsJson)
                var textBlockCounter = 0
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    when (obj.optString("type")) {
                        "text" -> {
                            val raw = obj.optString("value", "")
                            val t = stripAttachedFilesXml(stripSystemReminders(raw)).let {
                                if (it != raw) it.trim() else it
                            }
                            if (t.isEmpty()) continue
                            text += t
                            if (entity.role == "assistant") {
                                blocks.add(AssistantBlock(
                                    id = "text_restored_${entity.id}_${textBlockCounter++}",
                                    kind = "text",
                                    content = t,
                                ))
                            }
                        }
                        "toolUse" -> {
                            val value = obj.getJSONObject("value")
                            val toolId = value.optString("toolUseId", "")
                            if (toolId.startsWith("thinking_")) continue
                            val toolInput = value.optString("input", "")
                            val result = toolResultMap[toolId]
                            val pageURL = value.optString("pageURL", "").ifEmpty { null }
                            val imgPath = value.optString("imageFilePath", "").ifEmpty { null }
                            blocks.add(AssistantBlock(
                                id = toolId,
                                kind = "tool_use",
                                toolName = value.optString("name", ""),
                                toolTitle = value.optString("description", ""),
                                toolArgs = toolInput,
                                content = result?.output?.let { ToolTitleReminder.stripForDisplay(it) }
                                    ?.lines()?.takeLast(80)?.joinToString("\n") ?: "",
                                toolStatus = when {
                                    result == null -> ToolBlockStatus.SUCCESS
                                    !result.success && (
                                        result.output.startsWith(CANCELLED_MARKER) ||
                                            result.output.startsWith(LEGACY_CANCELLED_MARKER)
                                    ) -> ToolBlockStatus.CANCELLED
                                    result.success -> ToolBlockStatus.SUCCESS
                                    else -> ToolBlockStatus.FAILED
                                },
                                browserURL = pageURL,
                                imageFilePath = imgPath,
                                durationMs = result?.durationMs ?: 0L,
                                thoughtSignature = value.optString("thoughtSignature", "").ifEmpty { null },
                            ))
                        }
                        "mediaRef" -> {
                            if (entity.role != "user") continue
                            val value = obj.optJSONObject("value") ?: continue
                            val rel = value.optString("relativePath", "")
                            if (rel.isEmpty()) continue
                            val file = java.io.File(mediaStore.mediaBaseDir, rel)
                            if (!file.exists()) continue
                            val mime = value.optString("mimeType", "")
                            val name = value.optString("originalFileName", "").ifEmpty { file.name }
                            if (mime.startsWith("image/")) {
                                restoredImageUris.add(Uri.fromFile(file))
                                restoredImageNames.add(name)
                            } else {
                                restoredAttachmentUris.add(Uri.fromFile(file))
                                restoredFileNames.add(name)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "toChatMessages: failed to parse partsJson for id=${entity.id} " +
                        "len=${entity.partsJson.length} role=${entity.role}: ${e.javaClass.simpleName}: ${e.message}",
                )
                text = "(message could not be parsed: ${e.javaClass.simpleName}, " +
                    "${entity.partsJson.length} bytes)"
            }

            if (entity.role == "user" && text.isBlank() && restoredImageUris.isEmpty()) return@mapNotNull null
            if (entity.role == "assistant" && text.isBlank() && blocks.isEmpty() &&
                entity.errorInfo.isNullOrBlank()
            ) return@mapNotNull null
            ChatMessage(
                id = entity.id,
                role = entity.role,
                content = text,
                imageUris = restoredImageUris,
                attachmentNames = restoredImageNames + restoredFileNames,
                attachmentUris = restoredAttachmentUris,
                toolBlocks = blocks,
                sourceDbIds = listOf(entity.id),
                tokenUsage = if (entity.role == "assistant") {
                    ChatTokenUsage.parse(entity.tokenUsage)
                } else null,
                completedAt = if (entity.role == "assistant") entity.createdAt else null,
                error = entity.errorInfo?.takeIf { it.isNotBlank() },
            )
        }.let { messages ->
            val merged = mutableListOf<ChatMessage>()
            for (msg in messages) {
                val prev = merged.lastOrNull()
                if (msg.role == "assistant" && prev?.role == "assistant") {
                    val seen = mutableSetOf<String>()
                    val combinedBlocks = (prev.toolBlocks + msg.toolBlocks)
                        .asReversed()
                        .filter { seen.add(it.id) }
                        .asReversed()
                    val combinedText = when {
                        prev.content.isBlank() -> msg.content
                        msg.content.isBlank() -> prev.content
                        else -> prev.content + "\n\n" + msg.content
                    }
                    merged[merged.lastIndex] = prev.copy(
                        id = msg.id,
                        content = combinedText,
                        toolBlocks = combinedBlocks,
                        sourceDbIds = prev.sourceDbIds + msg.sourceDbIds,
                        error = msg.error ?: prev.error,
                        tokenUsage = msg.tokenUsage ?: prev.tokenUsage,
                        completedAt = msg.completedAt ?: prev.completedAt,
                    )
                } else {
                    merged.add(msg)
                }
            }
            merged
        }
    }

    private data class ToolResultData(val output: String, val success: Boolean, val durationMs: Long = 0L)

    private fun MessageEntity.toLLMMessage(): LLMMessage =
        ChatHistoryRebuild.toLLMMessage(this, mediaStore.mediaBaseDir, ::visionPlaceholderFor)

    private fun extractPartialStringValue(key: String, json: String): String? {
        val patterns = listOf("\"$key\": \"", "\"$key\":\"")
        for (p in patterns) {
            val at = json.indexOf(p)
            if (at < 0) continue
            val after = json.substring(at + p.length)
            return unescapePartialJsonString(findUnescapedEnd(after))
        }
        return null
    }

    private fun findUnescapedEnd(s: String): String {
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == '"') return s.substring(0, i)
            i++
        }
        return s
    }

    private fun unescapePartialJsonString(s: String): String =
        s.replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\\\", "\\")

    private fun friendlyToolTitle(toolName: String): String = when (toolName) {
        "shell_execute" -> "Execute Shell"
        "file_read" -> "Read File"
        "file_write" -> "Write File"
        "file_edit" -> "Edit File"
        "browser_use" -> "Browse Web"
        "read_image" -> "Read Image"
        "memory_write" -> "Write Memory"
        "memory_get" -> "Read Memory"
        "memory_fact" -> "Remember Fact"
        com.openminis.app.agent.userask.UserAskPromptText.TOOL_NAME -> "Question for you"
        "web_search" -> "Search Web"
        else -> toolName
            .split('_')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
    }

    private fun parseToolParams(argsJson: String): Map<String, Any?> {
        if (argsJson.isBlank()) return emptyMap()
        return try {
            val obj = JSONObject(argsJson)
            val out = HashMap<String, Any?>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.get(k)
                out[k] = if (v == JSONObject.NULL) null else v
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

internal data class WarmUpTrim(val kept: List<LLMMessage>, val decided: Boolean)

internal fun warmUpBudget(window: Int?, compactThreshold: Int?, ratio: Double, fixedTokens: Int): Int? {
    val w = window?.takeIf { it > 0 } ?: return null
    val threshold = compactThreshold ?: return null
    if (fixedTokens <= 0) return null
    val line = if (threshold > 0) threshold else w
    return (line / ratio).toInt() - fixedTokens
}

internal data class SpeedEligibility(val fast: Boolean, val ultrafast: Boolean)

internal object ChatPartsJson {
    fun escapeJson(text: String): String {
        val sb = StringBuilder("\"")
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) sb.append("\\u%04x".format(c.code))
                    else sb.append(c)
                }
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    fun buildUserPartsJson(
        text: String,
        mediaRefPartsJson: List<String>,
        attachedFilesXml: String? = null,
        bodyPartsJson: List<String>? = null,
    ): String {
        val parts = mutableListOf<String>()
        if (bodyPartsJson != null) {
            parts.addAll(bodyPartsJson)
        } else if (text.isNotEmpty() || mediaRefPartsJson.isEmpty()) {
            parts.add("""{"type":"text","value":${escapeJson(text)}}""")
        }
        parts.addAll(mediaRefPartsJson)
        attachedFilesXml?.let { parts.add("""{"type":"text","value":${escapeJson(it)}}""") }
        return parts.joinToString(prefix = "[", postfix = "]", separator = ",")
    }

    fun buildMediaRefPartJson(
        ref: com.openminis.app.data.model.MediaRef,
        linuxPath: String? = null,
    ): String {
        val value = JSONObject()
            .put("id", ref.id)
            .put("relativePath", ref.relativePath)
            .put("mimeType", ref.mimeType)
        if (ref.originalFileName != null) value.put("originalFileName", ref.originalFileName)
        if (linuxPath != null) value.put("linuxPath", linuxPath)
        return JSONObject().put("type", "mediaRef").put("value", value).toString()
    }

    fun toolResultPartsJson(
        results: List<AgentContentPart.ToolResult>,
        durationsMs: Map<String, Long>,
    ): String {
        val partsJson = buildString {
            append("[")
            results.forEachIndexed { index, result ->
                if (index > 0) append(",")
                val snapshotText = escapeJson(result.content.lines().takeLast(30).joinToString("\n"))
                append("""{"type":"toolResult","value":{"toolUseId":${escapeJson(result.id)},"name":${escapeJson(result.name)},"output":${escapeJson(result.content)},"success":${!result.isError},"snapshot":{"type":"text","text":$snapshotText${ToolDurationWire.snapshotField(durationsMs[result.id])}}}}""")
            }
            append("]")
        }
        return partsJson
    }
}

internal object ChatHistoryRebuild {
    private const val TAG = ChatViewModel.TAG

    fun toLLMMessage(
        entity: MessageEntity,
        mediaBaseDir: java.io.File,
        visionPlaceholderFor: (String?) -> String?,
    ): LLMMessage {
        with(entity) {
            val r = if (role == "user") LLMMessage.Role.USER else LLMMessage.Role.ASSISTANT
            val contentParts = mutableListOf<AgentContentPart>()
            val imageParts = mutableListOf<LLMMessage.ImagePart>()
            var textContent = ""

            try {
                val array = org.json.JSONArray(partsJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    when (obj.optString("type")) {
                        "text" -> {
                            val value = obj.optString("value", "")
                            if (value.contains("<user-attached-files>")) {
                                contentParts.add(AgentContentPart.Text(value))
                            } else {
                                textContent += value
                                contentParts.add(AgentContentPart.Text(value))
                            }
                        }
                        "toolUse" -> {
                            val v = obj.getJSONObject("value")
                            val inputStr = v.optString("input", "{}")
                            val inputJson = try {
                                JSONObject(inputStr)
                            } catch (_: Exception) {
                                JSONObject()
                            }
                            contentParts.add(AgentContentPart.ToolUse(
                                id = v.optString("toolUseId", ""),
                                name = v.optString("name", ""),
                                input = inputJson,
                                thoughtSignature = v.optString("thoughtSignature", "").ifEmpty { null },
                            ))
                        }
                        "toolResult" -> {
                            val v = obj.getJSONObject("value")
                            contentParts.add(AgentContentPart.ToolResult(
                                id = v.optString("toolUseId", ""),
                                name = v.optString("name", ""),
                                content = v.optString("output", ""),
                                isError = !v.optBoolean("success", true),
                            ))
                        }
                        "mediaRef" -> {
                            val v = obj.optJSONObject("value") ?: continue
                            val rel = v.optString("relativePath", "")
                            if (rel.isEmpty()) continue
                            val mime = v.optString("mimeType", "image/jpeg")
                            if (PastedMedia.isPastedRef(mime, v.optString("originalFileName", null))) {
                                val pf = java.io.File(mediaBaseDir, rel)
                                val body = try {
                                    if (pf.exists()) pf.readText(Charsets.UTF_8) else null
                                } catch (e: Exception) {
                                    AppLogger.warning(TAG, "[Paste] restore failed for $rel: ${e.message}")
                                    null
                                }
                                val resolved = body ?: PastedMedia.MISSING_PLACEHOLDER
                                if (body == null) {
                                    AppLogger.warning(
                                        TAG,
                                        "[Paste] missing pasted file for $rel — substituting placeholder",
                                    )
                                }
                                textContent += resolved
                                contentParts.add(AgentContentPart.Text(resolved))
                                continue
                            }
                            if (!mime.startsWith("image/")) continue
                            val file = java.io.File(mediaBaseDir, rel)
                            if (!file.exists()) continue
                            val original = try { file.readBytes() } catch (_: Exception) { continue }
                            val restoredPath = v.optString("linuxPath", "").ifEmpty { null }
                            val downscaled = ImageBudget.downscaleForModel(file, original)
                            val bytes = downscaled?.bytes ?: original
                            val sendMime = downscaled?.mimeType ?: mime
                            val restoredPlaceholder = visionPlaceholderFor(restoredPath)
                            imageParts.add(LLMMessage.ImagePart(bytes, sendMime, linuxPath = restoredPath, noVisionPlaceholder = restoredPlaceholder))
                            contentParts.add(AgentContentPart.ImageData(bytes, sendMime, linuxPath = restoredPath, noVisionPlaceholder = restoredPlaceholder))
                            downscaled?.let { contentParts.add(AgentContentPart.Text(ImageBudget.downscaleNote(it, restoredPath))) }
                        }
                    }
                }
            } catch (_: Exception) {
                textContent = partsJson
                contentParts.add(AgentContentPart.Text(partsJson))
            }

            return LLMMessage(
                role = r,
                content = textContent,
                imageParts = imageParts,
                contentParts = contentParts,
                dbMessageId = id,
                reasoningContent = reasoningContent,
            )
        }
    }
}

internal data class PastedParts(
    val partsJson: List<String>,
    val modelText: String,
    val uiNames: List<String>,
    val uiUris: List<Uri>,
    val consumedIds: Set<Int>,
)

internal data class PreparedAttachments(
    val imageParts: List<LLMMessage.ImagePart>,
    val imageUris: List<Uri>,
    val attachmentNames: List<String>,
    val mediaRefPartsJson: List<String>,
    val imageUploadPaths: List<String>,
    val attachedFilesXml: String?,
    val nonImageUris: List<Uri>,
    val imageModelNotes: List<String?> = emptyList(),
)

internal object ChatAttachmentIngest {
    private const val TAG = ChatViewModel.TAG

    fun uniqueUploadFileName(dir: java.io.File, original: String): String {
        val raw = original.substringAfterLast('/').substringAfterLast('\\').ifBlank { "image.jpg" }
        val sanitized = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (!java.io.File(dir, sanitized).exists()) return sanitized
        val dot = sanitized.lastIndexOf('.')
        val base = if (dot > 0) sanitized.substring(0, dot) else sanitized
        val ext = if (dot > 0) sanitized.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "${base}_$n$ext"
            if (!java.io.File(dir, candidate).exists()) return candidate
            n++
        }
    }

    class IngestedImage(
        val ref: com.openminis.app.data.model.MediaRef,
        val imagePart: LLMMessage.ImagePart,
        val modelNote: String?,
        val linuxPath: String?,
        val mediaRefPartJson: String,
    )

    fun ingestImage(
        rawBytes: ByteArray,
        mimeType: String,
        fileName: String,
        sessionId: String,
        mediaStore: com.openminis.app.data.storage.MediaStore,
        uploadsHostDir: java.io.File,
    ): IngestedImage? {
                val ref = try {
                    mediaStore.saveMedia(
                        data = rawBytes,
                        mimeType = mimeType,
                        sessionId = sessionId,
                        originalFileName = fileName,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to persist image attachment ${fileName}", e)
                    return null
                }
                val downscaled = ImageBudget.downscaleForModel(rawBytes)
                val inferenceBytes = downscaled?.bytes ?: rawBytes

                val safeName = uniqueUploadFileName(uploadsHostDir, fileName)
                val dest = java.io.File(uploadsHostDir, safeName)
                val uploadOk = try { dest.writeBytes(rawBytes); true } catch (e: Exception) {
                    Log.w(TAG, "uploads write failed for ${fileName}: ${e.message}")
                    false
                }
                val linuxPath = if (uploadOk) "/var/minis/attachments/uploads/$safeName" else null

                val inferenceMime = ImageBudget.sniffFormat(inferenceBytes).mimeType ?: mimeType
                return IngestedImage(
                    ref = ref,
                    imagePart = LLMMessage.ImagePart(inferenceBytes, inferenceMime, linuxPath = linuxPath),
                    modelNote = downscaled?.let { ImageBudget.downscaleNote(it, linuxPath) },
                    linuxPath = linuxPath,
                    mediaRefPartJson = ChatPartsJson.buildMediaRefPartJson(ref, linuxPath = linuxPath),
                )
    }

    fun addModelImage(
        parts: MutableList<AgentContentPart>,
        part: LLMMessage.ImagePart,
        path: String?,
        note: String?,
        visionPlaceholderFor: (String?) -> String?,
    ) = with(parts) {
        if (path != null) add(AgentContentPart.Text("[attached image: $path]"))
        add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path, noVisionPlaceholder = visionPlaceholderFor(path)))
        if (note != null) add(AgentContentPart.Text(note))
    }

    fun liveUserTurn(
        modelBody: String,
        imageParts: List<LLMMessage.ImagePart>,
        imageUploadPaths: List<String>,
        imageModelNotes: List<String?>,
        attachedFilesXml: String?,
        dbMessageId: String,
        visionPlaceholderFor: (String?) -> String?,
    ): LLMMessage {
            val userContentParts = mutableListOf<AgentContentPart>()
            if (modelBody.isNotEmpty()) userContentParts.add(AgentContentPart.Text(modelBody))
            imageParts.forEachIndexed { idx, part ->
                addModelImage(userContentParts, part, imageUploadPaths.getOrNull(idx), imageModelNotes.getOrNull(idx), visionPlaceholderFor)
            }
            attachedFilesXml?.let { userContentParts.add(AgentContentPart.Text(it)) }

            return LLMMessage(
                role = LLMMessage.Role.USER,
                content = modelBody,
                imageParts = imageParts,
                contentParts = userContentParts,
                dbMessageId = dbMessageId,
            )
    }

    fun prepare(
        attachments: List<InputAttachment>,
        sessionId: String,
        filesDir: java.io.File,
        mediaStore: com.openminis.app.data.storage.MediaStore,
        openStream: (InputAttachment) -> java.io.InputStream?,
        clockMs: () -> Long,
        onBudgetMutated: (ImageBudget.BudgetResult) -> Unit,
        fileUri: (java.io.File) -> Uri,
    ): PreparedAttachments {
        val imageParts = mutableListOf<LLMMessage.ImagePart>()
        val imageUris = mutableListOf<Uri>()
        val imageNames = mutableListOf<String>()
        val nonImageNames = mutableListOf<String>()
        val nonImageUris = mutableListOf<Uri>()
        val imageMediaRefPartsJson = mutableListOf<String>()
        val nonImageMediaRefPartsJson = mutableListOf<String>()
        val imageUploadPaths = mutableListOf<String>()
        val imageModelNotes = mutableListOf<String?>()
        val uploadsHostDir = java.io.File(
            filesDir,
            "minis-sessions/$sessionId/attachments/uploads",
        ).apply { mkdirs() }
        data class UploadMeta(val linuxPath: String, val size: Long, val modifiedIso: String)
        val metas = mutableListOf<UploadMeta>()
        val nowMs = clockMs()
        val isoFormatter = java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            java.util.Locale.US,
        ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val nowStr = isoFormatter.format(java.util.Date(nowMs))

        for (attachment in attachments) {
            if (attachment.isImage) {
                val rawBytes = try {
                    openStream(attachment)?.use { it.readBytes() }
                } catch (e: Exception) {
                    Log.w(TAG, "image read failed for ${attachment.fileName}: ${e.message}")
                    null
                } ?: continue
                val ingested = ChatAttachmentIngest.ingestImage(
                    rawBytes, attachment.mimeType, attachment.fileName, sessionId, mediaStore, uploadsHostDir,
                ) ?: continue
                val linuxPath = ingested.linuxPath
                if (linuxPath != null) {
                    imageUploadPaths.add(linuxPath)
                    metas.add(UploadMeta(linuxPath = linuxPath, size = rawBytes.size.toLong(), modifiedIso = nowStr))
                }
                imageParts.add(ingested.imagePart)
                imageModelNotes.add(ingested.modelNote)
                val savedFile = java.io.File(mediaStore.mediaBaseDir, ingested.ref.relativePath)
                imageUris.add(fileUri(savedFile))
                imageNames.add(attachment.fileName)
                imageMediaRefPartsJson.add(ingested.mediaRefPartJson)
                continue
            }

            nonImageNames.add(attachment.fileName)
            val safeName = uniqueUploadFileName(uploadsHostDir, attachment.fileName)
            val dest = java.io.File(uploadsHostDir, safeName)
            val uploadOk = try {
                openStream(attachment)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } != null
            } catch (e: Exception) {
                Log.w(TAG, "non-image upload write failed for ${attachment.fileName}: ${e.message}")
                runCatching { dest.delete() }
                false
            }
            if (!uploadOk) continue

            val ref = try {
                dest.inputStream().use { input ->
                    mediaStore.saveMediaStreamed(
                        source = input,
                        mimeType = attachment.mimeType,
                        sessionId = sessionId,
                        originalFileName = attachment.fileName,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist non-image attachment ${attachment.fileName}", e)
                null
            }
            if (ref != null) {
                nonImageMediaRefPartsJson.add(ChatPartsJson.buildMediaRefPartJson(ref))
                nonImageUris.add(fileUri(java.io.File(mediaStore.mediaBaseDir, ref.relativePath)))
            }

            val linuxPath = "/var/minis/attachments/uploads/$safeName"
            metas.add(UploadMeta(linuxPath = linuxPath, size = dest.length(), modifiedIso = nowStr))
        }

        if (imageParts.isNotEmpty()) {
            val budgetResult = ImageBudget.applyMessageBudget(imageParts.map { it.data })
            val newImageParts = budgetResult.keptBytes.mapIndexed { idx, kept ->
                val orig = imageParts[idx]
                if (kept === orig.data) orig
                else LLMMessage.ImagePart(kept, "image/jpeg", linuxPath = orig.linuxPath)
            }
            val newSize = newImageParts.size
            imageParts.clear()
            imageParts.addAll(newImageParts)
            while (imageUris.size > newSize) imageUris.removeAt(imageUris.size - 1)
            while (imageNames.size > newSize) imageNames.removeAt(imageNames.size - 1)
            while (imageMediaRefPartsJson.size > newSize) imageMediaRefPartsJson.removeAt(imageMediaRefPartsJson.size - 1)
            while (imageUploadPaths.size > newSize) imageUploadPaths.removeAt(imageUploadPaths.size - 1)
            while (imageModelNotes.size > newSize) imageModelNotes.removeAt(imageModelNotes.size - 1)
            if (budgetResult.mutated) {
                onBudgetMutated(budgetResult)
            }
        }

        val xml = if (metas.isEmpty()) null else buildString {
            append("<user-attached-files>\n")
            for (m in metas) {
                val urlPath = m.linuxPath.removePrefix("/var/minis/")
                append("  <file path=\"")
                append(m.linuxPath)
                append("\" url=\"minis://")
                append(urlPath)
                append("\" size=\"")
                append(m.size)
                append("\" modified=\"")
                append(m.modifiedIso)
                append("\" />\n")
            }
            append("</user-attached-files>")
        }

        return PreparedAttachments(
            imageParts = imageParts,
            imageUris = imageUris,
            attachmentNames = imageNames + nonImageNames,
            mediaRefPartsJson = imageMediaRefPartsJson + nonImageMediaRefPartsJson,
            imageUploadPaths = imageUploadPaths,
            imageModelNotes = imageModelNotes,
            attachedFilesXml = xml,
            nonImageUris = nonImageUris,
        )
    }

    fun pastedParts(
        text: String,
        buffer: () -> List<PastedText>,
        sessionId: String,
        mediaStore: com.openminis.app.data.storage.MediaStore,
        fileUri: (java.io.File) -> Uri,
    ): PastedParts? {
        val (chunks, consumed) = splitPastePlaceholders(text, buffer())
        if (consumed.isEmpty()) return null
        val byId = buffer().associateBy { it.id }

        val parts = mutableListOf<String>()
        val model = StringBuilder()
        val names = mutableListOf<String>()
        val uris = mutableListOf<Uri>()
        for (chunk in chunks) {
            when (chunk) {
                is PasteChunk.Text -> {
                    parts.add("""{"type":"text","value":${ChatPartsJson.escapeJson(chunk.value)}}""")
                    model.append(chunk.value)
                }
                is PasteChunk.Pasted -> {
                    val entry = byId[chunk.id] ?: continue
                    val ref = try {
                        mediaStore.saveMedia(
                            data = entry.text.toByteArray(Charsets.UTF_8),
                            mimeType = PastedMedia.MIME,
                            sessionId = sessionId,
                            originalFileName = PastedMedia.fileNameFor(chunk.id),
                        )
                    } catch (e: Exception) {
                        AppLogger.warning(
                            TAG,
                            "[Paste] saveMedia failed for #${chunk.id}, inlining: ${e.message}",
                        )
                        parts.add("""{"type":"text","value":${ChatPartsJson.escapeJson(entry.text)}}""")
                        model.append(entry.text)
                        continue
                    }
                    parts.add(ChatPartsJson.buildMediaRefPartJson(ref))
                    model.append(entry.text)
                    names.add(ref.originalFileName ?: PastedMedia.fileNameFor(chunk.id))
                    uris.add(fileUri(java.io.File(mediaStore.mediaBaseDir, ref.relativePath)))
                }
            }
        }
        AppLogger.info(
            TAG,
            "[Paste] ${consumed.size} placeholder(s) -> mediaRef: " +
                "${text.length} chars in bubble, ${model.length} chars to model",
        )
        return PastedParts(parts, model.toString(), names, uris, consumed)
    }

    class QueuedTurn(val parts: MutableList<AgentContentPart>, val text: String)

    fun queuedTurn(texts: List<String>, prepared: PreparedAttachments, visionPlaceholderFor: (String?) -> String?): QueuedTurn {
        val combinedParts = mutableListOf<AgentContentPart>()
        val combinedText = StringBuilder()
        for (text in texts) {
            if (text.isNotEmpty()) {
                if (combinedText.isNotEmpty()) combinedText.append("\n\n")
                combinedText.append(text)
                combinedParts.add(AgentContentPart.Text(text))
            }
        }
        prepared.imageParts.forEachIndexed { idx, part ->
            addModelImage(combinedParts, part, prepared.imageUploadPaths.getOrNull(idx), prepared.imageModelNotes.getOrNull(idx), visionPlaceholderFor)
        }
        prepared.attachedFilesXml?.let { combinedParts.add(AgentContentPart.Text(it)) }
        return QueuedTurn(combinedParts, combinedText.toString())
    }

    fun queuedHistoryMessage(
        userText: String,
        pastedModelText: String?,
        imageParts: List<LLMMessage.ImagePart>,
        combinedParts: List<AgentContentPart>,
        interruptReminder: String?,
        dbMessageId: String?,
    ): LLMMessage {
        val body = pastedModelText?.let { modelText ->
                    val bodyCount = combinedParts.takeWhile { it is AgentContentPart.Text }.size
                    listOf(AgentContentPart.Text(modelText)) + combinedParts.drop(bodyCount)
        } ?: combinedParts
        return LLMMessage(
                role = LLMMessage.Role.USER,
                content = pastedModelText ?: userText,
                imageParts = imageParts,
                contentParts = if (interruptReminder == null) body else body + AgentContentPart.Text(interruptReminder),
                dbMessageId = dbMessageId,
            )
    }
}

internal object RequestContextBuilder {
    private const val TAG = ChatViewModel.TAG

    fun isRealUserText(msg: LLMMessage): Boolean {
        if (msg.role != LLMMessage.Role.USER) return false
        if (msg.contentParts.any { it is AgentContentPart.ToolResult }) return false
        val text = msg.content.ifBlank {
            msg.contentParts.filterIsInstance<AgentContentPart.Text>().joinToString("\n") { it.text }
        }.trim()
        if (text.isEmpty()) return false
        return !text.startsWith("<system-reminder>") && !text.startsWith("<agent_callback")
    }

    data class Compaction(
        val summary: String?,
        val marker: CompactMarkerEntity?,
        val cachedWarmUpDrop: (markerId: String) -> Int? = { null },
        val recordWarmUpDrop: (markerId: String, drop: Int) -> Unit = { _, _ -> },
        val warmUpBudget: () -> Int? = { null },
    )

    data class Input(
        val history: List<LLMMessage>,
        val compaction: Compaction? = null,
        val keepRecentUserTurns: Int = 3,
        val sessionId: () -> String = { "" },
        val contextWindowTokens: () -> Int? = { null },
        val resolveAttachmentsRoot: () -> java.io.File? = { null },
        val onRequestBudget: (ImageBudget.RequestBudgetPlan) -> Unit = {},
        val trim: (List<LLMMessage>) -> List<LLMMessage> = { it },
    )

    enum class CompactionMode {
        NONE, V2_ANCHOR_UNRESOLVED, V2_PREPENDED_TO_TEXT, V2_NEW_LEADING_TEXT, V2_CONTENT_ONLY, V2_STANDALONE,
        V2_APPENDED_AFTER_TOOL_RESULTS,
        V1_HEAD, V1_UNRESOLVED,
    }

    enum class WarmUpDecision { CACHED, DECIDED, UNDECIDED }

    class CompactionTrace {
        var mode: CompactionMode? = null
        var anchorIdx: Int? = null
        var priorIdx: Int? = null
        var walkBackStop: String? = null
        var warmUp: WarmUpDecision? = null
        var preAnchorSent: Int? = null
        var warmUpSent: Int? = null
        var postAnchorCount: Int? = null
        var injectedAt: Int? = null
        override fun toString() =
            "mode=$mode anchorIdx=$anchorIdx priorIdx=$priorIdx walkBackStop=$walkBackStop warmUp=$warmUp " +
                "preAnchorSent=$preAnchorSent warmUpSent=$warmUpSent postAnchor=$postAnchorCount injectedAt=$injectedAt"
    }

    class OrphanTrace {
        var orphanedResults: List<String> = emptyList()
        var orphanedUses: List<String> = emptyList()
    }

    data class PayloadFinding(val msgIdx: Int, val kind: String, val id: String?, val finding: PayloadSizeAudit.Finding)

    data class Diagnostics(
        val compaction: CompactionTrace,
        val orphans: OrphanTrace,
        val budget: ImageBudget.RequestBudgetPlan?,
        val audit: List<PayloadFinding>,
    )

    data class RequestContext(
        val outbound: List<LLMMessage>,
        val messages: List<LLMMessage>,
        val diagnostics: Diagnostics,
    )

    fun build(input: Input, onOutbound: (List<LLMMessage>) -> Unit = {}): RequestContext {
        val compactionTrace = CompactionTrace()
        val orphanTrace = OrphanTrace()
        val outbound = outbound(input, compactionTrace, orphanTrace)
        onOutbound(outbound)
        var plan: ImageBudget.RequestBudgetPlan? = null
        val budgeted = applyRequestBudget(outbound, input.resolveAttachmentsRoot) {
            plan = it
            input.onRequestBudget(it)
        }
        val findings = mutableListOf<PayloadFinding>()
        val messages = auditOutgoingPayload(budgeted, input.contextWindowTokens, findings)
        return RequestContext(outbound, messages, Diagnostics(compactionTrace, orphanTrace, plan, findings))
    }

    fun outbound(input: Input, compactionTrace: CompactionTrace? = null, orphanTrace: OrphanTrace? = null): List<LLMMessage> =
        dropOrphanedToolParts(input.trim(compacted(input.history, input.compaction, input.keepRecentUserTurns, compactionTrace)), input.sessionId, orphanTrace)

    fun applyRequestBudget(
        messages: List<LLMMessage>,
        resolveAttachmentsRoot: () -> java.io.File?,
        onPlan: (ImageBudget.RequestBudgetPlan) -> Unit,
    ): List<LLMMessage> {
        val applied = ImageBudget.applyRequestBudget(messages, resolveAttachmentsRoot)
        val plan = applied.plan ?: return messages
        onPlan(plan)
        return applied.messages
    }

    fun base64Size(rawBytes: Int): Int = if (rawBytes <= 0) 0 else (rawBytes + 2) / 3 * 4

    private data class Quad(val a: Int, val b: Int, val c: String, val d: String?)


    fun simulatedSummaryGain(
        history: List<LLMMessage>,
        current: Compaction?,
        keepRecentUserTurns: Int,
        summaryTokens: Int,
    ): Int? {
        val anchorId = history.lastOrNull()?.dbMessageId ?: return null
        val summary = "摘".repeat(summaryTokens)
        val marker = CompactMarkerEntity(
            id = "simulated", sessionId = "", summary = summary, firstKeptSortOrder = 0,
            compactedCount = 0, createdAt = 0, lastCompactedMessageId = anchorId, version = 2,
        )
        val now = compacted(history, current, keepRecentUserTurns)
        val after = compacted(history, Compaction(summary = summary, marker = marker), keepRecentUserTurns)
        return ContextSizeMeter.estimateTokens(now) - ContextSizeMeter.estimateTokens(after)
    }

    fun compacted(
        history: List<LLMMessage>,
        compaction: Compaction?,
        keepRecentUserTurns: Int,
        trace: CompactionTrace? = null,
    ): List<LLMMessage> {
        val summary = compaction?.summary
        val marker = compaction?.marker
        if (compaction == null || summary.isNullOrBlank() || marker == null) return history.toList().also { trace?.mode = CompactionMode.NONE }

        val summaryWrappedText = "<context-summary>\n" +
            "The following is a summary of the earlier conversation that was compacted to save context space.\n" +
            "Treat it as background context only. The user's most recent message (below or in the next turn) takes precedence — if it changes the task, the goal, or any numbers/scope, follow the new instruction and do not resume the old plan from this summary. Do not re-run discovery (reading memory, scanning skills, re-reading files) unless the new instruction requires it.\n\n" +
            summary +
            "\n</context-summary>"

        if (marker.version >= 2) {
            val anchorId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
            val anchorIdx = anchorId?.let { id ->
                history.indexOfLast { it.dbMessageId == id }
            } ?: -1
            if (anchorIdx < 0) {
                Log.w(TAG, "[Compact] effectiveAgentHistory v2: anchorId=${anchorId?.take(8) ?: "nil"} not in agentHistory(size=${history.size}) — degrading to full history (no summary)")
                trace?.mode = CompactionMode.V2_ANCHOR_UNRESOLVED
                return history.toList()
            }

            val keepN = keepRecentUserTurns
            val preAnchorCap = 100
            val walkBack = walkBackUserTurnsBounded(
                history = history,
                anchorIdx = anchorIdx,
                maxUserTextTurns = keepN,
                maxMessages = preAnchorCap,
            )
            val priorIdxResolved: Int? = walkBack.priorIdx
            val priorIdx = walkBack.priorIdx ?: (anchorIdx + 1)
            trace?.apply { this.anchorIdx = anchorIdx; this.priorIdx = priorIdx; walkBackStop = walkBack.stopReason }
            if (walkBack.stopReason != "userTextTargetMet") {
                AppLogger.info(TAG, "[CompactDiag] eAH v2 walkBack stopped: reason=${walkBack.stopReason} priorIdx=$priorIdx userTextTurnsFound=${walkBack.userTextTurnsFound} preAnchorMsgs=${walkBack.messageCount}")
            }

            val preAnchorRaw: List<LLMMessage> =
                if (priorIdx <= anchorIdx) history.subList(priorIdx, anchorIdx + 1).toList()
                else {
                    val request = (anchorIdx downTo 0).firstOrNull { i ->
                        val m = history[i]
                        m.role == LLMMessage.Role.USER && isRealUserText(m)
                    }
                    if (request != null) {
                        AppLogger.info(TAG, "[CompactDiag] eAH v2 pinned the current request (idx=$request) — walk-back hit the cap")
                        listOf(history[request])
                    } else {
                        emptyList()
                    }
                }

            val droppedToolIds = mutableSetOf<String>()
            var droppedToolResultCount = 0
            for (msg in preAnchorRaw) {
                for (part in msg.contentParts) {
                    if (part is AgentContentPart.ToolResult && part.content.length > 1000) {
                        droppedToolIds.add(part.id)
                        droppedToolResultCount += 1
                    }
                }
            }

            val preAnchorPruned: MutableList<LLMMessage> = ArrayList(preAnchorRaw.size)
            for (msg in preAnchorRaw) {
                if (msg.contentParts.isEmpty()) {
                    preAnchorPruned.add(msg)
                    continue
                }
                val kept = msg.contentParts.filter { part ->
                    when (part) {
                        is AgentContentPart.ToolUse -> !droppedToolIds.contains(part.id)
                        is AgentContentPart.ToolResult -> !droppedToolIds.contains(part.id)
                        else -> true
                    }
                }
                if (kept.isEmpty()) continue
                preAnchorPruned.add(msg.copy(contentParts = kept))
            }

            if (droppedToolResultCount > 0) {
                AppLogger.info(TAG, "[CompactDiag] eAH v2 preAnchor prune: dropped $droppedToolResultCount toolResult(>1kc) + paired toolUse, ${preAnchorRaw.size - preAnchorPruned.size} messages emptied; pruned slice=${preAnchorPruned.size}")
            }

            while (preAnchorPruned.isNotEmpty() && preAnchorPruned.first().role != LLMMessage.Role.USER) {
                preAnchorPruned.removeAt(0)
            }

            val postAnchor = if (anchorIdx + 1 < history.size) {
                history.subList(anchorIdx + 1, history.size)
            } else {
                emptyList()
            }
            val warmUp = compaction.cachedWarmUpDrop(marker.id)?.let { drop ->
                trace?.warmUp = WarmUpDecision.CACHED
                preAnchorPruned.drop(minOf(drop, preAnchorPruned.size))
            } ?: trimWarmUpToFit(preAnchorPruned, postAnchor, summaryWrappedText, compaction.warmUpBudget).let { trim ->
                trace?.warmUp = if (trim.decided) WarmUpDecision.DECIDED else WarmUpDecision.UNDECIDED
                if (trim.decided) compaction.recordWarmUpDrop(marker.id, preAnchorPruned.size - trim.kept.size)
                trim.kept
            }
            trace?.apply { preAnchorSent = preAnchorPruned.size; warmUpSent = warmUp.size; postAnchorCount = postAnchor.size }
            val result = mutableListOf<LLMMessage>()
            result.addAll(warmUp)

            val preAnchorRawCount = maxOf(0, anchorIdx - priorIdx + 1)
            val priorIdxSource =
                if (priorIdxResolved == null) "fallback=empty(<$keepN user-text turns before anchor or cap hit)"
                else "userTextWalkBack(N=$keepN)"
            AppLogger.info(TAG, "[CompactDiag] eAH v2 slice: priorIdx=$priorIdx anchorIdx=$anchorIdx agentHistory.size=${history.size} → preAnchorRaw=$preAnchorRawCount preAnchorSent=${preAnchorPruned.size} postAnchor=${postAnchor.size} summaryChars=${summary.length} priorIdxSource=$priorIdxSource markerId=${marker.id.take(8)}")

            val firstUserOffset = postAnchor.indexOfFirst { it.role == LLMMessage.Role.USER }
            if (firstUserOffset >= 0) {
                if (firstUserOffset > 0) {
                    result.addAll(postAnchor.subList(0, firstUserOffset))
                }
                val target = postAnchor[firstUserOffset]
                val lastToolResultIdx = target.contentParts.indexOfLast { it is AgentContentPart.ToolResult }
                val isToolResultCarrier = lastToolResultIdx >= 0
                val injectedParts = if (target.contentParts.isEmpty()) {
                    target.contentParts
                } else if (isToolResultCarrier) {
                    target.contentParts.subList(0, lastToolResultIdx + 1) +
                        AgentContentPart.Text(summaryWrappedText) +
                        target.contentParts.subList(lastToolResultIdx + 1, target.contentParts.size)
                } else {
                    val firstTextIdx = target.contentParts.indexOfFirst { it is AgentContentPart.Text }
                    if (firstTextIdx < 0) {
                        listOf(AgentContentPart.Text(summaryWrappedText)) + target.contentParts
                    } else {
                        target.contentParts.mapIndexed { i, part ->
                            if (i != firstTextIdx || part !is AgentContentPart.Text) part
                            else AgentContentPart.Text(summaryWrappedText + "\n\n" + part.text)
                        }
                    }
                }
                val injected = target.copy(
                    content = if (!isToolResultCarrier) summaryWrappedText + "\n\n" + target.content
                    else if (target.content.isEmpty()) summaryWrappedText
                    else target.content + "\n\n" + summaryWrappedText,
                    contentParts = injectedParts,
                )
                trace?.apply {
                    injectedAt = result.size
                    mode = when {
                        target.contentParts.isEmpty() -> CompactionMode.V2_CONTENT_ONLY
                        isToolResultCarrier -> CompactionMode.V2_APPENDED_AFTER_TOOL_RESULTS
                        target.contentParts.none { it is AgentContentPart.Text } -> CompactionMode.V2_NEW_LEADING_TEXT
                        else -> CompactionMode.V2_PREPENDED_TO_TEXT
                    }
                }
                result.add(injected)
                if (firstUserOffset + 1 < postAnchor.size) {
                    result.addAll(postAnchor.subList(firstUserOffset + 1, postAnchor.size))
                }
            } else {
                result.addAll(postAnchor)
                trace?.apply { injectedAt = result.size; mode = CompactionMode.V2_STANDALONE }
                result.add(LLMMessage(role = LLMMessage.Role.USER, content = summaryWrappedText))
            }
            return result
        }

        val summaryHead = LLMMessage(role = LLMMessage.Role.USER, content = summaryWrappedText)
        val firstKeptId = (marker.firstKeptMessageId?.takeIf { it.isNotEmpty() })
            ?: (marker.boundaryMessageId?.takeIf { it.isNotEmpty() })

        if (firstKeptId != null) {
            val keepStart = history.indexOfFirst { it.dbMessageId == firstKeptId }
            if (keepStart >= 0) {
                trace?.apply { injectedAt = 0; mode = CompactionMode.V1_HEAD }
                return buildList(history.size - keepStart + 1) {
                    add(summaryHead)
                    addAll(history.subList(keepStart, history.size))
                }
            }
        } else {
            val lcmId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
            val lcmIdx = lcmId?.let { id ->
                history.indexOfLast { it.dbMessageId == id }
            } ?: -1
            val postCompactStart = lcmIdx + 1
            trace?.apply { injectedAt = 0; mode = CompactionMode.V1_HEAD }
            return buildList(history.size - postCompactStart + 1) {
                add(summaryHead)
                if (postCompactStart < history.size) {
                    addAll(history.subList(postCompactStart, history.size))
                }
            }
        }

        trace?.mode = CompactionMode.V1_UNRESOLVED
        Log.w(TAG, "[Compact] effectiveAgentHistory: marker ${marker.id.take(8)} unresolvable in agentHistory (size=${history.size}); returning full history")
        return history.toList()
    }

    fun dropOrphanedToolParts(history: List<LLMMessage>, sessionId: () -> String, trace: OrphanTrace? = null): List<LLMMessage> {
        val toolUseIds = HashSet<String>()
        val toolResultIds = HashSet<String>()
        for (msg in history) {
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.ToolUse -> toolUseIds.add(com.openminis.app.provider.ToolPairing.key(part.id))
                    is AgentContentPart.ToolResult -> toolResultIds.add(com.openminis.app.provider.ToolPairing.key(part.id))
                    else -> {}
                }
            }
        }
        val orphanedResults = toolResultIds - toolUseIds
        val orphanedUses = HashSet(toolUseIds - toolResultIds)

        val last = history.lastOrNull()
        if (last != null && last.role == LLMMessage.Role.ASSISTANT) {
            for (part in last.contentParts) {
                if (part is AgentContentPart.ToolUse) orphanedUses.remove(com.openminis.app.provider.ToolPairing.key(part.id))
            }
        }

        if (orphanedResults.isEmpty() && orphanedUses.isEmpty()) return history
        trace?.apply { this.orphanedResults = orphanedResults.sorted(); this.orphanedUses = orphanedUses.sorted() }

        AppLogger.warning(
            TAG,
            "[CompactDiag] orphan tool parts in OUTGOING history — repairing. " +
                "session=${sessionId()} " +
                "orphanedOutputs=${orphanedResults.size} [${orphanedResults.sorted().joinToString(",")}] " +
                "orphanedCalls=${orphanedUses.size} [${orphanedUses.sorted().joinToString(",")}] " +
                "orphanRoles=[${orphanRoleSummary(history, orphanedResults, orphanedUses)}] " +
                "historyCount=${history.size}",
        )

        val cleaned = ArrayList<LLMMessage>(history.size)
        for (msg in history) {
            val kept = msg.contentParts.filter { part ->
                if (part is AgentContentPart.ToolResult) !orphanedResults.contains(com.openminis.app.provider.ToolPairing.key(part.id)) else true
            }
            if (kept.isEmpty() && msg.contentParts.isNotEmpty()) continue
            cleaned.add(if (kept.size == msg.contentParts.size) msg else msg.copy(contentParts = kept))

            if (msg.role != LLMMessage.Role.ASSISTANT) continue
            val unanswered = kept.filterIsInstance<AgentContentPart.ToolUse>()
                .filter { orphanedUses.contains(com.openminis.app.provider.ToolPairing.key(it.id)) }
            if (unanswered.isNotEmpty()) {
                cleaned.add(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = "",
                        contentParts = unanswered.map {
                            AgentContentPart.ToolResult(
                                id = it.id,
                                name = it.name,
                                content = "Tool execution was interrupted by an unexpected error.",
                                isError = true,
                            )
                        },
                    ),
                )
            }
        }
        return cleaned
    }

    private fun orphanRoleSummary(
        history: List<LLMMessage>,
        orphanedResults: Set<String>,
        orphanedUses: Set<String>,
    ): String {
        val out = mutableListOf<String>()
        for (msg in history) {
            for (part in msg.contentParts) {
                val id = when (part) {
                    is AgentContentPart.ToolUse -> part.id.takeIf { com.openminis.app.provider.ToolPairing.key(it) in orphanedUses }
                    is AgentContentPart.ToolResult -> part.id.takeIf { com.openminis.app.provider.ToolPairing.key(it) in orphanedResults }
                    else -> null
                } ?: continue
                out += "${msg.role}:$id"
            }
        }
        return out.joinToString(",")
    }

    data class WalkBackResult(
        val priorIdx: Int?,
        val userTextTurnsFound: Int,
        val messageCount: Int,
        val stopReason: String,
    )

    fun walkBackUserTurnsBounded(
        history: List<LLMMessage>,
        anchorIdx: Int,
        maxUserTextTurns: Int,
        maxMessages: Int,
    ): WalkBackResult {
        if (anchorIdx < 0 || anchorIdx >= history.size) {
            return WalkBackResult(null, 0, 0, "invalidAnchor")
        }
        var acceptedPriorIdx: Int? = null
        var acceptedUserTextTurns = 0
        var acceptedMessageCount = 0

        var i = anchorIdx
        while (i >= 0) {
            val msg = history[i]
            if (msg.role != LLMMessage.Role.USER) {
                i -= 1
                continue
            }
            if (msg.contentParts.any { it is AgentContentPart.ToolResult }) {
                i -= 1
                continue
            }
            val candidateMessageCount = anchorIdx - i + 1
            if (candidateMessageCount > maxMessages) {
                return WalkBackResult(
                    priorIdx = acceptedPriorIdx,
                    userTextTurnsFound = acceptedUserTextTurns,
                    messageCount = acceptedMessageCount,
                    stopReason = "messageCapWouldExceed",
                )
            }
            acceptedPriorIdx = i
            acceptedMessageCount = candidateMessageCount
            if (isRealUserText(msg)) {
                acceptedUserTextTurns += 1
                if (acceptedUserTextTurns >= maxUserTextTurns) {
                    return WalkBackResult(
                        priorIdx = acceptedPriorIdx,
                        userTextTurnsFound = acceptedUserTextTurns,
                        messageCount = acceptedMessageCount,
                        stopReason = "userTextTargetMet",
                    )
                }
            }
            i -= 1
        }
        return WalkBackResult(
            priorIdx = acceptedPriorIdx,
            userTextTurnsFound = acceptedUserTextTurns,
            messageCount = acceptedMessageCount,
            stopReason = "reachedStart",
        )
    }

    fun trimWarmUpToFit(warmUp: List<LLMMessage>, rest: List<LLMMessage>, summaryText: String, warmUpBudget: () -> Int?): WarmUpTrim {
        if (warmUp.isEmpty()) return WarmUpTrim(warmUp, decided = true)
        val budget = warmUpBudget() ?: return WarmUpTrim(warmUp, decided = false)
        val restTokens = ContextSizeMeter.estimateTokens(rest) + ContextSizeMeter.estimateTokens(summaryText)
        if (ContextSizeMeter.estimateTokens(warmUp) + restTokens < budget) return WarmUpTrim(warmUp, decided = true)
        val drop = ContextSizeMeter.warmUpDrop(
            sizes = warmUp.map { ContextSizeMeter.estimateTokens(listOf(it)) },
            startsTurn = warmUp.map { m -> m.role == LLMMessage.Role.USER && m.contentParts.none { it is AgentContentPart.ToolResult } },
            restTokens = restTokens,
            budget = budget,
        )
        AppLogger.info(TAG, "[CtxMeter] warmup trimmed to fit: kept ${warmUp.size - drop}/${warmUp.size} message(s) (budget=$budget raw tokens)")
        return WarmUpTrim(warmUp.drop(drop), decided = true)
    }

    fun auditOutgoingPayload(messages: List<LLMMessage>, contextWindowTokens: () -> Int?, findings: MutableList<PayloadFinding>? = null): List<LLMMessage> {
        val window = contextWindowTokens() ?: 0
        for ((msgIdx, msg) in messages.withIndex()) {
            for (part in msg.contentParts) {
                val (bytes, estTokens, kind, id) = when (part) {
                    is AgentContentPart.ImageData ->
                        Quad(base64Size(part.data.size), BPETokenizer.countImageTokens(part.data), "image", null)
                    is AgentContentPart.ToolResult -> {
                        val imgBytes = part.imageData?.size ?: 0
                        Quad(
                            part.content.length + base64Size(imgBytes),
                            BPETokenizer.countTokens(part.content) +
                                (part.imageData?.let { BPETokenizer.countImageTokens(it) } ?: 0),
                            "tool_result",
                            part.id,
                        )
                    }
                    is AgentContentPart.Text ->
                        Quad(part.text.length, BPETokenizer.countTokens(part.text), "text", null)
                    is AgentContentPart.ToolUse -> {
                        val raw = part.input.toString()
                        Quad(raw.length, BPETokenizer.countTokens(raw), "tool_use", part.id)
                    }
                }
                val finding = PayloadSizeAudit.audit(bytes, estTokens, window)
                if (!finding.suspicious && !finding.dangerous) continue
                findings?.add(PayloadFinding(msgIdx, kind, id, finding))
                AppLogger.warning(
                    TAG,
                    "[PayloadAudit] GH#352 oversized part msgIdx=$msgIdx role=${msg.role} " +
                        "kind=$kind id=${id ?: "-"} bytes=${finding.bytes} " +
                        "estTokens=${finding.estimatedTokens} bytesPerToken=${finding.bytesPerToken} " +
                        "window=$window suspicious=${finding.suspicious} dangerous=${finding.dangerous}" +
                        if (finding.dangerous) {
                            " — this part alone could exhaust the window if measured as text; " +
                                "if the provider now 400s on context length the self-heal path " +
                                "([T-android-context-overflow-selfheal]) will offload it."
                        } else "",
                )
            }
        }
        return messages
    }
}

internal object BrowserArtifacts {
    fun toolResult(
        result: com.openminis.app.browser.BrowserActionResult,
        toolTitle: String,
        decodeBase64: (String) -> ByteArray,
        clockMs: () -> Long,
        persist: (filename: String, data: ByteArray) -> String?,
    ): ToolExecutionResult {
        var output = result.text
        var persistentImagePath: String? = result.imageFilePath
        var inferenceBytes: ByteArray? = null

        val base64 = result.base64Image
        var linuxImagePath: String? = null
        if (base64 != null) {
            val raw = try {
                decodeBase64(base64)
            } catch (_: Exception) { null }
            if (raw != null) {
                inferenceBytes = ImageBudget.resizeJpegToMaxEdge(raw, 2000) ?: raw
                val filename = "screenshot_${clockMs() / 1000}.jpg"
                val persistPath = persist(filename, raw)
                if (persistPath != null) {
                    persistentImagePath = persistPath
                    linuxImagePath = "/var/minis/browser/$filename"
                    linuxPathToMinisURL(linuxImagePath)?.let {
                        output = "$output\nminis_url: $it"
                    }
                }
            }
        }

        val fetchData = result.fetchedFileData
        val fetchName = result.fetchedFileName
        if (fetchData != null && fetchName != null) {
            persist(fetchName, fetchData)
            linuxPathToMinisURL("/var/minis/browser/$fetchName")?.let {
                output = "$output\nminis_url: $it"
            }
        }

        return ToolExecutionResult(
            output = output,
            success = result.success,
            imageData = inferenceBytes,
            imageMimeType = if (inferenceBytes != null) "image/jpeg" else null,
            toolTitle = toolTitle,
            pageURL = result.pageURL,
            imageFilePath = persistentImagePath,
            imageLinuxPath = linuxImagePath,
        )
    }

    fun persist(sessionId: String, filesDir: () -> java.io.File, filename: String, data: ByteArray): String? {
        val sid = sessionId.takeIf { it.isNotEmpty() } ?: return null
        return try {
            val dir = java.io.File(filesDir(), "minis-sessions/$sid/browser").apply { mkdirs() }
            val file = java.io.File(dir, filename)
            file.writeBytes(data)
            file.absolutePath
        } catch (e: Exception) {
            android.util.Log.w("ChatViewModel", "persistBrowserArtifact failed: ${e.message}")
            null
        }
    }

    fun linuxPathToMinisURL(path: String): String? {
        val prefix = "/var/minis/"
        if (!path.startsWith(prefix)) return null
        val rest = path.removePrefix(prefix)
        val slash = rest.indexOf('/')
        if (slash < 0) return null
        val namespace = rest.substring(0, slash)
        val filename = rest.substring(slash + 1)
        val encoded = java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
        return "minis://$namespace/$encoded"
    }
}

internal object ToolResultWrap {
    fun part(id: String, name: String, content: String, result: ToolExecutionResult): AgentContentPart.ToolResult =
        AgentContentPart.ToolResult(
                            id = id,
                            name = name,
                            content = content,
                            isError = !result.success,
                            imageData = result.imageData,
                            imageMimeType = result.imageMimeType,
                            imageLinuxPath = result.imageLinuxPath,
                        )

    fun historyMessage(resultParts: List<AgentContentPart>, dbMessageId: String?): LLMMessage =
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = resultParts,
                dbMessageId = dbMessageId,
            )

    suspend fun persist(
        parts: List<AgentContentPart>,
        durationsMs: Map<String, Long>,
        sessionId: () -> String,
        append: suspend (sessionId: String, role: String, partsJson: String) -> String,
    ): String? {
        val results = parts.filterIsInstance<AgentContentPart.ToolResult>()
        if (results.isEmpty()) return null
        val partsJson = ChatPartsJson.toolResultPartsJson(results, durationsMs)
        return append(sessionId(), "user", partsJson)
    }
}
