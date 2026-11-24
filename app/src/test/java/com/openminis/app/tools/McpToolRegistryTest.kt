package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolRegistryTest {

    private val sample = """{"ok":true,"result":{"server":"notion","tools":[{"name":"search_pages","description":"Search pages by query","inputSchema":{"type":"object","properties":{"query":{"type":"string"}}}}]}}"""

    @Test
    fun `ingest parses the cli payload and exposes definitions`() {
        McpToolRegistry.clear()
        val added = McpToolRegistry.ingest("notion", sample)
        assertEquals(1, added)
        val defs = McpToolRegistry.definitions()
        assertEquals(1, defs.size)
        assertEquals("mcp__notion__search_pages", defs[0].name)
        assertTrue(defs[0].description.contains("Search pages by query"))
        assertTrue(defs[0].description.contains("Arguments: query"))
        assertEquals(listOf("tool_title"), defs[0].required)
    }

    @Test
    fun `re-ingesting one server replaces its previous tools`() {
        McpToolRegistry.clear()
        McpToolRegistry.ingest("notion", sample)
        McpToolRegistry.ingest("notion", """{"result":{"server":"notion","tools":[{"name":"other_tool"}]}}""")
        val names = McpToolRegistry.definitions().map { it.name }
        assertEquals(listOf("mcp__notion__other_tool"), names)
    }

    @Test
    fun `servers stay separated`() {
        McpToolRegistry.clear()
        McpToolRegistry.ingest("a", """{"result":{"server":"a","tools":[{"name":"x"}]}}""")
        McpToolRegistry.ingest("b", """{"result":{"server":"b","tools":[{"name":"y"}]}}""")
        val names = McpToolRegistry.definitions().map { it.name }.sorted()
        assertEquals(listOf("mcp__a__x", "mcp__b__y"), names)
    }

    @Test
    fun `garbage or empty names are ignored`() {
        McpToolRegistry.clear()
        assertEquals(0, McpToolRegistry.ingest("a", "not json"))
        assertEquals(0, McpToolRegistry.ingest("a", """{"result":{"tools":[{"description":"no name"}]}}"""))
        assertTrue(McpToolRegistry.definitions().isEmpty())
    }

    @Test
    fun `blank payload keeps the previous list`() {
        McpToolRegistry.clear()
        McpToolRegistry.ingest("notion", sample)
        assertEquals(0, McpToolRegistry.ingest("notion", "   "))
        assertEquals(1, McpToolRegistry.definitions().size)
    }
}
