package com.openminis.app.agent.fleet

import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentLimits
import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentForgeTest {

    private fun builtIn() = SubAgentDefinition.makeBuiltIn()

    private fun custom(name: String) = SubAgentDefinition(
        name = name,
        description = "does $name things",
        instructions = "body",
    )

    private fun baseRequest() = AgentForge.Request(
        name = "Log Triage",
        whenToUse = "Use when a build log needs a failing step identified",
        goal = "Name the failing step and the smallest fix",
        steps = listOf("Read the log tail", "Map the error to a file"),
        constraints = listOf("Do not edit files"),
        outputFormat = "one line: step / cause / fix",
    )

    @Test
    fun acceptsValidRequest() {
        assertNull(AgentForge.validate(baseRequest(), listOf(builtIn())))
    }

    @Test
    fun rejectsBlankName() {
        assertTrue(AgentForge.validate(baseRequest().copy(name = "  "), listOf(builtIn()))!!.contains("name"))
    }

    @Test
    fun rejectsLongName() {
        val name = "x".repeat(SubAgentLimits.NAME_MAX_LENGTH + 1)
        assertNotNull(AgentForge.validate(baseRequest().copy(name = name), listOf(builtIn())))
    }

    @Test
    fun rejectsDuplicateNameIgnoringCaseAndSpaces() {
        val roster = listOf(builtIn(), custom("log triage"))
        assertNotNull(AgentForge.validate(baseRequest().copy(name = "  Log   Triage "), roster))
    }

    @Test
    fun rejectsWhenRosterIsFull() {
        val roster = buildList {
            add(builtIn())
            repeat(SubAgentLimits.MAX_COUNT - 1) { add(custom("agent $it")) }
        }
        assertNotNull(AgentForge.validate(baseRequest(), roster))
    }

    @Test
    fun rejectsMissingWhenToUse() {
        val req = baseRequest().copy(whenToUse = "", goal = "")
        assertNotNull(AgentForge.validate(req, listOf(builtIn())))
    }

    @Test
    fun fallsBackToGoalForDescription() {
        val req = baseRequest().copy(whenToUse = "")
        assertEquals(req.goal, AgentForge.descriptionOf(req))
    }

    @Test
    fun descriptionIsClamped() {
        val req = baseRequest().copy(whenToUse = "y".repeat(SubAgentLimits.DESCRIPTION_MAX_LENGTH + 50))
        assertEquals(SubAgentLimits.DESCRIPTION_MAX_LENGTH, AgentForge.descriptionOf(req).length)
    }

    @Test
    fun bodyCarriesGoalStepsConstraintsAndFormat() {
        val body = AgentForge.bodyOf(baseRequest())
        assertTrue(body.contains("Goal: Name the failing step"))
        assertTrue(body.contains("1. Read the log tail"))
        assertTrue(body.contains("2. Map the error to a file"))
        assertTrue(body.contains("- Do not edit files"))
        assertTrue(body.contains("Return exactly: one line: step / cause / fix"))
        assertTrue(body.length <= SubAgentLimits.INSTRUCTIONS_MAX_LENGTH)
    }

    @Test
    fun explicitInstructionsWin() {
        val body = AgentForge.bodyOf(baseRequest().copy(instructions = "  custom body  "))
        assertEquals("custom body", body)
    }

    @Test
    fun bodyIsTruncatedAtLimit() {
        val req = baseRequest().copy(instructions = "z".repeat(SubAgentLimits.INSTRUCTIONS_MAX_LENGTH + 10))
        assertEquals(SubAgentLimits.INSTRUCTIONS_MAX_LENGTH, AgentForge.bodyOf(req).length)
    }

    @Test
    fun buildProducesCustomEntry() {
        val def = AgentForge.build(baseRequest())
        assertEquals("Log Triage", def.name)
        assertEquals(baseRequest().whenToUse, def.description)
        assertTrue(def.instructions.isNotEmpty())
        assertEquals(false, def.isBuiltIn)
    }

    @Test
    fun thinkingParsing() {
        assertEquals(ThinkingLevel.LOW, AgentForge.thinkingOf("low"))
        assertEquals(ThinkingLevel.HIGH, AgentForge.thinkingOf("High"))
        assertNull(AgentForge.thinkingOf("nonsense"))
        assertNull(AgentForge.thinkingOf(null))
    }

    @Test
    fun matchByIdOrName() {
        val a = custom("Deck Builder")
        val roster = listOf(builtIn(), a)
        assertEquals(a.id, AgentForge.match(roster, a.id)?.id)
        assertEquals(a.id, AgentForge.match(roster, "deck builder")?.id)
        assertNull(AgentForge.match(roster, "missing"))
    }

    @Test
    fun rosterRenderCountsCustomAgents() {
        val text = AgentForge.renderRoster(listOf(builtIn(), custom("A"), custom("B")))
        assertTrue(text.contains("2 custom"))
        assertTrue(text.contains("(built in)"))
        assertTrue(text.contains("subagent_task"))
    }
}
