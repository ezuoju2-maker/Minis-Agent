package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

internal object EmptyTurnRecovery {

    const val REMINDER: String =
        "\n\n<system-reminder>The previous response was empty. You MUST continue: " +
            "if the last message was a completed sub-agent result or a notice, acknowledge it " +
            "and take the next step or give the user a final answer; if more work is needed, " +
            "respond with the next tool call(s). Do not return an empty response.</system-reminder>"

    fun shouldClassifyAsEmptyTurn(hasText: Boolean, hasToolCall: Boolean, stopReason: String?): Boolean {
        if (hasText || hasToolCall) return false
        if (stopReason == null || stopReason == "stop" || stopReason == "end_turn") return true
        return stopReason == "tool_calls" || stopReason == "tool_use"
    }

    fun turnHasText(turnText: String, blocks: List<AssistantBlock>, turnStartBlockIndex: Int): Boolean =
        turnText.isNotBlank() ||
            blocks.drop(turnStartBlockIndex.coerceAtLeast(0)).any { it.kind == "text" && it.content.isNotBlank() }

    sealed class Plan {
        data class NudgeExistingPart(val tailIndex: Int, val partIndex: Int, val isToolResult: Boolean) : Plan()

        object AppendStandalone : Plan()

        object GiveUp : Plan()
    }

    fun plan(history: List<LLMMessage>, alreadyFired: Boolean): Plan {
        if (alreadyFired) return Plan.GiveUp
        if (history.size < 2) return Plan.GiveUp
        val tailIdx = history.size - 2
        val tail = history[tailIdx]
        if (tail.role != LLMMessage.Role.USER) return Plan.GiveUp

        val trPartIdx = tail.contentParts.indexOfLast { it is AgentContentPart.ToolResult }
        if (trPartIdx >= 0) return Plan.NudgeExistingPart(tailIdx, trPartIdx, isToolResult = true)
        val txtPartIdx = tail.contentParts.indexOfLast { it is AgentContentPart.Text }
        if (txtPartIdx >= 0) return Plan.NudgeExistingPart(tailIdx, txtPartIdx, isToolResult = false)

        return Plan.AppendStandalone
    }
}
