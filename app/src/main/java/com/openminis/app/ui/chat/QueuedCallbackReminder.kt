package com.openminis.app.ui.chat

import com.openminis.app.agent.jobs.AgentCallback
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

internal object QueuedCallbackReminder {

    class Plan(val promptIds: List<String>, val text: String)

    fun pending(queue: List<QueuedPrompt>): List<Pair<QueuedPrompt, AgentCallback>> =
        queue.mapNotNull { prompt ->
            if (prompt.reminderSent || prompt.isScheduledFire) return@mapNotNull null
            if (prompt.origin != QueuedPromptOrigin.PROGRAMMATIC) return@mapNotNull null
            val callback = AgentCallback.parse(prompt.text) ?: return@mapNotNull null
            if (callback.kind != AgentCallback.Kind.FINISHED) return@mapNotNull null
            prompt to callback
        }

    fun plan(queue: List<QueuedPrompt>): Plan? {
        val pending = pending(queue)
        if (pending.isEmpty()) return null
        return Plan(pending.map { it.first.id }, text(pending.map { it.second }))
    }

    fun text(callbacks: List<AgentCallback>): String {
        val jobs = callbacks.joinToString("; ") {
            "${it.title.ifBlank { "sub agent" }} (job ${it.jobId}, ${it.status})"
        }
        return "<system-reminder>\n" +
            "Background update: sub agent(s) you delegated have finished, and the result is queued for this conversation: $jobs\n" +
            "\n" +
            "It has NOT been delivered to you yet. It arrives as a new message only after your current turn ends, i.e. when you reply without calling more tools. Your call:\n" +
            "- If the result could change what you do next, or your remaining work is short or non-essential, wrap up now and end the turn with a brief summary. The result will then arrive.\n" +
            "- If your current work is independent of it and must finish first, keep going. The result stays queued and won't be lost.\n" +
            "\n" +
            "Don't poll for it, re-delegate, or mention this reminder to the user.\n" +
            "</system-reminder>"
    }

    fun inject(messages: List<LLMMessage>, text: String): List<LLMMessage>? {
        val tail = messages.lastOrNull() ?: return null
        if (tail.role != LLMMessage.Role.USER) return null
        val suffix = "\n\n$text"
        val parts = tail.contentParts
        val newTail = when (val last = parts.lastOrNull()) {
            null -> tail.copy(content = tail.content + suffix)
            is AgentContentPart.Text -> tail.copy(contentParts = parts.dropLast(1) + AgentContentPart.Text(last.text + suffix))
            is AgentContentPart.ToolResult -> tail.copy(contentParts = parts.dropLast(1) + last.copy(content = last.content + suffix))
            else -> tail.copy(contentParts = parts + AgentContentPart.Text(text))
        }
        return messages.dropLast(1) + newTail
    }

    fun markSent(queue: List<QueuedPrompt>, plan: Plan): List<QueuedPrompt> {
        val ids = plan.promptIds.toSet()
        return queue.map { if (it.id in ids && !it.reminderSent) it.copy(reminderSent = true) else it }
    }
}
