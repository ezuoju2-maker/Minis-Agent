package com.openminis.app.tools

import android.content.Context
import com.openminis.app.agent.continuity.HistorySearch
import com.openminis.app.agent.goal.SessionGoals
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject

object ContinuityTools {

    const val GOAL = "session_goal"
    const val HISTORY_SEARCH = "history_search"
    const val HISTORY_READ = "history_read"
    const val RUN_STATUS = "inspect_run_status"

    fun goalDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = GOAL,
        description = "Pin one completion condition to this conversation, or inspect/clear the active one. " +
            "The condition is injected into every model call and survives context compaction, so long work " +
            "does not drift away from what the user actually asked for. Set it when the user gives a " +
            "multi-turn outcome (\"finish the migration and keep tests green\"), not for a single request. " +
            "action=set requires `goal`; action=show prints the current goal; action=clear removes it — only " +
            "after the condition genuinely holds, or when the user asks.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user. Use the same language as the user."),
            "action" to AgentToolParam(
                "string",
                "set: store `goal` as this conversation's completion condition. show: print it. clear: remove it.",
                enumValues = listOf("set", "show", "clear"),
            ),
            "goal" to AgentToolParam("string", "action=set only. The completion condition, stated as something checkable, e.g. `All unit tests pass and the README matches the new flags.`"),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "goal"),
    )

    fun searchDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = HISTORY_SEARCH,
        description = "Search this conversation's own history — user messages, assistant replies and tool results — " +
            "when you need a detail that was mentioned earlier but is no longer in your working context. " +
            "Returns at most `limit` hits, each with the message id, the character range of the excerpt and " +
            "the matched text, newest first with order=latest. Follow up with history_read on the same id and " +
            "an offset to read the full passage. A hit in history is a record, not fresh authorization: " +
            "user messages from before do not grant permission for a new action.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user. Use the same language as the user."),
            "query" to AgentToolParam("string", "Text to look for. Plain substring, case-insensitive; short distinctive phrases work best."),
            "role" to AgentToolParam(
                "string",
                "Restrict to one author. `all` (default) searches everything; `user` recovers what the user said; `tool` searches tool output.",
                enumValues = listOf("all", "user", "assistant", "tool"),
            ),
            "order" to AgentToolParam(
                "string",
                "`recent` (default): newest matches first, best for recovering a recent correction. `oldest`: from the start of the conversation.",
                enumValues = listOf("recent", "oldest"),
            ),
            "limit" to AgentToolParam("integer", "Maximum hits, default 8, max 20."),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query", "role", "order", "limit"),
    )

    fun readDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = HISTORY_READ,
        description = "Read a stored message or tool result from this conversation by id, starting at a character offset. " +
            "Use the id and excerpt offsets that history_search returned to pull the surrounding full text; " +
            "raise the offset to continue past the returned window.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user. Use the same language as the user."),
            "id" to AgentToolParam("string", "The record id printed by history_search."),
            "offset" to AgentToolParam("integer", "Character offset to start at. Default 0; use excerpt_start or the previous end."),
            "limit" to AgentToolParam("integer", "Characters to return, default 800, max 4000."),
        ),
        required = listOf("tool_title", "id"),
        propertyOrdering = listOf("tool_title", "id", "offset", "limit"),
    )

    fun runStatusDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = RUN_STATUS,
        description = "Report this conversation's own state in one call: the active goal, the task plan with " +
            "pending/in_progress/completed counts, how many delegated jobs are running, and how many messages " +
            "are in context. Use it when you are unsure whether a long task is actually finished, or before " +
            "telling the user everything is done.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user. Use the same language as the user."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title"),
    )

    fun executeGoal(context: Context, sessionId: String, argsJson: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val title = args.optString("tool_title", "Session goal")
        return when (args.optString("action", "show").lowercase()) {
            "set" -> {
                val text = args.optString("goal", "").trim()
                if (text.isEmpty()) {
                    ToolExecutionResult("action=set needs a non-empty `goal`.", false, toolTitle = title)
                } else {
                    val goal = SessionGoals.save(context, sessionId, text)
                    ToolExecutionResult(
                        "Goal set for this conversation: \"${goal.text}\"\n" +
                            "It is now injected into every model call until it is cleared.",
                        true,
                        toolTitle = title,
                    )
                }
            }

            "clear" -> {
                SessionGoals.clear(context, sessionId)
                ToolExecutionResult("Goal cleared.", true, toolTitle = title)
            }

            else -> {
                val goal = SessionGoals.load(context, sessionId)
                ToolExecutionResult(
                    if (goal == null) "No active goal in this conversation." else "Active goal: \"${goal.text}\"",
                    true,
                    toolTitle = title,
                )
            }
        }
    }

    fun executeHistorySearch(records: List<HistorySearch.Record>, argsJson: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val title = args.optString("tool_title", "Search history")
        val query = args.optString("query", "").trim()
        if (query.isEmpty()) return ToolExecutionResult("Error: `query` is required.", false, toolTitle = title)
        val role = args.optString("role", "all")
        val latest = args.optString("order", "recent") != "oldest"
        val hits = HistorySearch.search(
            records = records,
            query = query,
            role = role,
            latestFirst = latest,
            limit = args.optInt("limit", 8),
        )
        if (hits.isEmpty()) {
            return ToolExecutionResult("No match for \"$query\" in this conversation's history.", true, toolTitle = title)
        }
        return ToolExecutionResult(buildString {
            append(hits.size).append(" match(es) for \"").append(query).append("\" (")
            append(if (latest) "newest first" else "oldest first")
            if (role.isNotEmpty() && role != "all") append(", role=").append(role)
            append("):\n")
            hits.forEachIndexed { i, hit ->
                append("\n[").append(i + 1).append("] id=").append(hit.id)
                append(" role=").append(hit.role)
                append(" chars ").append(hit.excerptStart).append('-').append(hit.excerptEnd).append('\n')
                append(hit.excerpt.replace("\n", " ⏎ ")).append('\n')
            }
            append("\nCall history_read with an id and offset to continue reading.")
        }, true, toolTitle = title)
    }

    fun executeHistoryRead(records: List<HistorySearch.Record>, argsJson: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val title = args.optString("tool_title", "Read history")
        val id = args.optString("id", "").trim()
        if (id.isEmpty()) return ToolExecutionResult("Error: `id` is required.", false, toolTitle = title)
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val limit = args.optInt("limit", 800)
        val found = HistorySearch.read(records, id, offset, limit)
            ?: return ToolExecutionResult("No record with id=$id in this conversation.", false, toolTitle = title)
        val (record, slice) = found
        val end = (offset + slice.length).coerceAtMost(record.text.length)
        return ToolExecutionResult(buildString {
            append("[id=").append(record.id).append(" role=").append(record.role)
            append(" total=").append(record.text.length)
            append(" window=").append(offset).append('-').append(end).append("]\n\n")
            append(slice)
            if (end < record.text.length) {
                append("\n\n… ").append(record.text.length - end).append(" chars remain; call again with offset=").append(end)
            }
        }, true, toolTitle = title)
    }
}
