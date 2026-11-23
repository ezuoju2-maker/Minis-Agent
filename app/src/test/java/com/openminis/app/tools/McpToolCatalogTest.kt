package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolCatalogTest {

    private val envelope = """
        {"ok":true,"result":{"server":"notion","count":2,"tools":[
          {"name":"search","description":"Search Notion pages","inputSchema":{"type":"object","properties":{"query":{"type":"string"}}}},
          {"name":"create_page","description":"Create a page"}
        ]}}
    """.trimIndent()

    @Test
    fun `parses CLI envelope with server and tools`() {
        val tools = McpToolCatalog.parse(envelope)
        assertEquals(2, tools.size)
        assertEquals("notion", tools[0].server)
        assertEquals("search", tools[0].name)
        assertTrue(tools[0].description.contains("Search"))
        assertEquals(listOf("query"), tools[0].inputNames)
    }

    @Test
    fun `parses bare tools array with server hint`() {
        val tools = McpToolCatalog.parse("""[{"name":"ping","description":"Ping"}]""", "github")
        assertEquals(1, tools.size)
        assertEquals("github", tools[0].server)
        assertEquals("ping", tools[0].name)
    }

    @Test
    fun `malformed input yields empty list`() {
        assertTrue(McpToolCatalog.parse("not json").isEmpty())
        assertTrue(McpToolCatalog.parse("").isEmpty())
    }

    @Test
    fun `collisions detected across servers`() {
        val tools = McpToolCatalog.parse(envelope) + McpToolCatalog.parse(
            """{"ok":true,"result":{"server":"github","tools":[{"name":"search","description":"gh search"}]}}""",
        )
        val c = McpToolCatalog.collisions(tools)
        assertEquals(listOf("github", "notion"), c["search"])
    }

    @Test
    fun `manifest qualifies only colliding names and stays compact`() {
        val tools = McpToolCatalog.parse(envelope) + McpToolCatalog.parse(
            """{"result":{"server":"github","tools":[{"name":"search","description":"gh search"}]}}""",
        )
        val m = McpToolCatalog.manifest(tools)
        assertTrue(m.contains("notion.search"))
        assertTrue(m.contains("github.search"))
        assertTrue(m.contains("create_page:"))
        assertTrue(!m.contains("notion.create_page"))
    }

    @Test
    fun `manifest respects char budget`() {
        val m = McpToolCatalog.manifest(McpToolCatalog.parse(envelope), maxChars = 30)
        assertTrue(m.length <= 30)
    }

    @Test
    fun `resolve qualified and bare references`() {
        val tools = listOf(
            McpToolCatalog.Tool("notion", "search", ""),
            McpToolCatalog.Tool("github", "search", ""),
            McpToolCatalog.Tool("notion", "create_page", ""),
        )
        val found = McpToolCatalog.resolve("notion.create_page", tools)
        assertTrue(found is McpToolCatalog.ResolveResult.Found)
        assertEquals("notion", (found as McpToolCatalog.ResolveResult.Found).tool.server)

        val ambiguous = McpToolCatalog.resolve("search", tools)
        assertTrue(ambiguous is McpToolCatalog.ResolveResult.Ambiguous)

        val unknown = McpToolCatalog.resolve("nope", tools)
        assertTrue(unknown is McpToolCatalog.ResolveResult.Error)
    }

    @Test
    fun `resolve accepts slash separator`() {
        val tools = listOf(McpToolCatalog.Tool("github", "list_issues", ""))
        val found = McpToolCatalog.resolve("github/list_issues", tools)
        assertTrue(found is McpToolCatalog.ResolveResult.Found)
    }

    @Test
    fun `call command is exact`() {
        val t = McpToolCatalog.Tool("notion", "search", "")
        assertEquals("minis-mcp-cli call notion search '{}'", McpToolCatalog.callCommand(t))
        assertEquals("minis-mcp-cli call notion search '{\"q\":1}'", McpToolCatalog.callCommand(t, "{\"q\":1}"))
    }
}
