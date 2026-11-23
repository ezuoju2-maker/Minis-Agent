package com.openminis.app.ui.chat

internal fun shouldPulse(current: Int, baseline: Int): Boolean = current > baseline

internal interface PulseAlpha {
    val value: Float
    suspend fun animateTo(target: Float)
    suspend fun snapTo(target: Float)
}

internal const val FALLBACK_PULSE_COUNT = 3

internal const val FALLBACK_PULSE_HALF_MS = 350

internal suspend fun runFallbackPulse(current: Int, baseline: Int, alpha: PulseAlpha) {
    if (alpha.value != 0f) alpha.snapTo(0f)
    if (!shouldPulse(current, baseline)) return
    repeat(FALLBACK_PULSE_COUNT) {
        alpha.animateTo(1f)
        alpha.animateTo(0f)
    }
}

internal fun androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>.asPulseAlpha(): PulseAlpha {
    val anim = this
    return object : PulseAlpha {
        override val value: Float get() = anim.value
        override suspend fun animateTo(target: Float) {
            anim.animateTo(target, animationSpec = androidx.compose.animation.core.tween(FALLBACK_PULSE_HALF_MS))
        }
        override suspend fun snapTo(target: Float) {
            anim.snapTo(target)
        }
    }
}
