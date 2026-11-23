package com.openminis.app.agent.userask

import org.json.JSONArray
import org.json.JSONObject

data class UserAskRecord(
    val ask: PendingAsk,
    val assistantPartsJson: String? = null,
    val siblingToolUseIds: List<String> = emptyList(),
    val assistantPersisted: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("ask", UserAskCodec.toJson(ask))
        .put("assistantPartsJson", assistantPartsJson ?: JSONObject.NULL)
        .put("siblingToolUseIds", JSONArray(siblingToolUseIds))
        .put("assistantPersisted", assistantPersisted)

    companion object {
        fun fromJson(o: JSONObject): UserAskRecord? {
            val ask = o.optJSONObject("ask")?.let { UserAskCodec.fromJson(it) } ?: return null
            val siblings = o.optJSONArray("siblingToolUseIds")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            return UserAskRecord(
                ask = ask,
                assistantPartsJson = o.optString("assistantPartsJson", "").takeIf { it.isNotEmpty() && !o.isNull("assistantPartsJson") },
                siblingToolUseIds = siblings,
                assistantPersisted = o.optBoolean("assistantPersisted", false),
            )
        }
    }
}

enum class UserAskRecoveryAction {
    ALREADY_DELIVERED,
    DELIVER,
    TIME_OUT_THEN_DELIVER,
    AWAIT_USER,
    DELIVER_DEFERRED,
}

object UserAskRecovery {
    const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    const val INTERRUPTED_RESULT =
        "<system-reminder>The app was closed before this tool finished; it did not run. Run it again if it is still needed.</system-reminder>"

    fun action(r: UserAskRecord, nowMs: Long, toolResultInStore: Boolean): UserAskRecoveryAction = when {
        r.ask.deferred && toolResultInStore -> when {
            r.ask.consumed -> UserAskRecoveryAction.ALREADY_DELIVERED
            r.ask.state.isTerminal -> UserAskRecoveryAction.DELIVER_DEFERRED
            r.ask.isExpired(nowMs) -> UserAskRecoveryAction.TIME_OUT_THEN_DELIVER
            else -> UserAskRecoveryAction.AWAIT_USER
        }
        r.ask.consumed || toolResultInStore -> UserAskRecoveryAction.ALREADY_DELIVERED
        r.ask.state.isTerminal -> UserAskRecoveryAction.DELIVER
        r.ask.isExpired(nowMs) -> UserAskRecoveryAction.TIME_OUT_THEN_DELIVER
        else -> UserAskRecoveryAction.AWAIT_USER
    }

    fun resumesRun(ask: PendingAsk): Boolean = ask.state == UserAskState.ANSWERED || ask.state == UserAskState.TIMED_OUT

    fun purge(records: Map<String, UserAskRecord>, nowMs: Long): Map<String, UserAskRecord> =
        records.filterValues { !it.ask.consumed && nowMs - it.ask.createdAtMs < MAX_AGE_MS }

    fun plainTextFallback(ask: PendingAsk): String =
        (listOf("(Recovered after the app restarted — answers to your earlier user_ask; the original request could not be restored, so please pick up from here.)") +
            answerLines(ask, withStatus = true)).joinToString("\n")

    fun answerLines(ask: PendingAsk, withStatus: Boolean): List<String> {
        val statuses = ask.resultStatuses
        return ask.questions.mapIndexed { i, q ->
            val status = statuses.getOrNull(i)?.wire ?: "unanswered"
            val a = ask.answers.getOrElse(i) { UserAskAnswer() }
            val answer = mutableListOf<String>()
            q.options?.forEachIndexed { j, o -> if (j in a.selected) answer += o.label }
            a.otherText?.takeIf { it.isNotEmpty() }?.let { answer += it }
            a.confirmChoice?.let { answer += it.wire }
            if (!withStatus && a.delegated && answer.isEmpty()) answer += "you decide"
            var line = "${i + 1}. ${q.question} →"
            if (withStatus) line += " $status" + if (answer.isEmpty()) "" else ":"
            if (answer.isNotEmpty()) line += " " + answer.joinToString(", ")
            a.note?.takeIf { it.isNotEmpty() }?.let { line += " (note: $it)" }
            line
        }
    }

