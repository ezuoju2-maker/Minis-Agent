package com.openminis.app.speech

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

object SpeechRecognitionManager {

    private const val TAG = "SpeechManager"
    private const val PREFS = "speech_recognition"
    private const val KEY_ENGINE_ID = "engineId"
    private const val KEY_LOCALE = "locale"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private val engines = mutableListOf<SpeechRecognitionEngine>()

    private val _state = MutableStateFlow(RecognitionState.IDLE)
    val state: StateFlow<RecognitionState> = _state.asStateFlow()

    private fun setState(next: RecognitionState) {
        val prev = _state.value
        _state.value = next
        if (next == RecognitionState.IDLE && prev != RecognitionState.IDLE) {
            VoiceOutputState.resumeAllAfterCapture()
        }
    }

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _locale = MutableStateFlow(Locale.getDefault())
    val locale: StateFlow<Locale> = _locale.asStateFlow()

    private val _selectedEngineId = MutableStateFlow("system")
    val selectedEngineId: StateFlow<String> = _selectedEngineId.asStateFlow()

    private val _lastError = MutableStateFlow<RecognitionError?>(null)
    val lastError: StateFlow<RecognitionError?> = _lastError.asStateFlow()

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    val hasMicrophoneHardware: Boolean
        get() = if (::appContext.isInitialized) {
            runCatching {
                appContext.packageManager.hasSystemFeature(
                    android.content.pm.PackageManager.FEATURE_MICROPHONE,
                )
            }.getOrDefault(true)
        } else {
            true
        }

    private val _supportedLocales = MutableStateFlow<List<Locale>>(emptyList())
    val supportedLocales: StateFlow<List<Locale>> = _supportedLocales.asStateFlow()

    private val _audioLevels = MutableStateFlow(List(WAVEFORM_SIZE) { 0f })
    val audioLevels: StateFlow<List<Float>> = _audioLevels.asStateFlow()

    const val WAVEFORM_SIZE = 40

    private fun normalizeRms(db: Float): Float {
        val clamped = db.coerceIn(0f, 12f)
        return (clamped / 12f).coerceIn(0f, 1f)
    }

    internal fun pushLevel(rawDb: Float) {
        val n = normalizeRms(rawDb)
        val current = _audioLevels.value
        val updated = ArrayList<Float>(current.size)
        for (i in 1 until current.size) updated.add(current[i])
        updated.add(n)
        _audioLevels.value = updated
    }

    private fun resetLevels() {
        _audioLevels.value = List(WAVEFORM_SIZE) { 0f }
    }

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        engines.clear()
        engines.add(SystemSpeechRecognitionEngine(appContext))
        engines.add(ProviderSpeechRecognitionEngine(appContext))

