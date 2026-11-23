package com.openminis.app.diagnostics

import android.util.Log
import com.openminis.app.BuildConfig

object StreamJitterProbe {
    private const val TAG = "JitterProbe"

    @JvmStatic
    fun publish(kind: String, len: Int) {
        if (!BuildConfig.DEV_TOOLS) return
        Log.d(TAG, "$kind len=$len mono=${System.nanoTime()}")
    }

    @JvmStatic
    fun scroll(source: String, idx: Int) {
        if (!BuildConfig.DEV_TOOLS) return
        Log.d(TAG, "scroll src=$source idx=$idx mono=${System.nanoTime()}")
    }

    @JvmStatic
    fun flatten(ms: Long, frozenReused: Boolean, frozen: Int, live: Int, streamEmpty: Boolean) {
        if (!BuildConfig.DEV_TOOLS) return
        Log.d(
            TAG,
            "flatten ms=$ms frozenReused=$frozenReused frozen=$frozen live=$live " +
                "streamEmpty=$streamEmpty mono=${System.nanoTime()}",
        )
    }

    private val expCache = HashMap<String, Pair<Long, String>>()

    @JvmStatic
    fun experiment(key: String): String {
        if (!BuildConfig.DEV_TOOLS) return ""
        val now = System.nanoTime()
        synchronized(expCache) {
            expCache[key]?.let { (t, v) -> if (now - t < 1_000_000_000L) return v }
            val v = runCatching {
                val cls = Class.forName("android.os.SystemProperties")
                cls.getMethod("get", String::class.java, String::class.java)
                    .invoke(null, "debug.minis.$key", "") as String
            }.getOrDefault("")
            expCache[key] = now to v
            return v
        }
    }
}
