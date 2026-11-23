package com.openminis.app.ui.chat

import com.openminis.app.data.model.FallbackStrategy

internal object EmptyTurnFallback {

    enum class Route {
        SAME_MODEL_REMINDER,

        NEXT_MEMBER,

        NEXT_MEMBER_WITH_REMINDER,

        GIVE_UP,
    }

    fun route(
        strategy: FallbackStrategy,
        inGroup: Boolean,
        reminderDue: Boolean,
        hasNextMember: Boolean,
    ): Route {
        if (!inGroup || !hasNextMember) {
            return if (reminderDue) Route.SAME_MODEL_REMINDER else Route.GIVE_UP
        }
        return when (strategy) {
            FallbackStrategy.always ->
                if (reminderDue) Route.NEXT_MEMBER_WITH_REMINDER else Route.NEXT_MEMBER
            FallbackStrategy.default ->
                if (reminderDue) Route.SAME_MODEL_REMINDER else Route.NEXT_MEMBER
        }
    }

    fun movesMember(route: Route): Boolean =
        route == Route.NEXT_MEMBER || route == Route.NEXT_MEMBER_WITH_REMINDER

    fun trailReason(reasoningOnly: Boolean, afterReminder: Boolean): String = when {
        reasoningOnly && afterReminder ->
            "The model returned only its thinking after a tool result, even after a reminder — no reply and no tool call."
        reasoningOnly -> "The model returned only its thinking — no reply and no tool call."
        afterReminder -> "Empty response, even after a reminder"
        else -> "Empty response"
    }
}
