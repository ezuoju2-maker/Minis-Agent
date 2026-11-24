package com.openminis.app.agent

import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class ToolCallRecord(
    val toolName: String,
    val argsHash: String,
    val resultHash: String? = null,
    val unknownToolName: String? = null,
    val toolCallId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

enum class Level { NONE, WARNING, CRITICAL }

data class LoopCheckResult(
    val level: Level,
    val message: String? = null,
    val warningKey: String? = null,
) {
    companion object {
        val NONE = LoopCheckResult(Level.NONE)
    }
}

data class ToolLoopConfig(
    val historySize: Int = 30,
    val warningThreshold: Int = 10,
    val unknownToolThreshold: Int = 10,
    val criticalThreshold: Int = 20,
    val globalCircuitBreakerThreshold: Int = 30,
    val toolTitleReminderEvery: Int = 10,
) {
    init {
        require(warningThreshold > 0) { "warningThreshold must be positive" }
        require(toolTitleReminderEvery > 0) { "toolTitleReminderEvery must be positive" }
        require(warningThreshold < criticalThreshold) {
            "warningThreshold ($warningThreshold) must be < criticalThreshold ($criticalThreshold)"
        }
        require(criticalThreshold < globalCircuitBreakerThreshold) {
            "criticalThreshold ($criticalThreshold) must be < globalCircuitBreakerThreshold ($globalCircuitBreakerThreshold)"
        }
        require(historySize >= globalCircuitBreakerThreshold) {
            "historySize ($historySize) must be >= globalCircuitBreakerThreshold ($globalCircuitBreakerThreshold)"
        }
    }
}

class ToolLoopDetector(private val config: ToolLoopConfig = ToolLoopConfig()) {

    private val lock = Any()

    private val history = ArrayDeque<ToolCallRecord>()
    private val warningBuckets = HashMap<String, Int>()

    private var missingTitleCount = 0

    private var scheduledFireExemption = 0

    fun reset() = synchronized(lock) {
        history.clear()
        warningBuckets.clear()
        missingTitleCount = 0
        scheduledFireExemption = 0
    }

    fun recordToolTitle(args: JSONObject, titleDeclared: Boolean): String? {
        if (!titleDeclared || !com.openminis.app.ui.chat.ToolTitleReminder.isMissingTitle(args)) return null
        return synchronized(lock) {
            missingTitleCount += 1
            if (!shouldEmitEvery("title:missing", missingTitleCount, config.toolTitleReminderEvery)) {
                null
            } else {
                AppLogger.info("ToolLoopDetector", "loop-detector: tool_title missing count=$missingTitleCount — soft reminder due")
                com.openminis.app.ui.chat.ToolTitleReminder.TEXT
            }
        }
    }

    fun exemptNextCallForScheduledFire() = synchronized(lock) {
        scheduledFireExemption = 1
    }

    internal fun historySnapshot(): List<ToolCallRecord> = synchronized(lock) { history.toList() }


    fun check(toolName: String, params: Map<String, Any?>): LoopCheckResult = synchronized(lock) {
        val argsHash = argsHashFor(toolName, params)

        val unknownStreak = countUnknownStreakFromTail(toolName)
        if (unknownStreak >= config.unknownToolThreshold) {
            val msg = "[LOOP BLOCKED] CRITICAL: attempted unavailable tool '$toolName' " +
                "$unknownStreak times. Stop retrying that missing tool and answer without it."
            AppLogger.warning("ToolLoopDetector",
                "CRITICAL unknown_tool_repeat tool=$toolName streak=$unknownStreak")
            return LoopCheckResult(Level.CRITICAL, msg)
        }

        val noProgressStreak = getNoProgressStreak(toolName, argsHash)

        if (noProgressStreak >= config.globalCircuitBreakerThreshold) {
            val msg = "[LOOP BLOCKED] CRITICAL: $toolName has repeated identical " +
                "no-progress outcomes $noProgressStreak times. Session execution " +
                "blocked by global circuit breaker."
            AppLogger.warning("ToolLoopDetector",
                "CRITICAL global_circuit_breaker tool=$toolName streak=$noProgressStreak")
            return LoopCheckResult(Level.CRITICAL, msg)
        }

        if (isPollTool(toolName, params)) {
            if (noProgressStreak >= config.criticalThreshold) {
                val msg = "[LOOP BLOCKED] CRITICAL: Called $toolName $noProgressStreak " +
                    "times with identical no-progress results. Session execution blocked."
                AppLogger.warning("ToolLoopDetector",
                    "CRITICAL known_poll_no_progress tool=$toolName streak=$noProgressStreak")
                return LoopCheckResult(Level.CRITICAL, msg)
            }
            if (noProgressStreak >= config.warningThreshold) {
                val msg = "[LOOP WARNING] You have called $toolName $noProgressStreak " +
                    "times with no progress. Stop polling and either (1) increase wait " +
                    "time, or (2) report the task as failed."
                AppLogger.debug("ToolLoopDetector",
                    "WARNING known_poll_no_progress tool=$toolName streak=$noProgressStreak")
                return LoopCheckResult(Level.WARNING, msg, "poll:$toolName:$argsHash")
            }
        }

        if (!isPollTool(toolName, params)) {
            val totalCount = history.count { it.toolName == toolName && it.argsHash == argsHash }
            if (totalCount >= config.warningThreshold) {
                val msg = "[LOOP WARNING] You have called $toolName $totalCount times " +
                    "with identical arguments. If this is not making progress, stop " +
                    "retrying and report the task as failed."
                AppLogger.debug("ToolLoopDetector",
                    "WARNING generic_repeat tool=$toolName count=$totalCount")
                return LoopCheckResult(Level.WARNING, msg, "repeat:$toolName:$argsHash")
            }
        }

        return LoopCheckResult.NONE
    }


    fun record(
        toolName: String,
        params: Map<String, Any?>,
        result: String?,
        errorMessage: String? = null,
        toolCallId: String? = null,
    ): LoopCheckResult = synchronized(lock) {
        if (scheduledFireExemption > 0) {
            scheduledFireExemption -= 1
            AppLogger.info("ToolLoopDetector", "loop-detector: $toolName exempted (scheduled fire) — not counted")
            return@synchronized LoopCheckResult.NONE
        }
        val argsHash = argsHashFor(toolName, params)
        val resultHash = resultHashFor(result, errorMessage)
        val unknownToolName = extractUnknownToolName(errorMessage)

        history.addLast(ToolCallRecord(
            toolName = toolName,
            argsHash = argsHash,
            resultHash = resultHash,
            unknownToolName = unknownToolName,
            toolCallId = toolCallId,
        ))
        while (history.size > config.historySize) history.removeFirst()

        if (isPollTool(toolName, params)) {
            val streak = getNoProgressStreak(toolName, argsHash)
            if (streak in config.warningThreshold until config.criticalThreshold) {
                val key = "poll:$toolName:$argsHash"
                if (shouldEmitWarning(key, streak)) {
                    val msg = "[LOOP WARNING] You have called $toolName $streak times " +
                        "with no progress. Stop polling and either (1) increase wait " +
                        "time, or (2) report the task as failed."
                    return LoopCheckResult(Level.WARNING, msg, key)
                }
            }
        } else {
            val totalCount = history.count { it.toolName == toolName && it.argsHash == argsHash }
            if (totalCount >= config.warningThreshold) {
                val key = "repeat:$toolName:$argsHash"
                if (shouldEmitWarning(key, totalCount)) {
                    val msg = "[LOOP WARNING] You have called $toolName $totalCount " +
                        "times with identical arguments. If this is not making " +
                        "progress, stop retrying and report the task as failed."
                    return LoopCheckResult(Level.WARNING, msg, key)
                }
            }
        }

        return LoopCheckResult.NONE
    }


    private fun countUnknownStreakFromTail(toolName: String): Int {
        var streak = 0
        for (rec in history.reversed()) {
            val unk = rec.unknownToolName ?: break
            if (unk == toolName) streak++ else break
        }
        return streak
    }

    private fun getNoProgressStreak(toolName: String, argsHash: String): Int {
        var streak = 0
        var pinnedHash: String? = null
        for (rec in history.reversed()) {
            if (rec.toolName != toolName || rec.argsHash != argsHash) continue
            val rh = rec.resultHash ?: break
            if (pinnedHash == null) {
                pinnedHash = rh
                streak++
            } else if (rh == pinnedHash) {
                streak++
            } else {
                break
            }
        }
        return streak
    }

    private fun isPollTool(toolName: String, params: Map<String, Any?>): Boolean {
        if (toolName == "command_status") return true
        if (toolName == "process") {
            val action = (params["action"] as? String)?.lowercase()
            if (action == "poll" || action == "log") return true
        }
        return false
    }

    private fun shouldEmitEvery(warningKey: String, count: Int, period: Int): Boolean {
        val bucket = (count - 1) / period.coerceAtLeast(1)
        val last = warningBuckets[warningKey]
        if (last != null && last >= bucket) return false
        warningBuckets[warningKey] = bucket
        return true
    }

    private fun shouldEmitWarning(warningKey: String, currentCount: Int): Boolean {
        val bucket = currentCount / config.warningThreshold
        val last = warningBuckets[warningKey]
        if (last == bucket) return false
        warningBuckets[warningKey] = bucket
        return true
    }


    private fun argsHashFor(toolName: String, params: Map<String, Any?>): String {
        val filtered = if (params.keys.any { it in ARGS_HASH_IGNORED_KEYS }) {
            params.filterKeys { it !in ARGS_HASH_IGNORED_KEYS }
        } else {
            params
        }
        return sha256("$toolName:${stableJson(filtered)}")
    }

    private fun stableJson(value: Any?): String = buildString { appendStable(value) }

    private fun StringBuilder.appendStable(value: Any?) {
        when (value) {
            null -> append("null")
            is Map<*, *> -> {
                append('{')
                value.entries
                    .map { it.key?.toString().orEmpty() to it.value }
                    .sortedBy { it.first }
                    .forEachIndexed { i, (k, v) ->
                        if (i > 0) append(',')
                        append(JSONObject.quote(k)); append(':'); appendStable(v)
                    }
                append('}')
            }
            is List<*> -> {
                append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) append(',')
                    appendStable(v)
                }
                append(']')
            }
            is Array<*> -> appendStable(value.toList())
            is String -> append(JSONObject.quote(value))
            is Number -> append(ToolProgressSignature.canonicalNumber(value))
            is Boolean -> append(value.toString())
            is JSONObject -> appendStable(value.toPlainMap())
            is JSONArray -> appendStable(value.toPlainList())
            else -> append(JSONObject.quote(value.toString()))
        }
    }

    private fun JSONObject.toPlainMap(): Map<String, Any?> {
        val out = HashMap<String, Any?>(length())
        val keys = keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = unwrap(get(k))
        }
        return out
    }

    private fun JSONArray.toPlainList(): List<Any?> {
        val out = ArrayList<Any?>(length())
        for (i in 0 until length()) out.add(unwrap(get(i)))
        return out
    }

    private fun unwrap(v: Any?): Any? = when (v) {
        JSONObject.NULL -> null
        is JSONObject -> v.toPlainMap()
        is JSONArray -> v.toPlainList()
        else -> v
    }

    private fun resultHashFor(result: String?, errorMessage: String?): String =
        ToolProgressSignature.signature(result, errorMessage)

    private fun extractUnknownToolName(errorMessage: String?): String? {
        if (errorMessage.isNullOrBlank()) return null
        UNKNOWN_TOOL_RE_1.find(errorMessage)?.groupValues?.getOrNull(1)?.let { return it }
        UNKNOWN_TOOL_RE_2.find(errorMessage)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    companion object {
        private val ARGS_HASH_IGNORED_KEYS: Set<String> = setOf("tool_title")

        private val UNKNOWN_TOOL_RE_1 = Regex(
            """unknown tool[:\s]+["']?([a-zA-Z0-9_.\-]+)["']?""",
            RegexOption.IGNORE_CASE,
        )
        private val UNKNOWN_TOOL_RE_2 = Regex(
            """tool\s+["']?([a-zA-Z0-9_.\-]+)["']?\s+(?:not found|is not available)""",
            RegexOption.IGNORE_CASE,
        )
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
