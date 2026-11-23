package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

enum class InterruptedTailShape {
    TOOL_RESULT_TAIL,

    ASSISTANT_TOOL_USE,

    CONTINUE_REMINDER,

    UNANSWERED_USER_TURN,

    NONE,
}

object InterruptedTailDetector {

    fun awaitsUserAnswer(lastEntry: LLMMessage?, pendingToolCallIds: Set<String>): Boolean {
        if (lastEntry == null || pendingToolCallIds.isEmpty() || lastEntry.role != LLMMessage.Role.ASSISTANT) return false
        return lastEntry.contentParts.any { it is AgentContentPart.ToolUse && it.id in pendingToolCallIds }
    }

    const val CONTINUE_REMINDER_MARKER = "The user stopped the previous response"

    fun classify(lastEntry: LLMMessage?): InterruptedTailShape {
        if (lastEntry == null) return InterruptedTailShape.NONE
        return when (lastEntry.role) {
            LLMMessage.Role.USER -> {
                val parts = lastEntry.contentParts
                val allToolResults = parts.isNotEmpty() &&
                    parts.all { it is AgentContentPart.ToolResult }
                val isContinueReminder = parts.size == 1 &&
                    (parts.first() as? AgentContentPart.Text)?.text
                        ?.contains(CONTINUE_REMINDER_MARKER) == true
                when {
                    allToolResults -> InterruptedTailShape.TOOL_RESULT_TAIL
                    isContinueReminder -> InterruptedTailShape.CONTINUE_REMINDER
                    parts.isEmpty() -> InterruptedTailShape.NONE
                    else -> InterruptedTailShape.UNANSWERED_USER_TURN
                }
            }
            LLMMessage.Role.ASSISTANT ->
                if (lastEntry.contentParts.any { it is AgentContentPart.ToolUse }) {
                    InterruptedTailShape.ASSISTANT_TOOL_USE
                } else {
                    InterruptedTailShape.NONE
                }
            else -> InterruptedTailShape.NONE
        }
    }

    fun isInterrupted(lastEntry: LLMMessage?): Boolean =
        classify(lastEntry) != InterruptedTailShape.NONE
}
