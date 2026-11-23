package com.openminis.app.config.confirm

import com.openminis.app.config.ConfigRisk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigConfirmationGateNotifyTest {

    private val dispatcher = StandardTestDispatcher()
    private val notified = mutableListOf<String>()
    private val cancelled = mutableListOf<String>()

    private var appForeground = false

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        notified.clear()
        cancelled.clear()
        appForeground = false
        ConfigConfirmationGate.backgroundNotifier = { change ->
            if (appForeground) {
                false
            } else {
                notified.add(change.id)
                true
            }
        }
        ConfigConfirmationGate.cancelNotification = { id -> cancelled.add(id) }
    }

    @After
    fun tearDown() {
        ConfigConfirmationGate.userReject()
        ConfigConfirmationGate.backgroundNotifier = null
        ConfigConfirmationGate.cancelNotification = null
        Dispatchers.resetMain()
    }

    private fun change(caption: String? = "cap") = PendingConfigChange(
        items = listOf(
            PendingConfigChangeItem(
                displayName = "Field",
                path = "a.b",
                oldDisplay = "1",
                newDisplay = "2",
                verb = "set",
                risk = ConfigRisk.NORMAL,
            ),
        ),
        caption = caption,
    )

    @Test
    fun `backgrounded front-of-queue change is notified once`() = runTest(dispatcher) {
        val c = change()
        launch { ConfigConfirmationGate.requestConfirmation(c) }
        runCurrent()

        assertEquals(listOf(c.id), notified)

        ConfigConfirmationGate.notifyPending()
        runCurrent()
        assertEquals("still exactly one notification", listOf(c.id), notified)
    }

    @Test
    fun `foreground change is not notified`() = runTest(dispatcher) {
        appForeground = true
        val c = change()
        launch { ConfigConfirmationGate.requestConfirmation(c) }
        runCurrent()
        assertTrue("no notification while foreground", notified.isEmpty())
    }

    @Test
    fun `resolving clears the notification and lets a later change notify again`() =
        runTest(dispatcher) {
            val c = change()
            launch { ConfigConfirmationGate.requestConfirmation(c) }
            runCurrent()
            assertEquals(listOf(c.id), notified)

            ConfigConfirmationGate.userReject()
            runCurrent()
            assertEquals("resolve cancels the pending-approval notice", listOf(c.id), cancelled)

            val c2 = change()
            launch { ConfigConfirmationGate.requestConfirmation(c2) }
            runCurrent()
            assertEquals(listOf(c.id, c2.id), notified)
        }

    @Test
    fun `queue advance notifies the next backgrounded change`() = runTest(dispatcher) {
        val first = change("first")
        val second = change("second")
        launch { ConfigConfirmationGate.requestConfirmation(first) }
        runCurrent()
        launch { ConfigConfirmationGate.requestConfirmation(second) }
        runCurrent()
        assertEquals("only the front change is notified", listOf(first.id), notified)

        ConfigConfirmationGate.userReject()
        runCurrent()
        assertEquals(listOf(first.id, second.id), notified)
    }

    @Test
    fun `foreground app going to background nudges the pending change`() = runTest(dispatcher) {
        appForeground = true
        val c = change()
        launch { ConfigConfirmationGate.requestConfirmation(c) }
        runCurrent()
        assertTrue(notified.isEmpty())

        appForeground = false
        ConfigConfirmationGate.notifyPending()
        runCurrent()
        assertEquals(listOf(c.id), notified)
    }
}
