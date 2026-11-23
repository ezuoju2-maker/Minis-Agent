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
import androidx.compose.material.icons.automirrored.outlined.Chat
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
import com.openminis.app.ui.DisplayBitmapLimits
import com.openminis.app.ui.components.MinisTextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolDetailSheet(
    toolBlocks: List<AssistantBlock>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onOpenTerminalWithCommand: (String) -> Unit = {},
    onOpenBrowserForUrl: (String) -> Unit = {},
    onOpenAgentTranscript: ((childSessionId: String) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var currentIdx by remember { mutableStateOf(initialIndex.coerceIn(0, toolBlocks.lastIndex.coerceAtLeast(0))) }
    val block = toolBlocks.getOrNull(currentIdx) ?: return onDismiss()

    val isLive = block.toolStatus == ToolBlockStatus.RUNNING ||
        block.toolStatus == ToolBlockStatus.STREAMING ||
        block.toolStatus == ToolBlockStatus.PENDING
    val toolAccent = toolAccentColor(block.toolName)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .background(ChatColors.secondaryBg),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ChatColors.sheetHeaderBg)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(ChatColors.secondaryBg, CircleShape)
                        .border(0.5.dp, ChatColors.inputIconBorder, CircleShape)
                        .clip(CircleShape)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = ChatColors.primaryText,
                        modifier = Modifier.size(16.dp),
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "DeepSeek Computer",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ChatColors.primaryText,
                )

                Spacer(modifier = Modifier.weight(1f))

                val clipboardManager = LocalClipboardManager.current
                val actionContext = LocalContext.current
                var copyDone by remember { mutableStateOf(false) }
                val isShellTool = block.toolName == "shell_execute"
                val isBrowserTool = block.toolName == "browser_use"
                val toolArgsForAction = remember(block.toolArgs) {
                    try { org.json.JSONObject(block.toolArgs) } catch (_: Exception) { org.json.JSONObject() }
                }
                val browserActionUrl = remember(block.id, block.browserURL, block.toolArgs, toolBlocks) {
                    if (!isBrowserTool) ""
                    else {
                        val direct = block.browserURL?.takeIf { it.isNotEmpty() }
                        val argsUrl = toolArgsForAction.optString("url", "").takeIf { it.isNotEmpty() }
                        direct ?: argsUrl ?: run {
                            val idx = toolBlocks.indexOfFirst { it.id == block.id }
                            if (idx > 0) {
                                (idx - 1 downTo 0).firstNotNullOfOrNull { i ->
                                    val prev = toolBlocks[i]
                                    if (prev.toolName != "browser_use") return@firstNotNullOfOrNull null
                                    prev.browserURL?.takeIf { it.isNotEmpty() }
                                        ?: try {
                                            org.json.JSONObject(prev.toolArgs)
                                                .optString("url", "")
                                                .takeIf { it.isNotEmpty() }
                                        } catch (_: Exception) { null }
                                }
                            } else null
                        } ?: ""
                    }
                }
                val hasBrowserUrl = isBrowserTool && browserActionUrl.isNotEmpty()
                val isAgentTool = com.openminis.app.agent.jobs.HelperRunner.isSubAgentToolName(block.toolName)
                val agentChildId = if (isAgentTool) com.openminis.app.agent.jobs.HelperRunner.childSessionIdFrom(block.content) else null
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(ChatColors.secondaryBg, CircleShape)
                        .border(0.5.dp, ChatColors.inputIconBorder, CircleShape)
                        .clip(CircleShape)
                        .clickable {
                            if (isAgentTool) {
                                if (agentChildId != null) onOpenAgentTranscript?.invoke(agentChildId)
                            } else if (isShellTool) {
                                val command = extractShellCommand(toolArgsForAction, block)
                                if (command.isNotBlank() && command != "Shell command") {
                                    AppLogger.info(
                                        "ChatScreen",
                                        "opening terminal with prefill: ${command.take(120)}",
                                    )
                                    onDismiss()
                                    onOpenTerminalWithCommand(command)
                                }
                            } else if (hasBrowserUrl) {
                                AppLogger.info(
                                    "ChatScreen",
                                    "browser_use action → open in session pool: ${browserActionUrl.take(160)}",
                                )
                                onOpenBrowserForUrl(browserActionUrl)
                            } else if (block.content.isNotEmpty()) {
                                clipboardManager.setText(AnnotatedString(block.content))
                                copyDone = true
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        when {
                            copyDone -> Icons.Default.Check
                            isAgentTool -> Icons.AutoMirrored.Outlined.Chat
                            isShellTool -> Icons.Default.Terminal
                            isBrowserTool -> Icons.Outlined.Public
                            else -> Icons.Default.ContentCopy
                        },
                        contentDescription = when {
                            isAgentTool -> "Open agent conversation"
                            isShellTool -> "Open in terminal"
                            isBrowserTool -> "Open in session browser"
                            else -> stringResource(R.string.common_copy)
                        },
                        tint = ChatColors.primaryText,
                        modifier = Modifier.size(16.dp),
                    )
                }
                LaunchedEffect(copyDone) {
                    if (copyDone) {
                        kotlinx.coroutines.delay(2000)
                        copyDone = false
                    }
                }
            }

            HorizontalDivider(thickness = 1.dp, color = ChatColors.sheetHeaderBorder)

            val outputScrollState = rememberScrollState()
            val toolArgsObj = remember(block.toolArgs) {
                try { org.json.JSONObject(block.toolArgs) } catch (_: Exception) { org.json.JSONObject() }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when (block.toolName) {
                    com.openminis.app.agent.jobs.HelperRunner.TOOL_NAME -> HelperDetailContent(block)

                    "shell_execute" -> {
                        val command = extractShellCommand(toolArgsObj, block)
                        val sheetMonitor = rememberSystemResourceMonitor(active = isLive)
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp)
                                .padding(top = 12.dp, bottom = 16.dp),
                        ) {
                            val cardMinHeight = maxWidth * 3f / 4f
                            val hudReservePadding = if (isLive) 28.dp else 0.dp
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight()
                                    .heightIn(min = cardMinHeight)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Black)
                                    .border(
                                        width = 0.5.dp,
                                        color = Color(0xFF404040),
                                        shape = RoundedCornerShape(10.dp),
                                    ),
                            ) {
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .verticalScroll(outputScrollState)
                                            .padding(14.dp)
                                            .padding(bottom = hudReservePadding),
                                    ) {
                                        Text(
                                            text = "$ ${remember(command) { ToolTextTail.capLongLines(command) }}",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color.White,
                                            lineHeight = 18.sp,
                                        )
                                        if (block.content.isNotEmpty()) {
                                            val urlClick = LocalMarkdownUrlClickHandler.current
                                            val linkified = remember(block.content, urlClick) {
                                                val shown = ToolTextTail.capLongLines(block.content)
                                                if (urlClick != null) {
                                                    com.openminis.app.ui.util.linkifyUrls(
                                                        text = shown,
                                                        onClick = urlClick,
                                                        linkColor = Color(0xFF7CC4FF),
                                                    )
                                                } else androidx.compose.ui.text.AnnotatedString(shown)
                                            }
                                            Text(
                                                text = linkified,
                                                fontSize = 13.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = Color(0xFF34C759),
                                                lineHeight = 18.sp,
                                            )
                                        }
                                    }
                                }
                                if (isLive) {
                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .background(Color(0xFF141414))
                                            .padding(vertical = 2.5.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = sheetMonitor.formattedCpu(),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color(0xFF34C759),
                                        )
                                        Text(
                                            text = sheetMonitor.formattedMem(),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color(0xFF34C759),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    "file_edit" -> {
                        val path = toolArgsObj.optString("path", "")
                            .ifEmpty { extractPartialJsonString("path", block.toolArgs) ?: "" }
                        val fileName = if (path.contains("/")) path.substringAfterLast("/") else path
                        val oldStr = toolArgsObj.optString("old_string", "")
                            .ifEmpty { extractPartialJsonString("old_string", block.toolArgs) ?: "" }
                        val newStr = toolArgsObj.optString("new_string", "")
                            .ifEmpty { extractPartialJsonString("new_string", block.toolArgs) ?: "" }

                        val isDark = ChatColors.isDark
                        val cardBg = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF0F0F0)
                        val cardBorder = if (isDark) Color(0xFF404040) else Color(0xFFD1D1D1)
                        val redBg = if (isDark) Color(0xFF4D1414) else Color(0xFFFFE5E5)
                        val redText = if (isDark) Color(0xFFFF6666) else Color(0xFFCC1A1A)
                        val greenBg = if (isDark) Color(0xFF144D14) else Color(0xFFE5FFE5)
                        val greenText = if (isDark) Color(0xFF66FF66) else Color(0xFF1A991A)

                        val bytes = (oldStr.toByteArray(Charsets.UTF_8).size +
                            newStr.toByteArray(Charsets.UTF_8).size)
                        val sizeLabel = when {
                            isLive -> "streaming…"
                            bytes < 1024 -> "$bytes B"
                            else -> "${bytes / 1024} KB"
                        }
                        val resultDetail = block.content
                            .takeIf { it.isNotEmpty() && !isLive }
                            ?.let { Regex("""\(([^)]+)\)""").find(it)?.value }

                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                        ) {
                            val cardMinHeight = maxWidth * 3f / 4f
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight()
                                    .heightIn(min = cardMinHeight)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(cardBg)
                                    .border(0.5.dp, cardBorder, RoundedCornerShape(10.dp)),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(ChatColors.secondaryBg)
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.EditNote,
                                        contentDescription = null,
                                        tint = Color(0xFFFF9500),
                                        modifier = Modifier.size(12.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (fileName.isNotEmpty()) fileName else "(file)",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        fontFamily = FontFamily.Monospace,
                                        color = ChatColors.primaryText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "($sizeLabel)",
                                        fontSize = 11.sp,
                                        color = if (isLive) Color(0xCCFF9500) else ChatColors.tertiaryText,
                                    )
                                }
                                HorizontalDivider(thickness = 0.5.dp, color = cardBorder)

                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .verticalScroll(outputScrollState),
                                        verticalArrangement = Arrangement.spacedBy(0.dp),
                                    ) {
                                        if (oldStr.isNotEmpty()) {
                                            oldStr.lines().forEach { line ->
                                                Text(
                                                    text = "- $line",
                                                    fontSize = 13.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = redText,
                                                    lineHeight = 17.sp,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(redBg)
                                                        .padding(horizontal = 14.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                        if (newStr.isNotEmpty()) {
                                            newStr.lines().forEach { line ->
                                                Text(
                                                    text = "+ $line",
                                                    fontSize = 13.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = greenText,
                                                    lineHeight = 17.sp,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(greenBg)
                                                        .padding(horizontal = 14.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                    }
                                }

                                if (!isLive && (path.isNotEmpty() || resultDetail != null)) {
                                    Spacer(modifier = Modifier.weight(1f))
                                    HorizontalDivider(thickness = 0.5.dp, color = cardBorder)
                                    Column(
                                        modifier = Modifier.padding(
                                            horizontal = 14.dp,
                                            vertical = 10.dp,
                                        ),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "Edited",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = ChatColors.primaryText,
                                            )
                                            if (path.isNotEmpty()) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = path,
                                                    fontSize = 12.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = ChatColors.secondaryText,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f),
                                                )
                                            }
                                        }
                                        if (resultDetail != null) {
                                            Text(
                                                text = resultDetail,
                                                fontSize = 12.sp,
                                                color = ChatColors.tertiaryText,
                                                modifier = Modifier.padding(top = 2.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    "file_read", "file_write" -> {
                        val path = toolArgsObj.optString("path", "")
                            .ifEmpty { extractPartialJsonString("path", block.toolArgs) ?: "" }
                        val fileName = if (path.contains("/")) path.substringAfterLast("/") else path
                        val displayText = if (block.toolName == "file_write") {
                            toolArgsObj.optString("content", "")
                                .ifEmpty { extractPartialJsonString("content", block.toolArgs) ?: "" }
                                .ifEmpty { block.content }
                        } else {
                            block.content
                        }
                        EditorCard(
                            title = fileName.ifEmpty { "file" },
                            icon = if (block.toolName == "file_read") Icons.Default.Description
                                   else Icons.AutoMirrored.Filled.NoteAdd,
                            iconTint = ChatColors.secondaryText,
                            titleColor = ChatColors.primaryText,
                            sizeColor = ChatColors.tertiaryText,
                            bodyText = displayText,
                            bodyColor = ChatColors.primaryText,
                            isStreaming = isLive,
                            scrollState = outputScrollState,
                            trailingText = if (block.toolName == "file_write" &&
                                block.content.isNotEmpty() && block.content != displayText) block.content else null,
                        )
                    }

                    "browser_use" -> {
                        val action = toolArgsObj.optString("action", "")
                        val argsUrl = toolArgsObj.optString("url", "")
                        val resolvedUrl = block.browserURL?.takeIf { it.isNotEmpty() }
                            ?: argsUrl.takeIf { it.isNotEmpty() }
                            ?: run {
                                val blockIdx = toolBlocks.indexOfFirst { it.id == block.id }
                                if (blockIdx > 0) {
                                    (blockIdx - 1 downTo 0).firstNotNullOfOrNull { i ->
                                        val prev = toolBlocks[i]
                                        prev.browserURL?.takeIf { it.isNotEmpty() }
                                            ?: try {
                                                org.json.JSONObject(prev.toolArgs).optString("url", "").takeIf { it.isNotEmpty() }
                                            } catch (_: Exception) { null }
                                    }
                                } else null
                            } ?: ""
                        val liveBitmap = rememberBrowserLiveSnapshot(block)
                        val savedBitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(
                            initialValue = null,
                            block.imageFilePath,
                            toolBlocks.map { it.imageFilePath },
                        ) {
                            value = withContext(Dispatchers.IO) {
                                block.imageFilePath?.let { path ->
                                    DisplayBitmapLimits.decodeFileBounded(path)
                                } ?: run {
                                    val blockIdx = toolBlocks.indexOfFirst { it.id == block.id }
                                    if (blockIdx <= 0) null
                                    else (blockIdx - 1 downTo 0).firstNotNullOfOrNull { i ->
                                        val prev = toolBlocks[i]
                                        if (prev.toolName == "browser_use" && prev.imageFilePath != null) {
                                            DisplayBitmapLimits.decodeFileBounded(prev.imageFilePath)
                                        } else null
                                    }
                                }
                            }
                        }
                        val screenshotBitmap = liveBitmap ?: savedBitmap

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(outputScrollState)
                                .padding(top = 12.dp, bottom = 16.dp),
                        ) {
                            if (action.isNotEmpty() || resolvedUrl.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (action.isNotEmpty()) {
                                        Text(
                                            text = action,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White,
                                            modifier = Modifier
                                                .background(Color(0xFF007AFF), CircleShape)
                                                .padding(horizontal = 10.dp, vertical = 2.dp),
                                        )
                                    }
                                    if (resolvedUrl.isNotEmpty()) {
                                        val clipboardManager = LocalClipboardManager.current
                                        Text(
                                            text = resolvedUrl,
                                            fontSize = 12.sp,
                                            color = Color(0xFF595959),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .weight(1f)
                                                .background(Color(0xFFD9D9D9), CircleShape)
                                                .clip(CircleShape)
                                                .clickable { clipboardManager.setText(AnnotatedString(resolvedUrl)) }
                                                .padding(horizontal = 10.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }

                            if (screenshotBitmap != null) {
                                Spacer(modifier = Modifier.height(12.dp))
                                val aspect = screenshotBitmap.width.toFloat() /
                                    screenshotBitmap.height.coerceAtLeast(1)
                                Image(
                                    bitmap = screenshotBitmap.asImageBitmap(),
                                    contentDescription = "Browser screenshot",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp)
                                        .aspectRatio(aspect)
                                        .shadow(8.dp, RoundedCornerShape(10.dp))
                                        .clip(RoundedCornerShape(10.dp)),
                                )
                            } else if (isLive) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp)
                                        .height(200.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(ChatColors.secondaryBg),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp,
                                            color = ChatColors.link,
                                        )
                                        Text("Loading...", fontSize = 12.sp, color = ChatColors.tertiaryText)
                                    }
                                }
                            }

                            if (block.content.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp)
                                        .background(ChatColors.toolBg, RoundedCornerShape(10.dp))
                                        .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(10.dp)),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Default.Language,
                                            contentDescription = null,
                                            tint = ChatColors.tertiaryText,
                                            modifier = Modifier.size(12.dp),
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Result",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = ChatColors.secondaryText,
                                        )
                                    }
                                    HorizontalDivider(thickness = 0.5.dp, color = ChatColors.separator)
                                    Text(
                                        text = block.content,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = ChatColors.primaryText,
                                        lineHeight = 16.sp,
                                        modifier = Modifier.padding(14.dp),
                                    )
                                }
                            }
                        }
                    }

                    "memory_write", "memory_get" -> {
                        val memContent = if (block.toolName == "memory_write") {
                            toolArgsObj.optString("content", "")
                                .ifEmpty { extractPartialJsonString("content", block.toolArgs) ?: "" }
                                .ifEmpty { block.content }
                        } else {
                            block.content
                        }
                        val keywords = toolArgsObj.optString("keywords", "")
                            .ifEmpty { extractPartialJsonString("keywords", block.toolArgs) ?: "" }
                        val prefix = if (keywords.isNotEmpty() && block.toolName == "memory_get")
                            "Keywords: $keywords\n\n" else ""
                        EditorCard(
                            title = block.toolName,
                            icon = Icons.Default.Psychology,
                            iconTint = ToolMemoryAccent.copy(alpha = 0.6f),
                            titleColor = ToolMemoryAccent,
                            sizeColor = ToolMemoryAccent.copy(alpha = 0.5f),
                            bodyText = prefix + memContent,
                            bodyColor = ToolMemoryAccent.copy(alpha = 0.85f),
                            isStreaming = isLive,
                            scrollState = outputScrollState,
                        )
                    }

                    "read_image" -> {
                        val imgPath = block.imageFilePath
                        val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(
                            initialValue = null,
                            imgPath,
                        ) {
                            value = if (imgPath == null) null else withContext(Dispatchers.IO) {
                                val f = File(imgPath)
                                if (!f.exists()) null
                                else DisplayBitmapLimits.decodeFileBounded(f.absolutePath)
                            }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(ChatColors.background)
                                .verticalScroll(outputScrollState)
                                .padding(16.dp),
                        ) {
                            val bmp = bitmap
                            if (bmp != null) {
                                val aspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(aspect)
                                        .clip(RoundedCornerShape(10.dp))
                                        .border(0.5.dp, ChatColors.separator, RoundedCornerShape(10.dp)),
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                            if (block.content.isNotEmpty()) {
                                Text(
                                    text = block.content,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = ChatColors.primaryText,
                                    lineHeight = 18.sp,
                                )
                            }
                        }
                    }

                    else -> {
                        if (block.content.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(ChatColors.background)
                                    .verticalScroll(outputScrollState)
                                    .padding(16.dp),
                            ) {
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    LazyRevealToolText(
                                        bodyText = block.content,
                                        color = ChatColors.primaryText,
                                        scrollState = outputScrollState,
                                        linkify = true,
                                    )
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        toolIconFor(block.toolName),
                                        contentDescription = null,
                                        tint = toolAccent.copy(alpha = 0.3f),
                                        modifier = Modifier.size(40.dp),
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = if (isLive) "Running..." else "No output",
                                        color = ChatColors.tertiaryText,
                                        fontSize = 14.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ChatColors.background)
                    .navigationBarsPadding(),
            ) {
                HorizontalDivider(thickness = 0.5.dp, color = ChatColors.separator)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isLive) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = toolAccent,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        val (icon, tint) = when (block.toolStatus) {
                            ToolBlockStatus.SUCCESS -> Icons.Default.CheckCircle to ToolCheckColor
                            ToolBlockStatus.FAILED -> Icons.Default.Error to ToolErrorColor
                            ToolBlockStatus.CANCELLED -> Icons.Default.Cancel to ToolCancelColor
                            ToolBlockStatus.TIMEOUT -> Icons.Default.Schedule to ToolErrorColor
                            else -> Icons.Default.CheckCircle to ToolCheckColor
                        }
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = toolTitleLabel(block.toolName),
                            fontSize = 14.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ChatColors.primaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = displayToolTitle(block),
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            color = ChatColors.secondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    val shownDurationMs = if (com.openminis.app.agent.jobs.HelperRunner.isSubAgentToolName(block.toolName)) {
                        runCatching { org.json.JSONObject(block.content).optLong("elapsed_s", -1L) }.getOrDefault(-1L)
                            .takeIf { it >= 0 }?.let { it * 1000L } ?: block.durationMs
                    } else block.durationMs
                    if ((shownDurationMs > 0 && !isLive) || block.startTimeMs > 0L) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            if (shownDurationMs > 0 && !isLive) {
                                Text(
                                    text = formatToolDuration(shownDurationMs),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = ChatColors.tertiaryText,
                                )
                            }
                            if (block.startTimeMs > 0L) {
                                Text(
                                    text = formatStepTimestamp(block.startTimeMs),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = ChatColors.tertiaryText.copy(alpha = 0.75f),
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { if (currentIdx > 0) currentIdx-- },
                        enabled = currentIdx > 0,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            tint = if (currentIdx > 0) ChatColors.primaryText else ChatColors.disabledText,
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    if (isLive) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(Color(0xFF34C759), CircleShape),
                            )
                            Text(
                                "Live",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = ChatColors.primaryText,
                            )
                        }
                    } else {
                        Text(
                            "${currentIdx + 1} / ${toolBlocks.size}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace,
                            color = ChatColors.secondaryText,
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    IconButton(
                        onClick = { if (currentIdx < toolBlocks.lastIndex) currentIdx++ },
                        enabled = currentIdx < toolBlocks.lastIndex,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = if (currentIdx < toolBlocks.lastIndex) ChatColors.primaryText else ChatColors.disabledText,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

internal fun extractShellCommand(args: org.json.JSONObject, block: AssistantBlock): String {
    val fromArgs = args.optString("command", "")
    if (fromArgs.isNotEmpty()) return fromArgs
    val partial = extractPartialJsonString("command", block.toolArgs)
    if (!partial.isNullOrEmpty()) return partial
    if (block.content.startsWith("$ ")) {
        val firstLine = block.content.lineSequence().firstOrNull() ?: ""
        if (firstLine.length > 2) return firstLine.drop(2)
    }
    return "Shell command"
}

internal fun extractPartialJsonString(key: String, json: String): String? {
    if (json.isEmpty()) return null
    val patterns = listOf("\"$key\": \"", "\"$key\":\"")
    for (p in patterns) {
        val at = json.indexOf(p)
        if (at < 0) continue
        val after = json.substring(at + p.length)
        var i = 0
        val n = after.length
        while (i < n) {
            val c = after[i]
            if (c == '\\') { i += 2; continue }
            if (c == '"') {
                return after.substring(0, i)
                    .replace("\\n", "\n").replace("\\t", "\t")
                    .replace("\\\"", "\"").replace("\\/", "/")
                    .replace("\\\\", "\\")
            }
            i++
        }
        return after.replace("\\n", "\n").replace("\\t", "\t")
            .replace("\\\"", "\"").replace("\\/", "/")
            .replace("\\\\", "\\")
    }
    return null
}



private const val LAZY_TOOL_CHUNK_LINES = 40
private const val LAZY_TOOL_INITIAL_CHUNKS = 5
private const val LAZY_TOOL_BATCH_CHUNKS = 5
private const val LAZY_TOOL_INITIAL_BYTE_CAP = 10 * 1024

private fun chunkToolOutput(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val lines = text.split("\n")
    val out = ArrayList<String>((lines.size / LAZY_TOOL_CHUNK_LINES) + 1)
    var i = 0
    while (i < lines.size) {
        val end = minOf(i + LAZY_TOOL_CHUNK_LINES, lines.size)
        out.add(lines.subList(i, end).joinToString("\n"))
        i = end
    }
    return out
}

private fun initialRevealChunks(chunks: List<String>): Int {
    if (chunks.isEmpty()) return 0
    var count = 0
    var bytes = 0
    for (chunk in chunks.take(LAZY_TOOL_INITIAL_CHUNKS)) {
        bytes += chunk.toByteArray(Charsets.UTF_8).size
        count++
        if (bytes >= LAZY_TOOL_INITIAL_BYTE_CAP) break
    }
    return count.coerceAtLeast(1)
}

@Composable
private fun LazyRevealToolText(
    bodyText: String,
    color: Color,
    scrollState: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier,
    linkify: Boolean = false,
) {
    val chunks = remember(bodyText) { chunkToolOutput(bodyText) }
    var revealed by remember(bodyText) { mutableStateOf(initialRevealChunks(chunks)) }
    val total = chunks.size
    val shownText = remember(bodyText, revealed) {
        chunks.take(revealed.coerceIn(1, total.coerceAtLeast(1))).joinToString("\n")
    }
    val urlClick = LocalMarkdownUrlClickHandler.current
    val displayed = remember(shownText, urlClick, linkify) {
        if (linkify && urlClick != null) {
            com.openminis.app.ui.util.linkifyUrls(text = shownText, onClick = urlClick)
        } else androidx.compose.ui.text.AnnotatedString(shownText)
    }

    val nearBottom by remember {
        derivedStateOf {
            val max = scrollState.maxValue
            max > 0 && max != Int.MAX_VALUE && scrollState.value >= max - 600
        }
    }
    LaunchedEffect(nearBottom, revealed, total) {
        if (nearBottom && revealed < total) {
            revealed = (revealed + LAZY_TOOL_BATCH_CHUNKS).coerceAtMost(total)
        }
    }

    Column(modifier = modifier) {
        Text(
            text = displayed,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = color,
            lineHeight = 18.sp,
            modifier = Modifier.fillMaxWidth(),
        )
        if (revealed < total) {
            val remainingChunks = total - revealed
            val nextLines = minOf(LAZY_TOOL_BATCH_CHUNKS, remainingChunks) * LAZY_TOOL_CHUNK_LINES
            val remainingLines = remainingChunks * LAZY_TOOL_CHUNK_LINES
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Load more ($nextLines lines)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = color.copy(alpha = 0.9f),
                    modifier = Modifier.clickable {
                        revealed = (revealed + LAZY_TOOL_BATCH_CHUNKS).coerceAtMost(total)
                    },
                )
                Text(
                    text = "Load all (~$remainingLines)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = color.copy(alpha = 0.9f),
                    modifier = Modifier.clickable { revealed = total },
                )
            }
        }
    }
}

@Composable
private fun EditorCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    titleColor: Color,
    sizeColor: Color,
    bodyText: String,
    bodyColor: Color,
    isStreaming: Boolean,
    scrollState: androidx.compose.foundation.ScrollState,
    trailingText: String? = null,
) {
    val bytes = bodyText.toByteArray(Charsets.UTF_8).size
    val sizeLabel = when {
        bodyText.isEmpty() -> null
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
    val isDark = ChatColors.isDark
    val cardBg = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF0F0F0)
    val headerBg = if (isDark) Color(0xFF212121) else Color(0xFFEBEBEB)
    val cardBorder = if (isDark) Color(0xFF404040) else Color(0xFFD1D1D1)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(cardBg)
                .border(0.5.dp, cardBorder, RoundedCornerShape(10.dp)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBg)
                    .padding(vertical = 10.dp, horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(14.dp))
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (sizeLabel != null) {
                    Text(
                        text = if (isStreaming) "($sizeLabel received)" else "($sizeLabel)",
                        fontSize = 11.sp,
                        color = if (isStreaming) Color(0xFFFF9500).copy(alpha = 0.8f) else sizeColor,
                    )
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = cardBorder)

            if (bodyText.isNotEmpty()) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    LazyRevealToolText(
                        bodyText = bodyText,
                        color = bodyColor,
                        scrollState = scrollState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                    )
                }
            } else if (isStreaming) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = iconTint,
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
        if (trailingText != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = trailingText,
                fontSize = 12.sp,
                color = ChatColors.tertiaryText,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
