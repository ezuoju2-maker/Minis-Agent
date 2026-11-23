package com.openminis.app.ui.chat

import org.json.JSONObject

internal object ToolDurationWire {
    const val KEY = "duration"

    fun snapshotField(durationMs: Long?): String =
        if (durationMs == null || durationMs <= 0) "" else ",\"$KEY\":${durationMs / 1000.0}"

    fun restoreMs(value: JSONObject?): Long {
        val snapshot = value?.optJSONObject("snapshot") ?: return 0L
        if (!snapshot.has(KEY) || snapshot.isNull(KEY)) return 0L
        val seconds = snapshot.optDouble(KEY, Double.NaN)
        if (seconds.isNaN() || seconds.isInfinite() || seconds <= 0) return 0L
        return Math.round(seconds * 1000)
    }
}
