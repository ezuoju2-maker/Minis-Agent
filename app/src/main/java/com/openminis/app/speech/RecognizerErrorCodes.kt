package com.openminis.app.speech

import android.speech.SpeechRecognizer

object RecognizerErrorCodes {
    const val TOO_MANY_REQUESTS = 10
    const val SERVER_DISCONNECTED = 11
    const val LANGUAGE_NOT_SUPPORTED = 12
    const val LANGUAGE_UNAVAILABLE = 13
    const val CANNOT_CHECK_SUPPORT = 14
    const val CANNOT_LISTEN_TO_DOWNLOAD_EVENTS = 15

    fun name(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
        SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
        LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
        LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
        CANNOT_CHECK_SUPPORT -> "ERROR_CANNOT_CHECK_SUPPORT"
        CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
        else -> "ERROR_UNKNOWN_$code"
    }

    fun isLanguageError(code: Int): Boolean =
        code == LANGUAGE_NOT_SUPPORTED || code == LANGUAGE_UNAVAILABLE

    fun classify(code: Int): RecognitionError = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RecognitionError.NO_MATCH
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> RecognitionError.NETWORK
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> RecognitionError.PERMISSION_DENIED
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> RecognitionError.RECOGNIZER_BUSY
        SpeechRecognizer.ERROR_AUDIO -> RecognitionError.AUDIO_ERROR
        SpeechRecognizer.ERROR_CLIENT -> RecognitionError.OEM_NO_SERVICE
        SpeechRecognizer.ERROR_SERVER -> RecognitionError.NETWORK
        LANGUAGE_NOT_SUPPORTED, LANGUAGE_UNAVAILABLE -> RecognitionError.LANGUAGE_UNSUPPORTED
        else -> RecognitionError.UNKNOWN
    }

    fun describe(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client error: the system could not connect DeepSeek to a speech recognition service"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "RECORD_AUDIO required"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
        TOO_MANY_REQUESTS -> "Too many recognition requests - the system recognizer service is saturated"
        SERVER_DISCONNECTED -> "The recognition service disconnected"
        LANGUAGE_NOT_SUPPORTED -> "Language not supported by the recognizer"
        LANGUAGE_UNAVAILABLE -> "Language pack unavailable - download it in the system speech settings"
        CANNOT_CHECK_SUPPORT -> "The recognizer cannot report which languages it supports"
        else -> "Unknown error ($code)"
    }
}
