package com.openminis.app.provider

import com.openminis.app.provider.openai.ThinkPrefixStreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkPrefixStreamParserTest {

    private fun run(vararg chunks: String): Pair<String, String> {
        val p = ThinkPrefixStreamParser()
        val vis = StringBuilder()
        val think = StringBuilder()
        for (c in chunks) {
            val o = p.feed(c)
            vis.append(o.visible); think.append(o.thinking)
        }
        val fin = p.finishTurn()
        vis.append(fin.visible); think.append(fin.thinking)
        return vis.toString() to think.toString()
    }


    @Test
    fun `think prefix is split and the trailing newlines are dropped`() {
        val (vis, think) = run("<think>reasoning here</think>\n\nActual body text.")
        assertEquals("Actual body text.", vis)
        assertEquals("reasoning here", think)
    }

    @Test
    fun `think-only turn yields a completely empty body`() {
        val (vis, think) = run("<think>just planning</think>\n\n")
        assertEquals("", vis)
        assertTrue("body must be empty, not whitespace", vis.isEmpty())
        assertEquals("just planning", think)
    }

    @Test
    fun `leading whitespace before the think tag is tolerated and dropped`() {
        val (vis, think) = run("\n  <think>r</think>\n\nbody")
        assertEquals("body", vis)
        assertEquals("r", think)
    }


    @Test
    fun `mid-text think tag stays verbatim in the body`() {
        val input = "Use the <think> tag to mark reasoning."
        val (vis, think) = run(input)
        assertEquals(input, vis)
        assertEquals("", think)
    }

    @Test
    fun `a full think block mid-text is not treated as a prefix`() {
        val input = "Intro text <think>not a prefix</think> tail"
        val (vis, think) = run(input)
        assertEquals(input, vis)
        assertEquals("", think)
    }


    @Test
    fun `open tag split across chunks still enters thinking`() {
        val (vis, think) = run("<thi", "nk>abc</think>\n\nbody")
        assertEquals("body", vis)
        assertEquals("abc", think)
    }

    @Test
    fun `close tag split across chunks still exits thinking`() {
        val (vis, think) = run("<think>abc</thi", "nk>\n\nbody")
        assertEquals("body", vis)
        assertEquals("abc", think)
    }

    @Test
    fun `thinking streams incrementally rather than only at the end`() {
        val p = ThinkPrefixStreamParser()
        val first = p.feed("<think>partial reasoning")
        assertTrue("thinking must stream before </think> arrives", first.thinking.isNotEmpty())
        assertEquals("", first.visible)
    }

    @Test
    fun `body whitespace is preserved when interior`() {
        val (vis, _) = run("<think>r</think>\n\nline one\n\nline two")
        assertEquals("line one\n\nline two", vis)
    }


    @Test
    fun `plain text without any think tag passes through verbatim`() {
        val input = "Hello, this is a normal reply."
        val (vis, think) = run(input)
        assertEquals(input, vis)
        assertEquals("", think)
    }

    @Test
    fun `plain text arriving in many chunks is reassembled exactly`() {
        val (vis, think) = run("Hel", "lo ", "world", "!")
        assertEquals("Hello world!", vis)
        assertEquals("", think)
    }

    @Test
    fun `text with leading whitespace and no think tag keeps that whitespace`() {
        val (vis, _) = run("  indented start")
        assertEquals("  indented start", vis)
    }


    @Test
    fun `unterminated think block is reported as thinking not body`() {
        val (vis, think) = run("<think>reasoning that never closes")
        assertEquals("", vis)
        assertEquals("reasoning that never closes", think)
    }

    @Test
    fun `finishTurn is idempotent across the finish_reason and DONE double flush`() {
        val p = ThinkPrefixStreamParser()
        p.feed("<think>r</think>\n\nbody")
        val a = p.finishTurn()
        val b = p.finishTurn()
        assertEquals("", b.visible)
        assertEquals("", b.thinking)
        assertEquals("", a.thinking)
    }

    @Test
    fun `ordinary text is released by feed itself, leaving nothing to resolve`() {
        val p = ThinkPrefixStreamParser()
        assertEquals("Some text", p.feed("Some text").visible)
        assertEquals("", p.resolveAtToolBoundary().visible)
    }

    @Test
    fun `resolveAtToolBoundary releases whitespace held in the undecided state`() {
        val p = ThinkPrefixStreamParser()
        assertEquals("", p.feed("  ").visible)
        assertEquals("  ", p.resolveAtToolBoundary().visible)
    }

    @Test
    fun `resolveAtToolBoundary does not flush a possible partial open tag`() {
        val p = ThinkPrefixStreamParser()
        p.feed("<thi")
        val out = p.resolveAtToolBoundary()
        assertEquals("", out.visible)
        val o2 = p.feed("nk>abc</think>\n\nbody")
        assertEquals("abc", o2.thinking)
    }
}
