package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject

object McpNativeTools {

    const val GATEWAY = "mcp_call"

    const val PREFIX = "mcp__"

    const val CLI = "minis-mcp-cli"

    data class Ref(val server: String, val tool: String)

    fun toolName(server: String, tool: String): String = PREFIX + server.trim() + "__" + tool.trim()

    fun parseToolName(name: String): Ref? {
        if (!name.startsWith(PREFIX)) return null
        val body = name.substring(PREFIX.length)
        val cut = body.lastIndexOf("__")
        if (cut <= 0 || cut >= body.length - 2) return null
        val server = body.substring(0, cut).trim()
        val tool = body.substring(cut + 2).trim()
        if (server.isEmpty() || tool.isEmpty()) return null
        return Ref(server, tool)
    }

    fun argumentsJson(args: JSONObject): String {
        val nested = args.optJSONObject("arguments")
        if (nested != null) return nested.toString()
        val text = args.optString("arguments", "").trim()
        if (text.startsWith("{") || text.startsWith("[")) return text
        val payload = JSONObject()
        val keys = args.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k == "tool_title" || k == "server" || k == "tool" || k == "arguments") continue
            payload.put(k, args.opt(k))
        }
        return payload.toString()
    }

    fun shellCommand(server: String, tool: String, argumentsJson: String): String {
        val payload = argumentsJson.trim().ifEmpty { "{}" }
        return CLI + " call " + quote(server) + " " + quote(tool) + " " + quote(payload)
    }

    fun listCommand(server: String): String = CLI + " tools " + quote(server) + " --pretty"

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun gatewayDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = GATEWAY,
        description = "Call a tool exposed by a configured MCP server in one step, without shell escaping. " +
            "Use it only when this conversation has MCP servers (see the MCP section of your instructions). " +
            "Pass `server` exactly as listed there and `tool` exactly as the server reports it. " +
            "If you do not know the tool name yet, run " + CLI + " tools <server> once in shell_execute, " +
            "then call this tool with the exact name. Arguments go in `arguments` as a JSON object matching " +
            "the server's input schema; fields you put at the top level (except server/tool/tool_title) are " +
            "also forwarded, so both styles work.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user (e.g. 'Search Notion for Q4 plan'). Use the same language as the user."),
            "server" to AgentToolParam("string", "MCP server id exactly as listed in the MCP section (e.g. 'notion', 'github')."),
            "tool" to AgentToolParam("string", "Exact tool name reported by that server, case sensitive."),
            "arguments" to AgentToolParam("string", "JSON object of arguments for the tool, e.g. {\"query\":\"q4 plan\"}. Omit when the tool takes no arguments."),
        ),
        required = listOf("tool_title", "server", "tool"),
        propertyOrdering = listOf("tool_title", "server", "tool", "arguments"),
    )
}
