package com.openminis.app.ui.sessions

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChecklistRtl
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import android.content.Intent
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
import com.openminis.app.ui.components.SectionDesign
import com.openminis.app.ui.components.SectionTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Badge
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.openminis.app.service.SessionActivityTracker
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.minisFabColor
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.rememberDecorativeTick
import com.openminis.app.ui.components.decorativePhase
import androidx.compose.ui.graphics.graphicsLayer


private data class CategoryStyle(val icon: ImageVector, val color: Color)

private fun categoryStyle(category: String?): CategoryStyle {
    return when (category?.lowercase()) {
        "code"         -> CategoryStyle(Icons.Outlined.Code, Color(0xFFF09A37))
        "writing"      -> CategoryStyle(Icons.Outlined.Description, Color(0xFF3478F6))
        "research"     -> CategoryStyle(Icons.Outlined.Language, Color(0xFF30B0C7))
        "analysis"     -> CategoryStyle(Icons.Outlined.BarChart, Color(0xFF5856D6))
        "creative"     -> CategoryStyle(Icons.Outlined.Brush, Color(0xFFFF2D55))
        "chat"         -> CategoryStyle(Icons.Outlined.Forum, Color(0xFF34C759))
        "math"         -> CategoryStyle(Icons.Outlined.Calculate, Color(0xFF9B59B6))
        "translation"  -> CategoryStyle(Icons.Outlined.Translate, Color(0xFF00BCD4))
        "health"       -> CategoryStyle(Icons.Outlined.Favorite, Color(0xFFFF3B30))
        "finance"      -> CategoryStyle(Icons.Outlined.Payments, Color(0xFF00C7BE))
        "travel"       -> CategoryStyle(Icons.Outlined.Map, Color(0xFFF09A37))
        "education"    -> CategoryStyle(Icons.Outlined.Book, Color(0xFF3478F6))
        "design"       -> CategoryStyle(Icons.Outlined.Palette, Color(0xFFFF2D55))
        "productivity" -> CategoryStyle(Icons.Outlined.CalendarMonth, Color(0xFFFFCC00))
        "support"      -> CategoryStyle(Icons.Outlined.Settings, Color(0xFF8B6914))
        "other"        -> CategoryStyle(Icons.Outlined.GridView, Color(0xFF8E8E93))
        else           -> CategoryStyle(Icons.Outlined.Forum, Color(0xFF8E8E93))
    }
}

private enum class DatePeriod(val label: String) {
    PINNED("Pinned"),
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This Week"),
    THIS_MONTH("This Month"),
    EARLIER("Earlier"),
}

private fun datePeriod(timestamp: Long): DatePeriod {
    val now = Calendar.getInstance()
    val cal = Calendar.getInstance().apply { time = Date(timestamp) }

    if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    ) return DatePeriod.TODAY

    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (cal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
    ) return DatePeriod.YESTERDAY

    val diffDays = TimeUnit.MILLISECONDS.toDays(now.timeInMillis - timestamp)
    if (diffDays < 7) return DatePeriod.THIS_WEEK

    val monthAgo = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }.timeInMillis
    if (timestamp > monthAgo) return DatePeriod.THIS_MONTH

    return DatePeriod.EARLIER
}

private const val GROUP_BADGE_FRESH_WINDOW_MS = 24L * 60L * 60L * 1000L

data class FolderGroupBlock(
    val folder: FolderEntity,
    val ids: List<String>,
    val totalCount: Int,
    val isCollapsed: Boolean,
    val latestUpdatedAt: Long,
    val summaryTitle: String? = null,
    val anyPaused: Boolean = false,
    val anyActive: Boolean = false,
    val hiddenCount: Int = 0,
    val pagesShown: Int = 1,
    val tileMembers: List<FolderTileMember> = emptyList(),
    val activeCategory: String? = null,
    val anyUnread: Boolean = false,
)

data class FolderTileMember(val category: String?)

internal const val MEMBER_TILE_MAX = 4

internal fun emptyGroupIsStillRelevant(
    folder: FolderEntity,
    nowMs: Long,
): Boolean = folder.isPinned || (nowMs - folder.createdAt) < EMPTY_GROUP_GRACE_MS

internal const val EMPTY_GROUP_GRACE_MS: Long = 60L * 60L * 1000L

internal fun partitionByFolder(
    sessions: List<ChatSessionEntity>,
    folders: List<FolderEntity>,
    expandedFolderId: String?,
    freshBadgedIds: Set<String> = emptySet(),
    activeSessionIds: Set<String> = emptySet(),
    unreadIds: Set<String> = emptySet(),
    nowMs: Long = System.currentTimeMillis(),
    folderPages: Map<String, Int> = emptyMap(),
    showAllMembers: Boolean = false,
    revealIds: Set<String> = emptySet(),
    searchResultsActive: Boolean = false,
    searchMatchedFolderIds: Set<String> = emptySet(),
    searchFolderPages: Map<String, Int> = emptyMap(),
    searchCollapsedFolderIds: Set<String> = emptySet(),
): Pair<List<FolderGroupBlock>, List<ChatSessionEntity>> {
    if (folders.isEmpty()) return emptyList<FolderGroupBlock>() to sessions

    val byId = folders.associateBy { it.id }
    val members = LinkedHashMap<String, MutableList<ChatSessionEntity>>()
    val ungrouped = mutableListOf<ChatSessionEntity>()

    for (s in sessions) {
        val fid = s.folderId
        if (fid != null && byId.containsKey(fid)) {
            members.getOrPut(fid) { mutableListOf() }.add(s)
        } else {
            ungrouped.add(s)
        }
    }

    val ordered = members.keys.toMutableList()
    for (f in folders) {
        if (f.id in members) continue
        val shown = if (searchResultsActive) f.id in searchMatchedFolderIds else emptyGroupIsStillRelevant(f, nowMs)
        if (shown) ordered.add(f.id)
    }

    val openId = expandedFolderId?.takeIf { it in ordered }

    val blocks = ordered.mapNotNull { fid ->
        val folder = byId[fid] ?: return@mapNotNull null
        val m = members[fid].orEmpty()
        val collapsed = if (searchResultsActive) fid in searchCollapsedFolderIds else fid != openId
        val displayOrdered = m.filter { it.pinnedAt != null } + m.filter { it.pinnedAt == null }
        val pages = if (collapsed) 1 else if (searchResultsActive) maxOf(1, searchFolderPages[fid] ?: 1) else FolderPages.revealing(
            index = displayOrdered.indexOfFirst { it.id in revealIds }.takeIf { it >= 0 },
            stored = folderPages[fid] ?: 1,
        )
        val (shown, hidden) = if (collapsed || (showAllMembers && !searchResultsActive)) displayOrdered.size to 0
            else FolderPages.page(displayOrdered.size, pages)
        FolderGroupBlock(
            folder = folder,
            ids = if (collapsed) emptyList() else displayOrdered.take(shown).map { it.id },
            totalCount = m.size,
            isCollapsed = collapsed,
            hiddenCount = hidden,
            pagesShown = pages,
            latestUpdatedAt = m.firstOrNull()?.updatedAt ?: folder.updatedAt,
            summaryTitle = m.firstOrNull()?.title,
            anyPaused = freshBadgedIds.isNotEmpty() && m.any { it.id in freshBadgedIds },
            anyActive = activeSessionIds.isNotEmpty() && m.any { it.id in activeSessionIds },
            anyUnread = unreadIds.isNotEmpty() && m.any { it.id in unreadIds },
            tileMembers = m.asSequence()
                .filter { !it.isChild }
                .sortedByDescending { it.updatedAt }
                .take(MEMBER_TILE_MAX)
                .map { FolderTileMember(it.category) }
                .toList(),
            activeCategory = if (activeSessionIds.isEmpty()) null else m.asSequence()
                .filter { it.id in activeSessionIds }
                .sortedWith(compareBy<ChatSessionEntity> { it.isChild }.thenByDescending { it.updatedAt })
                .firstOrNull()?.category,
        )
    }

    val pinnedFirst = blocks.filter { it.folder.isPinned } + blocks.filter { !it.folder.isPinned }
    return pinnedFirst to ungrouped
}

private fun groupSessionsByDate(sessions: List<ChatSessionEntity>): List<Pair<DatePeriod, List<ChatSessionEntity>>> {
    val pinned = sessions.filter { it.pinnedAt != null }.sortedByDescending { it.pinnedAt }
    val unpinned = sessions.filter { it.pinnedAt == null }
    val grouped = unpinned.groupBy { datePeriod(it.updatedAt) }
    val result = mutableListOf<Pair<DatePeriod, List<ChatSessionEntity>>>()
    if (pinned.isNotEmpty()) {
        result.add(DatePeriod.PINNED to pinned)
    }
    for (period in DatePeriod.entries) {
        if (period == DatePeriod.PINNED) continue
        grouped[period]?.let { result.add(period to it) }
    }
    return result
}

