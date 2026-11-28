package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

object LessonTool {

    const val NAME = "lesson_write"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "The self-improving notebook. It is how you stop repeating a mistake the user already " +
            "corrected, weeks later and in a fresh session.\n\nWHEN TO WRITE: the user corrects you " +
            "(\"不对\", \"应该是…\", \"以后都…\", \"don't do that\"), states a standing preference or constraint " +
            "(\"reports go to the out folder\", \"always answer in Chinese\"), or points out that you forgot " +
            "something they told you. Write ONE imperative rule, at most 120 characters, phrased so it still " +
            "makes sense with no other context (e.g. \"Put generated files in /var/minis/out, never in the chat " +
            "body\"). Do NOT record one-off task details, passwords, or anything the user did not actually ask " +
            "for.\n\nHOW IT WORKS: lessons persist on disk across sessions and are recalled into your prompt " +
            "automatically when a later task resembles them; repeats raise their confidence and exact duplicates " +
            "are merged. `action=list` shows what you have learned; `action=forget` removes a wrong or obsolete " +
            "rule (by id or by text). Apply a recalled lesson silently — never recite the notebook to the user.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary of this call, shown to the user. Use the same language as the user.",
            ),
            "action" to AgentToolParam(
                "string",
                "\"write\" (default): save or reinforce `text`. \"list\": show the notebook. \"forget\": drop the " +
                    "entry named by `id` (or a close match of `text`).",
                enumValues = listOf("write", "list", "forget"),
            ),
            "text" to AgentToolParam(
                "string",
                "write/forget: one imperative, reusable rule (≤120 chars) or the text to forget.",
            ),
            "tags" to AgentToolParam(
                "array",
                "write only, optional. Short lowercase topic tags (e.g. [\"files\", \"reports\"]).",
            ),
            "id" to AgentToolParam(
                "string",
                "forget only, optional. The id printed by `list`; works without it too.",
            ),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "action", "text", "tags", "id"),
    )
}
