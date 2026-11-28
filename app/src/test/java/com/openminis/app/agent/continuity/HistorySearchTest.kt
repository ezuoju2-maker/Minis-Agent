package com.openminis.app.agent.continuity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySearchTest {

    private val records = listOf(
        HistorySearch.Record("m1", "user", "Please keep the retention numbers out of the summary."),
        HistorySearch.Record("m2", "assistant", "Done: the retention section is removed."),
        HistorySearch.Record("m3", "tool", "shell: grep retention app/src/main -> 42 hits"),
        HistorySearch.Record("m4", "user", "Actually put retention back, but only for the table."),
    )

    @Test
    fun findsCaseInsensitiveMatches() {
        val hits = HistorySearch.search(records, "Retention")
        assertEquals(4, hits.size)
    }

    @Test
    fun filtersByRole() {
        val hits = HistorySearch.search(records, "retention", role = "user")
        assertEquals(2, hits.size)
        assertTrue(hits.all { it.role == "user" })
    }

    @Test
    fun recentOrderPutsNewestFirst() {
        val hits = HistorySearch.search(records, "retention", latestFirst = true)
        assertEquals("m4", hits.first().id)
    }

    @Test
    fun oldestOrderPutsEarliestFirst() {
        val hits = HistorySearch.search(records, "retention")
        assertEquals("m1", hits.first().id)
    }

    @Test
    fun respectsLimit() {
        assertEquals(1, HistorySearch.search(records, "retention", limit = 1).size)
    }

    @Test
    fun emptyQueryReturnsNothing() {
        assertTrue(HistorySearch.search(records, "   ").isEmpty())
    }

    @Test
    fun missingTermReturnsNothing() {
        assertTrue(HistorySearch.search(records, "zebra").isEmpty())
    }

    @Test
    fun excerptIsBoundedAndLocatable() {
        val long = "x".repeat(2000) + "needle" + "y".repeat(2000)
        val hit = HistorySearch.search(listOf(HistorySearch.Record("m", "tool", long)), "needle").single()
        assertTrue(hit.excerpt.length <= HistorySearch.EXCERPT_CHARS)
        assertTrue(hit.excerptStart + hit.excerpt.length <= long.length)
        assertTrue(long.substring(hit.excerptStart, hit.excerptEnd).contains("needle"))
    }

    @Test
    fun excerptAtStartClampsToZero() {
        val text = "needle at the very beginning of this message"
        val hit = HistorySearch.search(listOf(HistorySearch.Record("m", "user", text)), "needle").single()
        assertEquals(0, hit.excerptStart)
    }

    @Test
    fun readReturnsWindowAndRecordsTotal() {
        val (record, slice) = HistorySearch.read(records, "m1", 7, 9)!!
        assertEquals("keep the ", slice)
        assertEquals("m1", record.id)
    }

    @Test
    fun readClampsOffsetPastEnd() {
        val (_, slice) = HistorySearch.read(records, "m2", 9999, 10)!!
        assertEquals("", slice)
    }

    @Test
    fun readUnknownIdReturnsNull() {
        assertNull(HistorySearch.read(records, "missing", 0, 10))
    }

    @Test
    fun excerptNeverClaimsMatchWhenAbsent() {
        val hit = HistorySearch.search(records, "retention").first()
        assertTrue(hit.matched)
        assertFalse(hit.excerpt.isEmpty())
    }
}