private fun relativeDate(context: Context, timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val seconds = TimeUnit.MILLISECONDS.toSeconds(diff)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)

    if (seconds < 60) return context.getString(R.string.time_just_now)
    if (minutes < 60) return context.getString(R.string.time_minutes_ago, minutes.toInt())
    if (hours < 24) return context.getString(R.string.time_hours_ago, hours.toInt())

    val dateCal = Calendar.getInstance().apply { time = Date(timestamp) }
    val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (dateCal.get(Calendar.YEAR) == yesterdayCal.get(Calendar.YEAR) &&
        dateCal.get(Calendar.DAY_OF_YEAR) == yesterdayCal.get(Calendar.DAY_OF_YEAR)
    ) {
        return context.getString(R.string.time_yesterday)
    }

    val days = TimeUnit.MILLISECONDS.toDays(diff)
    if (days < 7) {
        val dayNames = java.text.DateFormatSymbols(java.util.Locale.getDefault()).weekdays
        return dayNames[dateCal.get(Calendar.DAY_OF_WEEK)]
    }

    val month = dateCal.get(Calendar.MONTH) + 1
    val day = dateCal.get(Calendar.DAY_OF_MONTH)
    return "$month/$day"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionListScreen(
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    onSessionClick: (String) -> Unit,
    onNewChat: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onAddProviderClick: () -> Unit = {},
    onSelectModelsClick: () -> Unit = {},
    onRestoreBackupClick: () -> Unit = {},
    onTerminalClick: () -> Unit = {},
    onRootfsClick: () -> Unit = {},
    onScheduledTasksClick: () -> Unit = {},
    onNetworkPageClick: (String) -> Unit = {},
    selectedSessionId: String? = null,
    draftPlaceholderId: String? = null,
) {
    val context = LocalContext.current
    val viewModel: SessionListViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = SessionListViewModel.factory(chatRepository, providerRepository, context),
    )
    val persistedSessions by viewModel.displayedSessions.collectAsState()
    val sessions = remember(persistedSessions, draftPlaceholderId) {
        val draftId = draftPlaceholderId
        if (draftId == null || persistedSessions.any { it.id == draftId }) {
            persistedSessions
        } else {
            val now = System.currentTimeMillis()
            listOf(
                com.openminis.app.data.db.ChatSessionEntity(
                    id = draftId,
                    title = null,
                    modelId = "",
                    createdAt = now,
                    updatedAt = now,
                    folderId = null,
                ),
            ) + persistedSessions
        }
    }
    val isInitialLoadComplete by viewModel.isInitialLoadComplete.collectAsState()
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val searchSnippets by viewModel.searchSnippets.collectAsState()
    val isSelecting by viewModel.isSelecting.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val regeneratingIds by viewModel.regeneratingIds.collectAsState()
    val providerConfig by providerRepository.config.collectAsState()
    val hasProviders = providerConfig.instances.isNotEmpty()
    val hasGroups = providerConfig.modelGroups.isNotEmpty()
    val configLoaded by providerRepository.configLoaded.collectAsState()
    val scope = rememberCoroutineScope()
    val isDark = ChatColors.isDark

    fun exitSearchIfQueryBlank() {
        if (viewModel.isSearchActive.value && viewModel.searchQuery.value.isBlank()) {
            viewModel.searchQuery.value = ""
            viewModel.isSearchActive.value = false
        }
    }

    val searchRequests = com.openminis.app.ui.navigation.SessionSearchRequest.requests
    LaunchedEffect(Unit) {
        searchRequests.drop(1).collect {
            viewModel.isSearchActive.value = true
        }
    }
    val onSessionClickGuarded: (String) -> Unit = { id ->
        exitSearchIfQueryBlank()
        onSessionClick(id)
    }
    val onNewChatGuarded: (String) -> Unit = { id -> exitSearchIfQueryBlank(); onNewChat(id) }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteTargetId by remember { mutableStateOf<String?>(null) }
    var folderToRename by remember { mutableStateOf<FolderEntity?>(null) }
    var folderToDissolve by remember { mutableStateOf<FolderEntity?>(null) }
    var folderToDelete by remember { mutableStateOf<Pair<FolderEntity, Int>?>(null) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    var multiExportProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var multiExportResult by remember { mutableStateOf<com.openminis.app.share.SessionExporter.Result?>(null) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var editSession by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var showBrowserSheet by remember { mutableStateOf(false) }
    var showBrowserSettings by remember { mutableStateOf(false) }
    val browserTabPool = remember { com.openminis.app.browser.BrowserTabPool(context) }

    val folders by viewModel.folders.collectAsState()
    val expandedFolderId by viewModel.expandedFolderId.collectAsState()
    val folderMemberCounts by viewModel.folderMemberCounts.collectAsState()
    val groupPickerRequest by viewModel.groupPickerRequest.collectAsState()
    val searchResultsReady by viewModel.searchResultsReady.collectAsState()
    val searchMatchedFolderIds by viewModel.searchMatchedFolderIds.collectAsState()
    val searchResultsActive = isSearchActive && searchQuery.isNotBlank() && searchResultsReady
    val searchFolderPages by viewModel.searchFolderPages.collectAsState()
    val searchCollapsedFolderIds by viewModel.searchCollapsedFolderIds.collectAsState()
    val showFolderBlock = !isSearchActive || searchQuery.isBlank() || searchResultsActive
    val sessionBadges by com.openminis.app.service.SessionBadgeStore.byId.collectAsState()
    val badgeRevision by com.openminis.app.service.SessionBadgeStore.revision.collectAsState()
    val freshBadgedIds = remember(sessionBadges, badgeRevision) {
        com.openminis.app.service.SessionBadgeStore
            .freshCornerBadgeSessionIds(GROUP_BADGE_FRESH_WINDOW_MS)
    }
    val activeSessionIds by SessionActivityTracker.processingSessions.collectAsState()
    val unreadIds by com.openminis.app.service.SessionUnreadStore.ids.collectAsState()
    val folderPages by viewModel.folderPages.collectAsState()
    val revealIds = remember(selectedSessionId) { setOfNotNull(selectedSessionId) }
    val folderPartition = remember(
        sessions, folders, expandedFolderId, showFolderBlock, freshBadgedIds, activeSessionIds, unreadIds,
        folderPages, isSelecting, revealIds, searchResultsActive, searchMatchedFolderIds, searchFolderPages,
        searchCollapsedFolderIds,
    ) {
        if (showFolderBlock) {
            partitionByFolder(
                sessions, folders, expandedFolderId, freshBadgedIds, activeSessionIds,
                unreadIds = unreadIds,
                folderPages = folderPages,
                showAllMembers = isSelecting,
                revealIds = revealIds,
                searchResultsActive = searchResultsActive,
                searchMatchedFolderIds = if (searchResultsActive) searchMatchedFolderIds else emptySet(),
                searchFolderPages = if (searchResultsActive) searchFolderPages else emptyMap(),
                searchCollapsedFolderIds = if (searchResultsActive) searchCollapsedFolderIds else emptySet(),
            )
        } else emptyList<FolderGroupBlock>() to sessions
    }
    LaunchedEffect(folderPartition) {
        if (searchResultsActive) return@LaunchedEffect
        for (b in folderPartition.first) {
            if (!b.isCollapsed && b.pagesShown > (folderPages[b.folder.id] ?: 1)) {
                viewModel.keepFolderPages(b.folder.id, b.pagesShown)
            }
        }
    }
    val folderBlocks = folderPartition.first
    val groupedSessions = remember(folderPartition) { groupSessionsByDate(folderPartition.second) }

    val pinnedFirst = groupedSessions.firstOrNull()?.first == DatePeriod.PINNED
    val leadingDateGroups = if (pinnedFirst) groupedSessions.take(1) else emptyList()
    val trailingDateGroups = if (pinnedFirst) groupedSessions.drop(1) else groupedSessions
    val folderHeaderIndices = remember(leadingDateGroups, folderBlocks) {
        buildMap {
            var idx = 0
            leadingDateGroups.forEach { (_, rows) -> idx += 1 + rows.size }
            if (folderBlocks.isNotEmpty()) {
                idx += 1
                folderBlocks.forEach { b ->
                    put(b.folder.id, idx)
                    idx += 1 + b.ids.size + (if (b.hiddenCount > 0) 1 else 0)
                }
            }
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        viewModel.newTopSessionEvent.collect {
            if (!viewModel.isSearchActive.value && !viewModel.isSelecting.value) {
                listState.animateScrollToItem(0)
            }
        }
    }

    var pendingExpandFolderId by remember { mutableStateOf<String?>(null) }
    val densityForAnchor = LocalDensity.current
    LaunchedEffect(pendingExpandFolderId) {
        val fid = pendingExpandFolderId ?: return@LaunchedEffect
        withFrameNanos {}
        withFrameNanos {}
        val key = "folder_$fid"
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.key == key }
        val slack = with(densityForAnchor) { 8.dp.toPx() }.toInt()
        val offscreen = item == null ||
            item.offset < layout.viewportStartOffset - slack ||
            item.offset + item.size > layout.viewportEndOffset + slack
        if (offscreen) {
            folderHeaderIndices[fid]?.let { listState.animateScrollToItem(it) }
        }
        pendingExpandFolderId = null
    }

    val miniBarBlock by remember(folderBlocks, folderHeaderIndices, isSelecting) {
        derivedStateOf {
            if (isSelecting) return@derivedStateOf null
            val infos = listState.layoutInfo.visibleItemsInfo
            val first = infos.firstOrNull()?.index ?: return@derivedStateOf null
            val block = folderBlocks.firstOrNull { b ->
                if (b.isCollapsed || b.ids.isEmpty()) return@firstOrNull false
                val headerIdx = folderHeaderIndices[b.folder.id] ?: return@firstOrNull false
                val lastIdx = headerIdx + b.ids.size + (if (b.hiddenCount > 0) 1 else 0)
                first in (headerIdx + 1)..lastIdx
            } ?: return@derivedStateOf null
            if (infos.any { it.key == "folder_${block.folder.id}" }) null else block
        }
    }

    val scheduledTaskCount by remember {
        com.openminis.app.scheduled.ScheduledTaskStore(context).observe()
            .map { list -> list.count { it.enabled } }
    }.collectAsState(initial = 0)
    val showScheduledBadge by remember {
        com.openminis.app.scheduled.ScheduledTaskStore(context).observeShowHomeBadge()
    }.collectAsState(initial = true)
    val scheduledBadgeCount = com.openminis.app.scheduled.ScheduledTaskStore
        .homeBadgeCount(scheduledTaskCount, showScheduledBadge)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSelecting) {
                        Text(
                            if (selectedIds.isEmpty())
                                stringResource(R.string.sessionlist_select_title)
                            else
                                stringResource(R.string.sessionlist_n_selected, selectedIds.size),
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                        )
                    } else {
                        Column {
                            Text(
                                stringResource(R.string.app_name),
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                            )
                            NetworkStatusBadges(onOpen = onNetworkPageClick)
                        }
                    }
                },
                navigationIcon = {
                    if (isSelecting) {
                        MinisTextButton(onClick = { viewModel.clearSelection() }) {
                            Text(stringResource(R.string.cancel))
                        }
                    } else {
                        val updateBadge by com.openminis.app.data.UpdateStateStore.hasUpdateBadge.collectAsState()
                        val updateChannel by com.openminis.app.data.UpdateStateStore.channel.collectAsState()
                        com.openminis.app.ui.components.BadgedIconButton(
                            onClick = onSettingsClick,
                            badge = if (com.openminis.app.data.UpdatePolicy.hasHomeBadge(updateBadge, updateChannel)) {
                                { Badge() }
                            } else {
                                null
                            },
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.sessionlist_settings))
                        }
                    }
                },
                actions = {
                    if (isSelecting) {
                        val allSelected = allShownSelected(selectedIds, persistedSessions.mapTo(HashSet()) { it.id })
                        MinisTextButton(onClick = { viewModel.toggleSelectAll() }) {
                            Text(
                                stringResource(
                                    if (allSelected) R.string.sessionlist_deselect_all
                                    else R.string.sessionlist_select_all
                                )
                            )
                        }
                    } else {
                        com.openminis.app.ui.components.BadgedIconButton(
                            onClick = onScheduledTasksClick,
                            badge = scheduledBadgeCount?.let { n ->
                                { Badge { Text("$n") } }
                            },
                        ) {
                            Icon(
                                Icons.Outlined.Schedule,
                                contentDescription = stringResource(R.string.sessionlist_scheduled_tasks),
                            )
                        }
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(Icons.Outlined.Terminal, contentDescription = stringResource(R.string.sessionlist_shell))
                            }
                            MinisMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                                offset = DpOffset(0.dp, 0.dp),
                            ) {
                                if (sessions.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.sessionlist_select_action)) },
                                        onClick = {
                                            showOverflowMenu = false
                                            viewModel.isSelecting.value = true
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.ChecklistRtl, contentDescription = null)
                                        },
                                    )
                                    MinisMenuDivider()
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sessionlist_shell_terminal)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onTerminalClick()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Terminal, contentDescription = null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sessionlist_rootfs_management)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onRootfsClick()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Settings, contentDescription = null)
                                    },
                                )
                                MinisMenuDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sessionlist_open_browser)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        browserTabPool.ensureTabForUI()
                                        showBrowserSheet = true
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Language, contentDescription = null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sessionlist_browser_settings)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showBrowserSettings = true
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Settings, contentDescription = null)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (isInitialLoadComplete) Column(modifier = Modifier.fillMaxSize()) {
                if (sessions.isEmpty() && !(searchResultsActive && folderBlocks.isNotEmpty())) {
                    if (isSearchActive && searchQuery.isNotBlank()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.search_no_results, searchQuery),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (configLoaded) {
                        OnboardingLanding(
                            hasProviders = hasProviders,
                            hasGroups = hasGroups,
                            onAddProvider = onAddProviderClick,
                            onSelectModels = onSelectModelsClick,
                            onRestoreBackup = onRestoreBackupClick,
                            onStartConversation = {
                                scope.launch {
                                    val sessionId = viewModel.createNewSession()
                                    if (sessionId != null) onNewChatGuarded(sessionId)
                                }
                            },
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
                    ) {

                        val existingFolderIds = folders.mapTo(HashSet()) { it.id }

                        fun androidx.compose.foundation.lazy.LazyListScope.renderSessionRows(
                            rows: List<ChatSessionEntity>,
                            inFolder: Boolean = false,
                            closesFolder: Boolean = true,
                        ) {
                            items(rows, key = { it.id }) { session ->
                                val activeQuery =
                                    if (isSearchActive && searchQuery.isNotBlank()) searchQuery else ""
                                val rowModifier = if (inFolder) {
                                    val isLast = closesFolder && session.id == rows.last().id
                                    Modifier
                                        .padding(
                                            start = LIST_GUTTER, end = LIST_GUTTER,
                                            bottom = if (isLast) 4.dp else 0.dp,
                                        )
                                        .folderSurface(
                                            segment = if (isLast) FolderSegment.BOTTOM
                                            else FolderSegment.MIDDLE,
                                            fill = folderFillColor(),
                                            edge = folderEdgeColor(),
                                        )
                                        .clip(
                                            if (isLast) {
                                                RoundedCornerShape(
                                                    bottomStart = 16.dp, bottomEnd = 16.dp,
                                                )
                                            } else {
                                                RoundedCornerShape(0.dp)
                                            },
                                        )
                                } else {
                                    Modifier
                                }
                                Box(
                                    modifier = Modifier
                                        .animateItem(
                                            fadeInSpec = tween(250),
                                            fadeOutSpec = tween(250),
                                            placementSpec = tween(250),
                                        )
                                        .then(rowModifier),
                                ) {
                                SessionItemContent(
                                    session = session,
                                    isSelecting = isSelecting,
                                    selectedIds = selectedIds,
                                    onSessionClick = onSessionClickGuarded,
                                    onToggleSelect = { viewModel.toggleSelect(it) },
                                    onEnterSelect = { viewModel.enterSelection(it) },
                                    onPinToggle = { viewModel.togglePin(it) },
                                    onEditRequest = { editSession = it },
                                    onExportRequest = { s, fmt ->
                                        exportSession(context, s, chatRepository, scope, fmt)
                                    },
                                    onRegenerateTitle = { viewModel.regenerateTitle(it) },
                                    onDuplicate = { viewModel.duplicateSession(it) },
                                    onDeleteRequest = { id ->
                                        deleteTargetId = id
                                        showDeleteDialog = true
                                    },
                                    onMoveToGroup = { viewModel.requestGroupPicker(it) },
                                    isFiled = session.folderId != null &&
                                        session.folderId in existingFolderIds,
                                    isRegenerating = session.id in regeneratingIds,
                                    searchQuery = activeQuery,
                                    searchSnippet = searchSnippets[session.id],
                                    rowBackground = when {
                                        session.id == selectedSessionId ->
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        inFolder -> Color.Transparent
                                        else -> null
                                    },
                                    isFolderMember = inFolder,
                                )
                                }
                            }
                        }

                        leadingDateGroups.forEach { (period, periodSessions) ->
                            item(key = "header_${period.name}") {
                                SectionHeader(title = stringResource(R.string.sessionlist_section_pinned))
                            }
                            renderSessionRows(periodSessions)
                        }

                        if (folderBlocks.isNotEmpty()) {
                            item(key = "header_groups") {
                                SectionHeader(title = stringResource(R.string.group_section_header))
                            }
                            folderBlocks.forEach { block ->
                                item(key = "folder_${block.folder.id}") {
                                    Box(Modifier.animateItem(placementSpec = tween(250))) {
                                    FolderCard(
                                        block = block,
                                        searchQuery = if (searchResultsActive) searchQuery else null,
                                        onToggle = {
                                            if (searchResultsActive) {
                                                viewModel.toggleSearchFolderCollapsed(block.folder.id)
                                                return@FolderCard
                                            }
                                            val willExpand = block.isCollapsed
                                            viewModel.toggleFolderCollapsed(block.folder.id)
                                            if (willExpand) {
                                                pendingExpandFolderId = block.folder.id
                                            }
                                        },
                                        onTogglePin = { viewModel.toggleFolderPin(block.folder.id) },
                                        onRename = { folderToRename = block.folder },
                                        onDissolve = { folderToDissolve = block.folder },
                                        onNewChatInGroup = {
                                            if (searchResultsActive) {
                                                viewModel.reopenSearchFolder(block.folder.id)
                                            } else if (block.isCollapsed) {
                                                viewModel.toggleFolderCollapsed(block.folder.id)
                                            }
                                            val sessionId = viewModel.createNewSession(
                                                folderId = block.folder.id,
                                            )
                                            if (sessionId != null) onNewChatGuarded(sessionId)
                                        },
                                        onDeleteWithSessions = {
                                            folderToDelete = block.folder to block.totalCount
                                        },
                                    )
                                    }
                                }
                                renderSessionRows(
                                    block.ids.mapNotNull { id -> sessions.firstOrNull { it.id == id } },
                                    inFolder = true,
                                    closesFolder = block.hiddenCount == 0,
                                )
                                if (block.hiddenCount > 0) {
                                    item(key = "folder_more_${block.folder.id}") {
                                        Box(Modifier.animateItem(placementSpec = tween(250))) {
                                            FolderShowMoreRow(
                                                hidden = block.hiddenCount,
                                                onClick = {
                                                    if (searchResultsActive) {
                                                        viewModel.showMoreInSearchFolder(block.folder.id, block.pagesShown)
                                                    } else {
                                                        viewModel.showMoreInFolder(block.folder.id, block.pagesShown)
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        trailingDateGroups.forEach { (period, periodSessions) ->
                            item(key = "header_${period.name}") {
                                SectionHeader(title = stringResource(when (period) {
                                    DatePeriod.PINNED -> R.string.sessionlist_section_pinned
                                    DatePeriod.TODAY -> R.string.sessionlist_section_today
                                    DatePeriod.YESTERDAY -> R.string.sessionlist_section_yesterday
                                    DatePeriod.THIS_WEEK -> R.string.sessionlist_section_this_week
                                    DatePeriod.THIS_MONTH -> R.string.sessionlist_section_this_month
                                    DatePeriod.EARLIER -> R.string.sessionlist_section_earlier
                                }))
                            }
                            renderSessionRows(periodSessions)
                        }
                    }
                }
            }

            run {
                var lastBar by remember { mutableStateOf<FolderGroupBlock?>(null) }
                miniBarBlock?.let { lastBar = it }
                val bar = lastBar
                AnimatedVisibility(
                    visible = miniBarBlock != null,
                    enter = slideInVertically(tween(200)) { -it } + fadeIn(tween(200)),
                    exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(200)),
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    if (bar != null) {
                        val headerIdx = folderHeaderIndices[bar.folder.id]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(top = 8.dp, start = 24.dp, end = 24.dp)
                                .widthIn(max = 320.dp)
                                .height(48.dp)
                                .shadow(8.dp, RoundedCornerShape(24.dp))
                                .clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .border(
                                    0.5.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    RoundedCornerShape(24.dp),
                                )
                                .padding(start = 10.dp, end = 8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable(enabled = headerIdx != null) {
                                        scope.launch {
                                            headerIdx?.let { listState.animateScrollToItem(it) }
                                        }
                                    },
                            ) {
                                FolderMemberTile(
                                    members = bar.tileMembers,
                                    size = 30.dp,
                                    loadingColor = if (bar.anyActive) groupLoadingColor(bar.activeCategory) else null,
                                )
                                Text(
                                    bar.folder.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                    )
                                    .clickable {
                                        if (searchResultsActive) viewModel.toggleSearchFolderCollapsed(bar.folder.id)
                                        else viewModel.toggleFolderCollapsed(bar.folder.id)
                                    },
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowUp,
                                    contentDescription = stringResource(R.string.group_collapse),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (isSelecting) {
                SelectionToolbar(
                    selectedCount = selectedIds.size,
                    onExport = { format ->
                        val targets = persistedSessions.filter { it.id in selectedIds }
                        if (targets.isNotEmpty() && multiExportProgress == null) {
                            multiExportProgress = 0 to 0
                            scope.launch {
                                try {
                                    val result = com.openminis.app.share.SessionExporter.exportToZip(
                                        context, targets, chatRepository, format,
                                    ) { done, total ->
                                        scope.launch { multiExportProgress = done to total }
                                    }
                                    if (result.sessionCount > 1) multiExportResult = result
                                    else shareSessionExport(context, result)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (t: Throwable) {
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.export_progress_failed),
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                } finally {
                                    multiExportProgress = null
                                }
                            }
                        }
                    },
                    onMove = { viewModel.requestGroupPickerForSelection() },
                    onDelete = { showBulkDeleteDialog = true },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            } else if (hasProviders && (sessions.isNotEmpty() || isSearchActive)) {
                DualFabRow(
                    isDark = isDark,
                    isSearchActive = isSearchActive,
                    searchQuery = searchQuery,
                    isSearching = isSearching,
                    hasSessions = sessions.isNotEmpty() || isSearchActive,
                    onNewChat = {
                        scope.launch {
                            val sessionId = viewModel.createNewSession()
                            if (sessionId != null) onNewChatGuarded(sessionId)
                        }
                    },
                    onNewChatWithGroup = { groupId ->
                        scope.launch {
                            val sessionId = viewModel.createNewSession(groupId = groupId)
                            if (sessionId != null) onNewChatGuarded(sessionId)
                        }
                    },
                    modelGroups = providerConfig.modelGroups,
                    onSearchToggle = {
                        if (isSearchActive) {
                            viewModel.searchQuery.value = ""
                            viewModel.isSearchActive.value = false
                        } else {
                            viewModel.isSearchActive.value = true
                        }
                    },
                    onSearchQueryChange = { viewModel.searchQuery.value = it },
                    onSearchDismiss = {
                        viewModel.searchQuery.value = ""
                        viewModel.isSearchActive.value = false
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    if (showDeleteDialog && deleteTargetId != null) {
        MinisAlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                deleteTargetId = null
            },
            title = stringResource(R.string.sessionlist_delete_one_title),
            text = stringResource(R.string.sessionlist_delete_message),
            confirmText = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                deleteTargetId?.let { viewModel.deleteSession(it) }
                showDeleteDialog = false
                deleteTargetId = null
            },
        )
    }

    multiExportProgress?.let { (done, total) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.sessionlist_export)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.export_progress_running, done, total))
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {},
        )
    }
    multiExportResult?.let { result ->
        SessionExportSummaryDialog(
            result = result,
            onShare = {
                multiExportResult = null
                shareSessionExport(context, result)
            },
            onDismiss = { multiExportResult = null },
        )
    }

    if (showBulkDeleteDialog) {
        MinisAlertDialog(
            onDismissRequest = { showBulkDeleteDialog = false },
            title = stringResource(R.string.sessionlist_delete_n_title, selectedIds.size),
            confirmText = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                viewModel.deleteSelected()
                showBulkDeleteDialog = false
            },
        )
    }


    groupPickerRequest?.let { request ->
        val suggesting by viewModel.groupSuggesting.collectAsState()
        val suggestFailed by viewModel.groupSuggestFailed.collectAsState()
        val suggestion by viewModel.groupSuggestion.collectAsState()
        val lastActivity by viewModel.folderLastActivity.collectAsState()
        val pickerFolders = remember(folders, lastActivity) { groupPickerOrder(folders, lastActivity) }
        GroupPickerSheet(
            folders = pickerFolders,
            memberCounts = folderMemberCounts,
            sessionCount = request.sessionIds.size,
            anyFiled = request.anyFiled,
            onChoose = { viewModel.applyGroupChoice(it) },
            onDismiss = { viewModel.dismissGroupPicker() },
            suggesting = suggesting,
            suggestFailed = suggestFailed,
            suggestion = suggestion,
            onSuggest = { viewModel.suggestGroup() },
        )
    }

    folderToRename?.let { folder ->
        var name by remember(folder.id) { mutableStateOf(folder.name) }
        var desc by remember(folder.id) { mutableStateOf(folder.description.orEmpty()) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text(stringResource(R.string.group_rename)) },
            text = {
                Column {
                    DialogTextFieldFrame {
                        SectionTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = stringResource(R.string.group_name_hint),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    DialogTextFieldFrame {
                        SectionTextField(
                            value = desc,
                            onValueChange = { desc = it.take(FolderEntity.DESC_MAX_CHARS) },
                            placeholder = stringResource(R.string.group_desc_hint),
                        )
                    }
                }
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    viewModel.renameFolder(folder.id, name, desc)
                    folderToRename = null
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { folderToRename = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    folderToDissolve?.let { folder ->
        val count = folderMemberCounts[folder.id] ?: 0
        MinisAlertDialog(
            onDismissRequest = { folderToDissolve = null },
            title = stringResource(R.string.group_dissolve_confirm_title),
            text = stringResource(R.string.group_dissolve_confirm_message, count),
            confirmText = stringResource(R.string.group_dissolve),
            onConfirm = {
                viewModel.dissolveFolder(folder.id)
                folderToDissolve = null
            },
        )
    }

    folderToDelete?.let { (folder, count) ->
        MinisAlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = stringResource(R.string.group_delete_confirm_title),
            text = stringResource(R.string.group_delete_confirm_message, count),
            confirmText = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                viewModel.deleteFolderWithSessions(folder.id)
                folderToDelete = null
            },
        )
    }

    editSession?.let { session ->
        val liveSession = sessions.firstOrNull { it.id == session.id } ?: session
        SessionEditSheet(
            session = session,
            liveSession = liveSession,
            isRegenerating = session.id in regeneratingIds,
            onRegenerate = { viewModel.regenerateTitle(session.id) },
            onDismiss = { editSession = null },
            onSave = { title, category ->
                viewModel.updateTitleAndCategory(session.id, title, category)
                editSession = null
            },
        )
    }

    if (showBrowserSheet) {
        com.openminis.app.ui.browser.BrowserSheet(
            tabPool = browserTabPool,
            onDismiss = { showBrowserSheet = false },
        )
    }

    if (showBrowserSettings) {
        com.openminis.app.ui.browser.BrowserSettingsSheet(
            tabPool = browserTabPool,
            onDismiss = { showBrowserSettings = false },
        )
    }
}


private const val PREF_FAB_SWAPPED = "fab_swapped"

@Composable
private fun DualFabRow(
    isDark: Boolean,
    isSearchActive: Boolean,
    searchQuery: String,
    isSearching: Boolean,
    hasSessions: Boolean,
    onNewChat: () -> Unit,
    onNewChatWithGroup: (String) -> Unit,
    modelGroups: List<com.openminis.app.data.model.ModelGroup>,
    onSearchToggle: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE) }
    var isSwapped by remember { mutableStateOf(prefs.getBoolean(PREF_FAB_SWAPPED, false)) }

    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            kotlinx.coroutines.delay(50)
            runCatching { searchFocusRequester.requestFocus() }
            keyboardController?.show()
        }
    }

    var chatDragX by remember { mutableFloatStateOf(0f) }
    var searchDragX by remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current
    val swapThreshold = with(density) { 100.dp.toPx() }

    var showGroupMenu by remember { mutableStateOf(false) }
    val topGroups = remember(modelGroups) { modelGroups.take(10) }

    val chatFab: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .offset { IntOffset(chatDragX.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (kotlin.math.abs(chatDragX) > swapThreshold) {
                                isSwapped = !isSwapped
                                prefs.edit().putBoolean(PREF_FAB_SWAPPED, isSwapped).apply()
                            }
                            chatDragX = 0f
                        },
                        onDragCancel = { chatDragX = 0f },
                        onHorizontalDrag = { _, dragAmount -> chatDragX += dragAmount },
                    )
                },
        ) {
            FloatingActionButton(
                onClick = onNewChat,
                shape = CircleShape,
                containerColor = minisFabColor(),
                modifier = Modifier
                    .size(56.dp)
                    .shadow(6.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.15f))
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = onNewChat,
                        onLongClick = {
                            if (topGroups.isNotEmpty()) showGroupMenu = true
                        },
                    ),
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
            ) {
                Icon(Icons.Outlined.Forum, contentDescription = "New Chat", tint = Color.White, modifier = Modifier.size(24.dp))
            }
            DropdownMenu(
                expanded = showGroupMenu,
                onDismissRequest = { showGroupMenu = false },
            ) {
                topGroups.forEach { group ->
                    DropdownMenuItem(
                        text = { Text(group.name) },
                        leadingIcon = { Icon(Icons.Outlined.Forum, contentDescription = null) },
                        onClick = {
                            showGroupMenu = false
                            onNewChatWithGroup(group.id)
                        },
                    )
                }
            }
        }
    }

    val searchFab: @Composable () -> Unit = {
        if (hasSessions) {
            AnimatedVisibility(
                visible = !isSearchActive,
                enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.85f),
                exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.85f),
            ) {
                FloatingActionButton(
                    onClick = onSearchToggle,
                    shape = CircleShape,
                    containerColor = ChatColors.secondaryBg,
                    modifier = Modifier
                        .size(56.dp)
                        .offset { IntOffset(searchDragX.roundToInt(), 0) }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (kotlin.math.abs(searchDragX) > swapThreshold) {
                                        isSwapped = !isSwapped
                                        prefs.edit().putBoolean(PREF_FAB_SWAPPED, isSwapped).apply()
                                    }
                                    searchDragX = 0f
                                },
                                onDragCancel = { searchDragX = 0f },
                                onHorizontalDrag = { _, dragAmount -> searchDragX += dragAmount },
                            )
                        }
                        .shadow(6.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.15f)),
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
                ) {
                    Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.sessionlist_search_action), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                }
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSwapped) { searchFab(); } else { chatFab() }

        AnimatedVisibility(
            visible = isSearchActive,
            enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.85f),
            exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.85f),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_chats_placeholder)) },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    if (isSearching) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = onSearchDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.sessionlist_dismiss), modifier = Modifier.size(18.dp))
                        }
                    }
                },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.8f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    .heightIn(min = 48.dp)
                    .focusRequester(searchFocusRequester),
            )
        }

        if (isSwapped) { chatFab() } else { searchFab() }
    }
}


