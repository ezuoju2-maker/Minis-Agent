package com.openminis.app.ui.settings.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openminis.app.data.DeviceIdentity
import com.openminis.app.ui.settings.SettingsSwitch
import com.openminis.app.R
import com.openminis.app.backup.BackupCategory
import com.openminis.app.backup.BackupFormat
import com.openminis.app.backup.BackupHistory
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.safeLaunchPicker
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.openminis.app.ui.theme.ChatColors

@Composable
private fun RowScope.PrimaryActionContent(
    icon: ImageVector,
    label: String,
    busy: Boolean,
    fraction: Float? = null,
    onStopClick: (() -> Unit)? = null,
) {
    if (busy) {
        val stopActionLabel = stringResource(R.string.restore_stop_action)
        if (onStopClick != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onStopClick)
                    .semantics {
                        role = Role.Button
                        contentDescription = stopActionLabel
                    },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = { fraction ?: 0f },
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.5.dp,
                    color = LocalContentColor.current,
                )
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(LocalContentColor.current),
                )
            }
        } else {
            CircularProgressIndicator(
                progress = { fraction ?: 0f },
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
        }
    } else {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    }
    Spacer(Modifier.width(8.dp))
    Text(label)
}

@Composable
fun BackupAndRestoreScreen(
    onBack: () -> Unit,
    onManageDestinations: () -> Unit = {},
    onChooseRestoreServer: () -> Unit = {},
    onOpenHistoryRecord: (String) -> Unit = {},
    onBrowseDestination: (String) -> Unit = {},
    initialTab: Int = 0,
) {
    val vm: BackupViewModel = viewModel()
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var backupPassphrase by remember { mutableStateOf("") }
    var backupConfirm by remember { mutableStateOf("") }
    var restorePassphrase by remember { mutableStateOf("") }

    SettingsScaffold(title = stringResource(R.string.backup_title), onBack = onBack) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            SegmentedButton(
                selected = tab == 0,
                onClick = { tab = 0 },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text(stringResource(R.string.backup_tab_backup)) }
            SegmentedButton(
                selected = tab == 1,
                onClick = { tab = 1 },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text(stringResource(R.string.backup_tab_restore)) }
        }

        if (tab == 0) {
            BackupTab(
                vm, onManageDestinations, onOpenHistoryRecord,
                passphrase = backupPassphrase,
                onPassphraseChange = { backupPassphrase = it },
                confirm = backupConfirm,
                onConfirmChange = { backupConfirm = it },
            )
        } else {
            RestoreTab(
                vm, onBrowseDestination,
                onChooseRestoreServer = onChooseRestoreServer,
                passphrase = restorePassphrase,
                onPassphraseChange = { restorePassphrase = it },
            )
        }
    }
}

private const val HISTORY_COLLAPSED_COUNT = 10


