package com.openminis.app.ui.components

import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.AndroidUiDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.floor
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp


fun Modifier.animationLayer(): Modifier = graphicsLayer()

object DecorativeClock {
    const val INTERVAL_NS = 33_333_333L

    private val _nanos = mutableLongStateOf(0L)
    val nanos: State<Long> get() = _nanos

    private val scope = CoroutineScope(AndroidUiDispatcher.Main + SupervisorJob())
    private var job: Job? = null
    private var readers = 0

    val isRunning: Boolean get() = job != null

    @MainThread
    fun acquire() {
        if (readers++ == 0) start()
    }

    @MainThread
    fun release() {
        readers = (readers - 1).coerceAtLeast(0)
        if (readers == 0) {
            job?.cancel()
            job = null
        }
    }

    private fun start() {
        job = scope.launch {
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    if (now - last >= INTERVAL_NS) {
                        last = now
                        _nanos.longValue = now
                    }
                }
            }
        }
    }
}

@Composable
fun rememberDecorativeTick(): State<Long> {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        var held = false
        fun hold() { if (!held) { held = true; DecorativeClock.acquire() } }
        fun drop() { if (held) { held = false; DecorativeClock.release() } }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> hold()
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> drop()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            drop()
        }
    }
    return DecorativeClock.nanos
}

fun decorativePhase(nanos: Long, periodMs: Int, offsetMs: Int = 0): Float {
    if (periodMs <= 0) return 0f
    val periodNs = periodMs * 1_000_000L
    val shifted = nanos - offsetMs * 1_000_000L
    val m = shifted % periodNs
    val pos = if (m < 0) m + periodNs else m
    val r = pos.toFloat() / periodNs
    return if (r >= 1f) 0f else r
}

fun decorativePingPong(phase: Float): Float {
    val p = phase - floor(phase)
    return if (p < 0.5f) p * 2f else (1f - p) * 2f
}

@Composable
fun DecorativeSpinner(
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.dp,
    sweepFraction: Float = 0.75f,
    periodMs: Int = 900,
) {
    val tick = rememberDecorativeTick()
    Canvas(
        modifier = modifier.graphicsLayer {
            rotationZ = decorativePhase(tick.value, periodMs) * 360f
        },
    ) {
        val px = strokeWidth.toPx()
        val inset = px / 2f
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 360f * sweepFraction,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - px, size.height - px),
            style = Stroke(width = px, cap = StrokeCap.Round),
        )
    }
}
