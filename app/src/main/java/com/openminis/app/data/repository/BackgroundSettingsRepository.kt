package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BackgroundSettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _taskNotificationsEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_TASK_NOTIFICATIONS, DEFAULT_TASK_NOTIFICATIONS))

    val taskNotificationsEnabled: StateFlow<Boolean> =
        _taskNotificationsEnabled.asStateFlow()

    fun setTaskNotificationsEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_TASK_NOTIFICATIONS, value).apply()
        _taskNotificationsEnabled.value = value
    }

    private val _backgroundOverlayEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_BG_OVERLAY_ENABLED, false))
    val backgroundOverlayEnabled: StateFlow<Boolean> =
        _backgroundOverlayEnabled.asStateFlow()

    fun setBackgroundOverlayEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_BG_OVERLAY_ENABLED, value).apply()
        _backgroundOverlayEnabled.value = value
    }

    private val _dynamicIslandEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC_ISLAND_ENABLED, false))
    val dynamicIslandEnabled: StateFlow<Boolean> =
        _dynamicIslandEnabled.asStateFlow()

    fun setDynamicIslandEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_ISLAND_ENABLED, value).apply()
        _dynamicIslandEnabled.value = value
    }

    private val _scheduledCountdownLead =
        MutableStateFlow(readScheduledCountdownLead(prefs))
    val scheduledCountdownLead: StateFlow<Int> = _scheduledCountdownLead.asStateFlow()

    fun setScheduledCountdownLead(index: Int) {
        val safe = index.takeIf { it in SCHEDULED_COUNTDOWN_CASES.indices } ?: DEFAULT_SCHEDULED_COUNTDOWN_LEAD
        prefs.edit().putInt(KEY_SCHEDULED_COUNTDOWN_LEAD, safe).apply()
        _scheduledCountdownLead.value = safe
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        when (key) {
            KEY_TASK_NOTIFICATIONS ->
                _taskNotificationsEnabled.value = p.getBoolean(key, DEFAULT_TASK_NOTIFICATIONS)
            KEY_BG_OVERLAY_ENABLED -> _backgroundOverlayEnabled.value = p.getBoolean(key, false)
            KEY_DYNAMIC_ISLAND_ENABLED -> _dynamicIslandEnabled.value = p.getBoolean(key, false)
            KEY_SCHEDULED_COUNTDOWN_LEAD -> _scheduledCountdownLead.value = readScheduledCountdownLead(p)
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    fun getOverlayPosition(landscape: Boolean = false): Pair<Int, Int>? {
        val (kx, ky) = overlayKeys(landscape)
        if (!prefs.contains(kx) || !prefs.contains(ky)) return null
        return prefs.getInt(kx, 0) to prefs.getInt(ky, 0)
    }

    fun setOverlayPosition(x: Int, y: Int, landscape: Boolean = false) {
        val (kx, ky) = overlayKeys(landscape)
        prefs.edit().putInt(kx, x).putInt(ky, y).apply()
    }

    private fun overlayKeys(landscape: Boolean): Pair<String, String> =
        if (landscape) KEY_BG_OVERLAY_LAND_X to KEY_BG_OVERLAY_LAND_Y else KEY_BG_OVERLAY_X to KEY_BG_OVERLAY_Y

    companion object {
        internal const val PREFS_NAME = "background_settings"
        internal const val KEY_TASK_NOTIFICATIONS = "taskNotificationsEnabled"
        internal const val DEFAULT_TASK_NOTIFICATIONS = true
        private const val KEY_BG_OVERLAY_ENABLED = "backgroundOverlayEnabled"
        private const val KEY_BG_OVERLAY_X = "backgroundOverlayX"
        private const val KEY_BG_OVERLAY_Y = "backgroundOverlayY"
        private const val KEY_BG_OVERLAY_LAND_X = "backgroundOverlayLandscapeX"
        private const val KEY_BG_OVERLAY_LAND_Y = "backgroundOverlayLandscapeY"
        internal const val KEY_DYNAMIC_ISLAND_ENABLED = "dynamicIslandEnabled"

        internal const val KEY_SCHEDULED_COUNTDOWN_LEAD = "scheduledCountdownLead"

        internal val SCHEDULED_COUNTDOWN_CASES = listOf("off", "1m", "5m", "15m", "always")
        internal const val DEFAULT_SCHEDULED_COUNTDOWN_LEAD = 1

        internal fun readScheduledCountdownLead(p: SharedPreferences): Int =
            p.getInt(KEY_SCHEDULED_COUNTDOWN_LEAD, DEFAULT_SCHEDULED_COUNTDOWN_LEAD)
                .takeIf { it in SCHEDULED_COUNTDOWN_CASES.indices } ?: DEFAULT_SCHEDULED_COUNTDOWN_LEAD

        fun scheduledCountdownLeadMs(index: Int): Long = when (index) {
            0 -> 0L
            1 -> 60_000L
            2 -> 5 * 60_000L
            3 -> 15 * 60_000L
            else -> Long.MAX_VALUE
        }
    }
}
