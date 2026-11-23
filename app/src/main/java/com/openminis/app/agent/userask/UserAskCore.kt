package com.openminis.app.agent.userask

import com.openminis.app.data.model.AgentSchemaNode
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONArray
import org.json.JSONObject



enum class UserAskQuestionType(val wire: String) {
    OPTIONS("options"),
    CONFIRM("confirm");

    companion object {
        fun parse(raw: String?): UserAskQuestionType? = entries.firstOrNull { it.wire == raw }
    }
}

enum class UserAskRisk(val wire: String) {
    MEDIUM("medium"),
    HIGH("high");

    companion object {
        fun parse(raw: String?): UserAskRisk? = entries.firstOrNull { it.wire == raw }
    }
}

data class UserAskOption(val label: String, val description: String? = null)

data class UserAskQuestion(
    val type: UserAskQuestionType,
    val question: String,
    val header: String? = null,
    val options: List<UserAskOption>? = null,
    val multiSelect: Boolean = false,
    val impact: String? = null,
    val risk: UserAskRisk = UserAskRisk.MEDIUM,
    val defaultIndex: Int? = null,
) {
    val isOpenEnded: Boolean get() = type == UserAskQuestionType.OPTIONS && options.isNullOrEmpty()

    val timeoutSuggestion: String?
        get() {
            if (type != UserAskQuestionType.OPTIONS) return null
            val opts = options?.takeIf { it.isNotEmpty() } ?: return null
            defaultIndex?.let { i -> if (i in opts.indices) return opts[i].label }
            return opts[0].label
        }
}

data class UserAskRequest(val questions: List<UserAskQuestion>)

class UserAskParseError(message: String) : Exception(message)

object UserAskParser {
    const val MAX_QUESTIONS = 4
    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 4

    const val MAX_INPUT_LENGTH = 1000

    private val RECOMMENDED_MARKERS = listOf("(Recommended)", "(recommended)", "（推荐）", "(推荐)", "Recommended:", "推荐：")

    fun stripRecommended(s: String): String {
        var out = s
        for (m in RECOMMENDED_MARKERS) out = out.replace(m, "")
        return out.trim()
    }

    fun parse(args: JSONObject): Result<UserAskRequest> {
        val raw = when (val v = args.opt("questions")) {
            is JSONArray -> v
            is String -> decodeArray(v, "questions") { return fail(it) }
            else -> null
        }
        if (raw == null || raw.length() == 0) {
            return fail("`questions` is required: call user_ask again with 1-4 question objects, each with `type` and `question`.")
        }
        if (raw.length() > MAX_QUESTIONS) {
            return fail("At most $MAX_QUESTIONS questions per call; you sent ${raw.length()}. Ask only what changes your next step.")
        }
        val questions = mutableListOf<UserAskQuestion>()
        for (i in 0 until raw.length()) {
            val q = when (val item = raw.opt(i)) {
                is JSONObject -> item
                is String -> runCatching { JSONObject(item.trim()) }.getOrNull()
                else -> null
            } ?: return fail("questions[$i] must be an object.")
            questions += parseQuestion(q, i).getOrElse { return Result.failure(it) }
        }
        return Result.success(UserAskRequest(questions))
    }

    fun parse(argsJson: String?): Result<UserAskRequest> {
        val obj = runCatching { JSONObject(argsJson ?: "") }.getOrNull()
            ?: return fail("`questions` is required: call user_ask again with 1-4 question objects, each with `type` and `question`.")
        return parse(obj)
    }

    private fun fail(msg: String): Result<UserAskRequest> = Result.failure(UserAskParseError(msg))

    private inline fun decodeArray(s: String, path: String, onError: (String) -> Nothing): JSONArray? {
        val t = s.trim()
        if (t.isEmpty()) return null
        return try {
            JSONArray(t)
        } catch (e: Exception) {
            onError(
                "`$path` was sent as a string that is not a valid JSON array (${e.message?.take(120)}). " +
                    "Send `$path` as a JSON array, not as a string.",
            )
        }
    }

    private fun str(v: Any?): String? = (v as? String)?.trim()?.takeIf { it.isNotEmpty() }

