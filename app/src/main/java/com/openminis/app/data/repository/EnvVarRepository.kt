package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class EnvVarRepository internal constructor(
    private val dir: File,
    private val values: ValueStore,
) {

    interface ValueStore {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun remove(key: String)
    }

    constructor(context: Context) : this(context.filesDir, EncryptedValueStore(context))

    private class EncryptedValueStore(private val context: Context) : ValueStore {
        private val prefs: SharedPreferences by lazy {
            com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, ENCRYPTED_PREFS_NAME)
        }
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
        override fun remove(key: String) { prefs.edit().remove(key).apply() }
    }

    companion object {
        private const val TAG = "EnvVarRepository"
        internal const val METADATA_FILE = "env-vars.json"
        private const val ENCRYPTED_PREFS_NAME = "env_var_values"
        private val KEY_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*$")
    }

    data class EnvVarEntry(
        val id: String = UUID.randomUUID().toString(),
        val key: String,
        val note: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        val enabled: Boolean = true,
    )

    private val _entries = MutableStateFlow<List<EnvVarEntry>>(emptyList())
    val entries: StateFlow<List<EnvVarEntry>> = _entries.asStateFlow()

    private val states = EnvVarStateStore(dir)

    private val metadataFile: File
        get() = File(dir, METADATA_FILE)

    init {
        loadMetadata()
    }


    fun isValidKey(key: String): Boolean = KEY_REGEX.matches(key)

    private fun sanitizeValue(value: String): String =
        value.filter { ch ->
            val c = ch.code
            (c in 0x20..0x7E) || c == 0x09
        }

    fun isDuplicateKey(key: String, excludeId: String? = null): Boolean =
        _entries.value.any { it.key.equals(key, ignoreCase = true) && it.id != excludeId }

    fun duplicateKeys(): Set<String> =
        _entries.value.groupBy { it.key.uppercase() }.filterValues { it.size > 1 }.keys


    fun add(key: String, value: String, note: String = "", enabled: Boolean = true): Boolean {
        val normalizedKey = key.trim().uppercase()
        if (!isValidKey(normalizedKey)) return false
        if (isDuplicateKey(normalizedKey)) return false

        val entry = EnvVarEntry(key = normalizedKey, note = note.trim(), enabled = enabled)
        _entries.value = _entries.value + entry
        values.put(normalizedKey, sanitizeValue(value))
        saveMetadata()
        if (!enabled) states.setEnabled(entry.id, normalizedKey, false)
        Log.i(TAG, "Added env var: $normalizedKey enabled=$enabled")
        return true
    }

    fun update(id: String, newKey: String, newValue: String, newNote: String = ""): Boolean {
        val normalizedKey = newKey.trim().uppercase()
        if (!isValidKey(normalizedKey)) return false

        val current = _entries.value.find { it.id == id } ?: return false

        if (isDuplicateKey(normalizedKey, excludeId = id)) return false

        if (current.key != normalizedKey) {
            values.remove(current.key)
        }

        _entries.value = _entries.value.map {
            if (it.id == id) it.copy(key = normalizedKey, note = newNote.trim()) else it
        }

        values.put(normalizedKey, sanitizeValue(newValue))
        saveMetadata()
        states.renamed(id, normalizedKey)
        Log.i(TAG, "Updated env var: ${current.key} → $normalizedKey")
        return true
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val current = _entries.value.find { it.id == id } ?: return false
        if (current.enabled == enabled && states.state(id) != null) return true
        states.setEnabled(id, current.key, enabled)
        _entries.value = _entries.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        Log.i(TAG, "Env var ${current.key} enabled=$enabled")
        return true
    }

    fun delete(id: String) {
        val entry = _entries.value.find { it.id == id } ?: return
        values.remove(entry.key)
        _entries.value = _entries.value.filter { it.id != id }
        saveMetadata()
        states.remove(id)
        Log.i(TAG, "Deleted env var: ${entry.key}")
    }

    fun getValue(key: String): String? = values.get(key)

    fun isDisabled(key: String): Boolean =
        _entries.value.any { it.key.equals(key, ignoreCase = true) && !it.enabled }

    fun allAsDict(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (entry in _entries.value) {
            if (!entry.enabled) continue
            val value = values.get(entry.key)
            if (value != null) {
                result[entry.key] = value
            }
        }
        return result
    }

    fun allValuesForRedaction(): List<String> =
        _entries.value.mapNotNull { values.get(it.key) }


    private fun saveMetadata() {
        try {
            val array = JSONArray()
            for (entry in _entries.value) {
                val obj = JSONObject()
                obj.put("id", entry.id)
                obj.put("key", entry.key)
                if (entry.note.isNotEmpty()) obj.put("note", entry.note)
                obj.put("createdAt", entry.createdAt)
                array.put(obj)
            }
            metadataFile.writeText(array.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata: ${e.message}")
        }
    }

    private fun loadMetadata() {
        try {
            if (!metadataFile.exists()) {
                states.prune(emptySet())
                return
            }
            val array = JSONArray(metadataFile.readText())
            val entries = mutableListOf<EnvVarEntry>()
            var mintedIds = false
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id", "").ifEmpty { mintedIds = true; UUID.randomUUID().toString() }
                entries.add(EnvVarEntry(
                    id = id,
                    key = obj.optString("key", ""),
                    note = obj.optString("note", ""),
                    createdAt = obj.optLong("createdAt", 0),
                    enabled = states.isEnabled(id),
                ))
            }
            _entries.value = entries
            if (mintedIds) saveMetadata()
            states.prune(entries.map { it.id }.toSet())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load metadata: ${e.message}")
        }
    }
}
