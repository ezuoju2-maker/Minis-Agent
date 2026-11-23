package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences

object EnhancedCachePrefs {
    private const val PREFS = "minis_enhanced_cache_prefs"
    private const val KEY_CONFIRMED = "enhancedCacheConfirmed"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isConfirmed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONFIRMED, false)

    fun setConfirmed(context: Context) {
        prefs(context).edit().putBoolean(KEY_CONFIRMED, true).apply()
    }
}
