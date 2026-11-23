package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [long-tasks] Checkpoint ledger: idempotent stages, resumable summary, and
 * JSON round-trip that survives a compact/restart.
 */
class CheckpointLedgerTest {

    @Test
    fun `recording a stage twice updates without duplicating`() {
        val l = CheckpointLedger()
        assertTrue(l.record("s1", "Gather inputs", "in_progress"))
        assertFalse(l.record("s1", "Gather inputs", "done", "found 3 files"))
        assertEquals(1, l.all().size)
        assertEquals("done", l.get("s1")?.status)
    }

    @Test
    fun `nextPending follows insertion order`() {
        val l = CheckpointLedger()
        l.record("a", "A", "done")
        l.record("b", "B", "pending")
        l.record("c", "C", "pending")
        assertEquals("b", l.nextPending()?.key)
    }

    @Test
    fun `isComplete only when all stages done`() {
        val l = CheckpointLedger()
        assertFalse(l.isComplete) // empty
        l.record("a", "A", "done")
        l.record("b", "B", "pending")
        assertFalse(l.isComplete)
        l.record("b", "B", "done")
        assertTrue(l.isComplete)
    }

    @Test
    fun `resume summary shows completed next and blocked`() {
        val l = CheckpointLedger()
        l.record("a", "Download data", "done", "cached")
        l.record("b", "Transform", "blocked", "needs schema")
        l.record("c", "Publish", "pending")
        val s = l.resumeSummary()
        assertTrue(s.contains("1/3 done"))
        assertTrue(s.contains("Download data — cached"))
        assertTrue(s.contains("needs schema"))
        assertTrue(s.contains("Transform: needs schema")) // listed under Blocked
        assertTrue(s.contains("Next: Publish")) // next actionable (pending), not the blocked one
    }

    @Test
    fun `status normalization handles aliases`() {
        val l = CheckpointLedger()
        l.record("a", "A", "completed")
        l.record("b", "B", "running")
        l.record("c", "C", "failed")
        assertEquals("done", l.get("a")?.status)
        assertEquals("in_progress", l.get("b")?.status)
        assertEquals("blocked", l.get("c")?.status)
    }

    @Test
    fun `json round trip preserves stages`() {
        val l = CheckpointLedger()
        l.record("a", "A", "done", "n1", 100L)
        l.record("b", "B", "in_progress", "n2", 200L)
        val json = l.toJson()

        val restored = CheckpointLedger()
        restored.load(json)
        assertEquals(2, restored.all().size)
        assertEquals("done", restored.get("a")?.status)
        assertEquals(200L, restored.get("b")?.updatedAt)
    }

    @Test
    fun `malformed json keeps the existing ledger intact`() {
        val l = CheckpointLedger()
        l.record("a", "A", "done")
        // [v2.0 FIX-03] load() is transactional: bad JSON must not wipe a
        // ledger the caller already populated, and the failure is reported.
        assertFalse(l.load("{ broken"))
        assertEquals(1, l.all().size)
        assertEquals("done", l.get("a")?.status)
    }
}
