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
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
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
import com.openminis.app.ui.settings.autoExpandThinkingEnabled
import com.openminis.app.ui.settings.KEY_AUTO_EXPAND_THINKING
import com.openminis.app.ui.settings.getAppearancePrefs
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
import com.openminis.app.ui.util.expandToggleClickable
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.openminis.app.ui.components.DecorativeSpinner
import com.openminis.app.ui.components.rememberDecorativeTick
import com.openminis.app.ui.components.decorativePhase
import androidx.compose.ui.draw.drawWithCache

@Composable
internal fun AssistantHeader() {
    val soulMeta by com.openminis.app.agent.SoulStore.cachedMetadata.collectAsState()
    val displayName = soulMeta.name.ifBlank { com.openminis.app.agent.SoulMetadata.DEFAULT.name }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 10.dp, bottom = 2.dp),
    ) {
        val sparkleGradient = Brush.linearGradient(
            colors = listOf(SparkleColor1, SparkleColor2),
        )
        com.openminis.app.ui.settings.SoulIconGlyph(
            icon = soulMeta.icon,
            sizeDp = 18.dp,
            emojiSp = 15.sp,
            sparkleTint = sparkleGradient,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = displayName,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

internal fun formatReplyClock(
    epochMs: Long,
    nowMs: Long,
    locale: java.util.Locale,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): String {
    val at = java.time.Instant.ofEpochMilli(epochMs).atZone(zone)
    val now = java.time.Instant.ofEpochMilli(nowMs).atZone(zone)
    val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.US).format(at)
    if (at.toLocalDate() == now.toLocalDate()) return time
    val skeleton = if (at.year == now.year) "MMMd" else "yMMMd"
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
    return java.time.format.DateTimeFormatter.ofPattern(pattern, locale).format(at) + " " + time
}

@Composable
internal fun UsageCapsule(usage: ChatTokenUsage?, completedAt: Long?) {
    if (usage == null) return
    val summary = remember(usage) {
        buildString {
            if (usage.latestContextTokens > 0) append("ctx:${formatTokenCount(usage.latestContextTokens)} ")
            append("in:${formatTokenCount(usage.inputTokens)}")
            append(" out:${formatTokenCount(usage.outputTokens)}")
            if (usage.cacheReadTokens > 0) append(" cache:${formatTokenCount(usage.cacheReadTokens)}")
            if (usage.cacheCreationTokens > 0) append(" +cache:${formatTokenCount(usage.cacheCreationTokens)}")
        }
    }
    val today = java.time.LocalDate.now()
    val clock = remember(completedAt, today) {
        completedAt?.let { formatReplyClock(it, System.currentTimeMillis(), java.util.Locale.getDefault()) }
    }
    val ink = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .padding(top = 2.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(50))
            .background(ChatColors.userBubble.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Icon(
            Icons.Default.Speed,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(11.dp),
        )
        Text(
            text = summary,
            fontSize = 10.sp,
            lineHeight = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = ink,
            maxLines = 1,
        )
        clock?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                text = it,
                fontSize = 10.sp,
                lineHeight = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = ink,
                maxLines = 1,
            )
        }
    }
}

private fun formatTokenCount(count: Int): String {
    if (count < 1000) return count.toString()
    val k = count / 1000.0
    return if (k % 1.0 == 0.0) "${k.toInt()}k" else String.format(java.util.Locale.US, "%.1fk", k)
}

