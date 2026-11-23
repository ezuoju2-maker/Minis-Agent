package com.openminis.app.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import io.codeconcept.realtimecutvadlibrary.VADCallback
import io.codeconcept.realtimecutvadlibrary.VADWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

enum class SegmentEndReason {
    SILENCE_DETECTED,

    MAX_LENGTH_REACHED,

    MANUAL_FLUSH,
}

interface VoiceActivityListener {
    fun onVoiceStart() {}

    fun onVoiceEnd(wav: ByteArray, reason: SegmentEndReason, spokenSeconds: Float)

    fun onLevel(level: Float) {}

    fun onCaptureError(message: String) {}

    fun onMicInUse(detail: String) { onCaptureError(detail) }

    fun onSessionLimit(limit: SessionLimit) {}
}

enum class SessionLimit {
    IDLE,

    BACKGROUNDED,

    MAX_DURATION,
}

class VoiceActivityDetector(
    private val context: Context,
    private val listener: VoiceActivityListener,
) {

    companion object {
        private const val TAG = "VAD"

        private const val SILENCE_CHECK_FIRST_MS = 400L
        private const val SILENCE_CHECK_INTERVAL_MS = 1_000L

        const val SAMPLE_RATE = 48_000

        private const val START_PROBABILITY = 0.5f
        private const val END_PROBABILITY = 0.5f
        private const val START_TRUE_RATIO = 0.5f

        private const val END_FALSE_RATIO = 0.95f

        private const val START_FRAMES = 10

        private const val END_FRAMES = 156

        private const val GATE_RATIO = 2.5f
        private const val AGC_TARGET_RMS = 0.12f
        private const val MAX_GAIN = 6.0f
        private const val GAIN_SMOOTHING = 0.3f
        private const val INITIAL_NOISE_FLOOR = 0.003f
        private const val NOISE_FLOOR_MIN = 0.0005f
        private const val NOISE_FLOOR_MAX = 0.02f
        private const val NOISE_ADAPT_DOWN = 0.05f
        private const val NOISE_ADAPT_UP = 0.005f

        private const val FRAME_SAMPLES = 512

        private const val LEVEL_GAIN = 14f
        private const val LEVEL_SCALE = 0.95f
        private const val LEVEL_FLOOR = 0.04f

        internal const val WAV_SAMPLE_RATE = 16_000
        internal const val WAV_HEADER_BYTES = 44

        private const val START_BACKFILL_SECONDS = 1.5f

        private const val PREROLL_SECONDS = 1.0f

        private const val TAIL_SECONDS = 1.0f
        private val TAIL_FRAMES =
            VadCaptureWindow.tailFrames(SAMPLE_RATE, FRAME_SAMPLES, TAIL_SECONDS)

        const val IDLE_TIMEOUT_MS = 30_000L

        const val BACKGROUND_TIMEOUT_MS = 15_000L

        const val MAX_TOTAL_RECORDING_MS = 300_000L
    }

    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    @Volatile
    var isSpeaking: Boolean = false
        private set

    val isRunning: Boolean get() = running.get()

    @Volatile
    var maxSegmentSeconds: Int = 59

    private val vad = ReentrantSafeRelease<VADWrapper> { it.release() }

    @Volatile
    var rawAudioSink: ((ByteArray, Int) -> Unit)? = null

    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private var smoothedGain = 1.0f
    private var noiseFloorRms = INITIAL_NOISE_FLOOR

    private var segmentSamples = 0L

    private val backfill = ArrayDeque<FloatArray>()
    private var backfillSamples = 0
    private val backfillCapacity = (SAMPLE_RATE * START_BACKFILL_SECONDS).toInt()

    private val openSegment = java.io.ByteArrayOutputStream()
    private val openLock = Any()

    private val preroll = ArrayDeque<ByteArray>()
    private var prerollBytes = 0
    private val prerollCapacityBytes =
        VadCaptureWindow.prerollCapacityBytes(SAMPLE_RATE, PREROLL_SECONDS)

    private var tailFramesRemaining = 0

    @Volatile private var captureStartedAtMs = 0L
    @Volatile private var lastSpeechAtMs = 0L

    @Volatile
    var isBackgrounded: Boolean = false
        set(value) {
            field = value
            backgroundedAtMs = if (value) System.currentTimeMillis() else 0L
        }
    @Volatile private var backgroundedAtMs = 0L

    @Suppress("MissingPermission")
    fun start(): String? {
        if (running.getAndSet(true)) return null
        cancelled.set(false)
        smoothedGain = 1.0f
        noiseFloorRms = INITIAL_NOISE_FLOOR
        segmentSamples = 0
        isSpeaking = false

        val wrapper = try {
            VADWrapper(context).also {
                it.setVADModel(VADWrapper.SileroModelVersion.V5)
                it.setVADSampleRate(VADWrapper.SampleRate.SAMPLERATE_48)
                applyThresholds(it)
                it.setVADCallback(vadCallback)
            }
        } catch (t: Throwable) {
            running.set(false)
            Log.e(TAG, "VAD init failed", t)
            return "Voice detection unavailable: ${t.javaClass.simpleName}"
        }
        vad.attach(wrapper)

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            stopInternal()
            return "AudioRecord unsupported buffer size."
        }

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf * 4, FRAME_SAMPLES * 2 * 8),
            )
        } catch (t: Throwable) {
            stopInternal()
            return "AudioRecord init failed: ${t.message}"
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            stopInternal()
            return "AudioRecord not initialized."
        }
        recorder = rec

        captureJob = scope.launch { captureLoop(rec) }
        return null
    }

    private fun applyThresholds(w: VADWrapper) {
        w.setVADThreshold(
            START_PROBABILITY,
            END_PROBABILITY,
            START_TRUE_RATIO,
            END_FALSE_RATIO,
            START_FRAMES,
            END_FRAMES,
        )
    }

    private fun captureLoop(rec: AudioRecord) {
        val shorts = ShortArray(FRAME_SAMPLES)
        val floats = FloatArray(FRAME_SAMPLES)
        val sinkBytes = ByteArray(FRAME_SAMPLES * 2)
        try {
            rec.startRecording()
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
            val detail = "Microphone unavailable: ${t.message}"
            if (MicInUse.captureFailure(context, rec.audioSessionId) == RecognitionError.MIC_IN_USE) {
                listener.onMicInUse(detail)
            } else {
                listener.onCaptureError(detail)
            }
            stopInternal()
            return
        }
        var nextSilenceCheckAtMs = System.currentTimeMillis() + SILENCE_CHECK_FIRST_MS

        val maxSamples = SAMPLE_RATE.toLong() * maxSegmentSeconds
        captureStartedAtMs = System.currentTimeMillis()
        lastSpeechAtMs = captureStartedAtMs
        while (running.get() && !cancelled.get()) {
            val now = System.currentTimeMillis()
            if (now - captureStartedAtMs >= MAX_TOTAL_RECORDING_MS) {
                Log.i(TAG, "session hit ${MAX_TOTAL_RECORDING_MS / 1000}s cap — stopping")
                listener.onSessionLimit(SessionLimit.MAX_DURATION)
                break
            }
            if (!isSpeaking && now - lastSpeechAtMs >= IDLE_TIMEOUT_MS) {
                Log.i(TAG, "no speech for ${IDLE_TIMEOUT_MS / 1000}s — stopping")
                listener.onSessionLimit(SessionLimit.IDLE)
                break
            }
            if (isBackgrounded && backgroundedAtMs > 0 &&
                now - backgroundedAtMs >= BACKGROUND_TIMEOUT_MS
            ) {
                Log.i(TAG, "backgrounded ${BACKGROUND_TIMEOUT_MS / 1000}s — stopping")
                listener.onSessionLimit(SessionLimit.BACKGROUNDED)
                break
            }

            if (now >= nextSilenceCheckAtMs) {
                nextSilenceCheckAtMs = now + SILENCE_CHECK_INTERVAL_MS
                if (MicInUse.isSilenced(context, rec.audioSessionId)) {
                    Log.w(TAG, "recording silenced by the system — another app holds the microphone")
                    listener.onMicInUse("Recording silenced by the system (another app holds the microphone)")
                    break
                }
            }

            val n = try {
                rec.read(shorts, 0, FRAME_SAMPLES)
            } catch (t: Throwable) {
                Log.e(TAG, "read failed", t)
                listener.onCaptureError("Microphone read failed")
                break
            }
            if (n <= 0) continue

            var staged = 0
            for (i in 0 until n) {
                val v = shorts[i].toInt()
                sinkBytes[staged++] = (v and 0xFF).toByte()
                sinkBytes[staged++] = ((v shr 8) and 0xFF).toByte()
            }
            rawAudioSink?.let { sink -> runCatching { sink(sinkBytes, staged) } }
            if (staged > 0) {
                synchronized(openLock) {
                    if (isSpeaking || tailFramesRemaining > 0) {
                        openSegment.write(sinkBytes, 0, staged)
                        if (!isSpeaking) tailFramesRemaining--
                    } else {
                        preroll.addLast(sinkBytes.copyOf(staged))
                        prerollBytes += staged
                        while (prerollBytes > prerollCapacityBytes && preroll.isNotEmpty()) {
                            prerollBytes -= preroll.removeFirst().size
                        }
                    }
                }
            }

            var sumSq = 0.0f
            for (i in 0 until n) {
                val f = shorts[i] / 32768.0f
                floats[i] = f
                sumSq += f * f
            }
            val rms = if (n > 0) sqrt(sumSq / n) else 0f
            applyAgc(floats, n, rms)

            listener.onLevel(levelFor(floats, n))

            val frame = if (n == FRAME_SAMPLES) floats else floats.copyOf(n)

            backfill.addLast(frame)
            backfillSamples += n
            while (backfillSamples > backfillCapacity && backfill.isNotEmpty()) {
                backfillSamples -= backfill.removeFirst().size
            }

            var failure: Throwable? = null
            val alive = vad.call { w ->
                try { w.processAudio(frame) } catch (t: Throwable) { failure = t }
            }
            if (!alive) break
            val err = failure
            if (err != null) {
                Log.e(TAG, "processAudio failed", err)
                listener.onCaptureError("Voice detection failed")
                break
            }
            if (isSpeaking) lastSpeechAtMs = System.currentTimeMillis()

            if (isSpeaking) {
                segmentSamples += n
                if (segmentSamples >= maxSamples && pendingReason == null) {
                    Log.w(TAG, "segment exceeded ${maxSegmentSeconds}s — will report MAX_LENGTH_REACHED")
                    pendingReason = SegmentEndReason.MAX_LENGTH_REACHED
                }
            }
        }

        try { rec.stop() } catch (_: Throwable) {}
        rec.release()
    }

    private fun applyAgc(buf: FloatArray, n: Int, rms: Float) {
        val gate = noiseFloorRms * GATE_RATIO
        val targetGain: Float
        if (rms < gate) {
            targetGain = 1.0f
            if (!isSpeaking) {
                val rate = if (rms < noiseFloorRms) NOISE_ADAPT_DOWN else NOISE_ADAPT_UP
                noiseFloorRms += (rms - noiseFloorRms) * rate
                noiseFloorRms = max(NOISE_FLOOR_MIN, min(NOISE_FLOOR_MAX, noiseFloorRms))
            }
        } else {
            targetGain = min(MAX_GAIN, max(1.0f, AGC_TARGET_RMS / max(rms, 0.0001f)))
        }
        smoothedGain += (targetGain - smoothedGain) * GAIN_SMOOTHING
        if (smoothedGain > 1.01f) {
            for (i in 0 until n) buf[i] = tanh(buf[i] * smoothedGain)
        }
    }

    private fun levelFor(buf: FloatArray, n: Int): Float {
        var sumSq = 0.0f
        for (i in 0 until n) sumSq += buf[i] * buf[i]
        val rms = if (n > 0) sqrt(sumSq / n) else 0f
        val level = sqrt(min(1.0f, rms * LEVEL_GAIN)) * LEVEL_SCALE
        return min(1.0f, max(LEVEL_FLOOR, level))
    }

    @Volatile
    private var pendingReason: SegmentEndReason? = null

    private val vadCallback = object : VADCallback {
        override fun onVoiceStart() {
            segmentSamples = 0
            synchronized(openLock) {
                openSegment.reset()
                for (chunk in preroll) openSegment.write(chunk, 0, chunk.size)
                preroll.clear()
                prerollBytes = 0
                tailFramesRemaining = 0
                isSpeaking = true
            }
            listener.onVoiceStart()
        }

        override fun onVoiceEnd(wav: ByteArray?) {
            isSpeaking = false
            segmentSamples = 0
            synchronized(openLock) {
                openSegment.reset()
                preroll.clear()
                prerollBytes = 0
                tailFramesRemaining = TAIL_FRAMES
            }
            lastSpeechAtMs = System.currentTimeMillis()
            val reason = pendingReason ?: SegmentEndReason.SILENCE_DETECTED
            pendingReason = null
            if (wav == null || wav.isEmpty()) return
            val spoken = max(0, wav.size - WAV_HEADER_BYTES) /
                (WAV_SAMPLE_RATE * 2).toFloat()
            listener.onVoiceEnd(wav, reason, spoken)
        }

        override fun onVoiceDidContinue(pcm: ByteArray?) {
        }
    }

    fun flush(): ByteArray? {
        val pcm = synchronized(openLock) {
            val bytes = openSegment.toByteArray()
            openSegment.reset()
            bytes
        }
        if (pcm.isEmpty()) return null
        isSpeaking = false
        segmentSamples = 0
        return com.openminis.app.provider.voice.VoiceProvider.wrapPcm16InWav(
            downsampleTo16k(pcm),
            WAV_SAMPLE_RATE,
        )
    }

    private fun downsampleTo16k(pcm48: ByteArray): ByteArray {
        val ratio = SAMPLE_RATE / WAV_SAMPLE_RATE
        val inSamples = pcm48.size / 2
        val outSamples = inSamples / ratio
        val out = ByteArray(outSamples * 2)
        var si = 0
        for (o in 0 until outSamples) {
            var acc = 0
            for (k in 0 until ratio) {
                val b = si * 2
                acc += ((pcm48[b + 1].toInt() shl 8) or (pcm48[b].toInt() and 0xFF)).toShort().toInt()
                si++
            }
            val v = (acc / ratio).coerceIn(-32768, 32767)
            out[o * 2] = (v and 0xFF).toByte()
            out[o * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun stop() {
        stopInternal()
    }

    fun cancel() {
        cancelled.set(true)
        stopInternal()
    }

    private fun stopInternal() {
        running.set(false)
        isSpeaking = false
        synchronized(openLock) {
            openSegment.reset()
            preroll.clear()
            prerollBytes = 0
            tailFramesRemaining = 0
        }
        backfill.clear()
        backfillSamples = 0
        captureStartedAtMs = 0
        lastSpeechAtMs = 0
        backgroundedAtMs = 0
        captureJob?.let { runCatching { it.cancel() } }
        captureJob = null

        recorder?.let { rec ->
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        recorder = null
        vad.tearDown()
    }
}
