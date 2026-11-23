package com.openminis.app.ui.sandbox

import com.openminis.app.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.openminis.app.ui.components.MinisTextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    viewModel: FileBrowserViewModel,
    onBack: () -> Unit,
    onPreviewFile: (FileItem) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var deleteTarget by remember { mutableStateOf<FileItem?>(null) }
    var webAppSheetSource by remember { mutableStateOf<com.openminis.app.webapp.WebAppSource.HostFile?>(null) }

    val handleBack = {
        if (!state.canGoBack || !viewModel.goBack()) onBack()
    }

    BackHandler(enabled = true, onBack = handleBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.filebrowser_title)) },
                navigationIcon = {
                    IconButton(onClick = handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    MoreMenu(
                        sortKey = state.sortKey,
                        ascending = state.sortAscending,
                        foldersFirst = state.foldersFirst,
                        showHidden = state.showHidden,
                        onSelectKey = { viewModel.setSort(key = it) },
                        onToggleDirection = { viewModel.setSort(ascending = !state.sortAscending) },
                        onToggleFoldersFirst = { viewModel.setSort(foldersFirst = !state.foldersFirst) },
                        onToggleShowHidden = { viewModel.setShowHidden(!state.showHidden) },
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            BreadcrumbBar(
                pathComponents = state.pathComponents,
                onNavigate = { index -> viewModel.navigateToPathComponent(index) },
            )

            HorizontalDivider()

            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                state.isEmpty -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(R.string.filebrowser_empty_folder),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(state.items, key = { it.file.absolutePath }) { item ->
                            FileItemRow(
                                item = item,
                                currentLinuxPath = state.currentLinuxPath,
                                onClick = {
                                    if (item.isDirectory) {
                                        viewModel.navigateTo(item)
                                    } else {
                                        onPreviewFile(item)
                                    }
                                },
                                onDelete = { deleteTarget = item },
                                onAddToHome = { source -> webAppSheetSource = source },
                            )
                            HorizontalDivider(modifier = Modifier.padding(start = ROW_TEXT_START))
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.filebrowser_delete_title, item.name)) },
            text = { Text(stringResource(R.string.filebrowser_delete_message)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    viewModel.deleteItem(item)
                    deleteTarget = null
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    webAppSheetSource?.let { src ->
        com.openminis.app.webapp.AddToHomeSheet(
            source = src,
            onDismiss = { webAppSheetSource = null },
        )
    }

    (state.errorMessage ?: state.refusedMountPointName?.let {
        stringResource(R.string.filebrowser_delete_mount_point_refused, it)
    })?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = { Text(stringResource(R.string.filebrowser_error_title)) },
            text = { Text(msg) },
            confirmButton = {
                MinisTextButton(onClick = { viewModel.dismissError() }) {
                    Text("OK")
                }
            },
        )
    }
}

