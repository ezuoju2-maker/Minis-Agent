package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangePreviewTest {

    @Test
    fun `identical text yields no changes`() {
        val stats = ChangePreview.stats("a\nb\nc", "a\nb\nc")
        assertEquals(0, stats.changed)
        assertEquals(3, stats.unchanged)
    }

    @Test
    fun `one line replaced is one remove and one add`() {
        val stats = ChangePreview.stats("a\nb\nc", "a\nB\nc")
        assertEquals(1, stats.removed)
        assertEquals(1, stats.added)
        val d = ChangePreview.diff("a\nb\nc", "a\nB\nc")
        assertTrue(d.any { it.kind == ChangePreview.Line.Kind.REMOVE && it.text == "b" })
        assertTrue(d.any { it.kind == ChangePreview.Line.Kind.ADD && it.text == "B" })
    }

    @Test
    fun `pure insertion and pure deletion counted correctly`() {
        val ins = ChangePreview.stats("a\nc", "a\nb\nc")
        assertEquals(1, ins.added)
        assertEquals(0, ins.removed)
        val del = ChangePreview.stats("a\nb\nc", "a\nc")
        assertEquals(0, del.added)
        assertEquals(1, del.removed)
    }

    @Test
    fun `empty to content and content to empty`() {
        assertEquals(2, ChangePreview.stats("", "a\nb").added)
        assertEquals(2, ChangePreview.stats("a\nb", "").removed)
        assertEquals(0, ChangePreview.stats("", "").changed)
    }

    @Test
    fun `diff is deterministic across calls`() {
        val a = ChangePreview.render("x\nold\ny", "x\nnew\ny")
        val b = ChangePreview.render("x\nold\ny", "x\nnew\ny")
        assertEquals(a, b)
    }

    @Test
    fun `large input is marked approximate and preview stays bounded`() {
        val before = (1..500).joinToString("\n") { "line$it" }
        val after = before.replace("line250", "CHANGED")
        val stats = ChangePreview.stats(before, after)
        assertTrue(stats.approximate)
        val rendered = ChangePreview.render(before, after, maxChars = 400)
        assertTrue(rendered.length <= 400)
    }

    @Test
    fun `line numbers increase for context lines`() {
        val d = ChangePreview.diff("a\nb", "a\nc\nb")
        val ctx = d.filter { it.kind == ChangePreview.Line.Kind.CONTEXT }
        assertEquals(listOf(1, 2), ctx.map { it.oldNumber }.take(2))
        assertTrue(ctx.last().newNumber > ctx.last().oldNumber)
    }
}