@Composable
private fun SelectionToolbar(
    selectedCount: Int,
    onExport: (com.openminis.app.share.SessionExporter.Format) -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        var showExportMenu by remember { mutableStateOf(false) }
        Box {
            MinisTextButton(
                onClick = { showExportMenu = true },
                enabled = selectedCount > 0,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = stringResource(R.string.sessionlist_export),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.sessionlist_export), fontSize = 11.sp)
                }
            }
            DropdownMenu(expanded = showExportMenu, onDismissRequest = { showExportMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_export_json)) },
                    onClick = {
                        showExportMenu = false
                        onExport(com.openminis.app.share.SessionExporter.Format.JSON)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_export_plain)) },
                    onClick = {
                        showExportMenu = false
                        onExport(com.openminis.app.share.SessionExporter.Format.PLAIN_TEXT)
                    },
                )
            }
        }

        MinisTextButton(
            onClick = onMove,
            enabled = selectedCount > 0,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = stringResource(R.string.group_move_action),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.group_move_action), fontSize = 11.sp)
            }
        }

        MinisTextButton(
            onClick = onDelete,
            enabled = selectedCount > 0,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = if (selectedCount > 0) MaterialTheme.colorScheme.error else Color.Gray,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.delete),
                    fontSize = 11.sp,
                    color = if (selectedCount > 0) MaterialTheme.colorScheme.error else Color.Gray,
                )
            }
        }
    }
}


