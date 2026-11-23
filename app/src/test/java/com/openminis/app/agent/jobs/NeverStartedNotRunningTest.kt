package com.openminis.app.agent.jobs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeverStartedNotRunningTest {

    private fun isRunning(phase: String): Boolean =
        phase != "Finished" && phase != "NeverStarted"

    @Test
    fun `never started is not running`() {
        assertFalse(
            "a delegation lost with the queue has no job to stop",
            isRunning("NeverStarted"),
        )
    }

    @Test
    fun `finished is not running`() {
        assertFalse(isRunning("Finished"))
    }

    @Test
    fun `genuinely live phases stay running`() {
        assertTrue(isRunning("Running"))
        assertTrue(isRunning("Queued"))
        assertTrue(isRunning("Starting"))
    }

    @Test
    fun `control calls are not startable`() {
        assertTrue(
            HelperRunner.isControlOnly("""{"action":"status"}""", null),
        )
        assertTrue(
            HelperRunner.isControlOnly("""{"action":"cancel"}""", null),
        )
        assertFalse(
            "a delegation carries a task and IS startable",
            HelperRunner.isControlOnly("""{"task":"research X","tool_title":"X"}""", null),
        )
    }
}
