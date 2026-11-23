package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences

object AutoCompactPrefs {
    private const val PREFS = "minis_auto_compact_prefs"
    private const val KEY_ENABLED = "autoCompactOnThreshold"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedEnabled: Boolean = false

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun prime(context: Context) {
        appContext = context.applicationContext
        cachedEnabled = prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun isEnabled(): Boolean = cachedEnabled

    fun setEnabled(context: Context, enabled: Boolean) {
        cachedEnabled = enabled
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setEnabled(enabled: Boolean) {
        cachedEnabled = enabled
        appContext?.let { prefs(it).edit().putBoolean(KEY_ENABLED, enabled).apply() }
    }
}