    fun toolUseOrder(assistantPartsJson: String?): List<Pair<String, String>> {
        val arr = runCatching { JSONArray(assistantPartsJson ?: "") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("type") != "toolUse") return@mapNotNull null
            val v = o.optJSONObject("value") ?: return@mapNotNull null
            v.optString("toolUseId").takeIf { it.isNotEmpty() }?.let { it to v.optString("name") }
        }
    }

    fun resultRowPartsJson(ask: PendingAsk, resultJson: String, toolOrder: List<Pair<String, String>>): String {
        val order = toolOrder.ifEmpty { listOf(ask.toolCallId to UserAskPromptText.TOOL_NAME) }
        val arr = JSONArray()
        for ((id, name) in order) {
            val mine = id == ask.toolCallId
            val output = if (mine) resultJson else INTERRUPTED_RESULT
            arr.put(
                JSONObject().put("type", "toolResult").put(
                    "value",
                    JSONObject()
                        .put("toolUseId", id)
                        .put("name", name.ifEmpty { if (mine) UserAskPromptText.TOOL_NAME else "" })
                        .put("output", output)
                        .put("success", mine)
                        .put("snapshot", JSONObject().put("type", "text").put("text", output.lines().takeLast(30).joinToString("\n"))),
                ),
            )
        }
        return arr.toString()
    }
}

object UserAskCodec {
    fun toJson(a: PendingAsk): JSONObject = JSONObject()
        .put("requestId", a.requestId)
        .put("sessionId", a.sessionId)
        .put("toolCallId", a.toolCallId)
        .put("questions", JSONArray(a.questions.map(::questionJson)))
        .put("answers", JSONArray(a.answers.map(::answerJson)))
        .put("submitted", JSONArray(a.submitted.sorted()))
        .put("currentIndex", a.currentIndex)
        .put("state", a.state.wire)
        .put("source", a.source?.wire ?: JSONObject.NULL)
        .put("dismissReason", a.dismissReason?.wire ?: JSONObject.NULL)
        .put("createdAtMs", a.createdAtMs)
        .put("expiresAtMs", a.expiresAtMs ?: JSONObject.NULL)
        .put("timeoutMinutes", a.timeoutMinutes)
        .put("resultJson", a.resultJson ?: JSONObject.NULL)
        .put("consumed", a.consumed)
        .put("sessionTitle", a.sessionTitle ?: JSONObject.NULL)
        .put("notifiedIndex", a.notifiedIndex ?: JSONObject.NULL)
        .put("deferred", a.deferred)

    fun fromJson(o: JSONObject): PendingAsk? = runCatching {
        val questions = o.getJSONArray("questions").let { arr -> (0 until arr.length()).mapNotNull { questionFrom(arr.getJSONObject(it)) } }
        if (questions.isEmpty()) return null
        val answersArr = o.optJSONArray("answers")
        val answers = List(questions.size) { i -> answersArr?.optJSONObject(i)?.let(::answerFrom) ?: UserAskAnswer() }
        val submitted = o.optJSONArray("submitted")?.let { arr -> (0 until arr.length()).map { arr.getInt(it) }.toSet() } ?: emptySet()
        PendingAsk(
            requestId = o.getString("requestId"),
            sessionId = o.getString("sessionId"),
            toolCallId = o.getString("toolCallId"),
            questions = questions,
            answers = answers,
            submitted = submitted,
            currentIndex = o.optInt("currentIndex", 0).coerceIn(0, questions.lastIndex),
            state = UserAskState.parse(o.optString("state")) ?: UserAskState.PENDING,
            source = UserAskAnswerSource.parse(o.optStringOrNull("source")),
            dismissReason = UserAskDismissReason.parse(o.optStringOrNull("dismissReason")),
            createdAtMs = o.getLong("createdAtMs"),
            expiresAtMs = if (o.isNull("expiresAtMs") || !o.has("expiresAtMs")) null else o.getLong("expiresAtMs"),
            timeoutMinutes = o.optInt("timeoutMinutes", UserAskSettingsSpec.DEFAULT_TIMEOUT_MINUTES),
            resultJson = o.optStringOrNull("resultJson"),
            consumed = o.optBoolean("consumed", false),
            sessionTitle = o.optStringOrNull("sessionTitle"),
            notifiedIndex = if (o.isNull("notifiedIndex") || !o.has("notifiedIndex")) null else o.getInt("notifiedIndex"),
            deferred = o.optBoolean("deferred", false),
        )
    }.getOrNull()

