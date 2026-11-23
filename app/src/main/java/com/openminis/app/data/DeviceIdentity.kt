package com.openminis.app.data

import android.content.Context
import android.os.Build
import java.util.UUID

object DeviceIdentity {
    private const val PREFS_NAME = "minis_device_identity"
    private const val KEY_DEVICE_ID = "deviceId"

    @Volatile private var cached: String? = null

    fun deviceId(context: Context): String {
        cached?.let { return it }
        val prefs = encryptedPrefs(context)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) {
            cached = existing
            return existing
        }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
        cached = fresh
        return fresh
    }

    fun deviceName(context: Context): String {
        val shortId = deviceId(context).takeLast(4).uppercase()
        return "${displayName(context)} · $shortId"
    }


    private const val NAME_PREFS = "minis_device_name"
    const val CUSTOM_NAME_KEY = "device.customName"
    const val CUSTOM_NAME_MAX_LENGTH = 48

    fun customName(context: Context): String? =
        context.getSharedPreferences(NAME_PREFS, Context.MODE_PRIVATE)
            .getString(CUSTOM_NAME_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setCustomName(context: Context, value: String?) {
        val trimmed = value?.trim().orEmpty()
        val prefs = context.getSharedPreferences(NAME_PREFS, Context.MODE_PRIVATE).edit()
        if (trimmed.isEmpty()) prefs.remove(CUSTOM_NAME_KEY)
        else prefs.putString(CUSTOM_NAME_KEY, trimmed.take(CUSTOM_NAME_MAX_LENGTH))
        prefs.apply()
    }

    fun automaticName(context: Context): String {
        val system = runCatching {
            android.provider.Settings.Global.getString(
                context.contentResolver, android.provider.Settings.Global.DEVICE_NAME,
            )
        }.getOrNull()?.trim()
        if (!system.isNullOrEmpty()) return system
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.titlecase() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    }

    fun displayName(context: Context): String = customName(context) ?: automaticName(context)

    fun osVersion(): String = "Android ${Build.VERSION.RELEASE}"

    fun zoneName(context: Context): String = "device-${deviceId(context)}"

    private fun encryptedPrefs(context: Context) =
        com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, PREFS_NAME)
}
