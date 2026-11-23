package com.openminis.app.scheduled

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONArray

class ScheduledTaskStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun all(): List<ScheduledTask> {
        val raw = prefs.getString(KEY_TASKS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    runCatching { ScheduledTask.fromJson(o) }
                        .onSuccess { add(it) }
                        .onFailure { AppLogger.warning(TAG, "skip malformed row: ${it.message}") }
                }
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "load failed: ${t.message}")
            emptyList()
        }
    }

    fun get(taskId: String): ScheduledTask? = all().firstOrNull { it.id == taskId }

    fun upsert(task: ScheduledTask): Unit = synchronized(LOCK) {
        val current = all().filter { it.id != task.id }
        write(current + task)
    }

    fun delete(taskId: String): Unit = synchronized(LOCK) {
        write(all().filter { it.id != taskId })
    }

    fun update(taskId: String, transform: (ScheduledTask) -> ScheduledTask?): ScheduledTask? = synchronized(LOCK) {
        val tasks = all()
        val cur = tasks.firstOrNull { it.id == taskId } ?: return null
        val next = transform(cur) ?: return null
        write(tasks.map { if (it.id == taskId) next else it })
        next
    }

    fun clear() {
        prefs.edit().remove(KEY_TASKS).apply()
    }

    private fun write(tasks: List<ScheduledTask>) {
        val arr = JSONArray()
        for (t in tasks) arr.put(t.toJson())
        prefs.edit().putString(KEY_TASKS, arr.toString()).apply()
    }

    fun observe(): Flow<List<ScheduledTask>> = callbackFlow {
        trySend(all())
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_TASKS || key == null) {
                trySend(all())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun showHomeBadge(): Boolean = prefs.getBoolean(KEY_SHOW_HOME_BADGE, true)

    fun setShowHomeBadge(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_HOME_BADGE, show).apply()
    }

    fun observeShowHomeBadge(): Flow<Boolean> = callbackFlow {
        trySend(showHomeBadge())
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_SHOW_HOME_BADGE || key == null) {
                trySend(showHomeBadge())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        private const val TAG = "ScheduledTaskStore"

        fun homeBadgeCount(enabledTaskCount: Int, showBadge: Boolean): Int? =
            enabledTaskCount.takeIf { showBadge && it > 0 }

        private val LOCK = Any()
        private const val PREFS_NAME = "minis_scheduled_tasks_prefs"
        private const val KEY_TASKS = "tasks_json"
        private const val KEY_SHOW_HOME_BADGE = "show_home_badge"
    }
}
