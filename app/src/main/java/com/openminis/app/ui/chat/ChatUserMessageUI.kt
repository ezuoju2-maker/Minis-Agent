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
import androidx.compose.material.icons.outlined.IosShare
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
import androidx.compose.material.icons.filled.Compress
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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


@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun UserMessageBubble(
    message: ChatMessage,
    precededByUser: Boolean = false,
    onCopy: () -> Unit = {},
    onRetry: (() -> Unit)? = {},
    onEdit: (() -> Unit)? = null,
    onDeleteFromHere: (() -> Unit)? = null,
    onCompactAbove: (() -> Unit)? = null,
    onWithdraw: (() -> Unit)? = null,
    onPreviewFile: (Uri, String) -> Unit = { _, _ -> },
    onShareChat: (() -> Unit)? = null,
    onSearchInChat: (() -> Unit)? = null,
) {
    var showMenu by remember { mutableStateOf(false) }
    val isQueued = message.isQueued
    val haptics = LocalHapticFeedback.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (precededByUser) 14.dp else 10.dp, bottom = 4.dp),
    ) {
        val bubbleMaxWidth = this.maxWidth * 0.8f
    Row(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Spacer(modifier = Modifier.weight(1f).widthIn(min = 60.dp))
        Box(
            modifier = Modifier
                .widthIn(max = bubbleMaxWidth)
                .pointerInput(message.id) {
                    detectTapGestures(
                        onLongPress = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            showMenu = true
                        }
                    )
                }
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val hasAttachments = message.imageUris.isNotEmpty() || message.attachmentNames.isNotEmpty()
                if (hasAttachments) {
                    UserAttachmentList(
                        imageUris = message.imageUris,
                        allFileNames = message.attachmentNames,
                        nonImageUris = message.attachmentUris,
                        onPreviewFile = onPreviewFile,
                    )
                }

                if (message.content.isNotBlank()) {
                    val secondaryTextColor = ChatColors.secondaryText
                    val userBubbleColor = ChatColors.userBubble
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val textColor = if (isQueued) secondaryTextColor else MaterialTheme.colorScheme.onSurface
                        val bubbleBg = if (isQueued) Color.Transparent else userBubbleColor
                        val shape = RoundedCornerShape(18.dp)
                        val dashedStroke = if (isQueued) {
                            Modifier.drawBehind {
                                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = 1.5.dp.toPx(),
                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                        floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f
                                    ),
                                )
                                val r = 18.dp.toPx()
                                drawRoundRect(
                                    color = secondaryTextColor.copy(alpha = 0.5f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                                    style = stroke,
                                )
                            }
                        } else Modifier
                        androidx.compose.foundation.text.selection.DisableSelection {
                            val bubbleModifier = if (isQueued && onWithdraw != null) {
                                Modifier.weight(1f, fill = false)
                            } else Modifier
                            LargeContentGuard(
                                content = message.content,
                                isStreaming = false,
                                stableKey = "user:${message.id}",
                            ) {
                                Text(
                                    text = com.openminis.app.ui.chat.search.rememberChatSearchHighlighted(message.content, com.openminis.app.ui.chat.search.ChatSearchUnits.BODY),
                                    color = textColor,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.5.sp),
                                    modifier = bubbleModifier
                                        .background(bubbleBg, shape)
                                        .clip(shape)
                                        .then(dashedStroke)
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                        if (isQueued && onWithdraw != null) {
                            IconButton(
                                onClick = onWithdraw,
                                modifier = Modifier.size(44.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Cancel,
                                    contentDescription = "Withdraw queued message",
                                    tint = Color(0xFFFF3B30),
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }
            }

            val userMenuWidthDp = minOf(220, LocalConfiguration.current.screenWidthDp).dp
            MinisMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                offset = androidx.compose.ui.unit.DpOffset(0.dp, 6.dp),
                alignEnd = true,
                modifier = Modifier.widthIn(max = userMenuWidthDp),
                minWidth = userMenuWidthDp,
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_longpress_copy)) },
                    onClick = { showMenu = false; onCopy() },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                if (onRetry != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_longpress_retry)) },
                        onClick = { showMenu = false; onRetry() },
                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
                if (onEdit != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_longpress_edit)) },
                        onClick = { showMenu = false; onEdit() },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
                if (onSearchInChat != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_search_in_chat)) },
                        onClick = { showMenu = false; onSearchInChat() },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
                if (onShareChat != null) {
                    MinisMenuDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_share_share_chat)) },
                        onClick = { showMenu = false; onShareChat() },
                        leadingIcon = { Icon(Icons.Outlined.IosShare, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                    if (onDeleteFromHere != null || onCompactAbove != null) MinisMenuDivider()
                }
                if (onDeleteFromHere != null) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.chat_longpress_delete_from_here),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = { showMenu = false; onDeleteFromHere() },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
                if (onCompactAbove != null) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.chat_longpress_compact_above),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = { showMenu = false; onCompactAbove() },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Compress,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }
        }
    }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UserAttachmentList(
    imageUris: List<Uri>,
    allFileNames: List<String>,
    nonImageUris: List<Uri> = emptyList(),
    onPreviewFile: (Uri, String) -> Unit = { _, _ -> },
) {
    var previewImageIndex by remember { mutableStateOf<Int?>(null) }

    val imageCount = imageUris.size
    val fileNames = allFileNames.drop(imageCount)

    val tileSize = 64.dp

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        imageUris.forEachIndexed { idx, uri ->
            AsyncImage(
                model = uri,
                contentDescription = "Image attachment",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(tileSize)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ChatColors.secondaryBg)
                    .border(0.5.dp, ChatColors.thumbnailBorder, RoundedCornerShape(8.dp))
                    .clickable { previewImageIndex = idx },
            )
        }

        fileNames.forEachIndexed { idx, name ->
            val uri = nonImageUris.getOrNull(idx)
            FileAttachmentTile(
                fileName = name,
                searchIndex = idx,
                tileSize = tileSize,
                onClick = {
                    if (uri != null) onPreviewFile(uri, name)
                },
            )
        }
    }

    previewImageIndex?.let { startIdx ->
        com.openminis.app.ui.components.ImageGalleryViewer(
            items = imageUris.mapIndexed { i, uri ->
                com.openminis.app.ui.components.ImageGalleryItem(
                    model = uri,
                    caption = allFileNames.getOrNull(i),
                )
            },
            startIndex = startIdx,
            onDismiss = { previewImageIndex = null },
        )
    }
}

