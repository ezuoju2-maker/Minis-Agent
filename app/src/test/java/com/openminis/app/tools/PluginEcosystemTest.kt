package com.openminis.app.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [plugin-ecosystem / unified] One catalog across native capability packs,
 * skills and MCP servers; native packs install as real SKILL.md documents.
 */
class PluginEcosystemTest {

    @Test
    fun `catalog contains all three kinds`() {
        val kinds = PluginEcosystem.catalog().map { it.kind }.toSet()
        assertTrue(kinds.contains(PluginEcosystem.Kind.NATIVE))
        assertTrue(kinds.contains(PluginEcosystem.Kind.MCP))
    }

    @Test
    fun `native plugins need no config and have skill bodies`() {
        val natives = PluginEcosystem.nativePlugins()
        assertTrue(natives.isNotEmpty())
        natives.forEach { p ->
            assertFalse(p.needsConfig)
            assertTrue(p.skillBody.length > 50)
            assertEquals(PluginEcosystem.Kind.NATIVE, p.kind)
        }
    }

    @Test
    fun `native plugin converts to valid skill markdown`() {
        val p = PluginEcosystem.nativePlugins().first()
        val md = PluginEcosystem.toSkillMarkdown(p)
        assertNotNull(md)
        assertTrue(md!!.startsWith("---"))
        assertTrue(md.contains("name: ${p.name}"))
        assertTrue(md.contains("description:"))
        assertTrue(md.contains(p.skillBody))
    }

    @Test
    fun `mcp plugins carry valid import config and env classification`() {
        val mcps = PluginEcosystem.catalog().filter { it.kind == PluginEcosystem.Kind.MCP }
        assertTrue(mcps.isNotEmpty())
        mcps.forEach { p ->
            assertTrue(p.id.startsWith("mcp:"))
            val root = JSONObject(p.configJson)
            assertTrue(root.has("mcpServers"))
            root.getJSONObject("mcpServers").let { assertTrue(it.length() >= 1) }
        }
        assertTrue(mcps.any { it.needsConfig })   // github
        assertTrue(mcps.any { !it.needsConfig })  // time
    }

    @Test
    fun `toSkillMarkdown returns null for non-native`() {
        val mcp = PluginEcosystem.catalog().first { it.kind == PluginEcosystem.Kind.MCP }
        assertEquals(null, PluginEcosystem.toSkillMarkdown(mcp))
    }

    @Test
    fun `manifest is bounded and mentions kinds`() {
        val m = PluginEcosystem.manifest(maxChars = 800)
        assertTrue(m.length <= 800)
        assertTrue(m.contains("native"))
        assertTrue(m.contains("MCP"))
    }

    @Test
    fun `byId is case insensitive`() {
        assertNotNull(PluginEcosystem.byId("CODE-REVIEWER"))
        assertEquals(null, PluginEcosystem.byId("nope"))
    }

    @Test
    fun `kind labels are stable`() {
        assertEquals("Built-in", PluginEcosystem.nativePlugins().first().kindLabel)
        assertEquals("MCP", PluginEcosystem.catalog().first { it.kind == PluginEcosystem.Kind.MCP }.kindLabel)
    }
}
