package com.openminis.app.sandbox.offload

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.scheduled.PrefilledToolCall
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.scheduled.ScheduledTargetDelivery
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.scheduled.ScheduledTriggerKind
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

class ScheduledTaskOffloadHandler(private val context: Context) : NativeOffloadHandler {

    private val manager get() = ScheduledTaskManager(context)

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(
            request.argv.drop(1),
            booleanFlags = setOf("disabled", "h", "help"),
        )
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, HELP)
        }
        return try {
            when (val sub = args.positional.firstOrNull() ?: "list") {
                "list" -> handleList()
                "create", "add" -> {
                    val caller = OffloadCallerSession.resolve(request.sessionId)
                    AppLogger.info(TAG, "create caller=${caller.sid?.take(8) ?: "nil"} via=${caller.source}")
                    handleCreate(args, caller.sid)
                }
                "delete", "remove", "rm" -> handleDelete(args)
                "enable" -> handleSetEnabled(args, true)
                "disable" -> handleSetEnabled(args, false)
                "run" -> handleRun(args)
                else -> NativeOffloadResult(2, "minis-scheduled: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: IllegalArgumentException) {
            NativeOffloadResult(2, "minis-scheduled: ${e.message}")
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "handle failed: ${e.message}")
            NativeOffloadResult(1, "minis-scheduled: ${e.message}")
        }
    }

    private fun handleList(): NativeOffloadResult {
        val arr = JSONArray()
        for (t in manager.list()) arr.put(taskJson(t))
        val out = JSONObject().put("tasks", arr).put("count", arr.length())
        return NativeOffloadResult(0, out.toString(2))
    }

    internal data class Trigger(
        val kind: ScheduledTriggerKind,
        val hour: Int = 9,
        val minute: Int = 0,
        val repeat: ScheduledRepeatMode = ScheduledRepeatMode.ONCE,
        val customDays: Set<Int> = emptySet(),
        val delaySec: Long? = null,
        val intervalSec: Long? = null,
        val maxFires: Int? = null,
        val onCompletionOf: String? = null,
    )

    private fun handleCreate(args: OffloadArgs, callerSessionId: String?): NativeOffloadResult {
        val label = args.get("label", "l") ?: ""
        val trigger = parseTrigger(args) { ofRaw -> resolveUpstream(ofRaw) }
        val thinking = parseThinking(args.get("thinking"))
        val (target, defaulted) = resolveTarget(args, callerSessionId)
        val prompt = args.get("prompt", "p") ?: ""
        if (target !is ScheduledTargetMode.RerunMessage && prompt.isBlank()) {
            throw IllegalArgumentException("--prompt required (except for rerun target)")
        }
        val prefill = parsePrefill(args, label)
        if (prefill != null && target is ScheduledTargetMode.RerunMessage) {
            throw IllegalArgumentException("--command/--tool cannot be used with the rerun target (it replays an existing message)")
        }
        val model = args.get("model", "m")?.let { raw ->
            if (target !is ScheduledTargetMode.NewSession) {
                throw IllegalArgumentException(
                    "--model only applies to --target new; a follow-up or rerun runs in an existing chat and keeps that chat's model",
                )
            }
            resolveModel(raw)
        }
        val task = manager.create(
            ScheduledTask(
                label = label,
                timeOfDayHour = trigger.hour,
                timeOfDayMinute = trigger.minute,
                repeatMode = trigger.repeat,
                customDays = trigger.customDays,
                prompt = prompt,
                targetMode = target,
                modelId = model?.modelId,
                modelBinding = model?.bindingJson,
                thinkingLevel = thinking,
                prefillToolCall = prefill,
                enabled = !args.hasFlag("disabled"),
                startDateMs = args.get("start")?.let { parseDate(it) },
                endDateMs = args.get("end")?.let { parseDate(it) },
                triggerKind = trigger.kind,
                delaySec = trigger.delaySec,
                intervalSec = trigger.intervalSec,
                maxFires = trigger.maxFires,
                onCompletionOf = trigger.onCompletionOf,
                creatorSessionId = callerSessionId,
            ),
        )
        val destination = when (target) {
            is ScheduledTargetMode.AppendToSession -> ScheduledTargetDelivery.Destination.FollowUp(
                target.sessionId, sessionTitle(target.sessionId), target.sessionId == callerSessionId,
            )
            is ScheduledTargetMode.RerunMessage -> ScheduledTargetDelivery.Destination.Rerun(
                target.sessionId, target.messageId, sessionTitle(target.sessionId),
            )
            else -> ScheduledTargetDelivery.Destination.NewChat(label.ifBlank { null })
        }
        val out = JSONObject()
            .put("created", taskJson(task))
            .put("delivery", ScheduledTargetDelivery.sentence(destination, defaulted))
            .put("deliveryTarget", JSONObject(ScheduledTargetDelivery.summary(destination, defaulted)))
        return NativeOffloadResult(0, out.toString(2))
    }

    private fun resolveUpstream(raw: String): String {
        val tasks = manager.list()
        tasks.firstOrNull { it.id == raw }?.let { return it.id }
        tasks.filter { it.label == raw }.let { byLabel ->
            if (byLabel.size == 1) return byLabel.first().id
            if (byLabel.size > 1) throw IllegalArgumentException("--of '$raw' matches ${byLabel.size} tasks by label; use its id")
        }
        if (com.openminis.app.agent.jobs.AgentJobRegistry.job(raw) != null) return raw
        throw IllegalArgumentException("--of: no scheduled task or agent job with id or label '$raw' (see `minis-scheduled list`)")
    }

    private fun handleDelete(args: OffloadArgs): NativeOffloadResult {
        val id = args.get("id") ?: throw IllegalArgumentException("--id required")
        if (manager.get(id) == null) return NativeOffloadResult(1, "minis-scheduled: no task with id=$id")
        manager.delete(id)
        return NativeOffloadResult(0, JSONObject().put("deleted", id).toString())
    }

    private fun handleSetEnabled(args: OffloadArgs, enabled: Boolean): NativeOffloadResult {
        val id = args.get("id") ?: throw IllegalArgumentException("--id required")
        if (manager.get(id) == null) return NativeOffloadResult(1, "minis-scheduled: no task with id=$id")
        manager.setEnabled(id, enabled)
        return NativeOffloadResult(0, JSONObject().put("id", id).put("enabled", enabled).toString())
    }

    private fun handleRun(args: OffloadArgs): NativeOffloadResult {
        val id = args.get("id") ?: throw IllegalArgumentException("--id required")
        val task = manager.get(id) ?: return NativeOffloadResult(1, "minis-scheduled: no task with id=$id")
        val sessionId = runBlocking {
            com.openminis.app.scheduled.ScheduledAgentRunner.run(context, task)
        }
        val out = JSONObject()
            .put("id", id)
            .put("ran", sessionId != null)
            .apply { if (sessionId != null) put("sessionId", sessionId) }
        return NativeOffloadResult(if (sessionId != null) 0 else 1, out.toString(2))
    }

    private fun resolveTarget(args: OffloadArgs, callerSessionId: String?): Pair<ScheduledTargetMode, Boolean> {
        val session = args.get("session", "s")
        val (target, defaulted) = ScheduledTargetDelivery.resolveTarget(args.get("target"), session, callerSessionId)
        return when (target) {
            "new" -> ScheduledTargetMode.NewSession to defaulted
            "follow-up", "followup", "follow" -> {
                val sid = session ?: callerSessionId
                    ?: throw IllegalArgumentException("--session required for follow-up target")
                when {
                    sessionExists(sid) -> ScheduledTargetMode.AppendToSession(sid) to defaulted
                    defaulted -> ScheduledTargetMode.NewSession to true
                    else -> throw IllegalArgumentException("no chat session with id=$sid for the follow-up target")
                }
            }
            "rerun", "re-run", "retry" -> {
                val sid = session ?: throw IllegalArgumentException("--session required for rerun target")
                val mid = args.get("message")
                    ?: throw IllegalArgumentException("--message required for rerun target")
                ScheduledTargetMode.RerunMessage(sid, mid) to defaulted
            }
            else -> throw IllegalArgumentException("--target must be follow-up|new|rerun")
        }
    }

    private fun resolveModel(raw: String): com.openminis.app.scheduled.ScheduledModelSource.Resolved {
        val repo = (context.applicationContext as? com.openminis.app.MinisApp)?.providerRepositoryOrNull
            ?: throw IllegalStateException("--model: model settings are not loaded yet; try again")
        val cfg = repo.config.value
        return com.openminis.app.scheduled.ScheduledModelSource.resolve(
            raw,
            com.openminis.app.scheduled.ScheduledModelSource.entryRefs(cfg.modelEntries, repo.allVisibleEntries()),
            com.openminis.app.scheduled.ScheduledModelSource.groupRefs(cfg.modelGroups),
        )
    }

    private fun sessionEntity(id: String) =
        (context.applicationContext as? com.openminis.app.MinisApp)?.chatRepositoryOrNull?.let { repo ->
            runBlocking { repo.getSession(id) }
        }

    private fun sessionExists(id: String): Boolean {
        val repo = (context.applicationContext as? com.openminis.app.MinisApp)?.chatRepositoryOrNull ?: return true
        return runBlocking { repo.getSession(id) } != null
    }

    private fun sessionTitle(id: String): String? = sessionEntity(id)?.title?.takeIf { it.isNotBlank() }

    private fun parseDate(s: String): Long {
        val parts = s.split("-")
        require(parts.size == 3) { "--start/--end must be YYYY-MM-DD" }
        val y = parts[0].toInt(); val mo = parts[1].toInt(); val d = parts[2].toInt()
        return Calendar.getInstance().apply {
            clear(); set(y, mo - 1, d, 0, 0, 0)
        }.timeInMillis
    }

    private fun taskJson(t: ScheduledTask): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("label", t.label)
        put("time", "%02d:%02d".format(t.timeOfDayHour, t.timeOfDayMinute))
        put("repeat", t.repeatMode.name.lowercase())
        if (t.customDays.isNotEmpty()) put("days", JSONArray(t.customDays.toList()))
        put("prompt", t.prompt)
        put("target", t.targetMode.encode())
        put(
            "trigger",
            when (t.triggerKind) {
                ScheduledTriggerKind.CALENDAR -> if (t.repeatMode == ScheduledRepeatMode.ONCE) "once" else "cron"
                ScheduledTriggerKind.AFTER -> "once"
                ScheduledTriggerKind.INTERVAL -> "loop"
                ScheduledTriggerKind.ON_COMPLETION -> "on-completion"
            },
        )
        t.delaySec?.let { put("afterSec", it) }
        t.intervalSec?.let { put("intervalSec", it) }
        t.maxFires?.let { put("count", it) }
        t.remainingFires?.let { put("remaining", it) }
        t.onCompletionOf?.let { put("of", it) }
        if (t.usesAnchor) put("firedCount", t.triggeredCount ?: 0)
        t.thinkingLevel?.let { put("thinking", it.name.lowercase()) }
        com.openminis.app.scheduled.ScheduledModelSource.display(t.modelBinding, t.modelId)?.let { put("model", it) }
        t.prefillToolCall?.let { put("prefillTool", it.toJson()) }
        put("enabled", t.enabled)
        t.nextTriggerMs()?.let { put("nextTriggerMs", it) }
        if (t.startDateMs != null) put("startDateMs", t.startDateMs)
        if (t.endDateMs != null) put("endDateMs", t.endDateMs)
        if (t.lastFiredAt != null) put("lastFiredAt", t.lastFiredAt)
        if (t.lastResultPreview != null) put("lastResultPreview", t.lastResultPreview)
        if (t.lastResultSessionId != null) put("lastResultSessionId", t.lastResultSessionId)
    }

    companion object {
        private const val TAG = "ScheduledTaskOffload"

        internal const val MIN_INTERVAL_SEC = 60L

        internal fun parseTrigger(args: OffloadArgs, resolveOf: (String) -> String): Trigger {
            for (name in listOf("after", "interval", "count", "of", "trigger")) {
                if (args.hasFlag(name)) throw IllegalArgumentException("--$name needs a value")
            }
            val kind = args.get("trigger")?.lowercase()
                ?: when {
                    args.get("after") != null -> "once"
                    args.get("interval") != null -> "loop"
                    args.get("time", "t") != null -> "calendar"
                    args.get("of") != null -> "on-completion"
                    else -> throw IllegalArgumentException(
                        "give a trigger: --after <dur> (once), --interval <dur> [--count N] (loop), " +
                            "--time HH:MM [--repeat ...] (at a time of day), or --trigger on-completion --of <taskId|jobId>",
                    )
                }
            return when (kind) {
                "once" -> {
                    val after = args.get("after")
                    when {
                        after != null -> {
                            val secs = parseDurationSec(after)?.takeIf { it >= 1 }
                                ?: throw IllegalArgumentException("--after must be a duration like 30m, 2h, 90s")
                            Trigger(ScheduledTriggerKind.AFTER, delaySec = secs.toLong())
                        }
                        args.get("time", "t") != null -> calendar(args, defaultRepeat = ScheduledRepeatMode.ONCE)
                        else -> throw IllegalArgumentException("once needs --after <duration> or --time HH:MM")
                    }
                }
                "loop" -> {
                    val secs = args.get("interval")?.let { parseDurationSec(it) }
                        ?.takeIf { it >= MIN_INTERVAL_SEC }
                        ?: throw IllegalArgumentException("--interval must be a duration of at least 60s (e.g. 10m)")
                    val count = args.get("count")?.let {
                        it.toIntOrNull()?.takeIf { n -> n >= 1 }
                            ?: throw IllegalArgumentException("--count must be a positive integer")
                    }
                    Trigger(ScheduledTriggerKind.INTERVAL, intervalSec = secs.toLong(), maxFires = count)
                }
                "calendar" -> calendar(args, defaultRepeat = ScheduledRepeatMode.ONCE)
                "cron" -> calendar(args, defaultRepeat = ScheduledRepeatMode.DAILY)
                "on-completion", "oncompletion", "on_completion" -> {
                    val of = args.get("of")
                        ?: throw IllegalArgumentException("--of <taskId|jobId> required for the on-completion trigger")
                    Trigger(ScheduledTriggerKind.ON_COMPLETION, onCompletionOf = resolveOf(of))
                }
                else -> throw IllegalArgumentException("--trigger must be once|loop|cron|on-completion")
            }
        }

        private fun calendar(args: OffloadArgs, defaultRepeat: ScheduledRepeatMode): Trigger {
            val (hour, minute) = parseTime(
                args.get("time", "t") ?: throw IllegalArgumentException("--time HH:MM required"),
            )
            val repeat = args.get("repeat", "r")?.let { parseRepeat(it) } ?: defaultRepeat
            val customDays = if (repeat == ScheduledRepeatMode.CUSTOM) {
                parseDays(args.get("days") ?: throw IllegalArgumentException("--days required for custom repeat"))
            } else emptySet()
            return Trigger(ScheduledTriggerKind.CALENDAR, hour, minute, repeat, customDays)
        }

        internal fun parseThinking(raw: String?): com.openminis.app.data.model.ThinkingLevel? {
            if (raw == null) return null
            return com.openminis.app.data.model.ThinkingLevel.parseOrNull(raw)
                ?: throw IllegalArgumentException(
                    "--thinking must be one of: " +
                        com.openminis.app.data.model.ThinkingLevel.entries.joinToString(", ") { it.name.lowercase() },
                )
        }

        private fun parseTime(s: String): Pair<Int, Int> {
            val parts = s.split(":")
            require(parts.size == 2) { "--time must be HH:MM" }
            val h = parts[0].toIntOrNull()?.takeIf { it in 0..23 }
                ?: throw IllegalArgumentException("bad hour in --time")
            val m = parts[1].toIntOrNull()?.takeIf { it in 0..59 }
                ?: throw IllegalArgumentException("bad minute in --time")
            return h to m
        }

        private fun parseRepeat(s: String?): ScheduledRepeatMode = when (s?.lowercase()) {
            null, "once" -> ScheduledRepeatMode.ONCE
            "daily" -> ScheduledRepeatMode.DAILY
            "weekdays" -> ScheduledRepeatMode.WEEKDAYS
            "custom" -> ScheduledRepeatMode.CUSTOM
            else -> throw IllegalArgumentException("--repeat must be once|daily|weekdays|custom")
        }

        private fun parseDays(s: String): Set<Int> {
            val map = mapOf(
                "sun" to Calendar.SUNDAY, "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY,
                "wed" to Calendar.WEDNESDAY, "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY,
                "sat" to Calendar.SATURDAY,
            )
            return s.split(",").mapNotNull { map[it.trim().lowercase().take(3)] }.toSet()
        }

        internal fun parsePrefill(args: OffloadArgs, label: String): PrefilledToolCall? {
            val names = listOf("shell", "command", "shell-timeout", "command-timeout", "tool", "tool-args")
            for (name in names) {
                if (args.hasFlag(name)) {
                    throw IllegalArgumentException("--$name needs a value (write --$name=<value> if it starts with '-')")
                }
            }
            val command = args.get("shell", "command")
            val timeoutRaw = args.get("shell-timeout", "command-timeout")
            val tool = args.get("tool")
            val toolArgs = args.get("tool-args")
            if (command == null && timeoutRaw == null && tool == null && toolArgs == null) return null
            if (command != null && toolArgs != null) {
                throw IllegalArgumentException("use either --command or --tool/--tool-args, not both")
            }
            val toolName = tool?.let { PrefilledToolCall.canonicalToolName(it) } ?: PrefilledToolCall.SHELL_TOOL
            if (toolName !in PrefilledToolCall.SUPPORTED_TOOLS) {
                throw IllegalArgumentException(
                    "--tool $tool is not supported as a prefilled tool yet; only shell_execute (alias: shell) is",
                )
            }
            var timeoutSec: Int? = timeoutRaw?.let {
                parseDurationSec(it) ?: throw IllegalArgumentException("--command-timeout must be a duration like 90s, 2m, 1h")
            }
            var cmd = command
            var title: String? = null
            if (toolArgs != null) {
                val obj = try {
                    JSONObject(toolArgs)
                } catch (e: Exception) {
                    throw IllegalArgumentException("--tool-args must be a JSON object, e.g. '{\"command\":\"date\"}'")
                }
                val unknown = obj.keys().asSequence().toSet() - setOf("command", "timeout", "tool_title")
                if (unknown.isNotEmpty()) {
                    throw IllegalArgumentException(
                        "--tool-args: unsupported key(s) ${unknown.sorted().joinToString()} for shell_execute (allowed: command, timeout)",
                    )
                }
                cmd = obj.opt("command") as? String
                    ?: throw IllegalArgumentException("--tool-args needs a \"command\" string")
                if (obj.has("timeout")) {
                    val t = obj.opt("timeout") as? Number
                        ?: throw IllegalArgumentException("--tool-args \"timeout\" must be a number of seconds")
                    if (timeoutSec == null) timeoutSec = saturatingSeconds(t.toDouble())
                }
                title = obj.optString("tool_title", "").ifBlank { null }
            }
            if (cmd.isNullOrBlank()) throw IllegalArgumentException("--command needs a shell command")
            val t = timeoutSec
            if (t != null && t !in PrefilledToolCall.TIMEOUT_RANGE_SEC) {
                throw IllegalArgumentException("--command-timeout must be between 1s and 1h")
            }
            val call = PrefilledToolCall.shell(cmd, title = title ?: label.ifBlank { null }, timeoutSec = t)
            call.validationError()?.let { throw IllegalArgumentException(it) }
            return call
        }

        internal fun parseDurationSec(raw: String): Int? {
            val m = Regex("""^\s*(\d+(?:\.\d+)?)\s*([smh]?)\s*$""").find(raw.lowercase()) ?: return null
            val n = m.groupValues[1].toDoubleOrNull() ?: return null
            val mult = when (m.groupValues[2]) { "m" -> 60.0; "h" -> 3600.0; else -> 1.0 }
            return saturatingSeconds(n * mult)
        }

        internal fun saturatingSeconds(value: Double): Int = when {
            value.isNaN() -> Int.MIN_VALUE
            value >= Int.MAX_VALUE.toDouble() -> Int.MAX_VALUE
            value <= Int.MIN_VALUE.toDouble() -> Int.MIN_VALUE
            else -> Math.round(value).toInt()
        }

        private val HELP = """
            minis-scheduled — manage timed AI tasks (the same tasks as Settings › Scheduled Tasks).

            (no subcommand)  Same as `list`
            list
            create --prompt "..." [--label L]  plus ONE trigger:
                   --after 30m                               once, after a delay (90s, 30m, 2h)
                   --interval 10m [--count N]                every interval (>= 60s), N times (default: until deleted)
                   --time HH:MM [--repeat once|daily|weekdays|custom --days mon,tue,...]
                                                             at a time of day (default once)
                   --trigger on-completion --of <taskId|jobId>
                                                             once, when that task or agent job finishes;
                                                             a task finishes when its schedule is over
                                                             (its one fire, or its last --count fire;
                                                             a daily task or endless --interval never);
                                                             {{result}} in --prompt becomes its result
                   [--trigger once|loop|cron|on-completion]  optional; inferred from the flags above
                   [--target follow-up|new|rerun --session <id> --message <id>]
                   [--model entry:<id>|group:<id>|<modelId>] [--thinking off|low|medium|high|xhigh|max|ultra]
                   [--start YYYY-MM-DD] [--end YYYY-MM-DD] [--disabled]
                   [--command "<shell command>" [--command-timeout 2m]]   prefilled first step
                   [--tool shell_execute --tool-args '{"command":"..."}']
            delete  --id <taskId>
            enable  --id <taskId>          re-enabling an --after/--interval task starts it over
            disable --id <taskId>
            run     --id <taskId>          fire immediately, off-schedule (not counted by --count)

            TARGET (default: follow-up when run inside a chat, new otherwise):
              follow-up  each fire is a new turn IN THIS CHAT (or in --session <id>); the
                         result comes back to the conversation that created the task
              new        each fire opens a separate new chat "Scheduled · <label>"; the
                         result does NOT appear in this conversation — only when the user
                         asks for that
              rerun      re-run a chat (--session) from a chosen user message (--message,
                         prompt ignored)

            MODEL (--model, --target new only): entry:<id> or a bare entry id pins that exact
              entry, group:<id> a model group (the forms `minis-config get session.primaryModel`
              prints; ids from `minis-config get models`). A bare model id is accepted only
              when one visible entry serves it; otherwise create fails and lists the entry ids.

            OUTPUT: `create` answers with a `delivery` line saying where every fire will
            land (this chat, another chat, a new chat, a rerun). Check it; if it is not
            what the user asked for, `delete --id` and create again with the right --target.

            PREFILLED TOOL CALL (--command, alias --shell):
              The shell command runs as the FIRST step of every fire, before the model is
              asked anything; the model receives its real output as an already-completed
              tool call and only has to act on it — one model request per fire instead of
              two. Use it when the command is the same every time (fetch a page, check a
              status, run a script). --command-timeout (alias --shell-timeout) takes 90s,
              2m, 1h (1s..1h; default 15m). --tool/--tool-args is the general form; only
              shell_execute is supported today (args: command, timeout).
              A failing command is not a failed task: the model sees its output and exit
              status and decides what to do (explain, retry, or report).

            Tasks are kept by the system alarm service: they fire even when DeepSeek is in the
            background or closed, and survive a reboot (a force-stop pauses them until the
            app is next opened).

            EXAMPLES:
              minis-scheduled create --prompt "Remind me to drink water" --after 30m
              minis-scheduled create --prompt "Check the build status" --interval 10m --count 6 --label build-watch
              minis-scheduled create --time 08:00 --repeat daily --label Weather \
                --command "curl -s 'wttr.in/Shanghai?format=3'" \
                --prompt "Summarise today's weather and say whether I need an umbrella."
              minis-scheduled create --trigger on-completion --of build-watch \
                --prompt "The build watch ended with: {{result}}. Tell me if anything failed."
        """.trimIndent()
    }
}