@Composable
private fun FileAttachmentTile(
    fileName: String,
    searchIndex: Int = -1,
    tileSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .size(tileSize)
            .clip(RoundedCornerShape(8.dp))
            .background(ChatColors.secondaryBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = fileIconFor(fileName),
            contentDescription = null,
            tint = ChatColors.secondaryText,
            modifier = Modifier
                .size(20.dp)
                .weight(1f, fill = false),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = com.openminis.app.ui.chat.search.rememberChatSearchHighlighted(fileName, com.openminis.app.ui.chat.search.ChatSearchUnits.attachment(searchIndex)),
            fontSize = 9.sp,
            lineHeight = 11.sp,
            color = ChatColors.primaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

private fun fileIconFor(fileName: String): androidx.compose.ui.graphics.vector.ImageVector {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "pdf" -> Icons.Default.PictureAsPdf
        "doc", "docx", "pages" -> Icons.AutoMirrored.Filled.Article
        "txt", "log", "csv", "md", "markdown" -> Icons.AutoMirrored.Filled.Article
        "mp4", "mov", "avi", "mkv" -> Icons.Default.VideoFile
        "mp3", "wav", "m4a", "aac" -> Icons.Default.AudioFile
        "zip", "tar", "gz", "7z" -> Icons.Default.FolderZip
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageGalleryDialog(
    uris: List<Uri>,
    startIndex: Int,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val pagerState = androidx.compose.foundation.pager.rememberPagerState(
            initialPage = startIndex.coerceIn(0, (uris.size - 1).coerceAtLeast(0)),
            pageCount = { uris.size },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                ),
        ) {
            androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                AsyncImage(
                    model = uris[page],
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Icon(
                    androidx.compose.material.icons.Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                )
            }
            if (uris.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${uris.size}",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}
