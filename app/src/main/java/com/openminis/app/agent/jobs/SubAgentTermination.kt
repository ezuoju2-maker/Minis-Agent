package com.openminis.app.agent.jobs

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject


data class SubAgentTermination(
    val sessionId: String,
    val jobId: String,
    val parentSessionId: String?,
    val reason: Reason,
    val detail: String? = null,
    val lastTool: String? = null,
    val lastToolStatus: String? = null,
    val lastToolSummary: String? = null,
    val partialOutputAvailable: Boolean = false,
    val closingAttempted: Boolean = false,
    val delivery: Delivery? = null,
    val deliveryDetail: String? = null,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
) {
    enum class Reason(val wire: String) {
        RUNNING("running"),
        COMPLETED("completed"),
        TIMEOUT("timeout"),
        TOOL_ERROR("tool_error"),
        CANCELLED("cancelled"),
        INTERRUPTED("interrupted"),
        MODEL_ERROR("model_error"),
        ;

        companion object {
            fun fromWire(v: String?): Reason? = entries.firstOrNull { it.wire == v }
        }
    }

    enum class Delivery(val wire: String) {
        INLINE("inline"),
        PENDING("pending"),
        DELIVERED("delivered"),
        QUEUED("queued"),
        FAILED("failed"),
        ;

        companion object {
            fun fromWire(v: String?): Delivery? = entries.firstOrNull { it.wire == v }
        }
    }

    val isTerminal: Boolean get() = reason != Reason.RUNNING

    fun payload(): JSONObject = JSONObject().also { o -> putPayload(o) }

    fun putPayload(into: JSONObject): JSONObject {
        into.put("termination_reason", reason.wire)
        into.put("partial_output_available", partialOutputAvailable)
        into.put("closing_attempted", closingAttempted)
        detail?.let { into.put("termination_detail", it) }
        lastTool?.let { into.put("last_tool_name", it) }
        lastToolStatus?.let { into.put("last_tool_status", it) }
        lastToolSummary?.let { into.put("last_tool_result", it) }
        delivery?.let { into.put("delivery_state", it.wire) }
        deliveryDetail?.let { into.put("delivery_detail", it) }
        endedAtMs?.let { into.put("ended_at", iso8601(it)) }
        return into
    }

    fun toJson(): JSONObject = JSONObject()
        .put("sessionId", sessionId)
        .put("jobId", jobId)
        .put("reason", reason.wire)
        .put("partialOutputAvailable", partialOutputAvailable)
        .put("closingAttempted", closingAttempted)
        .put("startedAt", startedAtMs)
        .also { o ->
            parentSessionId?.let { o.put("parentSessionId", it) }
            detail?.let { o.put("detail", it) }
            lastTool?.let { o.put("lastTool", it) }
            lastToolStatus?.let { o.put("lastToolStatus", it) }
            lastToolSummary?.let { o.put("lastToolSummary", it) }
            delivery?.let { o.put("delivery", it.wire) }
            deliveryDetail?.let { o.put("deliveryDetail", it) }
            endedAtMs?.let { o.put("endedAt", it) }
        }

    companion object {
        fun fromJson(o: JSONObject): SubAgentTermination? {
            val sid = o.optString("sessionId").ifEmpty { return null }
            val reason = Reason.fromWire(o.optString("reason")) ?: return null
            fun opt(k: String): String? = if (o.has(k) && !o.isNull(k)) o.optString(k) else null
            return SubAgentTermination(
                sessionId = sid,
                jobId = o.optString("jobId"),
                parentSessionId = opt("parentSessionId"),
                reason = reason,
                detail = opt("detail"),
                lastTool = opt("lastTool"),
                lastToolStatus = opt("lastToolStatus"),
                lastToolSummary = opt("lastToolSummary"),
                partialOutputAvailable = o.optBoolean("partialOutputAvailable", false),
                closingAttempted = o.optBoolean("closingAttempted", false),
                delivery = Delivery.fromWire(opt("delivery")),
                deliveryDetail = opt("deliveryDetail"),
                startedAtMs = o.optLong("startedAt", 0L),
                endedAtMs = if (o.has("endedAt")) o.optLong("endedAt") else null,
            )
        }

        fun iso8601(ms: Long): String {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            f.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return f.format(java.util.Date(ms))
        }
    }
}

data class SubAgentRunEnd(
    val loopStatus: String,
    val userCancelled: Boolean = false,
    val budgetExpired: Boolean = false,
    val errorText: String? = null,
    val hasFinalAnswer: Boolean,
    val lastToolStatus: String? = null,
)

data class SubAgentToolSnapshot(val name: String, val status: String, val summary: String)

object SubAgentTerminationRules {
    const val NO_FINAL_ANSWER_LABEL =
        "(The agent did not write a final answer; this is its last message before the run ended.)"

    const val NO_ANSWER_NOTE_PREFIX = "(the agent ended without a final answer"

