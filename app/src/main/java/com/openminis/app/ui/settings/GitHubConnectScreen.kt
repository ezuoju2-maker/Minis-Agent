package com.openminis.app.ui.settings

import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.openminis.app.R
import com.openminis.app.github.GitHubApi
import com.openminis.app.github.GitHubAuth
import com.openminis.app.github.GitHubDeviceFlow
import com.openminis.app.ui.components.MinisTextButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun GitHubConnectScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = remember { GitHubAuth(context) }
    var session by remember { mutableStateOf(auth.session()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deviceAuth by remember { mutableStateOf<GitHubDeviceFlow.Authorization?>(null) }
    var loginJob by remember { mutableStateOf<Job?>(null) }
    var fullScope by remember { mutableStateOf(true) }
    var manualToken by remember { mutableStateOf("") }
    var clientIdInput by remember {
        mutableStateOf(if (auth.usesDefaultClientId()) "" else auth.clientId())
    }

    fun finishLogin(token: String, flowScopes: List<String>) {
        scope.launch {
            busy = true
            error = null
            try {
                val reply = GitHubApi(token).request("GET", "/user")
                if (!reply.ok) {
                    error = context.getString(R.string.github_token_invalid) + " (HTTP ${reply.code})"
                    return@launch
                }
                val json = runCatching { org.json.JSONObject(reply.body) }.getOrNull()
                val login = json?.optString("login", "").orEmpty()
                val name = json?.optString("name", "").orEmpty()
                val scopes = reply.scopes.split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .ifEmpty { flowScopes }
                auth.save(
                    token = token,
                    login = login,
                    name = name,
                    scopes = scopes,
                    kind = "oauth",
                )
                session = auth.session()
                manualToken = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    SettingsScaffold(title = stringResource(R.string.github_connector_title), onBack = onBack) {
        SettingsSection(
            header = stringResource(R.string.github_section_account),
            footer = error,
        ) {
            val current = session
            if (current != null) {
                SettingsRow(
                    title = stringResource(R.string.github_connected_as, current.login.ifBlank { "—" }),
                    subtitle = current.name.takeIf { it.isNotBlank() },
                    icon = Icons.Default.CheckCircle,
                    iconColor = Color(0xFF34C759),
                    showChevron = false,
                )
                SettingsRow(
                    title = stringResource(R.string.github_scopes),
                    subtitle = current.scopes.ifEmpty { listOf("—") }.joinToString(", "),
                    showChevron = false,
                )
                SettingsRow(
                    title = stringResource(R.string.github_token_kind),
                    subtitle = if (current.kind == "fine-grained") {
                        stringResource(R.string.github_kind_fine)
                    } else {
                        stringResource(R.string.github_kind_classic)
                    },
                    showChevron = false,
                )
                SettingsRow(
                    title = stringResource(R.string.github_saved_at),
                    subtitle = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        .format(Date(current.savedAt)),
                    showDivider = false,
                    showChevron = false,
                )
            } else {
                SettingsRow(
                    title = stringResource(R.string.github_not_connected),
                    subtitle = stringResource(R.string.github_not_connected_hint),
                    showDivider = false,
                    showChevron = false,
                )
            }
        }

        SettingsSection(
            header = stringResource(R.string.github_section_connect),
            footer = stringResource(R.string.github_connect_device_hint),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.github_full_scope),
                subtitle = stringResource(R.string.github_full_scope_hint),
                checked = fullScope,
                onCheckedChange = { fullScope = it },
            )
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Button(
                    onClick = {
                        error = null
                        busy = true
                        loginJob = scope.launch {
                            try {
                                val requested = if (fullScope) {
                                    GitHubDeviceFlow.MAX_SCOPE
                                } else {
                                    GitHubDeviceFlow.READ_SCOPE
                                }
                                val start = GitHubDeviceFlow.start(auth.clientId(), requested)
                                deviceAuth = start
                                val done = GitHubDeviceFlow.await(auth.clientId(), start)
                                deviceAuth = null
                                finishLogin(done.token, done.scopes)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                deviceAuth = null
                                error = e.message ?: e.javaClass.simpleName
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.github_connect_device))
                }
            }
        }

        SettingsSection(
            header = stringResource(R.string.github_section_manual),
            footer = stringResource(R.string.github_manual_hint),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = manualToken,
                    onValueChange = { manualToken = it },
                    label = { Text(stringResource(R.string.github_manual_label)) },
                    placeholder = { Text("ghp_… / github_pat_…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val token = manualToken.trim()
                        if (token.isNotEmpty()) finishLogin(token, emptyList())
                    },
                    enabled = !busy && manualToken.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.github_save_token))
                }
            }
        }

        SettingsSection(
            header = stringResource(R.string.github_section_client),
            footer = stringResource(R.string.github_client_id_hint),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = clientIdInput,
                    onValueChange = {
                        clientIdInput = it
                        auth.saveClientId(it)
                    },
                    label = { Text(stringResource(R.string.github_client_id)) },
                    placeholder = { Text(GitHubDeviceFlow.DEFAULT_CLIENT_ID) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (session != null) {
            Column(Modifier.fillMaxWidth().padding(24.dp, 24.dp, 24.dp, 32.dp)) {
                Button(
                    onClick = {
                        auth.disconnect()
                        session = null
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.github_disconnect))
                }
            }
        } else {
            Spacer(Modifier.height(32.dp))
        }
    }

    val pending = deviceAuth
    if (pending != null) {
        GitHubDeviceDialog(
            authorization = pending,
            onCancel = {
                loginJob?.cancel()
                deviceAuth = null
                busy = false
            },
        )
    }
}

@Composable
private fun GitHubDeviceDialog(
    authorization: GitHubDeviceFlow.Authorization,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copiedCode by remember { mutableStateOf(false) }
    var copiedLink by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.github_device_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.github_device_instructions, authorization.openUrl),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboard.setText(AnnotatedString(authorization.userCode))
                            copiedCode = true
                            Toast.makeText(
                                context,
                                context.getString(R.string.github_device_copied),
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                    ) {
                        Text(
                            authorization.userCode,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        Spacer(Modifier.width(10.dp))
                        Icon(
                            if (copiedCode) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.github_device_copy_code),
                            tint = if (copiedCode) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val customTabsIntent = CustomTabsIntent.Builder().setShowTitle(true).build()
                            customTabsIntent.launchUrl(context, authorization.openUrl.toUri())
                        }
                        .padding(vertical = 8.dp),
                ) {
                    Icon(
                        Icons.Default.OpenInBrowser,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.github_device_open),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboard.setText(AnnotatedString(authorization.openUrl))
                            copiedLink = true
                            Toast.makeText(
                                context,
                                context.getString(R.string.github_device_copied),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        .padding(vertical = 8.dp),
                ) {
                    Icon(
                        if (copiedLink) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.github_device_copy_link),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.github_device_waiting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            MinisTextButton(onClick = onCancel) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
