package com.openminis.app.ui.settings.backup

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openminis.app.R
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreServersScreen(
    onBack: () -> Unit,
    onPickServer: (String) -> Unit,
) {
    val vm: RcloneDestinationsViewModel = viewModel()
    val remotes by vm.remotes.collectAsState()
    var adding by remember { mutableStateOf(false) }
    val detail = remember { DestinationDetailNav() }
    androidx.activity.compose.BackHandler(enabled = detail.isOpen) { detail.back(vm) }

    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }

    SettingsScaffold(
        title = detail.title() ?: stringResource(R.string.backup_server_title),
        onBack = { if (detail.isOpen) detail.back(vm) else onBack() },
    ) {
        if (detail.isOpen) {
            DestinationDetailContent(vm, detail)
            return@SettingsScaffold
        }
        if (adding) {
            AddServerForm(
                vm = vm,
                onCancel = { adding = false },
                pickFolder = false,
                onSaved = { savedName ->
                    adding = false
                    vm.refresh()
                    onPickServer(savedName)
                },
            )
            return@SettingsScaffold
        }

        SettingsSection(
            footer = stringResource(R.string.backup_restore_servers_footer),
        ) {
            remotes.forEach { r ->
                RestoreSourceRow(
                    icon = Icons.Outlined.Cloud,
                    iconColor = MaterialTheme.colorScheme.primary,
                    label = r.name,
                    subtitle = stringResource(
                        R.string.backup_server_row,
                        r.backend.uppercase(),
                        r.path.trimStart('/'),
                    ),
                    enabled = true,
                    onClick = { onPickServer(r.name) },
                    showDivider = true,
                    trailing = {
                        IconButton(onClick = {
                            vm.clearError()
                            detail.open(r.name)
                        }) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = stringResource(R.string.backup_dest_details),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
            RestoreSourceRow(
                icon = Icons.Outlined.Add,
                iconColor = MaterialTheme.colorScheme.primary,
                label = stringResource(R.string.backup_dest_add_server),
                enabled = true,
                onClick = { adding = true },
                showDivider = false,
            )
        }
    }
}
