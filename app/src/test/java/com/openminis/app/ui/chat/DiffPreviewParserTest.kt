package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiffPreviewParserTest {

    private val sample = """
        Edited app/Main.kt (1 replacement(s), 220 bytes)
        Changes:
        +0/1  fun main() {
         1/2      println("hi")
        -2/3      println("bye")
        -3/4  }
    """.trimIndent()

    @Test
    fun `section splits the diff out of the tool output`() {
        val section = DiffPreviewParser.section(sample)
        assertEquals(4, DiffPreviewParser.rows(section).size)
    }

    @Test
    fun `rows keep marker numbers and text`() {
        val rows = DiffPreviewParser.rows(DiffPreviewParser.section(sample))
        assertEquals('+', rows[0].marker)
        assertEquals(0, rows[0].oldNumber)
        assertEquals(1, rows[0].newNumber)
        assertEquals("fun main() {", rows[0].text)
        assertEquals(' ', rows[1].marker)
        assertEquals("println(\"hi\")", rows[1].text)
    }

    @Test
    fun `stats count additions and removals`() {
        val rows = DiffPreviewParser.rows(DiffPreviewParser.section(sample))
        assertEquals(1 to 2, DiffPreviewParser.stats(rows))
    }

    @Test
    fun `plain output has no diff section`() {
        assertNull(DiffPreviewParser.section("Edited a.txt (1 replacement, 10 bytes)"))
    }

    @Test
    fun `empty sections yield no rows`() {
        assertEquals(0, DiffPreviewParser.rows(null).size)
        assertEquals(0, DiffPreviewParser.rows("").size)
    }

    @Test
    fun `truncation note is ignored`() {
        val rows = DiffPreviewParser.rows("+0/1  a\n… (preview truncated)")
        assertEquals(1, rows.size)
        assertEquals("a", rows[0].text)
    }
}
