package com.openminis.app.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.components.rememberDecorativeTick
import com.openminis.app.ui.components.decorativePhase
import com.openminis.app.ui.components.decorativePingPong


@Composable
internal fun BouncingDots(color: Color) {
    val tick = rememberDecorativeTick()
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .graphicsLayer {
                        val p = decorativePingPong(decorativePhase(tick.value, 700, i * 120))
                        translationY = -2.dp.toPx() * p
                    }
                    .background(color, CircleShape),
            )
        }
    }
}

@Composable
internal fun StreamingDotsText() {
    val tick = rememberDecorativeTick()
    Row {
        repeat(3) { i ->
            Text(
                text = ".",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.graphicsLayer {
                    val p = decorativePingPong(decorativePhase(tick.value, 700, i * 120))
                    translationY = -3.dp.toPx() * p
                },
            )
        }
    }
}


@Composable
internal fun CompactProgressIndicator(
    progress: ChatViewModel.CompactProgress,
    onCancel: () -> Unit,
) {
    var elapsedSec by remember(progress.startedAtMs) { mutableStateOf(0) }
    LaunchedEffect(progress.startedAtMs) {
        while (true) {
            elapsedSec = ((System.currentTimeMillis() - progress.startedAtMs) / 1000L).toInt()
            kotlinx.coroutines.delay(1000)
        }
    }
    val fg = ChatColors.secondaryText
    val rule = ChatColors.separator

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f).height(1.dp).background(rule))
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                color = fg,
                strokeWidth = 1.5.dp,
            )
            Text(
                text = if (progress.depth > 0) {
                    stringResource(
                        R.string.compact_progress_split,
                        elapsedSec,
                        progress.callsIssued,
                        progress.callBudget,
                    )
                } else {
                    stringResource(R.string.compact_progress, elapsedSec)
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = fg,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = stringResource(R.string.cancel),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
        Box(modifier = Modifier.weight(1f).height(1.dp).background(rule))
    }
}


@Composable
internal fun TypingIndicator(
    retryAttempt: Int = 0,
    retryMax: Int = 0,
    onRetryBadgeClick: (() -> Unit)? = null,
) {
    val tick = rememberDecorativeTick()
    val soulMeta by com.openminis.app.agent.SoulStore.cachedMetadata.collectAsState()
    val soulName = soulMeta.name.trim().ifEmpty { "DeepSeek" }

    Row(
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (retryAttempt > 0 && onRetryBadgeClick != null) {
            androidx.compose.foundation.layout.Box(modifier = Modifier.align(Alignment.CenterVertically)) {
                RetryBadge(attempt = retryAttempt, maxAttempts = retryMax, onClick = onRetryBadgeClick)
            }
        }
        Text(
            text = stringResource(R.string.chat_typing_indicator, soulName),
            fontSize = 15.sp,
            color = ChatColors.tertiaryText,
        )
        val dots = listOf(".", ".", ".")
        dots.forEachIndexed { index, dot ->
            Text(
                text = dot,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = ChatColors.tertiaryText,
                modifier = Modifier.graphicsLayer {
                    val p = decorativePingPong(decorativePhase(tick.value, 800, index * 150))
                    translationY = -6.dp.toPx() * p
                },
            )
        }
    }
}
