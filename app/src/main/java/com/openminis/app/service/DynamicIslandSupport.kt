package com.openminis.app.service

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log

object DynamicIslandSupport {

    private const val TAG = "DynamicIslandSupport"

    @Volatile
    private var capableCache: Boolean? = null

    @Volatile
    private var capableCachedAtMs: Long = 0L

    private const val CAPABILITY_TTL_MS = 5_000L

    fun invalidateCapabilityCache() {
        capableCache = null
    }

    private fun cacheCapability(capable: Boolean): Boolean {
        capableCachedAtMs = android.os.SystemClock.elapsedRealtime()
        capableCache = capable
        return capable
    }

    fun isDynamicIslandCapable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        capableCache?.let { cached ->
            val age = android.os.SystemClock.elapsedRealtime() - capableCachedAtMs
            if (age in 0 until CAPABILITY_TTL_MS) return cached
        }
        return try {
            val nm = context.getSystemService(NotificationManager::class.java)
            cacheCapability(nm?.canPostPromotedNotifications() == true)
        } catch (t: Throwable) {
            Log.w(TAG, "canPostPromotedNotifications() failed: ${t.message}")
            cacheCapability(false)
        }
    }

    fun isDynamicIslandActive(context: Context, userEnabled: Boolean): Boolean =
        userEnabled && isDynamicIslandCapable(context)
}
