package com.openminis.app.data

import com.openminis.app.agent.jobs.AgentCallback
import com.openminis.app.agent.jobs.AgentJobOrigin
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.AgentJobState
import com.openminis.app.agent.jobs.AgentJobTarget
import com.openminis.app.agent.jobs.AgentJobTrigger
import com.openminis.app.agent.jobs.HelperRunner
import com.openminis.app.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentRobustnessTest {


    @Test
    fun `a bare callback tag with no attributes does not crash the parser`() {
        assertNull(AgentCallback.parse("<agent_callback>"))
        assertNull(AgentCallback.parse("<agent_callback>hello</agent_callback>"))
    }

    @Test
    fun `a well-formed callback still parses`() {
        val xml = AgentCallback(
            kind = AgentCallback.Kind.FINISHED, jobId = "j", childSessionId = "c",
            title = "t", status = "done", body = "answer",
        ).xml
        assertNotNull(AgentCallback.parse(xml))
    }


    @Test
    fun `a malformed part does not discard the rest of the child's answer`() {
        val row = MessageEntity(
            id = "m1",
            sessionId = "s1",
            role = "assistant",
            partsJson = """["junk",{"type":"text","value":"the deliverable"}]""",
            createdAt = 0L,
            sortOrder = 0,
        )
        val facts = HelperRunner.childRunFacts(listOf(row))
        assertTrue(
            "expected the deliverable to survive, got '${facts.lastText}'",
            facts.lastText.contains("the deliverable"),
        )
    }

    @Test
    fun `tool names are still collected around a malformed part`() {
        val row = MessageEntity(
            id = "m1", sessionId = "s1", role = "assistant",
            partsJson = """[1,{"type":"toolUse","value":{"name":"shell_execute"}}]""",
            createdAt = 0L, sortOrder = 0,
        )
        assertEquals(listOf("shell_execute"), HelperRunner.childRunFacts(listOf(row)).toolNames)
    }


    @Test
    fun `a job claims its child session from registration, not from first run`() {
        val job = AgentJobRegistry.register(
            title = "t",
            origin = AgentJobOrigin.TOOL,
            trigger = AgentJobTrigger.Immediate,
            target = AgentJobTarget.ChildOfCurrent("p1", "tool-1"),
            prompt = null,
            runSessionId = "child-42",
        )
        assertEquals(job.id, AgentJobRegistry.jobForSession("child-42")?.id)
        AgentJobRegistry.finish(job.id, AgentJobState.DONE, null)
    }

    @Test
    fun `an ordinary delegation still claims its child only when it starts`() {
        val job = AgentJobRegistry.register(
            title = "t",
            origin = AgentJobOrigin.TOOL,
            trigger = AgentJobTrigger.Immediate,
            target = AgentJobTarget.ChildOfCurrent("p1", "tool-2"),
            prompt = "work",
        )
        assertNull(AgentJobRegistry.jobForSession("not-claimed"))
        AgentJobRegistry.finish(job.id, AgentJobState.DONE, null)
    }
}
