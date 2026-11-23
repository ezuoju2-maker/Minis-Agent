package com.openminis.app.speech

import android.speech.SpeechRecognizer

object RecognizerFallback {
    enum class Kind(val label: String) {
        REGULAR("regular"),
        ON_DEVICE("on_device"),
    }

    fun shouldSwitch(code: Int): Boolean =
        code == SpeechRecognizer.ERROR_CLIENT ||
            code == RecognizerErrorCodes.SERVER_DISCONNECTED ||
            RecognizerErrorCodes.isLanguageError(code)

    fun plan(preferOnDevice: Boolean, sdkInt: Int, onDeviceAvailable: Boolean?): List<Kind> {
        val onDeviceUsable = sdkInt >= 31 && onDeviceAvailable == true
        return when {
            preferOnDevice && onDeviceUsable -> listOf(Kind.ON_DEVICE, Kind.REGULAR)
            onDeviceUsable -> listOf(Kind.REGULAR, Kind.ON_DEVICE)
            else -> listOf(Kind.REGULAR)
        }
    }

    fun next(plan: List<Kind>, index: Int, code: Int): Kind? =
        if (shouldSwitch(code)) plan.getOrNull(index + 1) else null
}