    internal fun inferredType(q: JSONObject): UserAskQuestionType? {
        val hasOptions = when (val v = q.opt("options")) {
            is JSONArray -> v.length() > 0
            is String -> runCatching { JSONArray(v.trim()).length() > 0 }.getOrDefault(false)
            else -> false
        }
        val d = q.opt("default")
        val optionsOnly = hasOptions || (d != null && d != JSONObject.NULL) || q.opt("multi_select") is Boolean
        val confirmOnly = str(q.opt("impact")) != null || str(q.opt("risk")) != null
        return when {
            optionsOnly && !confirmOnly -> UserAskQuestionType.OPTIONS
            confirmOnly && !optionsOnly -> UserAskQuestionType.CONFIRM
            else -> null
        }
    }

    private fun parseQuestion(q: JSONObject, i: Int): Result<UserAskQuestion> {
        fun bad(msg: String) = Result.failure<UserAskQuestion>(UserAskParseError(msg))
        val type = UserAskQuestionType.parse(str(q.opt("type"))) ?: inferredType(q)
            ?: return bad(
                "questions[$i].type must be \"options\" or \"confirm\": options for a decision " +
                    "(or an open-ended question), confirm for approval of one action.",
            )
        val text = str(q.opt("question")) ?: return bad("questions[$i].question is required.")
        var options: List<UserAskOption>? = null
        val rawOptions = when (val v = q.opt("options")) {
            is JSONArray -> v
            is String -> decodeArray(v, "questions[$i].options") { return bad(it) }
            else -> null
        }
        if (rawOptions != null && rawOptions.length() > 0) {
            if (type != UserAskQuestionType.OPTIONS) {
                return bad("questions[$i] is a confirm: it takes no `options` (the card shows Cancel / Allow). Put the exact action in `question` and the consequence in `impact`.")
            }
            if (rawOptions.length() < MIN_OPTIONS) {
                return bad("questions[$i].options needs at least $MIN_OPTIONS genuinely different choices. With only one sensible choice, do not ask: take it and state the assumption. For an open question, omit `options`.")
            }
            if (rawOptions.length() > MAX_OPTIONS) {
                return bad("questions[$i].options allows at most $MAX_OPTIONS choices (the UI adds Other and You decide).")
            }
            val parsed = mutableListOf<UserAskOption>()
            for (j in 0 until rawOptions.length()) {
                val o = rawOptions.opt(j)
                val dict = o as? JSONObject
                    ?: (o as? String)?.trim()?.takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
                val label = str(dict?.opt("label")) ?: str(o)
                    ?: return bad("questions[$i].options[$j].label is required.")
                parsed += UserAskOption(stripRecommended(label), str(dict?.opt("description")))
            }
            options = parsed
        }
        var risk = UserAskRisk.MEDIUM
        if (type == UserAskQuestionType.CONFIRM) {
            str(q.opt("risk"))?.let { r ->
                risk = UserAskRisk.parse(r) ?: return bad("questions[$i].risk must be \"medium\" or \"high\".")
            }
        }
        var def: Int? = null
        val d = q.opt("default")
        if (type == UserAskQuestionType.OPTIONS && d != null && d != JSONObject.NULL) {
            val idx: Int? = when (d) {
                is Number -> d.toInt()
                else -> str(d)?.toIntOrNull()
            }
            val opts = options
            if (idx == null || opts == null || idx !in opts.indices) {
                return bad("questions[$i].default must be the 0-based index of one of its options (or omit it).")
            }
            def = idx
        }
        val multi = q.opt("multi_select") as? Boolean ?: false
        return Result.success(
            UserAskQuestion(
                type = type,
                question = text,
                header = str(q.opt("header")),
                options = options,
                multiSelect = type == UserAskQuestionType.OPTIONS && options != null && multi,
                impact = if (type == UserAskQuestionType.CONFIRM) str(q.opt("impact")) else null,
                risk = risk,
                defaultIndex = def,
            ),
        )
    }
}


enum class UserAskConfirmChoice(val wire: String) {
    APPROVE("approve"),
    DENY("deny");

    companion object {
        fun parse(raw: String?): UserAskConfirmChoice? = when (raw) {
            "approve", "approveAlways" -> APPROVE
            "deny" -> DENY
            else -> null
        }
    }
}

