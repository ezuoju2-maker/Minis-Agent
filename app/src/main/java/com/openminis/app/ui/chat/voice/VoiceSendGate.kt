package com.openminis.app.ui.chat.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.speech.RecognitionState
import com.openminis.app.speech.RetryPromptQueue
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.speech.VoiceFinishTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object VoiceSendGate {

    private const val TAG = "VoiceSend"

    class FailedUtterance(
        val id: Long,
        val audio: SpeechRecognitionManager.FailedAudio,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val nextId = AtomicLong(1)

    private val failureCount = AtomicInteger(0)

    var queue by mutableStateOf(RetryPromptQueue<FailedUtterance>())
        private set

    var isFinishingForSend by mutableStateOf(false)
        private set

    var generation by mutableIntStateOf(0)
        private set

    private var attempts = 0
    private var pendingAttempt: Int? = null

    fun abandonFinishForSend(reason: String) {
        if (pendingAttempt == null) return
        AppLogger.info(TAG, "✋ [voice-send] pending send abandoned ($reason) — not sending")
        pendingAttempt = null
        isFinishingForSend = false
    }

    @Volatile
    var transcript: String = ""

    fun bumpGeneration() {
        generation += 1
    }

    fun owesText(): Boolean =
        VoiceModePrefs.isVoiceActive &&
            (isFinishingForSend || SpeechRecognitionManager.state.value != RecognitionState.IDLE)


    fun recordFailure(failed: SpeechRecognitionManager.FailedAudio, captureGeneration: Int) {
        if (captureGeneration != generation) {
            AppLogger.info(
                TAG,
                "[voice-failure] ${"%.1f".format(failed.seconds)}s failed after the composition " +
                    "was sent/cleared — not prompting (${failed.error})",
            )
            return
        }
        failureCount.incrementAndGet()
        AppLogger.info(TAG, "⚠️ [voice-failure] ${"%.1f".format(failed.seconds)}s kept for retry (${failed.error})")
        val item = FailedUtterance(nextId.getAndIncrement(), failed)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            queue = queue.enqueue(item)
        } else {
            mainHandler.post { queue = queue.enqueue(item) }
        }
    }

    fun takeForRetry(): FailedUtterance? {
        val (item, rest) = queue.takeForRetry()
        queue = rest
        if (item != null) {
            AppLogger.info(TAG, "🔁 [voice-failure] retry requested (${"%.1f".format(item.audio.seconds)}s)")
        }
        return item
    }

    fun requeue(item: FailedUtterance) {
        queue = queue.enqueue(item)
    }

    fun discardCurrent() {
        queue.current?.let {
            AppLogger.info(TAG, "🗑️ [voice-failure] discarded by user (${"%.1f".format(it.audio.seconds)}s)")
        }
        queue = queue.discard()
    }


    fun deferSendIfOwed(scope: CoroutineScope, context: Context, onReady: () -> Unit): Boolean {
        if (!owesText()) return false
        finishCaptureForSend(scope, context, onReady)
        return true
    }

    private fun finishCaptureForSend(scope: CoroutineScope, context: Context, onReady: () -> Unit) {
        if (isFinishingForSend) {
            AppLogger.info(TAG, "[voice-send] finish already pending — ignoring repeated send")
            return
        }
        isFinishingForSend = true
        val attempt = ++attempts
        pendingAttempt = attempt
        val failuresBefore = failureCount.get()
        val state = SpeechRecognitionManager.state.value
        AppLogger.info(TAG, "🎙️ [voice-send] send while state=$state — finishing recognition first")
        if (state == RecognitionState.RECORDING || state == RecognitionState.STARTING) {
            SpeechRecognitionManager.stopRecording()
        }
        val app = context.applicationContext
        scope.launch {
            val tracker = VoiceFinishTracker()
            val started = SystemClock.elapsedRealtime()
            var step: VoiceFinishTracker.Step
            try {
                do {
                    delay(100)
                    if (pendingAttempt != attempt) return@launch
                    step = tracker.tick(
                        idle = SpeechRecognitionManager.state.value == RecognitionState.IDLE,
                        failedSinceStart = failureCount.get() > failuresBefore,
                        elapsedMs = SystemClock.elapsedRealtime() - started,
                    )
                } while (step == VoiceFinishTracker.Step.WAIT)
            } finally {
                if (pendingAttempt == attempt) {
                    pendingAttempt = null
                    isFinishingForSend = false
                }
            }
            val elapsedS = (SystemClock.elapsedRealtime() - started) / 1000.0
            when (step) {
                VoiceFinishTracker.Step.SEND -> {
                    AppLogger.info(TAG, "✅ [voice-send] recognition finished in ${"%.1f".format(elapsedS)}s — sending")
                    onReady()
                }
                VoiceFinishTracker.Step.FAILED ->
                    AppLogger.info(TAG, "⚠️ [voice-send] a transcription failed while finishing — send held for the retry prompt")
                VoiceFinishTracker.Step.TIMED_OUT -> {
                    AppLogger.info(TAG, "⏳ [voice-send] recognition still running after ${elapsedS.toInt()}s — send abandoned")
                    android.widget.Toast.makeText(
                        app,
                        app.getString(R.string.voice_send_still_transcribing),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                VoiceFinishTracker.Step.WAIT -> Unit
            }
        }
    }
}
