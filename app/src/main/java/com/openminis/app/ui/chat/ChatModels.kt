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
import com.openminis.app.data.ContextPolicy
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.R
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.terminal.MinisOpenUrlBroker
import com.openminis.app.terminal.MinisUrlMarker
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.FileEditTool
import com.openminis.app.tools.FileReadTool
import com.openminis.app.tools.FileWriteTool
import com.openminis.app.tools.MemoryTools
import com.openminis.app.tools.ReadImageTool
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject

data class StreamingDelta(
    val content: String,
    val toolBlocks: List<AssistantBlock>,
    val isAwaitingModelResponse: Boolean,
)

data class ChatTokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheCreationTokens: Int,
    val cacheReadTokens: Int,
    val latestContextTokens: Int,
) {
    companion object {
        fun parse(json: String?): ChatTokenUsage? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val o = org.json.JSONObject(json)
                val u = ChatTokenUsage(
                    inputTokens = o.optInt("inputTokens", 0),
                    outputTokens = o.optInt("outputTokens", 0),
                    cacheCreationTokens = o.optInt("cacheCreationTokens", 0),
                    cacheReadTokens = o.optInt("cacheReadTokens", 0),
                    latestContextTokens = o.optInt("latestContextTokens", 0),
                )
                if (u.inputTokens == 0 && u.outputTokens == 0 &&
                    u.cacheReadTokens == 0 && u.cacheCreationTokens == 0 &&
                    u.latestContextTokens == 0
                ) null else u
            }.getOrNull()
        }
    }
}

data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val isStreaming: Boolean = false,
    val isAwaitingModelResponse: Boolean = false,
    val imageUris: List<Uri> = emptyList(),
    val attachmentNames: List<String> = emptyList(),
    val attachmentUris: List<Uri> = emptyList(),
    val toolBlocks: List<AssistantBlock> = emptyList(),
    val thinkingLevel: com.openminis.app.data.model.ThinkingLevel? = null,
    val error: String? = null,
    val isQueued: Boolean = false,
    val queuedPromptId: String? = null,
    val isCompactedHistory: Boolean = false,
    val sourceDbIds: List<String> = emptyList(),
    val tokenUsage: ChatTokenUsage? = null,
    val completedAt: Long? = null,
) {
    val isInternalBridge: Boolean
        get() = role == "assistant" && isInternalBridgeText(content)

    companion object {
        internal const val INTERNAL_BRIDGE_TEXT =
            "(Interrupted mid-task by a new user message. Decide based on the new " +
                "message and overall context whether the prior task should continue — do " +
                "not forget or abandon it unless the user explicitly says to stop, or the " +
                "new message makes clear it is no longer needed.)"

        internal const val SCHEDULED_RESUME_NUDGE_TEXT =
            "<system-reminder>The scheduled task above has been handled. If the task you were working on before it " +
                "arrived is not finished yet, continue it now from where you left off; if it is already complete, " +
                "reply briefly and stop.</system-reminder>"

        internal const val SCHEDULED_INSERT_BRIDGE_TEXT =
            "(Pausing briefly between tool calls: a scheduled task has just fired and is delivered next. " +
                "I will deal with it, then pick up the task I was working on where I left off.)"

        internal const val QUEUED_INTERRUPT_REMINDER_TEXT =
            "<system-reminder>This note is from the system, not something the user wrote. The user sent the " +
                "message above while you were in the middle of a task. Decide from that message and the " +
                "conversation so far whether the earlier task should continue; do not forget or abandon it unless " +
                "the user explicitly asks you to stop or the message makes clear it is no longer needed. Do not " +
                "repeat or mention this note.</system-reminder>"

        private val INTERNAL_BRIDGE_TEXTS = listOf(
            INTERNAL_BRIDGE_TEXT,
            SCHEDULED_INSERT_BRIDGE_TEXT,
            "(Interrupted mid-task to handle your new message. Will return to the prior task after.)",
        )

        fun isInternalBridgeText(text: String): Boolean {
            val trimmed = text.trim()
            return INTERNAL_BRIDGE_TEXTS.any { trimmed == it }
        }
    }
}

data class QueuedPrompt(
    val id: String,
    val text: String,
    val attachments: List<InputAttachment> = emptyList(),
    val origin: QueuedPromptOrigin = QueuedPromptOrigin.USER,
    val prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
    val scheduledTaskId: String? = null,
    val reminderSent: Boolean = false,
) {
    val isScheduledFire: Boolean get() = scheduledTaskId != null
}

enum class QueuedPromptOrigin { USER, PROGRAMMATIC }

enum class ToolBlockStatus {
    STREAMING, PENDING, RUNNING, SUCCESS, FAILED, CANCELLED, TIMEOUT
}

data class SlashCommand(
    val id: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val subtitle: String,
    val isSkill: Boolean = false,
    val isMcp: Boolean = false,
)

data class AssistantBlock(
    val id: String,
    val kind: String,
    val content: String = "",
    val toolStatus: ToolBlockStatus? = null,
    val toolTitle: String = "",
    val toolName: String = "",
    val toolArgs: String = "",
    val durationMs: Long = 0L,
    val startTimeMs: Long = 0L,
    val browserURL: String? = null,
    val imageFilePath: String? = null,
    val thoughtSignature: String? = null,
) {
    val isText: Boolean get() = kind == "text"
}