@Composable
private fun SectionHeader(title: String) {
    val isPinned = title == stringResource(R.string.sessionlist_section_pinned)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .padding(top = 4.dp),
    ) {
        if (isPinned) {
            Icon(
                imageVector = Icons.Default.PushPin,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(14.dp)
                    .padding(end = 0.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionItemContent(
    session: ChatSessionEntity,
    isSelecting: Boolean,
    selectedIds: Set<String>,
    onSessionClick: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onEnterSelect: (String) -> Unit,
    onPinToggle: (String) -> Unit,
    onEditRequest: (ChatSessionEntity) -> Unit,
    onExportRequest: (ChatSessionEntity, String) -> Unit,
    onRegenerateTitle: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onDeleteRequest: (String) -> Unit,
    onMoveToGroup: (String) -> Unit,
    isFiled: Boolean,
    isRegenerating: Boolean = false,
    searchQuery: String = "",
    searchSnippet: String? = null,
    rowBackground: Color? = null,
    isFolderMember: Boolean = false,
) {
    if (isSelecting) {
        val isSelected = session.id in selectedIds
        SessionRow(
            session = session,
            onClick = { onToggleSelect(session.id) },
            onLongClick = null,
            searchQuery = searchQuery,
            searchSnippet = searchSnippet,
            rowBackground = rowBackground,
            isFolderMember = isFolderMember,
            leadingIcon = {
                Icon(
                    imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            },
        )
    } else {
        var showContextMenu by remember { mutableStateOf(false) }
        var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
        var menuAlignEnd by remember { mutableStateOf(false) }
        var rowWidthPx by remember { mutableFloatStateOf(0f) }
        val density = LocalDensity.current
        val isPinned = session.pinnedAt != null

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { rowWidthPx = it.width.toFloat() },
        ) {
            SessionRow(
                session = session,
                onClick = { onSessionClick(session.id) },
                searchQuery = searchQuery,
                searchSnippet = searchSnippet,
                rowBackground = rowBackground,
                isFolderMember = isFolderMember,
                onLongClick = { offsetPx ->
                    pressOffset = with(density) {
                        DpOffset(offsetPx.x.toDp(), offsetPx.y.toDp())
                    }
                    menuAlignEnd = rowWidthPx > 0f && offsetPx.x > rowWidthPx / 2f
                    showContextMenu = true
                },
            )
            if (isRegenerating) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            stringResource(R.string.sessionlist_regenerating_title),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .offset(x = pressOffset.x, y = pressOffset.y)
                    .size(1.dp),
            ) {
                MinisMenu(
                    expanded = showContextMenu,
                    onDismissRequest = { showContextMenu = false },
                    alignEnd = menuAlignEnd,
                ) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (isPinned) R.string.sessionlist_unpin else R.string.sessionlist_pin)) },
                    onClick = {
                        showContextMenu = false
                        onPinToggle(session.id)
                    },
                    leadingIcon = {
                        Icon(
                            if (isPinned) Icons.Default.Close else Icons.Default.PushPin,
                            contentDescription = null,
                        )
                    },
                )
                var showExportSub by remember { mutableStateOf(false) }
                DropdownMenuItem(
                    text = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.sessionlist_export))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    },
                    onClick = { showExportSub = !showExportSub },
                    leadingIcon = {
                        Icon(Icons.Default.Share, contentDescription = null)
                    },
                )
                if (showExportSub) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessionlist_export_json), modifier = Modifier.padding(start = 24.dp)) },
                        onClick = {
                            showContextMenu = false
                            onExportRequest(session, "json")
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessionlist_export_plain), modifier = Modifier.padding(start = 24.dp)) },
                        onClick = {
                            showContextMenu = false
                            onExportRequest(session, "text")
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_edit_title_category)) },
                    onClick = {
                        showContextMenu = false
                        onEditRequest(session)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Edit, contentDescription = null)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_regenerate_title)) },
                    onClick = {
                        showContextMenu = false
                        onRegenerateTitle(session.id)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_duplicate)) },
                    onClick = {
                        showContextMenu = false
                        onDuplicate(session.id)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (isFiled) R.string.group_change
                                else R.string.group_move_to,
                            ),
                        )
                    },
                    onClick = {
                        showContextMenu = false
                        onMoveToGroup(session.id)
                    },
                    leadingIcon = {
                        Icon(
                            if (isFiled) Icons.Default.DriveFileMove
                            else Icons.Default.Folder,
                            contentDescription = null,
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_select_action)) },
                    onClick = {
                        showContextMenu = false
                        onEnterSelect(session.id)
                    },
                    leadingIcon = {
                        Icon(Icons.Outlined.ChecklistRtl, contentDescription = null)
                    },
                )
                MinisMenuDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showContextMenu = false
                        onDeleteRequest(session.id)
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)

