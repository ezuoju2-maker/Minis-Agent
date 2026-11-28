package com.openminis.app.tools

import com.openminis.app.data.model.AgentSchemaNode
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

object AgentForgeTool {

    const val NAME = "agent_forge"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Turn a described requirement into a reusable specialist sub agent, stored in the roster " +
            "(the meta-agent pattern: describe the job, get an agent). `create` writes a new roster entry whose " +
            "instructions, when-to-use line and model are derived from what you pass; `preview` builds the same " +
            "spec without saving; `list` shows the roster; `remove` deletes one. A forged agent is then usable by " +
            "name through subagent_task, in this and every later conversation, so only forge agents that will be " +
            "reused — not one-off jobs. Do not forge when the work fits a single delegation with a written brief " +
            "or when the existing roster already covers it: list first if unsure.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of this call, shown to the user. Use the same language as the user."),
            "action" to AgentToolParam(
                "string",
                "\"create\" (default): validate and save a new sub agent. \"preview\": return the composed spec without saving. " +
                    "\"list\": show the current roster. \"remove\": delete the agent named by `target`.",
                enumValues = listOf("create", "preview", "list", "remove"),
            ),
            "name" to AgentToolParam("string", "create/preview. Short roster name, 1-40 characters, must be unique (e.g. 'Log Triage', 'Deck Builder')."),
            "when_to_use" to AgentToolParam("string", "create/preview. One sentence telling the main agent when to delegate here — costs and boundary conditions included. Required unless `goal` is given."),
            "goal" to AgentToolParam("string", "create/preview. What this agent must achieve, in one or two sentences."),
            "steps" to AgentToolParam(
                "array",
                "create/preview. Ordered method: the concrete moves this agent should make, one per item. Keep to 3-6 items.",
                schema = AgentSchemaNode.Arr(description = "Steps", items = AgentSchemaNode.Str("One step")),
            ),
            "constraints" to AgentToolParam(
                "array",
                "create/preview. Hard limits: what it must not do, which paths or sources it may touch, budget or style rules.",
                schema = AgentSchemaNode.Arr(description = "Constraints", items = AgentSchemaNode.Str("One constraint")),
            ),
            "output_format" to AgentToolParam("string", "create/preview. The exact shape of the final answer (e.g. 'a table of file / verdict / reason', 'a 5-bullet summary with sources')."),
            "instructions" to AgentToolParam("string", "create/preview. Optional full replacement for the generated instruction body (<=4000 chars). When set, goal/steps/constraints are ignored."),
            "thinking" to AgentToolParam(
                "string",
                "create/preview. Optional reasoning depth for this agent. Omit to inherit the conversation's setting.",
                enumValues = listOf("low", "medium", "high"),
            ),
            "target" to AgentToolParam("string", "remove only. The agent's name or id."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf(
            "tool_title", "action", "name", "when_to_use", "goal", "steps",
            "constraints", "output_format", "instructions", "thinking", "target",
        ),
    )
}