@Composable
internal fun AssistantMessageView(
    message: ChatMessage,
    onRetry: (() -> Unit)? = null,
    revealed: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        AssistantHeader()

        val toolPillBlocks = message.toolBlocks.filter { it.kind == "tool_use" }
        val lastThinkingId = message.toolBlocks.lastOrNull { it.kind == "thinking" }?.id
        val lastBlockIdOverall = message.toolBlocks.lastOrNull()?.id
        val hasAnyTextBlock = message.toolBlocks.any { it.kind == "text" }
        val lastTextBlockIndex = message.toolBlocks.indexOfLast { it.kind == "text" }
        message.toolBlocks.forEachIndexed { index, block ->
            when (block.kind) {
                "thinking" -> {
                    val effectiveLevel = message.thinkingLevel
                        ?: com.openminis.app.data.model.ThinkingLevel.MEDIUM
                    if (effectiveLevel.isEnabled) {
                        val isTrailingThinking = block.id == lastBlockIdOverall
                        ThinkingBlock(
                            block,
                            isStreaming = isTrailingThinking && message.isStreaming,
                            isLast = block.id == lastThinkingId,
                        )
                    }
                }
                "info" -> {
                    FallbackInfoBlock(block)
                }
                "text" -> {
                    if (block.content.isNotEmpty()) {
                        val isLastTextBlock = index == lastTextBlockIndex
                        val streaming = message.isStreaming && isLastTextBlock
                        LargeContentGuard(
                            content = block.content,
                            isStreaming = streaming,
                            stableKey = "legacy-text:${message.id}:${block.id}",
                        ) {
                            StreamingMarkdownText(
                                content = block.content,
                                isStreaming = streaming,
                            )
                        }
                    }
                }
                else -> {
                    ToolCallPill(block, allToolBlocks = toolPillBlocks)
                }
            }
        }

        val hasRealBlocks = message.toolBlocks.any { it.kind != "info" }
        if (message.isStreaming && message.content.isEmpty() && !hasRealBlocks) {
            TypingIndicator()
        }

        if (!hasAnyTextBlock && message.content.isNotEmpty()) {
            LargeContentGuard(
                content = message.content,
                isStreaming = message.isStreaming,
                stableKey = "legacy-fallback:${message.id}",
            ) {
                StreamingMarkdownText(
                    content = message.content,
                    isStreaming = message.isStreaming,
                )
            }
        }

        if (message.error != null) {
            InlineErrorBanner(error = message.error, onRetry = onRetry)
        }

        AnimatedVisibility(
            visible = !message.isStreaming && revealed,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            UsageCapsule(message.tokenUsage, message.completedAt)
        }
    }
}

@Composable
internal fun BoundsTrackedBlock(
    messageId: String,
    slotKey: String,
    markdown: String,
    content: @Composable () -> Unit,
) {
    val registry = LocalMessageBoundsRegistry.current
    Box(
        modifier = Modifier.onGloballyPositioned { coords ->
            registry?.put(messageId, slotKey, coords.boundsInWindow(), markdown)
        },
    ) {
        content()
    }
    androidx.compose.runtime.DisposableEffect(messageId, slotKey) {
        onDispose { registry?.remove(messageId, slotKey) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InlineErrorBanner(error: String, onRetry: (() -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFF3B30).copy(alpha = 0.12f))
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(error))
                },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            tint = Color(0xFFFF3B30),
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = com.openminis.app.ui.chat.search.rememberChatSearchHighlighted(error, com.openminis.app.ui.chat.search.ChatSearchUnits.ERROR),
            color = Color(0xFFFF3B30),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFFF3B30).copy(alpha = 0.15f))
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = Color(0xFFFF3B30),
                    modifier = Modifier.size(10.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.chat_longpress_retry), color = Color(0xFFFF3B30), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ToolStopButton(
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.stop_tool)
    Box(
        modifier = modifier.size(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .requiredSize(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(
                    onClickLabel = label,
                    onClick = onStop,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFFFF3B30)),
            )
        }
    }
}


