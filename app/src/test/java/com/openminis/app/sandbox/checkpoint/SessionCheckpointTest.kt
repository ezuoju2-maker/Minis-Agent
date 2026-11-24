package com.openminis.app.sandbox.checkpoint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCheckpointTest {

    @Test
    fun `first write keeps the original content`() {
        val cp = SessionCheckpoint()
        assertTrue(cp.record(1, "/w/a.txt", "old", "new"))
        val plan = cp.restorePlan()
        assertEquals(1, plan.size)
        assertEquals("/w/a.txt", plan[0].path)
        assertEquals("old", plan[0].content)
    }

    @Test
    fun `later writes keep the earliest before image`() {
        val cp = SessionCheckpoint()
        cp.record(1, "/w/a.txt", "v1", "v2")
        cp.record(2, "/w/a.txt", "v2", "v3")
        assertEquals("v1", cp.restorePlan()[0].content)
        assertEquals(1, cp.size)
    }

    @Test
    fun `identical before and after is ignored`() {
        val cp = SessionCheckpoint()
        assertFalse(cp.record(1, "/w/a.txt", "same", "same"))
        assertEquals(0, cp.size)
    }

    @Test
    fun `created file restores as a delete`() {
        val cp = SessionCheckpoint()
        assertTrue(cp.record(1, "/w/new.txt", null, "hello"))
        assertNull(cp.restorePlan()[0].content)
    }

    @Test
    fun `revert of one path is independent`() {
        val cp = SessionCheckpoint()
        cp.record(1, "/w/a.txt", "a0", "a1")
        cp.record(1, "/w/b.txt", "b0", "b1")
        assertEquals("a0", cp.revertPlan("/w/a.txt")?.content)
        assertNull(cp.revertPlan("/w/missing.txt"))
    }

    @Test
    fun `file cap evicts the oldest entry`() {
        val cp = SessionCheckpoint(maxFiles = 2, maxBytes = 1_000_000)
        cp.record(1, "/w/1.txt", "", "1")
        cp.record(2, "/w/2.txt", "", "2")
        cp.record(3, "/w/3.txt", "", "3")
        assertEquals(2, cp.size)
        assertFalse(cp.changedPaths().contains("/w/1.txt"))
    }

    @Test
    fun `oversized file is truncated and flagged`() {
        val cp = SessionCheckpoint(maxFiles = 2, maxBytes = 100)
        cp.record(1, "/w/big.txt", null, "x".repeat(200))
        val op = cp.restorePlan().first()
        assertTrue(op.truncated)
        assertTrue(op.content == null)
    }

    @Test
    fun `summary and clearing behave`() {
        val cp = SessionCheckpoint()
        assertEquals("no changes recorded", cp.summary())
        cp.record(3, "/w/a.txt", "", "x")
        assertTrue(cp.summary().contains("1 file"))
        cp.markRestored()
        assertEquals(0, cp.size)
        assertTrue(cp.restorePlan().isEmpty())
    }
}
