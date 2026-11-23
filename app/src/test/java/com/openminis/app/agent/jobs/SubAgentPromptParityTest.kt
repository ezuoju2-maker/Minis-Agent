package com.openminis.app.agent.jobs

import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.model.SubAgentDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentPromptParityTest {

    private fun cfg(maxTurns: Int = 25, title: String = "Survey the repo") = HelperConfig(
        parentSessionId = "parent",
        parentToolUseId = "tu_1",
        jobId = "job_1",
        maxTurns = maxTurns,
        title = title,
        tierUsed = HelperModelTier.PRIMARY,
    )


    @Test
    fun `the child is told the browser is shared and which tabs are its own`() {
        val s = HelperRunner.browserSharingSection(browserEnabled = true)
        assertTrue(s.startsWith("Browser: the browser is shared with the parent and with other agents."))
        assertTrue("must state the per-agent quota", s.contains("up to ${BrowserTabPool.AGENT_TAB_QUOTA}"))
        assertTrue("list_tabs scoping is the discoverable half of the rule", s.contains("list_tabs shows just yours"))
        assertTrue("guessing ids is the specific failure seen", s.contains("never guess tab ids you did not receive"))
        assertTrue("reuse is the remedy offered", s.contains("reuse your tabs by navigating"))
        assertTrue(s.endsWith("\n"))
    }

    @Test
    fun `no browser paragraph when browser_use is switched off`() {
        assertEquals("", HelperRunner.browserSharingSection(browserEnabled = false))
    }

    @Test
    fun `the identity preamble carries the browser paragraph only when enabled`() {
        assertTrue(HelperRunner.identitySection(cfg(), browserEnabled = true).contains("Browser: the browser is shared"))
        assertFalse(HelperRunner.identitySection(cfg(), browserEnabled = false).contains("Browser: the browser is shared"))
    }

    @Test
    fun `browser rules sit between the deliverable contract and the turn budget`() {
        val s = HelperRunner.identitySection(cfg(), browserEnabled = true)
        val deliverable = s.indexOf("returned verbatim to the parent agent")
        val browser = s.indexOf("Browser: the browser is shared")
        val budget = s.indexOf("You have at most")
        assertTrue(deliverable in 0 until browser)
        assertTrue(browser < budget)
    }


    @Test
    fun `the child is told its final message is the only thing the parent receives`() {
        val s = HelperRunner.identitySection(cfg(), browserEnabled = false)
        assertTrue(s.contains("it is the ONLY thing the parent receives"))
        assertTrue(s.contains("never a summary of what you did, a pointer to a file you wrote"))
        assertTrue(s.contains("[ESCALATE]"))
    }

    @Test
    fun `the turn budget is stated with the configured value`() {
        assertTrue(HelperRunner.identitySection(cfg(maxTurns = 7), browserEnabled = false)
            .contains("You have at most 7 tool rounds"))
    }

    @Test
    fun `the child is warned off scheduling and further delegation`() {
        assertTrue(HelperRunner.identitySection(cfg(), browserEnabled = false)
            .contains("Do not create scheduled tasks or delegate further."))
    }


    @Test
    fun `an empty roster contributes nothing to the prompt`() {
        assertEquals("", HelperRunner.subAgentRosterSection(emptyList()) { null })
    }

    @Test
    fun `the roster names each agent, its description and where it runs`() {
        val roster = listOf(
            com.openminis.app.data.model.SubAgentDefinition(
                id = "builtin.general", name = "General Sub Agent",
                description = "Open-ended work.", instructions = "",
                modelGroupId = null, isBuiltIn = true, sortOrder = 0,
            ),
            com.openminis.app.data.model.SubAgentDefinition(
                id = "custom-1", name = "Researcher",
                description = "Reads the web.", instructions = "",
                modelGroupId = "grp-9", isBuiltIn = false, sortOrder = 1,
            ),
        )
        val s = HelperRunner.subAgentRosterSection(roster) { if (it == "grp-9") "Fast Group" else null }

        assertTrue(s.startsWith("Available sub agents (pass the name as ${SubAgentDefinition.TOOL_NAME}.agent):"))
        assertTrue(s.contains("- General Sub Agent — Open-ended work. Model: Auto — you choose with model_choice."))
        assertTrue(s.contains("- Researcher — Reads the web. Model: fixed — Fast Group."))
        assertTrue(s.trimEnd().endsWith("Prefer a specific sub agent when its description matches; otherwise use the general one."))
    }

    @Test
    fun `an over-long description is clamped in the roster line`() {
        val max = com.openminis.app.data.model.SubAgentLimits.DESCRIPTION_MAX_LENGTH
        val roster = listOf(
            com.openminis.app.data.model.SubAgentDefinition(
                id = "x", name = "Verbose", description = "d".repeat(max * 3),
                instructions = "", modelGroupId = null, isBuiltIn = false, sortOrder = 0,
            ),
        )
        val s = HelperRunner.subAgentRosterSection(roster) { null }
        assertFalse("must not emit more than the bound", s.contains("d".repeat(max + 1)))
        assertTrue(s.contains("d".repeat(max)))
    }


    @Test
    fun `the bullet is absent when delegation is not offered`() {
        assertEquals("", HelperRunner.systemPromptBullet(enabled = false))
    }

    @Test
    fun `the bullet states what the tool schema does not say`() {
        val s = HelperRunner.systemPromptBullet(enabled = true)
        assertTrue(s.startsWith("- ${SubAgentDefinition.TOOL_NAME}:"))
        assertTrue(s.contains("written by the system, not typed by the user"))
        assertTrue(s.contains("the user sees it in the tool bar and can watch or stop it"))
        assertTrue(s.endsWith("\n"))
    }


    @Test
    fun `a resumed run says so in its final payload`() {
        val json = org.json.JSONObject(
            HelperRunner.resultJson(
                status = "completed", result = "done", modelLabel = "m",
                tierUsed = HelperModelTier.PRIMARY, tierRequested = "same_as_me",
                turns = 3, elapsedMs = 1_000, childSessionId = "c", jobId = "j",
                wasResumed = true,
            ),
        )
        assertTrue(json.getBoolean("resumed"))
        assertEquals(HelperRunner.RESUMED_NOTE, json.getString("resumed_note"))
        assertTrue(HelperRunner.RESUMED_NOTE.contains("tool context was lost"))
        assertTrue(HelperRunner.RESUMED_NOTE.contains("cover only the resumed part"))
    }

    @Test
    fun `an ordinary run carries no resumed keys at all`() {
        val json = org.json.JSONObject(
            HelperRunner.resultJson(
                status = "completed", result = "done", modelLabel = "m",
                tierUsed = HelperModelTier.PRIMARY, tierRequested = "same_as_me",
                turns = 3, elapsedMs = 1_000, childSessionId = "c", jobId = "j",
            ),
        )
        assertFalse(json.has("resumed"))
        assertFalse(json.has("resumed_note"))
    }


    @Test
    fun `the steer nudge only tells the child to read and continue`() {
        assertEquals(
            "The delegating agent has sent you a course correction. Read it and continue.",
            HelperRunner.STEER_NUDGE_PROMPT,
        )
    }
}
