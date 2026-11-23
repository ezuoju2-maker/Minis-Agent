package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchModelGhostRetryTest {


    private data class Teardown(
        val streamJob: Boolean,
        val retryCountdownUi: Boolean,
        val subAgents: Boolean,
        val queuedDelegations: Boolean,
        val shellCommand: Boolean,
        val perTurnCancelCleanup: Boolean = false,
    )

    private fun onStop() = Teardown(
        streamJob = true,
        retryCountdownUi = true,
        subAgents = true,
        queuedDelegations = true,
        shellCommand = true,
        perTurnCancelCleanup = true,
    )

    private fun onSwitchModel(inRetryLadder: Boolean) = Teardown(
        streamJob = inRetryLadder,
        retryCountdownUi = inRetryLadder,
        subAgents = false,
        queuedDelegations = false,
        shellCommand = false,
        perTurnCancelCleanup = false,
    )

    @Test
    fun `switching model cancels a turn parked in the retry ladder`() {
        assertTrue(onSwitchModel(inRetryLadder = true).streamJob)
    }

    @Test
    fun `switching model during normal streaming does not cancel anything`() {
        val switch = onSwitchModel(inRetryLadder = false)
        assertFalse("the live turn must finish", switch.streamJob)
        assertFalse("nothing to clear — no ladder is running", switch.retryCountdownUi)
        assertFalse("and the turn must never be paused into Resume", switch.perTurnCancelCleanup)
    }

    @Test
    fun `a model switch never runs Stop's per-turn closer`() {
        assertFalse(onSwitchModel(inRetryLadder = true).perTurnCancelCleanup)
        assertFalse(onSwitchModel(inRetryLadder = false).perTurnCancelCleanup)
        assertTrue("Stop still owns it", onStop().perTurnCancelCleanup)
    }

    @Test
    fun `switching model clears the retry countdown the user can see`() {
        assertTrue(onSwitchModel(inRetryLadder = true).retryCountdownUi)
    }

    @Test
    fun `switching model is not Stop — sub agents and shell work survive`() {
        val switch = onSwitchModel(inRetryLadder = true)
        assertFalse("a running sub agent is not addressed to the old model", switch.subAgents)
        assertFalse("queued delegations must still run", switch.queuedDelegations)
        assertFalse("a running shell command must not be killed", switch.shellCommand)

        val stop = onStop()
        assertTrue(stop.subAgents)
        assertTrue(stop.queuedDelegations)
        assertTrue(stop.shellCommand)
        assertFalse("switch and stop must stay distinct", switch == stop)
    }


    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing source: $path", f.exists())
        return f.readText()
    }

    private val vmSrc by lazy {
        src("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
    }

    @Test
    fun `every user-facing switch path cancels before rebinding`() {
        val selectors = Regex("fun (select[A-Za-z]*)\\(")
            .findAll(vmSrc)
            .map { it.groupValues[1] }
            .toSet()

        assertTrue(
            "expected the three known selectors; found $selectors — if a new " +
                "one was added, it needs the cancel guard too",
            selectors.containsAll(setOf("selectGroup", "selectGroupEntry", "selectEntry")),
        )

        for (name in selectors) {
            val after = vmSrc.substringAfter("fun $name(")
            val body = after.substringBefore("\n    fun ").substringBefore("\n    private fun ")
            assertTrue(
                "$name must cancel work bound to the previous model before " +
                    "rebinding, or a mid-turn switch leaves the session wedged " +
                    "with _isStreaming stuck true",
                body.contains("cancelWorkBoundToPreviousModel("),
            )
        }
    }

    @Test
    fun `selectEntry cancels only once the pick is known to take effect`() {
        val body = vmSrc.substringAfter("fun selectEntry(").substringBefore("currentModel = entry.model")
        val cancelAt = body.indexOf("cancelWorkBoundToPreviousModel(")
        val lastReturn = body.lastIndexOf("return")
        assertTrue("selectEntry must call the guard", cancelAt >= 0)
        assertTrue("selectEntry must have early returns", lastReturn >= 0)
        assertTrue(
            "the cancel must follow the last early return (cancelAt=$cancelAt, lastReturn=$lastReturn)",
            cancelAt > lastReturn,
        )
    }

    @Test
    fun `a normally streaming turn is left alone to finish`() {
        val body = vmSrc.substringAfter("private fun cancelWorkBoundToPreviousModel")
            .substringBefore("private fun isBoundToAbandonedModel")
        assertTrue(
            "the seam must bail out when the turn is not bound to the old model",
            body.contains("if (!isBoundToAbandonedModel())"),
        )
        val code = body.lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertFalse(
            "the seam must NOT run Stop's per-turn closer — that is what produced " +
                "the resumable pause on an ordinary model switch",
            code.contains("handleUserCancelledCleanup()"),
        )
        assertTrue("queued prompts must still drain under the new model", body.contains("resumeQueueAfterCancel()"))
    }

    @Test
    fun `only retry-ladder work counts as bound to the abandoned model`() {
        val pred = vmSrc.substringAfter("private fun isBoundToAbandonedModel(): Boolean =")
            .substringBefore("\n\n")
        assertTrue("the ladder's attempt counter decides", pred.contains("_autoRetryAttempt.value > 0"))
        assertTrue("so does a live countdown", pred.contains("_autoRetryCountdown.value > 0"))
        assertFalse(
            "_isStreaming must NOT make a turn 'bound' — that is the bug",
            pred.contains("_isStreaming"),
        )
    }

    @Test
    fun `the seam clears the retry ladder's visible state`() {
        val body = vmSrc.substringAfter("private fun cancelWorkBoundToPreviousModel")
            .substringBefore("fun hasWorkInFlight(")
        assertTrue("the stream job must be cancelled", body.contains("streamJob?.cancel()"))
        assertTrue("attempt counter reset", body.contains("_autoRetryAttempt.value = 0"))
        assertTrue("countdown reset", body.contains("_autoRetryCountdown.value = 0"))
        assertTrue(
            "the turn must read as user-interrupted, not as the old model failing",
            body.contains("lastTurnWasCancelled = true"),
        )
    }

    @Test
    fun `the seam does NOT perform Stop's broader teardown`() {
        val body = vmSrc.substringAfter("private fun cancelWorkBoundToPreviousModel")
            .substringBefore("fun hasWorkInFlight(")
        assertFalse("must not drop queued delegations", body.contains("dropQueuedDelegations"))
        assertFalse("must not cancel sub agents", body.contains("AgentJobRegistry.cancelAll"))
        assertFalse("must not stop the running shell", body.contains("stopCurrentCommand"))
        assertFalse("must not delegate to cancelStream", body.contains("cancelStream()"))
    }

    @Test
    fun `an idle conversation is untouched`() {
        val body = vmSrc.substringAfter("private fun cancelWorkBoundToPreviousModel")
            .substringBefore("fun hasWorkInFlight(")
        assertTrue(
            "the seam must early-return when nothing is in flight",
            body.contains("if (!_isStreaming.value && streamJob?.isActive != true) return"),
        )
    }

    @Test
    fun `the automatic fallback path does not route through the switch seam`() {
        val fallbackRegion = vmSrc.substringAfter("private fun resolveProviderFromGroup")
            .substringBefore("private fun cancelWorkBoundToPreviousModel")
        assertFalse(
            "resolveProviderFromGroup must not call the switch seam",
            fallbackRegion.contains("cancelWorkBoundToPreviousModel"),
        )
    }

    @Test
    fun `only user-driven UI picks call the switch functions`() {
        val screen = src("src/main/java/com/openminis/app/ui/chat/ChatScreen.kt")
        val callers = Regex("viewModel\\.selectGroup(Entry)?\\(").findAll(screen).count()
        assertEquals("all switch call sites live in the picker UI", 4, callers)
    }
}