data class UserAskAnswer(
    val selected: List<Int> = emptyList(),
    val otherText: String? = null,
    val note: String? = null,
    val delegated: Boolean = false,
    val confirmChoice: UserAskConfirmChoice? = null,
) {
    fun withOtherText(text: String?, q: UserAskQuestion): UserAskAnswer {
        val typed = !text.isNullOrBlank()
        return when {
            !typed -> copy(otherText = text)
            q.multiSelect -> copy(otherText = text, delegated = false)
            else -> copy(otherText = text, selected = emptyList(), delegated = false)
        }
    }

    fun withOption(i: Int, q: UserAskQuestion): UserAskAnswer =
        if (q.multiSelect) copy(delegated = false, selected = if (i in selected) selected - i else selected + i)
        else copy(delegated = false, selected = listOf(i), otherText = null)

    fun isComplete(q: UserAskQuestion): Boolean = when (q.type) {
        UserAskQuestionType.CONFIRM -> confirmChoice != null
        UserAskQuestionType.OPTIONS -> when {
            delegated -> true
            !otherText?.trim().isNullOrEmpty() -> true
            else -> selected.isNotEmpty()
        }
    }
}


enum class UserAskResultStatus(val wire: String) {
    ANSWERED("answered"),
    OTHER("other"),
    DELEGATED("delegated"),
    APPROVED("approved"),
    DENIED("denied"),
    TIMEOUT("timeout"),
    DISMISSED("dismissed"),
    UNAVAILABLE("unavailable");

    companion object {
        fun parse(raw: String?): UserAskResultStatus? {
            if (raw == null) return null
            if (raw.startsWith("timeout_")) return TIMEOUT
            if (raw == "approved_always") return APPROVED
            return entries.firstOrNull { it.wire == raw }
        }
    }
}

data class UserAskResultEntry(
    val index: Int,
    val status: UserAskResultStatus,
    val answer: List<String>? = null,
    val note: String? = null,
    val suggestion: String? = null,
) {
    fun toJson(): String = buildString {
        append('{')
        var first = true
        fun key(k: String) { if (!first) append(','); first = false; append(JSONObject.quote(k)).append(':') }
        answer?.let {
            key("answer")
            if (it.size == 1) append(JSONObject.quote(it[0]))
            else append(it.joinToString(",", "[", "]") { s -> JSONObject.quote(s) })
        }
        suggestion?.let { key("default"); append(JSONObject.quote(it)) }
        key("i"); append(index)
        note?.takeIf { it.isNotEmpty() }?.let { key("note"); append(JSONObject.quote(it)) }
        key("status"); append(JSONObject.quote(status.wire))
        append('}')
    }
}

enum class UserAskDismissReason(val wire: String) {
    SUPERSEDED_BY_USER_MESSAGE("superseded_by_user_message"),
    USER_CLOSED_CARD("user_closed_card"),
    STOPPED("stopped"),
    REPLACED_BY_NEW_ASK("replaced_by_new_ask");

    companion object {
        fun parse(raw: String?): UserAskDismissReason? = entries.firstOrNull { it.wire == raw }
    }
}

object UserAskResolver {
    private fun clip(s: String?): String? =
        s?.trim()?.takeIf { it.isNotEmpty() }?.take(UserAskParser.MAX_INPUT_LENGTH)

    fun answered(q: UserAskQuestion, index: Int, a: UserAskAnswer): UserAskResultEntry {
        val note = clip(a.note)
        return when (q.type) {
            UserAskQuestionType.CONFIRM -> when (a.confirmChoice) {
                UserAskConfirmChoice.APPROVE -> UserAskResultEntry(index, UserAskResultStatus.APPROVED)
                else -> UserAskResultEntry(index, UserAskResultStatus.DENIED, note = note)
            }
            UserAskQuestionType.OPTIONS -> {
                if (a.delegated) return UserAskResultEntry(index, UserAskResultStatus.DELEGATED, note = note)
                val other = clip(a.otherText)
                val labels = (q.options ?: emptyList()).filterIndexed { i, _ -> i in a.selected }.map { it.label }
                if (!q.multiSelect && other != null) {
                    return UserAskResultEntry(index, UserAskResultStatus.OTHER, listOf(other), note)
                }
                if (labels.isEmpty() && other != null) {
                    return UserAskResultEntry(index, UserAskResultStatus.OTHER, listOf(other), note)
                }
                val answer = labels + listOfNotNull(other)
                if (answer.isEmpty()) return UserAskResultEntry(index, UserAskResultStatus.DELEGATED, note = note)
                UserAskResultEntry(index, UserAskResultStatus.ANSWERED, answer, note)
            }
        }
    }

