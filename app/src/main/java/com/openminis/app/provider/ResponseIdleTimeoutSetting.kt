package com.openminis.app.provider

import android.content.Context

object ResponseIdleTimeoutSetting {
    const val DEFAULT_SECONDS = 120
    const val MIN_SECONDS = 30
    const val MAX_SECONDS = 3600
    val PRESETS = listOf(120, 300, 600, 1800, 3600)

    const val SOCKET_READ_DEFAULT_SECONDS = 600

    private const val PREFS = "provider_response_idle_timeout"

    fun clamp(seconds: Int): Int = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

    fun key(instanceId: String): String = "provider.responseIdleTimeout.$instanceId"

    fun seconds(context: Context, instanceId: String?): Int {
        if (instanceId.isNullOrEmpty()) return DEFAULT_SECONDS
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains(key(instanceId))) return DEFAULT_SECONDS
        return clamp(p.getInt(key(instanceId), DEFAULT_SECONDS))
    }

    fun set(context: Context, instanceId: String, seconds: Int?) {
        val e = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (seconds == null || clamp(seconds) == DEFAULT_SECONDS) e.remove(key(instanceId)) else e.putInt(key(instanceId), clamp(seconds))
        e.apply()
    }

    fun readTimeoutSeconds(idleSeconds: Int): Int? =
        if (idleSeconds > SOCKET_READ_DEFAULT_SECONDS) idleSeconds + 30 else null
}
