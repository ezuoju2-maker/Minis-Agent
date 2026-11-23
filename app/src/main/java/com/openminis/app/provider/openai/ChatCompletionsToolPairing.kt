package com.openminis.app.provider.openai

import org.json.JSONArray
import org.json.JSONObject

internal object ChatCompletionsToolPairing {

    const val PLACEHOLDER_REPLY = ResponsesToolPairing.PLACEHOLDER_OUTPUT

    const val HISTORY_REPAIR_REPLY = "Tool execution was interrupted by an unexpected error."

    val SYNTHETIC_REPLIES: Set<String> = setOf(PLACEHOLDER_REPLY, HISTORY_REPAIR_REPLY)

    class Result(
        val messages: JSONArray,
        val movedReplies: List<String>,
        val droppedDuplicateReplies: List<String>,
        val droppedOrphanReplies: List<String>,
        val placeholderCalls: List<String>,
    ) {
        val changed: Boolean
            get() = movedReplies.isNotEmpty() || droppedDuplicateReplies.isNotEmpty() ||
                droppedOrphanReplies.isNotEmpty() || placeholderCalls.isNotEmpty()
    }

    fun sanitize(messages: JSONArray): Result {
        if (isWellFormed(messages)) return Result(messages, emptyList(), emptyList(), emptyList(), emptyList())

        val replies = LinkedHashMap<String, ArrayList<Pair<Int, JSONObject>>>()
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            if (msg.optString("role") != "tool") continue
            replies.getOrPut(msg.optString("tool_call_id")) { ArrayList() }.add(i to msg)
        }

        val out = JSONArray()
        val moved = mutableListOf<String>()
        val placeholders = mutableListOf<String>()
        val claimedIds = HashSet<String>()
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i)
            if (msg == null) {
                out.put(messages.get(i))
                continue
            }
            if (msg.optString("role") == "tool") continue
            out.put(msg)
            val calls = toolCallIds(msg) ?: continue
            val adjacent = adjacentReplyRange(messages, i)
            for (id in calls) {
                claimedIds.add(id)
                val candidates = replies[id]
                val pick = candidates?.firstOrNull { it.second.optString("content") !in SYNTHETIC_REPLIES }
                    ?: candidates?.firstOrNull()
                if (pick == null) {
                    placeholders += id
                    out.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", id)
                        put("content", PLACEHOLDER_REPLY)
                    })
                    continue
                }
                candidates!!.remove(pick)
                if (pick.first !in adjacent) moved += id
                out.put(pick.second)
            }
        }

        val duplicates = mutableListOf<String>()
        val orphans = mutableListOf<String>()
        for ((id, left) in replies) {
            for (unused in left) if (id in claimedIds) duplicates += id else orphans += id
        }
        return Result(out, moved, duplicates, orphans, placeholders)
    }

    private fun toolCallIds(msg: JSONObject): List<String>? {
        if (msg.optString("role") != "assistant") return null
        val calls = msg.optJSONArray("tool_calls") ?: return null
        if (calls.length() == 0) return null
        return (0 until calls.length()).map { calls.optJSONObject(it)?.optString("id") ?: "" }
    }

    private fun adjacentReplyRange(messages: JSONArray, index: Int): IntRange {
        var end = index
        while (end + 1 < messages.length() && messages.optJSONObject(end + 1)?.optString("role") == "tool") end++
        return (index + 1)..end
    }

    private fun isWellFormed(messages: JSONArray): Boolean {
        var i = 0
        while (i < messages.length()) {
            val msg = messages.optJSONObject(i)
            if (msg == null) { i++; continue }
            if (msg.optString("role") == "tool") return false
            val calls = toolCallIds(msg)
            if (calls == null) { i++; continue }
            val run = adjacentReplyRange(messages, i)
            val replyIds = run.map { messages.optJSONObject(it)?.optString("tool_call_id") ?: "" }
            if (replyIds.size != calls.size || replyIds.sorted() != calls.sorted()) return false
            i = run.last + 1
        }
        return true
    }
}