private enum class FolderSegment { LONE, TOP, MIDDLE, BOTTOM }

@Composable
private fun folderEdgeColor(): Color =
    if (ChatColors.isDark) Color.White.copy(alpha = 0.30f)
    else Color.Black.copy(alpha = 0.08f)

@Composable
private fun folderFillColor(): Color = MaterialTheme.colorScheme.surfaceContainerLow

private fun Modifier.folderSurface(
    segment: FolderSegment,
    fill: Color,
    edge: Color,
): Modifier = drawWithContent {
    val r = 16.dp.toPx()
    val w = size.width
    val h = size.height
    val cr = CornerRadius(r, r)

    val fillPath = Path().apply {
        when (segment) {
            FolderSegment.LONE -> addRoundRect(RoundRect(0f, 0f, w, h, cr))
            FolderSegment.TOP -> addRoundRect(
                RoundRect(
                    rect = Rect(0f, 0f, w, h),
                    topLeft = cr, topRight = cr,
                    bottomLeft = CornerRadius.Zero, bottomRight = CornerRadius.Zero,
                ),
            )
            FolderSegment.MIDDLE -> addRect(Rect(0f, 0f, w, h))
            FolderSegment.BOTTOM -> addRoundRect(
                RoundRect(
                    rect = Rect(0f, 0f, w, h),
                    topLeft = CornerRadius.Zero, topRight = CornerRadius.Zero,
                    bottomLeft = cr, bottomRight = cr,
                ),
            )
        }
    }
    drawPath(fillPath, fill)
    drawContent()

    val border = Path().apply {
        when (segment) {
            FolderSegment.LONE -> addRoundRect(RoundRect(0f, 0f, w, h, cr))
            FolderSegment.TOP -> {
                moveTo(0f, h)
                lineTo(0f, r)
                arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
                lineTo(w - r, 0f)
                arcTo(Rect(w - 2 * r, 0f, w, 2 * r), 270f, 90f, false)
                lineTo(w, h)
            }
            FolderSegment.MIDDLE -> {
                moveTo(0f, 0f); lineTo(0f, h)
                moveTo(w, 0f); lineTo(w, h)
            }
            FolderSegment.BOTTOM -> {
                moveTo(0f, 0f)
                lineTo(0f, h - r)
                arcTo(Rect(0f, h - 2 * r, 2 * r, h), 180f, -90f, false)
                lineTo(w - r, h)
                arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 90f, -90f, false)
                lineTo(w, 0f)
            }
        }
    }
    drawPath(border, edge, style = Stroke(width = 0.75.dp.toPx()))
}