    fun timedOut(q: UserAskQuestion, index: Int, timeoutMinutes: Int): UserAskResultEntry =
        UserAskResultEntry(
            index, UserAskResultStatus.TIMEOUT,
            note = "No answer within $timeoutMinutes min. Nothing was applied or approved.",
            suggestion = q.timeoutSuggestion,
        )

    fun dismissed(questions: List<UserAskQuestion>): List<UserAskResultEntry> =
        questions.indices.map { UserAskResultEntry(it, UserAskResultStatus.DISMISSED) }

    fun unavailable(questions: List<UserAskQuestion>): List<UserAskResultEntry> =
        questions.indices.map { UserAskResultEntry(it, UserAskResultStatus.UNAVAILABLE) }

    fun resolveOnTimeout(
        questions: List<UserAskQuestion>,
        answers: List<UserAskAnswer>,
        submitted: Set<Int>,
        timeoutMinutes: Int,
    ): List<UserAskResultEntry> = questions.mapIndexed { i, q ->
        val a = answers.getOrElse(i) { UserAskAnswer() }
        if (i in submitted && a.isComplete(q)) answered(q, i, a) else timedOut(q, i, timeoutMinutes)
    }

    fun encode(entries: List<UserAskResultEntry>): String =
        entries.joinToString(",", "{\"results\":[", "]}") { it.toJson() }

    fun statuses(resultJson: String?): List<UserAskResultStatus> = parseResults(resultJson).map { it.status }

    data class Parsed(val status: UserAskResultStatus, val answer: List<String>, val note: String?)

    fun parseResults(resultJson: String?): List<Parsed> {
        val arr = runCatching { JSONObject(resultJson ?: "").optJSONArray("results") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { k ->
            val o = arr.optJSONObject(k) ?: return@mapNotNull null
            val s = UserAskResultStatus.parse(o.optString("status", "")) ?: return@mapNotNull null
            val answer = when (val a = o.opt("answer")) {
                is JSONArray -> (0 until a.length()).map { a.optString(it) }
                is String -> listOf(a)
                else -> emptyList()
            }
            Parsed(s, answer, o.optString("note", "").ifEmpty { null })
        }
    }
}


enum class UserAskState(val wire: String) {
    PENDING("pending"),
    ANSWERED("answered"),
    TIMED_OUT("timed_out"),
    DISMISSED("dismissed"),
    CANCELLED("cancelled");

    val isTerminal: Boolean get() = this != PENDING

    companion object {
        fun parse(raw: String?): UserAskState? = entries.firstOrNull { it.wire == raw }
    }
}

enum class UserAskAnswerSource(val wire: String) {
    APP("app"),
    NOTIFICATION("notification"),
    TIMEOUT("timeout");

