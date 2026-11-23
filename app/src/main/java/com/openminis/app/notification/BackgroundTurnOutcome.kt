package com.openminis.app.notification

import com.openminis.app.ui.chat.ChatMessage

enum class BackgroundTurnOutcome {
    COMPLETED,
    FAILED,
    INTERRUPTED;

    companion object {
        fun decide(streamError: Boolean, turnError: Boolean, canResume: Boolean, userStopped: Boolean): BackgroundTurnOutcome =
            when {
                streamError || turnError -> FAILED
                canResume || userStopped -> INTERRUPTED
                else -> COMPLETED
            }

        fun currentTurnError(messages: List<ChatMessage>): String? {
            val lastSent = messages.indexOfLast { it.role == "user" && !it.isQueued }
            for (i in messages.indices.reversed()) {
                if (i <= lastSent) break
                val m = messages[i]
                if (m.role == "assistant") return m.error?.takeIf { it.isNotBlank() }
            }
            return null
        }
    }
}
