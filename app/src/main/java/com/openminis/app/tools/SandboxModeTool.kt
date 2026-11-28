package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

object SandboxModeTool {

    const val NAME = "sandbox_mode"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Show or change this session's write policy. `read_only` runs no writes at all — no file " +
            "creation, edits, patches or writing shell commands (reads still work). `workspace_write` allows writes " +
            "under /var/minis, /tmp and /root and refuses writes to system directories. `full` applies only the " +
            "per-command risk rules. Report the current mode when the user asks why something was refused; change " +
            "it only when they ask for a mode by name.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of this call, shown to the user. Use the same language as the user."),
            "action" to AgentToolParam(
                "string",
                "\"show\" (default): report the current mode. \"set\": switch to `mode`.",
                enumValues = listOf("show", "set"),
            ),
            "mode" to AgentToolParam(
                "string",
                "set only: the mode to switch to.",
                enumValues = listOf("read_only", "workspace_write", "full"),
            ),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "action", "mode"),
    )
}