@Composable
private fun FolderShowMoreRow(hidden: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(start = LIST_GUTTER, end = LIST_GUTTER, bottom = 4.dp)
            .folderSurface(FolderSegment.BOTTOM, fill = folderFillColor(), edge = folderEdgeColor())
            .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AVATAR_INSET, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            stringResource(R.string.sessionlist_folder_show_more, FolderPages.nextPageCount(hidden), hidden),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun groupGlyphPath(side: Float): Path = Path().apply {
    fillType = PathFillType.EvenOdd
    val u = side / 1024f

    fun ring(x: Float, y: Float) {
        val outer = Rect(x * u, y * u, (x + 325.8f) * u, (y + 325.8f) * u)
        addRoundRect(RoundRect(outer, CornerRadius(93f * u)))
        val inner = Rect(
            outer.left + 46.5f * u, outer.top + 46.5f * u,
            outer.right - 46.5f * u, outer.bottom - 46.5f * u,
        )
        addRoundRect(RoundRect(inner, CornerRadius(46.5f * u)))
    }

    fun line(cy: Float) {
        val rect = Rect(
            558.5f * u, (cy - 23.27f) * u,
            (558.5f + 325.8f) * u, (cy + 23.27f) * u,
        )
        addRoundRect(RoundRect(rect, CornerRadius(23.27f * u)))
    }

    ring(139.6f, 139.6f)
    ring(139.6f, 511.9f)
    line(209.5f)
    line(395.6f)
    line(581.8f)
    line(768.0f)
}

@Composable
private fun FolderMemberTile(members: List<FolderTileMember>, size: Dp = 44.dp, loadingColor: Color? = null) {
    val scale = size.value / 44f
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(size)
            .background(
                muted.copy(alpha = if (ChatColors.isDark) 0.22f else 0.16f),
                RoundedCornerShape((12f * scale).dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (loadingColor != null) {
            GroupLoadingBorder(
                cornerRadius = (12f * scale).dp,
                color = loadingColor,
                modifier = Modifier.matchParentSize(),
            )
        }
        if (members.isEmpty()) {
            Canvas(Modifier.size(size * 0.5f)) {
                drawPath(groupGlyphPath(this.size.width), muted)
            }
            return@Box
        }
        memberTileCells(members.size).zip(members).forEach { (cell, member) ->
            val style = categoryStyle(member.category)
            val side = (cell.side * scale).dp
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (cell.x * scale).dp, y = (cell.y * scale).dp)
                    .size(side)
                    .background(style.color.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = style.color,
                    modifier = Modifier.size(side * 0.55f),
                )
            }
        }
    }
}

internal data class MemberTileCell(val x: Float, val y: Float, val side: Float)

internal fun memberTileCells(n: Int): List<MemberTileCell> {
    val pad = 4f
    val gap = 2f
    val inner = 44f - 2 * pad
    val cell = (inner - gap) / 2
    val far = pad + cell + gap
    val mid = pad + (inner - cell) / 2
    return when {
        n <= 0 -> emptyList()
        n == 1 -> listOf(MemberTileCell(mid, mid, cell))
        n == 2 -> listOf(MemberTileCell(pad, mid, cell), MemberTileCell(far, mid, cell))
        n == 3 -> listOf(MemberTileCell(mid, pad, cell), MemberTileCell(pad, far, cell), MemberTileCell(far, far, cell))
        else -> listOf(
            MemberTileCell(pad, pad, cell), MemberTileCell(far, pad, cell),
            MemberTileCell(pad, far, cell), MemberTileCell(far, far, cell),
        )
    }
}

private val AVATAR_INSET = 12.dp

private val LIST_GUTTER = 4.dp

