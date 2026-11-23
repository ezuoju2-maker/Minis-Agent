package com.openminis.app.ui.chat

import android.content.Context

object ComposerInputModePrefs {

    private const val PREFS = "composer_input_prefs"
    private const val KEY_MODE = "composer_input_mode_pref"

    fun save(context: Context, voice: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, if (voice) "voice" else "text")
            .apply()
    }
}
