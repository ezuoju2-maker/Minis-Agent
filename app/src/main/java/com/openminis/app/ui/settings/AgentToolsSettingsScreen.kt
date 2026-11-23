package com.openminis.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.tools.AgentToolSwitch

@Composable
fun AgentToolsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var browserEnabled by remember {
        mutableStateOf(AgentToolSwitch.BROWSER.isEnabled(context))
    }
    var userAskEnabled by remember {
        mutableStateOf(com.openminis.app.agent.userask.UserAskSettings.isEnabled(context))
    }
    var userAskTimeout by remember {
        mutableStateOf(com.openminis.app.agent.userask.UserAskSettings.timeoutMinutes(context))
    }
    var userAskNotify by remember {
        mutableStateOf(com.openminis.app.agent.userask.UserAskSettings.notifyInBackground(context))
    }
    var showWaitPicker by remember { mutableStateOf(false) }

    SettingsScaffold(title = stringResource(R.string.settings_agent_tools), onBack = onBack) {
        SettingsSection(footer = stringResource(R.string.agent_tools_browser_footer)) {
            SettingsSwitchRow(
                title = stringResource(R.string.agent_tools_browser),
                checked = browserEnabled,
                onCheckedChange = {
                    browserEnabled = it
                    AgentToolSwitch.BROWSER.setEnabled(context, it)
                },
                showDivider = false,
            )
        }
        SettingsSection(footer = stringResource(R.string.user_ask_setting_footer)) {
            SettingsSwitchRow(
                title = stringResource(R.string.user_ask_setting_title),
                checked = userAskEnabled,
                onCheckedChange = {
                    userAskEnabled = it
                    com.openminis.app.agent.userask.UserAskSettings.setEnabled(context, it)
                },
                showDivider = userAskEnabled,
            )
            if (userAskEnabled) {
                SettingsValueRow(
                    title = stringResource(R.string.user_ask_setting_wait),
                    value = userAskWaitLabel(userAskTimeout),
                    onClick = { showWaitPicker = true },
                    showDivider = true,
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.user_ask_setting_notify),
                    checked = userAskNotify,
                    onCheckedChange = {
                        userAskNotify = it
                        com.openminis.app.agent.userask.UserAskSettings.setNotifyInBackground(context, it)
                    },
                    showDivider = false,
                )
            }
        }
        if (showWaitPicker) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showWaitPicker = false },
                title = { androidx.compose.material3.Text(stringResource(R.string.user_ask_setting_wait)) },
                text = {
                    androidx.compose.foundation.layout.Column {
                        for (m in com.openminis.app.agent.userask.UserAskSettingsSpec.TIMEOUT_CHOICES) {
                            SettingsChoiceRow(
                                title = userAskWaitLabel(m),
                                selected = m == userAskTimeout,
                                onSelect = {
                                    userAskTimeout = m
                                    com.openminis.app.agent.userask.UserAskSettings.setTimeoutMinutes(context, m)
                                    showWaitPicker = false
                                },
                                showDivider = false,
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showWaitPicker = false }) {
                        androidx.compose.material3.Text(stringResource(R.string.user_ask_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun userAskWaitLabel(minutes: Int): String =
    if (minutes == 0) stringResource(R.string.user_ask_setting_indefinitely)
    else stringResource(R.string.user_ask_setting_minutes, minutes)
