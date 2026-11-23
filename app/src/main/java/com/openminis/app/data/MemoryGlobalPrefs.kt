package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences

object MemoryGlobalPrefs {
    private const val PREFS = "minis_memory_prefs"
    private const val KEY_GLOBAL_ENABLED = "memory.global.enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isGlobalEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GLOBAL_ENABLED, true)

    fun setGlobalEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GLOBAL_ENABLED, enabled).apply()
    }
}
