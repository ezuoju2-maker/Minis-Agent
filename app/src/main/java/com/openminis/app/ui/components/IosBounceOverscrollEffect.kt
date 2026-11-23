package com.openminis.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

@OptIn(ExperimentalFoundationApi::class)
class IosBounceOverscrollEffect(
    private val scope: CoroutineScope,
    private val maxOverscrollPx: Float = 600f,
) : OverscrollEffect {

    private val offset = Animatable(0f)

    override val isInProgress: Boolean
        get() = offset.value != 0f

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        val curOffset = offset.value
        val sameSign = curOffset != 0f && sign(delta.y) != sign(curOffset)
        val preConsumed = if (sameSign) {
            val correction = (-curOffset).coerceAtMost(abs(delta.y)) * sign(delta.y)
            scope.launch { offset.snapTo(curOffset + correction) }
            Offset(0f, correction)
        } else {
            Offset.Zero
        }

        val afterPre = delta - preConsumed
        val consumedByInner = performScroll(afterPre)
        val leftover = afterPre - consumedByInner

        if (leftover.y != 0f) {
            val newOffset = curOffset + preConsumed.y + rubberBand(leftover.y, curOffset + preConsumed.y)
            scope.launch { offset.snapTo(newOffset) }
        }

        return preConsumed + consumedByInner
    }

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ) {
        val leftover = performFling(velocity)
        offset.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
            initialVelocity = leftover.y,
        )
    }

    private fun rubberBand(delta: Float, currentOffset: Float): Float {
        val pull = abs(currentOffset).coerceAtMost(maxOverscrollPx)
        val resistance = (1f - (pull / maxOverscrollPx)).coerceAtLeast(0f).pow(0.55f)
        return delta * resistance
    }

    override val effectModifier: Modifier = Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.placeRelative(0, offset.value.toInt())
        }
    }
}

@Composable
fun rememberIosBounceOverscrollEffect(): IosBounceOverscrollEffect {
    val scope = rememberCoroutineScope()
    return remember(scope) { IosBounceOverscrollEffect(scope) }
}