    private const val EMPTY_RESULT_NOTE_PREFIX = "(the agent ended with status "

    data class TurnShape(val isAssistant: Boolean, val hasText: Boolean, val hasToolUse: Boolean)

    fun endsWithFinalTurn(turns: List<TurnShape>): Boolean {
        val last = turns.lastOrNull() ?: return false
        return last.isAssistant && last.hasText && !last.hasToolUse
    }

    fun shapesOf(history: List<LLMMessage>): List<TurnShape> = history.map { m ->
        val parts = m.contentParts
        TurnShape(
            isAssistant = m.role == LLMMessage.Role.ASSISTANT,
            hasText = if (parts.isEmpty()) m.content.isNotBlank()
            else parts.any { it is AgentContentPart.Text && it.text.isNotBlank() },
            hasToolUse = parts.any { it is AgentContentPart.ToolUse },
        )
    }

    fun shapesOfRows(rows: List<MessageEntity>): List<TurnShape> = rows.map { r ->
        var text = false
        var tool = false
        runCatching {
            val arr = JSONArray(r.partsJson)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                when (o.optString("type")) {
                    "text" -> if (o.optString("value").isNotBlank()) text = true
                    "toolUse" -> tool = true
                }
            }
        }
        TurnShape(isAssistant = r.role == "assistant", hasText = text, hasToolUse = tool)
    }

    fun classify(end: SubAgentRunEnd): Pair<SubAgentTermination.Reason, String?> {
        if (end.loopStatus == "cancelled" || end.userCancelled) {
            return SubAgentTermination.Reason.CANCELLED to "stopped before it finished"
        }
        if (end.loopStatus == "timeout") {
            return SubAgentTermination.Reason.TIMEOUT to "the time budget and the wrap-up grace ran out"
        }
        if (end.hasFinalAnswer) return SubAgentTermination.Reason.COMPLETED to null
        if (end.budgetExpired) {
            return SubAgentTermination.Reason.TIMEOUT to "the time budget ran out and the wrap-up turn produced no answer"
        }
        val err = end.errorText?.trim()
        if (!err.isNullOrEmpty()) return SubAgentTermination.Reason.MODEL_ERROR to err.take(300)
        if (end.lastToolStatus == "failed") {
            return SubAgentTermination.Reason.TOOL_ERROR to "the last tool call failed and the model wrote no answer after it"
        }
        return SubAgentTermination.Reason.MODEL_ERROR to "the model returned no text"
    }

    fun summarize(text: String, limit: Int = 300): String {
        val collapsed = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        return if (collapsed.length > limit) collapsed.take(limit) + "…" else collapsed
    }

    fun isFinalAnswer(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        return listOf(NO_FINAL_ANSWER_LABEL, NO_ANSWER_NOTE_PREFIX, EMPTY_RESULT_NOTE_PREFIX).none { t.startsWith(it) }
    }

    fun noAnswerNote(t: SubAgentTermination): String {
        val s = StringBuilder(NO_ANSWER_NOTE_PREFIX).append(" - termination_reason=").append(t.reason.wire)
        t.detail?.takeIf { it.isNotEmpty() }?.let { s.append(": ").append(it) }
        s.append(".")
        t.lastTool?.let { tool ->
            s.append(" Last tool: ").append(tool).append(" → ").append(t.lastToolStatus ?: "unknown")
            t.lastToolSummary?.takeIf { it.isNotEmpty() }?.let { s.append(": ").append(it) }
            s.append(".")
        }
        if (t.partialOutputAvailable) s.append(" It wrote partial notes before ending; they follow below.")
        s.append(" Re-delegate with a narrower task or a larger budget rather than reading its transcript.)")
        return s.toString()
    }

    fun helperResultText(resultText: String, t: SubAgentTermination): String {
        if (t.reason == SubAgentTermination.Reason.COMPLETED) return resultText
        val note = noAnswerNote(t)
        val partial = resultText.trim()
        return if (partial.isEmpty()) note else note + "\n" + partial
    }

    fun toolResultStatus(output: String, success: Boolean): String = when {
        output.startsWith(CANCELLED_MARKER_PREFIX) -> "cancelled"
        success -> "success"
        else -> "failed"
    }

    private const val CANCELLED_MARKER_PREFIX = "<system-reminder>The user cancelled this operation."

    fun lastActivity(rows: List<MessageEntity>): Pair<String, SubAgentToolSnapshot?> {
        var message = ""
        for (r in rows.asReversed()) {
            if (r.role != "assistant") continue
            val text = runCatching {
                val arr = JSONArray(r.partsJson)
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.takeIf { it.optString("type") == "text" }?.optString("value")
                }.joinToString("\n").trim()
            }.getOrDefault("")
            if (text.isNotEmpty()) { message = text; break }
        }
        val titles = HashMap<String, String>()
        val names = HashMap<String, String>()
        for (r in rows) {
            if (r.role != "assistant") continue
            runCatching {
                val arr = JSONArray(r.partsJson)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("type") != "toolUse") continue
                    val v = o.optJSONObject("value") ?: continue
                    val id = v.optString("toolUseId")
                    names[id] = v.optString("name")
                    v.optString("description").takeIf { it.isNotBlank() }?.let { titles[id] = it }
                }
            }
        }
        for (r in rows.asReversed()) {
            val tr = runCatching {
                val arr = JSONArray(r.partsJson)
                (arr.length() - 1 downTo 0).mapNotNull { i ->
                    arr.optJSONObject(i)?.takeIf { it.optString("type") == "toolResult" }?.optJSONObject("value")
                }.firstOrNull()
            }.getOrNull() ?: continue
            val id = tr.optString("toolUseId")
            val output = tr.optString("output")
            var name = tr.optString("name").ifEmpty { names[id].orEmpty() }.ifEmpty { "tool" }
            titles[id]?.let { name += " \"" + summarize(it, 80) + "\"" }
            return message to SubAgentToolSnapshot(name, toolResultStatus(output, tr.optBoolean("success", true)), summarize(output))
        }
        return message to null
    }
}

