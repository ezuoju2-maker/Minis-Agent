package com.openminis.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppBuildInfo {
    private const val ASSET = "build_info/build_time.txt"

    @Volatile
    private var cached: Long? = null

    fun buildTimeMillis(context: Context): Long? {
        cached?.let { return it }
        val value = try {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }.trim().toLongOrNull()
        } catch (_: Exception) {
            null
        }
        if (value != null) cached = value
        return value
    }

    fun commit(context: Context): String = try {
        context.assets.open("build_info/commit.txt").bufferedReader().use { it.readText() }.trim().ifEmpty { "unknown" }
    } catch (_: Exception) {
        "unknown"
    }

    fun buildDate(context: Context): String {
        val millis = buildTimeMillis(context) ?: return "unknown"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
    }
}