        _selectedEngineId.value = prefs.getString(KEY_ENGINE_ID, null) ?: pickDefaultEngineId()
        prefs.getString(KEY_LOCALE, null)?.let { tag ->
            runCatching { _locale.value = Locale.forLanguageTag(tag) }
        }
        refreshAvailability()
        refreshSupportedLocales()
        observeAppLifecycle()
    }

    private fun observeAppLifecycle() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
                    androidx.lifecycle.LifecycleEventObserver { _, event ->
                        when (event) {
                            androidx.lifecycle.Lifecycle.Event.ON_STOP -> setBackgrounded(true)
                            androidx.lifecycle.Lifecycle.Event.ON_START -> setBackgrounded(false)
                            else -> {}
                        }
                    },
                )
            }.onFailure { Log.w(TAG, "lifecycle observer failed: ${it.message}") }
        }
    }

    private fun setBackgrounded(backgrounded: Boolean) {
        for (e in engines) {
            when (e) {
                is SystemSpeechRecognitionEngine -> e.setBackgrounded(backgrounded)
                is ProviderSpeechRecognitionEngine -> e.setBackgrounded(backgrounded)
            }
        }
    }

    fun refreshSupportedLocales() {
        val engine = currentEngine() ?: return
        if (engine is SystemSpeechRecognitionEngine) {
            engine.fetchSupportedLocales { locales ->
                _supportedLocales.value = locales
                if (locales.isNotEmpty() && locales.none { it.toLanguageTag() == _locale.value.toLanguageTag() }) {
                    val fallback = locales.firstOrNull { it.language == Locale.getDefault().language }
                        ?: locales.firstOrNull { it.language == "en" }
                        ?: locales.first()
                    _locale.value = fallback
                }
            }
        } else {
            _supportedLocales.value = engine.supportedLocales
        }
    }

    private fun pickDefaultEngineId(): String =
        engines.firstOrNull { it.isAvailable }?.id ?: "system"

    fun selectEngine(id: String) {
        if (_selectedEngineId.value == id) return
        _selectedEngineId.value = id
        prefs.edit().putString(KEY_ENGINE_ID, id).apply()
        engines.firstOrNull { it.id == id }?.clearDegraded()
        refreshAvailability()
    }

    fun clearDegradationAndRefresh() {
        engines.forEach { it.clearDegraded() }
        refreshAvailability()
    }

    fun selectLocale(locale: Locale) {
        if (_locale.value.toLanguageTag() == locale.toLanguageTag()) return
        _locale.value = locale
        prefs.edit().putString(KEY_LOCALE, locale.toLanguageTag()).apply()
        if (_state.value == RecognitionState.RECORDING || _state.value == RecognitionState.STARTING) {
            val cb = activeCallbacks
            if (cb != null) {
                currentEngine()?.cancel()
                setState(RecognitionState.IDLE)
                startRecording(cb.onPartialOrFinal, cb.onError, cb.onFailedAudio)
            }
        }
    }

    private class Callbacks(
        val onPartialOrFinal: (String, Boolean) -> Unit,
        val onError: (RecognitionError, String?) -> Unit,
        val onFailedAudio: ((FailedAudio) -> Unit)?,
    )
    private var activeCallbacks: Callbacks? = null

    class FailedAudio(
        val wav: ByteArray,
        val seconds: Double,
        val error: RecognitionError,
        val message: String?,
        val engineId: String,
    )

    @Volatile
    private var retryEngine: SpeechRecognitionEngine? = null

    fun availableEngines(): List<SpeechRecognitionEngine> = engines.toList()

    private val noAsrStall = MutableStateFlow(false).asStateFlow()

    fun asrStalled(): StateFlow<Boolean> =
        engines.filterIsInstance<ProviderSpeechRecognitionEngine>().firstOrNull()?.asrAttempts?.stalled
            ?: noAsrStall

    fun skipStalledTranscription(systemName: String, genericName: String): String? =
        engines.filterIsInstance<ProviderSpeechRecognitionEngine>().firstOrNull()
            ?.skipStalledTranscription(systemName, genericName)

    fun refreshAvailability() {
        _isAvailable.value = engines.any { it.isAvailable }
    }

    private fun currentEngine(): SpeechRecognitionEngine? {
        val byId = engines.firstOrNull { it.id == _selectedEngineId.value }
        if (byId != null && byId.isAvailable) return byId
        return engines.firstOrNull { it.isAvailable }
    }

    fun startRecording(
        onPartialOrFinal: (text: String, isFinal: Boolean) -> Unit,
        onError: (RecognitionError, String?) -> Unit,
        onFailedAudio: ((FailedAudio) -> Unit)? = null,
    ) {
        if (_state.value != RecognitionState.IDLE) {
            Log.d(TAG, "startRecording ignored; state=${_state.value}")
            VoicePipelineLog.event("manager.start.rejected", "state" to _state.value)
            return
        }
        val engine = currentEngine()
        if (engine == null) {
            _lastError.value = RecognitionError.OEM_NO_SERVICE
            VoicePipelineLog.event("manager.start.noEngine")
            onError(RecognitionError.OEM_NO_SERVICE, "No recognition engine available")
            return
        }
        VoicePipelineLog.event("manager.start", "engine" to engine.id)

        VoiceOutputState.suspendAllForCapture()

        setState(RecognitionState.STARTING)
        _recognizedText.value = ""
        _lastError.value = null
        resetLevels()
        activeCallbacks = Callbacks(onPartialOrFinal, onError, onFailedAudio)

        val listener = makeListener(engine.id, onPartialOrFinal, onError, onFailedAudio)

        try {
            engine.start(_locale.value, listener)
        } catch (e: Throwable) {
            setState(RecognitionState.IDLE)
            _lastError.value = RecognitionError.UNKNOWN
            onError(RecognitionError.UNKNOWN, e.message)
        }
    }

    fun retryTranscription(
        failed: FailedAudio,
        onPartialOrFinal: (text: String, isFinal: Boolean) -> Unit,
        onError: (RecognitionError, String?) -> Unit,
        onFailedAudio: (FailedAudio) -> Unit,
    ): Boolean {
        if (_state.value != RecognitionState.IDLE) {
            VoicePipelineLog.event("manager.retry.rejected", "state" to _state.value)
            return false
        }
        val engine = engines.firstOrNull { it.id == failed.engineId } ?: currentEngine()
        if (engine == null) {
            onFailedAudio(failed)
            return true
        }
        VoicePipelineLog.event("manager.retry", "engine" to engine.id, "seconds" to "%.1f".format(failed.seconds))
        setState(RecognitionState.FINISHING)
        _lastError.value = null
        retryEngine = engine
        val listener = makeListener(engine.id, onPartialOrFinal, onError, onFailedAudio)
        try {
            engine.transcribeRetained(failed.wav, _locale.value, listener)
        } catch (e: Throwable) {
            retryEngine = null
            setState(RecognitionState.IDLE)
            onFailedAudio(FailedAudio(failed.wav, failed.seconds, RecognitionError.UNKNOWN, e.message, engine.id))
        }
        return true
    }

    private fun makeListener(
        engineId: String,
        onPartialOrFinal: (text: String, isFinal: Boolean) -> Unit,
        onError: (RecognitionError, String?) -> Unit,
        onFailedAudio: ((FailedAudio) -> Unit)?,
    ): SpeechRecognitionEngine.Listener {
        var retained: ByteArray? = null
        return object : SpeechRecognitionEngine.Listener {
            override fun onReadyForSpeech() {
                setState(RecognitionState.RECORDING)
            }

            override fun onPartial(text: String) {
                _recognizedText.value = text
                onPartialOrFinal(text, false)
            }

            override fun onFinal(text: String) {
                VoicePipelineLog.event("manager.final", "chars" to text.length, "state" to _state.value)
                retained = null
                retryEngine = null
                if (text.isNotEmpty()) _recognizedText.value = text
                onPartialOrFinal(text, true)
                setState(RecognitionState.IDLE)
                resetLevels()
                refreshAvailability()
            }

            override fun onRetainedAudio(wav: ByteArray) {
                retained = wav
            }

            override fun onError(error: RecognitionError, message: String?) {
                VoicePipelineLog.event("manager.error", "kind" to error.name, "state" to _state.value)
                _lastError.value = error
                retryEngine = null
                val audio = retained
                retained = null
                val seconds = audio?.let { VoiceAsrWatchdog.wavSeconds(it) } ?: 0.0
                val failed = if (audio != null && onFailedAudio != null &&
                    VoiceAsrFailurePolicy.shouldPromptRetry(error, seconds)
                ) FailedAudio(audio, seconds, error, message, engineId) else null
                if (failed != null) onFailedAudio?.invoke(failed)
                setState(RecognitionState.IDLE)
                resetLevels()
                if (failed == null) onError(error, message)
                refreshAvailability()
            }

            override fun onRmsDb(rms: Float) { pushLevel(rms) }
        }
    }

    fun stopRecording() {
        if (_state.value != RecognitionState.RECORDING && _state.value != RecognitionState.STARTING) {
            VoicePipelineLog.event("manager.stop.ignored", "state" to _state.value)
            return
        }
        VoicePipelineLog.event("manager.stop", "state" to _state.value)
        setState(RecognitionState.FINISHING)
        currentEngine()?.stop()
    }

    fun cancelRecording() {
        VoicePipelineLog.event("manager.cancel", "state" to _state.value)
        retryEngine?.let { if (it !== currentEngine()) it.cancel() }
        retryEngine = null
        currentEngine()?.cancel()
        setState(RecognitionState.IDLE)
        _recognizedText.value = ""
    }
}