internal fun formatToolDetailsForClipboard(block: AssistantBlock): String {
    val prettyInput = run {
        val raw = block.toolArgs
        if (raw.isBlank()) return@run "(none)"
        try {
            when (raw.trimStart().firstOrNull()) {
                '{' -> org.json.JSONObject(raw).toString(2)
                '[' -> org.json.JSONArray(raw).toString(2)
                else -> raw
            }
        } catch (_: Exception) {
            raw
        }
    }
    val statusLabel = when (block.toolStatus) {
        ToolBlockStatus.SUCCESS -> "success"
        ToolBlockStatus.FAILED -> "error"
        ToolBlockStatus.TIMEOUT -> "timeout"
        ToolBlockStatus.CANCELLED -> "cancelled"
        ToolBlockStatus.RUNNING, ToolBlockStatus.STREAMING, ToolBlockStatus.PENDING -> "running"
        null -> "unknown"
    }
    val resultText = block.content
    return buildString {
        append("## Tool Call\n")
        append("name: ").append(block.toolName).append('\n')
        append("id: ").append(block.id).append('\n')
        append("input:\n").append(prettyInput).append('\n')
        append('\n')
        append("## Tool Result\n")
        append("(").append(resultText.length).append(" chars, ").append(statusLabel).append(")\n")
        if (resultText.isNotEmpty()) append(resultText)
    }
}
private const val SHIMMER_PERIOD_MS = 2800


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolCallPill(
    block: AssistantBlock,
    allToolBlocks: List<AssistantBlock> = listOf(block),
    onRetry: (() -> Unit)? = null,
    onStop: (() -> Unit)? = null,
    onOpenTerminalWithCommand: (String) -> Unit = {},
    onOpenDetail: (String) -> Unit = {},
    onRerunFromHere: (() -> Unit)? = null,
    onCopyDetails: (() -> Unit)? = null,
    onSearchInChat: (() -> Unit)? = null,
) {
    if (com.openminis.app.BuildConfig.DEBUG && false) {
        android.util.Log.d("ToolChain[UI]", "ToolCallPill render: id=${block.id} name=${block.toolName} title=${block.toolTitle} status=${block.toolStatus} contentLen=${block.content.length} argsLen=${block.toolArgs.length}")
    }

    val isRunning = block.toolStatus == ToolBlockStatus.RUNNING ||
        block.toolStatus == ToolBlockStatus.STREAMING ||
        block.toolStatus == ToolBlockStatus.PENDING
    val isDone = block.toolStatus == ToolBlockStatus.SUCCESS
    val isFailed = block.toolStatus == ToolBlockStatus.FAILED ||
        block.toolStatus == ToolBlockStatus.TIMEOUT
    val isCancelled = block.toolStatus == ToolBlockStatus.CANCELLED

    val toolAccent = toolAccentColor(block.toolName)
    val controlSummary = helperControlSummary(block).takeIf {
        com.openminis.app.agent.jobs.HelperRunner.isSubAgentToolName(block.toolName)
    }
    val toolIcon = if (controlSummary != null) Icons.Default.Tune else toolIconFor(block.toolName)

    val iconTint = when {
        isFailed -> ToolErrorColor
        isCancelled -> ToolCancelColor
        isDone -> ToolCheckColor
        else -> toolAccent
    }

    val displayIcon = toolIcon

    val durationText = if (block.durationMs > 0 && !isRunning) {
        val seconds = block.durationMs / 1000.0
        if (seconds < 10) String.format("%.1fs", seconds)
        else String.format("%.0fs", seconds)
    } else null

    val shimmerTick = if (isRunning) rememberDecorativeTick() else null

    var showToolMenu by remember { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
      Box(modifier = Modifier.weight(1f, fill = false)) {
        Row(
            modifier = Modifier
                .background(
                    ChatColors.toolCapsuleBg,
                    CircleShape,
                )
                .border(0.5.dp, ChatColors.toolBorder, CircleShape)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = { onOpenDetail(block.id) },
                    onLongClick = if (onRerunFromHere != null || onCopyDetails != null || onSearchInChat != null) {
                        { showToolMenu = true }
                    } else null,
                )
                .padding(horizontal = 12.dp)
                .height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                displayIcon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(14.dp),
            )

            Spacer(modifier = Modifier.width(8.dp))


            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = com.openminis.app.ui.chat.search.rememberChatSearchHighlighted(controlSummary ?: displayToolTitle(block), com.openminis.app.ui.chat.search.ChatSearchUnits.BODY),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isRunning) {
                    StreamingDotsText()
                }
            }

            if (durationText != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = durationText,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    softWrap = false,
                    maxLines = 1,
                )
            }
            if (isRunning && onStop != null) {
                Spacer(modifier = Modifier.width(8.dp))
                ToolStopButton(onStop = onStop)
            }
        }
        if (shimmerTick != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .graphicsLayer {
                        val phase = decorativePhase(shimmerTick.value, SHIMMER_PERIOD_MS)
                        translationX = (-1f + phase * 3f) * size.width
                    }
                    .drawWithCache {
                        val band = size.width * 0.6f
                        val brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0f),
                                Color.White.copy(alpha = 0.18f),
                                Color.White.copy(alpha = 0f),
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(band, 0f),
                        )
                        onDrawBehind {
                            drawRect(brush = brush, size = androidx.compose.ui.geometry.Size(band, size.height))
                        }
                    },
            )
        }
        val toolMenuWidthDp = minOf(220, LocalConfiguration.current.screenWidthDp).dp
        MinisMenu(
            expanded = showToolMenu,
            onDismissRequest = { showToolMenu = false },
            offset = androidx.compose.ui.unit.DpOffset(0.dp, 6.dp),
            modifier = Modifier.widthIn(max = toolMenuWidthDp),
            minWidth = toolMenuWidthDp,
        ) {
            if (onRerunFromHere != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tool_longpress_rerun_from_here)) },
                    onClick = { showToolMenu = false; onRerunFromHere() },
                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            if (onCopyDetails != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tool_longpress_copy_details)) },
                    onClick = { showToolMenu = false; onCopyDetails() },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            if (onSearchInChat != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_search_in_chat)) },
                    onClick = { showToolMenu = false; onSearchInChat() },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
      }
    }
}



internal fun shouldAutoCollapseThinking(isStreaming: Boolean, userIntent: Boolean?): Boolean {
    if (isStreaming) return false
    return userIntent != false
}

