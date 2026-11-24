package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

object CheckpointRestoreTool {

    const val NAME = "checkpoint_restore"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Undo file changes made during this session. Call it with no arguments to revert every file you touched back to its content before you first wrote to it (a file you created is deleted again), or pass `path` to revert a single file. Use it when an edit went wrong and you want a clean base instead of patching on top of broken output. Destructive shell commands are not reverted by this tool — only file_write / file_edit changes are tracked.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this call does, shown to the user. Use the same language as the user."),
            "path" to AgentToolParam("string", "Optional. Revert only this file (as it was passed to file_write / file_edit). Omit to revert everything changed this session."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "path"),
    )
}
