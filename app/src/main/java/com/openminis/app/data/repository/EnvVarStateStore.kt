package com.openminis.app.data.repository

import org.json.JSONObject
import java.io.File

class EnvVarStateStore(private val dir: File) {

    data class State(val key: String, val enabled: Boolean, val enabledUpdatedAt: Long)

    private val file: File get() = File(dir, FILE_NAME)
    private var states: MutableMap<String, State> = LinkedHashMap()

    var unreadable: Boolean = false
        private set

    init {
        load()
    }

    fun isEnabled(id: String): Boolean = states[id]?.enabled ?: true

    fun state(id: String): State? = states[id]

    fun setEnabled(id: String, key: String, enabled: Boolean, now: Long = System.currentTimeMillis()) {
        val previous = states[id]
        val stamp = if (previous != null && now <= previous.enabledUpdatedAt) previous.enabledUpdatedAt + 1 else now
        states[id] = State(key, enabled, stamp)
        save()
    }

    fun renamed(id: String, newKey: String) {
        val s = states[id] ?: return
        if (s.key == newKey) return
        states[id] = s.copy(key = newKey)
        save()
    }

    fun remove(id: String) {
        if (states.remove(id) != null) save()
    }

    fun prune(liveIds: Set<String>) {
        if (unreadable) return
        val before = states.size
        states.keys.retainAll(liveIds)
        if (states.size != before) save()
    }

    private fun load() {
        val f = file
        if (!f.exists()) return
        try {
            val root = JSONObject(f.readText())
            val obj = root.optJSONObject("states") ?: JSONObject()
            val loaded = LinkedHashMap<String, State>()
            for (id in obj.keys()) {
                val s = obj.optJSONObject(id) ?: continue
                loaded[id] = State(
                    key = s.optString("key", ""),
                    enabled = s.optBoolean("enabled", true),
                    enabledUpdatedAt = s.optLong("enabledUpdatedAt", 0L),
                )
            }
            states = loaded
        } catch (e: Exception) {
            unreadable = true
        }
    }

    private fun save() {
        if (unreadable) return
        val obj = JSONObject()
        for ((id, s) in states) {
            obj.put(id, JSONObject().apply {
                put("key", s.key)
                put("enabled", s.enabled)
                put("enabledUpdatedAt", s.enabledUpdatedAt)
            })
        }
        val root = JSONObject().apply {
            put("version", 1)
            put("states", obj)
        }
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(root.toString())
            tmp.delete()
        }
    }

    companion object {
        const val FILE_NAME = "env-vars-state.json"
    }
}
