package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolSwitchTest {

    @Test
    fun `each optional tool is governed by the expected switch`() {
        assertEquals(AgentToolSwitch.BROWSER, AgentToolSwitch.governing("browser_use"))
        assertEquals(AgentToolSwitch.AGENTS, AgentToolSwitch.governing("delegate_task"))
        assertEquals(AgentToolSwitch.AGENTS, AgentToolSwitch.governing("agent_status"))
    }

    @Test
    fun `core tools are ungoverned and therefore always available`() {
        for (core in listOf("shell_execute", "file_read", "file_write", "file_edit", "memory_get")) {
            assertNull("$core must not be gated by a tool switch", AgentToolSwitch.governing(core))
        }
    }

    @Test
    fun `browser defaults on and keys match the iOS contract`() {
        assertEquals("agent.tools.browser.enabled", AgentToolSwitch.BROWSER.key)
        assertEquals("agent.tools.agents.enabled", AgentToolSwitch.AGENTS.key)
        assertTrue("browser_use is mature and ships on", AgentToolSwitch.BROWSER.defaultValue)
    }

    @Test
    fun `disabling a switch removes its tools from the schema`() {
        fun names(browser: Boolean, delegate: Boolean) =
            AgentTools.makeAgentTools(browserEnabled = browser, delegateEnabled = delegate)
                .map { it.name }.toSet()

        val all = names(browser = true, delegate = true)
        assertTrue(all.containsAll(listOf("browser_use", "subagent_task")))

        val noBrowser = names(browser = false, delegate = true)
        assertFalse("browser_use must be absent, not merely refused", "browser_use" in noBrowser)
        assertTrue("the agents tool is independent", "subagent_task" in noBrowser)

        val noAgents = names(browser = true, delegate = false)
        assertFalse("subagent_task" in noAgents)
        assertTrue("browser_use is independent", "browser_use" in noAgents)

        val neither = names(browser = false, delegate = false)
        assertTrue("shell_execute" in neither && "file_read" in neither)
        for (gated in listOf("browser_use", "subagent_task")) {
            assertFalse(gated in neither)
        }
    }

    @Test
    fun `a helper never gets delegation even with the switch on`() {
        val helperTools = AgentTools.makeAgentTools(isHelper = true, delegateEnabled = true)
            .map { it.name }
        assertFalse("delegate_task" in helperTools)
        assertFalse("agent_status" in helperTools)
    }
}