@Composable
private fun BreadcrumbBar(
    pathComponents: List<String>,
    onNavigate: (Int) -> Unit,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(pathComponents) {
        withFrameNanos { }
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val slots = breadcrumbSlots(pathComponents.size)
        slots.forEachIndexed { slotIndex, slot ->
            when (slot) {
                is BreadcrumbSlot.Component -> BreadcrumbCapsule(
                    label = pathComponents[slot.index],
                    isCurrent = slot.index == pathComponents.lastIndex,
                    onClick = { onNavigate(slot.index) },
                )
                is BreadcrumbSlot.Collapsed -> CollapsedBreadcrumbCapsule(
                    hidden = slot.indices.map { it to pathComponents[it] },
                    onNavigate = onNavigate,
                )
            }
            if (slotIndex < slots.lastIndex) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal sealed interface BreadcrumbSlot {
    data class Component(val index: Int) : BreadcrumbSlot

    data class Collapsed(val indices: IntRange) : BreadcrumbSlot
}

internal const val BREADCRUMB_FULL_MAX = 4

internal fun breadcrumbSlots(count: Int): List<BreadcrumbSlot> {
    if (count <= BREADCRUMB_FULL_MAX) return List(count) { BreadcrumbSlot.Component(it) }
    return listOf(
        BreadcrumbSlot.Component(0),
        BreadcrumbSlot.Collapsed(1 until count - 2),
        BreadcrumbSlot.Component(count - 2),
        BreadcrumbSlot.Component(count - 1),
    )
}

@Composable
private fun CollapsedBreadcrumbCapsule(
    hidden: List<Pair<Int, String>>,
    onNavigate: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val more = stringResource(R.string.filebrowser_more_action)
    Box {
        BreadcrumbCapsule(
            label = "···",
            isCurrent = false,
            onClick = { expanded = true },
            contentDescription = more,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            hidden.forEach { (index, name) ->
                DropdownMenuItem(
                    text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onNavigate(index)
                    },
                )
            }
        }
    }
}

@Composable
private fun BreadcrumbCapsule(
    label: String,
    isCurrent: Boolean,
    onClick: () -> Unit,
    contentDescription: String? = null,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clip(shape)
            .then(
                if (isCurrent) Modifier.semantics { heading() }
                else Modifier.clickable(role = Role.Button, onClick = onClick),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isCurrent) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier
                .then(
                    if (contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    },
                )
                .widthIn(max = 180.dp)
                .background(
                    if (isCurrent) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    shape,
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

private val ROW_PADDING_H = 16.dp
private val ROW_TILE = 36.dp
private val ROW_TILE_GAP = 12.dp
private val ROW_TEXT_START = ROW_PADDING_H + ROW_TILE + ROW_TILE_GAP

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileItemRow(
    item: FileItem,
    currentLinuxPath: String?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onAddToHome: (com.openminis.app.webapp.WebAppSource.HostFile) -> Unit,
) {
    val ext = item.file.extension.lowercase()
    val isHtml = !item.isDirectory && (ext == "html" || ext == "htm")
    var menuExpanded by remember(item.file.absolutePath) { mutableStateOf(false) }
    val context = LocalContext.current

    val absolutePath = currentLinuxPath?.let { "${it.trimEnd('/')}/${item.name}" }
        ?: item.file.absolutePath

    Box {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuExpanded = true },
            )
            .padding(start = ROW_PADDING_H, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileIconTile(kind = FileIconKind.of(ext, item.isDirectory), isSymlink = item.isSymlink)

        Spacer(modifier = Modifier.width(ROW_TILE_GAP))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val mtime = item.formattedDate
            if (!item.isDirectory || mtime.isNotEmpty()) {
                val parts = buildList {
                    if (!item.isDirectory) add(item.formattedSize)
                    if (mtime.isNotEmpty()) add(mtime)
                }
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        if (item.isDirectory) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp).size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        } else {
            Box {
                RowActionButton(
                    icon = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.filebrowser_more_action),
                    onClick = { menuExpanded = true },
                )
                FileRowMenu(
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    item = item,
                    absolutePath = absolutePath,
                    isHtml = isHtml,
                    onDelete = onDelete,
                    onAddToHome = onAddToHome,
                )
            }
            RowActionButton(
                icon = Icons.Filled.Share,
                contentDescription = stringResource(R.string.filepreview_share),
                onClick = { shareFile(context, item) },
            )
        }
    }
    if (item.isDirectory) {
        FileRowMenu(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            item = item,
            absolutePath = absolutePath,
            isHtml = false,
            onDelete = onDelete,
            onAddToHome = onAddToHome,
        )
    }
    }
}

@Composable
private fun FileIconTile(kind: FileIconKind, isSymlink: Boolean) {
    val tint = kind.tint()
    Box(modifier = Modifier.size(ROW_TILE)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(8.dp))
                .background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(kind.icon(), contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
        }
        if (isSymlink) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 3.dp, y = 3.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(SYMLINK_BADGE)
                    .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(9.dp), tint = Color.White)
            }
        }
    }
}

@Composable
private fun RowActionButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FileRowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    item: FileItem,
    absolutePath: String,
    isHtml: Boolean,
    onDelete: () -> Unit,
    onAddToHome: (com.openminis.app.webapp.WebAppSource.HostFile) -> Unit,
) {
    val context = LocalContext.current
    com.openminis.app.ui.components.MinisMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.filebrowser_copy_abs_path)) },
            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
            onClick = {
                onDismiss()
                val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clip.setPrimaryClip(android.content.ClipData.newPlainText("path", absolutePath))
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.filebrowser_copy_abs_path_toast),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            },
        )
        if (false && isHtml) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.webapp_add_to_home)) },
                leadingIcon = { Icon(Icons.Filled.AppShortcut, contentDescription = null) },
                onClick = {
                    onDismiss()
                    val triple = com.openminis.app.webapp.WebAppPathResolver.inferScope(item.file)
                    if (triple != null) {
                        val (scope, ctx, linuxPath) = triple
                        onAddToHome(
                            com.openminis.app.webapp.WebAppSource.HostFile(
                                file = item.file,
                                fileName = item.name,
                                pathScope = scope,
                                scopeContext = ctx,
                                linuxPath = linuxPath,
                            ),
                        )
                    }
                },
            )
        }
        if (!item.isDirectory) {
            val danger = MaterialTheme.colorScheme.error
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete), color = danger) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = danger) },
                onClick = {
                    onDismiss()
                    onDelete()
                },
            )
        }
    }
}

