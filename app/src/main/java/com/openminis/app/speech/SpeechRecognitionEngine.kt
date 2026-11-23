package com.openminis.app.speech

import java.util.Locale

interface SpeechRecognitionEngine {

    val id: String

    val displayName: String

    val isAvailable: Boolean

    val supportsPartialResults: Boolean

    val supportedLocales: List<Locale>

    fun start(locale: Locale, listener: Listener)

    fun stop()

    fun cancel()

    fun markDegraded() {}

    fun clearDegraded() {}

    fun transcribeRetained(wav: ByteArray, locale: Locale, listener: Listener) {
        listener.onError(RecognitionError.UNKNOWN, "This recognizer cannot re-transcribe saved audio.")
    }

    interface Listener {
        fun onPartial(text: String)

        fun onFinal(text: String)

        fun onError(error: RecognitionError, message: String? = null)

        fun onRmsDb(rms: Float) {}

        fun onReadyForSpeech() {}

        fun onRetainedAudio(wav: ByteArray) {}
    }
}

enum class RecognitionError {
    NO_MATCH,

    TRANSCRIPTION_FAILED,

    NETWORK,

    PERMISSION_DENIED,

    OEM_NO_SERVICE,

    RECOGNIZER_BUSY,

    LANGUAGE_UNSUPPORTED,

    AUDIO_ERROR,

    MIC_IN_USE,

    TIMED_OUT,

    UNKNOWN,
}

enum class RecognitionState {
    IDLE,

    STARTING,

    RECORDING,

    FINISHING,
}
