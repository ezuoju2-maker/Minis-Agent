package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeInlineSplitOffsetTest {

    private fun pad(lines: Int = 90) =
        (1..lines).joinToString("\n") { "$it. 这是一行足够长的正文内容用于撑开增量解析的阈值" } + "\n"

    @Test
    fun `inline code containing double star does not pin the offset at zero`() {
        val text = "1. 通配符写法 `a**b` 注意\n" + pad() + "z".repeat(300)
        val offset = safeInlineSplitOffset(text)
        assertTrue(
            "a `**` INSIDE inline code is a literal to parseInline and must not " +
                "leave the bold counter open — offset was $offset (0 means the " +
                "whole message re-parses every tick, which is the reported flicker)",
            offset > 0,
        )
    }

    @Test
    fun `offset advances as the stream grows`() {
        val head = "1. 通配符 `x**y` 说明\n" + pad()
        val short = head + "z".repeat(300)
        val long = head + pad(40) + "z".repeat(300)
        val a = safeInlineSplitOffset(short)
        val b = safeInlineSplitOffset(long)
        assertTrue("offset must be usable on the shorter buffer, got $a", a > 0)
        assertTrue(
            "a longer buffer must freeze at least as much as the shorter one " +
                "(short=$a long=$b); a shrinking boundary re-parses settled text",
            b >= a,
        )
    }

    @Test
    fun `unclosed backtick is treated as a literal, matching parseInline`() {
        val text = "1. 孤立反引号 ` 后面还有 **加粗** 内容\n" + pad() + "z".repeat(300)
        val offset = safeInlineSplitOffset(text)
        assertTrue("an unclosed backtick must not pin the offset, got $offset", offset > 0)
    }

    @Test
    fun `an open bold span still blocks the split`() {
        val text = "1. 开头 **还没有闭合\n" + pad() + "z".repeat(300)
        val offset = safeInlineSplitOffset(text)
        assertEquals(
            "a genuinely open bold run must yield no safe boundary (offset=$offset)",
            0,
            offset,
        )
    }

    @Test
    fun `offset always lands right after a newline`() {
        val text = "1. 代码 `a**b` 与 **加粗** 混排\n" + pad() + "z".repeat(300)
        val offset = safeInlineSplitOffset(text)
        assertTrue("expected a usable offset, got $offset", offset > 0)
        assertEquals(
            "the split must sit immediately after a '\\n' — inline constructs " +
                "reset at line boundaries, which is what makes the split safe",
            '\n',
            text[offset - 1],
        )
    }

    @Test
    fun `short input yields no split`() {
        assertEquals(0, safeInlineSplitOffset("1. 很短的内容\n"))
    }
}
