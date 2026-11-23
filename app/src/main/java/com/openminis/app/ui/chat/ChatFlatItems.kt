package com.openminis.app.ui.chat


import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.automirrored.filled.Article
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.openminis.app.offload.OffloadPermissionManager
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.browser.BrowserSheet
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.components.MinisTextButton

internal sealed class FlatChatItem {
    abstract val key: String
    abstract val contentType: String

    class UserBubble(
        val message: ChatMessage,
        val precededByUser: Boolean = false,
    ) : FlatChatItem() {
        override val key = "user:${message.id}"
        override val contentType = "user"
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UserBubble) return false
            return message == other.message && precededByUser == other.precededByUser
        }
        override fun hashCode(): Int = message.hashCode() * 31 + precededByUser.hashCode()
    }

    data class AssistantHeader(val messageId: String) : FlatChatItem() {
        override val key = "header:$messageId"
        override val contentType = "header"
    }

    class ScheduledTaskCardItem(
        val message: ChatMessage,
        val marker: com.openminis.app.scheduled.ScheduledTaskMarker,
    ) : FlatChatItem() {
        override val key = "scheduled:${message.id}"
        override val contentType = "scheduled"
        override fun equals(other: Any?): Boolean =
            this === other || (other is ScheduledTaskCardItem && message == other.message)
        override fun hashCode(): Int = message.hashCode()
    }

    class AgentCallbackCard(
        val message: ChatMessage,
        val callback: com.openminis.app.agent.jobs.AgentCallback,
    ) : FlatChatItem() {
        override val key = "callback:${message.id}"
        override val contentType = "callback"
        override fun equals(other: Any?): Boolean =
            this === other || (other is AgentCallbackCard && message == other.message)
        override fun hashCode(): Int = message.hashCode()
    }

    class AssistantText(
        val messageId: String,
        val block: AssistantBlock,
        val isStreaming: Boolean,
        val messageMarkdown: String,
    ) : FlatChatItem() {
        override val key = "text:$messageId:${block.id}"
        override val contentType = "text"
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AssistantText) return false
            return messageId == other.messageId &&
                block === other.block &&
                isStreaming == other.isStreaming &&
                messageMarkdown.length == other.messageMarkdown.length
        }
        override fun hashCode(): Int {
            var h = messageId.hashCode()
            h = h * 31 + System.identityHashCode(block)
            h = h * 31 + isStreaming.hashCode()
            h = h * 31 + messageMarkdown.length
            return h
        }
    }

    class AssistantMarkdownBlock(
        val messageId: String,
        val parentBlockId: String,
        val rawText: String,
        val blockIndex: Int,
        val isLastBlockOfMessage: Boolean,
        val messageIsStreaming: Boolean,
        val messageMarkdown: String,
        val inFinalTextBlock: Boolean = false,
    ) : FlatChatItem() {
        override val key = "mdblock:$messageId:$parentBlockId:$blockIndex"
        override val contentType = "mdblock"
        val isStreaming: Boolean get() = messageIsStreaming && isLastBlockOfMessage
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AssistantMarkdownBlock) return false
            return messageId == other.messageId &&
                parentBlockId == other.parentBlockId &&
                blockIndex == other.blockIndex &&
                isLastBlockOfMessage == other.isLastBlockOfMessage &&
                inFinalTextBlock == other.inFinalTextBlock &&
                messageIsStreaming == other.messageIsStreaming &&
                rawText.length == other.rawText.length &&
                messageMarkdown.length == other.messageMarkdown.length
        }
        override fun hashCode(): Int {
            var h = messageId.hashCode()
            h = h * 31 + parentBlockId.hashCode()
            h = h * 31 + blockIndex
            h = h * 31 + isLastBlockOfMessage.hashCode()
            h = h * 31 + inFinalTextBlock.hashCode()
            h = h * 31 + messageIsStreaming.hashCode()
            h = h * 31 + rawText.length
            h = h * 31 + messageMarkdown.length
            return h
        }
    }

    data class AssistantThinking(
        val messageId: String,
        val block: AssistantBlock,
        val isLast: Boolean,
        val messageIsStreaming: Boolean,
        val messageThinkingLevel: com.openminis.app.data.model.ThinkingLevel? = null,
        val isLastBlockOverall: Boolean = false,
    ) : FlatChatItem() {
        override val key = "thinking:$messageId:${block.id}"
        override val contentType = "thinking"
    }

    data class AssistantToolUse(
        val messageId: String,
        val block: AssistantBlock,
        val allToolBlocks: List<AssistantBlock>,
        val isLastCancelled: Boolean = false,
    ) : FlatChatItem() {
        override val key = "tool:$messageId:${block.id}"
        override val contentType = "tool"
    }

    data class AssistantToolGroup(
        val messageId: String,
        val runKey: String,
        val toolCount: Int,
        val failedCount: Int,
        val cancelledCount: Int,
        val durationMs: Long?,
        val expanded: Boolean,
    ) : FlatChatItem() {
        override val key = "toolgroup:$messageId:$runKey"
        override val contentType = "toolgroup"
    }

    data class AssistantInfo(
        val messageId: String,
        val block: AssistantBlock,
    ) : FlatChatItem() {
        override val key = "info:$messageId:${block.id}"
        override val contentType = "info"
    }

    data class AssistantTyping(val messageId: String) : FlatChatItem() {
        override val key = "typing:$messageId"
        override val contentType = "typing"
    }

    data class AssistantError(val messageId: String, val error: String) : FlatChatItem() {
        override val key = "error:$messageId"
        override val contentType = "error"
    }

    data class AssistantUsage(
        val messageId: String,
        val usage: ChatTokenUsage,
        val completedAt: Long?,
    ) : FlatChatItem() {
        override val key = "usage:$messageId"
        override val contentType = "usage"
    }

    class AssistantLegacyContent(
        val messageId: String,
        val content: String,
        val isStreaming: Boolean,
        val messageMarkdown: String = content,
    ) : FlatChatItem() {
        override val key = "legacy:$messageId"
        override val contentType = "legacy"
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AssistantLegacyContent) return false
            return messageId == other.messageId &&
                isStreaming == other.isStreaming &&
                content.length == other.content.length &&
                messageMarkdown.length == other.messageMarkdown.length
        }
        override fun hashCode(): Int {
            var h = messageId.hashCode()
            h = h * 31 + isStreaming.hashCode()
            h = h * 31 + content.length
            h = h * 31 + messageMarkdown.length
            return h
        }
    }
}

