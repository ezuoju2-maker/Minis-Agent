package com.openminis.app.service

import android.app.Application
import com.openminis.app.ProductionSources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FgsIdleRevivalTest {

    private val presentId = "fgs-idle-revival-test"

    @After
    fun tearDown() {
        SessionActivityTracker.setAbsent(presentId)
    }

    @Test
    fun `a sticky revival with nothing to run stops instead of showing an idle notification`() {
        assertTrue(AgentForegroundService.startHasNothingToRun(action = null, shouldRun = false))
    }

    @Test
    fun `a start that still has a chat to keep runs normally`() {
        assertFalse(AgentForegroundService.startHasNothingToRun(action = null, shouldRun = true))
    }

    @Test
    fun `the notification's Stop keeps its own branch`() {
        assertFalse(
            AgentForegroundService.startHasNothingToRun(
                action = "com.openminis.app.STOP_AGENT_SERVICE",
                shouldRun = false,
            ),
        )
    }

    @Test
    fun `a chat the user is present in is counted as one session, not zero and idle`() {
        SessionActivityTracker.setPresent(presentId)

        assertEquals(1, SessionActivityTracker.sessionCountForNotification())
        assertTrue(SessionActivityTracker.statusForNotification() != "Idle")
    }

    @Test
    fun `every notification rebuild uses the tracker's count and status`() {
        val svc = ProductionSources.read("service/AgentForegroundService.kt")
        assertFalse(
            "a rebuild from activeSessions.size shows a present chat as 0 sessions",
            svc.contains("buildNotification(\n                SessionActivityTracker.activeSessions.value.size,") ||
                svc.contains("buildNotification(\n            SessionActivityTracker.activeSessions.value.size,"),
        )
        assertTrue(
            "the backstop decides through startHasNothingToRun, null intent included",
            svc.contains("if (startHasNothingToRun(intent?.action, SessionActivityTracker.shouldRunServiceNow())) {"),
        )
    }
}