@Composable
private fun BackupTab(
    vm: BackupViewModel,
    onManageDestinations: () -> Unit = {},
    onOpenHistoryRecord: (String) -> Unit = {},
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    confirm: String,
    onConfirmChange: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val selected by vm.selected.collectAsState()
    val encrypt by vm.encrypt.collectAsState()
    val maxFileSizeMB by vm.maxFileSizeMB.collectAsState()
    val running by vm.exportRunning.collectAsState()
    val status by vm.statusText.collectAsState()
    val error by vm.errorText.collectAsState()
    val destinations by vm.destinations.collectAsState()
    val historyRecords by vm.historyRecords.collectAsState()
    val lastResult by vm.lastResult.collectAsState()
    val exportReady by vm.exportReady.collectAsState()
    val saveState by vm.saveState.collectAsState()

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(com.openminis.app.backup.BackupFormat.MIME_TYPE),
    ) { uri -> uri?.let(vm::saveExportTo) }

    LaunchedEffect(Unit) {
        vm.clearSettledSuccess()
        vm.refreshDestinations()
        vm.refreshHistory()
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                vm.refreshDestinations()
                vm.refreshHistory()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val passphraseValid = !encrypt || (passphrase.isNotEmpty() && passphrase == confirm)
    val hasFileTree = selected.any { it.carriesFileTree }

    DeviceNameSection(enabled = !running)

    val includeFooter = when {
        !hasFileTree -> stringResource(R.string.backup_include_footer)
        maxFileSizeMB == MAX_FILE_NO_FILES ->
            stringResource(R.string.backup_file_footer_no_files)
        maxFileSizeMB != MAX_FILE_UNLIMITED ->
            stringResource(R.string.backup_file_footer_limited, maxFileSizeMB)
        else -> stringResource(R.string.backup_file_footer_unlimited)
    }
    SettingsSection(
        header = stringResource(R.string.backup_section_include),
        footer = includeFooter,
    ) {
        val cats = BackupCategory.backupable
        cats.forEachIndexed { i, cat ->
            CategorySwitchRow(
                title = stringResource(categoryNameRes(cat)),
                icon = categoryIcon(cat),
                iconColor = categoryTint(cat),
                checked = cat in selected,
                onCheckedChange = { vm.toggleCategory(cat, it) },
                enabled = !running,
                showDivider = i < cats.lastIndex || hasFileTree,
            )
        }
        if (hasFileTree) {
            MaxFileSizeRow(
                current = maxFileSizeMB,
                enabled = !running,
                onSelect = { vm.setMaxFileSizeMB(it) },
            )
        }
    }

    SettingsSection(
        header = stringResource(R.string.backup_section_encryption),
        footer = if (encrypt) {
            stringResource(R.string.backup_encrypt_footer_on)
        } else if (BackupCategory.PROVIDERS in selected ||
            BackupCategory.ENVIRONMENT_VARIABLES in selected ||
            BackupCategory.MCP_SERVERS in selected
        ) {
            stringResource(R.string.backup_encrypt_footer_off_creds)
        } else {
            stringResource(R.string.backup_encrypt_footer_off)
        },
    ) {
        CategorySwitchRow(
            title = stringResource(R.string.backup_encrypt_backup),
            icon = Icons.Outlined.Lock,
            iconColor = Color(0xFF34C759),
            checked = encrypt,
            onCheckedChange = { vm.setEncrypt(it) },
            enabled = !running,
            showDivider = encrypt,
        )
        if (encrypt) {
            var revealPassphrase by remember { mutableStateOf(false) }
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = onPassphraseChange,
                    label = { Text(stringResource(R.string.backup_passphrase)) },
                    singleLine = true,
                    enabled = !running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (revealPassphrase) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = { revealPassphrase = !revealPassphrase },
                            enabled = !running,
                        ) {
                            Icon(
                                if (revealPassphrase) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                contentDescription = stringResource(
                                    if (revealPassphrase) R.string.backup_hide_passphrase
                                    else R.string.backup_show_passphrase,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = onConfirmChange,
                    label = { Text(stringResource(R.string.backup_confirm_passphrase)) },
                    singleLine = true,
                    enabled = !running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (revealPassphrase) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    isError = confirm.isNotEmpty() && confirm != passphrase,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (confirm.isNotEmpty() && confirm != passphrase) {
                    Text(
                        stringResource(R.string.backup_passphrase_mismatch),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    DestinationsSection(
        destinations = destinations,
        enabled = !running,
        onManage = onManageDestinations,
        onToggle = vm::setDestinationEnabled,
    )

    Column(Modifier.padding(16.dp)) {
        MinisButton(
            onClick = {
                if (running) vm.stopExport()
                else vm.startExport(passphrase.takeIf { encrypt })
            },
            enabled = running || (selected.isNotEmpty() && passphraseValid),
            colors = if (running) {
                androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                androidx.compose.material3.ButtonDefaults.buttonColors()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            PrimaryActionContent(
                icon = if (running) Icons.Outlined.Stop else Icons.Outlined.CloudUpload,
                label = stringResource(
                    if (running) R.string.backup_stop else R.string.backup_start,
                ),
                busy = false,
            )
        }

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (!running) {
            val hint = when {
                selected.isEmpty() -> stringResource(R.string.backup_needs_category)
                encrypt && passphrase.isEmpty() -> stringResource(R.string.backup_needs_passphrase)
                destinations.none { it.enabled } -> stringResource(R.string.backup_no_destination_hint)
                else -> null
            }
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }


    lastResult?.let { r ->
        if (!running) {
            SettingsSection(
                header = stringResource(R.string.backup_ready),
                footer = backupResultFooter(r),
            ) {
                DetailValueRow(
                    label = stringResource(R.string.backup_history_size),
                    value = humanBytes(r.totalBytes),
                    showDivider = r.skippedFiles > 0 || r.destinations.isNotEmpty(),
                )
                if (r.skippedFiles > 0) {
                    DetailValueRow(
                        label = stringResource(
                            if (maxFileSizeMB == MAX_FILE_NO_FILES) R.string.backup_result_files_not_included
                            else R.string.backup_result_excluded_too_large,
                        ),
                        value = stringResource(R.string.backup_history_file_count, r.skippedFiles),
                        showDivider = r.destinations.isNotEmpty(),
                    )
                }
                r.destinations.forEachIndexed { i, d ->
                    ResultDestinationRow(
                        outcome = d,
                        showDivider = i < r.destinations.lastIndex,
                    )
                }
            }
            val pkg = exportReady?.packageFile
            if (r.localOnly && pkg != null && pkg.exists()) {
                LocalExportActions(
                    saving = saveState is com.openminis.app.backup.BackupRunController.SaveState.Saving,
                    onSave = { saveLauncher.safeLaunchPicker(pkg.name, context, "Backup") },
                    onShare = { shareBackupPackage(context, pkg) },
                )
                saveStateLine(saveState)?.let { (text, isError) ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        }
    }

    BackupHistorySection(
        records = historyRecords,
        onOpen = onOpenHistoryRecord,
    )
}

@Composable
private fun DeviceNameSection(enabled: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val automatic = remember { DeviceIdentity.automaticName(context) }
    var draft by remember { mutableStateOf(DeviceIdentity.customName(context).orEmpty()) }
    SettingsSection(
        header = stringResource(R.string.backup_device_name),
        footer = stringResource(R.string.backup_device_name_footer, automatic),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(30.dp).background(Color(0xFF007AFF), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhoneAndroid,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            val textStyle = MaterialTheme.typography.bodyLarge
            androidx.compose.foundation.text.BasicTextField(
                value = draft,
                onValueChange = {
                    draft = it.take(DeviceIdentity.CUSTOM_NAME_MAX_LENGTH)
                    DeviceIdentity.setCustomName(context, draft)
                },
                enabled = enabled,
                singleLine = true,
                textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words,
                    autoCorrectEnabled = false,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty()) {
                            Text(
                                automatic,
                                style = textStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
        }
    }
}

@Composable
private fun BackupHistorySection(
    records: List<BackupHistory.Record>,
    onOpen: (String) -> Unit,
) {
    if (records.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) records else records.take(HISTORY_COLLAPSED_COUNT)
    SettingsSection(
        header = stringResource(R.string.backup_history),
        footer = stringResource(R.string.backup_history_footer),
    ) {
        shown.forEachIndexed { i, r ->
            BackupHistoryRow(
                record = r,
                showDivider = i < shown.lastIndex || !expanded && records.size > shown.size,
                onClick = { onOpen(r.id) },
            )
        }
        if (!expanded && records.size > shown.size) {
            RestoreSourceRow(
                icon = Icons.Outlined.History,
                iconColor = Color(0xFF8E8E93),
                label = stringResource(
                    R.string.backup_history_show_all,
                    records.size,
                ),
                enabled = true,
                onClick = { expanded = true },
                showDivider = false,
            )
        }
    }
}

@Composable
private fun BackupHistoryRow(
    record: BackupHistory.Record,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    val (icon, tint) = when (record.status) {
        BackupHistory.Status.RUNNING -> Icons.Outlined.Sync to Color(0xFF8E8E93)
        BackupHistory.Status.SUCCEEDED -> Icons.Outlined.CheckCircle to Color(0xFF34C759)
        BackupHistory.Status.COMPLETED_WITH_ISSUES -> Icons.Outlined.Warning to Color(0xFFFF9500)
        BackupHistory.Status.FAILED -> Icons.Outlined.ErrorOutline to Color(0xFFFF3B30)
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(30.dp).background(tint, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    formatTimestamp(record.startedAt),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    backupHistorySubtitle(record),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        if (showDivider) {
            Box(
                Modifier.fillMaxWidth().padding(start = 56.dp)
                    .height(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

@Composable
private fun backupHistorySubtitle(record: BackupHistory.Record): String {
    if (record.status == BackupHistory.Status.RUNNING) {
        return record.log.lastOrNull()?.message
            ?: stringResource(R.string.backup_history_running)
    }
    if (record.status == BackupHistory.Status.FAILED) {
        return when (record.errorMessage) {
            BackupHistory.INTERRUPTED_MARKER ->
                stringResource(R.string.backup_history_interrupted)
            BackupViewModel.STOPPED_MARKER ->
                stringResource(R.string.backup_history_stopped)
            null -> stringResource(R.string.backup_history_interrupted)
            else -> record.errorMessage
        }
    }
    val ok = record.destinations.count { it.succeeded }
    val total = record.destinations.size
    val size = humanBytes(record.totalBytes)
    return if (total == 0) size
    else "$size · " + stringResource(R.string.backup_history_delivered, ok, total)
}


internal fun formatTimestamp(millis: Long): String =
    java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT,
    ).format(java.util.Date(millis))

@Composable
private fun MaxFileSizeRow(
    current: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (current) {
        MAX_FILE_NO_FILES -> stringResource(R.string.backup_max_file_no_files)
        MAX_FILE_UNLIMITED -> stringResource(R.string.backup_max_file_unlimited)
        else -> stringResource(R.string.backup_max_file_mb, current)
    }
    val options = listOf(
        MAX_FILE_NO_FILES to stringResource(R.string.backup_max_file_no_files),
        1 to stringResource(R.string.backup_max_file_mb, 1),
        2 to stringResource(R.string.backup_max_file_mb, 2),
        5 to stringResource(R.string.backup_max_file_mb, 5),
        10 to stringResource(R.string.backup_max_file_mb, 10),
        50 to stringResource(R.string.backup_max_file_mb, 50),
        100 to stringResource(R.string.backup_max_file_mb, 100),
        500 to stringResource(R.string.backup_max_file_mb, 500),
        MAX_FILE_UNLIMITED to stringResource(R.string.backup_max_file_unlimited),
    )
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (enabled) Modifier.clickable { expanded = true } else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(30.dp).background(Color(0xFF8E8E93), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.FolderZip, contentDescription = null,
                    tint = Color.White, modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Text(
                stringResource(R.string.backup_max_file_size),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Outlined.UnfoldMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                MinisMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    alignEnd = true,
                    offset = DpOffset(0.dp, 4.dp),
                ) {
                    options.forEach { (value, text) ->
                        DropdownMenuItem(
                            text = { Text(text) },
                            trailingIcon = if (value == current) {
                                { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else null,
                            onClick = { onSelect(value); expanded = false },
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun RestoreTab(
    vm: BackupViewModel,
    onBrowseDestination: (String) -> Unit = {},
    onChooseRestoreServer: () -> Unit = {},
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
) {
    val running by vm.isRunning.collectAsState()
    val status by vm.statusText.collectAsState()
    val pending by vm.pending.collectAsState()
    val restoreSelected by vm.restoreSelected.collectAsState()
    val report by vm.report.collectAsState()
    val error by vm.errorText.collectAsState()

    var browsing by remember {
        mutableStateOf<com.openminis.app.backup.remote.RcloneRemoteStore.Remote?>(null)
    }
    val destinations by vm.destinations.collectAsState()
    LaunchedEffect(Unit) { vm.refreshDestinations() }

    val context = androidx.compose.ui.platform.LocalContext.current
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.loadPackage(uri) }

    if (report != null) {
        RestoreReport(report!!, onDone = { vm.dismissReport() })
        return
    }

    if (pending == null) {
        val serverDestinations = destinations.filterNot {
            com.openminis.app.backup.remote.RcloneRemoteStore.isLocalFolder(it.backend)
        }
        if (serverDestinations.isNotEmpty()) {
            SettingsSection(
                header = stringResource(R.string.backup_restore_destinations),
                footer = stringResource(R.string.backup_restore_destinations_footer),
            ) {
                serverDestinations.forEachIndexed { i, r ->
                    RestoreSourceRow(
                        icon = Icons.Outlined.Cloud,
                        iconColor = Color(0xFFAF52DE),
                        label = r.name,
                        subtitle = stringResource(
                            R.string.backup_dest_row_subtitle,
                            r.backend.uppercase(),
                            r.path,
                        ),
                        enabled = !running,
                        onClick = { onBrowseDestination(r.name) },
                        showDivider = i < serverDestinations.lastIndex,
                    )
                }
            }
        }

        SettingsSection(
            header = stringResource(R.string.backup_restore_other_sources),
            footer = stringResource(R.string.backup_choose_file_footer),
        ) {
            RestoreSourceRow(
                icon = Icons.Outlined.Description,
                iconColor = Color(0xFF007AFF),
                label = if (running) status ?: stringResource(R.string.backup_reading)
                else stringResource(R.string.backup_choose_file),
                enabled = !running,
                onClick = { pickLauncher.safeLaunchPicker(arrayOf("*/*"), context, "Backup") },
                showDivider = true,
            )
            RestoreSourceRow(
                icon = Icons.Outlined.Cloud,
                iconColor = Color(0xFFAF52DE),
                label = stringResource(R.string.backup_choose_server),
                enabled = !running,
                onClick = onChooseRestoreServer,
                showDivider = false,
            )
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        browsing?.let { r ->
            ServerPackagePicker(
                vm = vm,
                remote = r,
                onDismiss = { browsing = null; vm.clearServerPackages() },
            )
        }
        return
    }

    val p = pending!!
    SettingsSection(header = stringResource(R.string.backup_section_backup)) {
        Column(Modifier.padding(16.dp)) {
            p.manifest.snapshotAt
                ?.takeIf { it.isNotEmpty() && it != p.manifest.createdAt }
                ?.let { InfoLine(stringResource(R.string.backup_info_snapshot), it) }
            InfoLine(
                stringResource(R.string.backup_info_created),
                p.manifest.createdAt.ifEmpty { stringResource(R.string.backup_info_unknown) },
            )
            InfoLine(
                stringResource(R.string.backup_info_from),
                "${p.manifest.deviceName} · ${p.manifest.app.platform} ${p.manifest.app.version}",
            )
            InfoLine(
                stringResource(R.string.backup_info_encrypted),
                if (p.manifest.encryption != null) stringResource(R.string.backup_yes)
                else stringResource(R.string.backup_no),
            )
        }
    }

    SettingsSection(
        header = stringResource(R.string.backup_section_restore),
        footer = stringResource(R.string.backup_restore_merge_footer),
    ) {
        val cats = BackupCategory.entries.filter { it in p.availableCategories }
        cats.forEachIndexed { i, cat ->
            val stat = p.manifest.categories[cat.key]
            CategorySwitchRow(
                title = stringResource(categoryNameRes(cat)),
                subtitle = stat?.let { categoryCountLabel(cat, it) },
                icon = categoryIcon(cat),
                iconColor = categoryTint(cat),
                checked = cat in restoreSelected,
                onCheckedChange = { vm.toggleRestoreCategory(cat, it) },
                enabled = !running,
                showDivider = i < cats.lastIndex,
            )
        }
    }

    if (p.manifest.encryption != null) {
        SettingsSection(
            footer = stringResource(R.string.backup_restore_passphrase_footer),
        ) {
            Column(Modifier.padding(16.dp)) {
                var revealRestorePassphrase by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = onPassphraseChange,
                    label = { Text(stringResource(R.string.backup_passphrase)) },
                    singleLine = true,
                    enabled = !running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (revealRestorePassphrase) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = { revealRestorePassphrase = !revealRestorePassphrase },
                            enabled = !running,
                        ) {
                            Icon(
                                if (revealRestorePassphrase) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                contentDescription = stringResource(
                                    if (revealRestorePassphrase) R.string.backup_hide_passphrase
                                    else R.string.backup_show_passphrase,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    Column(Modifier.padding(16.dp)) {
        val restoreFocus = androidx.compose.ui.platform.LocalFocusManager.current
        RestoreProgressSection(
            vm = vm,
            running = running,
            enabled = !running && restoreSelected.isNotEmpty(),
            statusText = status,
            onStart = {
                restoreFocus.clearFocus(force = true)
                vm.startRestore(passphrase.takeIf { p.manifest.encryption != null })
            },
        )
        MinisOutlinedButton(
            onClick = { vm.cancelRestore(); onPassphraseChange("") },
            enabled = !running,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text(stringResource(R.string.backup_choose_different)) }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun RestoreReport(
    report: com.openminis.app.backup.BackupImporter.Report,
    onDone: () -> Unit,
) {
    SettingsSection(header = stringResource(R.string.backup_restore_complete)) {
        Column(Modifier.padding(16.dp)) {
            InfoLine(stringResource(R.string.backup_report_restored), report.totalImported.toString())
            InfoLine(stringResource(R.string.backup_report_updated), report.totalUpdated.toString())
            InfoLine(stringResource(R.string.backup_report_up_to_date), report.totalSkipped.toString())
            if (report.totalMissingBlobs > 0) {
                InfoLine(stringResource(R.string.backup_report_missing_files), report.totalMissingBlobs.toString())
            }
        }
    }

    val issues = restoreIssues(report)
    val lines = issues.map { issue ->
        val name = BackupCategory.fromKey(issue.categoryKey)
            ?.let { stringResource(categoryNameRes(it)) } ?: issue.categoryKey
        issue.severity to when (issue.kind) {
            RestoreIssueKind.FAILED ->
                stringResource(R.string.backup_report_failed, name, issue.detail.orEmpty())
            RestoreIssueKind.MISSING_FROM_PACKAGE ->
                stringResource(R.string.backup_report_missing_from_package, name, issue.count)
            RestoreIssueKind.UNREADABLE ->
                stringResource(R.string.backup_report_unreadable, name, issue.count)
            RestoreIssueKind.SIZE_SKIPPED ->
                stringResource(R.string.backup_report_size_skipped, name, issue.count)
            RestoreIssueKind.NOT_DOWNLOADED ->
                stringResource(R.string.backup_report_not_downloaded, name, issue.count)
        }
    }
    val errors = lines.filter { it.first == RestoreIssueSeverity.ERROR }.map { it.second }
    val notices = lines.filter { it.first == RestoreIssueSeverity.NOTICE }.map { it.second } +
        report.warnings
    if (errors.isNotEmpty()) {
        SettingsSection(header = stringResource(R.string.backup_report_issues)) {
            Column(Modifier.padding(16.dp)) {
                errors.forEach {
                    ReportLine(it, Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (notices.isNotEmpty()) {
        SettingsSection(
            header = stringResource(R.string.backup_report_notices),
            footer = stringResource(R.string.backup_report_notices_footer),
        ) {
            Column(Modifier.padding(16.dp)) {
                val amber = reportNoticeColor()
                notices.forEach { ReportLine(it, Icons.Outlined.Warning, amber) }
            }
        }
    }

    val prov = report.categories.firstOrNull {
        it.category == BackupCategory.PROVIDERS.key
    }
    val credentials = prov?.let { restoreCredentialsNotice(it) }
    if (prov != null && credentials != null) {
        val msg = when (credentials) {
            RestoreCredentialsNotice.RESTORED ->
                stringResource(R.string.backup_restore_creds_restored, prov.credentialsRestored)
            RestoreCredentialsNotice.KEPT ->
                stringResource(R.string.backup_restore_creds_kept)
            RestoreCredentialsNotice.NONE_IN_PACKAGE ->
                stringResource(R.string.backup_creds_none)
        }
        SettingsSection(footer = msg) {}
    }

    Column(Modifier.padding(16.dp)) {
        MinisButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.backup_done))
        }
    }
}


@Composable
private fun DestinationsSection(
    destinations: List<com.openminis.app.backup.remote.RcloneRemoteStore.Remote>,
    enabled: Boolean,
    onManage: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    SettingsSection(
        header = stringResource(R.string.backup_section_destinations),
        footer = stringResource(
            if (destinations.isEmpty()) R.string.backup_destinations_empty_footer
            else R.string.backup_destinations_footer,
        ),
    ) {
        destinations.forEach { remote ->
            DestinationRow(
                remote = remote,
                enabled = enabled,
                onToggle = { on -> onToggle(remote.name, on) },
                onClick = onManage,
            )
        }
        RestoreSourceRow(
            icon = Icons.Outlined.Add,
            iconColor = Color(0xFF34C759),
            label = stringResource(R.string.backup_manage_destinations),
            enabled = enabled,
            onClick = onManage,
            showDivider = false,
        )
    }
}

@Composable
private fun DestinationRow(
    remote: com.openminis.app.backup.remote.RcloneRemoteStore.Remote,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val isLocal = com.openminis.app.backup.remote.RcloneRemoteStore
        .isLocalFolder(remote.backend)
    val icon = when {
        isLocal -> Icons.Outlined.Folder
        remote.backend == "smb" -> Icons.Outlined.Dns
        remote.backend == "webdav" -> Icons.Outlined.Cloud
        remote.backend == "sftp" -> Icons.Outlined.Terminal
        remote.backend == "s3" -> Icons.Outlined.Storage
        remote.backend == "ftp" -> Icons.Outlined.SwapVert
        else -> Icons.Outlined.Cloud
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(30.dp).background(Color(0xFF007AFF), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    remote.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(
                        R.string.backup_dest_row_subtitle,
                        if (isLocal) stringResource(R.string.backup_dest_kind_local_folder)
                        else remote.backend.uppercase(),
                        remote.path,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            SettingsSwitch(checked = remote.enabled, onCheckedChange = onToggle, enabled = enabled)
        }
        Box(
            Modifier.fillMaxWidth().padding(start = 56.dp)
                .height(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
private fun DetailValueRow(label: String, value: String, showDivider: Boolean) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showDivider) {
            Box(
                Modifier.fillMaxWidth().padding(start = 14.dp)
                    .height(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

@Composable
private fun ResultDestinationRow(
    outcome: BackupHistory.DestinationOutcome,
    showDivider: Boolean,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (outcome.succeeded) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                contentDescription = null,
                tint = if (outcome.succeeded) Color(0xFF34C759) else Color(0xFFFF9500),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(outcome.name, style = MaterialTheme.typography.bodyMedium)
                outcome.detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (showDivider) {
            Box(
                Modifier.fillMaxWidth().padding(start = 44.dp)
                    .height(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

@Composable
private fun LocalExportActions(
    saving: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        MinisOutlinedButton(
            onClick = onSave,
            enabled = !saving,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.backup_save_to_device))
        }
        MinisOutlinedButton(
            onClick = onShare,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.backup_share_package))
        }
    }
}

@Composable
private fun saveStateLine(
    state: com.openminis.app.backup.BackupRunController.SaveState?,
): Pair<String, Boolean>? = when (state) {
    null -> null
    is com.openminis.app.backup.BackupRunController.SaveState.Saving ->
        stringResource(R.string.backup_saving_to_device, state.percent) to false
    is com.openminis.app.backup.BackupRunController.SaveState.Saved ->
        stringResource(R.string.backup_saved_to_device, state.name) to false
    is com.openminis.app.backup.BackupRunController.SaveState.Failed ->
        stringResource(R.string.backup_save_failed, state.message) to true
}

private fun shareBackupPackage(context: android.content.Context, file: java.io.File) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_TITLE, file.name)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(send, file.name)
        if (context !is android.app.Activity) chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }.onFailure {
        com.openminis.app.logging.AppLogger.error("BackupScreen", "[Backup] share failed: ${it.message}")
        android.widget.Toast.makeText(
            context, context.getString(R.string.backup_share_failed), android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
}

@Composable
private fun backupResultFooter(r: com.openminis.app.backup.BackupRunController.RunResult): String {
    if (r.destinations.isEmpty()) {
        return r.savedAs?.let { stringResource(R.string.backup_saved_to_device, it) }
            ?: stringResource(R.string.backup_result_local_pending)
    }
    val ok = r.destinations.count { it.succeeded }
    if (r.localCopyRemoved) {
        return stringResource(R.string.backup_result_delivered_cleaned, r.destinations.size)
    }
    return stringResource(R.string.backup_result_delivered, ok, r.destinations.size)
}

@Composable
internal fun RestoreSourceRow(
    icon: ImageVector,
    iconColor: Color,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    showDivider: Boolean,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(30.dp).background(iconColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
            }
            trailing?.invoke()
        }
        if (showDivider) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(start = 58.dp, end = 14.dp)
                    .height(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}

@Composable
private fun ServerPackagePicker(
    vm: BackupViewModel,
    remote: com.openminis.app.backup.remote.RcloneRemoteStore.Remote,
    onDismiss: () -> Unit,
) {
    val serverPackages by vm.serverPackages.collectAsState()
    val running by vm.isRunning.collectAsState()
    val status by vm.statusText.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(remote.name) },
        text = {
            when {
                running -> Text(status ?: stringResource(R.string.backup_reading))
                serverPackages.isEmpty() ->
                    Text(stringResource(R.string.backup_server_no_packages))
                else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    serverPackages.forEach { pkg ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        R.string.backup_pkg_row,
                                        pkg.displayName,
                                        humanBytes(pkg.size),
                                    ),
                                )
                            },
                            onClick = { vm.downloadServerPackage(pkg, remote); onDismiss() },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_dest_cancel)) }
        },
    )
}


@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    }
}

@Composable
private fun CategorySwitchRow(
    title: String,
    icon: ImageVector,
    iconColor: Color,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    showDivider: Boolean = true,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(
                    if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier,
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(30.dp).background(iconColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            SettingsSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 58.dp, end = 14.dp)
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}

@Composable
internal fun categoryLabel(key: String): String =
    BackupCategory.fromKey(key)?.let { stringResource(categoryNameRes(it)) } ?: key

internal fun categoryNameRes(cat: BackupCategory): Int = when (cat) {
    BackupCategory.CHATS -> R.string.backup_category_chats
    BackupCategory.SHARED_FILES -> R.string.backup_category_shared_files
    BackupCategory.SKILLS -> R.string.backup_category_skills
    BackupCategory.MEMORY -> R.string.backup_category_memory
    BackupCategory.PROVIDERS -> R.string.backup_category_providers
    BackupCategory.MCP_SERVERS -> R.string.backup_category_mcp
    BackupCategory.ENVIRONMENT_VARIABLES -> R.string.backup_category_env_vars
    BackupCategory.VOICE_CORRECTIONS -> R.string.backup_category_voice
}

private fun categoryIcon(cat: BackupCategory): ImageVector = when (cat) {
    BackupCategory.CHATS -> Icons.Outlined.Forum
    BackupCategory.SHARED_FILES -> Icons.Outlined.Description
    BackupCategory.SKILLS -> Icons.Outlined.Extension
    BackupCategory.MEMORY -> Icons.Outlined.Psychology
    BackupCategory.PROVIDERS -> Icons.Outlined.Link
    BackupCategory.MCP_SERVERS -> Icons.Outlined.Layers
    BackupCategory.ENVIRONMENT_VARIABLES -> Icons.Outlined.Terminal
    BackupCategory.VOICE_CORRECTIONS -> Icons.Outlined.RecordVoiceOver
}

private fun categoryTint(cat: BackupCategory): Color = when (cat) {
    BackupCategory.CHATS -> Color(0xFF007AFF)
    BackupCategory.SHARED_FILES -> Color(0xFF5856D6)
    BackupCategory.SKILLS -> Color(0xFFFF9500)
    BackupCategory.MEMORY -> Color(0xFFFF2D55)
    BackupCategory.PROVIDERS -> Color(0xFF30B0C7)
    BackupCategory.MCP_SERVERS -> Color(0xFF32ADE6)
    BackupCategory.ENVIRONMENT_VARIABLES -> Color(0xFFA2845E)
    BackupCategory.VOICE_CORRECTIONS -> Color(0xFFAF52DE)
}

@Composable
private fun categoryCountLabel(
    cat: BackupCategory,
    stat: com.openminis.app.backup.BackupManifest.CategoryStat,
): String = when {
    cat == BackupCategory.CHATS && stat.messages != null ->
        stringResource(
            R.string.backup_count_chats,
            stat.messages ?: 0, stat.files ?: 0, humanBytes(stat.bytes),
        )

    cat == BackupCategory.SKILLS && stat.files != null ->
        stringResource(
            R.string.backup_count_skills,
            stat.entries, stat.files ?: 0, humanBytes(stat.bytes),
        )

    cat == BackupCategory.MCP_SERVERS ->
        stringResource(R.string.backup_count_servers, stat.entries, humanBytes(stat.bytes))

    cat == BackupCategory.PROVIDERS && (stat.thinkingRules ?: 0) > 0 ->
        stringResource(
            R.string.backup_count_providers_rules,
            stat.entries, stat.thinkingRules ?: 0, humanBytes(stat.bytes),
        )

    cat == BackupCategory.PROVIDERS ->
        stringResource(R.string.backup_count_providers, stat.entries, humanBytes(stat.bytes))

    else -> stringResource(R.string.backup_count_items, stat.entries, humanBytes(stat.bytes))
}

internal fun humanBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1e6)
    bytes >= 1_000 -> String.format("%.0f KB", bytes / 1e3)
    else -> "$bytes B"
}

internal const val MAX_FILE_NO_FILES = -1
internal const val MAX_FILE_UNLIMITED = 0

@Composable
private fun RestoreProgressSection(
    vm: BackupViewModel,
    running: Boolean,
    enabled: Boolean,
    statusText: String?,
    onStart: () -> Unit,
) {
    val progress by vm.restoreProgress.collectAsState()
    val etaSeconds by vm.restoreEtaSeconds.collectAsState()
    var confirmStop by rememberSaveable { mutableStateOf(false) }

    val p = progress
    val total = p?.total?.takeIf { it > 0 }
    val fraction = if (p != null && total != null) {
        (p.done.toFloat() / total).coerceIn(0f, 1f)
    } else {
        null
    }

    val label = when {
        !running -> stringResource(R.string.backup_restore)
        p == null -> stringResource(R.string.backup_restoring)
        else -> {
            val name = categoryLabel(p.categoryKey)
            when {
                total != null && p.done > 0 -> stringResource(
                    R.string.backup_restoring_percent,
                    name,
                    (p.done.toLong() * 100 / total).toInt().coerceIn(0, 100),
                    p.done,
                    total,
                )
                p.done > 0 -> stringResource(R.string.backup_restoring_running, name, p.done)
                else -> stringResource(R.string.backup_restoring_category, name)
            }
        }
    }

    MinisButton(
        onClick = onStart,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        PrimaryActionContent(
            icon = Icons.Outlined.CloudDownload,
            label = label,
            busy = running,
            fraction = fraction,
            onStopClick = { confirmStop = true }.takeIf { running },
        )
    }

    if (running) {
        val eta = RestoreEta.bucket(etaSeconds)?.let { bucket ->
            when (bucket) {
                is RestoreEta.EtaBucket.AlmostDone ->
                    stringResource(R.string.restore_eta_almost_done)
                is RestoreEta.EtaBucket.Seconds ->
                    stringResource(R.string.restore_eta_seconds, bucket.seconds)
                is RestoreEta.EtaBucket.MinutesSeconds ->
                    stringResource(R.string.restore_eta_minutes, bucket.minutes, bucket.seconds)
            }
        }
        val line = listOfNotNull(statusText, eta).joinToString(" · ")
        if (line.isNotEmpty()) {
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                textAlign = TextAlign.Center,
            )
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.restore_stop_title)) },
            text = { Text(stringResource(R.string.restore_stop_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    vm.stopRunningRestore()
                }) {
                    Text(
                        stringResource(R.string.restore_stop_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) {
                    Text(stringResource(R.string.restore_stop_cancel))
                }
            },
        )
    }
}

@Composable
private fun ReportLine(text: String, icon: ImageVector, tint: Color) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.padding(top = 1.dp).size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

@Composable
private fun reportNoticeColor(): Color =
    if (ChatColors.isDark) {
        Color(0xFFFFB74D)
    } else {
        Color(0xFFB45309)
    }
