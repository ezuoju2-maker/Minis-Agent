package com.openminis.app.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.openminis.app.MinisApp
import com.openminis.app.provider.voice.VoiceInputRequest
import com.openminis.app.provider.voice.VoiceProvider
import com.openminis.app.provider.voice.VoiceProviderException
import com.openminis.app.provider.voice.VoiceProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class ProviderSpeechRecognitionEngine(private val appContext: Context) : SpeechRecognitionEngine {

    companion object {
        private const val TAG = "ProviderASR"

        const val ASR_STALL_THRESHOLD_MS = 5_000L

        private const val SYSTEM_NEXT = "__system_asr__"
        private const val SAMPLE_RATE = 16_000
        private const val MAX_RECORD_SECONDS = 60

        private const val CLOUD_MAX_SEGMENT_SECONDS = 300

        private const val MIN_SEGMENT_SECONDS = 2.0f

        private const val TOO_SHORT_TOAST_FLOOR = 0.3f

        private const val HOLD_FLUSH_MS = 5_000L
    }

    override val id: String = "provider"
    override val displayName: String = "Provider transcription"
    override val supportsPartialResults: Boolean = false

    override val isAvailable: Boolean
        get() = !degraded && repository()?.resolveVoiceInputEntry() != null

    override val supportedLocales: List<Locale> = emptyList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var transcribeJob: Job? = null
    private val pendingSegments = mutableListOf<ByteArray>()
    private var holdFlushJob: Job? = null
    private val recording = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private var degraded = false

    private val takeGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val systemReplayGeneration = java.util.concurrent.atomic.AtomicInteger(-1)

    @Volatile
    private var detector: VoiceActivityDetector? = null

    private class VadSession(
        val locale: Locale,
        val repo: com.openminis.app.data.repository.ProviderRepository,
        val candidates: List<Pair<com.openminis.app.data.model.ProviderInstance, com.openminis.app.data.model.ModelEntry>>,
        val listener: SpeechRecognitionEngine.Listener,
    )

    @Volatile
    private var vadSession: VadSession? = null

    @Volatile
    var useVad: Boolean = true

    @Volatile
    private var stickyEntryId: String? = null

    val asrAttempts = SkippableAsrAttempts(ASR_STALL_THRESHOLD_MS).also { a ->
        a.log = { msg -> Log.i(TAG, msg); VoicePipelineLog.event("engine.asr.stall", "msg" to msg.take(80)) }
    }

    @Volatile
    private var systemAsrPinned = false

    @Volatile
    private var currentCandidates: List<Pair<com.openminis.app.data.model.ProviderInstance, com.openminis.app.data.model.ModelEntry>> = emptyList()

    private fun repository() =
        (appContext.applicationContext as? MinisApp)
            ?.takeIf { it.subsystemsReady() }
            ?.providerRepository

    override fun markDegraded() {
        degraded = true
    }

    override fun clearDegraded() {
        degraded = false
    }

    @SuppressLint("MissingPermission")
    override fun start(locale: Locale, listener: SpeechRecognitionEngine.Listener) {
        val repo = repository()
        val candidates = repo?.resolveVoiceInputCandidates(
            loadBalanceSeed = kotlin.random.Random.nextInt(Int.MAX_VALUE),
        ).orEmpty()
        systemAsrPinned = false
        currentCandidates = candidates
        if (repo == null || candidates.isEmpty()) {
            listener.onError(
                RecognitionError.OEM_NO_SERVICE,
                "No provider voice-input model configured. Add an ASR model to the Voice Input group.",
            )
            return
        }
        if (recording.getAndSet(true)) {
            listener.onError(RecognitionError.RECOGNIZER_BUSY, "A capture is already in flight.")
            return
        }
        supersedeTake()
        cancelled.set(false)
        pendingSegments.clear()
        holdFlushJob?.cancel()
        holdFlushJob = null

        if (useVad) {
            startVadCapture(locale, repo, candidates, listener)
            return
        }

        captureJob = scope.launch {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                recording.set(false)
                listener.onError(RecognitionError.AUDIO_ERROR, "AudioRecord unsupported buffer size.")
                return@launch
            }
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 4,
                )
            } catch (e: Exception) {
                recording.set(false)
                listener.onError(MicInUse.captureFailure(appContext), "AudioRecord init failed: ${e.message}")
                return@launch
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                recording.set(false)
                listener.onError(MicInUse.captureFailure(appContext), "AudioRecord not initialized.")
                return@launch
            }

            val pcm = ByteArrayOutputStream()
            val buf = ByteArray(minBuf)
            val maxBytes = SAMPLE_RATE * 2 * MAX_RECORD_SECONDS
            try {
                recorder.startRecording()
                listener.onReadyForSpeech()
                var nextSilenceCheckAtMs = System.currentTimeMillis() + 400L
                while (recording.get() && pcm.size() < maxBytes) {
                    val now = System.currentTimeMillis()
                    if (now >= nextSilenceCheckAtMs) {
                        nextSilenceCheckAtMs = now + 1_000L
                        if (MicInUse.isSilenced(appContext, recorder.audioSessionId)) {
                            recording.set(false)
                            listener.onError(RecognitionError.MIC_IN_USE, "Recording silenced by the system")
                            return@launch
                        }
                    }
                    val n = recorder.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    pcm.write(buf, 0, n)
                    listener.onRmsDb(rmsDb(buf, n))
                }
            } catch (e: Exception) {
                Log.e(TAG, "capture failed: ${e.message}", e)
                recording.set(false)
                listener.onError(MicInUse.captureFailure(appContext, recorder.audioSessionId), e.message)
                return@launch
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
            }

            if (cancelled.get()) {
                VoicePipelineLog.event("engine.capture.cancelled")
                return@launch
            }
            val audio = pcm.toByteArray()
            VoicePipelineLog.event("engine.capture.end", "bytes" to audio.size)
            if (audio.isEmpty()) {
                listener.onError(RecognitionError.NO_MATCH, "No audio captured.")
                return@launch
            }

            transcribeJob = scope.launch {
                VoicePipelineLog.event("engine.transcribe.begin", "candidates" to candidates.size)
                val wav = VoiceProvider.wrapPcm16InWav(audio, SAMPLE_RATE)
                transcribeAndDeliver(wav, locale, repo, candidates, listener, logEvents = true)
            }
        }
    }


    private fun startVadCapture(
        locale: Locale,
        repo: com.openminis.app.data.repository.ProviderRepository,
        candidates: List<Pair<com.openminis.app.data.model.ProviderInstance, com.openminis.app.data.model.ModelEntry>>,
        listener: SpeechRecognitionEngine.Listener,
    ) {
        val det = VoiceActivityDetector(
            appContext,
            object : VoiceActivityListener {
                override fun onVoiceStart() {
                    Log.i(TAG, "[vad] speech start")
                }

                override fun onLevel(level: Float) {
                    listener.onRmsDb(level * 12f)
                }

                override fun onVoiceEnd(wav: ByteArray, reason: SegmentEndReason, spokenSeconds: Float) {
                    if (cancelled.get()) return

                    pendingSegments.add(wav)
                    val heldSeconds = WavSegmentMerger.totalSeconds(pendingSegments)

                    if (reason == SegmentEndReason.SILENCE_DETECTED &&
                        heldSeconds < MIN_SEGMENT_SECONDS
                    ) {
                        Log.i(
                            TAG,
                            "[vad] holding ${"%.2f".format(spokenSeconds)}s segment " +
                                "(total ${"%.2f".format(heldSeconds)}s < ${MIN_SEGMENT_SECONDS}s)",
                        )
                        holdFlushJob?.cancel()
                        holdFlushJob = scope.launch {
                            kotlinx.coroutines.delay(HOLD_FLUSH_MS)
                            if (cancelled.get()) return@launch
                            val held = WavSegmentMerger.totalSeconds(pendingSegments)
                            Log.i(TAG, "[vad] hold expired at ${"%.2f".format(held)}s — settling")
                            stopVad()
                            vadSession = null
                            listener.onError(
                                RecognitionError.NO_MATCH,
                                if (held > TOO_SHORT_TOAST_FLOOR) "Too short — hold the mic and speak." else null,
                            )
                            pendingSegments.clear()
                        }
                        return
                    }

                    holdFlushJob?.cancel()
                    holdFlushJob = null
                    val merged = WavSegmentMerger.merge(pendingSegments) ?: wav
                    if (pendingSegments.size > 1) {
                        Log.i(
                            TAG,
                            "[vad] merged ${pendingSegments.size} segments → " +
                                "${"%.2f".format(heldSeconds)}s",
                        )
                    }
                    pendingSegments.clear()

                    if (reason == SegmentEndReason.SILENCE_DETECTED) {
                        vadSession = null
                        stopVad()
                    }
                    transcribeJob = scope.launch {
                        transcribeSegment(merged, locale, repo, candidates, listener)
                    }
                }

                override fun onSessionLimit(limit: SessionLimit) {
                    Log.i(TAG, "[vad] session limit $limit — stopping")
                    VoicePipelineLog.event("engine.sessionLimit", "limit" to limit.name)
                    stopVad()
                    flushVadOnManualStop()
                }

                override fun onCaptureError(message: String) {
                    stopVad()
                    recording.set(false)
                    listener.onError(RecognitionError.AUDIO_ERROR, message)
                }

                override fun onMicInUse(detail: String) {
                    Log.w(TAG, "[vad] microphone in use by another app: $detail")
                    stopVad()
                    recording.set(false)
                    listener.onError(RecognitionError.MIC_IN_USE, detail)
                }
            },
        ).also {
            it.maxSegmentSeconds = CLOUD_MAX_SEGMENT_SECONDS
        }

        val err = det.start()
        if (err != null) {
            recording.set(false)
            listener.onError(MicInUse.captureFailure(appContext), err)
            return
        }
        detector = det
        vadSession = VadSession(locale, repo, candidates, listener)
        listener.onReadyForSpeech()
    }

    private suspend fun transcribeSegment(
        wav: ByteArray,
        locale: Locale,
        repo: com.openminis.app.data.repository.ProviderRepository,
        candidates: List<Pair<com.openminis.app.data.model.ProviderInstance, com.openminis.app.data.model.ModelEntry>>,
        listener: SpeechRecognitionEngine.Listener,
    ) = transcribeAndDeliver(wav, locale, repo, candidates, listener, logEvents = false)

    private suspend fun transcribeAndDeliver(
        wav: ByteArray,
        locale: Locale,
        repo: com.openminis.app.data.repository.ProviderRepository,
        candidates: List<Pair<com.openminis.app.data.model.ProviderInstance, com.openminis.app.data.model.ModelEntry>>,
        listener: SpeechRecognitionEngine.Listener,
        logEvents: Boolean,
    ) {
        if (systemAsrPinned) {
            Log.i(TAG, "ASR: System recognition pinned for this take after a skip")
            transcribeWithSystem(wav, locale, listener)
            return
        }
        if (logEvents) VoicePipelineLog.event("engine.transcribe.begin", "candidates" to candidates.size)
        val ordered = stickyEntryId
            ?.let { sticky -> candidates.indexOfFirst { it.second.id == sticky } }
            ?.takeIf { it > 0 }
            ?.let { i -> candidates.drop(i) + candidates.take(i) }
            ?: candidates
        val systemAvailable = systemEngine() != null
        var lastError: Exception? = null
        var userSkipped = false
        for ((i, pair) in ordered.withIndex()) {
            val (instance, entry) = pair
            if (cancelled.get()) return
            val provider = VoiceProviderFactory.make(instance, repo.loadApiKey(instance.id))
            if (provider == null) {
                Log.w(TAG, "candidate ${instance.label} cannot serve voice input — skipping")
                if (logEvents) VoicePipelineLog.event("engine.transcribe.skip", "model" to entry.baseModel.id)
                continue
            }
            val nextEntryId: String? = when {
                i + 1 < ordered.size -> ordered[i + 1].second.id
                systemAvailable -> SYSTEM_NEXT
                else -> null
            }
            val tryStartedMs = System.currentTimeMillis()
            if (logEvents) VoicePipelineLog.event("engine.transcribe.try", "model" to entry.baseModel.id)
            try {
                val response = asrAttempts.run(entry.model.displayName, nextEntryId, canSkip = nextEntryId != null) {
                    provider.transcribe(
                        VoiceInputRequest(
                            audioData = wav,
                            model = entry.baseModel.id,
                            language = locale.toLanguageTag(),
                            resolvedModel = entry.model,
                        ),
                    ).text
                }
                if (cancelled.get()) {
                    if (logEvents) VoicePipelineLog.event("engine.transcribe.cancelledAfterResponse")
                    return
                }
                stickyEntryId = entry.id
                val text = response.trim()
                if (logEvents) {
                    VoicePipelineLog.event(
                        "engine.transcribe.ok",
                        "model" to entry.baseModel.id,
                        "ms" to (System.currentTimeMillis() - tryStartedMs),
                        "chars" to text.length,
                    )
                }
                if (text.isEmpty()) {
                    listener.onError(RecognitionError.NO_MATCH, "Empty transcription.")
                } else {
                    listener.onFinal(text)
                }
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SkippableAsrAttempts.Skipped) {
                userSkipped = true
                lastError = e
                Log.i(TAG, "ASR fail-over: ${entry.model.displayName} skipped by the user after " +
                    "${ASR_STALL_THRESHOLD_MS / 1000}s without a result" +
                    if (i + 1 < ordered.size) " — trying next candidate" else " — falling back to System")
                if (logEvents) VoicePipelineLog.event("engine.transcribe.skipped", "model" to entry.baseModel.id)
            } catch (e: Exception) {
                Log.e(TAG, "transcribe failed on ${entry.model.displayName}: ${e.message} — trying next candidate")
                if (logEvents) {
                    VoicePipelineLog.event(
                        "engine.transcribe.fail",
                        "model" to entry.baseModel.id,
                        "ms" to (System.currentTimeMillis() - tryStartedMs),
                        "err" to VoicePipelineLog.errorKind(e),
                    )
                }
                lastError = e
            }
        }
        if (cancelled.get()) return
        if (userSkipped && systemAvailable) {
            systemAsrPinned = true
            Log.i(TAG, "ASR fail-over: chain exhausted after a user skip — using System recognition for this take")
            transcribeWithSystem(wav, locale, listener)
            return
        }
        if (logEvents) VoicePipelineLog.event("engine.transcribe.exhausted", "err" to VoicePipelineLog.errorKind(lastError))
        val e = lastError
        val kind = when {
            e is VoiceProviderException.Auth -> RecognitionError.PERMISSION_DENIED
            e is java.io.IOException -> RecognitionError.NETWORK
            else -> RecognitionError.UNKNOWN
        }
        listener.onRetainedAudio(wav)
        listener.onError(kind, e?.message ?: "No usable voice-input model could transcribe this.")
    }

    private fun transcribeWithSystem(wav: ByteArray, locale: Locale, listener: SpeechRecognitionEngine.Listener) {
        val system = systemEngine()
        if (system == null) {
            listener.onRetainedAudio(wav)
            listener.onError(RecognitionError.OEM_NO_SERVICE, "System speech recognition is not available.")
            return
        }
        val gen = takeGeneration.get()
        systemReplayGeneration.set(gen)
        system.transcribeRetained(wav, locale, object : SpeechRecognitionEngine.Listener {
            private fun live(what: String): Boolean =
                (takeGeneration.get() == gen).also { if (!it) Log.i(TAG, "ASR: dropped stale System $what (take superseded)") }
            override fun onPartial(text: String) { if (live("partial")) listener.onPartial(text) }
            override fun onFinal(text: String) { if (live("final")) listener.onFinal(text) }
            override fun onError(error: RecognitionError, message: String?) {
                if (live("error")) listener.onError(error, message)
            }
            override fun onRmsDb(rms: Float) { if (takeGeneration.get() == gen) listener.onRmsDb(rms) }
            override fun onReadyForSpeech() { if (takeGeneration.get() == gen) listener.onReadyForSpeech() }
            override fun onRetainedAudio(wav: ByteArray) { if (live("audio")) listener.onRetainedAudio(wav) }
        })
    }

    private fun supersedeTake() {
        val old = takeGeneration.getAndIncrement()
        if (systemReplayGeneration.compareAndSet(old, -1)) {
            Log.i(TAG, "ASR: take superseded — cancelling its System recognition fallback")
            systemEngine()?.cancel()
        }
    }

    private fun systemEngine(): SystemSpeechRecognitionEngine? =
        SpeechRecognitionManager.availableEngines()
            .firstOrNull { it is SystemSpeechRecognitionEngine && it.isAvailable }
            ?.takeIf { android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU }
            as? SystemSpeechRecognitionEngine

    fun skipStalledTranscription(systemName: String, genericName: String): String? {
        val nexts = asrAttempts.skipStalled()
        if (nexts.isEmpty()) return null
        val targets = nexts.filterNotNull()
        val nextCandidate = targets.firstOrNull { it != SYSTEM_NEXT }
            ?.let { id -> currentCandidates.firstOrNull { it.second.id == id } }
        val name = when {
            nextCandidate != null -> {
                stickyEntryId = nextCandidate.second.id
                nextCandidate.second.model.displayName
            }
            targets.contains(SYSTEM_NEXT) -> {
                systemAsrPinned = true
                systemName
            }
            else -> genericName
        }
        Log.i(TAG, "ASR stall: skip → next segments start on $name")
        return name
    }

    override fun transcribeRetained(wav: ByteArray, locale: Locale, listener: SpeechRecognitionEngine.Listener) {
        val repo = repository()
        val candidates = repo?.resolveVoiceInputCandidates(
            loadBalanceSeed = kotlin.random.Random.nextInt(Int.MAX_VALUE),
        ).orEmpty()
        if (repo == null || candidates.isEmpty()) {
            listener.onRetainedAudio(wav)
            listener.onError(
                RecognitionError.OEM_NO_SERVICE,
                "No provider voice-input model configured. Add an ASR model to the Voice Input group.",
            )
            return
        }
        supersedeTake()
        cancelled.set(false)
        currentCandidates = candidates
        systemAsrPinned = false
        VoicePipelineLog.event("engine.retry.begin", "candidates" to candidates.size)
        transcribeJob = scope.launch {
            transcribeSegment(wav, locale, repo, candidates, listener)
        }
    }

    private fun stopVad() {
        detector?.let { runCatching { it.stop() } }
        detector = null
        recording.set(false)
    }

    private fun flushVadOnManualStop() {
        val session = vadSession
        vadSession = null
        holdFlushJob?.cancel()
        holdFlushJob = null
        if (session == null) return
        val held = pendingSegments.toList()
        pendingSegments.clear()
        val merged = if (held.isEmpty()) null else WavSegmentMerger.merge(held) ?: held.firstOrNull()
        val seconds = if (held.isEmpty()) 0f else WavSegmentMerger.totalSeconds(held)
        VoicePipelineLog.event(
            "engine.manualStop.flush",
            "segments" to held.size,
            "seconds" to "%.2f".format(seconds),
        )
        val outcome = VadManualStopOutcome.decide(
            hasSession = true,
            heldSeconds = if (merged == null) 0f else seconds,
            floorSeconds = TOO_SHORT_TOAST_FLOOR,
        )
        if (outcome != VadManualStopOutcome.TRANSCRIBE || merged == null) {
            session.listener.onError(
                RecognitionError.NO_MATCH,
                "Too short — hold the mic and speak.",
            )
            return
        }
        transcribeJob = scope.launch {
            transcribeSegment(merged, session.locale, session.repo, session.candidates, session.listener)
        }
    }

    fun setBackgrounded(backgrounded: Boolean) {
        detector?.isBackgrounded = backgrounded
    }

    override fun stop() {
        recording.set(false)
        val det = detector
        val wasVad = det != null
        val openSegment = det?.let { runCatching { it.flush() }.getOrNull() }
        if (openSegment != null) pendingSegments.add(openSegment)
        det?.let { runCatching { it.stop() } }
        detector = null
        if (wasVad) flushVadOnManualStop()
    }

    override fun cancel() {
        supersedeTake()
        cancelled.set(true)
        recording.set(false)
        transcribeJob?.cancel()
        holdFlushJob?.cancel()
        holdFlushJob = null
        vadSession = null
        pendingSegments.clear()
    }

    private fun rmsDb(buf: ByteArray, len: Int): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < len) {
            val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sum += sample.toDouble() * sample
            count++
            i += 2
        }
        if (count == 0) return 0f
        val rms = sqrt(sum / count)
        if (rms <= 1.0) return 0f
        return (20 * log10(rms / 32768.0) + 50).toFloat().coerceIn(0f, 12f)
    }
}
