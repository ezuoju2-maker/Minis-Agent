package com.openminis.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTreeDiffTest {

    private fun n(id: String, text: String, bounds: String = "0,0-1,1", flags: Int = 0) =
        ScreenTreeDiff.Node(id, text = text, bounds = bounds, flags = flags)

    @Test
    fun `identical snapshot yields empty delta`() {
        val tree = listOf(n("a", "Hello"), n("b", "World"))
        val d = ScreenTreeDiff.diff(tree, tree)
        assertTrue(d.isEmpty)
        assertEquals(2, d.unchangedCount)
        assertTrue(ScreenTreeDiff.render(d).contains("unchanged"))
    }

    @Test
    fun `added and removed nodes reported`() {
        val before = listOf(n("a", "Keep"), n("b", "Gone"))
        val after = listOf(n("a", "Keep"), n("c", "New"))
        val d = ScreenTreeDiff.diff(before, after)
        assertEquals(listOf("c"), d.added.map { it.id })
        assertEquals(listOf("b"), d.removed)
        assertEquals(1, d.unchangedCount)
    }

    @Test
    fun `changed text is reported with old signature`() {
        val before = listOf(n("a", "Old"))
        val after = listOf(n("a", "New"))
        val d = ScreenTreeDiff.diff(before, after)
        assertEquals(1, d.changed.size)
        assertEquals("a", d.changed[0].first.id)
        assertTrue(d.changed[0].second.contains("Old"))
    }

    @Test
    fun `bounds-only move is a change`() {
        val d = ScreenTreeDiff.diff(
            listOf(n("a", "Btn", bounds = "0,0-10,10")),
            listOf(n("a", "Btn", bounds = "20,20-30,30")),
        )
        assertEquals(1, d.changed.size)
    }

    @Test
    fun `duplicate resource ids are disambiguated by occurrence`() {
        val before = listOf(n("row", "Item1"), n("row", "Item2"))
        val after = listOf(n("row", "Item1"), n("row", "Item2"))
        assertTrue(ScreenTreeDiff.diff(before, after).isEmpty)

        val after2 = listOf(n("row", "Item1"), n("row", "CHANGED"))
        val d2 = ScreenTreeDiff.diff(before, after2)
        assertEquals(1, d2.changed.size)
    }

    @Test
    fun `render respects char budget`() {
        val many = (1..200).map { n("id$it", "A long label for node number $it that takes space") }
        val d = ScreenTreeDiff.diff(emptyList(), many)
        val rendered = ScreenTreeDiff.render(d, maxChars = 500)
        assertTrue(rendered.length <= 500)
    }

    @Test
    fun `flags change is detected`() {
        val d = ScreenTreeDiff.diff(
            listOf(n("a", "Box", flags = 0)),
            listOf(n("a", "Box", flags = 1)),
        )
        assertFalse(d.isEmpty)
        assertEquals(1, d.changed.size)
    }
}
