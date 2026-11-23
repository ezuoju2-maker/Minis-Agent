package com.openminis.app.service

import com.openminis.app.ProductionSources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Review0923OverlayTaskSlotsTest {

    private val ids = listOf("review0923-a", "review0923-b", "review0923-c")

    @Before
    fun reset() = cleanup()

    @After
    fun cleanup() {
        ids.forEach { SessionActivityTracker.setInactive(it) }
        SessionActivityTracker.clearOverlayTasks()
    }

    @Test
    fun `two concurrent runs are numbered by start order and focus follows the newest`() {
        SessionActivityTracker.setActive(ids[0], sessionTitle = "Task A")
        SessionActivityTracker.setActive(ids[1], sessionTitle = "Task B")
        val slot = SessionActivityTracker.focusedTaskSlot()
        assertNotNull(slot)
        assertEquals(ids[1], slot!!.first.sessionId)
        assertEquals(2, slot.second)
        assertEquals(2, slot.third)
    }

    @Test
    fun `a finished run keeps its number while the capsule lingers`() {
        SessionActivityTracker.setActive(ids[0], sessionTitle = "Task A")
        SessionActivityTracker.setActive(ids[1], sessionTitle = "Task B")
        SessionActivityTracker.setInactive(ids[0])
        val tasks = SessionActivityTracker.overlayTasks.value
        assertEquals(listOf(ids[0], ids[1]), tasks.map { it.sessionId })
        assertTrue(tasks[0].finished)
        assertFalse(tasks[1].finished)
    }

    @Test
    fun `a re-run keeps its slot position and a null or blank title does not erase the known one`() {
        SessionActivityTracker.setActive(ids[0], sessionTitle = "Task A")
        SessionActivityTracker.setActive(ids[1], sessionTitle = "Task B")
        SessionActivityTracker.setInactive(ids[0])
        SessionActivityTracker.setActive(ids[0], sessionTitle = null)
        val tasks = SessionActivityTracker.overlayTasks.value
        assertEquals(listOf(ids[0], ids[1]), tasks.map { it.sessionId })
        assertEquals("Task A", tasks[0].title)
        assertFalse(tasks[0].finished)
        SessionActivityTracker.setActive(ids[0], sessionTitle = "  ")
        assertEquals("Task A", SessionActivityTracker.overlayTasks.value[0].title)
    }

    @Test
    fun `the first cycle advances from the slot actually being shown`() {
        SessionActivityTracker.setActive(ids[0], sessionTitle = "A")
        SessionActivityTracker.setActive(ids[1], sessionTitle = "B")
        SessionActivityTracker.setActive(ids[2], sessionTitle = "C")
        assertEquals(ids[2], SessionActivityTracker.focusedTaskSlot()!!.first.sessionId)
        SessionActivityTracker.cycleFocusedTask()
        assertEquals(
            "showing 3 of 3, one cycle must wrap to 1 of 3",
            ids[0],
            SessionActivityTracker.focusedTaskSlot()!!.first.sessionId,
        )
    }

    @Test
    fun `focus held on a removed slot falls back to the newest`() {
        SessionActivityTracker.setActive(ids[0], sessionTitle = "A")
        SessionActivityTracker.setActive(ids[1], sessionTitle = "B")
        SessionActivityTracker.setActive(ids[2], sessionTitle = "C")
        while (SessionActivityTracker.focusedTaskSlot()!!.first.sessionId != ids[0]) {
            SessionActivityTracker.cycleFocusedTask()
        }
        SessionActivityTracker.removeOverlayTask(ids[0])
        val slot = SessionActivityTracker.focusedTaskSlot()!!
        assertEquals(ids[2], slot.first.sessionId)
        assertEquals(2, slot.second)
        assertEquals(2, slot.third)
    }

    @Test
    fun `hiding the capsule never drops the slot of a session that is still running`() {
        SessionActivityTracker.setActive(ids[0], sessionTitle = "Task A")
        SessionActivityTracker.setActive(ids[1], sessionTitle = "Task B")
        SessionActivityTracker.setActive(ids[2], sessionTitle = "Task C")
        SessionActivityTracker.setInactive(ids[2])

        invokeHideOverlayTrackerEffect()

        val slotted = SessionActivityTracker.overlayTasks.value.map { it.sessionId }.toSet()
        for (sid in listOf(ids[0], ids[1])) {
            assertTrue(
                "running session $sid lost its capsule slot when the capsule was hidden " +
                    "(slots now: $slotted) — it will come back untitled and unnumbered",
                sid in slotted,
            )
        }
        assertFalse(
            "a finished slot must still be dropped on hide (the c9e859dca leak)",
            ids[2] in slotted,
        )
    }

    private fun invokeHideOverlayTrackerEffect() {
        val src = ProductionSources.read("service/AgentForegroundService.kt")
        val body = src.substringAfter("private fun hideOverlay()").substringBefore("\n    }")
        val calls = Regex("SessionActivityTracker\\.(\\w+)\\(\\)").findAll(body).map { it.groupValues[1] }.toList()
        assertTrue("hideOverlay() must touch the tracker's slots", calls.isNotEmpty())
        for (name in calls) {
            val m = SessionActivityTracker::class.java.getMethod(name)
            m.invoke(SessionActivityTracker)
        }
    }
}
