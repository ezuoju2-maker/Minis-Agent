package com.openminis.app.sandbox.offload

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.RepeatMode
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AlarmOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help") || args.positional.isEmpty()) {
            return NativeOffloadResult(if (args.positional.isEmpty()) 2 else 0, HELP)
        }

        return try {
            when (val sub = args.positional[0]) {
                "set", "schedule" -> handleSet(args)
                "timer" -> handleTimer(args)
                "open", "list", "cancel" -> handleOpen(args)
                else -> NativeOffloadResult(2, "android-alarm: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: SecurityException) {
            AppLogger.warning(TAG, "SecurityException: ${e.message}")
            val body = JSONObject().put("error", "exact_alarm_denied")
                .put("message", "Exact alarms are blocked. On Android 14+ the user must grant 'Alarms & reminders' in Settings; on Xiaomi/Huawei/Oppo/OnePlus/Vivo, also enable autostart and disable battery optimization for DeepSeek. Underlying: ${e.message}")
                .toString()
            NativeOffloadResult(77, OffloadOutput.formatBody(body, args) + "\n")
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            val body = JSONObject().put("error", "alarm_failed")
                .put("message", e.message ?: "unknown").toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }


    private fun handleSet(args: OffloadArgs): NativeOffloadResult {
        val timeStr = args.get("time") ?: args.positional.getOrNull(1)
            ?: return NativeOffloadResult(2, "android-alarm set: --time <HH:MM|ISO> is required\n")
        val (hour, minute) = parseTimeArg(timeStr)
            ?: return NativeOffloadResult(
                2,
                "android-alarm: invalid time '$timeStr' (expected HH:MM or ISO 8601)\n",
            )
        val label = args.get("label") ?: "Alarm"
        val repeat = args.get("repeat")?.uppercase() ?: "ONCE"
        val mode = runCatching { RepeatMode.valueOf(repeat) }.getOrNull()
            ?: return NativeOffloadResult(
                2,
                "android-alarm: invalid --repeat '$repeat' (use ONCE, DAILY, WEEKDAYS)\n",
            )
        val systemErr = scheduleViaSystemClock(label, hour, minute, mode)
        if (systemErr != null) {
            val body = JSONObject().put("error", "system_clock_unavailable")
                .put("message", systemErr)
                .put("hint", "Re-enable or install a Clock app (e.g. Google Clock) and grant the SET_ALARM permission.")
                .toString()
            AppLogger.warning(TAG, "set: system Clock dispatch failed — $systemErr")
            return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
        val data = buildSetEnvelope(label, hour, minute, mode)
            .put("system_alarm", "ok")
        AppLogger.info(TAG, "set: time=$hour:$minute repeat=$mode label='$label' → system Clock")
        return emitEnvelope("set", data, args)
    }

    private fun scheduleViaSystemClock(
        label: String,
        hour: Int,
        minute: Int,
        mode: RepeatMode,
    ): String? {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            when (mode) {
                RepeatMode.DAILY -> putExtra(
                    AlarmClock.EXTRA_DAYS,
                    arrayListOf(
                        Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY,
                        Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY,
                        Calendar.SATURDAY,
                    ),
                )
                RepeatMode.WEEKDAYS -> putExtra(
                    AlarmClock.EXTRA_DAYS,
                    arrayListOf(
                        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                        Calendar.THURSDAY, Calendar.FRIDAY,
                    ),
                )
                RepeatMode.ONCE -> Unit
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            null
        } catch (e: ActivityNotFoundException) {
            "No system Clock app handles ACTION_SET_ALARM. Install or re-enable a Clock app."
        } catch (e: SecurityException) {
            "System Clock refused the alarm — SET_ALARM permission missing or revoked: ${e.message}"
        } catch (e: Throwable) {
            "System Clock dispatch failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun handleTimer(args: OffloadArgs): NativeOffloadResult {
        val durationStr = args.get("duration") ?: args.positional.getOrNull(1)
            ?: return NativeOffloadResult(
                2,
                "android-alarm timer: --duration <seconds|5m|1h> is required\n",
            )
        val secs = parseDuration(durationStr)
            ?: return NativeOffloadResult(
                2,
                "android-alarm: invalid duration '$durationStr' (use seconds or shorthand 30s, 5m, 1h, 2d)\n",
            )
        if (secs <= 0) return NativeOffloadResult(2, "android-alarm: duration must be positive\n")
        val label = args.get("label") ?: "Timer"
        val systemErr = startSystemTimer(label, secs)
        if (systemErr != null) {
            val body = JSONObject().put("error", "system_clock_unavailable")
                .put("message", systemErr)
                .put("hint", "Re-enable or install a Clock app (e.g. Google Clock) and grant the SET_ALARM permission.")
                .toString()
            AppLogger.warning(TAG, "timer: system Clock dispatch failed — $systemErr")
            return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
        val data = buildTimerEnvelope(label, secs)
            .put("system_alarm", "ok")
        AppLogger.info(TAG, "timer: duration=${secs}s label='$label' → system Clock")
        return emitEnvelope("timer", data, args)
    }

    private fun startSystemTimer(label: String, durationSec: Int): String? {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, durationSec)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            null
        } catch (e: ActivityNotFoundException) {
            "No system Clock app handles ACTION_SET_TIMER. Install or re-enable a Clock app."
        } catch (e: SecurityException) {
            "System Clock refused the timer — SET_ALARM permission missing or revoked: ${e.message}"
        } catch (e: Throwable) {
            "System Clock dispatch failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun handleOpen(args: OffloadArgs): NativeOffloadResult {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            AppLogger.info(TAG, "open: launched system Clock SHOW_ALARMS")
            val data = JSONObject()
                .put("opened", true)
                .put("hint", "System Clock launched. Tell the user to view, edit, or cancel alarms in the Clock app's Alarms tab (or Timers tab for timers). DeepSeek cannot enumerate or cancel alarms programmatically — Android's Clock API is fire-and-forget.")
            emitEnvelope("open", data, args)
        } catch (e: ActivityNotFoundException) {
            val body = JSONObject().put("error", "no_clock_app")
                .put("message", "No Clock app handles ACTION_SHOW_ALARMS. Install or re-enable a Clock app to manage alarms.")
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        } catch (e: Throwable) {
            val body = JSONObject().put("error", "open_failed")
                .put("message", e.message ?: e.javaClass.simpleName)
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    private fun parseHHMM(s: String): Pair<Int, Int>? {
        val parts = s.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h to m
    }

    private fun parseTimeArg(s: String): Pair<Int, Int>? {
        parseHHMM(s)?.let { return it }
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
        )
        for (pat in patterns) {
            val date = runCatching {
                val sdf = SimpleDateFormat(pat, Locale.US)
                if (pat.endsWith("'Z'")) sdf.timeZone = TimeZone.getTimeZone("UTC")
                sdf.parse(s)
            }.getOrNull() ?: continue
            val cal = Calendar.getInstance().apply { time = date }
            return cal.get(Calendar.HOUR_OF_DAY) to cal.get(Calendar.MINUTE)
        }
        return null
    }

    private fun parseDuration(s: String): Int? {
        val trimmed = s.trim().lowercase()
        if (trimmed.isEmpty()) return null
        trimmed.toIntOrNull()?.let { return it }
        if (trimmed.length < 2) return null
        val unit = trimmed.last()
        val num = trimmed.dropLast(1).toIntOrNull() ?: return null
        return when (unit) {
            's' -> num
            'm' -> num * 60
            'h' -> num * 3600
            'd' -> num * 86400
            else -> null
        }
    }

    private fun formatIso(date: Date): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        return sdf.format(date)
    }

    private fun buildSetEnvelope(
        label: String,
        hour: Int,
        minute: Int,
        mode: RepeatMode,
    ): JSONObject {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_YEAR, 1)
        }
        return JSONObject().apply {
            put("label", label)
            put("time", formatIso(cal.time))
            put("hour", hour)
            put("minute", minute)
            put("repeat", mode.name)
            put("view_url", VIEW_URL)
            put("hint", "Alarm saved to the system Clock app. Open the Clock app, or $VIEW_URL inside DeepSeek, to view or cancel.")
        }
    }

    private fun buildTimerEnvelope(label: String, durationSec: Int): JSONObject {
        val firesAt = Date(System.currentTimeMillis() + durationSec * 1000L)
        return JSONObject().apply {
            put("label", label)
            put("duration_seconds", durationSec)
            put("fires_at", formatIso(firesAt))
            put("view_url", VIEW_URL)
            put("hint", "Timer started in the system Clock app. Open the Clock app's Timer tab, or $VIEW_URL inside DeepSeek, to view or stop.")
        }
    }

    private fun emitEnvelope(action: String, data: JSONObject, args: OffloadArgs): NativeOffloadResult {
        val needsHint = OsCompat.isXiaomi || OsCompat.isHuawei ||
            Build.MANUFACTURER.equals("OPPO", ignoreCase = true) ||
            Build.MANUFACTURER.equals("OnePlus", ignoreCase = true) ||
            Build.MANUFACTURER.equals("Vivo", ignoreCase = true)
        if (needsHint && (action == "set" || action == "timer")) {
            data.put(
                "oem_hint",
                "${OsCompat.oemLabel()} aggressively kills background alarms. Please enable Autostart and disable battery optimization for Minis in system settings to keep this alarm reliable.",
            )
        }
        val envelope = JSONObject()
            .put("ok", true)
            .put("tool", "android-alarm")
            .put("action", action)
            .put("data", data)
        return NativeOffloadResult(0, OffloadOutput.formatBody(envelope.toString(2), args) + "\n")
    }

    private fun wrap(text: String, args: OffloadArgs): NativeOffloadResult {
        val err = text.startsWith("Error")
        val formatted = OffloadOutput.formatBody(text.trimEnd('\n'), args)
        return NativeOffloadResult(if (err) 1 else 0, "$formatted\n")
    }

    companion object {
        private const val TAG = "AlarmOffload"
        private const val VIEW_URL = "minis://views/alarm"
        private const val HELP = """android-alarm — schedule alarms and timers (mirrors apple-alarm)

Usage:
  android-alarm set --time <HH:MM|ISO> [--label L] [--repeat ONCE|DAILY|WEEKDAYS]
  android-alarm timer --duration <seconds|5m|1h> [--label L]
  android-alarm open

T266+T268: alarms and timers are written into the user's Android
system Clock app (single source of truth). The agent cannot
enumerate or selectively cancel alarms because the system Clock
does not expose a query API — instruct the user to view/edit/cancel
in the Clock app's Alarms or Timers tab. `android-alarm open`
launches Clock to that screen; `list` and `cancel` remain as aliases
that do the same thing.

Legacy aliases:
  android-alarm schedule <HH:MM> [--label L] [--repeat ...]
  android-alarm timer <seconds> [--label L]
  android-alarm list             (alias of open)
  android-alarm cancel ...       (alias of open)

Examples:
  android-alarm set --time 07:30 --label "Wake up" --repeat DAILY
  android-alarm set --time 2026-02-25T14:00
  android-alarm timer --duration 5m --label "Tea"
  android-alarm open

set/timer return JSON with `view_url: minis://views/alarm` and
`system_alarm: "ok"` on success.
"""
    }
}
