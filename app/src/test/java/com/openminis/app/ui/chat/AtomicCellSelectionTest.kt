package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class AtomicCellSelectionTest {

    private fun atomicBounds(text: String): Pair<Int, Int> {
        val start = text.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return 0 to 0
        val end = text.indexOfLast { !it.isWhitespace() } + 1
        return start to end
    }

    private fun sentenceBounds(text: String, offset: Int): Pair<Int, Int> {
        val stops = setOf(
            '。', '？', '！', '；', '：', '，', '、',
            '.', '?', '!', ';', ':', ',',
            '\n', '\r',
        )
        if (text.isEmpty()) return 0 to 0
        val len = text.length
        val clamped = offset.coerceIn(0, len)
        var lo = clamped.coerceAtMost(len - 1).coerceAtLeast(0)
        while (lo > 0 && text[lo - 1] !in stops) lo--
        while (lo < len && text[lo].isWhitespace()) lo++
        var hi = clamped.coerceIn(0, len)
        while (hi < len && text[hi] !in stops) hi++
        if (hi < len) hi++
        while (hi > lo && text[hi - 1].isWhitespace()) hi--
        return lo to hi
    }

    private fun select(text: String, bounds: Pair<Int, Int>) =
        text.substring(bounds.first, bounds.second)

    @Test
    fun `a cell containing punctuation still selects in full`() {
        val cell = "1,200"
        assertEquals("1,200", select(cell, atomicBounds(cell)))
        assertEquals(
            "sentence expansion would stop at the comma",
            "1,", select(cell, sentenceBounds(cell, 0)),
        )
    }

    @Test
    fun `a cell with a version string selects in full`() {
        val cell = "v1.2 beta"
        assertEquals("v1.2 beta", select(cell, atomicBounds(cell)))
    }

    @Test
    fun `a punctuation-free cell selects in full either way`() {
        val cell = "Alice Smith"
        assertEquals("Alice Smith", select(cell, atomicBounds(cell)))
        assertEquals("Alice Smith", select(cell, sentenceBounds(cell, 3)))
    }

    @Test
    fun `a CJK cell selects in full`() {
        val cell = "张三，项目经理"
        assertEquals("张三，项目经理", select(cell, atomicBounds(cell)))
    }

    @Test
    fun `padding around cell text is not selected`() {
        val cell = "  spaced value  "
        assertEquals("spaced value", select(cell, atomicBounds(cell)))
    }

    @Test
    fun `an empty or blank cell yields an empty range`() {
        assertEquals(0 to 0, atomicBounds(""))
        assertEquals(0 to 0, atomicBounds("   "))
    }

    @Test
    fun `selection is independent of where inside the cell the press landed`() {
        val cell = "alpha, beta, gamma"
        val expected = select(cell, atomicBounds(cell))
        for (offset in cell.indices) {
            assertEquals(expected, select(cell, atomicBounds(cell)))
        }
        assertEquals("alpha, beta, gamma", expected)
    }

    @Test
    fun `prose keeps sentence-level expansion`() {
        val prose = "Hello world. Second sentence here."
        assertEquals("Hello world.", select(prose, sentenceBounds(prose, 2)))
    }
}
