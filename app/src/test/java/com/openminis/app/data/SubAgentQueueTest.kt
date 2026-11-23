package com.openminis.app.data

import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.HelperRunner
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SubAgentQueueTest {

    private val parent = "parent-session"

    private fun item(tool: String, p: String = parent) =
        AgentJobRegistry.QueuedDelegation(
            parentSessionId = p,
            argsJson = """{"tool_title":"t","task":"work"}""",
            toolUseId = tool,
        )

    @Before
    fun setUp() {
        AgentJobRegistry.dropQueuedDelegations(parent, "test-setup")
        AgentJobRegistry.dropQueuedDelegations("other", "test-setup")
    }

    @After
    fun tearDown() {
        AgentJobRegistry.dropQueuedDelegations(parent, "test-teardown")
        AgentJobRegistry.dropQueuedDelegations("other", "test-teardown")
    }

    @Test
    fun `a queued delegation is tracked by its tool use id`() {
        assertFalse(AgentJobRegistry.isQueued("tool-1"))
        assertTrue(AgentJobRegistry.enqueueDelegation(item("tool-1")))
        assertTrue(AgentJobRegistry.isQueued("tool-1"))
        assertEquals(1, AgentJobRegistry.queuedCount(parent))
    }

    @Test
    fun `the backlog is bounded and says so rather than silently dropping`() {
        repeat(AgentJobRegistry.MAX_QUEUED_DELEGATIONS_PER_PARENT) { i ->
            assertTrue("slot $i should accept", AgentJobRegistry.enqueueDelegation(item("tool-$i")))
        }
        assertFalse(AgentJobRegistry.enqueueDelegation(item("tool-overflow")))
        assertFalse(AgentJobRegistry.isQueued("tool-overflow"))
    }

    @Test
    fun `the queue is scoped per parent conversation`() {
        AgentJobRegistry.enqueueDelegation(item("mine", parent))
        AgentJobRegistry.enqueueDelegation(item("theirs", "other"))
        assertEquals(1, AgentJobRegistry.queuedCount(parent))
        assertEquals(1, AgentJobRegistry.queuedCount("other"))

        AgentJobRegistry.dropQueuedDelegations(parent, "test")
        assertEquals(0, AgentJobRegistry.queuedCount(parent))
        assertEquals(1, AgentJobRegistry.queuedCount("other"))
    }

    @Test
    fun `dropping a parent's backlog clears every one of its entries`() {
        repeat(3) { AgentJobRegistry.enqueueDelegation(item("t$it")) }
        AgentJobRegistry.dropQueuedDelegations(parent, "user-stopped")
        assertEquals(0, AgentJobRegistry.queuedCount(parent))
        for (i in 0 until 3) assertFalse(AgentJobRegistry.isQueued("t$i"))
    }


    @Test
    fun `the re-entry marker survives a round trip through the args`() {
        val args = JSONObject("""{"tool_title":"t","task":"work"}""")
            .put(HelperRunner.QUEUED_REENTRY_KEY, true)
            .toString()
        assertTrue(JSONObject(args).optBoolean(HelperRunner.QUEUED_REENTRY_KEY, false))
    }

    @Test
    fun `an ordinary call carries no re-entry marker`() {
        val args = JSONObject("""{"tool_title":"t","task":"work"}""")
        assertFalse(args.optBoolean(HelperRunner.QUEUED_REENTRY_KEY, false))
    }

    @Test
    fun `the marker key matches the iOS wire name`() {
        assertEquals("__from_queue", HelperRunner.QUEUED_REENTRY_KEY)
    }


    @Test
    fun `a queued result reports success so the model does not treat it as failure`() {
        val p = JSONObject(HelperRunner.queuedJson(2, "queued behind others"))
        assertTrue(p.optBoolean("ok"))
        assertEquals("queued", p.optString("status"))
        assertEquals(2, p.optInt("queued_behind"))
        assertEquals("queued behind others", p.optString("detail"))
    }

    @Test
    fun `a negative queue depth is never reported`() {
        assertEquals(0, JSONObject(HelperRunner.queuedJson(-3, "d")).optInt("queued_behind"))
    }


    @Test
    fun `the concurrency limit matches the documented contract`() {
        assertEquals(3, AgentJobRegistry.MAX_CONCURRENT_CHILD_JOBS_PER_PARENT)
        assertEquals(3, HelperRunner.MAX_PER_ASSISTANT_TURN)
    }

    @Test
    fun `draining a stalled queue with no free slot is a no-op`() {
        AgentJobRegistry.enqueueDelegation(item("tool-x"))
        AgentJobRegistry.drainIfStalled()
        assertEquals(0, AgentJobRegistry.queuedCount(parent))
    }
}
