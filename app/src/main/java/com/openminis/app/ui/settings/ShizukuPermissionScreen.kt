package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.offload.ShizukuBackend
import com.openminis.app.offload.ShizukuManager

@Composable
fun ShizukuPermissionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val snap by ShizukuManager.snapshot.collectAsState()

    LaunchedEffect(Unit) { ShizukuManager.refresh() }

    SettingsScaffold(
        title = stringResource(R.string.shizuku_title),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.shizuku_status_header),
            footer = stringResource(R.string.shizuku_status_footer),
        ) {
            SettingsRow(
                title = stringResource(stateTitle(snap.state)),
                subtitle = if (snap.state == ShizukuManager.State.READY) {
                    if (snap.isSui) {
                        stringResource(R.string.shizuku_ready_subtitle_sui, snap.version, snap.uid)
                    } else {
                        stringResource(R.string.shizuku_ready_subtitle, snap.version, snap.uid)
                    }
                } else {
                    stringResource(stateSubtitle(snap.state))
                },
                trailing = {
                    Text(
                        text = stringResource(stateBadge(snap.state)),
                        style = MaterialTheme.typography.labelMedium,
                        color = stateColor(snap.state),
                    )
                },
                showDivider = false,
            )
        }

        when (snap.state) {
            ShizukuManager.State.NOT_INSTALLED -> SettingsSection(
                header = stringResource(R.string.shizuku_actions_header),
                footer = stringResource(R.string.shizuku_install_footer),
            ) {
                SettingsRow(
                    title = stringResource(R.string.shizuku_install_btn),
                    subtitle = stringResource(R.string.shizuku_install_btn_subtitle),
                    onClick = { ShizukuManager.openInstallPage(context, ShizukuBackend.SHIZUKU_GITHUB_URL) },
                )
                SettingsRow(
                    title = stringResource(R.string.shizuku_install_btn_axmanager),
                    subtitle = stringResource(R.string.shizuku_install_btn_axmanager_subtitle),
                    onClick = { ShizukuManager.openInstallPage(context, ShizukuBackend.AXMANAGER_GITHUB_URL) },
                )
                SettingsRow(
                    title = stringResource(R.string.shizuku_install_btn_sui),
                    subtitle = stringResource(R.string.shizuku_install_btn_sui_subtitle),
                    onClick = { ShizukuManager.openInstallPage(context, ShizukuBackend.SUI_GITHUB_URL) },
                    showDivider = false,
                )
            }

            ShizukuManager.State.NOT_RUNNING -> SettingsSection(
                header = stringResource(R.string.shizuku_actions_header),
                footer = stringResource(R.string.shizuku_start_footer),
            ) {
                SettingsRow(
                    title = stringResource(R.string.shizuku_open_btn),
                    subtitle = stringResource(R.string.shizuku_open_btn_subtitle),
                    onClick = { ShizukuManager.openShizukuApp(context) },
                )
                SettingsRow(
                    title = stringResource(R.string.shizuku_recheck_btn),
                    subtitle = stringResource(R.string.shizuku_recheck_subtitle),
                    onClick = { ShizukuManager.refresh() },
                    showDivider = false,
                )
            }

            ShizukuManager.State.NEED_PERMISSION -> SettingsSection(
                header = stringResource(R.string.shizuku_actions_header),
                footer = stringResource(
                    if (snap.isSui) R.string.shizuku_grant_footer_sui
                    else R.string.shizuku_grant_footer,
                ),
            ) {
                SettingsRow(
                    title = stringResource(R.string.shizuku_grant_btn),
                    subtitle = stringResource(R.string.shizuku_grant_subtitle),
                    onClick = { ShizukuManager.requestPermission() },
                    showDivider = !snap.isSui,
                )
                if (!snap.isSui) {
                    SettingsRow(
                        title = stringResource(R.string.shizuku_open_btn),
                        subtitle = stringResource(R.string.shizuku_open_btn_subtitle),
                        onClick = { ShizukuManager.openShizukuApp(context) },
                        showDivider = false,
                    )
                }
            }

            ShizukuManager.State.READY -> SettingsSection(
                header = stringResource(R.string.shizuku_capabilities_header),
                footer = stringResource(R.string.shizuku_capabilities_footer),
            ) {
                if (!snap.isSui) {
                    SettingsRow(
                        title = stringResource(R.string.shizuku_open_btn),
                        subtitle = stringResource(R.string.shizuku_open_btn_subtitle),
                        onClick = { ShizukuManager.openShizukuApp(context) },
                    )
                }
                ShizukuCapabilities.forEachIndexed { index, cap ->
                    SettingsRow(
                        title = cap.name,
                        subtitle = stringResource(cap.descriptionRes),
                        icon = cap.icon,
                        showDivider = index != ShizukuCapabilities.lastIndex,
                        minHeight = 72.dp,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun stateTitle(s: ShizukuManager.State): Int = when (s) {
    ShizukuManager.State.NOT_INSTALLED -> R.string.shizuku_state_not_installed
    ShizukuManager.State.NOT_RUNNING -> R.string.shizuku_state_not_running
    ShizukuManager.State.NEED_PERMISSION -> R.string.shizuku_state_need_permission
    ShizukuManager.State.READY -> R.string.shizuku_state_ready
}

private fun stateSubtitle(s: ShizukuManager.State): Int = when (s) {
    ShizukuManager.State.NOT_INSTALLED -> R.string.shizuku_state_not_installed_subtitle
    ShizukuManager.State.NOT_RUNNING -> R.string.shizuku_state_not_running_subtitle
    ShizukuManager.State.NEED_PERMISSION -> R.string.shizuku_state_need_permission_subtitle
    ShizukuManager.State.READY -> R.string.shizuku_state_ready
}

private fun stateBadge(s: ShizukuManager.State): Int = when (s) {
    ShizukuManager.State.NOT_INSTALLED -> R.string.shizuku_badge_not_installed
    ShizukuManager.State.NOT_RUNNING -> R.string.shizuku_badge_not_running
    ShizukuManager.State.NEED_PERMISSION -> R.string.shizuku_badge_need_permission
    ShizukuManager.State.READY -> R.string.shizuku_badge_ready
}

@Composable
private fun stateColor(s: ShizukuManager.State): Color = when (s) {
    ShizukuManager.State.READY -> MaterialTheme.colorScheme.primary
    ShizukuManager.State.NEED_PERMISSION -> MaterialTheme.colorScheme.tertiary
    ShizukuManager.State.NOT_RUNNING -> MaterialTheme.colorScheme.tertiary
    ShizukuManager.State.NOT_INSTALLED -> MaterialTheme.colorScheme.error
}

private data class ShizukuCapability(
    val name: String,
    @androidx.annotation.StringRes val descriptionRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private val ShizukuCapabilities: List<ShizukuCapability> = listOf(
    ShizukuCapability("package", R.string.shizuku_cap_package_desc, Icons.Default.Apps),
    ShizukuCapability("permission", R.string.shizuku_cap_permission_desc, Icons.Default.Lock),
    ShizukuCapability("activity", R.string.shizuku_cap_activity_desc, Icons.Default.Launch),
    ShizukuCapability("display", R.string.shizuku_cap_display_desc, Icons.Default.Fullscreen),
    ShizukuCapability("settings", R.string.shizuku_cap_settings_desc, Icons.Default.Settings),
    ShizukuCapability("user", R.string.shizuku_cap_user_desc, Icons.Default.Person),
    ShizukuCapability("network", R.string.shizuku_cap_network_desc, Icons.Default.Wifi),
    ShizukuCapability("input", R.string.shizuku_cap_input_desc, Icons.Default.TouchApp),
    ShizukuCapability("notification", R.string.shizuku_cap_notification_desc, Icons.Default.Notifications),
    ShizukuCapability("file", R.string.shizuku_cap_file_desc, Icons.Default.Folder),
    ShizukuCapability("device", R.string.shizuku_cap_device_desc, Icons.Default.PhoneAndroid),
    ShizukuCapability("service", R.string.shizuku_cap_service_desc, Icons.Default.Dns),
)
