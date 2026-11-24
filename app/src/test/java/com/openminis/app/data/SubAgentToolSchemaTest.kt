package com.openminis.app.data

import com.openminis.app.agent.jobs.HelperRunner
import com.openminis.app.agent.jobs.SubAgentModelChoice
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.AgentToolSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentToolSchemaTest {

    private fun tools(
        rosterNames: List<String> = listOf(SubAgentDefinition.BUILT_IN_NAME),
        isHelper: Boolean = false,
        delegateEnabled: Boolean = true,
    ) = AgentTools.makeAgentTools(
        isHelper = isHelper,
        delegateEnabled = delegateEnabled,
        rosterNames = rosterNames,
    )

    private fun subAgentTool(vararg names: String) =
        tools(rosterNames = names.toList()).find { it.name == SubAgentDefinition.TOOL_NAME }


    @Test
    fun `the schema exposes subagent_task and no separate status tool`() {
        val all = tools().map { it.name }
        assertTrue("subagent_task must be offered", all.contains("subagent_task"))
        assertFalse("delegate_task was renamed", all.contains("delegate_task"))
        assertFalse("agent_status is folded in as an action", all.contains("agent_status"))
    }

    @Test
    fun `a helper never sees the tool`() {
        val all = tools(isHelper = true).map { it.name }
        assertFalse(all.contains(SubAgentDefinition.TOOL_NAME))
    }

    @Test
    fun `the agents switch removes the tool from the schema entirely`() {
        val all = tools(delegateEnabled = false).map { it.name }
        assertFalse(all.contains(SubAgentDefinition.TOOL_NAME))
    }


    @Test
    fun `only tool_title is required`() {
        val tool = subAgentTool()!!
        assertEquals(listOf("tool_title"), tool.required)
    }

    @Test
    fun `the five actions are advertised`() {
        val action = subAgentTool()!!.parameters["action"]!!
        assertEquals(
            listOf("delegate", "status", "steer", "cancel", "resume"),
            action.enumValues,
        )
    }

    @Test
    fun `the three model choices are advertised`() {
        val choice = subAgentTool()!!.parameters["model_choice"]!!
        assertEquals(
            listOf("same_as_me", "default_model", "sub_model"),
            choice.enumValues,
        )
    }

    @Test
    fun `the agent enum is the live roster so a name cannot be invented`() {
        val tool = subAgentTool("General Sub Agent", "Researcher", "Reviewer")!!
        assertEquals(
            listOf("General Sub Agent", "Researcher", "Reviewer"),
            tool.parameters["agent"]!!.enumValues,
        )
    }

    @Test
    fun `property ordering matches the iOS contract`() {
        assertEquals(
            listOf(
                "tool_title", "action", "task", "agent", "model_choice", "context",
                "max_turns", "max_minutes", "wait", "progress_report", "job_id", "message",
                "child_session_id",
            ),
            subAgentTool()!!.propertyOrdering,
        )
    }

    @Test
    fun `the control parameters exist for status steer cancel and resume`() {
        val p = subAgentTool()!!.parameters
        assertNotNull("status/steer/cancel need job_id", p["job_id"])
        assertNotNull("steer needs message", p["message"])
        assertNotNull("resume needs child_session_id", p["child_session_id"])
    }


    @Test
    fun `the pre-rename tool names are still recognised`() {
        assertTrue(HelperRunner.isSubAgentToolName("subagent_task"))
        assertTrue(HelperRunner.isSubAgentToolName("delegate_task"))
        assertTrue(HelperRunner.isSubAgentToolName("agent_status"))
        assertFalse(HelperRunner.isSubAgentToolName("browser_use"))
        assertFalse(HelperRunner.isSubAgentToolName(null))
    }

    @Test
    fun `new blocks are written under the new name only`() {
        assertEquals("subagent_task", HelperRunner.TOOL_NAME)
    }

    @Test
    fun `the agents switch still governs a legacy tool name`() {
        for (n in listOf("subagent_task", "delegate_task", "agent_status")) {
            assertEquals(
                "switch must govern $n",
                AgentToolSwitch.AGENTS,
                AgentToolSwitch.governing(n),
            )
        }
    }


    @Test
    fun `agent and model_choice are parsed off the call`() {
        val args = HelperRunner.parseArgs(
            """{"tool_title":"t","task":"do it","agent":"Researcher","model_choice":"sub_model"}""",
        )
        assertEquals("Researcher", args.agentName)
        assertEquals(SubAgentModelChoice.SUB_MODEL, args.modelChoice)
    }

    @Test
    fun `an omitted agent means the built-in`() {
        val args = HelperRunner.parseArgs("""{"tool_title":"t","task":"do it"}""")
        assertNull(args.agentName)
    }

    @Test
    fun `an unknown model choice falls back to the parent's model`() {
        for (raw in listOf("", "nonsense", "SAME_AS_ME")) {
            assertEquals(
                "'$raw' must parse to same_as_me",
                SubAgentModelChoice.SAME_AS_PARENT,
                SubAgentModelChoice.parse(raw),
            )
        }
        assertEquals(SubAgentModelChoice.SAME_AS_PARENT, SubAgentModelChoice.parse(null))
    }

    @Test
    fun `max_minutes is clamped to the documented ceiling`() {
        assertEquals(60, HelperRunner.parseArgs("""{"task":"x","max_minutes":999}""").minutes)
        assertEquals(1, HelperRunner.parseArgs("""{"task":"x","max_minutes":0}""").minutes)
        assertEquals(10, HelperRunner.parseArgs("""{"task":"x"}""").minutes)
    }


    @Test
    fun `a definition's instructions are appended under the shared delimiter`() {
        val out = HelperRunner.subAgentInstructionsSection("always cite sources")
        assertTrue(out.contains("--- Sub agent instructions (set by the user) ---"))
        assertTrue(out.contains("always cite sources"))
    }

    @Test
    fun `an agent with no instructions appends nothing`() {
        assertEquals("", HelperRunner.subAgentInstructionsSection(""))
        assertEquals("", HelperRunner.subAgentInstructionsSection("   \n  "))
    }


    @Test
    fun `the roster section names each agent and where it runs`() {
        val roster = listOf(
            SubAgentDefinition.makeBuiltIn(),
            SubAgentDefinition(id = "a", name = "Researcher", description = "Reads a lot.", sortOrder = 1),
            SubAgentDefinition(
                id = "b", name = "Reviewer", description = "Checks work.",
                modelGroupId = "grp", sortOrder = 2,
            ),
        )
        val out = HelperRunner.subAgentRosterSection(roster) { if (it == "grp") "Strong Models" else null }
        assertTrue(out.contains("subagent_task.agent"))
        assertTrue("an Auto agent invites model_choice", out.contains("Auto — you choose with model_choice"))
        assertTrue("a pinned agent names its group", out.contains("fixed — Strong Models"))
        assertTrue(out.contains("Researcher"))
        assertTrue(out.contains("Reviewer"))
    }

    @Test
    fun `an empty roster produces no section`() {
        assertEquals("", HelperRunner.subAgentRosterSection(emptyList()) { null })
    }

    @Test
    fun `a pinned group that no longer exists reads as Auto rather than dangling`() {
        val roster = listOf(
            SubAgentDefinition(id = "b", name = "Reviewer", description = "d", modelGroupId = "gone"),
        )
        val out = HelperRunner.subAgentRosterSection(roster) { null }
        assertTrue(out.contains("Auto — you choose with model_choice"))
        assertFalse(out.contains("fixed —"))
    }
}

