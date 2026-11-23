package com.openminis.app.agent.userask

object UserAskNotificationPlan {
    const val MAX_ACTIONS = 3

    const val ACTION_OTHER = "other"
    const val ACTION_CANCEL = "confirm.cancel"
    const val ACTION_ALLOW = "confirm.allow"

    fun optionActionId(index: Int) = "opt:$index"

    fun optionIndex(actionId: String): Int? =
        if (actionId.startsWith("opt:")) actionId.removePrefix("opt:").toIntOrNull() else null

    fun notifiedIndex(ask: PendingAsk): Int? = ask.questions.indices.firstOrNull { it !in ask.submitted }

    fun tag(requestId: String, index: Int) = "user-ask-$requestId-q$index"

    fun allTags(requestId: String, questionCount: Int): List<String> =
        (0 until maxOf(questionCount, 1)).map { tag(requestId, it) }

    enum class Layout {
        CONFIRM,
        OPTIONS,
        REPLY_ONLY,
        OPEN_ONLY,
    }

    data class Content(
        val index: Int,
        val layout: Layout,
        val optionButtons: List<Int> = emptyList(),
        val textReply: Boolean = false,
        val body: String,
        val isApproval: Boolean = false,
        val highRisk: Boolean = false,
    )

    fun content(ask: PendingAsk): Content? {
        val i = notifiedIndex(ask) ?: return null
        val q = ask.questions[i]
        val body = body(ask, i)
        if (q.type == UserAskQuestionType.CONFIRM) {
            return Content(i, Layout.CONFIRM, body = body, isApproval = true, highRisk = q.risk == UserAskRisk.HIGH)
        }
        val options = q.options.orEmpty()
        return when {
            q.multiSelect -> Content(i, Layout.OPEN_ONLY, body = body)
            options.isEmpty() -> Content(i, Layout.REPLY_ONLY, textReply = true, body = body)
            options.size + 1 <= MAX_ACTIONS -> Content(i, Layout.OPTIONS, options.indices.toList(), textReply = true, body = body)
            options.size <= MAX_ACTIONS -> Content(i, Layout.OPTIONS, options.indices.toList(), body = body)
            else -> Content(i, Layout.OPEN_ONLY, body = body)
        }
    }

    fun body(ask: PendingAsk, index: Int): String {
        val q = ask.questions[index]
        val text = if (q.type == UserAskQuestionType.CONFIRM) q.question.take(100) else q.question
        return if (ask.questions.size > 1) "${index + 1}/${ask.questions.size} · $text" else text
    }

    fun indexToPost(ask: PendingAsk, appInForeground: Boolean, enabled: Boolean, nowMs: Long): Int? {
        if (!enabled || appInForeground) return null
        if (ask.state != UserAskState.PENDING || ask.isExpired(nowMs)) return null
        val i = notifiedIndex(ask) ?: return null
        return i.takeIf { it != ask.notifiedIndex }
    }

    fun alertsWithSound(ask: PendingAsk): Boolean = ask.notifiedIndex == null

    enum class Outcome {
        ADVANCED,
        COMPLETE,
        REJECTED,
        STALE,
        NOT_FOUND,
    }

    fun apply(ask: PendingAsk, index: Int, actionId: String, text: String?, nowMs: Long): Pair<PendingAsk, Outcome> {
        if (ask.state != UserAskState.PENDING || ask.isExpired(nowMs)) return ask to Outcome.STALE
        if (index != notifiedIndex(ask)) return ask to Outcome.STALE
        val q = ask.questions[index]
        val cur = ask.answers[index]
        val opt = optionIndex(actionId)
        val answer = when {
            q.type == UserAskQuestionType.OPTIONS && !q.multiSelect && opt != null && opt in q.options.orEmpty().indices ->
                cur.copy(selected = listOf(opt), otherText = null, delegated = false)
            q.type == UserAskQuestionType.OPTIONS && !q.multiSelect && actionId == ACTION_OTHER && !text.isNullOrBlank() ->
                cur.copy(selected = emptyList(), otherText = text.trim().take(UserAskParser.MAX_INPUT_LENGTH), delegated = false)
            q.type == UserAskQuestionType.CONFIRM && actionId == ACTION_ALLOW -> cur.copy(confirmChoice = UserAskConfirmChoice.APPROVE)
            q.type == UserAskQuestionType.CONFIRM && actionId == ACTION_CANCEL -> cur.copy(confirmChoice = UserAskConfirmChoice.DENY)
            else -> return ask to Outcome.REJECTED
        }
        val (next, outcome) = ask.setAnswer(answer, index).submit(index, nowMs)
        return when (outcome) {
            is UserAskSubmitOutcome.Advanced -> PendingAsk.preselectRecommended(next, outcome.to) to Outcome.ADVANCED
            UserAskSubmitOutcome.Complete -> next to Outcome.COMPLETE
            UserAskSubmitOutcome.Rejected -> ask to Outcome.REJECTED
        }
    }
}
