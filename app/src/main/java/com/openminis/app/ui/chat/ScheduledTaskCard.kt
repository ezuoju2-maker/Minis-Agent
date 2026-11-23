package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.scheduled.ScheduledTaskDetailModel
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.scheduled.ScheduledTaskMarker
import com.openminis.app.scheduled.ScheduledTaskStore
import com.openminis.app.ui.theme.ChatColors
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map

internal val ScheduledAccent = Color(0xFFF59E0B)

@Composable
internal fun ScheduledTaskCard(
    marker: ScheduledTaskMarker,
    modifier: Modifier = Modifier,
    hostSessionId: String? = null,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    var confirming by remember { mutableStateOf(false) }
    var cancelled by remember(marker.taskId) { mutableStateOf(false) }
    var showingDetail by remember { mutableStateOf(false) }

    val now by produceState(System.currentTimeMillis(), marker.nextFireAtMs) {
        val next = marker.nextFireAtMs ?: return@produceState
        while (true) {
            val wait = next - System.currentTimeMillis()
            if (wait <= 0) break
            delay(ScheduledTaskDetailModel.nextTickMs(wait))
            value = System.currentTimeMillis()
        }
    }
    val store = remember { ScheduledTaskStore(context.applicationContext) }
    val live by remember(marker.taskId) {
        store.observe().map { tasks -> tasks.any { it.id == marker.taskId && it.enabled } }
    }.collectAsState(initial = remember(marker.taskId) { store.get(marker.taskId)?.enabled == true })

    val phrases = ScheduledTaskDetailModel.CardPhrases(
        run = stringResource(R.string.scheduled_card_run),
        left = stringResource(R.string.scheduled_card_left),
        next = stringResource(R.string.scheduled_card_next),
        complete = stringResource(R.string.scheduled_card_task_complete),
        cancelled = stringResource(R.string.scheduled_card_cancelled),
    )
    val subline = ScheduledTaskDetailModel.cardSubline(marker, cancelled, now, phrases, live = live) {
        relativeFireTime(context, it, now)
    }

    val cancelLabel = stringResource(R.string.scheduled_card_cancel)
    TaskCardFrame(
        accent = ScheduledAccent,
        onClick = { showingDetail = true },
        onClickLabel = null,
        modifier = modifier,
        icon = {
            Icon(
                Icons.Default.Schedule,
                contentDescription = null,
                tint = ScheduledAccent,
                modifier = Modifier.size(TaskCardMetrics.iconGlyph),
            )
        },
        action = if (live && !cancelled) {
            {
                TaskCardActionDisc(
                    label = cancelLabel,
                    background = ScheduledAccent,
                    onClick = { confirming = true },
                ) { TaskCardActionGlyph(Icons.Default.AlarmOff) }
            }
        } else {
            null
        },
    ) {
        TaskCardTitleRow {
            TaskCardChip(stringResource(R.string.scheduled_card_label), ScheduledAccent)
            TaskCardTitle(marker.label.ifEmpty { marker.prompt.take(40) })
        }
        TaskCardStatusRow {
            Text(
                subline,
                fontSize = TaskCardMetrics.subtitleSize,
                lineHeight = TaskCardMetrics.subtitleLineHeight,
                color = ChatColors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .height(taskCardStatusLineHeight())
                    .wrapContentHeight(Alignment.CenterVertically, unbounded = true),
            )
        }
    }

    if (showingDetail) {
        ScheduledTaskDetailSheet(
            marker = marker,
            hostSessionId = hostSessionId,
            onDismiss = { showingDetail = false },
        )
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.scheduled_card_cancel_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.scheduled_card_cancel_confirm_body,
                        marker.label.ifEmpty { marker.prompt.take(40) },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ScheduledTaskManager(context.applicationContext as android.app.Application)
                        .setEnabled(marker.taskId, false)
                    cancelled = true
                    confirming = false
                }) { Text(stringResource(R.string.scheduled_card_cancel_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.scheduled_card_cancel_confirm_keep))
                }
            },
        )
    }
}

internal fun relativeFireTime(context: android.content.Context, atMs: Long, nowMs: Long): String {
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val f = android.icu.text.RelativeDateTimeFormatter.getInstance(
        android.icu.util.ULocale.forLocale(locale), null,
        android.icu.text.RelativeDateTimeFormatter.Style.SHORT,
        android.icu.text.DisplayContext.CAPITALIZATION_NONE,
    )
    val sec = ((atMs - nowMs) / 1000L).coerceAtLeast(0L)
    val (value, unit) = ScheduledTaskDetailModel.relativeUnit(sec)
    val icuUnit = when (unit) {
        ScheduledTaskDetailModel.RelativeUnit.SECOND -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.SECONDS
        ScheduledTaskDetailModel.RelativeUnit.MINUTE -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MINUTES
        ScheduledTaskDetailModel.RelativeUnit.HOUR -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.HOURS
        ScheduledTaskDetailModel.RelativeUnit.DAY -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.DAYS
        ScheduledTaskDetailModel.RelativeUnit.WEEK -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.WEEKS
        ScheduledTaskDetailModel.RelativeUnit.MONTH -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MONTHS
        ScheduledTaskDetailModel.RelativeUnit.YEAR -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.YEARS
    }
    return f.format(value.toDouble(), android.icu.text.RelativeDateTimeFormatter.Direction.NEXT, icuUnit)
}
