package com.openminis.app.scheduled

import com.openminis.app.data.model.LLMStreamChunk
import org.json.JSONObject
import java.util.UUID

data class PrefilledToolCall(
    val toolName: String,
    val argsJson: String,
) {
    fun args(): JSONObject = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }

    val shellCommand: String?
        get() = if (toolName == SHELL_TOOL) args().optString("command", "").ifBlank { null } else null

    fun validationError(): String? {
        if (toolName !in SUPPORTED_TOOLS) {
            return "prefilled tool '$toolName' is not supported yet (supported: ${SUPPORTED_TOOLS.joinToString()})"
        }
        val a = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return "prefilled tool arguments must be a JSON object"
        return when (toolName) {
            SHELL_TOOL -> when {
                a.optString("command", "").isBlank() -> "shell prefill needs a non-empty command"
                a.optString("command", "").length > MAX_COMMAND_LENGTH ->
                    "shell command is longer than $MAX_COMMAND_LENGTH characters; put the script in a file and run that file"
                a.has("timeout") && a.optInt("timeout", -1) !in TIMEOUT_RANGE_SEC ->
                    "shell timeout must be between ${TIMEOUT_RANGE_SEC.first}s and ${TIMEOUT_RANGE_SEC.last}s"
                else -> null
            }
            else -> null
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("tool", toolName)
        .put("args", args())

    companion object {
        const val SHELL_TOOL = "shell_execute"

        const val MAX_COMMAND_LENGTH = 16_000
        val TIMEOUT_RANGE_SEC: IntRange = 1..3600

        val SUPPORTED_TOOLS: Set<String> = setOf(SHELL_TOOL)

        private val ALIASES = mapOf("shell" to SHELL_TOOL, "sh" to SHELL_TOOL, "bash" to SHELL_TOOL)

        fun canonicalToolName(raw: String): String {
            val t = raw.trim()
            return ALIASES[t.lowercase()] ?: t
        }

        fun shell(command: String, title: String? = null, timeoutSec: Int? = null): PrefilledToolCall =
            of(SHELL_TOOL, JSONObject().put("command", command).apply { timeoutSec?.let { put("timeout", it) } }, title)

        fun of(toolName: String, args: JSONObject, title: String? = null): PrefilledToolCall {
            val name = canonicalToolName(toolName)
            val copy = JSONObject(args.toString())
            if (copy.optString("tool_title", "").isBlank()) {
                val fallback = title?.trim()?.takeIf { it.isNotEmpty() }
                    ?: copy.optString("command", "").lineSequence().firstOrNull()?.trim()?.take(40)
                        ?.takeIf { it.isNotEmpty() }
                if (fallback != null) copy.put("tool_title", fallback)
            }
            return PrefilledToolCall(name, copy.toString())
        }

        fun fromJson(o: JSONObject?): PrefilledToolCall? {
            if (o == null) return null
            val tool = o.optString("tool", "").takeIf { it.isNotBlank() } ?: return null
            val args = o.optJSONObject("args") ?: JSONObject()
            return PrefilledToolCall(tool, args.toString())
        }

        fun newToolUseId(): String = ID_PREFIX + UUID.randomUUID().toString().replace("-", "").take(24)

        const val ID_PREFIX = "call_sched_"
    }
}

data class ScriptedToolTurn(val calls: List<Call>) {
    data class Call(val id: String, val toolName: String, val argsJson: String)

    fun asStreamChunks(): List<LLMStreamChunk> = buildList {
        add(LLMStreamChunk.Started)
        for (c in calls) {
            add(LLMStreamChunk.ToolUseStart(c.id, c.toolName))
            add(LLMStreamChunk.ToolCallComplete(c.id, c.toolName, runCatching { JSONObject(c.argsJson) }.getOrElse { JSONObject() }))
        }
        add(LLMStreamChunk.Finished(STOP_REASON))
    }

    companion object {
        const val STOP_REASON = "tool_use"

        fun isScriptedId(id: String): Boolean = id.startsWith(PrefilledToolCall.ID_PREFIX)

        fun from(prefills: List<PrefilledToolCall>, availableTools: Set<String>): ScriptedToolTurn? {
            val calls = prefills
                .filter { it.validationError() == null && it.toolName in availableTools }
                .map { Call(PrefilledToolCall.newToolUseId(), it.toolName, it.argsJson) }
            return if (calls.isEmpty()) null else ScriptedToolTurn(calls)
        }
    }
}