class SubAgentTerminationStore(private val file: java.io.File?) {
    private val records = LinkedHashMap<String, SubAgentTermination>()

    init {
        val f = file
        if (f != null && f.exists()) {
            runCatching {
                val o = JSONObject(f.readText())
                for (k in o.keys()) SubAgentTermination.fromJson(o.getJSONObject(k))?.let { records[k] = it }
            }.onFailure { AppLogger.warning(TAG, "could not read ${f.name}: ${it.message}") }
        }
    }

    @Synchronized fun record(sessionId: String): SubAgentTermination? = records[sessionId]

    @Synchronized fun begin(sessionId: String, jobId: String, parentSessionId: String?, nowMs: Long = System.currentTimeMillis()) {
        val old = records[sessionId]
        if (old != null && old.jobId == jobId && old.reason == SubAgentTermination.Reason.RUNNING) return
        records[sessionId] = SubAgentTermination(sessionId, jobId, parentSessionId, SubAgentTermination.Reason.RUNNING, startedAtMs = nowMs)
        save()
    }

    @Synchronized fun end(t: SubAgentTermination) {
        var out = t
        val old = records[t.sessionId]
        if (old != null && old.jobId == t.jobId) {
            out = out.copy(startedAtMs = old.startedAtMs)
            if (out.delivery == null) out = out.copy(delivery = old.delivery, deliveryDetail = old.deliveryDetail)
        }
        records[t.sessionId] = out
        save()
    }

    @Synchronized fun setDelivery(sessionId: String, delivery: SubAgentTermination.Delivery, detail: String? = null) {
        val t = records[sessionId] ?: return
        records[sessionId] = t.copy(delivery = delivery, deliveryDetail = detail)
        save()
    }

    @Synchronized fun setDeliveryForJob(jobId: String, delivery: SubAgentTermination.Delivery, detail: String? = null) {
        val t = records.values.firstOrNull { it.jobId == jobId } ?: return
        records[t.sessionId] = t.copy(delivery = delivery, deliveryDetail = detail)
        save()
    }

    @Synchronized fun terminalForJob(jobId: String): SubAgentTermination? =
        records.values.firstOrNull { it.jobId == jobId && it.isTerminal }

    @Synchronized fun resolved(sessionId: String, isLive: Boolean, nowMs: Long = System.currentTimeMillis()): SubAgentTermination? {
        val t = records[sessionId] ?: return null
        if (t.reason == SubAgentTermination.Reason.RUNNING && !isLive) {
            val out = t.copy(
                reason = SubAgentTermination.Reason.INTERRUPTED,
                detail = "the run stopped without reaching an ending (app killed or suspended)",
                endedAtMs = nowMs,
            )
            records[sessionId] = out
            save()
            return out
        }
        return t
    }

    @Synchronized fun count(): Int = records.size

    private fun save() {
        if (records.size > CAPACITY) {
            records.values.sortedBy { it.startedAtMs }.take(records.size - CAPACITY).forEach { records.remove(it.sessionId) }
        }
        val f = file ?: return
        runCatching {
            val o = JSONObject()
            for ((k, v) in records) o.put(k, v.toJson())
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) { f.writeText(o.toString()); tmp.delete() }
        }.onFailure { AppLogger.warning(TAG, "could not write ${f.name}: ${it.message}") }
    }

    companion object {
        private const val TAG = "SubAgentTermination"

        const val CAPACITY = 500

        const val FILE_NAME = "subagent_terminations.json"

        @Volatile var shared: SubAgentTerminationStore = SubAgentTerminationStore(null)
            private set

        fun install(filesDir: java.io.File) {
            shared = SubAgentTerminationStore(java.io.File(filesDir, FILE_NAME))
        }

        internal fun installForTest(store: SubAgentTerminationStore) { shared = store }
    }
}