internal fun floatingToolBarBlocks(messages: List<ChatMessage>): List<AssistantBlock> =
    messages.filter(::feedsFloatingToolBar)
        .flatMap { it.toolBlocks }
        .filter(::isFloatingToolBarBlock)

internal fun hasFloatingToolBarBlocks(messages: List<ChatMessage>): Boolean =
    messages.any { feedsFloatingToolBar(it) && it.toolBlocks.any(::isFloatingToolBarBlock) }

private fun feedsFloatingToolBar(m: ChatMessage) = m.role == "assistant" && !m.isCompactedHistory

private fun isFloatingToolBarBlock(b: AssistantBlock) =
    b.toolStatus != null && b.kind != "thinking" && b.kind != "info"

internal fun mergeStreamingOverlay(
    messages: List<ChatMessage>,
    streaming: Map<String, StreamingDelta>,
): List<ChatMessage> {
    if (streaming.isEmpty()) return messages
    return messages.map { m ->
        val delta = streaming[m.id] ?: return@map m
        m.copy(
            content = delta.content,
            isStreaming = true,
            toolBlocks = delta.toolBlocks,
            isAwaitingModelResponse = delta.isAwaitingModelResponse,
        )
    }
}

internal fun streamingOverlaySubset(
    messages: List<ChatMessage>,
    streaming: Map<String, StreamingDelta>,
): List<ChatMessage> {
    if (streaming.isEmpty()) return emptyList()
    val out = ArrayList<ChatMessage>(streaming.size)
    for (m in messages) {
        val delta = streaming[m.id] ?: continue
        out.add(
            m.copy(
                content = delta.content,
                isStreaming = true,
                toolBlocks = delta.toolBlocks,
                isAwaitingModelResponse = delta.isAwaitingModelResponse,
            )
        )
    }
    return out
}

internal fun ChatMessage.rendersAsUserBubble(): Boolean {
    if (role != "user") return false
    if (com.openminis.app.scheduled.ScheduledTaskMarker.parse(content) != null) return false
    if (com.openminis.app.agent.jobs.AgentCallback.isCallbackText(content)) return false
    return true
}

