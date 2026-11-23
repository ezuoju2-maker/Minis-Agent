package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class Review0923IncrementalParseEquivalenceTest {

    private fun chunked(full: String, step: Int): List<String> =
        generateSequence(step) { it + step }.takeWhile { it < full.length }
            .map { full.substring(0, it) }.toList() + listOf(full)

    private fun assertEquivalent(full: String, step: Int = 7) {
        assertEquals(
            "incremental replay must produce the same blocks as one full parse",
            debugParseBlockRaws(full),
            debugParseIncrementalRaws(chunked(full, step)),
        )
    }


    @Test
    fun `loose bullet list streams as one list, like the full parse`() {
        assertEquivalent("Intro:\n\n- alpha\n\n- beta\n\n- gamma\n")
    }

    @Test
    fun `loose ordered list with an indented continuation streams like the full parse`() {
        assertEquivalent("1. **Install**\n\n   run the installer\n\n2. **Configure**\n\n   edit the file\n")
    }

    @Test
    fun `bracket display math spanning a blank line streams like the full parse`() {
        assertEquivalent("Before\n\n\\[\na = 1\n\nb = 2\n\\]\n\nAfter\n")
    }


    @Test
    fun `blockquotes separated by a blank line`() {
        assertEquivalent("> first quote\n\n> second quote\n\ntail\n")
    }

    @Test
    fun `table followed by a paragraph`() {
        assertEquivalent("| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |\n\nAfter the table.\n")
    }

    @Test
    fun `a dollar-dollar line inside a code fence does not corrupt later blocks`() {
        val d = "$" + "$"
        assertEquivalent("```sh\necho $d\n$d\n```\n\npara one\n\npara two\n")
    }

    @Test
    fun `an unclosed dollar-dollar line is plain text in both parses`() {
        val d = "$" + "$"
        assertEquivalent("cost is $d\n\n" + d + " not math\n\nnext para\n\nlast\n")
    }
}
