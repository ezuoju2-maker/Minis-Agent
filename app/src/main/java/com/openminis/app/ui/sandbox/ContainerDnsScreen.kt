package com.openminis.app.ui.sandbox

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.ui.components.SettingsSection
import com.openminis.app.ui.settings.SettingsScaffold

@Composable
fun ContainerDnsScreen(onBack: () -> Unit) {
    SettingsScaffold(title = stringResource(R.string.network_container_dns), onBack = onBack) {
        SettingsSection(title = stringResource(R.string.rootfs_network_section)) {
            ContainerNetworkSection()
        }
    }
}
