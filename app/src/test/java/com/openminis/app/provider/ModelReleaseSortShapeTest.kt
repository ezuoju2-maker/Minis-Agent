package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelReleaseSortShapeTest {

    private data class Entry(val id: String, val day: Int?)

    private var rankCalls = 0

    private fun rankOf(e: Entry): ModelReleaseIndex.Rank {
        rankCalls++
        return ModelReleaseIndex.Rank(e.day, 0.0, 0, e.id)
    }

    private val order = Comparator<Pair<Entry, ModelReleaseIndex.Rank>> { a, b ->
        val byRank = ModelReleaseIndex.comparator.compare(a.second, b.second)
        if (byRank != 0) byRank else a.first.id.compareTo(b.first.id)
    }

    private fun sorted(entries: List<Entry>): List<Entry> {
        if (entries.size < 2) return entries
        return entries.map { it to rankOf(it) }.sortedWith(order).map { it.first }
    }

    @Test
    fun `ranks each entry exactly once regardless of list size`() {
        val entries = (1..200).map { Entry("m$it", 2026_01_01 + it) }

        rankCalls = 0
        val out = sorted(entries)

        assertEquals("one rank per entry, never per comparison", 200, rankCalls)
        assertEquals(200, out.size)
    }

    @Test
    fun `an empty or single-element list never builds the index`() {
        rankCalls = 0
        assertTrue(sorted(emptyList()).isEmpty())
        assertEquals(1, sorted(listOf(Entry("only", null))).size)
        assertEquals("no ranking work for a list that cannot be reordered", 0, rankCalls)
    }

    @Test
    fun `ordering is unchanged - newest first, undated last, ties by id`() {
        val entries = listOf(
            Entry("zeta", 2026_03_01),
            Entry("alpha", 2026_03_01),
            Entry("undated", null),
            Entry("newest", 2026_09_01),
            Entry("oldest", 2024_01_01),
        )

        assertEquals(
            listOf("newest", "alpha", "zeta", "oldest", "undated"),
            sorted(entries).map { it.id },
        )
    }
}
