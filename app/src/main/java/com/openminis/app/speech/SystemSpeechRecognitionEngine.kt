package com.openminis.app.speech

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

class SystemSpeechRecognitionEngine(private val appContext: Context) : SpeechRecognitionEngine {

    override val id: String = "system"
    override val displayName: String = "System recognizer"
    override val supportsPartialResults: Boolean = true

    @Volatile private var degraded: Boolean = false

    @Volatile private var cachedSupportedLocales: List<Locale>? = null

    private var recognizer: SpeechRecognizer? = null
    private var listener: SpeechRecognitionEngine.Listener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override val isAvailable: Boolean
        get() {
            if (degraded) return false
            val systemSaysYes = try { SpeechRecognizer.isRecognitionAvailable(appContext) }
                catch (_: Throwable) { false }
            if (!systemSaysYes) return false
            val intent = Intent(RecognitionService.SERVICE_INTERFACE)
            val services = try {
                appContext.packageManager.queryIntentServices(intent, 0)
            } catch (_: Throwable) { emptyList() }
            return services.isNotEmpty()
        }

    override val supportedLocales: List<Locale>
        get() = cachedSupportedLocales ?: fallbackLocales

    fun fetchSupportedLocales(onResult: (List<Locale>) -> Unit) {
        cachedSupportedLocales?.let { onResult(it); return }

        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        fun deliver(locales: List<Locale>) {
            if (fired.compareAndSet(false, true)) {
                cachedSupportedLocales = locales
                onResult(locales)
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val tags = getResultExtras(true)
                    ?.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES)
                    .orEmpty()
                val locales = tags.mapNotNull { tag ->
                    runCatching { Locale.forLanguageTag(tag) }.getOrNull()
                        ?.takeIf { !it.language.isNullOrEmpty() }
                }.distinct()
                if (locales.isNotEmpty()) deliver(locales) else deliver(fallbackLocales)
            }
        }
        try {
            appContext.sendOrderedBroadcast(
                Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS),
                null, receiver, mainHandler, 0, null, null,
            )
        } catch (e: Throwable) {
            Log.w(TAG, "ACTION_GET_LANGUAGE_DETAILS failed: ${e.message}")
            deliver(fallbackLocales)
            return
        }
        mainHandler.postDelayed({ deliver(fallbackLocales) }, 2500)
    }

    private val fallbackLocales: List<Locale> by lazy {
        listOf(
            Locale.forLanguageTag(RecognizerLocales.recognizerTag(Locale.getDefault())),
        ).plus(RecognizerLocales.BUILTIN_TAGS.map { Locale.forLanguageTag(it) })
            .distinctBy { it.toLanguageTag() }
    }

    override fun start(locale: Locale, listener: SpeechRecognitionEngine.Listener) {
        this.listener = listener
        bufferedPartial = null
        heardSpeech = false
        sessionCommitted = false
        stopRequested = false
        heldSpokenSeconds = 0f
        holdFlushRunnable?.let { mainHandler.removeCallbacks(it) }
        holdFlushRunnable = null
        cancelWatchdog()
        resetSessionPcm()
        replayPcm = null

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            listener.onError(RecognitionError.PERMISSION_DENIED, "RECORD_AUDIO not granted")
            return
        }

        segmentTranscript.setLength(0)
        startEndpointingVad()
        continuousSession = feedingAudio
        continuousLocale = if (feedingAudio) locale else null
        startInternal(locale, allowOnDeviceRetry = true)
    }


    @Volatile
    private var vad: VoiceActivityDetector? = null

    @Volatile
    private var speechStartedAt: Long = 0L

    @Volatile
    private var heardSpeech = false

    @Volatile
    private var sessionCommitted = false

    @Volatile
    private var stopRequested = false

    private var heldSpokenSeconds = 0f
    private var holdFlushRunnable: Runnable? = null

    @Volatile
    private var audioPipeWrite: android.os.ParcelFileDescriptor? = null

    @Volatile
    private var audioPipeStream: java.io.OutputStream? = null

    @Volatile
    private var audioPipeRead: android.os.ParcelFileDescriptor? = null

    @Volatile
    private var feedingAudio: Boolean = false

    @Volatile
    private var continuousSession: Boolean = false

    @Volatile
    private var continuousLocale: Locale? = null

    private val segmentTranscript = StringBuilder()

    private fun startEndpointingVad() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.i(TAG, "[vad] API ${Build.VERSION.SDK_INT} < 33 — platform endpointing")
            return
        }
        val pipe = try {
            android.os.ParcelFileDescriptor.createPipe()
        } catch (t: Throwable) {
            Log.w(TAG, "[vad] pipe creation failed, platform endpointing: ${t.message}")
            return
        }
        audioPipeRead = pipe[0]
        audioPipeWrite = pipe[1]
        audioPipeStream = android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])

        val det = VoiceActivityDetector(
            appContext,
            object : VoiceActivityListener {
                override fun onVoiceStart() {
                    speechStartedAt = System.currentTimeMillis()
                    heardSpeech = true
                    Log.i(TAG, "[vad] speech start")
                }

                override fun onLevel(level: Float) {
                    listener?.onRmsDb(level * 12f)
                }

                override fun onVoiceEnd(wav: ByteArray, reason: SegmentEndReason, spokenSeconds: Float) {
                    if (reason != SegmentEndReason.SILENCE_DETECTED) return
                    speechStartedAt = 0L

                    heldSpokenSeconds += spokenSeconds
                    if (heldSpokenSeconds < MIN_SEGMENT_SECONDS) {
                        Log.i(
                            TAG,
                            "[vad] silence close, spoken=${"%.2f".format(spokenSeconds)}s " +
                                "(held ${"%.2f".format(heldSpokenSeconds)}s) — holding session open",
                        )
                        holdFlushRunnable?.let { mainHandler.removeCallbacks(it) }
                        val flush = Runnable {
                            val held = heldSpokenSeconds
                            Log.i(TAG, "[vad] hold expired at ${"%.2f".format(held)}s — settling")
                            stopVad()
                            cancel()
                            if (held > TOO_SHORT_FLOOR_SECONDS) {
                                listener?.onError(
                                    RecognitionError.NO_MATCH,
                                    "Too short — tap the mic and speak.",
                                )
                            }
                        }
                        holdFlushRunnable = flush
                        mainHandler.postDelayed(flush, HOLD_FLUSH_MS)
                        return
                    }
                    holdFlushRunnable?.let { mainHandler.removeCallbacks(it) }
                    holdFlushRunnable = null
                    Log.i(TAG, "[vad] silence close, spoken=${"%.2f".format(spokenSeconds)}s — finalising")
                    finaliseSession()
                }

                override fun onSessionLimit(limit: SessionLimit) {
                    Log.i(TAG, "[vad] session limit $limit — finalising")
                    finaliseSession()
                }

                override fun onCaptureError(message: String) {
                    Log.w(TAG, "[vad] endpointing unavailable, platform timing applies: $message")
                    stopVad()
                }

                override fun onMicInUse(detail: String) {
                    Log.w(TAG, "[vad] microphone in use by another app: $detail")
                    mainHandler.post {
                        stopVad()
                        cancel()
                        listener?.onError(RecognitionError.MIC_IN_USE, detail)
                    }
                }
            },
        )
        det.rawAudioSink = { bytes, len ->
            retainPcm(bytes, len)
            val out = audioPipeStream
            if (out != null) {
                try {
                    out.write(bytes, 0, len)
                } catch (t: Throwable) {
                    Log.i(TAG, "[vad] audio pipe closed by reader: ${t.javaClass.simpleName}")
                    audioPipeStream = null
                }
            }
        }
        when (val err = det.start()) {
            null -> {
                vad = det
                feedingAudio = true
                Log.i(TAG, "[vad] capture started, feeding recogniser via EXTRA_AUDIO_SOURCE")
            }
            else -> {
                Log.w(TAG, "[vad] could not start, platform endpointing applies: $err")
                closeAudioPipe()
            }
        }
    }

    private fun finaliseSession() {
        stop()
    }

    private fun joinedTranscript(lastSegment: String): String {
        if (segmentTranscript.isEmpty()) return lastSegment
        if (lastSegment.isEmpty()) return segmentTranscript.toString()
        return "$segmentTranscript $lastSegment"
    }

    private fun restartRecogniserForNextSegment() {
        val locale = continuousLocale ?: run {
            Log.w(TAG, "[continuous] no locale to restart with — ending dictation")
            endContinuousSession()
            return
        }
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null

        closeAudioPipe()
        val pipe = try {
            android.os.ParcelFileDescriptor.createPipe()
        } catch (t: Throwable) {
            Log.w(TAG, "[continuous] could not re-open pipe — ending dictation: ${t.message}")
            endContinuousSession()
            return
        }
        audioPipeRead = pipe[0]
        audioPipeWrite = pipe[1]
        audioPipeStream = android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        feedingAudio = true

        startInternal(locale, allowOnDeviceRetry = false)
    }

    private fun endContinuousSession() {
        continuousSession = false
        continuousLocale = null
        val all = joinedTranscript(bufferedPartial.orEmpty())
        segmentTranscript.setLength(0)
        bufferedPartial = null
        if (all.isNotEmpty()) listener?.onFinal(all) else listener?.onFinal("")
        tearDown()
    }

    private fun closeAudioPipe() {
        audioPipeStream?.let { runCatching { it.close() } }
        audioPipeStream = null
        audioPipeWrite = null
        audioPipeRead?.let { runCatching { it.close() } }
        audioPipeRead = null
        feedingAudio = false
    }

    private fun stopVad() {
        vad?.let {
            it.rawAudioSink = null
            runCatching { it.stop() }
        }
        vad = null
        speechStartedAt = 0L
    }

    fun setBackgrounded(backgrounded: Boolean) {
        vad?.isBackgrounded = backgrounded
    }

    private fun startInternal(locale: Locale, allowOnDeviceRetry: Boolean) {
        mainHandler.post {
            try {
                val wantOnDevice = preferOffline &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    allowOnDeviceRetry
                val r = if (wantOnDevice) {
                    try { SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext) }
                    catch (_: Throwable) { SpeechRecognizer.createSpeechRecognizer(appContext) }
                } else {
                    SpeechRecognizer.createSpeechRecognizer(appContext)
                }
                usingOnDevice = wantOnDevice
                pendingLocale = locale
                recognizer = r
                r.setRecognitionListener(callbacks)
                r.startListening(buildIntent(locale))
            } catch (e: Throwable) {
                Log.w(TAG, "startListening threw: ${e.message}")
                this.listener?.onError(RecognitionError.UNKNOWN, e.message)
                markDegraded()
                tearDown()
            }
        }
    }

    @Volatile private var usingOnDevice: Boolean = false

    @Volatile private var pendingLocale: Locale? = null

    override fun stop() {
        mainHandler.post {
            continuousSession = false
            continuousLocale = null
            stopRequested = true

            stopVad()
            closeAudioPipe()
            try { recognizer?.stopListening() }
            catch (e: Throwable) { Log.w(TAG, "stopListening: ${e.message}") }
            if (recognizer != null && !sessionCommitted) {
                armWatchdog(VoiceAsrWatchdog.pcmSeconds(retainedPcmSize(), VoiceActivityDetector.SAMPLE_RATE))
            }
        }
    }


    // ── [T-voice-asr-failure-retry-prompt] Kept audio, replay, watchdog ────
    private val sessionPcm = ArrayList<ByteArray>()
    private var sessionPcmBytes = 0
    private val sessionPcmLock = Any()

    private fun resetSessionPcm() = synchronized(sessionPcmLock) {
        sessionPcm.clear()
        sessionPcmBytes = 0
    }

    @Volatile
    private var replayPcm: ByteArray? = null

    private var watchdogRunnable: Runnable? = null

    private fun retainPcm(bytes: ByteArray, len: Int) {
        if (len <= 0) return
        synchronized(sessionPcmLock) {
            if (sessionPcmBytes + len > MAX_RETAINED_PCM_BYTES) return
            sessionPcm.add(bytes.copyOf(len))
            sessionPcmBytes += len
        }
    }

    private fun retainedPcmSize(): Int = synchronized(sessionPcmLock) { sessionPcmBytes }

    private fun retainedPcm(): ByteArray = synchronized(sessionPcmLock) {
        val out = ByteArray(sessionPcmBytes)
        var pos = 0
        for (c in sessionPcm) {
            System.arraycopy(c, 0, out, pos, c.size)
            pos += c.size
        }
        out
    }

    private fun handBackAudio() {
        val pcm = retainedPcm()
        if (pcm.isEmpty()) return
        listener?.onRetainedAudio(
            com.openminis.app.provider.voice.VoiceProvider.wrapPcm16InWav(pcm, VoiceActivityDetector.SAMPLE_RATE),
        )
    }

    private fun armWatchdog(audioSeconds: Double) {
        cancelWatchdog()
        val seconds = VoiceAsrWatchdog.seconds(audioSeconds)
        val r = Runnable {
            watchdogRunnable = null
            if (sessionCommitted || listener == null) return@Runnable
            sessionCommitted = true
            val salvage = joinedTranscript(bufferedPartial.orEmpty())
            try { recognizer?.cancel() } catch (_: Throwable) {}
            if (salvage.isNotEmpty()) {
                Log.i(TAG, "[watchdog] fired after ${seconds.toInt()}s — committing ${salvage.length} salvaged chars")
                listener?.onFinal(salvage)
            } else {
                Log.w(TAG, "[watchdog] fired after ${seconds.toInt()}s with no text — reporting timeout")
                handBackAudio()
                listener?.onError(RecognitionError.TIMED_OUT, "Speech recognition timed out")
            }
            tearDown()
        }
        watchdogRunnable = r
        mainHandler.postDelayed(r, (seconds * 1000).toLong())
    }

    private fun cancelWatchdog() {
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    override fun transcribeRetained(wav: ByteArray, locale: Locale, listener: SpeechRecognitionEngine.Listener) {
        val header = VoiceActivityDetector.WAV_HEADER_BYTES
        val pcm = if (wav.size > header) wav.copyOfRange(header, wav.size) else ByteArray(0)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || pcm.isEmpty()) {
            listener.onRetainedAudio(wav)
            listener.onError(RecognitionError.UNKNOWN, "Re-transcribing saved audio needs Android 13 or later")
            return
        }
        mainHandler.post {
            this.listener = listener
            bufferedPartial = null
            heardSpeech = true
            sessionCommitted = false
            stopRequested = false
            heldSpokenSeconds = 0f
            segmentTranscript.setLength(0)
            continuousSession = false
            continuousLocale = null
            startReplay(pcm, locale, allowOnDeviceRetry = true)
        }
    }

    private fun startReplay(pcm: ByteArray, locale: Locale, allowOnDeviceRetry: Boolean) {
        replayPcm = pcm
        synchronized(sessionPcmLock) {
            sessionPcm.clear()
            sessionPcm.add(pcm)
            sessionPcmBytes = pcm.size
        }
        val pipe = try {
            android.os.ParcelFileDescriptor.createPipe()
        } catch (t: Throwable) {
            Log.w(TAG, "[replay] pipe creation failed: ${t.message}")
            sessionCommitted = true
            handBackAudio()
            listener?.onError(RecognitionError.UNKNOWN, t.message)
            tearDown()
            return
        }
        audioPipeRead = pipe[0]
        audioPipeWrite = pipe[1]
        val out = android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        audioPipeStream = out
        feedingAudio = true
        Thread({
            try {
                var pos = 0
                while (pos < pcm.size) {
                    val n = minOf(REPLAY_CHUNK_BYTES, pcm.size - pos)
                    out.write(pcm, pos, n)
                    pos += n
                }
            } catch (t: Throwable) {
                Log.i(TAG, "[replay] pipe closed early: ${t.javaClass.simpleName}")
            } finally {
                runCatching { out.close() }
            }
        }, "asr-replay").start()
        startInternal(locale, allowOnDeviceRetry)
        armWatchdog(VoiceAsrWatchdog.pcmSeconds(pcm.size, VoiceActivityDetector.SAMPLE_RATE))
    }

    override fun cancel() {
        mainHandler.post {
            try { recognizer?.cancel() }
            catch (e: Throwable) { Log.w(TAG, "cancel: ${e.message}") }
            tearDown()
        }
    }

    override fun markDegraded() {
        degraded = true
    }

    override fun clearDegraded() {
        degraded = false
    }

    private fun tearDown() {
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
        stopVad()
        closeAudioPipe()
        bufferedPartial = null
        continuousSession = false
        continuousLocale = null
        segmentTranscript.setLength(0)
        holdFlushRunnable?.let { mainHandler.removeCallbacks(it) }
        holdFlushRunnable = null
        heldSpokenSeconds = 0f
        cancelWatchdog()
        resetSessionPcm()
        replayPcm = null
    }

    @Volatile
    private var bufferedPartial: String? = null

    @Volatile var preferOffline: Boolean = false


    private fun buildIntent(locale: Locale): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, RecognizerLocales.recognizerTag(locale))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, PLATFORM_SILENCE_GUARD_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, PLATFORM_SILENCE_GUARD_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500)

            audioPipeRead?.let { readEnd ->
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, VoiceActivityDetector.SAMPLE_RATE)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(
                    RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                    RecognizerIntent.EXTRA_AUDIO_SOURCE,
                )
            }
        }
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { listener?.onReadyForSpeech() }
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onRmsChanged(rms: Float) { listener?.onRmsDb(rms) }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isEmpty()) return
            val prev = bufferedPartial
            if (prev != null && prev.length > text.length * 2 && !prev.startsWith(text)) {
                Log.i(TAG, "[partial] reset detected (${prev.length}→${text.length}) — folding previous into transcript")
                if (segmentTranscript.isNotEmpty()) segmentTranscript.append(' ')
                segmentTranscript.append(prev)
            }
            bufferedPartial = text
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            val committed = text.ifEmpty { bufferedPartial.orEmpty() }
            Log.i(
                TAG,
                "onResults finalLen=${text.length} bufferedLen=${bufferedPartial?.length ?: 0} " +
                    "committedLen=${committed.length} hasListener=${listener != null}",
            )
            bufferedPartial = null

            if (continuousSession) {
                if (committed.isNotEmpty()) {
                    if (segmentTranscript.isNotEmpty()) segmentTranscript.append(' ')
                    segmentTranscript.append(committed)
                }
                Log.i(TAG, "[continuous] segment done (+${committed.length}), restarting recogniser")
                restartRecogniserForNextSegment()
                return
            }

            commitFinal(joinedTranscript(committed))
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            val text = segmentResults
                .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            Log.i(TAG, "[segmented] segment result len=${text.length}")
            if (text.isNotEmpty()) {
                if (segmentTranscript.isNotEmpty()) segmentTranscript.append(' ')
                segmentTranscript.append(text)
                bufferedPartial = null
            }
        }

        override fun onEndOfSegmentedSession() {
            val tail = bufferedPartial.orEmpty()
            bufferedPartial = null
            Log.i(
                TAG,
                "[segmented] session end segLen=${segmentTranscript.length} tailLen=${tail.length}",
            )
            continuousSession = false
            continuousLocale = null
            commitFinal(joinedTranscript(tail))
        }

        private fun commitFinal(text: String) {
            if (sessionCommitted) {
                Log.i(TAG, "[commit] duplicate terminal callback ignored (len=${text.length})")
                return
            }
            sessionCommitted = true
            if (text.isBlank() && heardSpeech) {
                Log.w(TAG, "[commit] speech heard but zero text — surfacing TRANSCRIPTION_FAILED")
                handBackAudio()
                listener?.onError(
                    RecognitionError.TRANSCRIPTION_FAILED,
                    "Speech detected but nothing was transcribed. If this keeps happening, " +
                        "install the offline language pack in system voice-input settings, " +
                        "or switch to a provider recognizer.",
                )
                tearDown()
                return
            }
            listener?.onFinal(text)
            tearDown()
        }

        override fun onError(errorCode: Int) {
            val err = mapError(errorCode).let {
                if (it == RecognitionError.AUDIO_ERROR) MicInUse.captureFailure(appContext) else it
            }
            val msg = errorMessage(errorCode)
            val retryLocale = pendingLocale
            val beforeAnything = !sessionCommitted && !stopRequested && !heardSpeech &&
                segmentTranscript.isEmpty() && bufferedPartial.isNullOrEmpty()
            val mayFallBack = RecognizerErrorCodes.isLanguageError(errorCode) || replayPcm != null || beforeAnything
            if (RecognizerFallback.shouldSwitch(errorCode) && mayFallBack && usingOnDevice && retryLocale != null) {
                Log.i(TAG, "on-device recognizer failed (${RecognizerErrorCodes.name(errorCode)}) — retrying via the default recognizer")
                usingOnDevice = false
                val replay = replayPcm
                tearDown()
                if (replay != null) {
                    startReplay(replay, retryLocale, allowOnDeviceRetry = false)
                } else {
                    startInternal(retryLocale, allowOnDeviceRetry = false)
                }
                return
            }
            if (err == RecognitionError.OEM_NO_SERVICE ||
                err == RecognitionError.PERMISSION_DENIED ||
                err == RecognitionError.AUDIO_ERROR
            ) {
                markDegraded()
            }
            if (continuousSession && err == RecognitionError.NO_MATCH) {
                Log.i(TAG, "[continuous] pause (error $errorCode) — restarting recogniser")
                restartRecogniserForNextSegment()
                return
            }
            Log.w(TAG, "recognizer error $errorCode ($msg)")

            if (sessionCommitted) {
                Log.i(TAG, "[error] post-commit error $errorCode ignored")
                tearDown()
                return
            }
            sessionCommitted = true

            continuousSession = false
            continuousLocale = null
            val salvaged = joinedTranscript(bufferedPartial.orEmpty())
            segmentTranscript.setLength(0)
            if (salvaged.isNotEmpty()) {
                Log.i(TAG, "[continuous] salvaging ${salvaged.length} chars from error $errorCode")
                bufferedPartial = null
                listener?.onFinal(salvaged)
                tearDown()
                return
            }
            val effErr = if (err == RecognitionError.NO_MATCH && heardSpeech) {
                Log.w(TAG, "[error] NO_MATCH but VAD heard speech — surfacing TRANSCRIPTION_FAILED")
                RecognitionError.TRANSCRIPTION_FAILED
            } else err
            val effMsg = if (effErr == RecognitionError.TRANSCRIPTION_FAILED) {
                "Speech detected but nothing was transcribed. If this keeps happening, " +
                    "install the offline language pack in system voice-input settings, " +
                    "or switch to a provider recognizer."
            } else msg
            if (effErr != RecognitionError.NO_MATCH) handBackAudio()
            listener?.onError(effErr, effMsg)
            tearDown()
        }
    }

    companion object {
        private const val TAG = "SystemSTT"

        private const val MIN_SEGMENT_SECONDS = 2.0f

        private const val TOO_SHORT_FLOOR_SECONDS = 0.3f

        private const val HOLD_FLUSH_MS = 5_000L

        private const val PLATFORM_SILENCE_GUARD_MS = 15_000

        private const val MAX_RETAINED_PCM_BYTES = 300 * VoiceActivityDetector.SAMPLE_RATE * 2

        private const val REPLAY_CHUNK_BYTES = 8_192

        private fun mapError(code: Int): RecognitionError = RecognizerErrorCodes.classify(code)

        private fun errorMessage(code: Int): String = RecognizerErrorCodes.describe(code)
    }
}
