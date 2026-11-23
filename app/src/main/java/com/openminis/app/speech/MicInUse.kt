package com.openminis.app.speech

import android.content.Context
import android.media.AudioManager
import android.os.Build

object MicInUse {

    fun classify(callActive: Boolean, silenced: Boolean): RecognitionError =
        if (callActive || silenced) RecognitionError.MIC_IN_USE else RecognitionError.AUDIO_ERROR

    fun captureFailure(context: Context, audioSessionId: Int? = null): RecognitionError =
        classify(isCallActive(context), audioSessionId?.let { isSilenced(context, it) } == true)

    internal fun isCallMode(mode: Int): Boolean = when (mode) {
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION,
        4 ,
        5 ,
        6 ,
        -> true
        else -> false
    }

    fun isCallActive(context: Context): Boolean = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        isCallMode(am.mode)
    }.getOrDefault(false)

    fun isSilenced(context: Context, audioSessionId: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            am.activeRecordingConfigurations.any { it.clientAudioSessionId == audioSessionId && it.isClientSilenced }
        }.getOrDefault(false)
    }
}
