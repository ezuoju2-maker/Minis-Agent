package com.openminis.app.agent.jobs

import com.openminis.app.agent.AgentLoopLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SubAgentTurnCountdownTest {

    @Test
    fun `warning states the remaining count and is singular at one`() {
        assertTrue(HelperRunner.turnBudgetWarning(3).contains("3 tool rounds left"))
        assertTrue(HelperRunner.turnBudgetWarning(2).contains("2 tool rounds left"))
        assertTrue(HelperRunner.turnBudgetWarning(1).contains("1 tool round left"))
        assertFalse(HelperRunner.turnBudgetWarning(1).contains("1 tool rounds"))
    }

    @Test
    fun `warning tells the child to finish, not to stop`() {
        val w = HelperRunner.turnBudgetWarning(2)
        assertTrue("must redirect effort to finishing", w.contains("Stop investigating"))
        assertTrue("must name the write-the-artifact action", w.contains("write a file"))
        assertFalse(
            "must NOT claim tools are gone — they are not, and saying so would " +
                "stop the child from doing the very write this exists to rescue",
            w.contains("no longer available"),
        )
    }

    @Test
    fun `lead time leaves room to act before the cliff`() {
        assertTrue(HelperRunner.TURN_WARNING_LEAD >= 2)
        assertTrue(HelperRunner.TURN_WARNING_LEAD < HelperRunner.MAX_TURNS)
    }

    @Test
    fun `loop injects the warning before the wrap-up and only once`() {
        val src = File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt").readText()
        assertTrue("loop must call turnBudgetWarning", src.contains("HelperRunner.turnBudgetWarning"))
        assertTrue(
            "must be guarded by a once-only flag, or every remaining round " +
                "would repeat the nudge and drown the actual work",
            src.contains("helperTurnWarningInjected = true"),
        )
        assertTrue(
            "must be skipped once the wrap-up fired — at that point zero rounds " +
                "remain and the wrap-up is the stronger instruction",
            src.contains("!helperWrapUpInjected && !helperTurnWarningInjected"),
        )
    }

    @Test
    fun `the cap matches the main chat's own ceiling`() {
        assertEquals(500, AgentLoopLimits.MAX_AGENT_TURNS)
        assertEquals(AgentLoopLimits.MAX_AGENT_TURNS, 500)
        org.junit.Assert.assertTrue(HelperRunner.MAX_TURNS < AgentLoopLimits.MAX_AGENT_TURNS)
        val src = File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt").readText()
        assertTrue(
            src.contains("private const val MAX_AGENT_TURNS = com.openminis.app.agent.AgentLoopLimits.MAX_AGENT_TURNS"),
        )
    }
}
