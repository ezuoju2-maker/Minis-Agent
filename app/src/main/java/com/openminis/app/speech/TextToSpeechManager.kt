package com.openminis.app.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

class TextToSpeechManager : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TextToSpeech"

        const val INIT_TIMEOUT_MS = 4_000L
    }

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var explicitLocale: Locale? = null
    @Volatile
    var isInitialized = false
        private set

    var initFailed = false
        private set

    private val initResult = kotlinx.coroutines.CompletableDeferred<Boolean>()

    private val preInitQueue = mutableListOf<String>()
    private val queueLock = Any()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var isPaused = false
    private var pendingTexts = mutableListOf<String>()
    private var pausedAtIndex = 0

    private val sentenceBuffer = StringBuilder()

    var speechRate: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.1f, 3.0f)
            tts?.setSpeechRate(field)
        }

    var speechPitch: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
            tts?.setPitch(field)
        }

    var speechVolume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    var audioSessionId: Int = 0

    fun init(context: Context) {
        appContext = context.applicationContext
        tts = TextToSpeech(context.applicationContext, this)
    }

    private fun savedAppLanguageTag(): String? = runCatching {
        appContext
            ?.getSharedPreferences(
                com.openminis.app.ui.settings.PREF_APPEARANCE,
                Context.MODE_PRIVATE,
            )
            ?.getString(com.openminis.app.ui.settings.KEY_LANGUAGE, "")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            tts?.apply {
                setSpeechRate(speechRate)
                setPitch(speechPitch)
                applyResolvedLocale(TtsLocaleResolver.resolve("", savedAppLanguageTag()))
                setOnUtteranceProgressListener(createProgressListener())
            }
            Log.d(TAG, "TTS initialized successfully")
            val queued = synchronized(queueLock) {
                val copy = preInitQueue.toList()
                preInitQueue.clear()
                copy
            }
            if (queued.isNotEmpty()) {
                Handler(Looper.getMainLooper()).post {
                    if (tts != null && isInitialized) {
                        for (text in queued) speakQueued(text)
                    } else {
                        Log.d(TAG, "pre-init replay dropped — engine shut down during init")
                    }
                }
            }
        } else {
            initFailed = true
            synchronized(queueLock) { preInitQueue.clear() }
            _isSpeaking.value = false
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
        initResult.complete(status == TextToSpeech.SUCCESS)
    }

    suspend fun awaitReady(timeoutMs: Long = INIT_TIMEOUT_MS): Boolean {
        if (isInitialized) return true
        if (initFailed) return false
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { initResult.await() } ?: false
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        if (!isInitialized) {
            if (!initFailed) {
                synchronized(queueLock) {
                    preInitQueue.clear()
                    preInitQueue.add(text)
                }
                _isSpeaking.value = true
            }
            return
        }

        isPaused = false
        pendingTexts.clear()
        pendingTexts.add(text)
        pausedAtIndex = 0
        sentenceBuffer.setLength(0)

        autoDetectAndSetLanguage(text)

        val params = buildSpeechParams()
        val rc = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, generateUtteranceId())
        if (rc != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speak rejected by engine (rc=$rc len=${text.length})")
            _isSpeaking.value = false
            return
        }
        _isSpeaking.value = true
    }

    fun speakQueued(text: String) {
        if (text.isBlank()) return
        if (!isInitialized) {
            Log.i(TAG, "speakQueued deferred (init pending) len=${text.length} initFailed=$initFailed")
            if (!initFailed) {
                synchronized(queueLock) { preInitQueue.add(text) }
                _isSpeaking.value = true
            }
            return
        }
        Log.i(TAG, "speakQueued len=${text.length}")

        pendingTexts.add(text)
        autoDetectAndSetLanguage(text)

        val params = buildSpeechParams()
        val rc = tts?.speak(text, TextToSpeech.QUEUE_ADD, params, generateUtteranceId())
        if (rc != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speakQueued rejected by engine (rc=$rc len=${text.length})")
            if (pendingTexts.isEmpty()) _isSpeaking.value = false
            return
        }
        _isSpeaking.value = true
    }

    fun appendText(chunk: String) {
        if (chunk.isEmpty()) return
        sentenceBuffer.append(chunk)
        val sentences = extractCompleteSentences(sentenceBuffer)
        if (sentences.isEmpty()) return
        for (sentence in sentences) {
            speakQueued(sentence)
        }
    }

    fun flush() {
        if (sentenceBuffer.isEmpty()) return
        val tail = sentenceBuffer.toString().trim()
        sentenceBuffer.setLength(0)
        if (tail.isNotEmpty()) {
            speakQueued(tail)
        }
    }

    private fun extractCompleteSentences(buffer: StringBuilder): List<String> =
        SpeechSentenceSplitter.extractCompleteSentences(buffer)

    fun stop() {
        tts?.stop()
        explicitLocale = null
        isPaused = false
        pendingTexts.clear()
        pausedAtIndex = 0
        sentenceBuffer.setLength(0)
        synchronized(queueLock) { preInitQueue.clear() }
        _isSpeaking.value = false
    }

    fun togglePause() {
        if (isPaused) {
            isPaused = false
            if (pausedAtIndex < pendingTexts.size) {
                val remaining = pendingTexts.subList(pausedAtIndex, pendingTexts.size)
                remaining.forEachIndexed { index, text ->
                    autoDetectAndSetLanguage(text)
                    val params = buildSpeechParams()
                    val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    tts?.speak(text, queueMode, params, generateUtteranceId())
                }
                _isSpeaking.value = true
            }
        } else {
            isPaused = true
            tts?.stop()
            _isSpeaking.value = false
        }
    }

    fun setLanguage(locale: Locale) {
        if (!isInitialized) return
        explicitLocale = locale
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Language not supported: ${locale.toLanguageTag()}")
        } else {
            Log.d(TAG, "Language set to: ${locale.toLanguageTag()}")
        }
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        explicitLocale = null
        appContext = null
        tts = null
        isInitialized = false
        Log.d(TAG, "TTS shut down")
    }

    @Volatile
    var preferredVoiceName: String? = null

    private fun autoDetectAndSetLanguage(text: String) {
        preferredVoiceName?.let { wanted ->
            val v = runCatching { tts?.voices?.firstOrNull { it.name == wanted } }.getOrNull()
            if (v != null) {
                val rc = tts?.setVoice(v)
                if (rc == TextToSpeech.SUCCESS) return
                Log.w(TAG, "setVoice($wanted) rejected (rc=$rc) — falling back to auto language")
            } else {
                Log.w(TAG, "preferred voice '$wanted' not found on this engine — falling back to auto language")
            }
        }
        explicitLocale?.let { applyResolvedLocale(it); return }

        applyResolvedLocale(TtsLocaleResolver.resolve(text, savedAppLanguageTag()))
    }

    private fun applyResolvedLocale(wanted: Locale) {
        val engine = tts ?: return
        for (candidate in TtsLocaleResolver.candidates(wanted)) {
            val availability = runCatching { engine.isLanguageAvailable(candidate) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (availability == TextToSpeech.LANG_MISSING_DATA ||
                availability == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                continue
            }
            val rc = runCatching { engine.setLanguage(candidate) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (rc != TextToSpeech.LANG_MISSING_DATA && rc != TextToSpeech.LANG_NOT_SUPPORTED) {
                return
            }
        }
        Log.w(
            TAG,
            "no voice data for ${wanted.toLanguageTag()} — keeping the engine's " +
                "current language (install the language's TTS data to fix)",
        )
    }

    private fun buildSpeechParams(): android.os.Bundle {
        return android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, speechVolume)
            if (audioSessionId > 0) putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, audioSessionId)
        }
    }

    private fun generateUtteranceId(): String = UUID.randomUUID().toString()

    private fun createProgressListener(): UtteranceProgressListener =
        object : UtteranceProgressListener() {

            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                Handler(Looper.getMainLooper()).post {
                    pausedAtIndex++
                    if (pausedAtIndex >= pendingTexts.size) {
                        _isSpeaking.value = false
                    }
                }
            }

            @Deprecated("Deprecated in API level 21")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error for utterance: $utteranceId")
                _isSpeaking.value = false
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS error for utterance $utteranceId, code: $errorCode")
                _isSpeaking.value = false
            }
        }
}