    companion object {
        fun parse(raw: String?): UserAskAnswerSource? = entries.firstOrNull { it.wire == raw }
    }
}

sealed class UserAskSubmitOutcome {
    data object Rejected : UserAskSubmitOutcome()
    data class Advanced(val to: Int) : UserAskSubmitOutcome()
    data object Complete : UserAskSubmitOutcome()
}

data class PendingAsk(
    val requestId: String,
    val sessionId: String,
    val toolCallId: String,
    val questions: List<UserAskQuestion>,
    val answers: List<UserAskAnswer>,
    val submitted: Set<Int> = emptySet(),
    val currentIndex: Int = 0,
    val state: UserAskState = UserAskState.PENDING,
    val source: UserAskAnswerSource? = null,
    val dismissReason: UserAskDismissReason? = null,
    val createdAtMs: Long,
    val expiresAtMs: Long?,
    val timeoutMinutes: Int,
    val resultJson: String? = null,
    val consumed: Boolean = false,
    val sessionTitle: String? = null,
    val notifiedIndex: Int? = null,
    val deferred: Boolean = false,
) {
    companion object {
        fun create(
            requestId: String,
            sessionId: String,
            toolCallId: String,
            questions: List<UserAskQuestion>,
            nowMs: Long,
            timeoutMinutes: Int,
        ) = PendingAsk(
            requestId = requestId,
            sessionId = sessionId,
            toolCallId = toolCallId,
            questions = questions,
            answers = List(questions.size) { UserAskAnswer() },
            createdAtMs = nowMs,
            expiresAtMs = if (timeoutMinutes > 0) nowMs + timeoutMinutes * 60_000L else null,
            timeoutMinutes = timeoutMinutes,
        )

        fun showsPrevious(index: Int): Boolean = index > 0

        fun preselectRecommended(ask: PendingAsk, index: Int): PendingAsk {
            val q = ask.questions.getOrNull(index) ?: return ask
            if (q.type != UserAskQuestionType.OPTIONS || q.multiSelect || q.options.isNullOrEmpty()) return ask
            val a = ask.answers[index]
            if (a.selected.isNotEmpty() || a.delegated || !a.otherText.isNullOrEmpty()) return ask
            return ask.copy(answers = ask.answers.toMutableList().also { it[index] = a.copy(selected = listOf(0)) })
        }
    }

    fun isExpired(nowMs: Long): Boolean {
        if (state != UserAskState.PENDING) return false
        if (expiresAtMs != null) return nowMs >= expiresAtMs
        return deferred && nowMs >= createdAtMs + UserAskDeferred.LIFETIME_MS
    }

    fun noteInteraction(nowMs: Long): PendingAsk =
        if (state != UserAskState.PENDING || timeoutMinutes <= 0) this
        else copy(expiresAtMs = nowMs + timeoutMinutes * 60_000L)

    fun goTo(index: Int, nowMs: Long): PendingAsk? {
        if (state != UserAskState.PENDING || index !in questions.indices) return null
        return copy(currentIndex = index).noteInteraction(nowMs)
    }

    fun submit(index: Int, nowMs: Long): Pair<PendingAsk, UserAskSubmitOutcome> {
        if (state != UserAskState.PENDING || index !in questions.indices ||
            !answers[index].isComplete(questions[index])
        ) return this to UserAskSubmitOutcome.Rejected
        val sub = submitted + index
        val touched = copy(submitted = sub).noteInteraction(nowMs)
        val firstOpen = questions.indices.firstOrNull { it !in sub } ?: return touched to UserAskSubmitOutcome.Complete
        val next = if (index + 1 in questions.indices) index + 1 else firstOpen
        return touched.copy(currentIndex = next) to UserAskSubmitOutcome.Advanced(next)
    }

    fun setAnswer(a: UserAskAnswer, index: Int): PendingAsk {
        if (state != UserAskState.PENDING || index !in answers.indices) return this
        return copy(answers = answers.toMutableList().also { it[index] = a })
    }

    fun resolveAnswered(source: UserAskAnswerSource): PendingAsk? {
        if (state != UserAskState.PENDING) return null
        val entries = questions.mapIndexed { i, q -> UserAskResolver.answered(q, i, answers[i]) }
        return copy(state = UserAskState.ANSWERED, source = source, resultJson = UserAskResolver.encode(entries))
    }

    fun resolveTimedOut(): PendingAsk? {
        if (state != UserAskState.PENDING) return null
        val minutes = if (deferred && timeoutMinutes <= 0) (UserAskDeferred.LIFETIME_MS / 60_000L).toInt() else timeoutMinutes
        val entries = UserAskResolver.resolveOnTimeout(questions, answers, submitted, minutes)
        return copy(state = UserAskState.TIMED_OUT, source = UserAskAnswerSource.TIMEOUT, resultJson = UserAskResolver.encode(entries))
    }

    fun resolveDismissed(reason: UserAskDismissReason): PendingAsk? {
        if (state != UserAskState.PENDING) return null
        return copy(
            state = if (reason == UserAskDismissReason.STOPPED) UserAskState.CANCELLED else UserAskState.DISMISSED,
            dismissReason = reason,
            resultJson = UserAskResolver.encode(UserAskResolver.dismissed(questions)),
        )
    }

    val resultStatuses: List<UserAskResultStatus> get() = UserAskResolver.statuses(resultJson)
}


object UserAskSettingsSpec {
    const val ENABLED_KEY = "agent.tools.userAsk.enabled"
    const val TIMEOUT_MINUTES_KEY = "agent.tools.userAsk.timeoutMinutes"
    const val NOTIFY_IN_BACKGROUND_KEY = "agent.tools.userAsk.notifyInBackground"
    const val DEFAULT_NOTIFY_IN_BACKGROUND = true

    const val DEFAULT_ENABLED = true
    const val DEFAULT_TIMEOUT_MINUTES = 5
    const val MAX_TIMEOUT_MINUTES = 1440

    val TIMEOUT_CHOICES = listOf(1, 2, 5, 10, 30, 60, 0)

