package com.openminis.app.offload

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

enum class RepeatMode { ONCE, DAILY, WEEKDAYS }

class AlarmOffloadManager(private val context: Context) {

    companion object {
        private const val TAG = "AlarmOffloadManager"
        private const val PREFS_NAME = "minis_alarms_prefs"
        private const val KEY_ALARMS = "alarms_json"
        const val CHANNEL_ID = "minis_alarms"
        private const val CHANNEL_NAME = "Minis Alarms & Timers"

        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_ALARM_LABEL = "alarm_label"
    }

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alarms and timers scheduled by the Minis agent"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }


    fun scheduleAlarm(label: String, hour: Int, minute: Int, repeatMode: RepeatMode): String {
        val id = UUID.randomUUID().toString()
        val requestCode = id.hashCode() and 0x7FFFFFFF

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val pendingIntent = buildPendingIntent(id, requestCode, label)

        try {
            when (repeatMode) {
                RepeatMode.ONCE -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent,
                    )
                }
                RepeatMode.DAILY -> {
                    alarmManager.setRepeating(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        AlarmManager.INTERVAL_DAY,
                        pendingIntent,
                    )
                }
                RepeatMode.WEEKDAYS -> {
                    skipToNextWeekday(calendar)
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent,
                    )
                }
            }
        } catch (e: SecurityException) {
            AppLogger.error(TAG, "Cannot schedule exact alarm – missing SCHEDULE_EXACT_ALARM permission: ${e.message}")
            return "Error: exact alarm permission not granted. Please allow exact alarms in system settings."
        }

        val entry = JSONObject().apply {
            put("id", id)
            put("requestCode", requestCode)
            put("label", label)
            put("hour", hour)
            put("minute", minute)
            put("repeatMode", repeatMode.name)
            put("type", "alarm")
            put("triggerAtMs", calendar.timeInMillis)
        }
        persistAlarm(entry)

        val timeStr = "%02d:%02d".format(hour, minute)
        return "Alarm scheduled: \"$label\" at $timeStr ($repeatMode) [id=$id]"
    }

    fun scheduleTimer(label: String, durationSec: Int): String {
        val id = UUID.randomUUID().toString()
        val requestCode = id.hashCode() and 0x7FFFFFFF
        val triggerAt = System.currentTimeMillis() + durationSec * 1000L

        val pendingIntent = buildPendingIntent(id, requestCode, label)

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent,
            )
        } catch (e: SecurityException) {
            AppLogger.error(TAG, "Cannot schedule exact alarm: ${e.message}")
            return "Error: exact alarm permission not granted."
        }

        val entry = JSONObject().apply {
            put("id", id)
            put("requestCode", requestCode)
            put("label", label)
            put("durationSec", durationSec)
            put("type", "timer")
            put("triggerAtMs", triggerAt)
        }
        persistAlarm(entry)

        return "Timer scheduled: \"$label\" in ${durationSec}s [id=$id]"
    }


    fun listAlarms(): String {
        sweepExpiredOnce()

        val alarms = loadAlarms()
        if (alarms.length() == 0) return "No active alarms or timers."

        val now = System.currentTimeMillis()
        val sb = StringBuilder("Active alarms/timers (${alarms.length()}):\n")
        for (i in 0 until alarms.length()) {
            val a = alarms.getJSONObject(i)
            sb.append("  - [${a.getString("type")}] \"${a.getString("label")}\" id=${a.getString("id")}")
            if (a.has("hour")) {
                sb.append(" at %02d:%02d".format(a.getInt("hour"), a.getInt("minute")))
                sb.append(" (${a.optString("repeatMode", "ONCE")})")
            } else if (a.has("durationSec")) {
                sb.append(" duration=${a.getInt("durationSec")}s")
            }
            val triggerAt = a.optLong("triggerAtMs", 0L)
            val mode = a.optString("repeatMode", "ONCE")
            val nonRepeating = a.getString("type") == "timer" || mode == "ONCE"
            if (nonRepeating && triggerAt in 1L..now) {
                sb.append(" [expired]")
            }
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }

    fun sweepExpiredOnce(): Int {
        val alarms = loadAlarms()
        if (alarms.length() == 0) return 0
        val now = System.currentTimeMillis()
        val kept = JSONArray()
        var removed = 0
        for (i in 0 until alarms.length()) {
            val a = alarms.getJSONObject(i)
            val mode = a.optString("repeatMode", "ONCE")
            val nonRepeating = a.optString("type") == "timer" || mode == "ONCE"
            val triggerAt = a.optLong("triggerAtMs", 0L)
            if (nonRepeating && triggerAt in 1L..now) {
                removed++
                continue
            }
            kept.put(a)
        }
        if (removed > 0) {
            prefs.edit().putString(KEY_ALARMS, kept.toString()).apply()
            AppLogger.info(TAG, "swept $removed expired ONCE alarm(s)/timer(s)")
        }
        return removed
    }

    fun onAlarmFired(alarmId: String) {
        val alarms = loadAlarms()
        for (i in 0 until alarms.length()) {
            val a = alarms.getJSONObject(i)
            if (a.optString("id") != alarmId) continue
            val mode = a.optString("repeatMode", "ONCE")
            val nonRepeating = a.optString("type") == "timer" || mode == "ONCE"
            if (!nonRepeating) {
                AppLogger.info(TAG, "alarm id=$alarmId mode=$mode fired — keeping entry (repeats)")
                return
            }
            alarms.remove(i)
            prefs.edit().putString(KEY_ALARMS, alarms.toString()).apply()
            AppLogger.info(TAG, "alarm id=$alarmId mode=$mode fired — removed from prefs")
            return
        }
        AppLogger.warning(TAG, "alarm id=$alarmId fired but no matching prefs entry")
    }

    fun cancelAlarm(alarmId: String): String {
        val alarms = loadAlarms()
        var found = false

        for (i in 0 until alarms.length()) {
            val a = alarms.getJSONObject(i)
            if (a.getString("id") == alarmId) {
                val requestCode = a.getInt("requestCode")
                val label = a.getString("label")
                val pi = buildPendingIntent(alarmId, requestCode, label)
                alarmManager.cancel(pi)
                pi.cancel()
                alarms.remove(i)
                found = true
                break
            }
        }

        if (!found) return "Error: no alarm found with id=$alarmId"

        prefs.edit().putString(KEY_ALARMS, alarms.toString()).apply()
        return "Alarm cancelled: id=$alarmId"
    }

    fun cancelAllAlarms(): Int {
        val alarms = loadAlarms()
        val n = alarms.length()
        for (i in 0 until n) {
            val a = alarms.getJSONObject(i)
            val id = a.optString("id")
            val requestCode = a.optInt("requestCode")
            val label = a.optString("label", "Alarm")
            if (id.isEmpty() || requestCode == 0) continue
            val pi = buildPendingIntent(id, requestCode, label)
            alarmManager.cancel(pi)
            pi.cancel()
        }
        prefs.edit().remove(KEY_ALARMS).apply()
        return n
    }


    private fun buildPendingIntent(id: String, requestCode: Int, label: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, id)
            putExtra(EXTRA_ALARM_LABEL, label)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun skipToNextWeekday(cal: Calendar) {
        while (true) {
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            if (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY) break
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
    }

    private fun loadAlarms(): JSONArray {
        val raw = prefs.getString(KEY_ALARMS, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun persistAlarm(entry: JSONObject) {
        val alarms = loadAlarms()
        alarms.put(entry)
        prefs.edit().putString(KEY_ALARMS, alarms.toString()).apply()
    }
}
