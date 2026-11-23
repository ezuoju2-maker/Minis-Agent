package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ListNestedTableParseTest {
    private val item = "4. Name it `Minis DNS`, with permissions:"
    private val table = """
        |   | Item | Setting |
        |   |---|---|
        |   | Permission | `Zone → DNS → Edit` |
        |   | Client IP | leave empty |
    """.trimMargin()

    @Test
    fun tableUnderOrderedItemBecomesTableChild() {
        assertEquals(
            listOf("OrderedList[Paragraph,Table]"),
            debugParseBlockShapes("$item\n\n$table"),
        )
    }

    @Test
    fun coalescedFragmentsParseTheSameAsTheJoinedText() {
        val fragments = splitMarkdownIntoBlockTexts("$item\n\n$table\n\nDone.")
        val coalesced = coalesceMarkdownFragments(fragments)
        assertEquals(1, coalesced.size)
        assertEquals(
            listOf("OrderedList[Paragraph,Table]", "Paragraph"),
            debugParseBlockShapes(coalesced.single()),
        )
    }

    @Test
    fun tableUnderBulletItemWithoutBlankLine() {
        assertEquals(
            listOf("UnorderedList[Paragraph,Table,Paragraph]"),
            debugParseBlockShapes("- Config:\n  | a | b |\n  |---|---|\n  | 1 | 2 |\n  after the table"),
        )
    }

    @Test
    fun pipeInPlainItemStaysInline() {
        assertEquals(
            listOf("UnorderedList"),
            debugParseBlockShapes("- run `a | b`\n- second"),
        )
    }
}
