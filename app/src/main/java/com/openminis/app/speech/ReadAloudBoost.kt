package com.openminis.app.speech

import android.media.audiofx.LoudnessEnhancer
import com.openminis.app.logging.AppLogger
import kotlin.math.log10
import kotlin.math.roundToInt

internal class ReadAloudBoost(private val sessionId: Int) {
    private var enhancer: LoudnessEnhancer? = null
    private var failed = false
    private var lastGainMb = 0

    fun apply(volume: Float) {
        val gainMb = boostMillibels(volume)
        if (gainMb == lastGainMb && (gainMb == 0 || enhancer != null)) return
        lastGainMb = gainMb
        if (gainMb <= 0) {
            enhancer?.runCatching { enabled = false }
            return
        }
        if (failed || sessionId == 0) return
        val fx = enhancer ?: runCatching { LoudnessEnhancer(sessionId) }
            .onFailure {
                failed = true
                AppLogger.error(TAG, "LoudnessEnhancer unavailable on session $sessionId, read-aloud stays at 100%: $it")
            }
            .getOrNull()
            ?.also { enhancer = it }
            ?: return
        fx.runCatching {
            setTargetGain(gainMb)
            enabled = true
        }.onFailure { AppLogger.error(TAG, "LoudnessEnhancer gain $gainMb mB rejected: $it") }
    }

    fun release() {
        enhancer?.runCatching { release() }
        enhancer = null
        lastGainMb = 0
    }

    companion object {
        private const val TAG = "ReadAloud"

        fun linearGain(volume: Float): Float = volume.coerceIn(0f, 1f)

        fun boostMillibels(volume: Float): Int =
            if (volume <= 1f || volume.isNaN()) 0 else (2000.0 * log10(volume.toDouble())).roundToInt()
    }
}
