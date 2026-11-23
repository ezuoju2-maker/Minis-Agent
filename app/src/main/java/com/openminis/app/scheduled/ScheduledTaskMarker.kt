package com.openminis.app.scheduled

data class ScheduledTaskMarker(
    val taskId: String,
    val label: String,
    val nextFireAtMs: Long?,
    val prompt: String,
    val prefilledTool: String? = null,
    val insertedMidTask: Boolean = false,
    val firedAtMs: Long? = null,
    val fireIndex: Int? = null,
    val remaining: Int? = null,
    val triggerLabel: String? = null,
) {
    val xml: String
        get() {
            val attrs = buildString {
                append("id=\"").append(escapeAttr(taskId)).append('"')
                append(" label=\"").append(escapeAttr(label)).append('"')
                nextFireAtMs?.let { append(" next=\"").append(it).append('"') }
                firedAtMs?.let { append(" fired=\"").append(it).append('"') }
                fireIndex?.takeIf { it > 0 }?.let { append(" fire=\"").append(it).append('"') }
                remaining?.takeIf { it >= 0 }?.let { append(" remaining=\"").append(it).append('"') }
                triggerLabel?.takeIf { it.isNotEmpty() }?.let { append(" trigger=\"").append(escapeAttr(it)).append('"') }
                prefilledTool?.let { append(" prefilled=\"").append(escapeAttr(it)).append('"') }
                if (insertedMidTask) append(" inserted=\"1\"")
            }
            val note = when {
                insertedMidTask -> insertionReminder(label, prefilledTool) + "\n"
                prefilledTool != null -> prefillNote(prefilledTool) + "\n"
                else -> ""
            }
            return "<$TAG $attrs>\n$note$prompt\n</$TAG>"
        }

    companion object {
        const val TAG = "scheduled_task"

        fun prefillNote(tool: String): String =
            "[This task's first step already ran for you: $tool — its call and output follow. " +
                "Work from that output; re-run only if it failed.]"

        fun insertionReminder(label: String, prefilledTool: String?): String {
            val name = label.replace("\n", " ").trim().ifEmpty { null }
                ?.let { "Scheduled task \"$it\"" } ?: "A scheduled task"
            val preset = if (prefilledTool != null) " Preset command already ran; read the result below, don't re-run it." else ""
            return "<system-reminder>$name fired and was inserted here — not your last tool result, not a user request." +
                "$preset Handle briefly, then resume your unfinished prior task.</system-reminder>"
        }

        fun isMarkerText(text: String): Boolean =
            text.startsWith("<$TAG ") || text.startsWith("<$TAG>")

        fun parse(text: String): ScheduledTaskMarker? {
            if (!isMarkerText(text)) return null
            val openEnd = text.indexOf('>').takeIf { it >= 0 } ?: return null
            val attrStart = (TAG.length + 1).coerceAtMost(openEnd)
            val attrs = parseAttributes(text.substring(attrStart, openEnd))
            val id = attrs["id"]?.takeIf { it.isNotEmpty() } ?: return null
            val after = text.substring(openEnd + 1)
            val close = after.lastIndexOf("</$TAG>")
            val body = (if (close >= 0) after.substring(0, close) else after).trim()
            val prefilled = attrs["prefilled"]?.takeIf { it.isNotEmpty() }
            val inserted = attrs["inserted"] == "1"
            val label = attrs["label"].orEmpty()
            val note = when {
                inserted -> insertionReminder(label, prefilled)
                prefilled != null -> prefillNote(prefilled)
                else -> null
            }
            val prompt = when {
                note != null && body.startsWith(note) -> body.removePrefix(note).trimStart()
                else -> stripLegacyPreamble(body, inserted, prefilled != null)
            }
            return ScheduledTaskMarker(
                taskId = id,
                label = label,
                nextFireAtMs = attrs["next"]?.toLongOrNull(),
                prompt = prompt,
                prefilledTool = prefilled,
                insertedMidTask = inserted,
                firedAtMs = attrs["fired"]?.toLongOrNull(),
                fireIndex = attrs["fire"]?.toIntOrNull()?.takeIf { it > 0 },
                remaining = attrs["remaining"]?.toIntOrNull()?.takeIf { it >= 0 },
                triggerLabel = attrs["trigger"]?.takeIf { it.isNotEmpty() },
            )
        }

        fun forFire(
            task: ScheduledTask,
            firedAtMs: Long,
            scheduledFire: Boolean,
            prefilledTool: String?,
            nextFireAtMs: Long? = task.nextTriggerMs(),
        ): ScheduledTaskMarker {
            val repeating = task.triggerKind == ScheduledTriggerKind.INTERVAL ||
                (task.isCalendar && task.repeatMode != ScheduledRepeatMode.ONCE)
            val fireIndex = when {
                !scheduledFire || !repeating -> null
                task.triggerKind == ScheduledTriggerKind.INTERVAL -> task.triggeredCount?.takeIf { it > 0 }
                else -> task.firesSoFar + 1
            }
            return ScheduledTaskMarker(
                taskId = task.id,
                label = task.label,
                nextFireAtMs = nextFireAtMs,
                prompt = task.prompt,
                prefilledTool = prefilledTool,
                firedAtMs = firedAtMs,
                fireIndex = fireIndex,
                remaining = if (scheduledFire) task.remainingFires else null,
                triggerLabel = triggerLabel(task),
            )
        }

        fun triggerLabel(task: ScheduledTask): String = when (task.triggerKind) {
            ScheduledTriggerKind.AFTER -> "once(+${task.delaySec ?: 0}s)"
            ScheduledTriggerKind.INTERVAL -> "loop(${task.intervalSec ?: 0}s×${task.maxFires?.toString() ?: "∞"})"
            ScheduledTriggerKind.ON_COMPLETION -> "onCompletion(${task.onCompletionOf.orEmpty().take(8)})"
            ScheduledTriggerKind.CALENDAR -> {
                val days = when (task.repeatMode) {
                    ScheduledRepeatMode.ONCE -> null
                    ScheduledRepeatMode.DAILY -> (1..7).toList()
                    ScheduledRepeatMode.WEEKDAYS -> (2..6).toList()
                    ScheduledRepeatMode.CUSTOM -> task.customDays.sorted()
                }
                if (days == null) "once"
                else "cron(${task.timeOfDayHour}:${String.format(java.util.Locale.ROOT, "%02d", task.timeOfDayMinute)} days=${days.joinToString(", ", "[", "]")})"
            }
        }

        private fun stripLegacyPreamble(body: String, inserted: Boolean, prefilled: Boolean): String {
            if (inserted && body.startsWith("<system-reminder>")) {
                val end = body.indexOf("</system-reminder>")
                if (end >= 0) return body.substring(end + "</system-reminder>".length).trimStart()
            }
            if (prefilled && body.startsWith(PREFILL_NOTE_HEAD)) {
                val nl = body.indexOf('\n')
                if (nl >= 0) return body.substring(nl + 1).trimStart()
            }
            return body
        }

        private const val PREFILL_NOTE_HEAD = "[This task's first step already ran for you:"

        fun stripMarker(text: String): String = parse(text)?.prompt ?: text

        private fun parseAttributes(s: String): Map<String, String> {
            val out = mutableMapOf<String, String>()
            var rest = s
            while (true) {
                val eq = rest.indexOf('=').takeIf { it >= 0 } ?: break
                val key = rest.substring(0, eq).trim()
                val afterEq = rest.substring(eq + 1)
                val q = afterEq.indexOf('"').takeIf { it >= 0 } ?: break
                val endQ = afterEq.indexOf('"', q + 1).takeIf { it >= 0 } ?: break
                if (key.isNotEmpty()) out[key] = unescapeAttr(afterEq.substring(q + 1, endQ))
                rest = afterEq.substring(endQ + 1)
            }
            return out
        }

        private fun escapeAttr(s: String): String = s
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("\n", " ")

        private fun unescapeAttr(s: String): String = s
            .replace("&quot;", "\"").replace("&gt;", ">")
            .replace("&lt;", "<").replace("&amp;", "&")
    }
}
