package com.openminis.app.scheduled

object ScheduledTargetDelivery {

    fun resolveTarget(explicit: String?, session: String?, callerSession: String?): Pair<String, Boolean> {
        val e = explicit?.trim()
        if (!e.isNullOrEmpty()) return e.lowercase() to false
        return (if ((session ?: callerSession) != null) "follow-up" else "new") to true
    }

    sealed class Destination {
        abstract val kind: String

        data class FollowUp(val sessionId: String, val title: String?, val isCallerSession: Boolean) : Destination() {
            override val kind = "follow-up"
        }

        data class NewChat(val label: String?) : Destination() {
            override val kind = "new"
        }

        data class Rerun(val sessionId: String, val messageId: String, val title: String?) : Destination() {
            override val kind = "rerun"
        }
    }

    fun sentence(d: Destination, defaulted: Boolean): String = when (d) {
        is Destination.FollowUp -> {
            val name = sessionName(d.sessionId, d.title)
            if (d.isCallerSession) {
                "Delivery: each fire runs as a new turn IN THIS CHAT ($name)." +
                    if (defaulted) {
                        " --target defaulted to follow-up because this command ran inside a chat; " +
                            "to use a separate chat instead, delete this task and re-create it with --target new."
                    } else ""
            } else {
                "Delivery: each fire runs as a new turn in ANOTHER chat ($name), NOT in this conversation. " +
                    "If the result should come back here, delete this task and re-create it without --session."
            }
        }
        is Destination.NewChat -> {
            val titled = d.label?.takeIf { it.isNotBlank() }?.let { " titled “Scheduled · $it”" } ?: ""
            "Delivery: each fire opens a NEW, separate chat$titled; the result will NOT appear in this conversation." +
                if (defaulted) {
                    " --target defaulted to new because no chat was running this command."
                } else {
                    " If the user wanted it in this chat, delete this task and re-create it with --target follow-up."
                }
        }
        is Destination.Rerun ->
            "Delivery: each fire RE-RUNS user message ${d.messageId.take(8)} in ${sessionName(d.sessionId, d.title)}: " +
                "the existing reply is regenerated, and no new prompt is added."
    }

    fun summary(d: Destination, defaulted: Boolean): Map<String, Any> {
        val out = linkedMapOf<String, Any>("target" to d.kind, "defaulted" to defaulted)
        when (d) {
            is Destination.FollowUp -> {
                out["sessionId"] = d.sessionId
                out["isThisChat"] = d.isCallerSession
                d.title?.let { out["sessionTitle"] = it }
            }
            is Destination.NewChat -> d.label?.takeIf { it.isNotBlank() }?.let { out["newChatTitle"] = "Scheduled · $it" }
            is Destination.Rerun -> {
                out["sessionId"] = d.sessionId
                out["messageId"] = d.messageId
                d.title?.let { out["sessionTitle"] = it }
            }
        }
        return out
    }

    private fun sessionName(sid: String, title: String?): String =
        if (!title.isNullOrEmpty()) "session ${sid.take(8)} “$title”" else "session ${sid.take(8)}"
}
