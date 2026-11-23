package com.openminis.app.config

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MinisConfigPermissionStore {
    private const val PREFS = "minis_config_permission"
    private const val KEY = "minis_config_enabled"

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
