package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedPromptInjectRaceTest {

    private data class Msg(val id: String, val queuedPromptId: String? = null)

    private fun emitFromSnapshot(snapshot: List<Msg>, queuedIds: Set<String>): List<Msg> =
        snapshot.filterNot { it.queuedPromptId != null && it.queuedPromptId in queuedIds }

    private fun emitFromLive(live: List<Msg>, queuedIds: Set<String>): List<Msg> =
        live.filterNot { it.queuedPromptId != null && it.queuedPromptId in queuedIds }


    @Test
    fun `a message appended during the suspend window survives the inject emit`() {
        val queuedIds = setOf("p1")
        val atCaptureTime = listOf(
            Msg("m1"), Msg("m2"),
            Msg("queued_msg_p1", queuedPromptId = "p1"),
        )
        val live = atCaptureTime + Msg("queued_msg_p2", queuedPromptId = "p2")

        val fixed = emitFromLive(live, queuedIds)

        assertTrue(
            "the message sent mid-task must still be in the list — this is the " +
                "exact bubble the user watched disappear",
            fixed.any { it.id == "queued_msg_p2" },
        )
        assertTrue(
            "the placeholder being replaced must still be removed",
            fixed.none { it.id == "queued_msg_p1" },
        )
        assertEquals(listOf("m1", "m2", "queued_msg_p2"), fixed.map { it.id })
    }

    @Test
    fun `the stale-snapshot shape is what loses it`() {
        val queuedIds = setOf("p1")
        val atCaptureTime = listOf(
            Msg("m1"), Msg("m2"),
            Msg("queued_msg_p1", queuedPromptId = "p1"),
        )
        val live = atCaptureTime + Msg("queued_msg_p2", queuedPromptId = "p2")

        val buggy = emitFromSnapshot(atCaptureTime, queuedIds)

        assertTrue(
            "the pre-fix shape drops the mid-window message — that IS the bug",
            buggy.none { it.id == "queued_msg_p2" },
        )
        assertTrue("and the live list did contain it", live.any { it.id == "queued_msg_p2" })
    }

    @Test
    fun `a programmatic enqueue arriving mid-window also survives`() {
        val queuedIds = setOf("p1")
        val live = listOf(
            Msg("m1"),
            Msg("queued_msg_p1", queuedPromptId = "p1"),
            Msg("queued_msg_cb", queuedPromptId = "cb"),
        )
        val fixed = emitFromLive(live, queuedIds)
        assertTrue(fixed.any { it.id == "queued_msg_cb" })
        assertTrue(fixed.none { it.id == "queued_msg_p1" })
    }

    @Test
    fun `with no concurrent append the two shapes agree`() {
        val queuedIds = setOf("p1", "p2")
        val list = listOf(
            Msg("m1"),
            Msg("queued_msg_p1", queuedPromptId = "p1"),
            Msg("queued_msg_p2", queuedPromptId = "p2"),
        )
        assertEquals(emitFromSnapshot(list, queuedIds), emitFromLive(list, queuedIds))
        assertEquals(listOf("m1"), emitFromLive(list, queuedIds).map { it.id })
    }

    @Test
    fun `only the placeholders being injected are removed`() {
        val queuedIds = setOf("p1")
        val live = listOf(
            Msg("queued_msg_p1", queuedPromptId = "p1"),
            Msg("queued_msg_p9", queuedPromptId = "p9"),
        )
        val fixed = emitFromLive(live, queuedIds)
        assertEquals(listOf("queued_msg_p9"), fixed.map { it.id })
    }
}
