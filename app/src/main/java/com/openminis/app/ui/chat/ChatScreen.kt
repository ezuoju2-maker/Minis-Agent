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
import androidx.compose.animation.Crossfade
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.VerticalAlignTop
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
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ripple
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import com.openminis.app.ui.settings.SettingsSwitch
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisMenu
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Delete
import com.openminis.app.ui.components.MinisIcons
import com.openminis.app.ui.components.MenuItemIcon
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Forum
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
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.browser.BrowserSheet
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.safeLaunchPicker

internal val ToolCheckColor = Color(0xFF34C759)
internal val ToolErrorColor = Color(0xFFFF3B30)
internal val ToolCancelColor = Color(0xFFFFCC00)
internal val ToolMemoryAccent = Color(0xFFFF2D55)
internal val SparkleColor1 = Color(0xFFB8B096)
internal val SparkleColor2 = Color(0xFF99998C)

private const val ATTACHMENT_PICK_LIMIT = 50

private const val SEND_FOLLOW_GRACE_MS = 2_000L

private const val STREAM_END_ARM_GRACE_MS = 1_500L


private val SLASH_PICKER_FIXED_HEIGHT: Dp = 176.dp

private val SLASH_PICKER_MAX_HEIGHT: Dp = 280.dp

private val CHAT_MAX_CONTENT_WIDTH = 900.dp

@Composable
private fun slashPickerHeight(
    titleSp: TextUnit = 14.sp,
    subtitleSp: TextUnit = 11.sp,
    verticalPadding: Dp = 7.dp,
    iconSize: Dp = 18.dp,
): Dp {
    val fontScale = LocalDensity.current.fontScale
    if (fontScale <= 1f) return SLASH_PICKER_FIXED_HEIGHT

    val density = LocalDensity.current
    return with(density) {
        val textHeight = (titleSp.toDp() + subtitleSp.toDp()) * 1.2f
        val rowHeight = maxOf(textHeight, iconSize) + verticalPadding * 2
        (rowHeight * SLASH_PICKER_VISIBLE_ROWS + 8.dp)
            .coerceIn(SLASH_PICKER_FIXED_HEIGHT, SLASH_PICKER_MAX_HEIGHT)
    }
}

private const val MAX_FAB_UP_SEEK_PROBES = 12

private const val SLASH_PICKER_VISIBLE_ROWS = 4

private fun Modifier.verticalScrollbar(
    listState: androidx.compose.foundation.lazy.LazyListState,
    width: Dp = 3.dp,
    color: Color = Color(0x55888888),
): Modifier = this.then(Modifier.drawWithContent {
    drawContent()
    val layoutInfo = listState.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo
    if (totalItems == 0 || visibleItems.isEmpty()) return@drawWithContent
    if (visibleItems.size >= totalItems &&
        visibleItems.first().index == 0 &&
        visibleItems.last().index == totalItems - 1 &&
        visibleItems.first().offset >= 0
    ) {
        return@drawWithContent
    }
    val firstIndex = visibleItems.first().index
    val firstOffsetPx = visibleItems.first().offset.toFloat()
    val avgItemSize = visibleItems.sumOf { it.size }.toFloat() / visibleItems.size
    val totalContentPx = avgItemSize * totalItems
    val viewportHeight = this.size.height
    if (totalContentPx <= viewportHeight || avgItemSize <= 0f) return@drawWithContent
    val scrollOffsetPx = firstIndex * avgItemSize - firstOffsetPx
    val thumbHeight = (viewportHeight * (viewportHeight / totalContentPx)).coerceAtLeast(24f)
    val maxScroll = (totalContentPx - viewportHeight).coerceAtLeast(1f)
    val maxTop = (viewportHeight - thumbHeight).coerceAtLeast(0f)
    val thumbTop = (scrollOffsetPx / maxScroll * maxTop).coerceIn(0f, maxTop)
    val widthPx = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(this.size.width - widthPx - 1f, thumbTop),
        size = androidx.compose.ui.geometry.Size(widthPx, thumbHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(widthPx / 2, widthPx / 2),
    )
})

