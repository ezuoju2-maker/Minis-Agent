package com.openminis.app.ui.settings.backup

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.NorthWest
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.safeLaunchPicker
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openminis.app.backup.remote.RcloneBackendCatalog
import com.openminis.app.backup.remote.RcloneRemoteStore
import com.openminis.app.data.SafMountHelper
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsSwitch
import com.openminis.app.ui.settings.SettingsSwitchRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RcloneDestinationsScreen(onBack: () -> Unit) {
    val vm: RcloneDestinationsViewModel = viewModel()
    val remotes by vm.remotes.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val browse by vm.browse.collectAsState()

    var adding by remember { mutableStateOf(false) }
    val detail = remember { DestinationDetailNav() }
    BackHandler(enabled = detail.isOpen) { detail.back(vm) }
    var justSaved by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (!SafMountHelper.handlePickerResult(context, uri)) {
            Toast.makeText(
                context,
                context.getString(R.string.backup_dest_local_folder_no_permission),
                Toast.LENGTH_LONG,
            ).show()
            return@rememberLauncherForActivityResult
        }
        val display = runCatching { SafMountHelper.treeDisplayPath(uri) }.getOrNull().orEmpty()
        justSaved = vm.addLocalFolder(uri.toString(), display)
    }

    SettingsScaffold(
        title = detail.title() ?: stringResource(R.string.backup_dest_title),
        onBack = { if (detail.isOpen) detail.back(vm) else onBack() },
    ) {
        if (detail.isOpen) {
            DestinationDetailContent(vm, detail)
            return@SettingsScaffold
        }

        if (browse != null) {
            FolderBrowser(vm) { savedName ->
                adding = false
                justSaved = savedName
            }
            return@SettingsScaffold
        }

        if (adding) {
            AddServerForm(
                vm = vm,
                onCancel = { adding = false },
            )
            return@SettingsScaffold
        }

        justSaved?.let { name ->
            Text(
                stringResource(R.string.backup_dest_saved_confirm, name),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        SettingsSection(
            header = stringResource(R.string.backup_dest_saved),
            footer = stringResource(R.string.backup_dest_footer),
        ) {
            if (remotes.isEmpty()) {
                Text(
                    stringResource(R.string.backup_dest_none),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                remotes.forEachIndexed { i, r ->
                    SettingsRow(
                        title = r.name,
                        subtitle = stringResource(
                            R.string.backup_dest_row_subtitle,
                            if (RcloneRemoteStore.isLocalFolder(r.backend)) {
                                stringResource(R.string.backup_dest_kind_local_folder)
                            } else {
                                r.backend.uppercase()
                            },
                            r.path.trimStart('/'),
                        ),
                        onClick = {
                            vm.clearError()
                            detail.open(r.name)
                        },
                        trailing = {
                            SettingsSwitch(
                                checked = r.enabled,
                                onCheckedChange = { vm.setEnabled(r.name, it) },
                            )
                        },
                        showDivider = i < remotes.lastIndex,
                    )
                }
            }
        }

        Column(Modifier.padding(16.dp)) {
            MinisButton(
                onClick = { adding = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.backup_dest_add_server)) }

            Spacer(Modifier.height(8.dp))
            MinisOutlinedButton(
                onClick = { folderPicker.safeLaunchPicker(null, context, "RcloneDestinations") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.backup_dest_add_local_folder)) }


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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddServerForm(
    vm: RcloneDestinationsViewModel,
    onCancel: () -> Unit,
    pickFolder: Boolean = true,
    onSaved: (String) -> Unit = {},
) {
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val certificateRejected by vm.certificateRejected.collectAsState()

    var backendExpanded by remember { mutableStateOf(false) }
    var backend by remember { mutableStateOf(RcloneBackendCatalog.all.first()) }
    var displayName by remember { mutableStateOf("") }
    val fieldValues = remember { mutableStateMapOf<String, String>() }
    var allowInsecureTLS by remember { mutableStateOf(false) }
    var startPath by remember { mutableStateOf("") }

    Column(Modifier.padding(16.dp)) {
        ExposedDropdownMenuBox(
            expanded = backendExpanded,
            onExpandedChange = { backendExpanded = it },
        ) {
            OutlinedTextField(
                value = backend.title,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.backup_dest_server_type)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(backendExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(
                expanded = backendExpanded,
                onDismissRequest = { backendExpanded = false },
            ) {
                RcloneBackendCatalog.all.forEach { b ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.backup_dest_backend_label, b.title, b.subtitle)) },
                        onClick = {
                            backend = b; backendExpanded = false
                            fieldValues.clear()
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text(stringResource(R.string.backup_dest_display_name)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )

        backend.fields.forEach { f ->
            Spacer(Modifier.height(8.dp))
            ServerFieldInput(
                field = f,
                value = fieldValues[f.key] ?: "",
                onValueChange = { fieldValues[f.key] = it },
                enabled = !busy,
            )
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = startPath,
            onValueChange = { startPath = it },
            label = { Text(stringResource(R.string.backup_dest_start_path)) },
            placeholder = { Text(stringResource(R.string.backup_dest_start_path_hint)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )

        if (certificateRejected) {
            Spacer(Modifier.height(16.dp))
            TrustCertificateRow(
                checked = allowInsecureTLS,
                onCheckedChange = { allowInsecureTLS = it },
                enabled = !busy,
            )
        }

        Spacer(Modifier.height(16.dp))
        val nameOk = displayName.trim().isNotEmpty() &&
            displayName.trim().all { it.isLetterOrDigit() || it == '-' || it == '_' }
        MinisButton(
            onClick = {
                vm.connectAndBrowse(
                    backend = backend.type,
                    displayName = displayName.trim(),
                    values = fieldValues.toMap(),
                    startPath = startPath.trim(),
                    allowInsecureTLS = allowInsecureTLS,
                    onConnected = if (pickFolder) null else {
                        { vm.saveConnected(onSaved) }
                    },
                )
            },
            enabled = !busy && nameOk,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(
                    stringResource(
                        if (pickFolder) R.string.backup_dest_connect
                        else R.string.backup_dest_connect_and_save,
                    ),
                )
            }
        }
        MinisOutlinedButton(
            onClick = onCancel,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text(stringResource(R.string.backup_dest_cancel)) }

        if (displayName.isNotEmpty() && !nameOk) {
            Text(
                stringResource(R.string.backup_dest_name_invalid),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
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
    }
}

@Composable
internal fun ServerFieldInput(
    field: RcloneBackendCatalog.Field,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    label: String = if (field.isOptional) {
        stringResource(R.string.backup_dest_field_optional, field.label)
    } else {
        field.label
    },
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(field.placeholder) },
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (field.isSecret) PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun TrustCertificateRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean) {
    SettingsSwitchRow(
        title = stringResource(R.string.backup_dest_trust_certificate),
        subtitle = stringResource(R.string.backup_dest_trust_certificate_warning),
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        showDivider = false,
    )
}

@Composable
private fun FolderPickerRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Text(
                label,
                color = tint,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.padding(start = 10.dp).weight(1f),
            )
            trailing?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp)
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

@Composable
internal fun FolderBrowser(vm: RcloneDestinationsViewModel, onSaved: (String) -> Unit) {
    val browse by vm.browse.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val b = browse ?: return

    var showNewFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.backup_dest_choose_folder), style = MaterialTheme.typography.titleMedium)

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "/${b.path.trimStart('/')}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            IconButton(onClick = { showNewFolder = true }, enabled = !busy) {
                Icon(
                    Icons.Outlined.CreateNewFolder,
                    contentDescription = stringResource(R.string.backup_dest_new_folder),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        ) {
            if (b.path.isNotEmpty()) {
                FolderPickerRow(
                    icon = Icons.Outlined.NorthWest,
                    label = stringResource(R.string.backup_dest_up),
                    tint = MaterialTheme.colorScheme.primary,
                    enabled = !busy,
                    showDivider = true,
                    onClick = { vm.navigateTo(b.path.substringBeforeLast('/', "")) },
                )
            }

            b.entries.forEachIndexed { i, entry ->
                FolderPickerRow(
                    icon = Icons.Outlined.Folder,
                    label = entry.name,
                    tint = MaterialTheme.colorScheme.primary,
                    enabled = !busy,
                    showDivider = i < b.entries.lastIndex,
                    trailing = entry.modified?.let { formatTimestamp(it) },
                    onClick = { vm.navigateTo(entry.path) },
                )
            }

            if (b.entries.isEmpty() && !busy) {
                Text(
                    stringResource(R.string.backup_dest_no_subfolders),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        MinisButton(
            onClick = {
                if (b.existing) vm.applyFolder(b.path) { onSaved(it.name) }
                else vm.savePending(b.path) { onSaved(b.remote.name) }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.backup_dest_use_folder)) }

        MinisOutlinedButton(
            onClick = { vm.cancelPending() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text(stringResource(R.string.backup_dest_cancel)) }

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false; newFolderName = "" },
            title = { Text(stringResource(R.string.backup_dest_new_folder_title)) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text(stringResource(R.string.backup_dest_folder_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                MinisTextButton(
                    onClick = {
                        vm.createFolder(newFolderName.trim())
                        showNewFolder = false; newFolderName = ""
                    },
                    enabled = newFolderName.trim().isNotEmpty(),
                ) { Text(stringResource(R.string.backup_dest_create)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showNewFolder = false; newFolderName = "" }) {
                    Text(stringResource(R.string.backup_dest_cancel))
                }
            },
        )
    }
}
