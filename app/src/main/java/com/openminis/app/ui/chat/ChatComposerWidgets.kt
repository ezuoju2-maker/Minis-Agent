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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.material.icons.filled.Keyboard
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
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
import androidx.compose.foundation.horizontalScroll
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
import com.openminis.app.ui.DisplayBitmapLimits
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.DecorativeSpinner

@Composable
internal fun AttachmentChip(
    attachment: InputAttachment,
    onRemove: () -> Unit,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val chipShape = RoundedCornerShape(8.dp)
    Box(modifier = Modifier.size(width = 72.dp, height = 70.dp)) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(64.dp)
                .clip(chipShape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        ) {
            if (attachment.isImage) {
                AsyncImage(
                    model = attachment.uri,
                    contentDescription = attachment.fileName,
                    modifier = Modifier
                        .matchParentSize()
                        .clip(chipShape)
                        .border(1.dp, ChatColors.thumbnailBorder, chipShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Column(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant, chipShape)
                        .border(1.dp, ChatColors.thumbnailBorder, chipShape),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = attachment.fileName,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .border(0.5.dp, ChatColors.thumbnailBorder, CircleShape)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(13.dp),
            )
        }
    }
}


@Composable
internal fun InputCircleButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(ChatColors.inputIconBg, CircleShape)
            .border(0.5.dp, ChatColors.inputIconBorder, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MicButton(
    isRecording: Boolean,
    localeBadge: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    isVoiceActive: Boolean = false,
) {
    val bg = if (isRecording) Color.Red.copy(alpha = 0.15f)
             else ChatColors.inputIconBg
    val tint = if (isRecording) Color.Red
               else MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = if (isRecording) Color.Transparent else ChatColors.inputIconBorder
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(bg, CircleShape)
            .border(0.5.dp, borderColor, CircleShape)
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (isVoiceActive) Icons.Default.Keyboard else Icons.Default.Mic,
            contentDescription = if (isVoiceActive) "Switch to keyboard"
            else if (isRecording) "Stop recording" else "Voice input",
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        if (!localeBadge.isNullOrEmpty()) {
            Text(
                text = localeBadge,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Red,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .background(Color.White, CircleShape)
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }
    }
}


@Composable
internal fun rememberChildLiveToolBlock(childSessionId: String?): AssistantBlock? {
    if (childSessionId.isNullOrEmpty()) return null
    val vm = remember(childSessionId) {
        runCatching { ChatViewModelStore.existing(childSessionId) }.getOrNull()
    } ?: return null
    val msgs by vm.messages.collectAsState()
    val streaming by vm.streamingById.collectAsState()
    return remember(msgs, streaming) {
        val merged = if (streaming.isEmpty()) msgs else streamingOverlaySubset(msgs, streaming)
        (merged.lastOrNull { it.role == "assistant" }
            ?: msgs.lastOrNull { it.role == "assistant" })
            ?.toolBlocks
            ?.lastOrNull {
                it.kind == "tool_use" && (
                    it.toolStatus == ToolBlockStatus.RUNNING ||
                        it.toolStatus == ToolBlockStatus.STREAMING ||
                        it.toolStatus == ToolBlockStatus.PENDING
                    )
            }
    }
}

@Composable
private fun GenericThumbContent(block: AssistantBlock, toolAccent: Color) {
    if (block.content.isNotEmpty()) {
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)) {
            Text(
                text = remember(block.content) { ToolTextTail.lastLines(block.content, 12) },
                fontSize = 5.5.sp,
                fontFamily = FontFamily.Monospace,
                color = toolAccent.copy(alpha = 0.85f),
                maxLines = 12,
                lineHeight = 6.5.sp,
            )
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(toolIconFor(block.toolName), contentDescription = null, tint = toolAccent.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ToolPreviewThumbnail(
    block: AssistantBlock,
    toolAccent: Color,
    onClick: () -> Unit,
    fallbackImagePath: String? = null,
    punchThroughDepth: Int = 0,
    onLongClick: (() -> Unit)? = null,
) {
    val args = remember(block.toolArgs) {
        try { org.json.JSONObject(block.toolArgs) } catch (_: Exception) { org.json.JSONObject() }
    }

    val isShellTool = block.toolName == "shell_execute"
    val isLive = block.toolStatus == ToolBlockStatus.RUNNING ||
        block.toolStatus == ToolBlockStatus.STREAMING ||
        block.toolStatus == ToolBlockStatus.PENDING
    val resourceMonitor = rememberSystemResourceMonitor(active = isShellTool && isLive)

    val thumbnailShape = RoundedCornerShape(8.dp)
    val subAgentLive = punchThroughDepth > 0 ||
        (
            block.toolName == com.openminis.app.agent.jobs.HelperRunner.TOOL_NAME &&
                (remember(block.content, block.toolStatus, block.toolTitle) { parseHelperBlock(block) }.phase as? HelperPhase.Running)?.tool != null
            )
    val subAgentGlow = helperAccent()
    Box(
        modifier = Modifier
            .size(width = 100.dp, height = 65.dp)
            .shadow(elevation = 10.dp, shape = thumbnailShape, ambientColor = Color.Black.copy(alpha = 0.15f), spotColor = Color.Black.copy(alpha = 0.25f))
            .clip(thumbnailShape)
            .background(
                when (block.toolName) {
                    "file_read", "file_write" -> ChatColors.secondaryBg
                    "file_edit" -> Color(0xFF1A1A1E)
                    else -> Color(0xFF1A1A1E)
                }
            )
            .border(0.5.dp, ChatColors.thumbnailBorder, thumbnailShape)
            .drawWithContent {
                drawContent()
                if (!subAgentLive) return@drawWithContent
                val band = 32.dp.toPx()
                val step = 0.5.dp.toPx()
                val radius = 8.dp.toPx()
                var inset = 0f
                while (inset < band) {
                    val t = inset / band
                    val fade = (1f - t)
                    val alpha = 0.11f * fade * fade * fade
                    val w = size.width - inset * 2
                    val h = size.height - inset * 2
                    if (w <= 0f || h <= 0f) break
                    drawRoundRect(
                        color = subAgentGlow.copy(alpha = alpha),
                        topLeft = Offset(inset, inset),
                        size = Size(w, h),
                        cornerRadius = CornerRadius((radius - inset).coerceAtLeast(0f)),
                        style = Stroke(width = step * 2f),
                    )
                    inset += step
                }
            }
            .then(
                if (onLongClick == null) Modifier.clickable(onClick = onClick)
                else Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
            ),
    ) {
        when (block.toolName) {
            "shell_execute" -> {
                val command = extractShellCommand(args, block)
                Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) {
                    Text(
                        text = "$ ${ToolTextTail.head(command)}",
                        fontSize = 7.sp,
                        lineHeight = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (block.content.isNotEmpty()) {
                        Text(
                            text = remember(block.content) { ToolTextTail.lastLines(block.content, 12) },
                            fontSize = 5.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = toolAccent.copy(alpha = 0.85f),
                            maxLines = 12,
                            lineHeight = 6.5.sp,
                        )
                    }
                }
            }

            "file_edit" -> {
                val oldStr = args.optString("old_string", "")
                    .ifEmpty { extractPartialJsonString("old_string", block.toolArgs) ?: "" }
                val newStr = args.optString("new_string", "")
                    .ifEmpty { extractPartialJsonString("new_string", block.toolArgs) ?: "" }
                Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)) {
                    if (oldStr.isNotEmpty()) {
                        remember(oldStr) { ToolTextTail.lastLines(oldStr, 5).lines() }.forEach { line ->
                            Text(
                                text = "- $line",
                                fontSize = 5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFFF6B6B),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 6.sp,
                            )
                        }
                    }
                    if (newStr.isNotEmpty()) {
                        remember(newStr) { ToolTextTail.lastLines(newStr, 5).lines() }.forEach { line ->
                            Text(
                                text = "+ $line",
                                fontSize = 5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF4ADE80),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 6.sp,
                            )
                        }
                    }
                    if (oldStr.isEmpty() && newStr.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(toolIconFor("file_edit"), contentDescription = null, tint = toolAccent.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            "file_read", "file_write" -> {
                val path = args.optString("path", "")
                    .ifEmpty { extractPartialJsonString("path", block.toolArgs) ?: "" }
                val rawName = if (path.contains("/")) path.substringAfterLast("/") else path
                val header = when {
                    rawName.isNotEmpty() -> rawName
                    block.toolName == "file_write" -> "Write file"
                    else -> "Read file"
                }
                val displayText = if (block.toolName == "file_write") {
                    args.optString("content", "")
                        .ifEmpty { extractPartialJsonString("content", block.toolArgs) ?: "" }
                        .ifEmpty { block.content }
                } else {
                    block.content
                }
                Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                    Text(
                        text = header,
                        fontSize = 7.sp,
                        lineHeight = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = ChatColors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (displayText.isNotEmpty()) {
                        Text(
                            text = remember(displayText) { ToolTextTail.lastLines(displayText, 12) },
                            fontSize = 5.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = toolAccent.copy(alpha = 0.85f),
                            maxLines = 12,
                            lineHeight = 6.5.sp,
                        )
                    }
                }
            }

            "memory_write", "memory_get" -> {
                val memContent = if (block.toolName == "memory_write") {
                    args.optString("content", "")
                        .ifEmpty { extractPartialJsonString("content", block.toolArgs) ?: "" }
                        .ifEmpty { block.content }
                } else {
                    block.content
                }
                val keywords = args.optString("keywords", "")
                    .ifEmpty { extractPartialJsonString("keywords", block.toolArgs) ?: "" }
                val displayText = if (block.toolName == "memory_get" && keywords.isNotEmpty()) {
                    "Keywords: $keywords\n$memContent"
                } else memContent
                Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                    Text(
                        text = block.toolName,
                        fontSize = 7.sp,
                        lineHeight = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = ToolMemoryAccent.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (displayText.isNotEmpty()) {
                        Text(
                            text = remember(displayText) { ToolTextTail.lastLines(displayText, 12) },
                            fontSize = 5.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = toolAccent.copy(alpha = 0.85f),
                            maxLines = 12,
                            lineHeight = 6.5.sp,
                        )
                    }
                }
            }

            "read_image" -> {
                val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(
                    initialValue = null,
                    block.imageFilePath,
                ) {
                    val path = block.imageFilePath
                    value = if (path == null) null else withContext(Dispatchers.IO) {
                        DisplayBitmapLimits.decodeFileBounded(path, DisplayBitmapLimits.MAX_THUMBNAIL_EDGE_PX)
                    }
                }
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Read image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            toolIconFor(block.toolName),
                            contentDescription = null,
                            tint = toolAccent.copy(alpha = 0.6f),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            "browser_use" -> {
                val liveBitmap = rememberBrowserLiveSnapshot(block)
                val savedBitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(
                    initialValue = null,
                    block.imageFilePath,
                    fallbackImagePath,
                ) {
                    val path = block.imageFilePath ?: fallbackImagePath
                    value = if (path == null) null else withContext(Dispatchers.IO) {
                        DisplayBitmapLimits.decodeFileBounded(path, DisplayBitmapLimits.MAX_THUMBNAIL_EDGE_PX)
                    }
                }
                val bitmap = liveBitmap ?: savedBitmap
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Browser screenshot",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Language,
                            contentDescription = null,
                            tint = toolAccent.copy(alpha = 0.6f),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            com.openminis.app.agent.jobs.HelperRunner.TOOL_NAME -> {
                val childId = remember(block.content, block.toolStatus, block.toolTitle) {
                    parseHelperBlock(block).childSessionId
                }
                val inner = if (punchThroughDepth < 1) {
                    rememberChildLiveToolBlock(childId)
                } else {
                    null
                }
                if (inner != null) {
                    ToolPreviewThumbnail(
                        block = inner,
                        toolAccent = toolAccentColor(inner.toolName),
                        onClick = onClick,
                        punchThroughDepth = punchThroughDepth + 1,
                        onLongClick = onLongClick,
                    )
                } else {
                    HelperThumbContent(block)
                }
            }

            com.openminis.app.agent.userask.UserAskPromptText.TOOL_NAME ->
                UserAskThumbContent(block) { GenericThumbContent(block, toolAccent) }

            else -> GenericThumbContent(block, toolAccent)
        }

        if (isShellTool && isLive) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = 10.dp)
                    .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(vertical = 1.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "${resourceMonitor.formattedCpu()}  ${resourceMonitor.formattedMem(compact = true)}",
                    fontSize = 5.sp,
                    lineHeight = 8.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
internal object FloatingToolBarMetrics {
    val BAR_HEIGHT = 38.dp
    val THUMBNAIL_HEIGHT = 65.dp
    val WRAPPER_BOTTOM_PADDING = 6.dp
    val ROW_GAP = 8.dp

    fun visibleTop(previewEnabled: Boolean) =
        WRAPPER_BOTTOM_PADDING + if (previewEnabled) THUMBNAIL_HEIGHT else BAR_HEIGHT
}

@Composable
internal fun FloatingToolStatusBar(
    toolBlocks: List<AssistantBlock>,
    onStop: ((blockId: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    onOpenTerminalWithCommand: (String) -> Unit = {},
    onOpenDetail: (String) -> Unit = {},
) {
    var currentIndex by remember { mutableStateOf(toolBlocks.lastIndex.coerceAtLeast(0)) }
    val lastIndex = toolBlocks.lastIndex
    val currentStatus = toolBlocks.getOrNull(currentIndex)?.toolStatus
    LaunchedEffect(lastIndex, currentStatus) {
        val isCurrentActive = currentStatus == ToolBlockStatus.RUNNING ||
            currentStatus == ToolBlockStatus.STREAMING ||
            currentStatus == ToolBlockStatus.PENDING
        if (!isCurrentActive) {
            val newestActive = toolBlocks.indexOfLast {
                it.toolStatus == ToolBlockStatus.RUNNING ||
                    it.toolStatus == ToolBlockStatus.STREAMING ||
                    it.toolStatus == ToolBlockStatus.PENDING
            }
            currentIndex = if (newestActive >= 0) newestActive else lastIndex.coerceAtLeast(0)
        }
    }
    val block = toolBlocks.getOrNull(currentIndex) ?: return
    val onOpenCurrentDetail: () -> Unit = { onOpenDetail(block.id) }

    val isDone = block.toolStatus == ToolBlockStatus.SUCCESS
    val isFailed = block.toolStatus == ToolBlockStatus.FAILED ||
        block.toolStatus == ToolBlockStatus.TIMEOUT
    val isCancelled = block.toolStatus == ToolBlockStatus.CANCELLED
    val isRunning = block.toolStatus == ToolBlockStatus.RUNNING ||
        block.toolStatus == ToolBlockStatus.STREAMING ||
        block.toolStatus == ToolBlockStatus.PENDING
    val toolAccent = toolAccentColor(block.toolName)
    val previewEnabled = LocalToolPreviewEnabled.current
    val context = LocalContext.current
    var showBarMenu by remember { mutableStateOf(false) }
    var showThumbMenu by remember { mutableStateOf(false) }
    fun setToolPreview(on: Boolean) {
        com.openminis.app.ui.settings.getAppearancePrefs(context).edit()
            .putBoolean(com.openminis.app.ui.settings.KEY_TOOL_PREVIEW, on).apply()
        if (!on) {
            android.widget.Toast.makeText(
                context, context.getString(R.string.tool_preview_closed_toast), android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    val thumbnailWidth = 100.dp
    val thumbnailHeight = FloatingToolBarMetrics.THUMBNAIL_HEIGHT
    val barHeight = FloatingToolBarMetrics.BAR_HEIGHT
    val thumbnailOverhang = thumbnailHeight - barHeight
    val thumbnailInset = 10.dp
    val barStartPadding = if (previewEnabled) thumbnailWidth + thumbnailInset + 8.dp else 12.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (previewEnabled) thumbnailOverhang else 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .align(Alignment.BottomCenter)
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(10.dp), ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.12f))
                .background(ChatColors.inputBg, RoundedCornerShape(10.dp))
                .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(10.dp))
                .padding(start = barStartPadding, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isRunning) {
                DecorativeSpinner(
                    color = toolAccent,
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 1.5.dp,
                )
            } else {
                val (icon, tint) = when {
                    isDone -> Icons.Default.CheckCircle to ToolCheckColor
                    isFailed -> Icons.Default.Error to ToolErrorColor
                    isCancelled -> Icons.Default.Close to ToolCancelColor
                    else -> Icons.Default.Build to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
            }

            Spacer(modifier = Modifier.width(6.dp))


            Box(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayToolTitle(block),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = ChatColors.primaryText,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onLongClick = { showBarMenu = true },
                        ) { onOpenCurrentDetail() },
                    overflow = TextOverflow.Ellipsis,
                )
                DropdownMenu(expanded = showBarMenu, onDismissRequest = { showBarMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.appearance_tool_preview_title)) },
                        trailingIcon = { if (previewEnabled) Icon(Icons.Default.Check, contentDescription = null) },
                        onClick = {
                            showBarMenu = false
                            setToolPreview(!previewEnabled)
                        },
                    )
                }
            }

            if (toolBlocks.size > 1) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    Icon(
                        Icons.Default.ChevronLeft,
                        contentDescription = "Previous",
                        tint = if (currentIndex > 0) MaterialTheme.colorScheme.onSurface
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                        modifier = Modifier
                            .size(18.dp)
                            .combinedClickable(
                                enabled = currentIndex > 0,
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onLongClick = { currentIndex = 0 },
                                onClick = { if (currentIndex > 0) currentIndex-- },
                            ),
                    )
                    val positionLabel = stringResource(
                        R.string.tool_pager_position,
                        currentIndex + 1,
                        toolBlocks.size,
                    )
                    Text(
                        "${currentIndex + 1}/${toolBlocks.size}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics {
                            contentDescription = positionLabel
                        },
                    )
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "Next",
                        tint = if (currentIndex < toolBlocks.lastIndex) MaterialTheme.colorScheme.onSurface
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                        modifier = Modifier
                            .size(18.dp)
                            .combinedClickable(
                                enabled = currentIndex < toolBlocks.lastIndex,
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onLongClick = { currentIndex = toolBlocks.lastIndex },
                                onClick = { if (currentIndex < toolBlocks.lastIndex) currentIndex++ },
                            ),
                    )
                }
            }
        }

        if (previewEnabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = thumbnailInset),
            ) {
                val fallbackImagePath = remember(block.id, toolBlocks) {
                    if (block.toolName != "browser_use" || block.imageFilePath != null) null
                    else {
                        val blockIdx = toolBlocks.indexOfFirst { it.id == block.id }
                        if (blockIdx <= 0) null
                        else (blockIdx - 1 downTo 0).firstNotNullOfOrNull { i ->
                            val prev = toolBlocks[i]
                            if (prev.toolName == "browser_use") prev.imageFilePath else null
                        }
                    }
                }
                ToolPreviewThumbnail(
                    block = block,
                    toolAccent = toolAccent,
                    onClick = { onOpenCurrentDetail() },
                    fallbackImagePath = fallbackImagePath,
                    onLongClick = { showThumbMenu = true },
                )
                DropdownMenu(expanded = showThumbMenu, onDismissRequest = { showThumbMenu = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.tool_preview_close_menu), color = MaterialTheme.colorScheme.error)
                        },
                        onClick = {
                            showThumbMenu = false
                            setToolPreview(false)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ThinkingLevelPicker(
    current: ThinkingLevel,
    availableLevels: List<ThinkingLevel>,
    onSelect: (ThinkingLevel) -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val maxAvailable = availableLevels.lastOrNull { it != ThinkingLevel.OFF }
    val isClamped = current.isEnabled && maxAvailable != null && current.rank > maxAvailable.rank
    val clampOrange = Color(0xFFFF9500)
    Row(
        modifier = Modifier
            .background(
                ChatColors.secondaryText.copy(alpha = 0.12f),
                RoundedCornerShape(6.dp),
            )
            .clip(RoundedCornerShape(6.dp))
            .horizontalScroll(scrollState),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        availableLevels.forEach { level ->
            val isExactMatch = level == current
            val isClampedHighlight = isClamped && level == maxAvailable
            val isHighlighted = isExactMatch || isClampedHighlight
            val bg = when {
                isClampedHighlight -> clampOrange.copy(alpha = 0.75f)
                isHighlighted -> ChatColors.thinking
                else -> Color.Transparent
            }
            val fg = if (isHighlighted) Color.White else ChatColors.secondaryText
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(bg)
                    .clickable { onSelect(if (isHighlighted) ThinkingLevel.OFF else level) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text(
                    text = level.localizedName(context),
                    fontSize = 11.sp,
                    fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
                    color = fg,
                )
                if (isClampedHighlight) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = null,
                        tint = fg,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}