@Composable
private fun FolderCard(
    block: FolderGroupBlock,
    searchQuery: String? = null,
    onToggle: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDissolve: () -> Unit,
    onNewChatInGroup: () -> Unit,
    onDeleteWithSessions: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
    var menuAlignEnd by remember { mutableStateOf(false) }
    val headerPressInteractions = remember { MutableInteractionSource() }
    val density = LocalDensity.current
    val expandLabel = stringResource(
        if (block.isCollapsed) R.string.group_expand else R.string.group_collapse,
    )
    val isExpandedWithRows = !block.isCollapsed && block.ids.isNotEmpty()
    val chevronRotation by animateFloatAsState(
        targetValue = if (block.isCollapsed) -90f else 0f,
        animationSpec = tween(250),
        label = "folderChevron",
    )
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val dateText = remember(block.latestUpdatedAt, ctx) {
        relativeDate(ctx, block.latestUpdatedAt)
    }
    val headerHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .padding(
                start = LIST_GUTTER, end = LIST_GUTTER, top = 4.dp,
                bottom = if (isExpandedWithRows) 0.dp else 4.dp,
            )
            .folderSurface(
                segment = if (isExpandedWithRows) FolderSegment.TOP else FolderSegment.LONE,
                fill = folderFillColor(),
                edge = folderEdgeColor(),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(
                    if (isExpandedWithRows) {
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    } else {
                        RoundedCornerShape(16.dp)
                    },
                )
                .indication(headerPressInteractions, LocalIndication.current)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { offset ->
                            val press = PressInteraction.Press(offset)
                            headerPressInteractions.emit(press)
                            val released = tryAwaitRelease()
                            headerPressInteractions.emit(
                                if (released) PressInteraction.Release(press)
                                else PressInteraction.Cancel(press),
                            )
                        },
                        onTap = { onToggle() },
                        onLongPress = { offset ->
                            headerHaptics.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                            )
                            pressOffset = with(density) {
                                DpOffset(offset.x.toDp(), offset.y.toDp())
                            }
                            menuAlignEnd = offset.x > size.width / 2f
                            menuOpen = true
                        },
                    )
                }
                .semantics(mergeDescendants = true) {
                    onClick(label = expandLabel) { onToggle(); true }
                }
                .padding(AVATAR_INSET),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(44.dp)) {
                FolderMemberTile(
                    members = block.tileMembers,
                    loadingColor = if (block.isCollapsed && block.anyActive) groupLoadingColor(block.activeCategory) else null,
                )
                if (block.isCollapsed && block.anyUnread) {
                    UnreadDot(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 2.5.dp, y = (-2.5).dp),
                        ringColor = folderFillColor(),
                    )
                }
                if (block.isCollapsed && block.anyPaused) {
                    SessionBadgeOverlay(
                        state = com.openminis.app.service.SessionBadgeStore
                            .SessionBadgeState.PAUSED,
                        modifier = Modifier.align(Alignment.BottomEnd),
                        ringColor = folderFillColor(),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    if (searchQuery != null) highlightedAnnotatedString(block.folder.name, searchQuery)
                    else androidx.compose.ui.text.AnnotatedString(block.folder.name),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        searchQuery != null && block.isCollapsed && block.totalCount > 0 ->
                            stringResource(R.string.sessionlist_folder_matches_hidden, block.totalCount)
                        searchQuery != null ->
                            stringResource(R.string.sessionlist_folder_matching_chats, block.totalCount)
                        block.totalCount > 0 && block.summaryTitle != null ->
                            stringResource(R.string.group_n_chats, block.totalCount) +
                                " · " + block.summaryTitle
                        block.totalCount > 0 ->
                            stringResource(R.string.group_n_chats, block.totalCount)
                        else -> stringResource(R.string.group_empty)
                    },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    dateText,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (block.folder.isPinned) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(chevronRotation),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .offset(x = pressOffset.x, y = pressOffset.y)
                .size(1.dp),
        ) {
            MinisMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                alignEnd = menuAlignEnd,
            ) {
                val menuIcon: @Composable (androidx.compose.ui.graphics.vector.ImageVector) -> Unit =
                    { image ->
                        Icon(
                            image,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (block.folder.isPinned) R.string.sessionlist_unpin
                                else R.string.sessionlist_pin,
                            ),
                        )
                    },
                    onClick = { menuOpen = false; onTogglePin() },
                    leadingIcon = { menuIcon(Icons.Outlined.PushPin) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_rename)) },
                    onClick = { menuOpen = false; onRename() },
                    leadingIcon = { menuIcon(Icons.Outlined.Edit) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_new_chat_in)) },
                    onClick = { menuOpen = false; onNewChatInGroup() },
                    leadingIcon = { menuIcon(Icons.Outlined.AddComment) },
                )
                MinisMenuDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_dissolve)) },
                    onClick = { menuOpen = false; onDissolve() },
                    leadingIcon = { menuIcon(Icons.Outlined.FolderOff) },
                )
                MinisMenuDivider()
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                R.string.group_delete_with_sessions, block.totalCount,
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = { menuOpen = false; onDeleteWithSessions() },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                )
            }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: ChatSessionEntity,
    onClick: () -> Unit,
    onLongClick: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    searchQuery: String = "",
    searchSnippet: String? = null,
    rowBackground: Color? = null,
    isFolderMember: Boolean = false,
) {
    val style = remember(session.category) { categoryStyle(session.category) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val timeText = remember(session.updatedAt, ctx) { relativeDate(ctx, session.updatedAt) }
    val rowHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    val pressInteractions = remember { MutableInteractionSource() }
    val inFolder = isFolderMember
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!inFolder) {
                    Modifier
                        .padding(horizontal = LIST_GUTTER)
                        .clip(RoundedCornerShape(16.dp))
                } else {
                    Modifier
                }
            )
            .background(rowBackground ?: MaterialTheme.colorScheme.surface)
            .then(
                if (onLongClick != null) {
                    Modifier
                        .indication(pressInteractions, LocalIndication.current)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = { offset ->
                                    val press = PressInteraction.Press(offset)
                                    pressInteractions.emit(press)
                                    val released = tryAwaitRelease()
                                    pressInteractions.emit(
                                        if (released) PressInteraction.Release(press)
                                        else PressInteraction.Cancel(press),
                                    )
                                },
                                onTap = {
                                    com.openminis.app.diagnostics.PerfLongCtx.click(session.id)
                                    onClick()
                                },
                                onLongPress = { offset ->
                                    rowHaptics.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                    )
                                    onLongClick(offset)
                                },
                            )
                        }
                } else {
                    Modifier.clickable {
                        com.openminis.app.diagnostics.PerfLongCtx.click(session.id)
                        onClick()
                    }
                }
            )
            .padding(AVATAR_INSET),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leadingIcon != null) {
            leadingIcon()
        }

        val processingSessions by SessionActivityTracker.processingSessions.collectAsState()
        val isActive = session.id in processingSessions
        val badgeMap by com.openminis.app.service.SessionBadgeStore.byId.collectAsState()
        val badgeHead = badgeMap[session.id]?.firstOrNull()
        val unreadIds by com.openminis.app.service.SessionUnreadStore.ids.collectAsState()
        Box(
            modifier = Modifier.size(44.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = style.color.copy(alpha = 0.18f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = style.color,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .align(Alignment.Center),
                    contentAlignment = Alignment.Center,
                ) {
                    SpinningRing(
                        color = style.color,
                        modifier = Modifier.size(42.dp),
                    )
                }
            }
            if (badgeHead != null) {
                SessionBadgeOverlay(
                    state = badgeHead,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
            if (session.id in unreadIds) {
                UnreadDot(modifier = Modifier.align(Alignment.TopEnd).offset(x = (-1).dp, y = 1.dp))
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            val titleText = session.title ?: "New Chat"
            if (searchQuery.isNotBlank()) {
                Text(
                    text = highlightedAnnotatedString(titleText, searchQuery),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = titleText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (searchQuery.isNotBlank() && searchSnippet != null) {
                Text(
                    text = highlightedAnnotatedString(searchSnippet, searchQuery),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = session.lastMessage ?: "No messages yet",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Text(
            text = timeText,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun DialogTextFieldFrame(content: @Composable () -> Unit) {
    Surface(
        shape = SectionDesign.CardShape,
        color = SectionDesign.cardColor(),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.padding(horizontal = 12.dp)) { content() }
    }
}

@Composable
private fun GroupLoadingBorder(cornerRadius: Dp, color: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val still = remember(context) { com.openminis.app.ui.chat.animationsDisabled(context) }
    val tick = if (still) null else rememberDecorativeTick()
    Spacer(
        modifier
            .graphicsLayer()
            .drawWithCache {
                val strokePx = GROUP_LOADING_STROKE.toPx()
                val half = strokePx / 2
                val outline = Path().apply {
                    addRoundRect(
                        RoundRect(
                            Rect(half, half, size.width - half, size.height - half),
                            CornerRadius((cornerRadius.toPx() - half).coerceAtLeast(0f)),
                        ),
                    )
                }
                val stroke = Stroke(width = strokePx)
                val track = color.copy(alpha = 0.22f)
                val cx = size.width / 2
                val cy = size.height / 2
                val shader = android.graphics.SweepGradient(
                    cx, cy,
                    intArrayOf(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                        color.toArgb(),
                    ),
                    floatArrayOf(0f, 0.35f, 1f),
                )
                val comet = ShaderBrush(shader)
                val matrix = android.graphics.Matrix()
                onDrawBehind {
                    if (tick == null) {
                        drawPath(outline, color, style = stroke)
                        return@onDrawBehind
                    }
                    drawPath(outline, track, style = stroke)
                    matrix.setRotate(decorativePhase(tick.value, GROUP_LOADING_PERIOD_MS) * 360f, cx, cy)
                    shader.setLocalMatrix(matrix)
                    drawPath(outline, comet, style = stroke)
                }
            },
    )
}

@Composable
private fun groupLoadingColor(category: String?): Color {
    val style = categoryStyle(category)
    return if (category.isNullOrBlank() || style == categoryStyle(null)) MaterialTheme.colorScheme.primary else style.color
}

private val GROUP_LOADING_STROKE = 2.5.dp
private const val GROUP_LOADING_PERIOD_MS = 1500

@Composable
private fun SpinningRing(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val tick = rememberDecorativeTick()
    val arcColor = remember(color) { color.copy(alpha = 0.8f) }
    Canvas(
        modifier = modifier.graphicsLayer {
            rotationZ = decorativePhase(tick.value, 1000) * 360f
        },
    ) {
        drawArc(
            color = arcColor,
            startAngle = 0f,
            sweepAngle = 360f * 0.3f,
            useCenter = false,
            size = Size(size.width, size.height),
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun UnreadDot(modifier: Modifier = Modifier, ringColor: Color? = null) {
    val red = if (ChatColors.isDark) Color(0xFFFF453A) else Color(0xFFFF3B30)
    Box(
        modifier = modifier
            .then(if (ringColor != null) Modifier.background(ringColor, CircleShape).padding(1.5.dp) else Modifier)
            .size(8.dp)
            .background(red, CircleShape),
    )
}

@Composable
private fun SessionBadgeOverlay(
    state: com.openminis.app.service.SessionBadgeStore.SessionBadgeState,
    modifier: Modifier = Modifier,
    ringColor: Color = MaterialTheme.colorScheme.surface,
) {
    when (state) {
        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED -> {
            Box(
                modifier = modifier
                    .offset(x = 2.dp, y = 2.dp)
                    .size(14.dp)
                    .background(
                        color = Color(0xFFFF9500),
                        shape = CircleShape,
                    )
                    .border(
                        width = 1.5.dp,
                        color = ringColor,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Pause,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(9.dp),
                )
            }
        }
        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.ICLOUD_SYNCING -> {
        }
    }
}


@Composable
private fun OnboardingLanding(
    hasProviders: Boolean,
    hasGroups: Boolean,
    onAddProvider: () -> Unit,
    onSelectModels: () -> Unit,
    onStartConversation: () -> Unit,
    onRestoreBackup: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
            .padding(bottom = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.sessionlist_welcome_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.sessionlist_welcome_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SetupStepCard(
                number = 1,
                title = stringResource(R.string.sessionlist_welcome_step1_title),
                subtitle = if (hasProviders) {
                    stringResource(R.string.sessionlist_welcome_step_done)
                } else {
                    stringResource(R.string.sessionlist_welcome_step1_subtitle)
                },
                isDone = hasProviders,
                isLocked = false,
                onClick = { if (!hasProviders) onAddProvider() },
            )

            SetupStepCard(
                number = 2,
                title = stringResource(R.string.sessionlist_welcome_step2_title),
                subtitle = when {
                    hasGroups -> stringResource(R.string.sessionlist_welcome_step_done)
                    hasProviders -> stringResource(R.string.sessionlist_welcome_step2_subtitle)
                    else -> stringResource(R.string.sessionlist_welcome_step2_locked)
                },
                isDone = hasGroups,
                isLocked = !hasProviders,
                onClick = { if (hasProviders && !hasGroups) onSelectModels() },
            )

            SetupStepCard(
                number = 3,
                title = stringResource(R.string.sessionlist_welcome_step3_title),
                subtitle = if (hasGroups) {
                    stringResource(R.string.sessionlist_welcome_step3_subtitle)
                } else {
                    stringResource(R.string.sessionlist_welcome_step3_locked)
                },
                isDone = false,
                isLocked = !hasGroups,
                onClick = { if (hasGroups) onStartConversation() },
            )
        }

        if (!hasGroups) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onRestoreBackup)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.sessionlist_welcome_restore_backup),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SetupStepCard(
    number: Int,
    title: String,
    subtitle: String,
    isDone: Boolean,
    isLocked: Boolean,
    onClick: () -> Unit,
) {
    val isEnabled = !isDone && !isLocked

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(enabled = isEnabled, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    color = if (isDone) Color(0xFF34C759) else MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isDone) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Text(
                    text = "$number",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f).height(56.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (isDone) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!isDone && isEnabled) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}


private val allCategories = listOf(
    "Code", "Writing", "Research", "Analysis",
    "Creative", "Chat", "Math", "Translation",
    "Health", "Finance", "Travel", "Education",
    "Design", "Productivity", "Support", "Other",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionEditSheet(
    session: ChatSessionEntity,
    onDismiss: () -> Unit,
    onSave: (title: String, category: String?) -> Unit,
    liveSession: ChatSessionEntity = session,
    isRegenerating: Boolean = false,
    onRegenerate: () -> Unit = {},
) {
    var title by remember { mutableStateOf(session.title ?: "") }
    var selectedCategory by remember { mutableStateOf(session.category) }

    LaunchedEffect(liveSession.title, liveSession.category) {
        liveSession.title?.let { title = it }
        selectedCategory = liveSession.category
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MinisTextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.weight(1f))
                Text(
                    "Edit Session",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                MinisTextButton(
                    onClick = { onSave(title.ifBlank { "New Chat" }, selectedCategory) },
                ) { Text("Save") }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(20.dp))

            Text(
                "Category",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(240.dp),
            ) {
                items(allCategories) { cat ->
                    val isSelected = selectedCategory?.equals(cat, ignoreCase = true) == true
                    val style = categoryStyle(cat.lowercase())
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) style.color.copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .clickable {
                                selectedCategory = if (isSelected) null else cat.lowercase()
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = style.icon,
                                contentDescription = null,
                                tint = style.color,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                cat,
                                fontSize = 11.sp,
                                color = if (isSelected) style.color
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            MinisOutlinedButton(
                onClick = onRegenerate,
                enabled = !isRegenerating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isRegenerating) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sessionlist_regenerating_title))
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sessionlist_regenerate_title))
                }
            }
        }
    }
}


private fun shareSessionExport(context: Context, result: com.openminis.app.share.SessionExporter.Result) {
    val mime = when {
        result.fileName.endsWith(".zip") -> "application/zip"
        result.format == com.openminis.app.share.SessionExporter.Format.JSON -> "application/json"
        else -> "text/plain"
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_SUBJECT, result.fileName.substringBeforeLast('.'))
        putExtra(Intent.EXTRA_STREAM, result.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(intent, context.getString(R.string.sessionlist_export))
            .apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) },
    )
}

@Composable
private fun SessionExportSummaryDialog(
    result: com.openminis.app.share.SessionExporter.Result,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val s = result.summary
    val dateFmt = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) }
    val rows = buildList {
        add(
            stringResource(R.string.export_summary_format) to stringResource(
                if (result.format == com.openminis.app.share.SessionExporter.Format.JSON) R.string.export_summary_format_value_json
                else R.string.export_summary_format_value_txt,
            ),
        )
        add(stringResource(R.string.export_summary_sessions) to result.sessionCount.toString())
        add(stringResource(R.string.export_summary_messages) to stringResource(R.string.export_summary_messages_value, s.totalMessages))
        if (s.earliest != null && s.latest != null) {
            add(
                stringResource(R.string.export_summary_timerange) to stringResource(
                    R.string.export_summary_timerange_value,
                    dateFmt.format(java.util.Date(s.earliest)),
                    dateFmt.format(java.util.Date(s.latest)),
                ),
            )
        }
        if (s.attachmentCount > 0) {
            add(stringResource(R.string.export_summary_attachments) to stringResource(R.string.export_summary_attachments_value, s.images, s.videos))
        }
        add(
            stringResource(R.string.export_summary_size) to stringResource(
                R.string.export_summary_size_value,
                android.text.format.Formatter.formatShortFileSize(context, result.fileSizeBytes),
            ),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_progress_done)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, value) in rows) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        Spacer(Modifier.width(12.dp))
                        Text(value, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onShare) { Text(stringResource(R.string.common_share)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

private fun exportSession(
    context: Context,
    session: ChatSessionEntity,
    chatRepository: ChatRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    format: String,
) {
    scope.launch {
        try {
            val (uri, _) = com.openminis.app.share.ChatExporter.exportToZip(
                context = context,
                session = session,
                repository = chatRepository,
                format = format,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_SUBJECT, session.title ?: "Conversation")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(
                intent,
                context.getString(R.string.sessionlist_export),
            ).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            context.startActivity(chooser)
        } catch (t: Throwable) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.export_progress_failed),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
}
