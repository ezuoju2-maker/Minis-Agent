package com.openminis.app.ui.chat

import android.content.Context
import com.openminis.app.speech.ReadAloudPlayer

internal class LazyReadAloudPlayer(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var player: ReadAloudPlayer? = null

    fun speak(text: String) {
        if (text.isBlank()) return
        com.openminis.app.speech.VoiceOutputState.activateForExplicitReadAloud()
        val p = player ?: synchronized(this) {
            player ?: ReadAloudPlayer(appContext).also { player = it }
        }
        p.speak(text)
    }

    fun stop() {
        player?.stop()
    }

    fun shutdown() {
        synchronized(this) {
            player?.shutdown()
            player = null
        }
    }
}