    private fun JSONObject.optStringOrNull(k: String): String? = if (!has(k) || isNull(k)) null else optString(k)

    private fun questionJson(q: UserAskQuestion): JSONObject = JSONObject()
        .put("type", q.type.wire)
        .put("question", q.question)
        .put("header", q.header ?: JSONObject.NULL)
        .put("options", q.options?.let { os -> JSONArray(os.map { JSONObject().put("label", it.label).put("description", it.description ?: JSONObject.NULL) }) } ?: JSONObject.NULL)
        .put("multiSelect", q.multiSelect)
        .put("impact", q.impact ?: JSONObject.NULL)
        .put("risk", q.risk.wire)
        .put("defaultIndex", q.defaultIndex ?: JSONObject.NULL)

    private fun questionFrom(o: JSONObject): UserAskQuestion? {
        val type = UserAskQuestionType.parse(o.optString("type")) ?: return null
        val options = o.optJSONArray("options")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { UserAskOption(it.getString("label"), it.optStringOrNull("description")) }
        }
        return UserAskQuestion(
            type = type,
            question = o.getString("question"),
            header = o.optStringOrNull("header"),
            options = options,
            multiSelect = o.optBoolean("multiSelect", false),
            impact = o.optStringOrNull("impact"),
            risk = UserAskRisk.parse(o.optString("risk")) ?: UserAskRisk.MEDIUM,
            defaultIndex = if (type == UserAskQuestionType.OPTIONS && o.has("defaultIndex") && !o.isNull("defaultIndex")) o.optInt("defaultIndex") else null,
        )
    }

    private fun answerJson(a: UserAskAnswer): JSONObject = JSONObject()
        .put("selected", JSONArray(a.selected))
        .put("otherText", a.otherText ?: JSONObject.NULL)
        .put("note", a.note ?: JSONObject.NULL)
        .put("delegated", a.delegated)
        .put("confirmChoice", a.confirmChoice?.wire ?: JSONObject.NULL)

    private fun answerFrom(o: JSONObject): UserAskAnswer = UserAskAnswer(
        selected = o.optJSONArray("selected")?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } } ?: emptyList(),
        otherText = o.optStringOrNull("otherText"),
        note = o.optStringOrNull("note"),
        delegated = o.optBoolean("delegated", false),
        confirmChoice = UserAskConfirmChoice.parse(o.optStringOrNull("confirmChoice")),
    )
}

object UserAskDeferred {
    const val STATUS = "deferred"
    const val NOTE = UserAskPromptText.DEFERRED_NOTE

    const val LIFETIME_MS = 60 * 60_000L

    const val ANSWER_REMINDER = "<system-reminder>The user's answers to your earlier question card.</system-reminder>"

    const val COMPACTION_WAIT_MS = 5 * 60_000L

    fun toolResult(questionCount: Int): String =
        (0 until maxOf(questionCount, 1)).joinToString(",", "{\"note\":${JSONObject.quote(NOTE)},\"results\":[", "]}") {
            "{\"i\":$it,\"status\":\"$STATUS\"}"
        }

    fun answerMessage(ask: PendingAsk): String =
        (UserAskRecovery.answerLines(ask, withStatus = false) + ANSWER_REMINDER).joinToString("\n")

    fun sendsAnswer(ask: PendingAsk): Boolean = ask.deferred && ask.state == UserAskState.ANSWERED
}

internal fun UserAskRecovery.replaceToolResultOutput(partsJson: String, toolUseId: String, output: String): String? {
    if (!partsJson.contains(toolUseId)) return null
    val arr = runCatching { JSONArray(partsJson) }.getOrNull() ?: return null
    var found = false
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (o.optString("type") != "toolResult") continue
        val v = o.optJSONObject("value") ?: continue
        if (v.optString("toolUseId") != toolUseId) continue
        v.put("output", output)
        v.optJSONObject("snapshot")?.takeIf { it.optString("type") == "text" }
            ?.put("text", output.lines().takeLast(30).joinToString("\n"))
        found = true
    }
    return if (found) arr.toString() else null
}