    fun clampTimeout(m: Int): Int = m.coerceIn(0, MAX_TIMEOUT_MINUTES)
}


object UserAskExposure {
    data class Decision(val exposed: Boolean, val deferred: Boolean, val reason: String)

    fun decision(
        enabled: Boolean,
        isHelper: Boolean,
        interactive: Boolean,
        foreground: Boolean = true,
        userAskAnswer: Boolean = false,
    ): Decision = when {
        !enabled -> Decision(false, false, "setting off")
        isHelper -> Decision(false, false, "sub agent")
        userAskAnswer && foreground -> Decision(true, false, "answer to a deferred ask, app in foreground")
        userAskAnswer || !interactive -> Decision(true, true, "programmatic turn")
        !foreground -> Decision(true, true, "run started in background")
        else -> Decision(true, false, "interactive foreground run")
    }
}


object UserAskPromptText {
    const val TOOL_NAME = "user_ask"

    const val NOT_AVAILABLE = "Error: user_ask is not available in this run."

    const val DEFERRED_NOTE = "The card is shown; the answer will arrive as the user's next message. End your turn now."

    const val TOOL_LIST_LINE = "- user_ask: Ask the user questions on an in-chat card."

    val TOOL_DESCRIPTION = """
Ask the user questions on an in-chat card. Whenever the user must choose or approve something, wherever the options came from, ask with this tool rather than in plain text; the card waits for them even if they are not watching right now.

Whether to ask: only when the answer changes what you do next. Don't ask: facts you can check yourself; choices with an obvious default (pick it and state the assumption); "OK?" or "continue?"; options that are not genuinely different.

Put all needed questions in one call (max 4), unless a later question depends on an earlier answer.

Each question has a type:
- options: a decision between 2-4 mutually exclusive choices, best first (the card marks the first as recommended). Put the choices in `options`, not in `question`. Omit `options` for an open-ended question. The card also offers Other, a note and "You decide".
- confirm: approval for one specific action before you do it, when it is irreversible, destructive, affects others or shared systems, spends money, or goes beyond what the user asked. `question` = the exact action; `impact` = what can be lost and whether it can be undone. Not for reversible actions or ones the user explicitly asked for.

The app only relays what the user did; it never decides for them. Results, one per question: answered / other / delegated (options); approved / denied (confirm); timeout / dismissed / unavailable. `other` answers and notes are the user's own words: follow them. Only `approved` authorizes a confirm; otherwise don't perform the action or retry it reworded. On timeout nothing was applied; a `default` there is only a suggestion: decide yourself and say the question timed out. After delegated, state your assumption. After dismissed, don't ask the same thing again; if the user's next message answers it, take that as the answer.
""".trim('\n')

    fun definition(): AgentToolDefinition {
        val option = AgentSchemaNode.Obj(
            properties = listOf(
                "label" to AgentSchemaNode.Str("Max 12 chars."),
                "description" to AgentSchemaNode.Str("One line."),
            ),
            required = listOf("label"),
        )
        val question = AgentSchemaNode.Obj(
            properties = listOf(
                "type" to AgentSchemaNode.Str("Required. options = a decision; confirm = approval for one action.", listOf("options", "confirm")),
                "question" to AgentSchemaNode.Str("Required. options: the question only, one sentence. confirm: the exact action (real command or target)."),
                "header" to AgentSchemaNode.Str("Short chip, max 12 chars."),
                "options" to AgentSchemaNode.Arr("type=options only. 2-4 genuinely different choices, best first. Omit for an open-ended question.", option, 2, 4),
                "multi_select" to AgentSchemaNode.Bool("type=options only."),
                "impact" to AgentSchemaNode.Str("type=confirm only: what can be lost, and whether it can be undone."),
                "risk" to AgentSchemaNode.Str("type=confirm only. Card colour only (high = red).", listOf("medium", "high")),
                "default" to AgentSchemaNode.Str("type=options only, optional: 0-based index (e.g. \"0\") of the option you would pick. Never applied; returned to you on timeout as a suggestion."),
            ),
            required = listOf("type", "question"),
        )
        return AgentToolDefinition(
            name = TOOL_NAME,
            description = TOOL_DESCRIPTION,
            parameters = mapOf(
                "questions" to AgentToolParam(
                    "array",
                    "Required. 1-4 question objects, each with `type` and `question`.",
                    schema = AgentSchemaNode.Arr(items = question, minItems = 1, maxItems = 4),
                ),
            ),
            required = listOf("questions"),
        )
    }
}