private data class ScrollFollowKey(
    val lastIndex: Int,
    val growth: Long,
    val toolBlockCount: Int,
    val awaiting: Boolean,
    val blockSig: Long,
)

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    sessionId: String,
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    memoryRepository: MemoryRepository? = null,
    skillRepository: com.openminis.app.data.repository.SkillRepository? = null,
    mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
    onBack: () -> Unit,
    isTwoPane: Boolean = false,
    onToggleSidebar: (() -> Unit)? = null,
    sidebarCollapsed: Boolean = false,
    onNewChat: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenTerminalWithCommand: (command: String) -> Unit = {},
    onOpenAgentTranscript: (childSessionId: String) -> Unit = {},
    onMoveToSession: (sessionId: String) -> Unit = {},
    onBrowseChatFiles: () -> Unit = {},
    onBrowseChatFolder: (String) -> Unit = {},
    onPreviewAttachment: (com.openminis.app.ui.sandbox.FileItem) -> Unit = {},
    onModelGroupsClick: () -> Unit = {},
    onEditProviderClick: (instanceId: String) -> Unit = {},
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val viewModel: ChatViewModel = viewModel(
        viewModelStoreOwner = ChatViewModelStore.ownerFor(sessionId),
        factory = ChatViewModel.factory(
            sessionId = sessionId,
            chatRepository = chatRepository,
            providerRepository = providerRepository,
            appContext = context.applicationContext,
            memoryRepository = memoryRepository,
            skillRepository = skillRepository,
            mcpRepository = mcpRepository,
        ),
    )
    val unreadLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(viewModel, unreadLifecycleOwner) {
        val token = Any()
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME ->
                    com.openminis.app.service.SessionUnreadStore.startViewing(token) { viewModel.currentSessionId }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE ->
                    com.openminis.app.service.SessionUnreadStore.stopViewing(token)
                else -> Unit
            }
        }
        unreadLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            unreadLifecycleOwner.lifecycle.removeObserver(observer)
            com.openminis.app.service.SessionUnreadStore.stopViewing(token)
        }
    }
    val messages by viewModel.uiMessages.collectAsState()
    val revealedUsageIds by viewModel.revealedUsageIds.collectAsState()
    val hasOlderMessages by viewModel.hasOlderMessages.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    LaunchedEffect(isStreaming) { com.openminis.app.diagnostics.JankDiag.setStreaming(isStreaming) }
    DisposableEffect(sessionId) {
        com.openminis.app.diagnostics.JankDiag.enterScreen("chat")
        onDispose { com.openminis.app.diagnostics.JankDiag.leaveScreen("chat") }
    }
    val canResume by viewModel.canResume.collectAsState()
    val contextUsage by viewModel.contextUsage.collectAsState()
    val contextUsageHint by viewModel.contextUsageHint.collectAsState()
    val compactProgress by viewModel.compactProgress.collectAsState()
    val error by viewModel.error.collectAsState()
    val modelName by viewModel.modelName.collectAsState()
    val sessionTitle by viewModel.sessionTitle.collectAsState()
    val sessionCategory by viewModel.sessionCategory.collectAsState()
    val attachments by viewModel.attachments.collectAsState()
    val pastedTexts by viewModel.pastedTexts.collectAsState()
    val availableGroups by viewModel.availableGroups.collectAsState()
    val selectedGroupId by viewModel.selectedGroupId.collectAsState()
    val showBrowserSheet by viewModel.showBrowserSheet.collectAsState()
    val showMemorySheet by viewModel.showMemorySheet.collectAsState()
    val memoryToolRecords by viewModel.memoryToolRecords.collectAsState()
    val selectedGroupName by viewModel.selectedGroupName.collectAsState()
    val providerName by viewModel.providerName.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val ensureMicPermissionFlow: suspend () -> Boolean = ensure@{
        val perm = android.Manifest.permission.RECORD_AUDIO
        val hasPerm: () -> Boolean = {
            androidx.core.content.ContextCompat.checkSelfPermission(context, perm) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (hasPerm()) return@ensure true
        var result = com.openminis.app.offload.OffloadPermissionManager
            .requestAndroidPermission(listOf(perm))
        if (result == com.openminis.app.offload.OffloadPermissionManager
                .AndroidPermissionResult.DENIED &&
            com.openminis.app.offload.OffloadPermissionManager.pollForPermissionGrant(hasPerm)
        ) {
            result = com.openminis.app.offload.OffloadPermissionManager
                .AndroidPermissionResult.GRANTED
        }
        if (result == com.openminis.app.offload.OffloadPermissionManager
                .AndroidPermissionResult.DENIED
        ) {
            result = com.openminis.app.offload.OffloadPermissionManager.requestSettingsGate(
                com.openminis.app.offload.OffloadPermissionManager.SettingsGateRequest(
                    id = perm,
                    title = context.getString(R.string.mic_permission_title),
                    message = context.getString(R.string.mic_permission_message),
                    settingsAction = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    requiresPackageUri = true,
                    positiveLabel = context.getString(R.string.mic_permission_open_settings),
                    negativeLabel = context.getString(R.string.mic_permission_cancel),
                ),
                check = hasPerm,
            )
        }
        result == com.openminis.app.offload.OffloadPermissionManager.AndroidPermissionResult.GRANTED
    }
    val listState = viewModel.listState
    val toggleAnchorScope = rememberCoroutineScope()
    val toggleAnchor = remember(listState) { ChatToggleAnchor(listState) }
    toggleAnchor.scope = toggleAnchorScope
    val voiceScreenItems = remember(sessionId) { VoiceScreenItemsHolder() }
    val inputText by viewModel.inputText.collectAsState()

    val shareBufferVersion by com.openminis.app.share.ShareCoordinator.bufferVersion.collectAsState()
    androidx.compose.runtime.LaunchedEffect(shareBufferVersion) {
        if (shareBufferVersion == 0) return@LaunchedEffect
        val pending = com.openminis.app.share.ShareCoordinator.consumeBuffer(context, sessionId)
            ?: return@LaunchedEffect
        com.openminis.app.logging.AppLogger.info(
            "ChatScreen",
            "[Share] injecting ${pending.items.size} item(s) into chat session=$sessionId",
        )
        val sharedDir = com.openminis.app.share.SharedShareStore.sharedFileDirectory(context)
        var draft = inputText
        for (item in pending.items) {
            when (item.kind) {
                com.openminis.app.share.PendingShare.Item.Kind.INLINE_TEXT -> {
                    val sep = if (draft.isNotEmpty()) "\n" else ""
                    val needsTrailingSpace = item.value.startsWith("http://") ||
                        item.value.startsWith("https://")
                    draft = draft + sep + item.value +
                        if (needsTrailingSpace) " " else ""
                    viewModel.setInputText(draft)
                }
                com.openminis.app.share.PendingShare.Item.Kind.ATTACHMENT -> {
                    viewModel.addAttachmentFromStagedShare(java.io.File(sharedDir, item.value))
                }
            }
        }
        com.openminis.app.logging.AppLogger.info(
            "XSessionDiag",
            "[XSessionDiag] share/injected: session=${sessionId.take(8)} " +
                "items=${pending.items.size} draftLen=${draft.length} " +
                "draftHead=\"${draft.take(200).replace("\n", "\\n")}\"",
        )
        viewModel.markShareInjected()
        com.openminis.app.share.SharedShareStore.cleanSharedFiles(context)
        val isEmptyChat = viewModel.messages.value.isEmpty()
        val title = viewModel.sessionTitle.value.takeIf {
            it.isNotBlank() && it != ChatViewModel.UNTITLED_SESSION_TITLE
        }
        android.widget.Toast.makeText(
            context,
            when {
                isEmptyChat -> context.getString(com.openminis.app.R.string.share_added_new_chat)
                title != null -> context.getString(com.openminis.app.R.string.share_added_to_chat, title)
                else -> context.getString(com.openminis.app.R.string.share_added_this_chat)
            },
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    val tHangDiagAppContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    androidx.compose.runtime.DisposableEffect(sessionId) {
        ChatViewModelStore.setActiveSession(sessionId)
        ChatViewModelStore.unparkDraft(sessionId)
        viewModel.healStaleInFlightTools("screen-open")
        println(
            "[T-HANG-DIAG] ChatScreen MOUNT session=$sessionId hangCount=" +
                com.openminis.app.diagnostics.HangDetector.currentHangCount(tHangDiagAppContext),
        )
        com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "chatScreen.mount")
        onDispose {
            println("[T-HANG-DIAG] ChatScreen UNMOUNT session=$sessionId")
            ChatViewModelStore.clearActiveSession(sessionId)
            ChatViewModelStore.parkDraftIfUnsent(sessionId, viewModel.hasUnsentDraft)
            val activity = context as? android.app.Activity
            if (activity?.isChangingConfigurations != true) {
                viewModel.cleanupIfEmptyOnExit()
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(sessionId) {
        kotlinx.coroutines.delay(10_000)
        com.openminis.app.diagnostics.HangDetector.markHealthyTick()
    }

    androidx.compose.runtime.LaunchedEffect(sessionId) {
        val transfer = ChatViewModelStore.consumePendingTransfer(sessionId) ?: return@LaunchedEffect
        com.openminis.app.logging.AppLogger.info(
            "ChatScreen",
            "[MoveTo] draining transfer into session=$sessionId text=${transfer.inputText.length}ch attachments=${transfer.attachments.size}",
        )
        viewModel.clearAttachments()
        if (transfer.inputText.isNotEmpty()) {
            val sep = if (inputText.isNotEmpty()) "\n" else ""
            viewModel.setInputText(inputText + sep + transfer.inputText)
        }
        for (a in transfer.attachments) viewModel.addAttachment(a)
    }

    var inputFieldValue by remember {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))
    }
    var lastSendTimeMs by remember { mutableStateOf(0L) }
    var voiceUsedSinceClear by remember { mutableStateOf(false) }
    val noteSendForInputModePref: () -> Unit = {
        ComposerInputModePrefs.save(context, voice = voiceUsedSinceClear)
        voiceUsedSinceClear = false
        com.openminis.app.speech.correction.VoiceCorrection.mineVocabularyIfNeeded(context)
    }
    var lastUserAppendMs by remember { mutableStateOf(0L) }
    var lastStreamEndMs by remember { mutableStateOf(0L) }
    var followLastDistPx by remember { mutableStateOf(0f) }
    var followLastGrowMs by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(inputText) {
        if (inputFieldValue.text != inputText) {
            val caret = viewModel.consumePendingCaret()?.coerceIn(0, inputText.length)
                ?: inputText.length
            inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                text = inputText,
                selection = androidx.compose.ui.text.TextRange(caret),
                composition = null,
            )
        }
    }
    val inputFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    var inputFocused by remember { mutableStateOf(false) }

    var sendSwipeProgress by remember { mutableStateOf(0f) }
    var sendSwipeLocation by remember { mutableStateOf(Offset.Zero) }
    val swipeThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }
    val swipeArmFraction = 0.8f
    val swipeHapticOffsetPx = with(LocalDensity.current) { 60.dp.toPx() }
    val swipeArrowHalfPx = with(LocalDensity.current) { 17.dp.toPx() }
    val swipeHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    val coroutineScope = rememberCoroutineScope()

    var showModelPicker by remember { mutableStateOf(false) }
    val reopenModelPickerOnResume = com.openminis.app.ui.components.rememberReopenOnResume {
        showModelPicker = true
    }
    var modelPickerInteractionGeneration by remember { mutableIntStateOf(0) }
    val modelPickerInteraction = remember(modelPickerInteractionGeneration) {
        MutableInteractionSource()
    }
    LaunchedEffect(showModelPicker) {
        if (!showModelPicker) modelPickerInteractionGeneration++
    }
    var showThinkingLevelSheet by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showChatMenu by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showSkillsSheet by remember { mutableStateOf(false) }
    var showMcpsSheet by remember { mutableStateOf(false) }
    var showTokenUsageSheet by remember { mutableStateOf(false) }
    var showMoveSheet by remember { mutableStateOf(false) }
    var showClearChatDialog by remember { mutableStateOf(false) }
    var deleteFromHereTargetId by remember { mutableStateOf<String?>(null) }
    var compactAboveTargetId by remember { mutableStateOf<String?>(null) }
    var showNewChatStopDialog by remember { mutableStateOf(false) }
    var shareRequest by remember { mutableStateOf<com.openminis.app.ui.chat.share.ChatShareRequest?>(null) }
    val newChatTitle = stringResource(R.string.new_chat)
    val openSharePicker: (String?) -> Unit = { messageId ->
        shareRequest = com.openminis.app.ui.chat.share.buildChatShareRequest(viewModel, messageId, newChatTitle, context)
    }
    var showEnhancedCacheDialog by remember { mutableStateOf(false) }

    val clearChatRequested by viewModel.clearChatConfirmRequested.collectAsState()
    LaunchedEffect(clearChatRequested) {
        if (clearChatRequested) {
            showClearChatDialog = true
            viewModel.ackClearChatConfirmRequest()
        }
    }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(
            maxItems = ATTACHMENT_PICK_LIMIT,
        ),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val limited = uris.take(ATTACHMENT_PICK_LIMIT)
        for (uri in limited) {
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            val isVideo = mimeType.startsWith("video/")
            val defaultName = if (isVideo) "video.mp4" else "image.jpg"
            val fileName = getFileName(context, uri) ?: defaultName
            viewModel.addAttachment(
                InputAttachment(
                    fileName = fileName,
                    uri = uri,
                    mimeType = mimeType,
                    kind = if (isVideo) InputAttachment.Kind.DOCUMENT else InputAttachment.Kind.IMAGE,
                ),
            )
        }
        if (uris.size > ATTACHMENT_PICK_LIMIT) {
            android.widget.Toast.makeText(
                context,
                "Only the first $ATTACHMENT_PICK_LIMIT items were attached.",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    var pendingCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingCameraFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        com.openminis.app.service.SessionActivityTracker.setCameraSuppressActive(false)
        val uri = pendingCameraUri
        val file = pendingCameraFilePath?.let { java.io.File(it) }
        pendingCameraUri = null
        pendingCameraFilePath = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        val ok = file.exists() && file.length() > 0
        if (ok) {
            val fileName = getFileName(context, uri) ?: file.name
            viewModel.addAttachment(
                InputAttachment(
                    fileName = fileName,
                    uri = uri,
                    mimeType = "image/jpeg",
                    kind = InputAttachment.Kind.IMAGE,
                ),
            )
        } else {
            AppLogger.warning(
                "Camera",
                "capture failed: rc=${result.resultCode}, file=${file.name} len=${file.length()}",
            )
            file.delete()
        }
    }
    val launchCamera: () -> Unit = {
        val (uri, file) = createCameraOutputUri(context)
        pendingCameraUri = uri
        pendingCameraFilePath = file.absolutePath
        val intent = android.content.Intent(
            android.provider.MediaStore.ACTION_IMAGE_CAPTURE,
        ).apply {
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        com.openminis.app.service.SessionActivityTracker.setCameraSuppressActive(true)
        runCatching { cameraLauncher.launch(intent) }
            .onFailure {
                AppLogger.warning("Camera", "launch failed: ${it.message}")
                com.openminis.app.service.SessionActivityTracker.setCameraSuppressActive(false)
                pendingCameraUri = null
                pendingCameraFilePath = null
                file.delete()
            }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
    }

    LaunchedEffect(sessionId) {
        val pending = com.openminis.app.deeplink.DeepLinkCoordinator
            .pendingChatAction.value
        if (pending == com.openminis.app.deeplink.DeepLinkCoordinator
                .ChatAction.OPEN_CAMERA
        ) {
            com.openminis.app.deeplink.DeepLinkCoordinator
                .consumePendingChatAction()
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) launchCamera()
            else cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val limited = uris.take(ATTACHMENT_PICK_LIMIT)
        for (uri in limited) {
            val fileName = getFileName(context, uri) ?: "file"
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val kind = if (mimeType.startsWith("image/")) InputAttachment.Kind.IMAGE else InputAttachment.Kind.DOCUMENT
            viewModel.addAttachment(
                InputAttachment(fileName = fileName, uri = uri, mimeType = mimeType, kind = kind)
            )
        }
        if (uris.size > ATTACHMENT_PICK_LIMIT) {
            android.widget.Toast.makeText(
                context,
                "Only the first $ATTACHMENT_PICK_LIMIT files were attached.",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val currentPermissionsRef = remember { mutableStateOf<Array<String>>(emptyArray()) }
    val androidPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val perms = currentPermissionsRef.value
        val grantedNow = perms.isNotEmpty() && perms.any { p ->
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        }
        OffloadPermissionManager.respondToAndroidPermission(grantedNow)
    }
    val pendingAndroidPermission by OffloadPermissionManager.pendingAndroidPermission.collectAsState()
    LaunchedEffect(pendingAndroidPermission) {
        val req = pendingAndroidPermission ?: return@LaunchedEffect
        val perms = req.permissions.toTypedArray()
        val alreadyGranted = perms.isNotEmpty() && perms.any { p ->
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        }
        if (alreadyGranted) {
            OffloadPermissionManager.respondToAndroidPermission(true)
            return@LaunchedEffect
        }
        currentPermissionsRef.value = perms
        androidPermissionLauncher.launch(perms)
    }

    val tagScroll = "ChatScrollFollow"
    val tracedScrollToItem: suspend (source: String, idx: Int, off: Int) -> Unit = { source, idx, off ->
        AppLogger.debug("ScrollSrc") {
            "scrollToItem src=$source idx=$idx off=$off canBwd=${listState.canScrollBackward} firstIdx=${listState.firstVisibleItemIndex} firstOff=${listState.firstVisibleItemScrollOffset} inProgress=${listState.isScrollInProgress}"
        }
        com.openminis.app.diagnostics.StreamJitterProbe.scroll(source, idx)
        runCatching { listState.scrollToItem(idx, off) }
        Unit
    }
    val tracedScrollBy: suspend (source: String, delta: Float) -> Unit = { source, delta ->
        AppLogger.debug(
            "ScrollSrc",
            "scrollBy src=$source delta=$delta canBwd=${listState.canScrollBackward} firstIdx=${listState.firstVisibleItemIndex} firstOff=${listState.firstVisibleItemScrollOffset}",
        )
        runCatching { listState.scrollBy(delta) }
        Unit
    }
    val verboseScrollLogs = false


    val nearBottomThresholdPx = with(LocalDensity.current) { 32.dp.toPx() }
    val isNearBottom = remember(listState, nearBottomThresholdPx) {
        derivedStateOf {
            val info = listState.layoutInfo
            val bottomItem = info.visibleItemsInfo.firstOrNull { it.index == 0 }
            val viewportEnd = info.viewportEndOffset
            val itemBottom = bottomItem?.let { it.offset + it.size } ?: Int.MIN_VALUE
            val gap = viewportEnd - itemBottom
            val firstIdx = listState.firstVisibleItemIndex
            val firstOff = listState.firstVisibleItemScrollOffset
            val result = firstIdx == 0 && firstOff <= nearBottomThresholdPx.toInt()
            if (false) {
                AppLogger.debug(
                    tagScroll,
                    "isNearBottom: bottomVisible=${bottomItem != null} itemBottom=$itemBottom viewportEnd=$viewportEnd gap=$gap threshold=${nearBottomThresholdPx.toInt()} firstVisible=$firstIdx firstOffset=$firstOff canScrollForward=${listState.canScrollForward} canScrollBackward=${listState.canScrollBackward} totalItems=${info.totalItemsCount} visibleItems=${info.visibleItemsInfo.size} isScrollInProgress=${listState.isScrollInProgress} → $result",
                )
            }
            result
        }
    }
    val contentOverflows = remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val viewportSize = info.viewportEndOffset - info.viewportStartOffset
            val canScroll = listState.canScrollForward || listState.canScrollBackward
            val moreItemsThanVisible = info.totalItemsCount > info.visibleItemsInfo.size
            val visibleSum = info.visibleItemsInfo.sumOf { it.size }
            val sumExceedsViewport = visibleSum > viewportSize
            val result = canScroll || moreItemsThanVisible || sumExceedsViewport
            if (false) {
                AppLogger.debug(
                    tagScroll,
                    "contentOverflows: canScroll=$canScroll moreItems=$moreItemsThanVisible sumExceeds=$sumExceedsViewport visibleSum=$visibleSum viewport=$viewportSize total=${info.totalItemsCount} visible=${info.visibleItemsInfo.size} → $result",
                )
            }
            result
        }
    }

    val itemSizeByIndex = remember(listState) { mutableStateMapOf<Int, Int>() }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }
            .collect { vis ->
                for (it in vis) {
                    val cached = itemSizeByIndex[it.index]
                    if (cached == null || cached != it.size) itemSizeByIndex[it.index] = it.size
                }
            }
    }
    val avgItemSize = remember(listState, itemSizeByIndex) {
        derivedStateOf {
            val sizes = itemSizeByIndex.values
            if (sizes.isEmpty()) 0 else sizes.sum() / sizes.size
        }
    }

    var userScrolledAway by remember { mutableStateOf(false) }
    val readingAnchor = remember(listState) { ReadingAnchor(listState, toggleAnchor) }
    readingAnchor.readerAway = { userScrolledAway }
    readingAnchor.scope = toggleAnchorScope

    var lastJumpedUserId by remember(sessionId) { mutableStateOf<String?>(null) }

    LaunchedEffect(messages.size) { lastJumpedUserId = null }

    val flatRowIndexByKey = remember(sessionId) { mutableStateOf<Map<String, Int>>(emptyMap()) }

    val scrollToPreviousUserTurn: suspend () -> Unit = scrollToPreviousUserTurn@{
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) return@scrollToPreviousUserTurn
        val userIds = messages.filter { it.rendersAsUserBubble() }.map { it.id }
        if (userIds.isEmpty()) {
            tracedScrollToItem("FAB-UP/no-user-turns", (info.totalItemsCount - 1).coerceAtLeast(0), 0)
            return@scrollToPreviousUserTurn
        }
        val topKey = visible.minByOrNull { it.index }?.key as? String
        val topMessageId = topKey
            ?.split(':')
            ?.getOrNull(1)
            ?.takeIf { it.isNotEmpty() }
        val topMsgIdx = topMessageId
            ?.let { id -> messages.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: 0
        val currentAnchor = messages.take(topMsgIdx + 1).lastOrNull { it.rendersAsUserBubble() }?.id
            ?: userIds.first()
        val walkFrom = lastJumpedUserId?.takeIf { it in userIds } ?: currentAnchor
        val pos = userIds.indexOf(walkFrom)
        val steppedTarget = if (lastJumpedUserId == walkFrom && pos > 0) {
            userIds[pos - 1]
        } else {
            walkFrom
        }
        val viewportTop = info.viewportStartOffset
        val viewportBottom = info.viewportEndOffset
        val fullyVisibleUserIds: Set<String> = visible
            .asSequence()
            .filter { item ->
                val k = item.key as? String ?: return@filter false
                k.startsWith("user:") &&
                    item.offset >= viewportTop &&
                    item.offset + item.size <= viewportBottom
            }
            .mapNotNull { (it.key as? String)?.removePrefix("user:")?.takeIf { id -> id.isNotEmpty() } }
            .toSet()

        val target = if (steppedTarget in fullyVisibleUserIds) {
            var i = userIds.indexOf(steppedTarget)
            while (i > 0 && userIds[i] in fullyVisibleUserIds) i--
            userIds[i]
        } else {
            steppedTarget
        }
        val targetKey = "user:$target"
        fun indexOfTargetKey(): Int? =
            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == targetKey }?.index

        val restoreIndex = listState.firstVisibleItemIndex
        val restoreOffset = listState.firstVisibleItemScrollOffset
        val lastFlat = (flatRowIndexByKey.value.size - 1).coerceAtLeast(0)
        val leadingItemCount: Int? = visible.firstNotNullOfOrNull { item ->
            (item.key as? String)
                ?.let { flatRowIndexByKey.value[it] }
                ?.let { flat -> item.index - (lastFlat - flat) }
        }
        var targetIndex = indexOfTargetKey()
            ?: flatRowIndexByKey.value[targetKey]?.let { flat ->
                leadingItemCount?.let { lead -> (lastFlat - flat) + lead }
            }?.takeIf { it >= 0 }
        AppLogger.debug("ScrollSrc") {
            "FAB-UP decide anchor=${currentAnchor?.take(8)} walkFrom=${walkFrom.take(8)} " +
                "stepped=${steppedTarget.take(8)} target=${target.take(8)} " +
                "pos=$pos/${userIds.size} lastJumped=${lastJumpedUserId?.take(8)} " +
                "visibleUserIds=${fullyVisibleUserIds.size} " +
                "targetIndex=$targetIndex viaMap=${flatRowIndexByKey.value[targetKey]} " +
                "lead=$leadingItemCount lastFlat=$lastFlat mapSize=${flatRowIndexByKey.value.size} " +
                "firstVisible=${listState.firstVisibleItemIndex} total=${info.totalItemsCount}"
        }
        var guard = 0
        if (targetIndex == null) {
            val maxIdx = (info.totalItemsCount - 1).coerceAtLeast(0)
            var probe = 0
            while (targetIndex == null && probe <= maxIdx && guard++ < MAX_FAB_UP_SEEK_PROBES) {
                tracedScrollToItem("FAB-UP/seek", probe, 0)
                targetIndex = indexOfTargetKey()
                val hi = listState.layoutInfo.visibleItemsInfo.maxByOrNull { it.index }?.index
                probe = (hi ?: probe) + 1
            }
        }
        if (targetIndex == null) {
            AppLogger.debug("ScrollSrc") {
                "FAB-UP RESTORE — target=${target.take(8)} never resolved after $guard probe(s)"
            }
            tracedScrollToItem("FAB-UP/restore", restoreIndex, restoreOffset)
            return@scrollToPreviousUserTurn
        }
        AppLogger.debug("ScrollSrc") {
            "FAB-UP LAND target=${target.take(8)} row=$targetIndex probes=$guard"
        }
        lastJumpedUserId = target
        val landIndex: Int = targetIndex
        val vpH = listState.layoutInfo.viewportSize.height
        tracedScrollToItem("FAB-UP/turn-walk", landIndex, 0)
        val rowSize = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == targetKey }?.size ?: 0
        val headerInset = listState.layoutInfo.beforeContentPadding
        val topOffset = rowSize - vpH + headerInset
        tracedScrollToItem("FAB-UP/turn-walk-top", landIndex, topOffset)
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val up = !isNearBottom.value && messages.isNotEmpty()
            val down = userScrolledAway && contentOverflows.value && messages.isNotEmpty()
            "FABs up=$up down=$down | nearBottom=${isNearBottom.value} lastJumped=${lastJumpedUserId?.take(8)} scrolledAway=$userScrolledAway overflow=${contentOverflows.value} canFwd=${listState.canScrollForward} canBwd=${listState.canScrollBackward}"
        }.collect { AppLogger.debug("ScrollFAB2", it) }
    }

    val hasHardwareKeyboard = com.openminis.app.ui.components.rememberHasHardwareKeyboard()

    val releaseComposerAfterSend: () -> Unit = {
        if (!hasHardwareKeyboard) {
            keyboardController?.hide()
            focusManager.clearFocus()
        }
    }

    val performSendOrEnqueueNow: (String) -> Unit = handler@{ rawText ->
        if (viewModel.tryExecuteInputAsSlashCommand(rawText)) {
            viewModel.setInputText("")
            releaseComposerAfterSend()
            return@handler
        }
        lastSendTimeMs = System.currentTimeMillis()
        viewModel.endSlashSessionForSend()
        viewModel.setInputText("")
        releaseComposerAfterSend()
        viewModel.sendMessage(rawText)
        noteSendForInputModePref()
        userScrolledAway = false
        coroutineScope.launch {
            tracedScrollToItem("SEND-PATH/initial", 0, 0)
            kotlinx.coroutines.delay(100)
            tracedScrollToItem("SEND-PATH/settle", 0, 0)
        }
    }
    val submitAfterVoice: () -> Unit = {
        val transcript = com.openminis.app.ui.chat.voice.VoiceSendGate.transcript
        if (com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive &&
            transcript.isNotEmpty() && viewModel.inputText.value != transcript
        ) {
            viewModel.setInputText(transcript)
            viewModel.updateSlashMenuState(transcript)
        }
        val text = viewModel.inputText.value
        if (text.isNotBlank() || viewModel.attachments.value.isNotEmpty()) {
            performSendOrEnqueueNow(text)
        } else {
            AppLogger.info("VoiceSend", "[voice-send] nothing to send after recognition finished")
        }
    }
    val deferSendUntilVoiceFinished: () -> Boolean = {
        com.openminis.app.ui.chat.voice.VoiceSendGate.deferSendIfOwed(coroutineScope, context) {
            submitAfterVoice()
        }
    }
    val performSendOrEnqueue: (String) -> Unit = handler@{ rawText ->
        if (deferSendUntilVoiceFinished()) return@handler
        performSendOrEnqueueNow(rawText)
    }
    var lastInterruptMs by remember { mutableStateOf(0L) }
    var isUserDragging by remember { mutableStateOf(false) }
    readingAnchor.keepOnResize = { userScrolledAway || isUserDragging }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is androidx.compose.foundation.interaction.DragInteraction.Start -> {
                    isUserDragging = true
                    lastJumpedUserId = null
                }
                is androidx.compose.foundation.interaction.DragInteraction.Stop -> {
                    isUserDragging = false
                    lastInterruptMs = System.currentTimeMillis()
                    val nowAtBottom = isNearBottom.value
                    val newScrolledAway = !nowAtBottom
                    if (newScrolledAway != userScrolledAway) {
                        userScrolledAway = newScrolledAway
                    }
                }
                is androidx.compose.foundation.interaction.DragInteraction.Cancel ->
                    isUserDragging = false
                else -> Unit
            }
        }
    }
    var childScrollGestureOpen by remember { mutableStateOf(false) }
    var lastChildScrollMs by remember { mutableStateOf(0L) }
    val childScrollWatch = remember(listState) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): Offset {
                if (ChatNestedScrollFollow.isChildUserScroll(
                        source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput,
                        available.y,
                        isUserDragging,
                    )
                ) {
                    childScrollGestureOpen = true
                    lastChildScrollMs = System.currentTimeMillis()
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(
                consumed: androidx.compose.ui.unit.Velocity,
                available: androidx.compose.ui.unit.Velocity,
            ): androidx.compose.ui.unit.Velocity {
                if (childScrollGestureOpen) {
                    childScrollGestureOpen = false
                    lastInterruptMs = System.currentTimeMillis()
                    val away = ChatNestedScrollFollow.scrolledAwayAfterGesture(isNearBottom.value)
                    if (away != userScrolledAway) userScrolledAway = away
                    AppLogger.debug("ScrollFollow", "child scroll gesture ended scrolledAway=$away")
                }
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    LaunchedEffect(isNearBottom.value) {
        if (isNearBottom.value && userScrolledAway) {
            val sinceDragMs = System.currentTimeMillis() - lastInterruptMs
            if (sinceDragMs > 1500L) return@LaunchedEffect
            userScrolledAway = false
        }
    }
    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottomPx) {
        if (userScrolledAway && isNearBottom.value) userScrolledAway = false
    }
    LaunchedEffect(listState, viewModel) {
        viewModel.forceScrollToBottom.collect {
            userScrolledAway = false
            tracedScrollToItem("FORCE-SCROLL-TO-BOTTOM(resume/retry/rerun)", 0, 0)
        }
    }
    LaunchedEffect(messages.size) {
        val lastMsg = messages.lastOrNull() ?: return@LaunchedEffect
        if (!lastMsg.rendersAsUserBubble()) return@LaunchedEffect
        lastUserAppendMs = System.currentTimeMillis()
        userScrolledAway = false
        tracedScrollToItem("LE(messages.size)USER-SEND-SNAP", 0, 0)
    }
    LaunchedEffect(listState, userScrolledAway) {
        kotlinx.coroutines.flow.combine(
            snapshotFlow { messages },
            viewModel.streamingById,
        ) { msgs, stream ->
            val effective = if (stream.isEmpty()) msgs else streamingOverlaySubset(msgs, stream)
            val m = effective.lastOrNull { it.role == "assistant" }
                ?: msgs.lastOrNull { it.role == "assistant" }
                ?: return@combine null
            var growth: Long = m.content.length.toLong()
            var blockSig: Long = 1469598103934665603L
            for (b in m.toolBlocks) {
                growth += b.content.length.toLong()
                blockSig = blockSig xor b.id.hashCode().toLong()
                blockSig *= 1099511628211L
                blockSig = blockSig xor b.kind.hashCode().toLong()
                blockSig *= 1099511628211L
                blockSig = blockSig xor (b.toolStatus?.ordinal?.toLong() ?: -1L)
                blockSig *= 1099511628211L
                blockSig = blockSig xor b.content.length.toLong()
                blockSig *= 1099511628211L
            }
            ScrollFollowKey(
                lastIndex = effective.lastIndex,
                growth = growth,
                toolBlockCount = m.toolBlocks.size,
                awaiting = m.isAwaitingModelResponse,
                blockSig = blockSig,
            )
        }
            .filterNotNull()
            .distinctUntilChanged()
            .conflate()
            .sample(120L)
            .collect {
                if (!viewModel.isStreaming.value) return@collect
                if (userScrolledAway) return@collect
                if (listState.isScrollInProgress) return@collect
                val sinceInterrupt = System.currentTimeMillis() - lastInterruptMs
                if (sinceInterrupt < 1000L) return@collect
                if (listState.firstVisibleItemIndex != 0 ||
                    listState.firstVisibleItemScrollOffset != 0
                ) {
                    if (avgItemSize.value <= 0) {
                        tracedScrollToItem("LE(streaming-content)cold", 0, 0)
                        return@collect
                    }
                    val distNow = listState.firstVisibleItemIndex * avgItemSize.value.toFloat() +
                        listState.firstVisibleItemScrollOffset
                    val nowMs = System.currentTimeMillis()
                    if (StreamFollowDeadband.shouldSuppress(
                            current = distNow,
                            previous = followLastDistPx,
                            msSinceGrowth = nowMs - followLastGrowMs,
                        )
                    ) {
                        return@collect
                    }
                    if (distNow > followLastDistPx) followLastGrowMs = nowMs
                    followLastDistPx = distNow
                    if (com.openminis.app.diagnostics.StreamJitterProbe.experiment("glide") == "snap") {
                        tracedScrollToItem("LE(streaming-content)exp-snap", 0, 0)
                        return@collect
                    }
                    val avg = avgItemSize.value.toFloat().coerceAtLeast(1f)
                    val stepCeil = (avg * 0.40f).coerceIn(28f, 80f)
                    runCatching {
                        listState.scroll {
                            var guard = 0
                            while (
                                (listState.firstVisibleItemIndex != 0 ||
                                    listState.firstVisibleItemScrollOffset != 0) &&
                                guard < 120
                            ) {
                                guard++
                                val remaining = listState.firstVisibleItemIndex * avg +
                                    listState.firstVisibleItemScrollOffset
                                val step = (remaining * 0.22f).coerceIn(5f, stepCeil)
                                val consumed = withFrameNanos { scrollBy(-step) }
                                if (consumed == 0f) break
                            }
                        }
                    }
                }
            }
    }
    val streamingNowFlag = isStreaming
    LaunchedEffect(streamingNowFlag) {
        if (streamingNowFlag) return@LaunchedEffect
        lastStreamEndMs = System.currentTimeMillis()
        kotlinx.coroutines.delay(220)
        if (userScrolledAway || listState.isScrollInProgress) return@LaunchedEffect
        val sinceInterrupt = System.currentTimeMillis() - lastInterruptMs
        if (sinceInterrupt < 1000L) return@LaunchedEffect
        tracedScrollToItem("stream-end/AT-BOTTOM-RE-PIN", 0, 0)
        withFrameNanos { }
        kotlinx.coroutines.delay(900)
        if (userScrolledAway || listState.isScrollInProgress) return@LaunchedEffect
        if (listState.firstVisibleItemIndex != 0) {
            val sinceDrag = System.currentTimeMillis() - lastInterruptMs
            if (sinceDrag > 1500L) tracedScrollToItem("stream-end/LATE-REPIN", 0, 0)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val bottomItem = info.visibleItemsInfo.firstOrNull { it.index == 0 }
            Triple(
                bottomItem?.size ?: -1,
                info.visibleItemsInfo.sumOf { it.size },
                info.viewportEndOffset,
            )
        }.distinctUntilChanged().collectLatest { _ ->
            val childScrolling = ChatNestedScrollFollow.childScrollActive(
                childScrollGestureOpen, System.currentTimeMillis() - lastChildScrollMs,
            )
            if (!ChatNestedScrollFollow.followMaySnap(userScrolledAway, listState.isScrollInProgress, childScrolling)) {
                return@collectLatest
            }
            if (toggleAnchor.isBusy) return@collectLatest
            if (!isNearBottom.value) return@collectLatest
            val info = listState.layoutInfo
            val bottomItemNow = info.visibleItemsInfo.firstOrNull { it.index == 0 }
            val bottomOffsetNow = bottomItemNow?.offset ?: 0
            if (bottomOffsetNow >= 0) return@collectLatest
            tracedScrollToItem("LAYOUT-DRIFT-SNAP", 0, 0)
            val infoAfter = listState.layoutInfo
            val bottomNow = infoAfter.visibleItemsInfo.firstOrNull { it.index == 0 }
            if (bottomNow != null && bottomNow.offset < 0) {
                tracedScrollBy("LAYOUT-DRIFT-CLIP", bottomNow.offset.toFloat())
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { inProgress ->
                if (SettleAfterInteractionGate.shouldSettle(
                        scrollInProgress = inProgress,
                        userScrolledAway = userScrolledAway,
                        nearBottom = isNearBottom.value,
                        streaming = viewModel.isStreaming.value,
                        msSinceDrag = System.currentTimeMillis() - lastInterruptMs,
                    )
                ) {
                    tracedScrollToItem("settle-after-interaction", 0, 0)
                }
                if (!inProgress && !isNearBottom.value && !userScrolledAway) {
                    userScrolledAway = true
                }
            }
    }

    LaunchedEffect(listState) {
        snapshotFlow { isNearBottom.value }
            .distinctUntilChanged()
            .collect { nearBottom ->
                val firstIdx = listState.firstVisibleItemIndex
                val sinceStreamEnd = System.currentTimeMillis() - lastStreamEndMs
                val streamingDrift = viewModel.isStreaming.value ||
                    (lastStreamEndMs > 0L && sinceStreamEnd <= STREAM_END_ARM_GRACE_MS)
                if (!nearBottom && !userScrolledAway && !streamingDrift) {
                    userScrolledAway = true
                    AppLogger.debug(
                        "ScrollFollow",
                        "userScrolledAway ARMED inProgress=${listState.isScrollInProgress} " +
                            "firstIdx=$firstIdx streaming=${viewModel.isStreaming.value}",
                    )
                } else if (!nearBottom && streamingDrift) {
                    val why = if (viewModel.isStreaming.value) "streaming content growth"
                              else "stream-end reflow (+${sinceStreamEnd}ms)"
                    AppLogger.debug(
                        "ScrollFollow",
                        "arm SUPPRESSED ($why) firstIdx=$firstIdx",
                    )
                }
            }
    }

    LaunchedEffect(sessionId) {
        if (sessionId.startsWith("__new__") || hasHardwareKeyboard) {
            kotlinx.coroutines.delay(300)
            try {
                inputFocusRequester.requestFocus()
            } catch (e: IllegalStateException) {
                AppLogger.debug(
                    tagScroll,
                    "auto-focus skipped: FocusRequester not attached (likely activity recreate / theme switch): ${e.message}",
                )
            }
        }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.imageBudgetEvent.collect { ev ->
            if (ev.droppedCount > 0) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.image_budget_total_exceeded),
                )
            }
        }
    }


    val appearancePrefs = remember { com.openminis.app.ui.settings.getAppearancePrefs(context) }
    var messageFontLevel by remember { mutableStateOf(appearancePrefs.getInt(com.openminis.app.ui.settings.KEY_FONT_MESSAGE, 0)) }
    var chatInputLevel by remember { mutableStateOf(appearancePrefs.getInt(com.openminis.app.ui.settings.KEY_FONT_CHAT_INPUT, 0)) }
    var toolPreviewEnabled by remember { mutableStateOf(appearancePrefs.getBoolean(com.openminis.app.ui.settings.KEY_TOOL_PREVIEW, true)) }
    var showChatTitlePill by remember { mutableStateOf(appearancePrefs.getBoolean(com.openminis.app.ui.settings.KEY_SHOW_CHAT_TITLE, true)) }
    var editingSession by remember { mutableStateOf<com.openminis.app.data.db.ChatSessionEntity?>(null) }
    DisposableEffect(appearancePrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            when (key) {
                com.openminis.app.ui.settings.KEY_FONT_MESSAGE -> messageFontLevel = sp.getInt(key, 0)
                com.openminis.app.ui.settings.KEY_FONT_CHAT_INPUT -> chatInputLevel = sp.getInt(key, 0)
                com.openminis.app.ui.settings.KEY_TOOL_PREVIEW -> toolPreviewEnabled = sp.getBoolean(key, true)
                com.openminis.app.ui.settings.KEY_SHOW_CHAT_TITLE -> showChatTitlePill = sp.getBoolean(key, true)
            }
        }
        appearancePrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { appearancePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val toolFold = remember(sessionId) {
        ToolFoldController().apply {
            enabled = appearancePrefs.getBoolean(
                com.openminis.app.ui.settings.KEY_FOLD_TOOLS, com.openminis.app.ui.settings.FOLD_TOOLS_DEFAULT,
            )
        }
    }
    DisposableEffect(appearancePrefs, toolFold) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            if (key == com.openminis.app.ui.settings.KEY_FOLD_TOOLS) {
                toolFold.enabled = sp.getBoolean(key, com.openminis.app.ui.settings.FOLD_TOOLS_DEFAULT)
            }
        }
        appearancePrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { appearancePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(toolFold) {
        snapshotFlow { userScrolledAway }.collect { toolFold.browsing = it }
    }
    val chatSearch = remember(sessionId) {
        com.openminis.app.ui.chat.search.ChatSearchController(
            coroutineScope,
            com.openminis.app.ui.chat.search.ResourceChatSearchTextSource(context.resources),
        )
    }
    androidx.activity.compose.BackHandler(enabled = chatSearch.isActive) { chatSearch.deactivate() }
    var retrySheetSnapshot by remember(sessionId) { mutableStateOf<List<RetryRecord>?>(null) }
    retrySheetSnapshot?.let { snapshot ->
        val liveRetries by viewModel.retryLog.collectAsState()
        RetryLogSheet(records = liveRetries.ifEmpty { snapshot }, onDismiss = { retrySheetSnapshot = null })
    }
    val markdownFontScale = com.openminis.app.ui.settings.fontScaleForLevel(messageFontLevel)
    val chatInputFontScale = com.openminis.app.ui.settings.fontScaleForLevel(chatInputLevel)

    var previewUrl by remember { mutableStateOf<String?>(null) }
    var htmlPreviewHolder by remember {
        mutableStateOf<com.openminis.app.ui.preview.WebViewHolder?>(null)
    }
    var htmlPreviewFallbackTitle by remember { mutableStateOf("") }
    var htmlPreviewFullscreen by remember { mutableStateOf(false) }
    val appCtx = context.applicationContext
    val openHtmlPreview = remember<(java.io.File, String) -> Unit>(appCtx) {
        { file, title ->
            val url = "file://${file.absolutePath}"
            val existing = htmlPreviewHolder
            if (existing == null || existing.currentUrl != url || existing.isDead) {
                existing?.destroy()
                htmlPreviewHolder = com.openminis.app.ui.preview.WebViewHolder(appCtx, url)
            }
            htmlPreviewFallbackTitle = title
            htmlPreviewFullscreen = false
        }
    }
    LaunchedEffect(sessionId) {
        val pending = com.openminis.app.deeplink.DeepLinkCoordinator
            .pendingHtmlPreview.value ?: return@LaunchedEffect
        if (pending.sessionId != sessionId) return@LaunchedEffect
        com.openminis.app.deeplink.DeepLinkCoordinator.consumePendingHtmlPreview()
        val absPath = "/var/minis" + pending.resourcePath
        val file = java.io.File(absPath)
        if (!file.exists()) {
            com.openminis.app.logging.AppLogger.warning(
                "ChatScreen",
                "pinned HTML preview path missing: $absPath",
            )
            return@LaunchedEffect
        }
        val url = "file://${file.absolutePath}"
        htmlPreviewHolder?.destroy()
        htmlPreviewHolder = com.openminis.app.ui.preview.WebViewHolder(appCtx, url)
        htmlPreviewFallbackTitle = pending.title
        htmlPreviewFullscreen = true
    }
    var previewImageGallery by remember {
        mutableStateOf<Pair<List<com.openminis.app.ui.components.ImageGalleryItem>, Int>?>(null)
    }
    var previewVideoFile by remember { mutableStateOf<java.io.File?>(null) }
    var webAppSheetTarget by remember { mutableStateOf<InputAttachment?>(null) }
    val urlClickHandler = remember<(String) -> Unit>(viewModel) {
        { url ->
            when (val action = ChatLinkResolver.resolve(url, viewModel.currentSessionId, context)) {
                is ChatLinkAction.DeepLink -> ChatLinkResolver.dispatchDeepLink(context, url)
                is ChatLinkAction.SandboxFile -> {
                    when {
                        action.item.isImageFile -> {
                            previewImageGallery = listOf(
                                com.openminis.app.ui.components.ImageGalleryItem(
                                    model = action.item.file,
                                    caption = action.item.name,
                                ),
                            ) to 0
                        }
                        action.item.isVideoFile -> previewVideoFile = action.item.file
                        action.item.isHtmlFile -> openHtmlPreview(action.item.file, action.item.name)
                        else -> onPreviewAttachment(action.item)
                    }
                }
                is ChatLinkAction.SandboxFolder -> onBrowseChatFolder(action.linuxPath)
                is ChatLinkAction.ExternalApp ->
                    com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                        .handle(
                            context,
                            action.url,
                            com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                                .Origin.USER_INITIATED,
                        )
                is ChatLinkAction.Web -> previewUrl = action.url
            }
        }
    }

    val pendingMinisOpenUrl by com.openminis.app.terminal.MinisOpenUrlBroker.pendingUrl
        .collectAsState()
    val minisOpenTerminalVisible by com.openminis.app.terminal.MinisOpenUrlBroker.terminalVisible
        .collectAsState()
    LaunchedEffect(pendingMinisOpenUrl, minisOpenTerminalVisible) {
        val url = pendingMinisOpenUrl ?: return@LaunchedEffect
        if (minisOpenTerminalVisible) return@LaunchedEffect
        urlClickHandler(url.toString())
        com.openminis.app.terminal.MinisOpenUrlBroker.consume()
    }

    val markdownImageTapHandler = remember<(String, String) -> Unit>(messages, sessionId) {
        handler@{ tappedMessageId, tappedUrl ->
            val imageRegex = Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)")
            data class Ref(val messageId: String, val source: String, val title: String)
            val refs = mutableListOf<Ref>()
            for (msg in messages) {
                if (msg.role != "assistant") continue
                val content = msg.content
                if (content.isEmpty()) continue
                for (m in imageRegex.findAll(content)) {
                    val alt = m.groupValues.getOrNull(1).orEmpty()
                    val src = m.groupValues.getOrNull(2).orEmpty()
                    if (src.isEmpty()) continue
                    val pathPart = src.substringBefore('?').substringBefore('#')
                    val ext = pathPart.substringAfterLast('.', "").lowercase()
                    if (ext in setOf("mp4", "mov", "avi", "mkv", "webm",
                                     "mp3", "wav", "aac", "flac", "ogg", "m4a")) continue
                    val title = alt.ifEmpty { pathPart.substringAfterLast('/').ifEmpty { src } }
                    refs.add(Ref(msg.id, src, title))
                }
            }
            if (refs.isEmpty()) {
                urlClickHandler(tappedUrl)
                return@handler
            }
            val startIndex = refs.indexOfFirst { it.messageId == tappedMessageId && it.source == tappedUrl }
                .takeIf { it >= 0 }
                ?: refs.indexOfFirst { it.source == tappedUrl }.takeIf { it >= 0 }
                ?: 0
            val items = refs.map { ref ->
                val resolved = resolveMdMediaFile(context, ref.source, sessionId)
                com.openminis.app.ui.components.ImageGalleryItem(
                    model = resolved ?: ref.source,
                    caption = ref.title,
                )
            }
            previewImageGallery = items to startIndex
        }
    }

    CompositionLocalProvider(
        LocalBrowserTabPool provides viewModel.browserTabPool,
        LocalMarkdownFontScale provides markdownFontScale,
        LocalToolPreviewEnabled provides toolPreviewEnabled,
        LocalMarkdownUrlClickHandler provides urlClickHandler,
        LocalMarkdownImageTapHandler provides markdownImageTapHandler,
        LocalMarkdownBlankTapHandler provides remember(viewModel) {
            { msgId: String -> viewModel.toggleUsageCapsule(msgId.substringBefore('#')) }
        },
        LocalUsageTapBandRegistry provides remember(viewModel) { UsageTapBandRegistry() },
        LocalMarkdownSessionId provides sessionId,
    ) {
    Scaffold(
        containerColor = ChatColors.background,
        contentWindowInsets = WindowInsets(0),
        topBar = { Column {
            TopAppBar(
                title = {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        val noFontPad = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                        )
                        val fallbackTrigger by viewModel.fallbackTrigger.collectAsState()
                        val fallbackPulseAlpha = remember(viewModel) { androidx.compose.animation.core.Animatable(0f) }
                        val fallbackPulseBaseline = remember(viewModel) {
                            viewModel.fallbackTrigger.value
                        }
                        LaunchedEffect(viewModel, fallbackTrigger) {
                            runFallbackPulse(fallbackTrigger, fallbackPulseBaseline, fallbackPulseAlpha.asPulseAlpha())
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Red.copy(alpha = 0.35f * fallbackPulseAlpha.value))
                                .padding(horizontal = 32.dp, vertical = 2.dp),
                        ) {
                            val topBarSoul by com.openminis.app.agent.SoulStore
                                .cachedMetadata.collectAsState()
                            val displayTitle = when {
                                showChatTitlePill
                                    && sessionTitle.isNotBlank()
                                    && sessionTitle != "New Chat" -> sessionTitle
                                topBarSoul.name.isNotBlank() -> topBarSoul.name
                                else -> stringResource(R.string.app_name)
                            }
                            Text(
                                text = displayTitle,
                                fontSize = 16.sp,
                                lineHeight = 19.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ChatColors.primaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = noFontPad,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        coroutineScope.launch {
                                            editingSession = viewModel.loadSessionEntity()
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(
                                        interactionSource = modelPickerInteraction,
                                        indication = ripple(),
                                    ) { showModelPicker = true }
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    com.openminis.app.ui.quota.ModelQuotaStatusDot(
                                        isAuthed = modelName.isNotEmpty(),
                                        activeEntryId = viewModel.activeEntryId,
                                        providerRepository = providerRepository,
                                    )
                                    val groupNameDisplay = selectedGroupName.ifEmpty {
                                        if (selectedGroupId == null) {
                                            modelName
                                        } else {
                                            val defaultGroupId = providerRepository.defaultPrimaryGroupId
                                            availableGroups.firstOrNull { it.id == defaultGroupId }?.name
                                                ?: stringResource(R.string.common_default)
                                        }
                                    }.ifEmpty {
                                        stringResource(R.string.model_picker_no_model_selected)
                                    }
                                    if (groupNameDisplay.isNotEmpty()) {
                                        if (selectedGroupId != null) {
                                            Icon(
                                                Icons.Default.Layers,
                                                contentDescription = null,
                                                tint = ChatColors.tertiaryText,
                                                modifier = Modifier.size(10.dp),
                                            )
                                        }
                                        Text(
                                            text = groupNameDisplay,
                                            fontSize = 12.sp,
                                            lineHeight = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = ChatColors.secondaryText,
                                            maxLines = 1,
                                            style = noFontPad,
                                        )
                                        Icon(
                                            Icons.Default.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = ChatColors.tertiaryText,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                                if (providerName.isNotEmpty() || modelName.isNotEmpty()) {
                                    val thinkingLevelBadgeState by viewModel.thinkingLevel.collectAsState()
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        val navSpeedTier by viewModel.displayedSpeedTier.collectAsState()
                                        SpeedTierNavBadge(navSpeedTier)
                                        Text(
                                            text = if (providerName.isNotEmpty() && modelName.isNotEmpty()) {
                                                "$providerName · $modelName"
                                            } else {
                                                modelName.ifEmpty { providerName }
                                            },
                                            fontSize = 11.sp,
                                            lineHeight = 13.sp,
                                            color = ChatColors.tertiaryText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = noFontPad,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (viewModel.availableThinkingLevels.isNotEmpty() &&
                                            (
                                                thinkingLevelBadgeState.isEnabled ||
                                                    viewModel.currentModelSupportsReasoning
                                            )
                                        ) {
                                            ThinkingLevelBadge(
                                                level = thinkingLevelBadgeState,
                                                onClick = { showThinkingLevelSheet = true },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (!isTwoPane) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    } else if (onToggleSidebar != null) {
                        IconButton(
                            onClick = onToggleSidebar,
                            modifier = Modifier.offset(y = (-2).dp),
                        ) {
                            Icon(
                                Icons.Filled.Menu,
                                contentDescription = stringResource(
                                    if (sidebarCollapsed) {
                                        R.string.chat_show_sidebar
                                    } else {
                                        R.string.chat_hide_sidebar
                                    },
                                ),
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showChatMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        MinisMenu(
                            expanded = showChatMenu,
                            onDismissRequest = { showChatMenu = false },
                        ) {
                            val menuMemoryEnabled by viewModel.memoryEnabled.collectAsState()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_new_chat)) },
                                onClick = {
                                    showChatMenu = false
                                    if (isStreaming) {
                                        showNewChatStopDialog = true
                                    } else {
                                        onNewChat()
                                    }
                                },
                                leadingIcon = {
                                    MenuItemIcon(MinisIcons.SquareAndPencil)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_share_share_chat)) },
                                enabled = messages.isNotEmpty(),
                                onClick = {
                                    showChatMenu = false
                                    openSharePicker(null)
                                },
                                leadingIcon = {
                                    MenuItemIcon(Icons.Outlined.IosShare)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_search_in_chat)) },
                                enabled = messages.isNotEmpty(),
                                onClick = {
                                    showChatMenu = false
                                    chatSearch.activate()
                                },
                                leadingIcon = {
                                    MenuItemIcon(Icons.Default.Search)
                                },
                            )
                            MinisMenuDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_clear_chat), color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showChatMenu = false
                                    showClearChatDialog = true
                                },
                                leadingIcon = {
                                    MenuItemIcon(Icons.Outlined.Delete, tint = MaterialTheme.colorScheme.error)
                                },
                            )
                            MinisMenuDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_open_terminal)) },
                                onClick = {
                                    showChatMenu = false
                                    onOpenTerminal()
                                },
                                leadingIcon = {
                                    MenuItemIcon(Icons.Outlined.Terminal)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_open_browser)) },
                                onClick = {
                                    showChatMenu = false
                                    viewModel.toggleBrowserSheet()
                                },
                                leadingIcon = {
                                    MenuItemIcon(Icons.Outlined.Language)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_browse_chat_files)) },
                                onClick = {
                                    showChatMenu = false
                                    onBrowseChatFiles()
                                },
                                leadingIcon = {
                                    MenuItemIcon(Icons.Outlined.Folder)
                                },
                            )
                            MinisMenuDivider()
                            if (skillRepository != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.session_skills_title)) },
                                    onClick = {
                                        showChatMenu = false
                                        showSkillsSheet = true
                                    },
                                    leadingIcon = {
                                        MenuItemIcon(Icons.Outlined.Extension)
                                    },
                                )
                            }
                            if (mcpRepository != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.session_mcps_title)) },
                                    onClick = {
                                        showChatMenu = false
                                        showMcpsSheet = true
                                    },
                                    leadingIcon = {
                                        MenuItemIcon(Icons.Outlined.Handyman)
                                    },
                                )
                            }
                            if (memoryRepository != null && menuMemoryEnabled) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.session_memory_title)) },
                                    onClick = {
                                        showChatMenu = false
                                        viewModel.toggleMemorySheet()
                                    },
                                    leadingIcon = {
                                        MenuItemIcon(Icons.Outlined.Psychology)
                                    },
                                )
                            }
                            MinisMenuDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_token_usage)) },
                                onClick = {
                                    showChatMenu = false
                                    showTokenUsageSheet = true
                                },
                                leadingIcon = {
                                    MenuItemIcon(Icons.Outlined.Tag)
                                },
                            )
                            val showEnhancedCache by viewModel.showEnhancedCacheToggle.collectAsState()
                            val enhancedCacheOn by viewModel.enhancedCacheEnabled.collectAsState()
                            if (showEnhancedCache) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_menu_enhanced_cache)) },
                                    onClick = {
                                        if (enhancedCacheOn) {
                                            viewModel.setEnhancedCacheEnabled(false)
                                        } else if (viewModel.isEnhancedCacheConfirmed()) {
                                            viewModel.setEnhancedCacheEnabled(true)
                                        } else {
                                            showChatMenu = false
                                            showEnhancedCacheDialog = true
                                        }
                                    },
                                    leadingIcon = {
                                        MenuItemIcon(Icons.Outlined.History)
                                    },
                                    trailingIcon = {
                                        SettingsSwitch(
                                            checked = enhancedCacheOn,
                                            onCheckedChange = {
                                                if (enhancedCacheOn) {
                                                    viewModel.setEnhancedCacheEnabled(false)
                                                } else if (viewModel.isEnhancedCacheConfirmed()) {
                                                    viewModel.setEnhancedCacheEnabled(true)
                                                } else {
                                                    showChatMenu = false
                                                    showEnhancedCacheDialog = true
                                                }
                                            },
                                        )
                                    },
                                )
                            }
                            val showFastMode by viewModel.showFastModeToggle.collectAsState()
                            val showUltrafast by viewModel.showUltrafastOption.collectAsState()
                            val shownSpeedTier by viewModel.displayedSpeedTier.collectAsState()
                            val fastModeOn = shownSpeedTier != com.openminis.app.data.SpeedTier.STANDARD
                            if (showFastMode && showUltrafast) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_menu_fast_mode_tier)) },
                                    onClick = {
                                        showChatMenu = false
                                        showSpeedMenu = true
                                    },
                                    leadingIcon = {
                                        MenuItemIcon(com.openminis.app.ui.components.SpeedTierIcons.Speedometer)
                                    },
                                    trailingIcon = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                speedTierLabel(shownSpeedTier),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Icon(
                                                Icons.Default.ChevronRight,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    },
                                )
                            } else if (showFastMode) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_menu_fast_mode)) },
                                    onClick = { viewModel.setFastModeEnabled(!fastModeOn) },
                                    leadingIcon = {
                                        MenuItemIcon(com.openminis.app.ui.components.SpeedTierIcons.BoltOutline)
                                    },
                                    trailingIcon = {
                                        SettingsSwitch(
                                            checked = fastModeOn,
                                            onCheckedChange = { viewModel.setFastModeEnabled(it) },
                                        )
                                    },
                                )
                            }
                            val autoCompactOn by viewModel.autoCompactEnabled.collectAsState()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_auto_compact)) },
                                onClick = { viewModel.setAutoCompactEnabled(!autoCompactOn) },
                                leadingIcon = {
                                    MenuItemIcon(Icons.Outlined.CloseFullscreen)
                                },
                                trailingIcon = {
                                    SettingsSwitch(
                                        checked = autoCompactOn,
                                        onCheckedChange = { viewModel.setAutoCompactEnabled(it) },
                                    )
                                },
                            )
                            if (BuildConfig.DEBUG) {
                                MinisMenuDivider()
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.debug_trigger_crash_menu),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        showChatMenu = false
                                        throw RuntimeException(
                                            "Debug crash triggered by user (T287)",
                                        )
                                    },
                                    leadingIcon = {
                                        MenuItemIcon(
                                            Icons.Outlined.BugReport,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                )
                            }
                        }
                        MinisMenu(
                            expanded = showSpeedMenu,
                            onDismissRequest = { showSpeedMenu = false },
                        ) {
                            val currentTier by viewModel.displayedSpeedTier.collectAsState()
                            for (tier in com.openminis.app.data.SpeedTier.entries) {
                                DropdownMenuItem(
                                    text = { Text(speedTierLabel(tier)) },
                                    onClick = {
                                        viewModel.setSpeedTier(tier)
                                        showSpeedMenu = false
                                    },
                                    leadingIcon = { SpeedTierMenuGlyph(tier) },
                                    trailingIcon = {
                                        if (tier == currentTier) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
                windowInsets = WindowInsets.statusBars,
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = ChatColors.background.copy(alpha = 0.92f),
                    scrolledContainerColor = ChatColors.background.copy(alpha = 0.92f),
                ),
                expandedHeight = 68.dp,
            )
            ScheduledTaskPill(currentSessionId = { viewModel.currentSessionId })
        } },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        var chatPaneWidthPx by remember { mutableStateOf(0) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .onGloballyPositioned { chatPaneWidthPx = it.size.width },
        ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            LaunchedEffect(isUserDragging) {
                if (isUserDragging) {
                    if (ImeDictation.recordingActive(context)) {
                        AppLogger.debug("ScrollFollow", "drag keeps the keyboard: audio recording active (IME voice typing)")
                        return@LaunchedEffect
                    }
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                var toolBarHeightPx by remember { mutableStateOf(0) }
                var callbackDetailBlock by remember { mutableStateOf<AssistantBlock?>(null) }
                val density = LocalDensity.current
                val toolBarHeightDp = with(density) { toolBarHeightPx.toDp() }
                val sessionLoadedForDots by viewModel.sessionLoadedState.collectAsState()
                val enteringSession = showSessionEnteringIndicator(
                    isDraft = viewModel.isDraftSession,
                    sessionLoaded = sessionLoadedForDots,
                    messagesEmpty = messages.isEmpty(),
                    safeMode = com.openminis.app.crash.CrashFrequencyDetector.isSafeMode(),
                )
                val listEntryAlpha by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (enteringSession) 0f else 1f,
                    animationSpec = tween(SessionEnteringDots.EXIT_MS),
                    label = "listEntryAlpha",
                )
                val streamingById by viewModel.streamingById.collectAsState()
                val hasFloatingTools = remember(messages, streamingById) {
                    val merged = if (streamingById.isEmpty()) messages
                                 else mergeStreamingOverlay(messages, streamingById)
                    hasFloatingToolBarBlocks(merged)
                }
                val bottomReserve =
                    if (hasFloatingTools) {
                        FloatingToolBarMetrics.visibleTop(toolPreviewEnabled) + FloatingToolBarMetrics.ROW_GAP
                    } else 20.dp
                val reservePx = with(LocalDensity.current) { bottomReserve.roundToPx() }
                val lastReservePx = remember(sessionId) { intArrayOf(-1) }
                if (lastReservePx[0] >= 0 && reservePx != lastReservePx[0]) {
                    val delta = reservePx - lastReservePx[0]
                    androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
                        if (userScrolledAway && !isNearBottom.value) {
                            listState.requestScrollToItem(
                                listState.firstVisibleItemIndex,
                                (listState.firstVisibleItemScrollOffset + delta).coerceAtLeast(0),
                            )
                        }
                    }
                }
                lastReservePx[0] = reservePx
                LaunchedEffect(bottomReserve) {
                    val sinceSendMs = System.currentTimeMillis() - lastUserAppendMs
                    val sendGrace = lastUserAppendMs > 0L && sinceSendMs in 0..SEND_FOLLOW_GRACE_MS
                    if (!userScrolledAway && (isNearBottom.value || sendGrace)) {
                        val reason = if (!isNearBottom.value) "send-grace" else "near-bottom"
                        tracedScrollToItem("reserve-change/$reason", 0, 0)
                    }
                }

                var flatItems by remember(sessionId) {
                    mutableStateOf<List<FlatChatItem>>(emptyList())
                }
                val growRevealTracker = remember(sessionId) { StreamGrowRevealTracker() }
                SideEffect { voiceScreenItems.items = flatItems }
                val prewarmMarkdown = rememberMarkdownPrewarmer()
                var lastColdPrewarmMs by remember(sessionId) { mutableStateOf(-1L) }
                var coldOpenSummaryEmitted by remember(sessionId) { mutableStateOf(false) }
                val screenMountAtMs = remember(sessionId) { System.currentTimeMillis() }
                val liveMessageTracker = remember(sessionId) { LiveMessageTracker() }
                LaunchedEffect(messages, sessionId) {
                    var frozenRows: List<FlatChatItem> = emptyList()
                    var frozenKeys: Set<String> = emptySet()
                    var frozenSplitIdx = -1
                    var streamWasActive = false
                    try {
                    kotlinx.coroutines.flow.combine(
                        kotlinx.coroutines.flow.flowOf(messages),
                        viewModel.streamingById,
                        toolFold.changes,
                    ) { msgs, stream, _ -> msgs to stream }
                        .conflate()
                        .sample(80L)
                        .collect { (msgs, stream) ->
                            val tickStartNs = System.nanoTime()
                            if (stream.isNotEmpty() && !streamWasActive) {
                                streamWasActive = true
                                com.openminis.app.diagnostics.StreamPerfMonitor.turnStart(sessionId)
                            }
                            val liveIds = liveMessageTracker.update(msgs, stream.keys)
                            val splitIdx = if (stream.isEmpty()) {
                                msgs.size
                            } else {
                                val i = msgs.indexOfFirst { stream.containsKey(it.id) }
                                if (i < 0) msgs.size else i
                            }
                            val frozenReused = splitIdx == frozenSplitIdx
                            if (!frozenReused) {
                                val tBuildStart = System.nanoTime()
                                val wasEmptyPre = flatItems.isEmpty()
                                if (wasEmptyPre && stream.isEmpty()) {
                                    com.openminis.app.diagnostics.PerfLongCtx.step(
                                        sessionId,
                                        "buildFlatChatItems.start",
                                        "msgCount=${msgs.size}",
                                    )
                                }
                                val rows = withContext(Dispatchers.Default) {
                                    buildFlatChatItems(msgs.take(splitIdx), sessionId, liveMessageIds = liveIds)
                                }
                                val buildMs = (System.nanoTime() - tBuildStart) / 1_000_000
                                frozenRows = rows
                                frozenKeys = rows.mapTo(HashSet()) { it.key }
                                frozenSplitIdx = splitIdx
                                if (stream.isEmpty() && rows.isNotEmpty()) {
                                    val prewarmRowLimit = 16
                                    val prewarmCharBudget = 96_000
                                    val raws = mutableListOf<String>()
                                    var charSum = 0
                                    for (item in rows.asReversed()) {
                                        if (raws.size >= prewarmRowLimit || charSum >= prewarmCharBudget) break
                                        val raw = (item as? FlatChatItem.AssistantMarkdownBlock)?.rawText ?: continue
                                        raws.add(raw)
                                        charSum += raw.length
                                    }
                                    if (raws.isNotEmpty()) {
                                        launch(Dispatchers.Default) {
                                            val tPrewarmNs = System.nanoTime()
                                            prewarmMarkdown(raws)
                                            val prewarmMs = (System.nanoTime() - tPrewarmNs) / 1_000_000
                                            lastColdPrewarmMs = prewarmMs
                                            com.openminis.app.diagnostics.PerfLongCtx.step(
                                                sessionId,
                                                "coldPrewarm.done",
                                                "rows=${raws.size} chars=$charSum prewarmMs=$prewarmMs",
                                            )
                                        }
                                    }
                                }
                                if ((wasEmptyPre || buildMs >= 50) && stream.isEmpty()) {
                                    com.openminis.app.diagnostics.PerfLongCtx.step(
                                        sessionId,
                                        if (wasEmptyPre) "buildFlatChatItems.firstBuild"
                                        else "buildFlatChatItems.slow",
                                        "msgCount=${msgs.size} rowCount=${rows.size} buildMs=$buildMs",
                                    )
                                    if (rows.size > 3000) {
                                        com.openminis.app.diagnostics.PerfLongCtx.step(
                                            sessionId,
                                            "buildFlatChatItems.highRowCount",
                                            "rowCount=${rows.size} threshold=3000 msgCount=${msgs.size}",
                                        )
                                    }
                                }
                            }
                            val liveRows = if (splitIdx >= msgs.size) {
                                emptyList()
                            } else {
                                withContext(Dispatchers.Default) {
                                    val merged = mergeStreamingOverlay(msgs, stream)
                                    buildFlatChatItems(merged, null, fromIndex = splitIdx, seedKeys = frozenKeys, liveMessageIds = liveIds)
                                }
                            }
                            val builtRows = if (liveRows.isEmpty()) frozenRows else frozenRows + liveRows
                            chatSearch.onRows(builtRows)
                            flatItems = toolFold.fold(
                                builtRows,
                                msgs,
                                if (stream.isEmpty()) emptyList() else streamingOverlaySubset(msgs, stream),
                            )
                            toggleAnchor.restoreRow(flatItems)
                            run {
                                val live = if (viewModel.isStreaming.value) {
                                    msgs.lastOrNull { it.role == "assistant" }?.id?.substringBefore('#')
                                } else {
                                    null
                                }
                                val pinned = !userScrolledAway &&
                                    listState.firstVisibleItemIndex == 0 &&
                                    listState.firstVisibleItemScrollOffset == 0
                                val pin = growRevealTracker.onRows(
                                    flatItems.map { StreamGrowRevealTracker.Row(it.key, it.growOwnerMessageId()) },
                                    live,
                                    pinned,
                                )
                                if (pin) listState.requestScrollToItem(0, 0)
                            }
                            flatRowIndexByKey.value = buildMap {
                                flatItems.forEachIndexed { idx, row -> put(row.key, idx) }
                            }
                            com.openminis.app.diagnostics.StreamJitterProbe.flatten(
                                ms = (System.nanoTime() - tickStartNs) / 1_000_000,
                                frozenReused = frozenReused,
                                frozen = frozenRows.size,
                                live = liveRows.size,
                                streamEmpty = stream.isEmpty(),
                            )
                            com.openminis.app.diagnostics.StreamPerfMonitor.tick(
                                flattenNanos = System.nanoTime() - tickStartNs,
                                frozenReused = frozenReused,
                                frozenRows = frozenRows.size,
                                liveRows = liveRows.size,
                            )
                            if (stream.isEmpty() && streamWasActive) {
                                streamWasActive = false
                                com.openminis.app.diagnostics.StreamPerfMonitor.turnEnd()
                            }
                        }
                    } finally {
                        if (streamWasActive) {
                            com.openminis.app.diagnostics.StreamPerfMonitor.turnEnd()
                        }
                    }
                }
                var lastTrailingPinKey by remember(sessionId) { mutableStateOf<String?>(null) }
                LaunchedEffect(flatItems) {
                    val newest = flatItems.lastOrNull() ?: return@LaunchedEffect
                    val isQueuedBubble =
                        newest is FlatChatItem.UserBubble && newest.message.isQueued
                    if (newest !is FlatChatItem.AssistantToolUse &&
                        newest !is FlatChatItem.AssistantTyping &&
                        !isQueuedBubble
                    ) return@LaunchedEffect
                    if (newest.key == lastTrailingPinKey) return@LaunchedEffect
                    if (userScrolledAway) return@LaunchedEffect
                    if (listState.isScrollInProgress) return@LaunchedEffect
                    val sinceInterrupt = System.currentTimeMillis() - lastInterruptMs
                    if (sinceInterrupt < 1000L) return@LaunchedEffect
                    val sinceSendForPin = System.currentTimeMillis() - lastUserAppendMs
                    val sendGrace = lastUserAppendMs > 0L && sinceSendForPin <= SEND_FOLLOW_GRACE_MS
                    val streamingActive = viewModel.isStreaming.value && !userScrolledAway
                    if (!sendGrace && !streamingActive) return@LaunchedEffect
                    lastTrailingPinKey = newest.key
                    tracedScrollToItem("trailing-row/${newest.contentType}", 0, 0)
                }
                LaunchedEffect(chatSearch) {
                    chatSearch.navigations.collect { match ->
                        val key = match.key.rowKey
                        fun lazyIndex(): Int? {
                            val i = flatItems.indexOfFirst { it.key == key }
                            if (i < 0) return null
                            val visible = listState.layoutInfo.visibleItemsInfo
                            visible.firstOrNull { it.key == key }?.let { return it.index }
                            val lastFlat = flatItems.size - 1
                            val lead = visible.firstNotNullOfOrNull { info ->
                                val k = info.key as? String ?: return@firstNotNullOfOrNull null
                                val fi = flatItems.indexOfFirst { it.key == k }
                                if (fi < 0) null else info.index - (lastFlat - fi)
                            }?.coerceAtLeast(0) ?: 0
                            return (lastFlat - i) + lead
                        }
                        var idx = lazyIndex()
                        if (idx == null) {
                            val row = chatSearch.rowFor(key)
                            val mid = row?.foldMessageId()
                            val bid = row?.foldBlockId()
                            val run = if (mid != null && bid != null) toolFold.runKeyContaining(mid, bid) else null
                            if (run != null) {
                                toolFold.expand(run)
                                idx = kotlinx.coroutines.withTimeoutOrNull(1_500) {
                                    snapshotFlow { lazyIndex() }.filterNotNull().first()
                                }
                            }
                        }
                        val target = idx ?: run {
                            com.openminis.app.logging.AppLogger.info("ChatSearch", "[ChatSearch] navigate: row not shown key=${key.take(48)}")
                            return@collect
                        }
                        userScrolledAway = true
                        lastInterruptMs = System.currentTimeMillis()
                        listState.scrollToItem(target)
                        val viewport = listState.layoutInfo.viewportSize.height
                        if (viewport > 0) listState.scrollBy(-(viewport * 0.6f))
                    }
                }
                val grayedMap = remember(messages) {
                    messages.associate { it.id to it.isCompactedHistory }
                }
                fun originalMessageId(id: String): String =
                    id.substringBefore('#')
                fun FlatChatItem.isCompacted(): Boolean = when (this) {
                    is FlatChatItem.UserBubble -> grayedMap[originalMessageId(message.id)] == true
                    is FlatChatItem.AgentCallbackCard -> grayedMap[originalMessageId(message.id)] == true
                    is FlatChatItem.ScheduledTaskCardItem -> grayedMap[originalMessageId(message.id)] == true
                    is FlatChatItem.AssistantHeader -> grayedMap[originalMessageId(messageId)] == true
                    is FlatChatItem.AssistantText -> grayedMap[originalMessageId(messageId)] == true
                    is FlatChatItem.AssistantMarkdownBlock -> grayedMap[originalMessageId(messageId)] == true
                    is FlatChatItem.AssistantThinking -> grayedMap[originalMessageId(messageId)] == true
                    is FlatChatItem.AssistantToolUse -> grayedMap[originalMessageId(messageId)] == true
                    is FlatChatItem.AssistantToolGroup -> grayedMap[originalMessageId(messageId)] == true
                    is FlatChatItem.AssistantInfo -> false
                    is FlatChatItem.AssistantTyping -> false
                    is FlatChatItem.AssistantError -> grayedMap[originalMessageId(messageId)] == true
                    is FlatChatItem.AssistantUsage -> grayedMap[originalMessageId(messageId)] == true
                    is FlatChatItem.AssistantLegacyContent -> grayedMap[originalMessageId(messageId)] == true
                }
                val growLiveMessageId = if (isStreaming) {
                    messages.lastOrNull { it.role == "assistant" }?.id?.let(::originalMessageId)
                } else {
                    null
                }
                val messageBounds = remember { MessageBoundsRegistry() }
                val selectionController = remember { SelectionController() }
                DisposableEffect(selectionController, flatRowIndexByKey) {
                    selectionController.documentOrder = chatDocumentOrder { flatRowIndexByKey.value }
                    onDispose { selectionController.documentOrder = null }
                }
                val selectionReader = remember { LazyReadAloudPlayer(context) }
                DisposableEffect(selectionReader) {
                    onDispose { selectionReader.shutdown() }
                }
                LaunchedEffect(viewModel, selectionReader) {
                    viewModel.stopStaleReadAloud.collect { selectionReader.stop() }
                }
                val markdownToolbar = remember(context, messageBounds, viewModel, inputFocusRequester, keyboardController, selectionController, selectionReader, chatSearch) {
                    MinisMarkdownTextToolbar(
                        context = context,
                        registry = messageBounds,
                        onAddToInput = { snippet ->
                            viewModel.appendToInputText(snippet)
                            try {
                                inputFocusRequester.requestFocus()
                            } catch (_: IllegalStateException) {
                            }
                            keyboardController?.show()
                        },
                        onReadAloud = { snippet -> selectionReader.speak(snippet) },
                        onReadFromStart = { fullText -> selectionReader.speak(fullText) },
                        isStreamingNow = { viewModel.isStreaming.value },
                        selectionController = selectionController,
                        onShareChat = { messageId -> openSharePicker(messageId) },
                        onSearchInChat = { text -> chatSearch.activate(text) },
                    )
                }
                val safeMutate: (() -> Unit) -> Unit = { block ->
                    markdownToolbar.hide()
                    focusManager.clearFocus()
                    block()
                }
                val slashMenuOpen by viewModel.showSlashMenu.collectAsState()
                val mentionMenuOpenForSpy by viewModel.showMentionMenu.collectAsState()
                androidx.activity.compose.BackHandler(
                    enabled = slashMenuOpen || mentionMenuOpenForSpy
                ) {
                    if (slashMenuOpen) {
                        viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
                    }
                    if (mentionMenuOpenForSpy) {
                        viewModel.dismissMentionMenu()
                    }
                }
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalMessageBoundsRegistry provides messageBounds,
                    androidx.compose.ui.platform.LocalTextToolbar provides markdownToolbar,
                    LocalMinisSelectionController provides selectionController,
                ) {
                var listRootCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
                toggleAnchor.listCoords = { listRootCoords }
                toggleAnchor.bottomInsetPx = with(LocalDensity.current) { (if (bottomReserve == 0.dp) 12.dp else bottomReserve).toPx() }
                val perfFirstLayoutFired = remember(sessionId) { java.util.concurrent.atomic.AtomicBoolean(false) }
                val perfNewestLogged = remember(sessionId) { java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
                Box {
                AlwaysStretchOverscrollBox { sharedEffect ->
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(
                        top = 4.dp,
                        bottom = if (bottomReserve == 0.dp) 12.dp else bottomReserve,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = listEntryAlpha }
                        .nestedScroll(childScrollWatch)
                        .wrapContentWidth(Alignment.CenterHorizontally)
                        .widthIn(max = CHAT_MAX_CONTENT_WIDTH)
                        .padding(horizontal = 16.dp)
                        .then(readingAnchor.viewportModifier)
                        .onGloballyPositioned {
                            listRootCoords = it
                            if (perfFirstLayoutFired.compareAndSet(false, true)) {
                                val info = listState.layoutInfo
                                com.openminis.app.diagnostics.PerfLongCtx.step(
                                    sessionId,
                                    "lazyColumn.firstLayout",
                                    "totalItems=${info.totalItemsCount} visibleItems=${info.visibleItemsInfo.size} viewport=${info.viewportSize.width}x${info.viewportSize.height}",
                                )
                            }
                        }
                        .minisTextKitSelectionGesture(
                            controller = selectionController,
                            listState = listState,
                            rootCoordinates = { listRootCoords },
                            reverseLayout = true,
                        )
                        .pointerInput(slashMenuOpen, mentionMenuOpenForSpy) {
                            if (!slashMenuOpen && !mentionMenuOpenForSpy) return@pointerInput
                            awaitEachGesture {
                                awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                                )
                                if (slashMenuOpen) {
                                    viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
                                }
                                if (mentionMenuOpenForSpy) {
                                    viewModel.dismissMentionMenu()
                                }
                            }
                        },
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Bottom),
                    overscrollEffect = sharedEffect,
                ) {
                    val lastAssistantHasError = messages
                        .lastOrNull { it.role == "assistant" }
                        ?.error
                        ?.isNotBlank() == true
                    compactProgress?.let { progress ->
                        item(key = "__compact_progress__", contentType = "compact_progress") {
                            CompactProgressIndicator(
                                progress = progress,
                                onCancel = { viewModel.cancelCompact() },
                            )
                        }
                    }
                    if (canResume && !isStreaming && error == null && !lastAssistantHasError) {
                        item(key = "__resume_banner__", contentType = "resume_banner") {
                            ResumeBanner(onResume = {
                                viewModel.resume()
                                userScrolledAway = false
                                coroutineScope.launch {
                                    tracedScrollToItem("RESUME-BANNER/initial", 0, 0)
                                    kotlinx.coroutines.delay(100)
                                    tracedScrollToItem("RESUME-BANNER/settle", 0, 0)
                                }
                            })
                        }
                    }
                    items(
                        items = flatItems.asReversed(),
                        key = { it.key },
                        contentType = { it.contentType },
                    ) { item ->
                        if (item == flatItems.lastOrNull()) {
                            androidx.compose.runtime.SideEffect {
                                com.openminis.app.diagnostics.JankDiag.onNewestRowRecompose()
                                if (perfNewestLogged.add("compose:${item.key}")) {
                                    com.openminis.app.diagnostics.PerfLongCtx.step(
                                        sessionId,
                                        "lazyColumn.firstItem.compose",
                                    )
                                }
                            }
                        }
                        com.openminis.app.diagnostics.PerfLongCtx.maybeReportRowComposed(
                            sessionId,
                            item::class.java.simpleName,
                        )
                        val rowAlpha = if (item.isCompacted()) 0.4f else 1f
                        val tHangDiagLen = remember(item.key) {
                            when (item) {
                                is FlatChatItem.UserBubble -> item.message.content.length
                                is FlatChatItem.AssistantText -> item.messageMarkdown.length
                                else -> 0
                            }
                        }
                        if (tHangDiagLen >= 50_000) {
                            androidx.compose.runtime.SideEffect {
                                println(
                                    "[T-HANG-DIAG] LAUNCH-RENDER key=${item.key} " +
                                        "type=${item::class.java.simpleName} len=$tHangDiagLen",
                                )
                            }
                            androidx.compose.runtime.DisposableEffect(item.key) {
                                onDispose {
                                    println("[T-HANG-DIAG] FINISH-RENDER key=${item.key} (composed → disposed)")
                                }
                            }
                        }
                        val isNewestItem = item == flatItems.lastOrNull()
                        val growOwner = if (growLiveMessageId == null) null else item.growOwnerMessageId()
                        val rowHold = remember(item.key) { readingAnchor.RowHold(item.key) }
                        Box(
                            modifier = Modifier
                                .then(rowHold.modifier)
                                .then(
                                    if (growOwner != null && growOwner == growLiveMessageId) {
                                        Modifier.streamGrowReveal()
                                    } else {
                                        Modifier
                                    },
                                )
                                .alpha(rowAlpha)
                                .then(
                                    if (isNewestItem) {
                                        Modifier.onPlaced {
                                            if (perfNewestLogged.add("placed:${item.key}")) {
                                                com.openminis.app.diagnostics.PerfLongCtx.step(
                                                    sessionId,
                                                    "lazyColumn.firstItem.placed",
                                                    "size=${it.size.width}x${it.size.height}",
                                                )
                                            }
                                            if (!coldOpenSummaryEmitted) {
                                                coldOpenSummaryEmitted = true
                                                com.openminis.app.diagnostics.JankDiag.openStable(
                                                    sessionId, messages.size, flatItems.size, lastColdPrewarmMs,
                                                )
                                                val totalChars = messages.sumOf { m -> m.content.length }
                                                val maxChars = messages.maxOfOrNull { m -> m.content.length } ?: 0
                                                AppLogger.info(
                                                    "JankDiag",
                                                    "[JankDiag] coldOpen summary session=$sessionId msgs=${messages.size} rows=${flatItems.size} " +
                                                        "totalChars=$totalChars maxChars=$maxChars prewarmMs=$lastColdPrewarmMs " +
                                                        "sinceMountMs=${System.currentTimeMillis() - screenMountAtMs} " +
                                                        "hangCount=${com.openminis.app.diagnostics.HangDetector.currentHangCount(context)}",
                                                )
                                                messages.forEachIndexed { idx, m ->
                                                    if (m.content.length >= com.openminis.app.diagnostics.CONTENT_DIAG_MIN_CHARS) {
                                                        val s = com.openminis.app.diagnostics.ContentDiag.summarize(m.content)
                                                        AppLogger.info(
                                                            "Perf",
                                                            "[Perf][ContentDiag] session=$sessionId msgIdx=$idx role=${m.role} " +
                                                                "streaming=${m.isStreaming} ${s.asLogFields()}",
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                        com.openminis.app.ui.chat.search.ChatSearchRowScope(chatSearch, item.key) {
                        when (item) {
                            is FlatChatItem.UserBubble -> {
                                UserMessageBubble(
                                message = item.message,
                                precededByUser = item.precededByUser,
                                onSearchInChat = { chatSearch.activate() },
                                onCopy = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("message", item.message.content))
                                },
                                onRetry = if (isStreaming) null else ({
                                    coroutineScope.launch {
                                        tracedScrollToItem("RETRY-FROM-MSG", 0, 0)
                                    }
                                    safeMutate { viewModel.retryFromMessage(item.message.id) }
                                }),
                                onDeleteFromHere = if (isStreaming) null else ({
                                    deleteFromHereTargetId = item.message.id
                                }),
                                onCompactAbove = if (isStreaming) null else ({
                                    compactAboveTargetId = item.message.id
                                }),
                                onEdit = if (isStreaming || item.message.isQueued) null else ({
                                    val prefill = viewModel.editMessage(item.message.id)
                                    if (prefill != null) {
                                        viewModel.setInputText(prefill)
                                        coroutineScope.launch {
                                            tracedScrollToItem("EDIT-MSG", 0, 0)
                                        }
                                        inputFocusRequester.requestFocus()
                                    }
                                }),
                                onWithdraw = if (item.message.isQueued) {
                                    { safeMutate { viewModel.withdrawQueuedMessage(item.message.id) } }
                                } else null,
                                onShareChat = { openSharePicker(originalMessageId(item.message.id)) },
                                onPreviewFile = { uri, name ->
                                    val file = uri.path?.let { java.io.File(it) }
                                    if (file != null && file.exists()) {
                                        onPreviewAttachment(
                                            com.openminis.app.ui.sandbox.FileItem(
                                                file = file,
                                                name = name,
                                                isDirectory = false,
                                                isSymlink = false,
                                                size = file.length(),
                                                modifiedMs = file.lastModified(),
                                            )
                                        )
                                    }
                                },
                            )
                            }
                            is FlatChatItem.AssistantHeader -> AssistantHeader()
                            is FlatChatItem.AssistantText -> BoundsTrackedBlock(
                                messageId = item.messageId,
                                slotKey = "text:${item.block.id}",
                                markdown = item.messageMarkdown,
                            ) {
                                LargeContentGuard(
                                    content = item.block.content,
                                    isStreaming = item.isStreaming,
                                    stableKey = "text:${item.messageId}:${item.block.id}",
                                ) {
                                    SideEffect {
                                        selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                                    }
                                    StreamingMarkdownText(
                                        content = item.block.content,
                                        isStreaming = item.isStreaming,
                                        shardId = TextShardId(
                                            messageId = item.messageId,
                                            shardId = "text:${item.block.id}",
                                        ),
                                    )
                                }
                            }
                            is FlatChatItem.AssistantMarkdownBlock -> BoundsTrackedBlock(
                                messageId = item.messageId,
                                slotKey = "mdblock:${item.parentBlockId}:${item.blockIndex}",
                                markdown = item.messageMarkdown,
                            ) {
                                UsageTapBandRowScope(
                                    messageId = item.messageId,
                                    inFinalTextBlock = item.inFinalTextBlock,
                                    isLastRow = item.isLastBlockOfMessage,
                                ) {
                                LargeContentGuard(
                                    content = item.rawText,
                                    isStreaming = item.isStreaming,
                                    stableKey = "mdblock:${item.messageId}:${item.parentBlockId}:${item.blockIndex}",
                                ) {
                                    SideEffect {
                                        selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                                    }
                                    MarkdownBlock(
                                        rawText = item.rawText,
                                        isStreaming = item.isStreaming,
                                        shardId = TextShardId(
                                            messageId = item.messageId,
                                            shardId = "mdblock:${item.parentBlockId}:${item.blockIndex}",
                                        ),
                                    )
                                }
                                }
                            }
                            is FlatChatItem.AssistantThinking -> {
                                val effectiveLevel = item.messageThinkingLevel
                                    ?: viewModel.thinkingLevel.value
                                if (effectiveLevel.isEnabled) {
                                    ThinkingBlock(
                                        block = item.block,
                                        isStreaming = item.isLastBlockOverall && item.messageIsStreaming,
                                        isLast = item.isLast,
                                    )
                                }
                            }
                            is FlatChatItem.ScheduledTaskCardItem -> ScheduledTaskCard(
                                marker = item.marker,
                                hostSessionId = viewModel.currentSessionId,
                            )
                            is FlatChatItem.AgentCallbackCard -> AgentCallbackCard(
                                callback = item.callback,
                                onTap = { callbackDetailBlock = item.callback.syntheticBlock() },
                            )
                            is FlatChatItem.AssistantToolGroup -> ToolGroupRow(item) {
                                toggleAnchor.captureRow(item.key, flatItems)
                                toolFold.toggle(item.runKey)
                            }
                            is FlatChatItem.AssistantToolUse -> if (item.block.toolName == com.openminis.app.agent.userask.UserAskPromptText.TOOL_NAME) {
                                CompositionLocalProvider(LocalChatToggleAnchor provides toggleAnchor) {
                                    UserAskBlock(item.block) {
                                        ToolCallPill(item.block, allToolBlocks = item.allToolBlocks, onOpenDetail = { viewModel.openToolDetail(it) })
                                    }
                                }
                            } else if (
                                com.openminis.app.agent.jobs.HelperRunner.isSubAgentToolName(item.block.toolName) &&
                                helperControlSummary(item.block) != null
                            ) {
                                ToolCallPill(
                                    item.block,
                                    allToolBlocks = item.allToolBlocks,
                                    onOpenDetail = { viewModel.openToolDetail(it) },
                                )
                            } else if (com.openminis.app.agent.jobs.HelperRunner.isSubAgentToolName(item.block.toolName)) HelperToolBlock(
                                block = item.block,
                                onOpenDetail = { viewModel.openToolDetail(it) },
                                onStop = {
                                    com.openminis.app.agent.jobs.HelperRunner.childSessionIdFrom(item.block.content)
                                        ?.let { stopSubAgentByUser(context, it, "user-stop-block") }
                                },
                                onStart = {
                                    val started = viewModel.startNeverStartedDelegation(item.block)
                                    if (!started) {
                                        android.widget.Toast.makeText(
                                            context,
                                            context.getString(R.string.helper_start_failed),
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                                onResume = {
                                    val outcome = viewModel.resumeInterruptedFromCard(item.block)
                                    val note = when (outcome) {
                                        ChatViewModel.CardResume.STARTED -> null
                                        ChatViewModel.CardResume.QUEUED -> R.string.helper_resume_queued
                                        ChatViewModel.CardResume.REFUSED -> R.string.helper_resume_failed
                                    }
                                    note?.let {
                                        android.widget.Toast.makeText(
                                            context, context.getString(it), android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                    outcome != ChatViewModel.CardResume.REFUSED
                                },
                            ) else ToolCallPill(
                                block = item.block,
                                allToolBlocks = item.allToolBlocks,
                                onRetry = if (item.isLastCancelled && !isStreaming && !canResume) ({ safeMutate { viewModel.retryLast() } }) else null,
                                onStop = { viewModel.stopTool(item.block.id) },
                                onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                                onOpenDetail = { viewModel.openToolDetail(it) },
                                onRerunFromHere = if (!isStreaming) ({
                                    coroutineScope.launch {
                                        tracedScrollToItem("RERUN-FROM-TOOL", 0, 0)
                                    }
                                    safeMutate { viewModel.rerunFromToolBlock(item.messageId, item.block.id) }
                                }) else null,
                                onCopyDetails = {
                                    val text = formatToolDetailsForClipboard(item.block)
                                    val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cb.setPrimaryClip(android.content.ClipData.newPlainText("tool", text))
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.tool_longpress_copied_toast),
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                onSearchInChat = { chatSearch.activate() },
                            )
                            is FlatChatItem.AssistantInfo -> FallbackInfoBlock(
                                block = item.block,
                                onRevert = if (item.block.toolName == "compact") {
                                    { viewModel.revertCompact() }
                                } else null,
                            )
                            is FlatChatItem.AssistantTyping -> {
                                val retryAttempt by viewModel.autoRetryAttempt.collectAsState()
                                TypingIndicator(
                                    retryAttempt = retryAttempt,
                                    retryMax = viewModel.autoRetryMax,
                                    onRetryBadgeClick = { retrySheetSnapshot = viewModel.retryLog.value },
                                )
                            }
                            is FlatChatItem.AssistantUsage -> {
                                val msgId = originalMessageId(item.messageId)
                                val shown = msgId in revealedUsageIds
                                UsageCapsuleRow(
                                    shown = shown,
                                    onToggle = { viewModel.toggleUsageCapsule(msgId) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    androidx.compose.animation.AnimatedVisibility(
                                        visible = shown,
                                        enter = androidx.compose.animation.fadeIn(),
                                        exit = androidx.compose.animation.fadeOut(),
                                    ) {
                                        UsageCapsule(usage = item.usage, completedAt = item.completedAt)
                                    }
                                }
                            }
                            is FlatChatItem.AssistantError -> InlineErrorBanner(
                                error = item.error,
                                onRetry = {
                                    coroutineScope.launch { tracedScrollToItem("INLINE-RETRY-LAST", 0, 0) }
                                    safeMutate { viewModel.retryLast() }
                                },
                            )
                            is FlatChatItem.AssistantLegacyContent -> BoundsTrackedBlock(
                                messageId = item.messageId,
                                slotKey = "legacy",
                                markdown = item.messageMarkdown,
                            ) {
                                UsageTapBandRowScope(messageId = item.messageId, inFinalTextBlock = true, isLastRow = true) {
                                LargeContentGuard(
                                    content = item.content,
                                    isStreaming = item.isStreaming,
                                    stableKey = "legacy:${item.messageId}",
                                ) {
                                    SideEffect {
                                        selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                                    }
                                    StreamingMarkdownText(
                                        content = item.content,
                                        isStreaming = item.isStreaming,
                                        shardId = TextShardId(
                                            messageId = item.messageId,
                                            shardId = "legacy",
                                        ),
                                    )
                                }
                                }
                            }
                        }
                        }
                        }
                    }
                    if (hasOlderMessages) {
                        item(key = "__load_older_messages__", contentType = "load_older") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { viewModel.loadOlderMessages() }
                                    .padding(vertical = 8.dp, horizontal = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.chat_load_older_messages),
                                    color = ChatColors.secondaryText,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                }
                SelectionDragTracker(
                    controller = selectionController,
                    listState = listState,
                    listRootCoordinates = { listRootCoords },
                    reverseLayout = true,
                )
                MinisMarkdownTextToolbarHost(markdownToolbar)
                MinisSelectionToolbarHost(
                    controller = selectionController,
                    contentViewportBounds = {
                        val coords = listRootCoords
                        if (coords != null && coords.isAttached) {
                            val origin = coords.positionInWindow()
                            androidx.compose.ui.geometry.Rect(
                                left = origin.x,
                                top = origin.y,
                                right = origin.x + coords.size.width,
                                bottom = origin.y + coords.size.height,
                            )
                        } else null
                    },
                    actions = SelectionToolbarActions(
                        resolveSelectionMarkdown = {
                            selectionController.selectionMessageMarkdown()
                        },
                        onAddToInput = { snippet ->
                            viewModel.appendToInputText(snippet)
                            try { inputFocusRequester.requestFocus() } catch (_: IllegalStateException) {}
                            keyboardController?.show()
                        },
                        onReadAloud = { snippet -> selectionReader.speak(snippet) },
                        onReadFromStart = if (isStreaming) null else {
                            { fullText -> selectionReader.speak(fullText) }
                        },
                        onShareChat = { messageId -> openSharePicker(messageId) },
                    ),
                )
                MinisSelectionHandlesHost(
                    controller = selectionController,
                    listState = listState,
                    reverseLayout = true,
                )
                }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = enteringSession,
                    modifier = Modifier.align(Alignment.Center),
                    enter = fadeIn(tween(SessionEnteringDots.ENTER_MS, delayMillis = SessionEnteringDots.ENTER_DELAY_MS)),
                    exit = fadeOut(tween(SessionEnteringDots.EXIT_MS)) +
                        scaleOut(tween(SessionEnteringDots.EXIT_MS), targetScale = 0.92f),
                ) {
                    SessionEnteringDots()
                }

                var lastToolBlocks by remember { mutableStateOf<List<AssistantBlock>>(emptyList()) }
                LaunchedEffect(messages) {
                    kotlinx.coroutines.flow.combine(
                        kotlinx.coroutines.flow.flowOf(messages),
                        viewModel.streamingById,
                    ) { msgs, stream ->
                        val merged = if (stream.isEmpty()) msgs else mergeStreamingOverlay(msgs, stream)
                        floatingToolBarBlocks(merged)
                    }.collect { lastToolBlocks = it }
                }
                val allToolBlocks = lastToolBlocks
                if (lastToolBlocks.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .widthIn(max = CHAT_MAX_CONTENT_WIDTH)
                            .fillMaxWidth()
                            .onGloballyPositioned { toolBarHeightPx = it.size.height }
                            .padding(horizontal = 12.dp)
                            .padding(bottom = FloatingToolBarMetrics.WRAPPER_BOTTOM_PADDING),
                    ) {
                        Column {
                        FloatingToolStatusBar(
                            toolBlocks = lastToolBlocks,
                            onStop = { blockId -> viewModel.stopTool(blockId) },
                            onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                            onOpenDetail = { viewModel.openToolDetail(it) },
                        )
                        }
                    }
                } else {
                    SideEffect { toolBarHeightPx = 0 }
                }

                val upFabVisible = messages.isNotEmpty() && !isNearBottom.value
                val downFabVisible =
                    userScrolledAway && contentOverflows.value && messages.isNotEmpty()
                val fabBaseDp = if (lastToolBlocks.isNotEmpty()) 80.dp else 8.dp
                val fabStackTopDp = when {
                    upFabVisible -> fabBaseDp + 46.dp + 36.dp
                    downFabVisible -> fabBaseDp + 36.dp
                    else -> 0.dp
                }
                com.openminis.app.ui.chat.voice.SpeechPlayerCapsule(
                    bottomObstructionPx = toolBarHeightPx,
                    additionalObstructionDp = fabStackTopDp,
                    onEditProvider = onEditProviderClick,
                )

                val selectedToolDetailId by viewModel.selectedToolDetailId.collectAsState()
                LaunchedEffect(selectedToolDetailId, lastToolBlocks) {
                    val id = selectedToolDetailId ?: return@LaunchedEffect
                    if (lastToolBlocks.none { it.id == id }) viewModel.closeToolDetail()
                }
                val selectedToolBlock = selectedToolDetailId?.let { id ->
                    lastToolBlocks.firstOrNull { it.id == id }
                }
                if (selectedToolBlock != null) {
                    val initialIdx = lastToolBlocks
                        .indexOfFirst { it.id == selectedToolBlock.id }
                        .coerceAtLeast(0)
                    ToolDetailSheet(
                        toolBlocks = lastToolBlocks,
                        initialIndex = initialIdx,
                        onDismiss = { viewModel.closeToolDetail() },
                        onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                        onOpenBrowserForUrl = { url ->
                            viewModel.closeToolDetail()
                            viewModel.openBrowserSheetForUrl(url)
                        },
                        onOpenAgentTranscript = { childId ->
                            viewModel.closeToolDetail()
                            onOpenAgentTranscript(childId)
                        },
                    )
                }
                callbackDetailBlock?.let { block ->
                    ToolDetailSheet(
                        toolBlocks = listOf(block),
                        initialIndex = 0,
                        onDismiss = { callbackDetailBlock = null },
                        onOpenAgentTranscript = { childId ->
                            callbackDetailBlock = null
                            onOpenAgentTranscript(childId)
                        },
                    )
                }

                val fabEndInset = with(LocalDensity.current) {
                    val leftover = chatPaneWidthPx.toDp() - CHAT_MAX_CONTENT_WIDTH
                    if (leftover > 0.dp) leftover / 2 else 0.dp
                }

                if (messages.isNotEmpty() && !isNearBottom.value) {
                    val upBaseBottom = if (lastToolBlocks.isNotEmpty()) 80.dp else 8.dp
                    androidx.compose.material3.FilledIconButton(
                        onClick = {
                            userScrolledAway = true
                            coroutineScope.launch { scrollToPreviousUserTurn() }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp + fabEndInset, bottom = upBaseBottom + 46.dp)
                            .shadow(4.dp, CircleShape)
                            .size(36.dp),
                        colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                            containerColor = ChatColors.inputBg,
                            contentColor = ChatColors.primaryText,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerticalAlignTop,
                            contentDescription = "Scroll to previous message",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                if (userScrolledAway && contentOverflows.value && messages.isNotEmpty()) {
                    val fabBottomPadding = if (lastToolBlocks.isNotEmpty()) 80.dp else 8.dp
                    androidx.compose.material3.FilledIconButton(
                        onClick = {
                            userScrolledAway = false
                            lastJumpedUserId = null
                            coroutineScope.launch {
                                tracedScrollToItem("FAB-DOWN", 0, 0)
                                kotlinx.coroutines.delay(100)
                                tracedScrollToItem("FAB-DOWN/settle", 0, 0)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp + fabEndInset, bottom = fabBottomPadding)
                            .shadow(4.dp, CircleShape)
                            .size(36.dp),
                        colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                            containerColor = ChatColors.inputBg,
                            contentColor = ChatColors.primaryText,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Scroll to bottom",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }


            }

            editingSession?.let { session ->
                com.openminis.app.ui.sessions.SessionEditSheet(
                    session = session,
                    onDismiss = { editingSession = null },
                    onSave = { newTitle, newCategory ->
                        viewModel.updateTitleAndCategory(newTitle, newCategory)
                        editingSession = null
                    },
                )
            }

            var composerWidthPx by remember { mutableStateOf(0) }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentWidth(Alignment.CenterHorizontally)
                    .widthIn(max = CHAT_MAX_CONTENT_WIDTH)
                    .onGloballyPositioned { composerWidthPx = it.size.width }
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp)
                    .padding(top = 2.dp, bottom = 8.dp),
            ) {

                val showSlashMenu by viewModel.showSlashMenu.collectAsState()
                val filteredSlashCommands = remember(
                    showSlashMenu,
                    viewModel.slashFilter.collectAsState().value,
                    viewModel.memoryEnabled.collectAsState().value,
                    viewModel.thinkingLevel.collectAsState().value,
                ) { viewModel.filteredSlashCommands() }

                if (showSlashMenu && filteredSlashCommands.isNotEmpty()) {
                    val thinkingLevelState by viewModel.thinkingLevel.collectAsState()
                    val thinkingSupported = viewModel.currentModelSupportsReasoning
                    val memoryOnState by viewModel.memoryEnabled.collectAsState()
                    androidx.compose.ui.window.Popup(
                        popupPositionProvider = remember {
                            object : androidx.compose.ui.window.PopupPositionProvider {
                                override fun calculatePosition(
                                    anchorBounds: androidx.compose.ui.unit.IntRect,
                                    windowSize: androidx.compose.ui.unit.IntSize,
                                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                    popupContentSize: androidx.compose.ui.unit.IntSize,
                                ): androidx.compose.ui.unit.IntOffset {
                                    val gap = 12
                                    val x = ((anchorBounds.left + anchorBounds.right - popupContentSize.width) / 2)
                                        .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                                    val y = (anchorBounds.top - popupContentSize.height - gap)
                                        .coerceAtLeast(0)
                                    return androidx.compose.ui.unit.IntOffset(x, y)
                                }
                            }
                        },
                        onDismissRequest = {
                            viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
                        },
                        properties = androidx.compose.ui.window.PopupProperties(
                            focusable = false,
                            dismissOnBackPress = true,
                            dismissOnClickOutside = false,
                        ),
                    ) {
                        val slashListState = androidx.compose.foundation.lazy.rememberLazyListState()
                        Box(
                            modifier = Modifier
                                .then(
                                    if (composerWidthPx > 0) {
                                        Modifier.width(
                                            with(LocalDensity.current) { composerWidthPx.toDp() },
                                        )
                                    } else {
                                        Modifier.fillMaxWidth()
                                    },
                                )
                                .padding(horizontal = 12.dp)
                                .shadow(elevation = 3.dp, shape = RoundedCornerShape(10.dp))
                                .background(ChatColors.inputBg, RoundedCornerShape(10.dp))
                                .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(10.dp)),
                        ) {
                            androidx.compose.foundation.lazy.LazyColumn(
                                state = slashListState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(slashPickerHeight())
                                    .verticalScrollbar(slashListState),
                            ) {
                            itemsIndexed(filteredSlashCommands, key = { _, c -> c.id }) { index, cmd ->
                                if (cmd.isSkill && index > 0 && !filteredSlashCommands[index - 1].isSkill) {
                                    HorizontalDivider(
                                        thickness = 0.5.dp,
                                        color = ChatColors.toolBorder,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                                val isThinking = cmd.id == "thinking"
                                val isThinkingActive = isThinking && thinkingLevelState.isEnabled && thinkingSupported
                                val titleColor = if (isThinkingActive) ChatColors.sendButton else ChatColors.primaryText
                                val subtitleColor = if (isThinking && !thinkingSupported) {
                                    ChatColors.secondaryText
                                } else if (isThinkingActive) {
                                    ChatColors.sendButton.copy(alpha = 0.7f)
                                } else ChatColors.secondaryText
                                val iconTint = if (isThinkingActive) ChatColors.sendButton else ChatColors.primaryText
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .let {
                                            if (!isThinking) {
                                                it.clickable {
                                                    viewModel.setInputText(viewModel.executeSlashCommand(cmd, inputText))
                                                    if (cmd.isSkill || cmd.isMcp) {
                                                        try {
                                                            inputFocusRequester.requestFocus()
                                                        } catch (_: IllegalStateException) {
                                                        }
                                                        keyboardController?.show()
                                                    }
                                                }
                                            } else if (thinkingSupported) {
                                                it.clickable {
                                                    val newLevel = if (thinkingLevelState.isEnabled) ThinkingLevel.OFF else ThinkingLevel.MEDIUM
                                                    viewModel.setThinkingLevel(newLevel)
                                                }
                                            } else it
                                        }
                                        .padding(horizontal = 14.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = cmd.icon,
                                        contentDescription = null,
                                        tint = iconTint,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "/${cmd.title.lowercase()}",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = titleColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = cmd.subtitle,
                                            fontSize = 11.sp,
                                            color = subtitleColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (cmd.id == "memory") {
                                        Icon(
                                            imageVector = if (memoryOnState) Icons.Default.CheckCircle else Icons.Default.Block,
                                            contentDescription = null,
                                            tint = if (memoryOnState) ChatColors.sendButton else ChatColors.secondaryText,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    if (isThinking && thinkingSupported) {
                                        ThinkingLevelPicker(
                                            current = thinkingLevelState,
                                            availableLevels = viewModel.availableThinkingLevels,
                                            onSelect = { level -> viewModel.setThinkingLevel(level) },
                                        )
                                    }
                                }
                            }
                            }
                        }
                    }
                }

                val showMentionMenu by viewModel.showMentionMenu.collectAsState()
                val mentionEntries by viewModel.mentionEntries.collectAsState()
                val isMentionScanning by viewModel.isMentionScanning.collectAsState()
                val mentionSelectedIndex by viewModel.mentionSelectedIndex.collectAsState()
                if (showMentionMenu) {
                    androidx.compose.ui.window.Popup(
                        popupPositionProvider = remember {
                            object : androidx.compose.ui.window.PopupPositionProvider {
                                override fun calculatePosition(
                                    anchorBounds: androidx.compose.ui.unit.IntRect,
                                    windowSize: androidx.compose.ui.unit.IntSize,
                                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                    popupContentSize: androidx.compose.ui.unit.IntSize,
                                ): androidx.compose.ui.unit.IntOffset {
                                    val gap = 6
                                    val x = ((anchorBounds.left + anchorBounds.right - popupContentSize.width) / 2)
                                        .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                                    val y = (anchorBounds.top - popupContentSize.height - gap)
                                        .coerceAtLeast(0)
                                    return androidx.compose.ui.unit.IntOffset(x, y)
                                }
                            }
                        },
                        onDismissRequest = { viewModel.dismissMentionMenu() },
                        properties = androidx.compose.ui.window.PopupProperties(
                            focusable = false,
                            dismissOnBackPress = true,
                            dismissOnClickOutside = false,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .then(
                                    if (composerWidthPx > 0) {
                                        Modifier.width(
                                            with(LocalDensity.current) { composerWidthPx.toDp() },
                                        )
                                    } else {
                                        Modifier.fillMaxWidth()
                                    },
                                )
                                .padding(horizontal = 12.dp)
                                .shadow(elevation = 8.dp, shape = RoundedCornerShape(10.dp))
                                .background(ChatColors.inputBg, RoundedCornerShape(10.dp))
                                .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(10.dp)),
                        ) {
                            if (mentionEntries.isEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (isMentionScanning) {
                                        androidx.compose.material3.CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 1.5.dp,
                                            color = ChatColors.secondaryText,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(
                                        text = stringResource(
                                            if (isMentionScanning) R.string.mention_scanning
                                            else R.string.mention_no_match
                                        ),
                                        fontSize = 13.sp,
                                        color = ChatColors.secondaryText,
                                    )
                                }
                            } else {
                                val mentionListState = androidx.compose.foundation.lazy.rememberLazyListState()
                                LaunchedEffect(mentionSelectedIndex, mentionEntries.size) {
                                    val idx = mentionSelectedIndex
                                    if (idx in mentionEntries.indices) {
                                        mentionListState.animateScrollToItem(idx)
                                    }
                                }
                                LazyColumn(
                                    state = mentionListState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(
                                            slashPickerHeight(
                                                titleSp = 13.sp,
                                                subtitleSp = 11.sp,
                                                verticalPadding = 8.dp,
                                                iconSize = 16.dp,
                                            ),
                                        )
                                        .verticalScrollbar(mentionListState),
                                ) {
                                    itemsIndexed(mentionEntries, key = { _, e -> e.linuxPath }) { i, entry ->
                                        val isSelected = i == mentionSelectedIndex
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    if (isSelected) {
                                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                    } else {
                                                        Color.Transparent
                                                    },
                                                )
                                                .clickable {
                                                    val (newText, newCaret) = viewModel.selectMention(
                                                        entry,
                                                        currentText = inputFieldValue.text,
                                                        currentCaret = inputFieldValue.selection.end,
                                                    )
                                                    viewModel.setInputText(newText)
                                                    inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                                                        text = newText,
                                                        selection = androidx.compose.ui.text.TextRange(newCaret),
                                                    )
                                                }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Description,
                                                contentDescription = null,
                                                tint = ChatColors.secondaryText,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = entry.basename,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = ChatColors.primaryText,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = entry.displayPath,
                                                    fontSize = 11.sp,
                                                    color = ChatColors.secondaryText,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = entry.mountName ?: entry.scope.displayLabel,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = ChatColors.secondaryText,
                                                modifier = Modifier
                                                    .background(
                                                        ChatColors.toolCapsuleBg,
                                                        RoundedCornerShape(8.dp),
                                                    )
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                val inputBgArgb = ChatColors.inputBg.toArgb()
                val shadowPaint = remember(inputBgArgb) {
                    android.graphics.Paint().apply {
                        color = inputBgArgb
                        isAntiAlias = true
                    }
                }
                val shareInjected by viewModel.hasInjectedShareContent.collectAsState()
                val showMoveCapsule = shareInjected && hasMovableShareContent(inputText, attachments.size)
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (chatSearch.isActive) return@awaitEachGesture
                            var totalDx = 0f
                            var totalDy = 0f
                            var claimed = false
                            var lastPos = down.position
                            verticalDrag(down.id) { change ->
                                val delta = change.positionChange()
                                totalDx += delta.x
                                totalDy += delta.y
                                lastPos = change.position
                                if (!claimed) {
                                    if (kotlin.math.abs(totalDy) < slop) return@verticalDrag
                                    if (kotlin.math.abs(totalDy) <= kotlin.math.abs(totalDx)) return@verticalDrag
                                    claimed = true
                                }
                                change.consume()
                                if (totalDy < 0) {
                                    val hasText = viewModel.inputText.value.isNotBlank() ||
                                        com.openminis.app.ui.chat.voice.VoiceSendGate.owesText()
                                    if (hasText) {
                                        val newProgress = (-totalDy / swipeThresholdPx).coerceIn(0f, 1f)
                                        if (newProgress >= swipeArmFraction && sendSwipeProgress < swipeArmFraction) {
                                            swipeHaptics.performHapticFeedback(
                                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                            )
                                        }
                                        sendSwipeProgress = newProgress
                                        sendSwipeLocation = lastPos
                                    } else if (sendSwipeProgress != 0f) {
                                        sendSwipeProgress = 0f
                                    }
                                } else if (sendSwipeProgress != 0f) {
                                    sendSwipeProgress = 0f
                                }
                            }
                            val hasText = viewModel.inputText.value.isNotBlank() ||
                                com.openminis.app.ui.chat.voice.VoiceSendGate.owesText()
                            val swipedUp = claimed && totalDy < 0 &&
                                kotlin.math.abs(totalDy) > kotlin.math.abs(totalDx)
                            if (swipedUp && hasText) {
                                val attachmentsCount = viewModel.attachments.value.size
                                val canSendNow = hasText || attachmentsCount > 0
                                if (sendSwipeProgress >= swipeArmFraction && canSendNow) {
                                    performSendOrEnqueue(viewModel.inputText.value)
                                }
                                sendSwipeProgress = 0f
                            } else if (swipedUp && !hasText && !inputFocused) {
                                inputFocusRequester.requestFocus()
                                keyboardController?.show()
                                sendSwipeProgress = 0f
                            } else {
                                sendSwipeProgress = 0f
                            }
                        }
                    },
                ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (chatSearch.isActive) {
                                Modifier
                                    .alpha(0f)
                                    .clearAndSetSemantics { }
                                    .pointerInput(Unit) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                                    .changes.forEach { it.consume() }
                                            }
                                        }
                                    }
                            } else Modifier,
                        )
                        .drawBehind {
                            val radiusPx = 20.dp.toPx()
                            val canvas = drawContext.canvas.nativeCanvas
                            shadowPaint.setShadowLayer(
                                6.dp.toPx(), 0f, 0f,
                                android.graphics.Color.argb(22, 0, 0, 0),
                            )
                            canvas.drawRoundRect(
                                0f, 0f, size.width, size.height,
                                radiusPx, radiusPx,
                                shadowPaint,
                            )
                            shadowPaint.setShadowLayer(
                                10.dp.toPx(), 0f, 3.dp.toPx(),
                                android.graphics.Color.argb(24, 0, 0, 0),
                            )
                            canvas.drawRoundRect(
                                0f, 0f, size.width, size.height,
                                radiusPx, radiusPx,
                                shadowPaint,
                            )
                        }
                        .contextUsageGlow(
                            tier = contextUsage?.tier ?: ContextUsage.Tier.NORMAL,
                            cornerRadius = 20.dp,
                            animate = !animationsDisabled(context),
                        )
                        .padding(top = if (attachments.isNotEmpty()) 8.dp else 4.dp),
                ) {
                    if (showMoveCapsule) {
                        val popupDensity = androidx.compose.ui.platform.LocalDensity.current
                        val rightInsetPx = with(popupDensity) { 12.dp.roundToPx() }
                        val topInsetPx = with(popupDensity) { 6.dp.roundToPx() }
                        androidx.compose.ui.window.Popup(
                            popupPositionProvider = remember(rightInsetPx, topInsetPx) {
                                object : androidx.compose.ui.window.PopupPositionProvider {
                                    override fun calculatePosition(
                                        anchorBounds: androidx.compose.ui.unit.IntRect,
                                        windowSize: androidx.compose.ui.unit.IntSize,
                                        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                        popupContentSize: androidx.compose.ui.unit.IntSize,
                                    ): androidx.compose.ui.unit.IntOffset {
                                        val x = (anchorBounds.right - popupContentSize.width - rightInsetPx)
                                            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                                        val y = (anchorBounds.top + topInsetPx).coerceAtLeast(0)
                                        return androidx.compose.ui.unit.IntOffset(x, y)
                                    }
                                }
                            },
                            onDismissRequest = {},
                            properties = androidx.compose.ui.window.PopupProperties(
                                focusable = false,
                                dismissOnBackPress = false,
                                dismissOnClickOutside = false,
                            ),
                        ) {
                            androidx.compose.material3.Surface(
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shadowElevation = 0.dp,
                                tonalElevation = 0.dp,
                                border = androidx.compose.foundation.BorderStroke(
                                    0.5.dp,
                                    ChatColors.thumbnailBorder,
                                ),
                                modifier = Modifier.clickable { showMoveSheet = true },
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(15.dp)
                                            .border(
                                                1.dp,
                                                ChatColors.secondaryText,
                                                CircleShape,
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = null,
                                            modifier = Modifier.size(10.dp),
                                            tint = ChatColors.secondaryText,
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "Move to…",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = ChatColors.secondaryText,
                                    )
                                }
                            }
                        }
                    }
                    if (pastedTexts.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(pastedTexts, key = { it.id }) { pasted ->
                                PastedTextChip(
                                    pasted = pasted,
                                    onRemove = {
                                        val marker = pasted.placeholder
                                        val cur = inputFieldValue.text
                                        val at = cur.indexOf(marker)
                                        if (at >= 0) {
                                            val stripped =
                                                cur.substring(0, at) +
                                                    cur.substring(at + marker.length)
                                            inputFieldValue =
                                                androidx.compose.ui.text.input.TextFieldValue(
                                                    text = stripped,
                                                    selection =
                                                        androidx.compose.ui.text.TextRange(at),
                                                )
                                            viewModel.setInputText(stripped)
                                        }
                                        viewModel.removePastedText(pasted.id)
                                    },
                                )
                            }
                        }
                    }
                    if (attachments.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(0.dp),
                        ) {
                            items(attachments, key = { it.id }) { attachment ->
                                val isHtmlAttachment = attachment.fileName
                                    .substringAfterLast('.', "")
                                    .lowercase()
                                    .let { it == "html" || it == "htm" }
                                var webAppMenuExpanded by remember(attachment.id) { mutableStateOf(false) }
                                Box {
                                AttachmentChip(
                                    attachment = attachment,
                                    onRemove = { viewModel.removeAttachment(attachment.id) },
                                    onLongClick = if (false && isHtmlAttachment) {
                                        { webAppMenuExpanded = true }
                                    } else null,
                                    onClick = {
                                        if (attachment.isImage) {
                                            val imageChips = attachments.filter { it.isImage }
                                            val startIdx = imageChips.indexOfFirst { it.id == attachment.id }
                                                .coerceAtLeast(0)
                                            previewImageGallery = imageChips.map { ic ->
                                                com.openminis.app.ui.components.ImageGalleryItem(
                                                    model = ic.uri,
                                                    caption = ic.fileName,
                                                )
                                            } to startIdx
                                        } else {
                                            val uri = attachment.uri
                                            val asFile = if (uri.scheme == "file") {
                                                uri.path?.let { java.io.File(it) }
                                            } else null
                                            if (asFile != null && asFile.exists()) {
                                                onPreviewAttachment(
                                                    com.openminis.app.ui.sandbox.FileItem(
                                                        file = asFile,
                                                        name = attachment.fileName,
                                                        isDirectory = false,
                                                        isSymlink = false,
                                                        size = asFile.length(),
                                                        modifiedMs = asFile.lastModified(),
                                                    )
                                                )
                                            } else {
                                                val intent = android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                ).apply {
                                                    setDataAndType(uri, attachment.mimeType)
                                                    addFlags(
                                                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                                    )
                                                }
                                                try {
                                                    context.startActivity(intent)
                                                } catch (_: android.content.ActivityNotFoundException) {
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "No app available to open this attachment.",
                                                        android.widget.Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                            }
                                        }
                                    },
                                )
                                if (false && isHtmlAttachment) {
                                    com.openminis.app.ui.components.MinisMenu(
                                        expanded = webAppMenuExpanded,
                                        onDismissRequest = { webAppMenuExpanded = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.webapp_add_to_home)) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.AppShortcut,
                                                    contentDescription = null,
                                                )
                                            },
                                            onClick = {
                                                webAppMenuExpanded = false
                                                webAppSheetTarget = attachment
                                            },
                                        )
                                    }
                                }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    val recSttState by com.openminis.app.speech.SpeechRecognitionManager
                        .state.collectAsState()
                    val recIsRecording = recSttState == com.openminis.app.speech.RecognitionState.RECORDING ||
                        recSttState == com.openminis.app.speech.RecognitionState.STARTING ||
                        recSttState == com.openminis.app.speech.RecognitionState.FINISHING
                    if (com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive) {
                        com.openminis.app.ui.chat.voice.InlineVoiceInputPanel(
                            providerRepository = providerRepository,
                            inputText = inputText,
                            onInputTextChange = { text ->
                                viewModel.setInputText(text)
                                viewModel.updateSlashMenuState(text)
                            },
                            ensureMicPermission = { ensureMicPermissionFlow() },
                            conversationContextProvider = { screen ->
                                com.openminis.app.speech.correction.VoiceCorrection
                                    .buildConversationContext(context, viewModel.messages.value, screen)
                            },
                            captureScreen = {
                                captureVoiceScreenSnapshot(listState, voiceScreenItems.items, reverseLayout = true)
                            },
                            contextUsageHint = contextUsageHint,
                            onEditProvider = onEditProviderClick,
                        )
                    } else if (recIsRecording) {
                        val levels by com.openminis.app.speech.SpeechRecognitionManager
                            .audioLevels.collectAsState()
                        val partial by com.openminis.app.speech.SpeechRecognitionManager
                            .recognizedText.collectAsState()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            AudioWaveformView(
                                levels = levels,
                                barColor = Color.Red.copy(alpha = 0.75f),
                                heightDp = 28,
                            )
                            if (partial.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = partial,
                                    fontSize = 14.sp,
                                    color = ChatColors.secondaryText,
                                    maxLines = 2,
                                )
                            }
                        }
                    } else
                    run {
                        val interactionSource = remember { MutableInteractionSource() }
                        var placeholderIndex by rememberSaveable {
                            mutableIntStateOf(ComposerPlaceholderRotation.DEFAULT_INDEX)
                        }
                        var composerHasFocusedBefore by rememberSaveable { mutableStateOf(false) }
                        val usageLifecycle = remember { ContextUsageHintLifecycle() }
                        val activeUsageHint = contextUsageHint
                        LaunchedEffect(activeUsageHint?.generation) {
                            val candidate = activeUsageHint ?: return@LaunchedEffect
                            if (inputFieldValue.text.isNotEmpty()) return@LaunchedEffect
                            usageLifecycle.present(candidate)
                        }
                        LaunchedEffect(inputFieldValue.text.isEmpty()) {
                            usageLifecycle.onTextChanged(inputFieldValue.text)
                        }
                        val screenReaderEnabled = rememberScreenReaderEnabled()
                        val usagePage = rememberHintCarouselPage(
                            usageLifecycle.hint,
                            composerEmpty = inputFieldValue.text.isEmpty(),
                            screenReaderOn = screenReaderEnabled,
                        )
                        val onComposerVisit: (Boolean) -> Unit = { shouldRotate ->
                            if (shouldRotate) {
                                placeholderIndex = ComposerPlaceholderRotation.nextIndex(
                                    current = placeholderIndex,
                                    hasFocusedBefore = composerHasFocusedBefore,
                                    sessionHasMessages = messages.isNotEmpty(),
                                    screenReaderOn = screenReaderEnabled,
                                    randomIndex = { bound -> kotlin.random.Random.nextInt(bound) },
                                    poolSize = ComposerPlaceholderPool.size(contextUsageHint),
                                )
                                composerHasFocusedBefore = true
                            }
                        }
                        val imeVisible = WindowInsets.isImeVisible
                        LaunchedEffect(imeVisible) {
                            if (imeVisible) onComposerVisit(usageLifecycle.onImeShown())
                        }
                        val mergedTextStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 16.5.sp * chatInputFontScale,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val sendOnEnter by rememberReturnKeySendsMessage(context)
                        val performEnterSend: () -> Boolean = handler@{
                            if (deferSendUntilVoiceFinished()) return@handler true
                            if (inputText.isBlank() && attachments.isEmpty()) return@handler false
                            if (viewModel.tryExecuteInputAsSlashCommand(inputText)) {
                                viewModel.setInputText("")
                                releaseComposerAfterSend()
                                return@handler true
                            }
                            val toSend = inputText
                            lastSendTimeMs = System.currentTimeMillis()
                            viewModel.setInputText("")
                            releaseComposerAfterSend()
                            viewModel.sendMessage(toSend)
                            noteSendForInputModePref()
                            userScrolledAway = false
                            coroutineScope.launch {
                                tracedScrollToItem("SEND-PATH(keyboard-imeAction)/initial", 0, 0)
                                kotlinx.coroutines.delay(100)
                                tracedScrollToItem("SEND-PATH(keyboard-imeAction)/settle", 0, 0)
                            }
                            true
                        }
                        BasicTextField(
                            value = inputFieldValue,
                            onValueChange = { tfv ->
                                val now = System.currentTimeMillis()
                                if (now - lastSendTimeMs < 300L && tfv.text.isNotEmpty()) {
                                    return@BasicTextField
                                }
                                captureSelectionReplacement(context, inputFieldValue, tfv)
                                if (sendOnEnter && !showMentionMenu) {
                                    val oldText = inputFieldValue.text
                                    val newText = tfv.text
                                    val addedNewline = newText.length == oldText.length + 1 &&
                                        newText.count { it == '\n' } == oldText.count { it == '\n' } + 1
                                    if (addedNewline) {
                                        val caret = tfv.selection.end
                                        if (caret in 1..newText.length &&
                                            newText[caret - 1] == '\n'
                                        ) {
                                            performEnterSend()
                                            return@BasicTextField
                                        }
                                    }
                                }
                                val value = foldLongPasteIfNeeded(
                                    old = inputFieldValue,
                                    new = tfv,
                                    stash = { pasted ->
                                        if (pasted.length > PASTE_AS_FILE_THRESHOLD) {
                                            if (viewModel.stashPastedTextAsFile(pasted) != null) {
                                                ""
                                            } else {
                                                viewModel.stashPastedText(pasted)
                                            }
                                        } else {
                                            viewModel.stashPastedText(pasted)
                                        }
                                    },
                                )
                                inputFieldValue = value
                                if (inputText != value.text) {
                                    viewModel.setInputText(value.text)
                                    viewModel.updateSlashMenuState(value.text)
                                }
                                viewModel.updateMentionMenuState(
                                    text = value.text,
                                    caret = value.selection.end,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 25.dp)
                                .focusRequester(inputFocusRequester)
                                .onFocusChanged {
                                    if (it.isFocused && !inputFocused) {
                                        onComposerVisit(usageLifecycle.onFocusGained())
                                    }
                                    inputFocused = it.isFocused
                                }
                                .onKeyEvent { event ->
                                    if (showSlashMenu &&
                                        event.type == KeyEventType.KeyDown &&
                                        event.key == Key.Tab
                                    ) {
                                        val first = filteredSlashCommands.firstOrNull()
                                        if (first != null) {
                                            val newText = viewModel.executeSlashCommand(first, inputText)
                                            viewModel.setInputText(newText)
                                            inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                                                text = newText,
                                                selection = androidx.compose.ui.text.TextRange(newText.length),
                                            )
                                            return@onKeyEvent true
                                        }
                                    }
                                    if (showMentionMenu && event.type == KeyEventType.KeyDown) {
                                        when (event.key) {
                                            Key.DirectionUp -> {
                                                viewModel.mentionMenuUp()
                                                return@onKeyEvent true
                                            }
                                            Key.DirectionDown -> {
                                                viewModel.mentionMenuDown()
                                                return@onKeyEvent true
                                            }
                                            Key.Enter, Key.Tab -> {
                                                val result = viewModel.executeSelectedMention(
                                                    currentText = inputFieldValue.text,
                                                    currentCaret = inputFieldValue.selection.end,
                                                )
                                                if (result != null) {
                                                    val (newText, newCaret) = result
                                                    viewModel.setInputText(newText)
                                                    inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                                                        text = newText,
                                                        selection = androidx.compose.ui.text.TextRange(newCaret),
                                                    )
                                                    return@onKeyEvent true
                                                }
                                                if (event.key == Key.Tab) return@onKeyEvent true
                                            }
                                            Key.Escape -> {
                                                viewModel.dismissMentionMenu()
                                                return@onKeyEvent true
                                            }
                                            else -> Unit
                                        }
                                    }
                                    if (event.type == KeyEventType.KeyDown &&
                                        event.key == Key.Enter &&
                                        !event.isShiftPressed &&
                                        sendOnEnter
                                    ) {
                                        performEnterSend()
                                    } else false
                                },
                            textStyle = mergedTextStyle,
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(
                                imeAction = if (sendOnEnter) ImeAction.Send else ImeAction.Default,
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = { performEnterSend() },
                            ),
                            interactionSource = interactionSource,
                            decorationBox = { innerTextField ->
                                OutlinedTextFieldDefaults.DecorationBox(
                                    value = inputText,
                                    innerTextField = innerTextField,
                                    enabled = true,
                                    singleLine = false,
                                    visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                                    interactionSource = interactionSource,
                                    placeholder = {
                                        val soulName by com.openminis.app.agent.SoulStore
                                            .cachedMetadata.collectAsState()
                                        val fadeMs = if (animationsDisabled(context)) 0 else 220
                                        val shownHint = usageLifecycle.hint
                                        Crossfade(
                                            targetState = shownHint?.let { -1 - (it.generation * 2 + usagePage) }
                                                ?: placeholderIndex,
                                            animationSpec = tween(durationMillis = fadeMs),
                                            label = "composerPlaceholder",
                                        ) { key ->
                                            val poolPage = ComposerPlaceholderPool.usagePage(key, contextUsageHint)
                                            if (key < 0 && shownHint != null) {
                                                UsageHintPageText(
                                                    hint = shownHint,
                                                    page = shownHint.pages().getOrElse((-1 - key) % 2) { UsageHintPage.CONTEXT },
                                                    fontSize = 16.5.sp * chatInputFontScale,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                                                )
                                            } else if (poolPage != null && contextUsageHint != null) {
                                                UsageHintPageText(
                                                    hint = contextUsageHint!!,
                                                    page = poolPage,
                                                    fontSize = 16.5.sp * chatInputFontScale,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                                                )
                                            } else {
                                                Text(
                                                    composerPlaceholderText(
                                                        ComposerPlaceholderPool.textIndex(key),
                                                        soulName.name,
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurface
                                                        .copy(alpha = 0.25f),
                                                    fontSize = 16.5.sp * chatInputFontScale,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent,
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                    ),
                                    contentPadding = PaddingValues(
                                        horizontal = 12.dp,
                                        vertical = 7.dp,
                                    ),
                                )
                            },
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            InputCircleButton(
                                onClick = { showAttachMenu = true },
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Attach",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            MinisMenu(
                                expanded = showAttachMenu,
                                onDismissRequest = { showAttachMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_attach_take_photo)) },
                                    leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        val granted = ContextCompat.checkSelfPermission(
                                            context,
                                            android.Manifest.permission.CAMERA,
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) {
                                            launchCamera()
                                        } else {
                                            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_attach_choose_photos_videos)) },
                                    leadingIcon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        mediaPickerLauncher.safeLaunchPicker(
                                            androidx.activity.result.PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                                            ),
                                            context,
                                            "ChatScreen",
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_attach_add_file)) },
                                    leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        filePickerLauncher.safeLaunchPicker(arrayOf("*/*"), context, "ChatScreen")
                                    },
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        InputCircleButton(onClick = {
                            if (viewModel.showSlashMenu.value) {
                                viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
                            } else {
                                viewModel.setInputText(viewModel.showSlashMenuOverInput(inputText))
                            }
                        }) {
                            Text(
                                "/",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        val editingId by viewModel.editingMessageId.collectAsState()
                        if (editingId != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = ChatColors.inputBg,
                                modifier = Modifier.clickable {
                                    viewModel.cancelEdit()
                                    viewModel.setInputText("")
                                },
                            ) {
                                Text(
                                    text = stringResource(R.string.chat_edit_exit_button),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ChatColors.secondaryText,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        val sttAvailable by com.openminis.app.speech.SpeechRecognitionManager
                            .isAvailable.collectAsState()
                        val sttState by com.openminis.app.speech.SpeechRecognitionManager
                            .state.collectAsState()
                        val sttLocale by com.openminis.app.speech.SpeechRecognitionManager
                            .locale.collectAsState()
                        var showLangSheet by remember { mutableStateOf(false) }
                        if (sttAvailable && !com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive &&
                            (sttState == com.openminis.app.speech.RecognitionState.RECORDING ||
                                sttState == com.openminis.app.speech.RecognitionState.STARTING)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(ChatColors.inputIconBg, CircleShape)
                                    .border(0.5.dp, ChatColors.inputIconBorder, CircleShape)
                                    .clip(CircleShape)
                                    .clickable { showLangSheet = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = sttLocale.language.uppercase().take(2),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ChatColors.primaryText,
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        if (showLangSheet) {
                            SpeechLanguagePickerSheet(onDismiss = { showLangSheet = false })
                        }
                        val triggerVoiceInput: () -> Unit = lambda@{
                            if (com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive) {
                                if (com.openminis.app.speech.SpeechRecognitionManager.state.value !=
                                    com.openminis.app.speech.RecognitionState.IDLE
                                ) {
                                    com.openminis.app.speech.SpeechRecognitionManager.stopRecording()
                                }
                                com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive = false
                                ComposerInputModePrefs.save(context, voice = false)
                                voiceUsedSinceClear = false
                            } else {
                                com.openminis.app.speech.SpeechRecognitionManager
                                    .clearDegradationAndRefresh()
                                voiceUsedSinceClear = true
                                com.openminis.app.ui.chat.voice.VoiceModePrefs.enteredFromText = true
                                com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive = true
                            }
                        }

                        LaunchedEffect(Unit) {
                            if (!com.openminis.app.speech.SpeechRecognitionManager
                                    .hasMicrophoneHardware
                            ) {
                                return@LaunchedEffect
                            }
                            val pending = com.openminis.app.deeplink.DeepLinkCoordinator
                                .pendingChatAction.value
                            if (pending == com.openminis.app.deeplink.DeepLinkCoordinator
                                    .ChatAction.START_VOICE
                            ) {
                                com.openminis.app.deeplink.DeepLinkCoordinator
                                    .consumePendingChatAction()
                                triggerVoiceInput()
                            }
                        }

                        if (com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive && editingId == null) {
                            LaunchedEffect(Unit) {
                                com.openminis.app.speech.VoiceOutputState.init(context)
                            }
                            val ttsEnabled by com.openminis.app.speech.VoiceOutputState
                                .isEnabled.collectAsState()
                            val ttsMuted by com.openminis.app.speech.VoiceOutputState
                                .isMuted.collectAsState()
                            val readReplies = ttsEnabled && !ttsMuted
                            val replyTts = remember {
                                com.openminis.app.speech.ReadAloudPlayer(context)
                            }
                            DisposableEffect(replyTts) {
                                onDispose { replyTts.shutdown() }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .wrapContentWidth()
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (ttsEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        else ChatColors.secondaryText.copy(alpha = 0.10f),
                                    )
                                    .clickable {
                                        val s = com.openminis.app.speech.VoiceOutputState
                                        when {
                                            ttsEnabled && !ttsMuted -> s.setMuted(true)
                                            ttsEnabled && ttsMuted -> s.setEnabled(false)
                                            else -> { s.setMuted(false); s.setEnabled(true) }
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Icon(
                                    if (readReplies) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (ttsEnabled) MaterialTheme.colorScheme.primary else ChatColors.secondaryText,
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    stringResource(R.string.voice_panel_read_replies),
                                    style = LocalTextStyle.current.copy(
                                        fontSize = 13.sp,
                                        lineHeight = 16.25.sp,
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeightStyle = LineHeightStyle(
                                            alignment = LineHeightStyle.Alignment.Center,
                                            trim = LineHeightStyle.Trim.None,
                                        ),
                                    ),
                                    color = if (ttsEnabled) MaterialTheme.colorScheme.primary else ChatColors.secondaryText,
                                )
                            }
                            val lastAssistant = messages.lastOrNull { it.role == "assistant" }
                            val lastAssistantId = lastAssistant?.id
                            var spokenUpTo by remember(lastAssistantId) { mutableStateOf(0) }
                            LaunchedEffect(lastAssistantId, readReplies) {
                                if (!readReplies || lastAssistantId == null) return@LaunchedEffect
                                val key = lastAssistantId.hashCode()
                                val alreadySeen = com.openminis.app.ui.chat.voice.VoiceModePrefs
                                    .lastSpokenAssistantKey == key
                                val liveAtEntry =
                                    viewModel.streamingById.value.containsKey(lastAssistantId)
                                if (alreadySeen || (!viewModel.isStreaming.value && !liveAtEntry)) {
                                    android.util.Log.i(
                                        "ReadReplies",
                                        "suppressed: alreadySeen=$alreadySeen " +
                                            "streamingNow=${viewModel.isStreaming.value} " +
                                            "(history is never spoken)",
                                    )
                                    com.openminis.app.ui.chat.voice.VoiceModePrefs
                                        .lastSpokenAssistantKey = key
                                    return@LaunchedEffect
                                }
                                com.openminis.app.ui.chat.voice.VoiceModePrefs
                                    .lastSpokenAssistantKey = key
                                replyTts.stop()
                                var lastToolCount = 0
                                kotlinx.coroutines.flow.combine(
                                    viewModel.streamingById,
                                    viewModel.isStreaming,
                                ) { stream, streamingNow ->
                                    Triple(
                                        stream[lastAssistantId]?.content,
                                        streamingNow,
                                        stream[lastAssistantId]?.toolBlocks ?: emptyList(),
                                    )
                                }.collect { (live, streamingNow, toolBlocks) ->
                                    val toolCount = toolBlocks.size
                                    if (toolCount > lastToolCount) {
                                        replyTts.flush()
                                        for (i in lastToolCount until toolCount) {
                                            val b = toolBlocks.getOrNull(i) ?: continue
                                            replyTts.speakQueued(
                                                com.openminis.app.speech.ToolSpeech.announcement(
                                                    name = b.toolName,
                                                    argsJson = b.toolArgs,
                                                    title = b.toolTitle.takeIf { it.isNotBlank() },
                                                )
                                            )
                                        }
                                        lastToolCount = toolCount
                                    }
                                    val text = live
                                        ?: viewModel.messages.value
                                            .lastOrNull { it.id == lastAssistantId }?.content
                                        ?: return@collect
                                    if (text.length > spokenUpTo) {
                                        android.util.Log.i(
                                            "ReadReplies",
                                            "feeding tts +${text.length - spokenUpTo} chars " +
                                                "(total=${text.length}) live=${live != null} " +
                                                "streaming=$streamingNow",
                                        )
                                        replyTts.appendText(text.substring(spokenUpTo))
                                        spokenUpTo = text.length
                                    }
                                    if (!streamingNow && live == null) replyTts.flush()
                                }
                            }
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        if (com.openminis.app.speech.SpeechRecognitionManager.hasMicrophoneHardware) {
                            MicButton(
                                isRecording = !com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive &&
                                    (sttState == com.openminis.app.speech.RecognitionState.RECORDING ||
                                        sttState == com.openminis.app.speech.RecognitionState.STARTING),
                                localeBadge = null,
                                onClick = { triggerVoiceInput() },
                                onLongClick = { showLangSheet = true },
                                isVoiceActive = com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive,
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        val hasText = inputText.isNotBlank()
                        val voiceOwesText = com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive &&
                            (sttState != com.openminis.app.speech.RecognitionState.IDLE ||
                                com.openminis.app.ui.chat.voice.VoiceSendGate.isFinishingForSend)
                        val hasContent = hasText || attachments.isNotEmpty() || voiceOwesText
                        val processingSessions by com.openminis.app.service.SessionActivityTracker
                            .processingSessions.collectAsState()
                        val sessionBusy = isStreaming ||
                            viewModel.currentSessionId in processingSessions
                        val showStop = sessionBusy && !hasContent
                        if (showStop) {
                            var stopChoice by remember { mutableStateOf<StopSummary?>(null) }
                            var stopChoiceTurn by remember { mutableStateOf<Long?>(null) }
                            var confirmStop by remember { mutableStateOf(false) }
                            var confirmStopTurn by remember { mutableStateOf<Long?>(null) }
                            val scheduledOnly = !isStreaming &&
                                viewModel.currentSessionId in
                                com.openminis.app.service.SessionActivityTracker.scheduledSessions.collectAsState().value
                            ComposerStopButton(
                                contentDescription = if (scheduledOnly) {
                                    stringResource(R.string.chat_stop_scheduled_tasks)
                                } else {
                                    "Stop"
                                },
                                onStop = {
                                    val confirmEnabled = com.openminis.app.ui.settings.getAppearancePrefs(context)
                                        .getBoolean(StopConfirmPolicy.SETTINGS_KEY, StopConfirmPolicy.DEFAULT_ENABLED)
                                    val summary = viewModel.stopSummary()
                                    when (StopConfirmPolicy.onTap(summary.hasBackground, confirmEnabled)) {
                                        StopConfirmPolicy.TapAction.CHOOSE_SCOPE -> {
                                            stopChoiceTurn = viewModel.liveTurnToken()
                                            stopChoice = summary
                                        }
                                        StopConfirmPolicy.TapAction.CONFIRM_STOP -> {
                                            confirmStopTurn = viewModel.liveTurnToken()
                                            confirmStop = true
                                        }
                                        StopConfirmPolicy.TapAction.STOP_NOW -> viewModel.stopByUser()
                                    }
                                },
                            )
                            if (confirmStop) {
                                MinisAlertDialog(
                                    onDismissRequest = { confirmStop = false },
                                    title = stringResource(R.string.chat_stop_confirm_title),
                                    text = stringResource(R.string.chat_stop_confirm_message),
                                    confirmText = stringResource(R.string.chat_stop_confirm_stop),
                                    dismissText = stringResource(R.string.chat_stop_confirm_keep_running),
                                    isDestructive = true,
                                    onConfirm = {
                                        confirmStop = false
                                        if (StopConfirmPolicy.confirmStillApplies(confirmStopTurn, viewModel.liveTurnToken(), sessionBusy)) {
                                            viewModel.stopByUser()
                                        } else {
                                            com.openminis.app.logging.AppLogger.info(
                                                "ChatScreen", "[StopConfirm] Stop tapped after the task ended; ignored",
                                            )
                                        }
                                    },
                                )
                            }
                            stopChoice?.let { summary ->
                                StopChoiceDialog(
                                    summary = summary,
                                    canStopToolOnly = stopChoiceTurn != null,
                                    onDismiss = { stopChoice = null },
                                    onToolOnly = {
                                        stopChoice = null
                                        if (StopConfirmPolicy.confirmStillApplies(stopChoiceTurn, viewModel.liveTurnToken(), sessionBusy)) {
                                            viewModel.stopCurrentToolOnly()
                                        } else {
                                            com.openminis.app.logging.AppLogger.info(
                                                "ChatScreen", "[StopChoice] Stop Current Tool Only tapped after the turn ended; ignored",
                                            )
                                        }
                                    },
                                    onStopAll = {
                                        stopChoice = null
                                        viewModel.stopByUser()
                                    },
                                )
                            }
                        } else {
                            val canActivate = hasContent
                            ComposerSendButton(
                                canActivate = canActivate,
                                busy = com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive &&
                                    com.openminis.app.ui.chat.voice.VoiceSendGate.isFinishingForSend,
                                onSend = {
                                    performSendOrEnqueue(inputText)
                                },
                            )
                        }
                    }
                }
                if (chatSearch.isActive) {
                    com.openminis.app.ui.chat.search.ChatSearchBar(
                        controller = chatSearch,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
                SwipeToSendHint(
                    progress = sendSwipeProgress,
                    armFraction = swipeArmFraction,
                    location = sendSwipeLocation,
                    hoverAbovePx = swipeHapticOffsetPx,
                    arrowHalfPx = swipeArrowHalfPx,
                    isEnqueue = isStreaming,
                )
            }

            if (showMoveSheet) {
                MoveToSessionSheet(
                    currentSessionId = sessionId,
                    chatRepository = chatRepository,
                    onDismiss = { showMoveSheet = false },
                    onSelect = { targetId ->
                        ChatViewModelStore.stashPendingTransfer(
                            ChatViewModelStore.PendingTransfer(
                                inputText = inputText,
                                attachments = viewModel.attachments.value,
                                targetId = targetId,
                            ),
                        )
                        viewModel.setInputText("")
                        viewModel.clearAttachments()
                        viewModel.clearShareInjectedFlag()
                        showMoveSheet = false
                        onMoveToSession(targetId)
                    },
                )
            }

            val showCompactBeforeSend by viewModel.showCompactBeforeSendPrompt.collectAsState()
            if (showCompactBeforeSend) {
                MinisAlertDialog(
                    onDismissRequest = { viewModel.cancelCompactBeforeSend() },
                    title = stringResource(R.string.context_near_capacity_title),
                    text = stringResource(R.string.context_near_capacity_message),
                    confirmText = stringResource(R.string.context_compact_and_send),
                    onConfirm = { viewModel.compactAndSendPending() },
                    dismissText = stringResource(R.string.context_send_anyway),
                    onDismiss = { viewModel.sendPendingWithoutCompacting() },
                    neutralText = stringResource(R.string.context_compact_and_enable_auto),
                    onNeutral = {
                        viewModel.compactAndSendPending(alsoEnableAutoCompact = true)
                    },
                )
            }

            if (showClearChatDialog) {
                MinisAlertDialog(
                    onDismissRequest = { showClearChatDialog = false },
                    title = stringResource(R.string.chat_menu_clear_chat),
                    text = stringResource(R.string.chat_clear_dialog_body),
                    confirmText = stringResource(R.string.chat_clear_dialog_confirm),
                    isDestructive = true,
                    onConfirm = {
                        viewModel.clearChat()
                        viewModel.setInputText("")
                        showClearChatDialog = false
                    },
                )
            }
            deleteFromHereTargetId?.let { targetId ->
                val affected = remember(targetId, messages) {
                    val idx = messages.indexOfFirst { it.id == targetId }
                    if (idx < 0) 0 else messages.size - idx
                }
                MinisAlertDialog(
                    onDismissRequest = { deleteFromHereTargetId = null },
                    title = stringResource(R.string.chat_longpress_delete_from_here),
                    text = pluralStringResource(
                        R.plurals.chat_delete_from_here_dialog_body,
                        affected,
                        affected,
                    ),
                    confirmText = stringResource(R.string.chat_delete_from_here_confirm),
                    isDestructive = true,
                    onConfirm = {
                        viewModel.deleteFromMessage(targetId)
                        deleteFromHereTargetId = null
                    },
                )
            }
            compactAboveTargetId?.let { targetId ->
                MinisAlertDialog(
                    onDismissRequest = { compactAboveTargetId = null },
                    title = stringResource(R.string.chat_longpress_compact_above),
                    text = stringResource(R.string.chat_compact_above_dialog_body),
                    confirmText = stringResource(R.string.chat_compact_above_confirm),
                    isDestructive = true,
                    onConfirm = {
                        viewModel.compactBefore(targetId)
                        compactAboveTargetId = null
                    },
                )
            }
            com.openminis.app.ui.chat.share.ChatShareDebugHost(sessionId, viewModel, openSharePicker)
            shareRequest?.let { request ->
                com.openminis.app.ui.chat.share.ChatShareSelectionDialog(
                    request = request,
                    onDismiss = { shareRequest = null },
                )
            }
            val newChatStopChoice = remember(showNewChatStopDialog) {
                if (showNewChatStopDialog) viewModel.stopSummary().takeIf { it.hasBackground } else null
            }
            if (newChatStopChoice != null) {
                StopChoiceDialog(
                    summary = newChatStopChoice,
                    canStopToolOnly = true,
                    onDismiss = { showNewChatStopDialog = false },
                    onToolOnly = {
                        showNewChatStopDialog = false
                        viewModel.stopCurrentToolOnly()
                        onNewChat()
                    },
                    onStopAll = {
                        showNewChatStopDialog = false
                        viewModel.stopByUser()
                        onNewChat()
                    },
                    title = stringResource(R.string.chat_menu_new_chat),
                    toolOnlyText = stringResource(R.string.chat_new_chat_stop_turn_only),
                    stopAllText = stringResource(R.string.chat_new_chat_stop_all),
                    body = stringResource(R.string.chat_new_chat_stop_dialog_body),
                )
            } else if (showNewChatStopDialog) {
                MinisAlertDialog(
                    onDismissRequest = { showNewChatStopDialog = false },
                    title = stringResource(R.string.chat_menu_new_chat),
                    text = stringResource(R.string.chat_new_chat_stop_dialog_body),
                    confirmText = stringResource(R.string.chat_new_chat_stop_dialog_confirm),
                    isDestructive = true,
                    onConfirm = {
                        showNewChatStopDialog = false
                        viewModel.cancelStream()
                        onNewChat()
                    },
                )
            }
            if (showEnhancedCacheDialog) {
                MinisAlertDialog(
                    onDismissRequest = { showEnhancedCacheDialog = false },
                    title = stringResource(R.string.chat_menu_enhanced_cache),
                    text = stringResource(R.string.enhanced_cache_dialog_body),
                    confirmText = stringResource(R.string.enhanced_cache_dialog_confirm),
                    onConfirm = {
                        viewModel.confirmAndEnableEnhancedCache()
                        showEnhancedCacheDialog = false
                    },
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(6.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            ChatColors.background,
                            ChatColors.background.copy(alpha = 0f),
                        ),
                    )
                )
        )
        }
    }

    if (showBrowserSheet) {
        BrowserSheet(
            tabPool = viewModel.browserTabPool,
            onDismiss = { viewModel.dismissBrowserSheet() },
        )
    }

    if (showTokenUsageSheet) {
        TokenUsageSheet(
            viewModel = viewModel,
            onDismiss = { showTokenUsageSheet = false },
        )
    }

    if (showMemorySheet && memoryRepository != null) {
        SessionMemorySheet(
            memoryRepository = memoryRepository,
            toolRecords = memoryToolRecords,
            onDismiss = { viewModel.dismissMemorySheet() },
            onRevokeRecord = { record -> viewModel.revokeMemoryRecord(record) },
            onSaveRecord = { record, newContent -> viewModel.replaceMemoryRecord(record, newContent) },
        )
    }

    if (showSkillsSheet && skillRepository != null) {
        SessionSkillsSheet(
            skillRepository = skillRepository,
            sessionId = sessionId,
            onDismiss = { showSkillsSheet = false },
        )
    }

    if (showMcpsSheet && mcpRepository != null) {
        SessionMcpsSheet(
            mcpRepository = mcpRepository,
            sessionId = sessionId,
            onDismiss = { showMcpsSheet = false },
        )
    }

    if (showThinkingLevelSheet) {
        val currentThinkingLevel by viewModel.thinkingLevel.collectAsState()
        ThinkingLevelSheet(
            currentLevel = currentThinkingLevel,
            availableLevels = viewModel.availableThinkingLevels,
            onSelect = { level ->
                viewModel.setThinkingLevel(level)
                showThinkingLevelSheet = false
            },
            onDismiss = { showThinkingLevelSheet = false },
        )
    }

    if (showModelPicker) {
        val config by providerRepository.config.collectAsState()
        val activeEntryId by viewModel.activeEntryId.collectAsState()

        var pendingNonTextSelection by remember {
            mutableStateOf<PendingNonTextSelection?>(null)
        }
        val resolveImageLabel = stringResource(R.string.model_picker_modality_image)
        val resolveAudioLabel = stringResource(R.string.model_picker_modality_audio)
        val resolveVideoLabel = stringResource(R.string.model_picker_modality_video)
        fun nonTextLabelFor(model: LLMModel): String? {
            val mods = model.outputModalities?.map { it.lowercase() } ?: emptyList()
            return when {
                "image" in mods -> resolveImageLabel
                "audio" in mods -> resolveAudioLabel
                "video" in mods -> resolveVideoLabel
                else -> null
            }
        }
        fun entryById(entryId: String): ModelEntry? =
            config.modelEntries.firstOrNull { it.id == entryId }

        ModelPickerSheet(
            groups = availableGroups,
            selectedGroupId = selectedGroupId,
            activeEntryId = activeEntryId,
            defaultPrimaryGroupId = config.defaultPrimaryGroupId,
            config = config,
            providerRepository = providerRepository,
            onSelectGroup = { groupId ->
                val group = availableGroups.firstOrNull { it.id == groupId }
                val firstEntry = group?.memberEntryIds?.firstNotNullOfOrNull(::entryById)
                val label = firstEntry?.model?.let(::nonTextLabelFor)
                if (label != null) {
                    pendingNonTextSelection = PendingNonTextSelection.Group(
                        groupId = groupId,
                        modelDisplayName = firstEntry.model.displayName,
                        modalityLabel = label,
                    )
                } else {
                    viewModel.selectGroup(groupId)
                    showModelPicker = false
                }
            },
            onSelectGroupEntry = { groupId, entryId ->
                val entry = entryById(entryId)
                val label = entry?.model?.let(::nonTextLabelFor)
                if (entry != null && label != null) {
                    pendingNonTextSelection = PendingNonTextSelection.GroupEntry(
                        groupId = groupId,
                        entryId = entryId,
                        modelDisplayName = entry.model.displayName,
                        modalityLabel = label,
                    )
                } else {
                    viewModel.selectGroupEntry(groupId, entryId)
                    showModelPicker = false
                }
            },
            onSelectEntry = { entryId ->
                val entry = entryById(entryId)
                val label = entry?.model?.let(::nonTextLabelFor)
                if (entry != null && label != null) {
                    pendingNonTextSelection = PendingNonTextSelection.Entry(
                        entryId = entryId,
                        modelDisplayName = entry.model.displayName,
                        modalityLabel = label,
                    )
                } else {
                    viewModel.selectEntry(entryId)
                    showModelPicker = false
                }
            },
            onDismiss = { showModelPicker = false },
            onEditGroups = {
                showModelPicker = false
                reopenModelPickerOnResume()
                onModelGroupsClick()
            },
            onEditProvider = { instanceId ->
                showModelPicker = false
                reopenModelPickerOnResume()
                onEditProviderClick(instanceId)
            },
        )

        pendingNonTextSelection?.let { pending ->
            MinisAlertDialog(
                onDismissRequest = { pendingNonTextSelection = null },
                title = stringResource(R.string.model_picker_non_text_warning_title),
                text = stringResource(
                    R.string.model_picker_non_text_warning_body,
                    pending.modelDisplayName,
                    pending.modalityLabel,
                    pending.modalityLabel,
                ),
                confirmText = stringResource(R.string.model_picker_non_text_warning_use_anyway),
                dismissText = stringResource(R.string.model_picker_non_text_warning_choose_other),
                onConfirm = {
                    when (val sel = pending) {
                        is PendingNonTextSelection.Group -> viewModel.selectGroup(sel.groupId)
                        is PendingNonTextSelection.GroupEntry ->
                            viewModel.selectGroupEntry(sel.groupId, sel.entryId)
                        is PendingNonTextSelection.Entry -> viewModel.selectEntry(sel.entryId)
                    }
                    pendingNonTextSelection = null
                    showModelPicker = false
                },
            )
        }
    }

    OffloadPermissionDialog()

    previewUrl?.let { url ->
        com.openminis.app.ui.components.UrlPreviewSheet(
            url = url,
            onDismiss = { previewUrl = null },
        )
    }

    htmlPreviewHolder?.let { holder ->
        val onFullDismiss = {
            holder.destroy()
            htmlPreviewHolder = null
            htmlPreviewFallbackTitle = ""
            htmlPreviewFullscreen = false
        }
        if (htmlPreviewFullscreen) {
            com.openminis.app.ui.preview.WebPreviewFullscreenScreen(
                holder = holder,
                fallbackTitle = htmlPreviewFallbackTitle,
                onCollapseToSheet = {
                    holder.detach()
                    htmlPreviewFullscreen = false
                },
                onDismiss = onFullDismiss,
            )
        } else {
            com.openminis.app.ui.preview.WebPreviewBottomSheet(
                holder = holder,
                fallbackTitle = htmlPreviewFallbackTitle,
                pinSessionId = sessionId,
                onExpandFullscreen = {
                    holder.detach()
                    htmlPreviewFullscreen = true
                },
                onDismiss = onFullDismiss,
            )
        }
    }


    previewImageGallery?.let { (items, startIdx) ->
        com.openminis.app.ui.components.ImageGalleryViewer(
            items = items,
            startIndex = startIdx,
            onDismiss = { previewImageGallery = null },
        )
    }

    previewVideoFile?.let { file ->
        com.openminis.app.ui.media.MinisFullscreenVideoPlayer(
            file = file,
            onDismiss = { previewVideoFile = null },
        )
    }

    webAppSheetTarget?.let { target ->
        com.openminis.app.webapp.AddToHomeSheet(
            source = com.openminis.app.webapp.WebAppSource.ChatAttachment(
                uri = target.uri,
                fileName = target.fileName,
                sessionId = sessionId,
                sessionTitle = null,
            ),
            onDismiss = { webAppSheetTarget = null },
        )
    }
    }
}









@Composable
private fun ThinkingLevelBadge(
    level: com.openminis.app.data.model.ThinkingLevel,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val badgeColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Lightbulb,
            contentDescription = null,
            tint = if (level.isEnabled) badgeColor else badgeColor.copy(alpha = 0.4f),
            modifier = Modifier.size(9.dp),
        )
        Text(
            text = level.localizedName(context),
            fontSize = 9.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Medium,
            color = badgeColor,
            maxLines = 1,
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ThinkingLevelSheet(
    currentLevel: com.openminis.app.data.model.ThinkingLevel,
    availableLevels: List<com.openminis.app.data.model.ThinkingLevel>,
    onSelect: (com.openminis.app.data.model.ThinkingLevel) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = remember(availableLevels) {
        listOf(com.openminis.app.data.model.ThinkingLevel.OFF) +
            availableLevels.filter { it != com.openminis.app.data.model.ThinkingLevel.OFF }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(
                text = stringResource(R.string.thinking_level_sheet_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = ChatColors.primaryText,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            HorizontalDivider(color = ChatColors.toolBorder, thickness = 0.5.dp)
            val context = LocalContext.current
            rows.forEach { level ->
                val isSelected = if (level == com.openminis.app.data.model.ThinkingLevel.OFF) {
                    !currentLevel.isEnabled
                } else {
                    currentLevel == level
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(level) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Lightbulb,
                        contentDescription = null,
                        tint = ChatColors.thinking.copy(
                            alpha = if (level == com.openminis.app.data.model.ThinkingLevel.OFF) 0.4f else 1f,
                        ),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = level.localizedName(context),
                        fontSize = 15.sp,
                        color = ChatColors.primaryText,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = ChatColors.thinking,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}



@Composable
private fun speedTierLabel(tier: com.openminis.app.data.SpeedTier): String = stringResource(
    when (tier) {
        com.openminis.app.data.SpeedTier.STANDARD -> R.string.chat_speed_standard
        com.openminis.app.data.SpeedTier.FAST -> R.string.chat_speed_fast
        com.openminis.app.data.SpeedTier.ULTRAFAST -> R.string.chat_speed_ultrafast
    },
)

@Composable
private fun SpeedTierMenuGlyph(tier: com.openminis.app.data.SpeedTier) {
    MenuItemIcon(com.openminis.app.ui.components.SpeedTierIcons.forTier(tier))
}

@Composable
private fun SpeedTierNavBadge(tier: com.openminis.app.data.SpeedTier, textSize: TextUnit = 11.sp) {
    if (tier == com.openminis.app.data.SpeedTier.STANDARD) return
    val density = LocalDensity.current
    val boxDp = remember(density, textSize) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = with(density) { textSize.toPx() }
        }
        val bounds = android.graphics.Rect()
        paint.getTextBounds("H", 0, 1, bounds)
        val ink = kotlin.math.ceil(bounds.height().toFloat())
        with(density) { com.openminis.app.ui.components.SpeedTierIcons.boltBoxForInk(ink).toDp() }
    }
    Icon(
        com.openminis.app.ui.components.SpeedTierIcons.forTier(tier),
        contentDescription = speedTierLabel(tier),
        tint = Color(0xFFFF9500),
        modifier = Modifier.size(boxDp),
    )
}

internal fun hasMovableShareContent(inputText: String, attachmentCount: Int): Boolean =
    inputText.isNotBlank() || attachmentCount > 0