class SubAgentResultPayloadTest {

    private fun payload(
        agentName: String? = null,
        origin: com.openminis.app.agent.jobs.HelperModelOrigin? = null,
        unavailable: Boolean = false,
    ) = org.json.JSONObject(
        HelperRunner.resultJson(
            status = "completed",
            result = "done",
            modelLabel = "Claude Sonnet 5",
            tierUsed = com.openminis.app.agent.jobs.HelperModelTier.PRIMARY,
            tierRequested = "primary",
            turns = 3,
            elapsedMs = 12_000,
            childSessionId = "child-1",
            jobId = "job-1",
            agentName = agentName,
            modelOrigin = origin,
            modelGroupUnavailable = unavailable,
        ),
    )

    @Test
    fun `a named sub agent is reported and the built-in is not`() {
        assertEquals("Researcher", payload(agentName = "Researcher").optString("agent"))
        assertFalse(payload(agentName = null).has("agent"))
    }

    @Test
    fun `the model origin is reported on the wire`() {
        for (o in com.openminis.app.agent.jobs.HelperModelOrigin.entries) {
            assertEquals(o.wire, payload(origin = o).optString("model_origin"))
        }
    }

    @Test
    fun `an unavailable pinned group is called out, and silence means it applied`() {
        assertTrue(payload(unavailable = true).optBoolean("model_group_unavailable"))
        assertFalse(
            "absent rather than false, so a normal payload is unchanged",
            payload(unavailable = false).has("model_group_unavailable"),
        )
    }

    @Test
    fun `the pre-existing result fields are untouched`() {
        val p = payload()
        assertTrue(p.optBoolean("ok"))
        assertEquals("completed", p.optString("status"))
        assertEquals("done", p.optString("result"))
        assertEquals("child-1", p.optString("child_session_id"))
        assertEquals(12L, p.optLong("elapsed_s"))
    }
}
