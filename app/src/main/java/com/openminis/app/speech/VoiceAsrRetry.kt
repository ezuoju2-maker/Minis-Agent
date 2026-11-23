package com.openminis.app.speech


object VoiceAsrWatchdog {
    const val MIN_SECONDS = 8.0
    const val MAX_SECONDS = 120.0

    fun seconds(audioSeconds: Double): Double {
        val audio = if (audioSeconds.isNaN() || audioSeconds < 0) 0.0 else audioSeconds
        return (audio + MIN_SECONDS).coerceIn(MIN_SECONDS, MAX_SECONDS)
    }

    fun pcmSeconds(pcmBytes: Int, sampleRate: Int = 16_000): Double =
        if (sampleRate <= 0 || pcmBytes <= 0) 0.0 else pcmBytes / 2.0 / sampleRate

    fun wavSeconds(wav: ByteArray): Double {
        if (wav.size <= 44) return 0.0
        val rate = (wav[28].toLong() and 0xFF) or
            ((wav[29].toLong() and 0xFF) shl 8) or
            ((wav[30].toLong() and 0xFF) shl 16) or
            ((wav[31].toLong() and 0xFF) shl 24)
        if (rate <= 0) return 0.0
        return (wav.size - 44).toDouble() / rate
    }
}

object VoiceAsrFailurePolicy {
    const val MIN_AUDIO_SECONDS = 0.3

    fun shouldPromptRetry(error: RecognitionError, audioSeconds: Double): Boolean {
        if (audioSeconds <= MIN_AUDIO_SECONDS) return false
        return error != RecognitionError.NO_MATCH && error != RecognitionError.MIC_IN_USE
    }
}

class RetryPromptQueue<T> private constructor(val items: List<T>) {
    constructor() : this(emptyList())

    val current: T? get() = items.firstOrNull()
    val size: Int get() = items.size
    fun isEmpty(): Boolean = items.isEmpty()

    fun enqueue(item: T): RetryPromptQueue<T> = RetryPromptQueue(items + item)

    fun takeForRetry(): Pair<T?, RetryPromptQueue<T>> =
        if (items.isEmpty()) null to this else items.first() to RetryPromptQueue(items.drop(1))

    fun discard(): RetryPromptQueue<T> =
        if (items.isEmpty()) this else RetryPromptQueue(items.drop(1))
}

class VoiceFinishTracker(
    private val timeoutMs: Long = TIMEOUT_MS,
    private val settleMs: Long = 300,
    private val requiredIdleTicks: Int = 2,
) {
    enum class Step { WAIT, SEND, FAILED, TIMED_OUT }

    private var idleTicks = 0

    fun tick(idle: Boolean, failedSinceStart: Boolean, elapsedMs: Long): Step {
        idleTicks = if (idle && elapsedMs >= settleMs) idleTicks + 1 else 0
        return when {
            failedSinceStart -> Step.FAILED
            idleTicks >= requiredIdleTicks -> Step.SEND
            elapsedMs >= timeoutMs -> Step.TIMED_OUT
            else -> Step.WAIT
        }
    }

    companion object {
        const val TIMEOUT_MS = 60_000L
    }
}