@Composable
internal fun ThinkingBlock(block: AssistantBlock, isStreaming: Boolean, isLast: Boolean = true) {
    val context = LocalContext.current
    val autoExpandThinking = remember { autoExpandThinkingEnabled(context) }
    var expanded by remember(block.id) { mutableStateOf(autoExpandThinking && isLast && isStreaming) }
    var userIntent by remember(block.id) { mutableStateOf<Boolean?>(null) }
    var showThinkingMenu by remember { mutableStateOf(false) }
    LaunchedEffect(block.id, isStreaming) {
        if (shouldAutoCollapseThinking(isStreaming, userIntent)) expanded = false
    }
    val thinkingBlue = Color(0xFF007AFF)
    val charCount = block.content.length
    val charLabel = when {
        charCount >= 1000 -> "${charCount / 1000}K"
        else -> "$charCount"
    }
    val thinkingWindowSize = 8000
    val thinkingHardCap = 100_000
    val overHardCap = charCount > thinkingHardCap
    val canOpenFull = !isStreaming
    var showFullContent by remember(block.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(thinkingBlue.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .border(0.5.dp, thinkingBlue.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box {
            DropdownMenu(expanded = showThinkingMenu, onDismissRequest = { showThinkingMenu = false }) {
                val on = autoExpandThinkingEnabled(context)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.appearance_auto_expand_thinking_title)) },
                    trailingIcon = { if (on) Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = {
                        showThinkingMenu = false
                        getAppearancePrefs(context).edit().putBoolean(KEY_AUTO_EXPAND_THINKING, !on).apply()
                        if (isStreaming && isLast && userIntent == null && !overHardCap) expanded = !on
                    },
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .expandToggleClickable(onLongClick = { showThinkingMenu = true }) {
                    if (!overHardCap) userIntent = !expanded
                    if (overHardCap) {
                        if (canOpenFull) showFullContent = true
                    } else {
                        expanded = !expanded
                    }
                },
        ) {
            if (isStreaming && block.toolStatus != ToolBlockStatus.SUCCESS) {
                DecorativeSpinner(
                    color = thinkingBlue,
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 1.5.dp,
                )
                Spacer(modifier = Modifier.width(6.dp))
            } else {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    tint = thinkingBlue,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = stringResource(R.string.chat_thinking_block_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = thinkingBlue,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (charCount > 0) {
                Text(
                    text = charLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = thinkingBlue.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            if (overHardCap) {
                if (canOpenFull) Text(
                    text = stringResource(R.string.thinking_view_full),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = thinkingBlue,
                )
            } else {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = thinkingBlue.copy(alpha = 0.5f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        AnimatedVisibility(visible = expanded && !overHardCap) {
            val scrollState = rememberScrollState()
            val isTruncated = charCount > thinkingWindowSize
            val displayContent = remember(charCount) {
                if (isTruncated) {
                    val full = block.content
                    val start = charCount - thinkingWindowSize
                    val nl = full.indexOf('\n', start)
                    if (nl in start until start + 200) full.substring(nl + 1)
                    else full.substring(start)
                } else {
                    block.content
                }
            }
            var userScrolledAway by remember(block.id) { mutableStateOf(false) }
            val userDragging by scrollState.interactionSource.collectIsDraggedAsState()
            LaunchedEffect(scrollState, block.id) {
                snapshotFlow {
                    Triple(
                        scrollState.value,
                        scrollState.maxValue,
                        userDragging,
                    )
                }.collect { (v, max, dragging) ->
                    val gap = (max - v).coerceAtLeast(0)
                    when {
                        dragging && gap > 4 -> userScrolledAway = true
                        gap <= 4 -> userScrolledAway = false
                    }
                }
            }
            LaunchedEffect(scrollState, block.id, isStreaming) {
                if (!isStreaming) return@LaunchedEffect
                snapshotFlow { scrollState.maxValue }
                    .collect { max ->
                        if (userScrolledAway) return@collect
                        scrollState.scrollTo(max)
                    }
            }
            Column(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .heightIn(max = 300.dp)
                    .verticalScroll(scrollState),
            ) {
                if (isTruncated) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(
                                R.string.thinking_truncated_hint,
                                displayContent.length / 1000,
                                charCount / 1000,
                            ),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                            modifier = Modifier.padding(vertical = 4.dp).weight(1f, fill = false),
                        )
                        if (canOpenFull) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.thinking_show_full),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = thinkingBlue,
                                modifier = Modifier
                                    .clickable { showFullContent = true }
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }
                }
                Text(
                    text = displayContent,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    lineHeight = 19.sp,
                )
            }
        }

        if (showFullContent && canOpenFull) {
            ThinkingFullDialog(
                content = block.content,
                onDismiss = { showFullContent = false },
            )
        }
    }
}
