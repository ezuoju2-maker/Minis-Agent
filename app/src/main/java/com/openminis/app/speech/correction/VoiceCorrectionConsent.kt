package com.openminis.app.speech.correction

import android.content.Context

object VoiceCorrectionConsent {

    private const val PREFS = "voice_correction_prefs"

    private const val KEY_ENABLED = "voiceCorrectionCollectionEnabled"
    private const val KEY_PROMPTED = "voiceCorrectionCollectionPrompted"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun hasPrompted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PROMPTED, false)

    fun setPrompted(context: Context, prompted: Boolean) {
        prefs(context).edit().putBoolean(KEY_PROMPTED, prompted).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
