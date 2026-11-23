package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrphanedRunningToolGateTest {


    @Test
    fun `an orphaned running block stops spinning`() {
        assertEquals(
            ToolBlockStatus.TIMEOUT,
            OrphanedRunningToolGate.resolve(ToolBlockStatus.RUNNING, isLiveRun = false, jobIsAlive = false),
        )
    }

    @Test
    fun `every spinner state is gated, not just RUNNING`() {
        for (s in listOf(ToolBlockStatus.RUNNING, ToolBlockStatus.STREAMING, ToolBlockStatus.PENDING)) {
            assertTrue("$s must be treated as a spinner", OrphanedRunningToolGate.isSpinner(s))
            assertEquals(
                "$s must be downgraded when orphaned",
                ToolBlockStatus.TIMEOUT,
                OrphanedRunningToolGate.resolve(s, isLiveRun = false, jobIsAlive = false),
            )
        }
    }

    @Test
    fun `a genuinely running job keeps spinning`() {
        assertEquals(
            ToolBlockStatus.RUNNING,
            OrphanedRunningToolGate.resolve(ToolBlockStatus.RUNNING, isLiveRun = false, jobIsAlive = true),
        )
    }

    @Test
    fun `a live turn is trusted even before the registry knows the job`() {
        assertEquals(
            ToolBlockStatus.RUNNING,
            OrphanedRunningToolGate.resolve(ToolBlockStatus.RUNNING, isLiveRun = true, jobIsAlive = false),
        )
    }

    @Test
    fun `terminal states are historical facts and pass through untouched`() {
        for (s in listOf(
            ToolBlockStatus.SUCCESS, ToolBlockStatus.FAILED,
            ToolBlockStatus.CANCELLED, ToolBlockStatus.TIMEOUT,
        )) {
            assertEquals(s, OrphanedRunningToolGate.resolve(s, isLiveRun = false, jobIsAlive = false))
            assertFalse(OrphanedRunningToolGate.isSpinner(s))
        }
    }

    @Test
    fun `a null status stays null`() {
        assertEquals(null, OrphanedRunningToolGate.resolve(null, isLiveRun = false, jobIsAlive = false))
        assertFalse(OrphanedRunningToolGate.isSpinner(null))
    }


    @Test
    fun `a stale spinner override must not re-stamp the rebuilt block`() {
        assertFalse(
            OrphanedRunningToolGate.overrideMayApply(
                ToolBlockStatus.RUNNING, isLiveRun = false, jobIsAlive = false,
            ),
        )
    }

    @Test
    fun `a live override still applies`() {
        assertTrue(OrphanedRunningToolGate.overrideMayApply(ToolBlockStatus.RUNNING, isLiveRun = false, jobIsAlive = true))
        assertTrue(OrphanedRunningToolGate.overrideMayApply(ToolBlockStatus.RUNNING, isLiveRun = true, jobIsAlive = false))
    }

    @Test
    fun `a terminal override always applies, dead job or not`() {
        assertTrue(OrphanedRunningToolGate.overrideMayApply(ToolBlockStatus.SUCCESS, isLiveRun = false, jobIsAlive = false))
        assertTrue(OrphanedRunningToolGate.overrideMayApply(null, isLiveRun = false, jobIsAlive = false))
    }


    private val vmSrc by lazy {
        val f = File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
        assertTrue("missing ${f.absolutePath}", f.exists())
        f.readText()
    }

    @Test
    fun `mergeDelegateOverrides consults the gate`() {
        val body = vmSrc.substringAfter("private fun mergeDelegateOverrides(")
            .substringBefore("private fun delegateJobIsAlive(")
        assertTrue(
            "the override merge must gate spinner statuses, or a dead delegate " +
                "re-stamps RUNNING on every reload",
            body.contains("OrphanedRunningToolGate.overrideMayApply("),
        )
        assertTrue("liveness must come from the registry", body.contains("delegateJobIsAlive("))
    }

    @Test
    fun `a historical progress callback does not spin forever`() {
        val src = File("src/main/java/com/openminis/app/ui/chat/HelperUi.kt")
        assertTrue("missing ${src.absolutePath}", src.exists())
        val body = src.readText().substringAfter("internal fun AgentCallback.syntheticBlock(")
            .substringBefore("\n}")
        assertTrue(
            "the unfinished branch must go through the gate",
            body.contains("OrphanedRunningToolGate.resolve("),
        )
        assertTrue(
            "liveness must come from the registry, keyed by this callback's job",
            body.contains("AgentJobRegistry.job(jobId)?.isActive"),
        )
    }

    @Test
    fun `liveness is read from the job registry by job_id`() {
        val body = vmSrc.substringAfter("private fun delegateJobIsAlive(").substringBefore("\n    }")
        assertTrue("must read job_id from the block payload", body.contains("""optString("job_id", "")"""))
        assertTrue("must ask the registry", body.contains("AgentJobRegistry.job(jobId)?.isActive"))
        assertTrue(
            "an unparseable payload must NOT be treated as alive — a spinner " +
                "that never stops is the worse failure",
            body.contains("return false"),
        )
    }
}