private val SYMLINK_BADGE = Color(0xFFFF9800)

internal enum class FileIconKind {
    FOLDER, CODE, SHELL, IMAGE, AUDIO, VIDEO, TEXT, PDF, ARCHIVE, OTHER;

    companion object {
        fun of(extension: String, isDirectory: Boolean): FileIconKind {
            if (isDirectory) return FOLDER
            return when (extension.lowercase()) {
                "swift", "py", "js", "mjs", "ts", "tsx", "jsx", "c", "cc", "cpp", "h", "hpp", "m", "mm",
                "go", "rs", "java", "kt", "rb", "php", "lua", "html", "htm", "css",
                "json", "xml", "yaml", "yml", "toml" -> CODE
                "sh", "bash", "zsh", "fish" -> SHELL
                "png", "jpg", "jpeg", "gif", "bmp", "svg", "webp", "heic", "tiff", "ico" -> IMAGE
                "mp3", "wav", "aac", "flac", "m4a", "ogg", "opus" -> AUDIO
                "mp4", "mov", "avi", "mkv", "webm", "m4v" -> VIDEO
                "txt", "md", "markdown", "log", "csv", "rtf", "doc", "docx" -> TEXT
                "pdf" -> PDF
                "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "zst" -> ARCHIVE
                else -> OTHER
            }
        }
    }
}

private fun FileIconKind.icon(): ImageVector = when (this) {
    FileIconKind.FOLDER -> Icons.Filled.Folder
    FileIconKind.CODE -> Icons.Filled.Code
    FileIconKind.SHELL -> Icons.Filled.Terminal
    FileIconKind.IMAGE -> Icons.Filled.Image
    FileIconKind.AUDIO -> Icons.Filled.MusicNote
    FileIconKind.VIDEO -> Icons.Filled.Movie
    FileIconKind.TEXT -> Icons.Filled.Description
    FileIconKind.PDF -> Icons.Filled.PictureAsPdf
    FileIconKind.ARCHIVE -> Icons.Filled.FolderZip
    FileIconKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@Composable
private fun FileIconKind.tint(): Color = when (this) {
    FileIconKind.FOLDER -> MaterialTheme.colorScheme.primary
    FileIconKind.CODE -> Color(0xFF9C27B0)
    FileIconKind.SHELL -> Color(0xFF4CAF50)
    FileIconKind.IMAGE -> Color(0xFFFF9800)
    FileIconKind.AUDIO -> Color(0xFFE91E63)
    FileIconKind.VIDEO -> Color(0xFFF44336)
    FileIconKind.TEXT, FileIconKind.PDF -> Color(0xFF2196F3)
    FileIconKind.ARCHIVE -> Color(0xFF795548)
    FileIconKind.OTHER -> Color(0xFF757575)
}

@Composable
private fun MoreMenu(
    sortKey: FileSortKey,
    ascending: Boolean,
    foldersFirst: Boolean,
    showHidden: Boolean,
    onSelectKey: (FileSortKey) -> Unit,
    onToggleDirection: () -> Unit,
    onToggleFoldersFirst: () -> Unit,
    onToggleShowHidden: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.filebrowser_more_action))
        }
        com.openminis.app.ui.components.MinisMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (key in FileSortKey.entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(when (key) {
                        FileSortKey.NAME -> R.string.filebrowser_sort_name
                        FileSortKey.MODIFIED -> R.string.filebrowser_sort_modified
                        FileSortKey.SIZE -> R.string.filebrowser_sort_size
                        FileSortKey.KIND -> R.string.filebrowser_sort_kind
                    })) },
                    leadingIcon = {
                        if (key == sortKey) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        } else {
                            Spacer(modifier = Modifier.size(24.dp))
                        }
                    },
                    onClick = {
                        onSelectKey(key)
                        expanded = false
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(if (ascending) R.string.filebrowser_sort_ascending else R.string.filebrowser_sort_descending)) },
                leadingIcon = {
                    Icon(
                        if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                        contentDescription = null,
                    )
                },
                onClick = {
                    onToggleDirection()
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.filebrowser_sort_folders_first)) },
                leadingIcon = {
                    if (foldersFirst) {
                        Icon(Icons.Filled.Check, contentDescription = null)
                    } else {
                        Spacer(modifier = Modifier.size(24.dp))
                    }
                },
                onClick = {
                    onToggleFoldersFirst()
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = {
                    Text(stringResource(
                        if (showHidden) R.string.filebrowser_hide_hidden
                        else R.string.filebrowser_show_hidden
                    ))
                },
                leadingIcon = {
                    Icon(
                        if (showHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = null,
                    )
                },
                onClick = {
                    onToggleShowHidden()
                    expanded = false
                },
            )
        }
    }
}
