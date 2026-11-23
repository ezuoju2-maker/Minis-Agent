package com.openminis.app.offload

import com.openminis.app.offload.OffloadPermissionManager.AndroidPermissionResult
import com.openminis.app.offload.OffloadPermissionManager.PermissionLevel
import com.openminis.app.offload.OffloadPermissionManager.Response
import com.openminis.app.offload.OffloadPermissionManager.SettingsGateDecision
import com.openminis.app.offload.OffloadPermissionManager.SettingsGateRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OffloadPermissionWaitBoundTest {
    private val timeout = 5 * 60_000L
    private val session = "gh453-test"

    @Before fun setUp() {
        OffloadPermissionManager.init(RuntimeEnvironment.getApplication())
        OffloadPermissionManager.setLevel("contacts", PermissionLevel.ASK_ONCE)
        OffloadPermissionManager.setLevel("photos", PermissionLevel.ASK_ONCE)
        OffloadPermissionManager.clearSessionGrants(session)
    }

    @After fun tearDown() {
        OffloadPermissionManager.resetAll()
        OffloadPermissionManager.clearSessionGrants(session)
    }

    private fun gate(id: String) = SettingsGateRequest(id, "t", "m", "android.settings.X", requiresPackageUri = false)

    @Test fun `two permission requests at once - each gets its own answer`() = runTest {
        val first = async { OffloadPermissionManager.checkPermission("contacts", "Contacts", session) }
        val second = async { OffloadPermissionManager.checkPermission("photos", "Photos", session) }
        runCurrent()
        assertEquals("contacts", OffloadPermissionManager.pendingRequest.value?.toolName)
        OffloadPermissionManager.respondToRequest(Response.ALLOW_ONCE)
        runCurrent()
        assertTrue(first.isCompleted && first.await())
        assertEquals("the queued request shows next", "photos", OffloadPermissionManager.pendingRequest.value?.toolName)
        OffloadPermissionManager.respondToRequest(Response.DENY_SESSION)
        runCurrent()
        assertFalse(second.await())
    }

    @Test fun `a request queued behind an answer for the same tool needs no second dialog`() = runTest {
        val first = async { OffloadPermissionManager.checkPermission("contacts", "Contacts", session) }
        val second = async { OffloadPermissionManager.checkPermission("contacts", "Contacts", session) }
        runCurrent()
        OffloadPermissionManager.respondToRequest(Response.ALLOW_SESSION)
        runCurrent()
        assertTrue(first.await())
        assertTrue(second.isCompleted && second.await())
        assertNull(OffloadPermissionManager.pendingRequest.value)
    }

    @Test fun `an unanswered permission dialog times out as not granted, without a session denial`() = runTest {
        val call = async { OffloadPermissionManager.checkPermission("contacts", "Contacts", session) }
        runCurrent()
        assertNotNull(OffloadPermissionManager.pendingRequest.value)
        advanceTimeBy(timeout - 1); runCurrent()
        assertFalse("still waiting just before the bound", call.isCompleted)
        advanceTimeBy(2); runCurrent()
        assertTrue(call.isCompleted)
        assertFalse(call.await())
        assertNull("the dialog is withdrawn", OffloadPermissionManager.pendingRequest.value)
        val again = async { OffloadPermissionManager.checkPermission("contacts", "Contacts", session) }
        runCurrent()
        assertNotNull(OffloadPermissionManager.pendingRequest.value)
        OffloadPermissionManager.respondToRequest(Response.ALLOW_ONCE)
        runCurrent()
        assertTrue(again.await())
    }

    @Test fun `unanswered settings gates all return, one after another`() = runTest {
        val calls = List(3) { async { OffloadPermissionManager.requestSettingsGate(gate("notification_access")) { false } } }
        runCurrent()
        advanceTimeBy(timeout + 1); runCurrent()
        assertEquals(AndroidPermissionResult.TIMEOUT, calls[0].await())
        advanceTimeBy(timeout + 1); runCurrent()
        assertTrue("every queued call is bounded by its own wait for a turn", calls.all { it.isCompleted })
        assertTrue(calls.all { it.await() == AndroidPermissionResult.TIMEOUT })
        assertNull(OffloadPermissionManager.pendingSettingsGate.value)
    }

    @Test fun `a settings gate queued behind a successful trip sees the grant and shows nothing`() = runTest {
        var granted = false
        val first = async { OffloadPermissionManager.requestSettingsGate(gate("notification_access")) { granted } }
        val second = async { OffloadPermissionManager.requestSettingsGate(gate("notification_access")) { granted } }
        runCurrent()
        assertNotNull(OffloadPermissionManager.pendingSettingsGate.value)
        OffloadPermissionManager.respondToSettingsGate(OffloadPermissionManager.pendingSettingsGate.value!!, SettingsGateDecision.OPEN)
        granted = true
        advanceTimeBy(600); runCurrent()
        assertEquals(AndroidPermissionResult.GRANTED, first.await())
        assertEquals(AndroidPermissionResult.GRANTED, second.await())
        assertNull(OffloadPermissionManager.pendingSettingsGate.value)
    }

    @Test fun `a dialog that outlived its request answers nothing - not even the next gate`() = runTest {
        val first = async { OffloadPermissionManager.requestSettingsGate(gate("a")) { false } }
        runCurrent()
        val staleRequest = OffloadPermissionManager.pendingSettingsGate.value!!
        advanceTimeBy(timeout + 1); runCurrent()
        assertEquals(AndroidPermissionResult.TIMEOUT, first.await())
        assertNull("the timed-out request is withdrawn, so its dialog closes", OffloadPermissionManager.pendingSettingsGate.value)
        val second = async { OffloadPermissionManager.requestSettingsGate(gate("b")) { false } }
        runCurrent()
        val live = OffloadPermissionManager.pendingSettingsGate.value!!
        assertFalse(OffloadPermissionManager.respondToSettingsGate(staleRequest, SettingsGateDecision.CANCEL))
        runCurrent()
        assertFalse(second.isCompleted)
        assertTrue(live === OffloadPermissionManager.pendingSettingsGate.value)
        assertTrue(OffloadPermissionManager.respondToSettingsGate(live, SettingsGateDecision.CANCEL))
        runCurrent()
        assertEquals(AndroidPermissionResult.DENIED, second.await())
    }

    @Test fun `cancelling the settings dialog still answers DENIED`() = runTest {
        val call = async { OffloadPermissionManager.requestSettingsGate(gate("x")) { false } }
        runCurrent()
        OffloadPermissionManager.respondToSettingsGate(OffloadPermissionManager.pendingSettingsGate.value!!, SettingsGateDecision.CANCEL)
        runCurrent()
        assertEquals(AndroidPermissionResult.DENIED, call.await())
    }
}
