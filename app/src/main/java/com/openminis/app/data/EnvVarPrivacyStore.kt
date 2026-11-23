package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object EnvVarPrivacyStore {
    private const val PREFS = "envvar_privacy"
    private const val KEY = "privacy_mode_enabled"
    private const val DEFAULT_ENABLED = true

    private var prefs: SharedPreferences? = null

    private val _enabled = MutableStateFlow(DEFAULT_ENABLED)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    val isEnabled: Boolean get() = _enabled.value

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _enabled.value = if (p.contains(KEY)) p.getBoolean(KEY, DEFAULT_ENABLED) else DEFAULT_ENABLED
    }

    fun setEnabled(value: Boolean) {
        prefs?.edit()?.putBoolean(KEY, value)?.apply()
        _enabled.value = value
    }
}
