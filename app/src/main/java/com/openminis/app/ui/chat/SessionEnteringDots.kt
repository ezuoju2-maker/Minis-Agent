package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.components.decorativePhase
import com.openminis.app.ui.components.decorativePingPong
import com.openminis.app.ui.components.rememberDecorativeTick

internal fun showSessionEnteringIndicator(
    isDraft: Boolean,
    sessionLoaded: Boolean,
    messagesEmpty: Boolean,
    safeMode: Boolean,
): Boolean = !isDraft && !sessionLoaded && messagesEmpty && !safeMode

@Composable
internal fun SessionEnteringDots(modifier: Modifier = Modifier) {
    val tick = rememberDecorativeTick()
    val color = MaterialTheme.colorScheme.primary
    Box(modifier = modifier.height(24.dp), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(DOT_SIZE * 0.7f)) {
            repeat(3) { i ->
                Box(
                    modifier = Modifier
                        .size(DOT_SIZE)
                        .graphicsLayer {
                            val p = SessionEnteringDots.level(tick.value, i)
                            val scale = SessionEnteringDots.MIN_SCALE + (1f - SessionEnteringDots.MIN_SCALE) * p
                            scaleX = scale
                            scaleY = scale
                            alpha = SessionEnteringDots.MIN_ALPHA +
                                (SessionEnteringDots.MAX_ALPHA - SessionEnteringDots.MIN_ALPHA) * p
                        }
                        .background(color, CircleShape),
                )
            }
        }
    }
}

private val DOT_SIZE = 8.dp

internal object SessionEnteringDots {
    const val PERIOD_MS = 1_000
    const val STAGGER_MS = 160
    const val MIN_SCALE = 0.55f
    const val MIN_ALPHA = 0.3f
    const val MAX_ALPHA = 0.95f

    const val ENTER_DELAY_MS = 120
    const val ENTER_MS = 200

    const val EXIT_MS = 280

    fun level(nanos: Long, index: Int): Float {
        val x = decorativePingPong(decorativePhase(nanos, PERIOD_MS, index * STAGGER_MS))
        return x * x * (3f - 2f * x)
    }
}
