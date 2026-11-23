package com.openminis.app.speech

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.widget.Toast
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.voice.VoiceOutputRequest
import com.openminis.app.provider.voice.VoiceProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class ReadAloudPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val system = TextToSpeechManager().also { it.init(appContext) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private var worker: Job? = null

    private var player: MediaPlayer? = null

    private var playerBoost: ReadAloudBoost? = null

    private val systemSessionId: Int = runCatching {
        appContext.getSystemService(AudioManager::class.java).generateAudioSessionId()
    }.getOrDefault(0).coerceAtLeast(0)
    private val systemBoost = ReadAloudBoost(systemSessionId)

    private var playbackFinisher: (() -> Unit)? = null

    @Volatile
    private var progressListenerBroken = false

    private val pending = AtomicInteger(0)

    private fun ownsCapsule(): Boolean = VoiceOutputState.isActivePlayer(this)

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val focus = ReadAloudAudioFocus.shared(appContext)

    private val onFocusLost: () -> Unit = {
        AppLogger.info(TAG, "audio focus lost — stopping read-aloud")
        stop()
    }

    private val sentenceBuffer = StringBuilder()

    private val heldForCapture = mutableListOf<String>()

    @Volatile
    private var capturePaused = false

    private val linkPhrases by lazy {
        VoiceTextSanitizer.LinkPhrases(
            linkToHost = appContext.getString(R.string.voice_link_to_host),
            bareLink = appContext.getString(R.string.voice_bare_link),
        )
    }

    init {
        VoiceOutputState.init(appContext)
        VoiceOutputState.registerPlayer(this)
        worker = scope.launch {
            for (text in queue) {
                try {
                    runCatching { speakOne(text) }
                        .onFailure { AppLogger.error(TAG, "utterance failed: $it") }
                } finally {
                    val moreQueued = pending.get() > 1
                    focus.releaseSoon(
                        this@ReadAloudPlayer,
                        if (moreQueued) ReadAloudAudioFocus.SYNTH_WAIT_MS else ReadAloudAudioFocus.GRACE_MS,
                    )
                }
                if (pending.decrementAndGet() <= 0) {
                    _isSpeaking.value = false
                    if (ownsCapsule()) VoiceOutputState.isSpeaking.value = false
                }
            }
        }
        scope.launch {
            VoiceOutputState.volume.collect { v ->
                val gain = ReadAloudBoost.linearGain(v)
                player?.runCatching { setVolume(gain, gain) }
                playerBoost?.apply(v)
                systemBoost.apply(v)
            }
        }
    }

    fun appendText(chunk: String) {
        if (chunk.isEmpty()) return
        sentenceBuffer.append(chunk)
        for (sentence in extractCompleteSentences(sentenceBuffer)) {
            enqueue(sentence)
        }
    }

    fun flush() {
        if (sentenceBuffer.isEmpty()) return
        for (s in SpeechSentenceSplitter.extractCompleteSentences(sentenceBuffer, streaming = false)) {
            enqueue(s)
        }
        val tail = sentenceBuffer.toString().trim()
        sentenceBuffer.setLength(0)
        if (tail.isNotEmpty()) enqueue(tail)
    }

    fun speak(text: String) {
        stop()
        sentenceBuffer.append(text)
        flush()
    }

    fun speakQueued(text: String) {
        if (text.isBlank()) return
        enqueue(text)
    }

    private fun enqueue(raw: String) {
        if (!VoiceOutputState.canPlay) return
        val recState = SpeechRecognitionManager.state.value
        if (recState == RecognitionState.RECORDING || recState == RecognitionState.STARTING) {
            AppLogger.info(TAG, "enqueue dropped — mic capturing (state=$recState)")
            return
        }
        val clean = VoiceTextSanitizer.sanitize(raw, linkPhrases)
        if (clean.isBlank()) return
        pending.incrementAndGet()
        _isSpeaking.value = true
        if (ownsCapsule()) VoiceOutputState.isSpeaking.value = true
        queue.trySend(clean)
    }

    fun suspendForCapture() {
        if (capturePaused) return
        capturePaused = true
        val held = mutableListOf<String>()
        while (true) {
            val r = queue.tryReceive()
            if (r.isSuccess) held.add(r.getOrThrow()) else break
        }
        val tail = sentenceBuffer.toString().trim()
        sentenceBuffer.setLength(0)
        if (tail.isNotEmpty()) held.add(tail)
        heldForCapture.addAll(held)

        playbackFinisher?.invoke()
        releasePlayer()
        system.stop()
        focus.releaseNow(this)
        pending.set(0)
        _isSpeaking.value = false
        if (ownsCapsule()) {
            VoiceOutputState.isSpeaking.value = false
            VoiceOutputState.isSynthesizing.value = false
        }
        AppLogger.info(TAG, "suspended for mic capture — held ${heldForCapture.size} utterance(s)")
    }

    fun resumeAfterCapture() {
        if (!capturePaused) return
        capturePaused = false
        if (heldForCapture.isEmpty()) return
        val toSpeak = heldForCapture.toList()
        heldForCapture.clear()
        toSpeak.forEach { enqueue(it) }
        AppLogger.info(TAG, "resumed after mic capture — re-queued ${toSpeak.size} utterance(s)")
    }

    fun stop() {
        capturePaused = false
        heldForCapture.clear()
        sentenceBuffer.setLength(0)
        while (queue.tryReceive().isSuccess) {  }
        playbackFinisher?.invoke()
        releasePlayer()
        system.stop()
        focus.releaseNow(this)
        pending.set(0)
        _isSpeaking.value = false
        if (ownsCapsule()) {
            VoiceOutputState.isSpeaking.value = false
            if (ownsCapsule()) VoiceOutputState.isSynthesizing.value = false
        }
    }

    fun shutdown() {
        stop()
        worker?.cancel()
        queue.close()
        scope.cancel()
        system.shutdown()
        systemBoost.release()
        VoiceOutputState.unregisterPlayer(this)
    }


    private suspend fun speakOne(text: String) {
        val entry = runCatching {
            (appContext as? MinisApp)?.providerRepository?.resolveVoiceOutputEntry()
        }.getOrNull()

        if (entry != null) {
            val (instance, modelEntry) = entry
            if (ownsCapsule()) {
                VoiceOutputState.activeModelLabel.value =
                    modelEntry.model.displayName.ifBlank { modelEntry.model.id }
            }
            val ok = runCatching { speakViaProvider(instance, modelEntry, text) }
                .getOrElse {
                    AppLogger.error(
                        TAG,
                        "provider TTS failed (model=${modelEntry.model.id} " +
                            "vendor=${instance.providerType}), falling back to system engine: $it",
                    )
                    false
                }
            if (ok) return
        }
        if (ownsCapsule()) VoiceOutputState.activeModelLabel.value = null
        if (!speakViaSystem(text)) {
            notifySpeechUnavailable(providerConfigured = entry != null)
        }
    }

    private var unavailableNotified = false

    private fun notifySpeechUnavailable(providerConfigured: Boolean) {
        AppLogger.error(
            TAG,
            "read-aloud produced NO audio: providerConfigured=$providerConfigured " +
                "systemEngine=${if (system.initFailed) "init-failed" else "unavailable"}",
        )
        if (unavailableNotified) return
        unavailableNotified = true
        Toast.makeText(
            appContext,
            appContext.getString(R.string.read_aloud_unavailable),
            Toast.LENGTH_LONG,
        ).show()
    }

    private suspend fun speakViaProvider(
        instance: com.openminis.app.data.model.ProviderInstance,
        modelEntry: com.openminis.app.data.model.ModelEntry,
        text: String,
    ): Boolean {
        val repo = (appContext as? MinisApp)
            ?.takeIf { it.subsystemsReady() }
            ?.providerRepository
            ?: return false.also { AppLogger.error(TAG, "provider TTS skipped: repository unavailable") }
        val apiKey = repo.loadApiKey(instance.id)
            ?: return false.also { AppLogger.error(TAG, "provider TTS skipped: no API key for ${instance.id}") }
        val voice = VoiceProviderFactory.make(instance, apiKey)
            ?: return false.also { AppLogger.error(TAG, "provider TTS skipped: no voice adapter for ${instance.providerType}/${instance.customBaseURL}") }
        if (!voice.supportsVoiceOutput) {
            AppLogger.error(TAG, "provider TTS skipped: ${voice.javaClass.simpleName} does not support output")
            return false
        }

        if (ownsCapsule()) VoiceOutputState.isSynthesizing.value = true
        val data = try {
            withContext(Dispatchers.IO) {
                voice.synthesize(
                    VoiceOutputRequest(
                        input = text,
                        model = modelEntry.model.id,
                        voice = modelEntry.model.id,
                        resolvedModel = modelEntry.model,
                    ),
                )
            }
        } finally {
            VoiceOutputState.isSynthesizing.value = false
        }
        if (data.isEmpty()) {
            AppLogger.error(TAG, "provider TTS returned empty audio (model=${modelEntry.model.id})")
            return false
        }
        playBytes(data)
        return true
    }

    private suspend fun playBytes(data: ByteArray) {
        val file = withContext(Dispatchers.IO) {
            File(appContext.cacheDir, "readaloud_tts.audio").apply { writeBytes(data) }
        }
        suspendCancellableCoroutine { cont ->
            val mp = MediaPlayer()
            player = mp
            var resumed = false
            fun finish() {
                if (resumed) return
                resumed = true
                playbackFinisher = null
                releasePlayer()
                if (cont.isActive) cont.resume(Unit)
            }
            playbackFinisher = ::finish
            runCatching {
                mp.setAudioAttributes(ReadAloudAudioFocus.ATTRIBUTES)
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener { finish() }
                mp.setOnErrorListener { _, what, extra ->
                    AppLogger.error(TAG, "MediaPlayer error what=$what extra=$extra")
                    finish()
                    true
                }
                mp.prepare()
                val volume = VoiceOutputState.volume.value
                val gain = ReadAloudBoost.linearGain(volume)
                mp.setVolume(gain, gain)
                playerBoost = ReadAloudBoost(mp.audioSessionId).also { it.apply(volume) }
                logVolume("provider", volume)
                focus.acquire(this@ReadAloudPlayer, onFocusLost)
                mp.start()
                val speed = VoiceOutputState.speed.value
                if (speed != 1.0f) {
                    runCatching {
                        mp.playbackParams = mp.playbackParams.setSpeed(speed)
                    }.onFailure {
                        AppLogger.error(TAG, "playback speed $speed rejected by this ROM's MediaPlayer, playing at 1×: $it")
                    }
                }
            }.onFailure {
                AppLogger.error(TAG, "MediaPlayer setup failed: $it")
                finish()
            }
            cont.invokeOnCancellation { releasePlayer() }
        }
    }

    private suspend fun speakViaSystem(text: String): Boolean {
        if (!system.awaitReady()) return false
        system.speechRate = VoiceOutputState.speed.value
        system.preferredVoiceName = VoiceOutputState.systemVoiceName.value
        val volume = VoiceOutputState.volume.value
        system.speechVolume = ReadAloudBoost.linearGain(volume)
        system.audioSessionId = systemSessionId
        systemBoost.apply(volume)
        logVolume("system", volume)
        if (ownsCapsule()) {
            VoiceOutputState.activeModelLabel.value = VoiceOutputState.systemVoiceLabel.value
        }
        focus.acquire(this, onFocusLost)
        system.speak(text)
        if (!system.isSpeaking.value) return false
        if (progressListenerBroken) {
            kotlinx.coroutines.delay(estimatedSpeechMs(text))
            return true
        }
        val budgetMs = (text.length * 600L + 10_000L).coerceAtMost(180_000L)
        val deadline = System.currentTimeMillis() + budgetMs
        var timedOut = false
        while (system.isSpeaking.value) {
            if (System.currentTimeMillis() > deadline) {
                AppLogger.error(
                    TAG,
                    "system TTS completion never reported (len=${text.length}, " +
                        "waited ${budgetMs}ms) — engine's progress listener is broken; moving on",
                )
                timedOut = true
                break
            }
            kotlinx.coroutines.delay(POLL_MS)
        }
        if (timedOut) {
            progressListenerBroken = true
            return false
        }
        return true
    }

    private fun estimatedSpeechMs(text: String): Long =
        (text.length * 90L + 400L).coerceIn(600L, 30_000L)

    private var lastVolumeLog: Pair<String, Float>? = null

    private fun logVolume(path: String, volume: Float) {
        if (lastVolumeLog == path to volume) return
        lastVolumeLog = path to volume
        AppLogger.info(TAG, "read-aloud volume=$volume path=$path")
    }

    private fun releasePlayer() {
        playerBoost?.release()
        playerBoost = null
        player?.runCatching {
            if (isPlaying) stop()
            release()
        }
        player = null
    }

    private fun extractCompleteSentences(buffer: StringBuilder): List<String> =
        SpeechSentenceSplitter.extractCompleteSentences(buffer)

    companion object {
        private const val TAG = "ReadAloud"
        private const val POLL_MS = 80L
    }
}
