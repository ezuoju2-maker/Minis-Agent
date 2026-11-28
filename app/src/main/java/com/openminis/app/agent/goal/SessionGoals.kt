package com.openminis.app.agent.goal

import android.content.Context
import org.json.JSONObject
import java.io.File

object SessionGoals {

    data class Goal(val text: String, val createdAt: Long, val continuations: Int = 0)

    private fun dir(context: Context) = File(context.filesDir, "minis-global/goals").apply { mkdirs() }

    private fun file(context: Context, sessionId: String) =
        File(dir(context), "${sessionId.ifEmpty { "default" }}.json")

    fun load(context: Context, sessionId: String): Goal? {
        val f = file(context, sessionId)
        if (!f.isFile) return null
        return runCatching { decode(f.readText()) }.getOrNull()
    }

    fun save(context: Context, sessionId: String, text: String): Goal {
        val goal = Goal(text.trim(), System.currentTimeMillis())
        runCatching { file(context, sessionId).writeText(encode(goal)) }
        return goal
    }

    fun clear(context: Context, sessionId: String) {
        runCatching { file(context, sessionId).delete() }
    }

    fun noteContinuation(context: Context, sessionId: String): Goal? {
        val current = load(context, sessionId) ?: return null
        val next = current.copy(continuations = current.continuations + 1)
        runCatching { file(context, sessionId).writeText(encode(next)) }
        return next
    }

    fun promptFragment(context: Context, sessionId: String): String? {
        val goal = load(context, sessionId) ?: return null
        if (goal.text.isBlank()) return null
        return buildString {
            append("\n\nActive goal (thread-scoped; survives context compaction):\n")
            append("\"${goal.text}\"\n")
            append(
                "The conversation is not finished until this condition holds. Keep it in view when " +
                    "choosing the next action, and do not silently abandon it. If you believe it is " +
                    "satisfied, say so plainly and call session_goal with action=clear — clearing is " +
                    "otherwise the user's decision, not yours.",
            )
        }
    }

    internal fun encode(goal: Goal): String = JSONObject()
        .put("text", goal.text)
        .put("createdAt", goal.createdAt)
        .put("continuations", goal.continuations)
        .toString()

    internal fun decode(raw: String): Goal? {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val text = obj.optString("text", "").trim()
        if (text.isEmpty()) return null
        return Goal(text, obj.optLong("createdAt", 0L), obj.optInt("continuations", 0))
    }
}
