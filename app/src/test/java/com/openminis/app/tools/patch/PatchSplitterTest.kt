package com.openminis.app.tools.patch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchSplitterTest {

    private val before = """
        line1
        line2
        line3
        line4
        line5
        line6
        line7
        line8
        line9
        line10
    """.trimIndent() + "\n"

    private val after = """
        line1
        line2
        TWO
        line4
        line5
        line6
        line7
        line8
        NINE
        line10
    """.trimIndent() + "\n"

    @Test
    fun `splits distant edits into separate hunks`() {
        val plan = PatchSplitter.plan(before, after, context = 1)
        assertTrue("expected at least two hunks", plan.hunks.size >= 2)
        assertTrue(plan.complete)
    }

    @Test
    fun `accepting everything reproduces the new text`() {
        val plan = PatchSplitter.plan(before, after)
        assertEquals(after, plan.apply(plan.ids().toSet()))
    }

    @Test
    fun `accepting nothing reproduces the old text`() {
        val plan = PatchSplitter.plan(before, after)
        assertEquals(before, plan.apply(emptySet()))
    }

    @Test
    fun `accepting only the first hunk keeps the second edit out`() {
        val plan = PatchSplitter.plan(before, after, context = 1)
        val first = plan.apply(setOf(0))
        assertTrue(first.contains("TWO"))
        assertTrue(!first.contains("NINE"))
        assertTrue(first.endsWith("\n"))
    }

    @Test
    fun `pure insertion hunk applies without dropping neighbours`() {
        val b = "a\nb\n"
        val a = "a\nnew\nb\n"
        val plan = PatchSplitter.plan(b, a)
        assertEquals(a, plan.apply(plan.ids().toSet()))
        assertEquals(b, plan.apply(emptySet()))
    }

    @Test
    fun `pure deletion hunk restores when rejected`() {
        val b = "a\ngone\nb\n"
        val a = "a\nb\n"
        val plan = PatchSplitter.plan(b, a)
        assertEquals(a, plan.apply(plan.ids().toSet()))
        assertEquals(b, plan.apply(emptySet()))
    }

    @Test
    fun `missing trailing newline is preserved`() {
        val plan = PatchSplitter.plan("x\ny", "x\nz")
        assertEquals("x\nz", plan.apply(plan.ids().toSet()))
    }

    @Test
    fun `oversized diff is marked incomplete`() {
        val b = (1..600).joinToString("\n") { "l$it" }
        val a = (1..600).joinToString("\n") { if (it == 300) "changed" else "l$it" }
        val plan = PatchSplitter.plan(b, a)
        assertFalse("truncated diffs must not be treated as fully applicable", plan.complete)
    }

    @Test
    fun `identical texts produce no hunks`() {
        val plan = PatchSplitter.plan("same\n", "same\n")
        assertEquals(0, plan.hunks.size)
        assertEquals("same\n", plan.apply(emptySet()))
    }
}
