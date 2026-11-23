package com.openminis.app.ui.chat

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.logging.AppLogger
import java.io.File


internal const val LARGE_MESSAGE_THRESHOLD_CHARS = 32_000
internal const val LARGE_MESSAGE_PREVIEW_CHARS = 8_000

internal const val STREAM_DEGRADE_CHARS = 8_000

internal fun shouldCollapse(content: String, isStreaming: Boolean): Boolean =
    !isStreaming && content.length > LARGE_MESSAGE_THRESHOLD_CHARS

@Composable
internal fun LargeContentBadge(
    content: String,
    stableKey: String,
    onExpand: () -> Unit,
) {
    val context = LocalContext.current
    val sizeLabel = remember(content.length) { formatByteSize(content.length) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp),
            )
            .border(
                0.5.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val preview = remember(content) {
            content.take(LARGE_MESSAGE_PREVIEW_CHARS) + if (content.length > LARGE_MESSAGE_PREVIEW_CHARS) "…" else ""
        }
        Text(
            text = preview,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.chat_message_large_collapsed_title, sizeLabel),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )

            MinisTextButton(
                onClick = {
                    AppLogger.info(
                        "LargeContentGuard",
                        "expand requested key=$stableKey chars=${content.length}",
                    )
                    onExpand()
                },
                modifier = Modifier.padding(0.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.UnfoldMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.chat_message_large_expand),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            MinisTextButton(
                onClick = {
                    val ok = exportLargeContentToFile(context, content, stableKey)
                    Toast.makeText(
                        context,
                        if (ok) {
                            context.getString(R.string.chat_message_large_export_ok)
                        } else {
                            context.getString(R.string.chat_message_large_export_fail)
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                modifier = Modifier.padding(0.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.IosShare,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.chat_message_large_export),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

@Composable
internal fun LargeContentGuard(
    content: String,
    isStreaming: Boolean,
    stableKey: String,
    renderer: @Composable () -> Unit,
) {
    val wasDegraded = remember(stableKey) { mutableStateOf(false) }
    if (isStreaming) {
        if (content.length > STREAM_DEGRADE_CHARS) {
            wasDegraded.value = true
            remember(content.length / 2000) {
                val s = com.openminis.app.diagnostics.ContentDiag.summarize(content)
                AppLogger.info(
                    "Perf",
                    "[Perf][ContentDiag] stream-degrade key=$stableKey " +
                        "threshold=$STREAM_DEGRADE_CHARS ${s.asLogFields()}",
                )
                Unit
            }
            DegradedStreamingTail(content)
            return
        }
    }
    if (!shouldCollapse(content, isStreaming)) {
        if (!isStreaming && wasDegraded.value) {
            wasDegraded.value = false
            remember(stableKey, content.length) {
                val s = com.openminis.app.diagnostics.ContentDiag.summarize(content)
                AppLogger.info(
                    "Perf",
                    "[Perf][ContentDiag] stream-end-swap key=$stableKey ${s.asLogFields()}",
                )
                Unit
            }
        }
        if (content.length >= com.openminis.app.diagnostics.CONTENT_DIAG_MIN_CHARS) {
            val msgId = remember(stableKey) { stableKey.substringAfter(':').substringBefore(':') }
            androidx.compose.runtime.DisposableEffect(stableKey, content.length) {
                com.openminis.app.diagnostics.ContentDiag.setCurrentRender(
                    sessionId = "",
                    messageId = msgId,
                    summary = com.openminis.app.diagnostics.ContentDiag.summarize(content),
                )
                onDispose { com.openminis.app.diagnostics.ContentDiag.clearCurrentRender(msgId) }
            }
        }
        renderer()
        return
    }

    var expanded by rememberSaveable(stableKey) { mutableStateOf(false) }

    remember(stableKey) {
        AppLogger.info(
            "LargeContentGuard",
            "collapse key=$stableKey chars=${content.length} threshold=$LARGE_MESSAGE_THRESHOLD_CHARS",
        )
        Unit
    }

    if (expanded) {
        renderer()
    } else {
        LargeContentBadge(
            content = content,
            stableKey = stableKey,
            onExpand = { expanded = true },
        )
    }
}

private const val DEGRADED_TAIL_CHARS = 4_000

@Composable
private fun DegradedStreamingTail(content: String) {
    LaunchedEffect(Unit) {
        com.openminis.app.logging.AppLogger.info(
            "StreamDegrade",
            "live block over $STREAM_DEGRADE_CHARS chars (${content.length}) rendered as a plain tail",
        )
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (content.length > DEGRADED_TAIL_CHARS) {
                "…" + content.takeLast(DEGRADED_TAIL_CHARS)
            } else {
                content
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatByteSize(chars: Int): String {
    val bytes = chars.toLong()
    return when {
        bytes >= 1_000_000L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000L -> "${bytes / 1_000L} KB"
        else -> "$bytes B"
    }
}

private fun exportLargeContentToFile(
    context: android.content.Context,
    content: String,
    stableKey: String,
): Boolean = try {
    val dir = File(context.cacheDir, "large-messages").apply { mkdirs() }
    val safeKey = stableKey.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(64)
    val file = File(dir, "msg-$safeKey-${System.currentTimeMillis()}.txt")
    file.writeText(content)
    val authority = context.packageName + ".fileprovider"
    val uri = FileProvider.getUriForFile(context, authority, file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val chooser = Intent.createChooser(send, context.getString(R.string.chat_message_large_export))
    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
    true
} catch (t: Throwable) {
    AppLogger.warning("LargeContentGuard", "export failed: ${t.message}")
    false
}
