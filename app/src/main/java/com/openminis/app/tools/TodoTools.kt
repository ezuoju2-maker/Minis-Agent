package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentSchemaNode
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object TodoTools {

    const val NAME = "todo_write"
    private data class Item(val content: String, val status: String, val priority: String = "medium")

    private fun dir(context: Context) = File(context.filesDir, "todos").apply { mkdirs() }
    private fun file(context: Context, sessionId: String) = File(dir(context), "$sessionId.json")

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Create and update a structured task list for the current conversation. Use it for complex, multi-step work (3+ steps): PLAN FIRST — capture all steps up front with priorities before doing the work, then mark exactly ONE task as in_progress while you work on it, mark it completed when done, and update the list as the plan changes. This is your working plan: it is injected into every turn and survives context compaction, so you can resume a long task after compaction by reading it. Set priority=high on steps that are blockers or critical to the user's goal. Do not use it for trivial single-step requests. Passing an empty todos array clears the list.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of this plan update, shown to the user. Use the same language as the user."),
            "todos" to AgentToolParam(
                "array",
                "The full task list, replacing the previous one. Include every item (done and pending) each time. Set exactly one item to in_progress while working.",
                schema = AgentSchemaNode.Arr(
                    description = "Task items",
                    items = AgentSchemaNode.Obj(
                        description = "One task",
                        properties = listOf(
                            "content" to AgentSchemaNode.Str("Short imperative description of the step"),
                            "status" to AgentSchemaNode.Str(
                                "One of: pending, in_progress, completed",
                                enumValues = listOf("pending", "in_progress", "completed"),
                            ),
                            "priority" to AgentSchemaNode.Str(
                                "Optional priority. Use high for blockers/critical steps, low for nice-to-have. Default medium.",
                                enumValues = listOf("high", "medium", "low"),
                            ),
                        ),
                        required = listOf("content", "status"),
                    ),
                ),
            ),
        ),
        required = listOf("tool_title", "todos"),
        propertyOrdering = listOf("tool_title", "todos"),
    )

    fun execute(context: Context, sessionId: String, argsJson: String): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (_: Exception) {
            return ToolExecutionResult("Error: invalid arguments", false)
        }
        val toolTitle = args.optString("tool_title", "Update task list")
        val arr = args.optJSONArray("todos")
            ?: return ToolExecutionResult("Error: 'todos' must be an array", false)

        val items = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val content = o.optString("content", "").trim()
            if (content.isEmpty()) continue
            val status = when (o.optString("status", "pending").lowercase()) {
                "in_progress", "in-progress", "running" -> "in_progress"
                "completed", "done" -> "completed"
                else -> "pending"
            }
            val priority = when (o.optString("priority", "medium").lowercase()) {
                "high" -> "high"
                "low" -> "low"
                else -> "medium"
            }
            items.add(Item(content, status, priority))
        }

        val out = JSONObject().apply {
            put("todos", JSONArray().apply {
                for (it in items) put(JSONObject().put("content", it.content).put("status", it.status).put("priority", it.priority))
            })
        }
        file(context, sessionId).writeText(out.toString())

        val done = items.count { it.status == "completed" }
        val summary = buildString {
            append("Task list updated: $done/${items.size} completed.\n")
            if (items.isEmpty()) {
                append("(list cleared)")
            } else {
                items.forEachIndexed { idx, it ->
                    val mark = when (it.status) {
                        "completed" -> "[x]"
                        "in_progress" -> "[>]"
                        else -> "[ ]"
                    }
                    append("$mark ${idx + 1}. ${it.content}\n")
                }
            }
        }
        return ToolExecutionResult(summary, true, toolTitle = toolTitle)
    }

    fun promptFragment(context: Context, sessionId: String): String? {
        val f = file(context, sessionId)
        if (!f.exists()) return null
        val items = try {
            val arr = JSONObject(f.readText()).optJSONArray("todos") ?: return null
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Item(
                    o.optString("content", ""),
                    o.optString("status", "pending"),
                    o.optString("priority", "medium"),
                )
            }
        } catch (_: Exception) { return null }
        if (items.isEmpty()) return null
        val done = items.count { it.status == "completed" }
        val hasPending = items.any { it.status != "completed" }
        return buildString {
            append("\n\nCurrent task plan ($done/${items.size} completed; keep it current with todo_write):\n")
            items.forEachIndexed { idx, it ->
                val mark = when (it.status) {
                    "completed" -> "[x]"
                    "in_progress" -> "[>]"
                    else -> "[ ]"
                }
                val pri = if (it.priority == "high") " [HIGH]" else if (it.priority == "low") " [low]" else ""
                append("$mark ${idx + 1}. ${it.content}$pri\n")
            }
            if (hasPending) {
                append("This plan is still in progress. If the user's original request is still active, continue with the in_progress item (or the highest-priority pending item if none is in_progress) — do not restart completed steps.\n")
            }
        }
    }

    fun clear(context: Context, sessionId: String) {
        file(context, sessionId).delete()
    }
}
