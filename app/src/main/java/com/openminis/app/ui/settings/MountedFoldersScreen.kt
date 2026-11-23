package com.openminis.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import com.openminis.app.ui.components.MinisTextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.MountedFoldersStore
import com.openminis.app.data.SafMountHelper
import com.openminis.app.ui.components.DialogTextField
import com.openminis.app.ui.components.safeLaunchPicker
import kotlinx.coroutines.launch
import com.openminis.app.i18n.uppercaseForDisplay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MountedFoldersScreen(
    store: MountedFoldersStore,
    onBack: () -> Unit,
    onMountClick: (mountId: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by store.entries.collectAsState()
    val isAtCapacity = entries.size >= MountedFoldersStore.MAX_MOUNTS

    var pendingPickedUri by remember { mutableStateOf<Uri?>(null) }
    var pendingDefaultName by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }

    var showPickerIntro by remember { mutableStateOf(false) }

    var hasAllFilesAccess by remember { mutableStateOf(checkAllFilesAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAllFilesAccess = checkAllFilesAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val legacyStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) {
            hasAllFilesAccess = checkAllFilesAccess(context)
            scope.launch { store.refreshWritability() }
        }
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (uri.authority != "com.android.externalstorage.documents") {
            Toast.makeText(
                context,
                context.getString(R.string.mount_only_on_device_supported),
                Toast.LENGTH_LONG,
            ).show()
            return@rememberLauncherForActivityResult
        }
        if (!SafMountHelper.handlePickerResult(context, uri)) {
            return@rememberLauncherForActivityResult
        }
        pendingPickedUri = uri
        pendingDefaultName = defaultMountName(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_mount_external_folders)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q &&
                                context.checkSelfPermission(
                                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                legacyStorageLauncher.launch(
                                    arrayOf(
                                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                    ),
                                )
                            } else {
                                showPickerIntro = true
                            }
                        },
                        enabled = !isAtCapacity,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            InfoBanner()

            if (!hasAllFilesAccess) {
                AllFilesAccessBanner(onClick = {
                    when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }.onFailure {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }
                        }
                        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
                            legacyStorageLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                ),
                            )
                    }
                })
            }

            if (entries.isEmpty()) {
                EmptyState()
            } else {
                ListHeader(count = entries.size, atCapacity = isAtCapacity)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        val dismissState = rememberSwipeToDismissBoxState()
                        LaunchedEffect(dismissState.currentValue) {
                            if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
                                scope.launch { store.remove(entry.id) }
                            }
                        }
                        SwipeToDismissBox(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            state = dismissState,
                            backgroundContent = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .padding(horizontal = 16.dp),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    Text(
                                        text = stringResource(R.string.delete),
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            },
                            enableDismissFromStartToEnd = false,
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                MountRow(entry = entry, onClick = { onMountClick(entry.id) })
                            }
                        }
                    }
                    if (isAtCapacity) {
                        item {
                            Text(
                                text = stringResource(R.string.mount_folders_limit_reached),
                                color = Color(0xFFFF9500),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    val pickedUri = pendingPickedUri
    if (pickedUri != null) {
        AddMountSheet(
            sourceUri = pickedUri,
            initialName = pendingDefaultName,
            onDismiss = { pendingPickedUri = null },
            onConfirm = { name, allowWrite ->
                scope.launch {
                    val added = store.add(pickedUri, name, allowWrite)
                    if (added == null) {
                        addError = context.getString(R.string.mount_add_failed)
                    }
                    pendingPickedUri = null
                }
            },
        )
    }

    addError?.let { msg ->
        AlertDialog(
            onDismissRequest = { addError = null },
            confirmButton = {
                MinisTextButton(onClick = { addError = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            text = { Text(msg) },
        )
    }

    if (showPickerIntro) {
        AlertDialog(
            onDismissRequest = { showPickerIntro = false },
            title = { Text(stringResource(R.string.mount_picker_intro_title)) },
            text = { Text(stringResource(R.string.mount_picker_intro_message)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    showPickerIntro = false
                    pickerLauncher.safeLaunchPicker(
                        initialPickerUri(),
                        context,
                        "MountedFolders",
                        R.string.mount_picker_unavailable,
                    )
                }) {
                    Text(stringResource(R.string.mount_picker_intro_continue))
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showPickerIntro = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private fun initialPickerUri(): Uri? = runCatching {
    DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:Documents",
    )
}.getOrNull()

@Composable
private fun InfoBanner() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(12.dp)),
    ) {
        Text(
            text = stringResource(R.string.mount_folders_info_banner),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun AllFilesAccessBanner(onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.mount_all_files_access_required),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.mount_all_files_access_required_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

private fun checkAllFilesAccess(context: android.content.Context): Boolean {
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        else -> true
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.FolderShared,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.mount_folders_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.mount_folders_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ListHeader(count: Int, atCapacity: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.mount_folders_title).uppercaseForDisplay(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "$count / ${MountedFoldersStore.MAX_MOUNTS}",
            style = MaterialTheme.typography.labelSmall,
            color = if (atCapacity) Color(0xFFFF9500) else MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun MountRow(
    entry: MountedFoldersStore.Entry,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = Color(0xFF007AFF),
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                AccessBadge(entry = entry)
            }
            Text(
                text = "/var/minis/mounts/${entry.name}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val source = entry.sourceDisplayName.ifEmpty { stringResource(R.string.mount_path_unavailable) }
            Text(
                text = "← $source",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AccessBadge(entry: MountedFoldersStore.Entry) {
    val (text, color) = when {
        !entry.isWritable -> stringResource(R.string.mount_badge_readonly) to Color(0xFFFF9500)
        !entry.userAllowWrite -> stringResource(R.string.mount_badge_locked) to Color(0xFFAF52DE)
        else -> stringResource(R.string.mount_badge_rw) to Color(0xFF34C759)
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.12f),
        contentColor = color,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMountSheet(
    sourceUri: Uri,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, allowWrite: Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(initialName) }
    var allowWrite by remember { mutableStateOf(true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.mount_add_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.mount_add_source_path),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = SafMountHelper.treeDisplayPath(sourceUri),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.mount_add_name_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            DialogTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.mount_add_name_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.mount_add_allow_writes),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.mount_add_allow_writes_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                SettingsSwitch(checked = allowWrite, onCheckedChange = { allowWrite = it })
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                MinisTextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(Modifier.width(8.dp))
                MinisTextButton(
                    onClick = { onConfirm(name.trim(), allowWrite) },
                    enabled = isValidMountName(name),
                ) {
                    Text(stringResource(R.string.mount_add_confirm))
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

internal fun isValidMountName(raw: String): Boolean {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return false
    if (trimmed.contains('/') || trimmed.contains(' ')) return false
    return true
}

internal fun defaultMountName(uri: Uri): String {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull().orEmpty()
    val rel = docId.substringAfter(':', docId)
    val segments = rel.split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return "mount"

    val androidIdx = segments.indexOf("Android")
    if (androidIdx >= 0 && androidIdx + 2 < segments.size && segments[androidIdx + 1] == "data") {
        return sanitize(segments[androidIdx + 2])
    }
    return sanitize(segments.last())
}

private fun sanitize(raw: String): String {
    val cleaned = raw.trim().replace('/', '-').replace(' ', '_')
    return cleaned.ifEmpty { "mount" }
}
