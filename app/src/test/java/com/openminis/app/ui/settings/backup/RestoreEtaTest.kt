package com.openminis.app.ui.settings.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreEtaTest {

    private var now = 0L
    private fun eta(window: Int = 8) = RestoreEta(windowSize = window, nowMs = { now })

    @Test
    fun `no estimate from a single sample`() {
        assertNull(eta().update("chats", done = 100, total = 1000))
    }

    @Test
    fun `steady progress yields the arithmetic answer`() {
        val e = eta()
        e.update("chats", 0, 1000)
        now += 1000; e.update("chats", 100, 1000)
        now += 1000
        assertEquals(8L, e.update("chats", 200, 1000))
    }

    @Test
    fun `a stall reports no estimate rather than a wrong one`() {
        val e = eta()
        e.update("chats", 500, 1000)
        now += 5000
        assertNull(e.update("chats", 500, 1000))
    }

    @Test
    fun `changing category discards the old rate`() {
        val e = eta()
        e.update("chats", 0, 1000)
        now += 1000; e.update("chats", 500, 1000)
        now += 1000
        assertNull(e.update("skills", 10, 100))
    }

    @Test
    fun `progress going backwards restarts rather than going negative`() {
        val e = eta()
        e.update("chats", 900, 1000)
        now += 1000
        assertNull(e.update("chats", 10, 1000))
        now += 1000
        val v = e.update("chats", 110, 1000)
        assertTrue("should recover a sane estimate, got $v", v != null && v > 0)
    }

    @Test
    fun `a completed category reports zero`() {
        val e = eta()
        e.update("chats", 0, 1000)
        now += 1000
        assertEquals(0L, e.update("chats", 1000, 1000))
    }

    @Test
    fun `no total means no estimate`() {
        val e = eta()
        e.update("chats", 100, null)
        now += 1000
        assertNull(e.update("chats", 200, null))
    }

    @Test
    fun `the window slides so an old slow stretch stops counting`() {
        val e = eta(window = 2)
        e.update("chats", 0, 10_000)
        now += 10_000; e.update("chats", 100, 10_000)
        now += 1000; e.update("chats", 1100, 10_000)
        now += 1000
        val v = e.update("chats", 2100, 10_000)!!
        assertTrue("expected a recent-rate estimate, got $v", v in 5..12)
    }

    @Test
    fun `reset clears everything`() {
        val e = eta()
        e.update("chats", 0, 1000)
        now += 1000; e.update("chats", 100, 1000)
        e.reset()
        now += 1000
        assertNull(e.update("chats", 200, 1000))
    }


    @Test
    fun `buckets read the way a person would say them`() {
        assertNull(RestoreEta.bucket(null))
        assertEquals(RestoreEta.EtaBucket.AlmostDone, RestoreEta.bucket(0))
        assertEquals(RestoreEta.EtaBucket.AlmostDone, RestoreEta.bucket(3))
        assertEquals(RestoreEta.EtaBucket.Seconds(35), RestoreEta.bucket(35))
        assertEquals(RestoreEta.EtaBucket.Seconds(59), RestoreEta.bucket(59))
        assertEquals(RestoreEta.EtaBucket.MinutesSeconds(1, 0), RestoreEta.bucket(60))
        assertEquals(RestoreEta.EtaBucket.MinutesSeconds(1, 45), RestoreEta.bucket(105))
    }

    @Test
    fun `a near-zero estimate does not render as zero seconds`() {
        assertEquals(RestoreEta.EtaBucket.AlmostDone, RestoreEta.bucket(1))
    }
}