internal fun buildFlatChatItems(
    messages: List<ChatMessage>,
    sessionId: String? = null,
    fromIndex: Int = 0,
    seedKeys: Set<String> = emptySet(),
    liveMessageIds: Set<String> = emptySet(),
): List<FlatChatItem> {
    val out = mutableListOf<FlatChatItem>()
    val usedKeys = if (seedKeys.isEmpty()) mutableSetOf() else seedKeys.toMutableSet()
    fun dedupe(item: FlatChatItem): FlatChatItem {
        if (usedKeys.add(item.key)) return item
        var n = 2
        while (!usedKeys.add("${item.key}#$n")) n++
        return when (item) {
            is FlatChatItem.UserBubble -> FlatChatItem.UserBubble(item.message.copy(id = "${item.message.id}#$n"), item.precededByUser)
            is FlatChatItem.AgentCallbackCard -> FlatChatItem.AgentCallbackCard(item.message.copy(id = "${item.message.id}#$n"), item.callback)
            is FlatChatItem.ScheduledTaskCardItem -> FlatChatItem.ScheduledTaskCardItem(item.message.copy(id = "${item.message.id}#$n"), item.marker)
            is FlatChatItem.AssistantHeader -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantText -> FlatChatItem.AssistantText(
                messageId = "${item.messageId}#$n",
                block = item.block,
                isStreaming = item.isStreaming,
                messageMarkdown = item.messageMarkdown,
            )
            is FlatChatItem.AssistantMarkdownBlock -> FlatChatItem.AssistantMarkdownBlock(
                messageId = "${item.messageId}#$n",
                parentBlockId = item.parentBlockId,
                rawText = item.rawText,
                blockIndex = item.blockIndex,
                isLastBlockOfMessage = item.isLastBlockOfMessage,
                messageIsStreaming = item.messageIsStreaming,
                messageMarkdown = item.messageMarkdown,
                inFinalTextBlock = item.inFinalTextBlock,
            )
            is FlatChatItem.AssistantThinking -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantToolUse -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantToolGroup -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantInfo -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantTyping -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantError -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantUsage -> item.copy(messageId = "${item.messageId}#$n")
            is FlatChatItem.AssistantLegacyContent -> FlatChatItem.AssistantLegacyContent(
                messageId = "${item.messageId}#$n",
                content = item.content,
                isStreaming = item.isStreaming,
                messageMarkdown = item.messageMarkdown,
            )
        }
    }
    for (idx in fromIndex until messages.size) {
        val message = messages[idx]
        if (sessionId != null && idx > 0 && idx % 100 == 0) {
            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "buildFlatChatItems.progress",
                "msgIdx=$idx of=${messages.size} rowsSoFar=${out.size}",
            )
        }
        if (message.role == "user") {
            val sched = com.openminis.app.scheduled.ScheduledTaskMarker.parse(message.content)
            if (sched != null) {
                out.add(dedupe(FlatChatItem.ScheduledTaskCardItem(message, sched)))
                continue
            }
            val callback = if (com.openminis.app.agent.jobs.AgentCallback.isCallbackText(message.content))
                com.openminis.app.agent.jobs.AgentCallback.parse(message.content) else null
            if (callback != null) {
                out.add(dedupe(FlatChatItem.AgentCallbackCard(message, callback)))
                continue
            }
            val prevIsUser = idx > 0 && messages[idx - 1].role == "user"
            out.add(dedupe(FlatChatItem.UserBubble(message, precededByUser = prevIsUser)))
            continue
        }
        val isSystem = message.role == "system"
        val joinedMarkdown = run {
            val parts = message.toolBlocks
                .filter { it.kind == "text" && it.content.isNotEmpty() }
                .joinToString("\n\n") { it.content }
            if (parts.isNotEmpty()) parts else message.content
        }
        val prevNonSystem = (idx - 1 downTo 0).asSequence()
            .map { messages[it] }
            .firstOrNull { it.role != "system" && !AssistantHeaderRule.isSystemWrittenTurn(it) }
        val isResumeContinuation = prevNonSystem?.role == "assistant"
        if (!isSystem && !isResumeContinuation) {
            out.add(dedupe(FlatChatItem.AssistantHeader(message.id)))
        }

        val blocks = message.toolBlocks
        val toolPillBlocks = blocks.filter { it.kind == "tool_use" }
        val lastThinkingId = blocks.lastOrNull { it.kind == "thinking" }?.id
        val lastBlockId = blocks.lastOrNull()?.id
        val lastTextIdx = blocks.indexOfLast { it.kind == "text" }
        val hasAnyTextBlock = lastTextIdx >= 0
        val finalTextIdx = blocks.lastOrNull().let {
            if (UsageTapBand.endsWithText(it?.kind, it?.content?.isNotEmpty() == true)) blocks.lastIndex else -1
        }
        val lastCancelledToolId = blocks.lastOrNull { it.kind == "tool_use" && it.toolStatus == ToolBlockStatus.CANCELLED }?.id

        blocks.forEachIndexed { index, block ->
            when (block.kind) {
                "text" -> {
                    if (block.content.isNotEmpty()) {
                        val isLastText = index == lastTextIdx
                        val blockIsStreaming = message.isStreaming && isLastText
                        if (!blockIsStreaming &&
                            block.content.length > LARGE_MESSAGE_THRESHOLD_CHARS
                        ) {
                            out.add(dedupe(FlatChatItem.AssistantMarkdownBlock(
                                messageId = message.id,
                                parentBlockId = block.id,
                                rawText = block.content,
                                blockIndex = 0,
                                isLastBlockOfMessage = isLastText,
                                messageIsStreaming = false,
                                messageMarkdown = joinedMarkdown,
                                inFinalTextBlock = index == finalTextIdx,
                            )))
                            return@forEachIndexed
                        }
                        val rawFragments = splitMarkdownIntoBlockTexts(block.content)
                        val keepSplit = message.isStreaming || message.id in liveMessageIds
                        val fragments = if (keepSplit) {
                            rawFragments
                        } else {
                            coalesceMarkdownFragments(rawFragments)
                        }
                        if (fragments.isEmpty()) {
                            out.add(dedupe(FlatChatItem.AssistantMarkdownBlock(
                                messageId = message.id,
                                parentBlockId = block.id,
                                rawText = block.content,
                                blockIndex = 0,
                                isLastBlockOfMessage = isLastText && message.isStreaming,
                                messageIsStreaming = message.isStreaming && isLastText,
                                messageMarkdown = joinedMarkdown,
                                inFinalTextBlock = index == finalTextIdx,
                            )))
                        } else {
                            fragments.forEachIndexed { fragIdx, raw ->
                                val isLastFragOfText = fragIdx == fragments.lastIndex
                                out.add(dedupe(FlatChatItem.AssistantMarkdownBlock(
                                    messageId = message.id,
                                    parentBlockId = block.id,
                                    rawText = raw,
                                    blockIndex = fragIdx,
                                    isLastBlockOfMessage = isLastText && isLastFragOfText,
                                    messageIsStreaming = message.isStreaming && isLastText,
                                    messageMarkdown = joinedMarkdown,
                                    inFinalTextBlock = index == finalTextIdx,
                                )))
                            }
                        }
                    }
                }
                "thinking" -> out.add(dedupe(FlatChatItem.AssistantThinking(
                    messageId = message.id,
                    block = block,
                    isLast = block.id == lastThinkingId,
                    messageIsStreaming = message.isStreaming,
                    messageThinkingLevel = message.thinkingLevel,
                    isLastBlockOverall = block.id == lastBlockId,
                )))
                "info" -> out.add(dedupe(FlatChatItem.AssistantInfo(
                    messageId = message.id,
                    block = block,
                )))
                else -> out.add(dedupe(FlatChatItem.AssistantToolUse(
                    messageId = message.id,
                    block = block,
                    allToolBlocks = toolPillBlocks,
                    isLastCancelled = block.id == lastCancelledToolId,
                )))
            }
        }

        val hasVisibleContent = message.content.isNotEmpty() || blocks.any {
            when (it.kind) {
                "info" -> false
                "thinking" -> message.thinkingLevel?.isEnabled ?: true
                "text" -> it.content.isNotEmpty()
                else -> true
            }
        }
        if (message.isStreaming && (!hasVisibleContent || message.isAwaitingModelResponse)) {
            out.add(dedupe(FlatChatItem.AssistantTyping(message.id)))
        }

        if (!hasAnyTextBlock && message.content.isNotEmpty()) {
            out.add(dedupe(FlatChatItem.AssistantLegacyContent(
                messageId = message.id,
                content = message.content,
                isStreaming = message.isStreaming,
            )))
        }

        message.error?.let {
            out.add(dedupe(FlatChatItem.AssistantError(message.id, it)))
        }

        message.tokenUsage?.let { u ->
            if (!message.isStreaming) {
                out.add(dedupe(FlatChatItem.AssistantUsage(message.id, u, message.completedAt)))
            }
        }
    }
    return out
}

internal object AssistantHeaderRule {
    fun isSystemWrittenTurn(message: ChatMessage): Boolean =
        com.openminis.app.agent.jobs.AgentCallback.isCallbackText(message.content) ||
            (message.role == "user" && com.openminis.app.scheduled.ScheduledTaskMarker.parse(message.content) != null)
}


internal class LiveMessageTracker {
    private val ids = HashSet<String>()
    private var seeded = false

    fun update(messages: List<ChatMessage>, streamingIds: Collection<String>): Set<String> {
        if (!seeded && messages.isNotEmpty()) {
            seeded = true
            messages.lastOrNull { it.role == "assistant" }?.let { ids.add(it.id) }
        }
        messages.lastOrNull()?.takeIf { it.isStreaming }?.let { ids.add(it.id) }
        ids.addAll(streamingIds)
        return HashSet(ids)
    }
}
