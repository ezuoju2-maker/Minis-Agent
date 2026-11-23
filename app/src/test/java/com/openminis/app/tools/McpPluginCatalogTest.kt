package com.openminis.app.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpPluginCatalogTest {

    @Test
    fun `catalog is non-empty with unique ids`() {
        val all = McpPluginCatalog.all()
        assertTrue(all.isNotEmpty())
        assertEquals(all.size, all.map { it.id }.distinct().size)
    }

    @Test
    fun `every plugin config has exactly one transport`() {
        for (p in McpPluginCatalog.all()) {
            val c = p.config
            val stdio = c.has("command")
            val http = c.has("url")
            assertTrue("${p.id} must have a transport", stdio || http)
            assertFalse("${p.id} must not have both transports", stdio && http)
        }
    }

    @Test
    fun `no-config plugins work without credentials`() {
        val noConfig = McpPluginCatalog.all().filter { !it.needsConfig }
        assertTrue(noConfig.isNotEmpty())
        assertTrue(noConfig.any { it.id == "time" })
        noConfig.forEach { assertTrue(it.requiredEnv.isEmpty()) }
    }

    @Test
    fun `credential plugins use runtime env placeholder not literal secret`() {
        val gh = McpPluginCatalog.byId("github")!!
        assertTrue(gh.needsConfig)
        assertEquals(listOf("GITHUB_TOKEN"), gh.requiredEnv)
        val env = gh.config.getJSONObject("env")
        assertEquals("\$\$GITHUB_TOKEN", env.getString("GITHUB_PERSONAL_ACCESS_TOKEN"))
    }

    @Test
    fun `import json is valid mcpServers payload for one plugin`() {
        val p = McpPluginCatalog.byId("filesystem")!!
        val root = JSONObject(McpPluginCatalog.importJson(p))
        val servers = root.getJSONObject("mcpServers")
        assertEquals(1, servers.length())
        assertNotNull(servers.getJSONObject("filesystem"))
    }

    @Test
    fun `bulk import json contains all plugins`() {
        val all = McpPluginCatalog.all()
        val root = JSONObject(McpPluginCatalog.importJson(all))
        assertEquals(all.size, root.getJSONObject("mcpServers").length())
    }

    @Test
    fun `byId is case insensitive and unknown returns null`() {
        assertNotNull(McpPluginCatalog.byId("TIME"))
        assertEquals(null, McpPluginCatalog.byId("does-not-exist"))
    }

    @Test
    fun `categories are sorted and distinct`() {
        val c = McpPluginCatalog.categories()
        assertEquals(c, c.sorted())
        assertEquals(c.size, c.distinct().size)
    }
}
