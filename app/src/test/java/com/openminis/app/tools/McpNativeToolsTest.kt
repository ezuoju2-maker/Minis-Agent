package com.openminis.app.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpNativeToolsTest {

    @Test
    fun `round trip keeps server and tool apart`() {
        val ref = McpNativeTools.parseToolName(McpNativeTools.toolName("notion", "search_pages"))!!
        assertEquals("notion", ref.server)
        assertEquals("search_pages", ref.tool)
    }

    @Test
    fun `server names containing double underscore still split correctly`() {
        val ref = McpNativeTools.parseToolName(McpNativeTools.toolName("my__server", "do_it"))!!
        assertEquals("my__server", ref.server)
        assertEquals("do_it", ref.tool)
    }

    @Test
    fun `malformed names are rejected`() {
        assertNull(McpNativeTools.parseToolName("shell_execute"))
        assertNull(McpNativeTools.parseToolName("mcp__onlyserver"))
        assertNull(McpNativeTools.parseToolName("mcp____"))
    }

    @Test
    fun `arguments come from the nested object first`() {
        val args = JSONObject().put("server", "s").put("tool", "t")
            .put("arguments", JSONObject().put("q", "x"))
        assertEquals("{\"q\":\"x\"}", McpNativeTools.argumentsJson(args))
    }

    @Test
    fun `arguments accept a serialized json string`() {
        val args = JSONObject().put("server", "s").put("tool", "t")
            .put("arguments", "{\"q\":\"x\"}")
        assertEquals("{\"q\":\"x\"}", McpNativeTools.argumentsJson(args))
    }

    @Test
    fun `top level fields are forwarded when arguments is absent`() {
        val args = JSONObject().put("server", "s").put("tool", "t")
            .put("tool_title", "Search").put("query", "q4")
        val out = JSONObject(McpNativeTools.argumentsJson(args))
        assertEquals("q4", out.optString("query"))
        assertTrue(!out.has("server") && !out.has("tool") && !out.has("tool_title"))
    }

    @Test
    fun `shell command quotes payloads safely`() {
        val cmd = McpNativeTools.shellCommand("s", "t", "{\"q\":\"it's fine\"}")
        assertTrue(cmd.startsWith("minis-mcp-cli call 's' 't' '"))
        assertTrue(cmd.contains("'\\''"))
    }

    @Test
    fun `gateway definition exposes the three required fields`() {
        val def = McpNativeTools.gatewayDefinition()
        assertEquals("mcp_call", def.name)
        assertEquals(listOf("tool_title", "server", "tool"), def.required)
        assertTrue(def.parameters.containsKey("arguments"))
    }
}
