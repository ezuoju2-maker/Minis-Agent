package com.openminis.app.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.util.expandToggleClickable

@Composable
internal fun ToolGroupRow(item: FlatChatItem.AssistantToolGroup, onToggle: () -> Unit) {
    val title = stringResource(R.string.tool_fold_ran_n_tools, item.toolCount)
    val failed = stringResource(R.string.tool_fold_n_failed, item.failedCount)
    val cancelled = stringResource(R.string.tool_fold_n_cancelled, item.cancelledCount)
    val duration = item.durationMs?.let(::formatToolDuration)
    val hint = stringResource(if (item.expanded) R.string.tool_fold_collapse_hint else R.string.tool_fold_expand_hint)
    val spoken = buildList {
        add(title)
        duration?.let(::add)
        if (item.failedCount > 0) add(failed)
        if (item.cancelledCount > 0) add(cancelled)
    }.joinToString(", ")
    val chevronAngle = animateFloatAsState(if (item.expanded) 90f else 0f, label = "toolGroupChevron")
    val text = buildAnnotatedString {
        append(title)
        if (duration != null) {
            append(" · ")
            withStyle(SpanStyle(fontFeatureSettings = "tnum")) { append(duration) }
        }
        if (item.failedCount > 0) {
            append(" · ")
            withStyle(SpanStyle(color = ToolErrorColor)) { append(failed) }
        }
        if (item.cancelledCount > 0) {
            append(" · ")
            append(cancelled)
        }
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(28.dp)
                .expandToggleClickable(onClick = onToggle)
                .padding(start = 0.dp, end = 8.dp)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    stateDescription = hint
                    role = Role.Button
                    onClick(label = hint) { onToggle(); true }
                },
        ) {
            FoldChevron(angle = chevronAngle.value, color = ChatColors.secondaryText)
            Spacer(Modifier.width(5.dp))
            Text(
                text = text,
                fontSize = 13.sp,
                color = ChatColors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FoldChevron(angle: Float, color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .size(width = FoldChevronWidth, height = FoldChevronHeight)
            .graphicsLayer { rotationZ = angle },
    ) {
        val stroke = FoldChevronStroke.toPx()
        val inset = stroke / 2f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(inset, inset)
            lineTo(size.width - inset, size.height / 2f)
            lineTo(inset, size.height - inset)
        }
        drawPath(
            path = path,
            color = color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            ),
        )
    }
}

internal val FoldChevronWidth = 5.5.dp
internal val FoldChevronHeight = 10.dp
private val FoldChevronStroke = 1.6.dp
