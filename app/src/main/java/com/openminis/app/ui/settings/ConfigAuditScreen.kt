package com.openminis.app.ui.settings

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.config.audit.ConfigAuditActor
import com.openminis.app.config.audit.ConfigAuditEntry
import com.openminis.app.config.audit.ConfigAuditLog
import com.openminis.app.config.audit.ConfigAuditStatus
import com.openminis.app.config.ConfigBridge
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

@Composable
fun ConfigAuditScreen(
    modifier: Modifier = Modifier,
    onOpenEntry: (String) -> Unit = {},
) {
    val log = remember { ConfigAuditLog.get() }
    val revision by log.revision.collectAsState()
    var entries by remember { mutableStateOf<List<ConfigAuditEntry>>(emptyList()) }
    var usage by remember { mutableStateOf(ConfigAuditLog.Usage(0, 1000)) }
    var revertCandidate by remember { mutableStateOf<ConfigAuditEntry?>(null) }
    var revertResult by remember { mutableStateOf<RevertMessage?>(null) }

    LaunchedEffect(revision) {
        withContext(Dispatchers.IO) {
            val recent = log.recent(limit = 200)
            val u = log.usage()
            entries = recent
            usage = u
        }
    }

    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val localize = rememberReadableLocalizer()

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.logs_config_used_count, usage.count, usage.capacity),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.logs_config_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    AuditRow(
                        entry = entry,
                        localize = localize,
                        onOpen = { onOpenEntry(entry.id) },
                        onRevert = { revertCandidate = entry },
                    )
                }
            }
        }
    }

    revertCandidate?.let { entry ->
        AlertDialog(
            onDismissRequest = { revertCandidate = null },
            title = { Text(stringResource(R.string.logs_config_revert_dialog_title)) },
            text = { Text(text = configRevertMessage(entry, localize)) },
            confirmButton = {
                val successTitle = stringResource(R.string.logs_config_revert_success_title)
                val successBody = stringResource(R.string.logs_config_revert_success_body)
                val failedTitle = stringResource(R.string.logs_config_revert_failed_title)
                val unknownErr = stringResource(R.string.logs_config_revert_unknown_error)
                MinisTextButton(onClick = {
                    val target = entry
                    revertCandidate = null
                    scope.launch {
                        val res = withContext(Dispatchers.IO) {
                            ConfigBridge.auditRevert(
                                id = target.id,
                                actorRaw = "user-revert",
                                sessionId = null,
                                skipConfirmation = true,
                            )
                        }
                        revertResult = if (res.optBoolean("ok", false)) {
                            RevertMessage(successTitle, successBody)
                        } else {
                            RevertMessage(failedTitle, res.optString("reason", unknownErr))
                        }
                    }
                }) {
                    Text(stringResource(R.string.logs_config_revert), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { revertCandidate = null }) {
                    Text(stringResource(R.string.logs_config_dialog_cancel))
                }
            },
        )
    }

    revertResult?.let { msg ->
        AlertDialog(
            onDismissRequest = { revertResult = null },
            title = { Text(msg.title) },
            text = { Text(msg.body) },
            confirmButton = {
                MinisTextButton(onClick = { revertResult = null }) {
                    Text(stringResource(R.string.logs_config_dialog_ok))
                }
            },
        )
    }
}

private data class RevertMessage(val title: String, val body: String)

@Composable
private fun AuditRow(
    entry: ConfigAuditEntry,
    localize: (String) -> String?,
    onOpen: () -> Unit,
    onRevert: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConfigAuditStatusBadge(entry.status)
            Spacer(Modifier.width(6.dp))
            Text(
                text = actorLabel(entry.actor),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Text(
                text = coarseRelativeTime(ctx, entry.at),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = entry.key,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        val readable = entry.readable?.displayText(localize).orEmpty()
        if (readable.isNotEmpty()) {
            ConfigReadableDivider()
            Text(
                text = readable,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = if (entry.status == ConfigAuditStatus.APPLIED) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        } else Row(verticalAlignment = Alignment.Top) {
            Text(
                text = displayJSON(entry.oldValueJSON),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = displayJSON(entry.newValueJSON),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (entry.status == ConfigAuditStatus.APPLIED) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (!entry.caption.isNullOrEmpty()) {
            Text(
                text = "📝 ${entry.caption}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (entry.status) {
            ConfigAuditStatus.APPLIED -> {
                AssistChip(
                    onClick = onRevert,
                    label = { Text(stringResource(R.string.logs_config_revert_chip)) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
            ConfigAuditStatus.REVERTED -> {
                Text(
                    text = stringResource(R.string.logs_config_reverted_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8E5CD9),
                )
            }
            else -> Unit
        }
    }
}

@Composable
private fun actorLabel(actor: ConfigAuditActor): String = when (actor) {
    ConfigAuditActor.AGENT -> stringResource(R.string.logs_config_source_agent)
    ConfigAuditActor.USER -> stringResource(R.string.logs_config_source_user)
    ConfigAuditActor.AGENT_REVERT -> stringResource(R.string.logs_config_source_agent_revert)
    ConfigAuditActor.USER_REVERT -> stringResource(R.string.logs_config_source_user_revert)
}

private fun coarseRelativeTime(ctx: android.content.Context, epochMs: Long): String {
    val now = System.currentTimeMillis()
    val secs = ((now - epochMs) / 1000L).coerceAtLeast(0)
    return when {
        secs < 60 -> ctx.getString(R.string.logs_config_time_just_now)
        secs < 3600 -> ctx.getString(R.string.logs_config_time_min_ago, (secs / 60).toInt())
        secs < 86_400 -> ctx.getString(R.string.logs_config_time_hr_ago, (secs / 3600).toInt())
        else -> {
            val sameDay = (now - epochMs) < 2 * 86_400_000L
            if (sameDay) ctx.getString(R.string.logs_config_time_yesterday)
            else java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(epochMs))
        }
    }
}

private fun displayJSON(json: String): String {
    if (json.isEmpty()) return "—"
    if (json.length >= 2 && json.startsWith('"') && json.endsWith('"')) {
        return json.substring(1, json.length - 1)
    }
    return json
}
