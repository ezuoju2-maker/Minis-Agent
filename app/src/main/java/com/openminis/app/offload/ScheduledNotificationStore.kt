package com.openminis.app.offload

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal class ScheduledNotificationStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun add(entry: JSONObject) {
        val list = loadAll()
        list.put(entry)
        prefs.edit().putString(KEY, list.toString()).apply()
    }

    fun remove(id: String): Boolean {
        val list = loadAll()
        for (i in 0 until list.length()) {
            if (list.getJSONObject(i).optString("id") == id) {
                list.remove(i)
                prefs.edit().putString(KEY, list.toString()).apply()
                return true
            }
        }
        return false
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    fun get(id: String): JSONObject? {
        val list = loadAll()
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (o.optString("id") == id) return o
        }
        return null
    }

    fun loadAll(): JSONArray {
        val raw = prefs.getString(KEY, null) ?: return JSONArray()
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    fun sweepExpired() {
        val list = loadAll()
        val now = System.currentTimeMillis()
        val kept = JSONArray()
        var dropped = false
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (o.optLong("trigger_at_ms", Long.MAX_VALUE) > now) {
                kept.put(o)
            } else {
                dropped = true
            }
        }
        if (dropped) prefs.edit().putString(KEY, kept.toString()).apply()
    }

    companion object {
        private const val PREFS = "minis_scheduled_notifications"
        private const val KEY = "scheduled"
    }
}
