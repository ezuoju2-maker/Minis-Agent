package com.openminis.app.ui.chat

internal object QueuedPromptBatching {

    fun nextInsertBatch(queue: List<QueuedPrompt>): List<QueuedPrompt> {
        val first = queue.firstOrNull { it.isScheduledFire || it.origin == QueuedPromptOrigin.USER }
            ?: return emptyList()
        return if (first.isScheduledFire) listOf(first) else queue.filterNot { it.isScheduledFire }
    }

    fun nextDrainBatch(queue: List<QueuedPrompt>): List<QueuedPrompt> {
        val first = queue.firstOrNull() ?: return emptyList()
        return if (first.isScheduledFire) listOf(first) else queue.filterNot { it.isScheduledFire }
    }

    fun supersededBy(queue: List<QueuedPrompt>, taskId: String): List<String> =
        queue.filter { it.scheduledTaskId == taskId }.map { it.id }
}
