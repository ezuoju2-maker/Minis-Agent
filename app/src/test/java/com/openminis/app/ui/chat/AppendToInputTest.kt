package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppendToInputTest {

    private fun join(draft: String, snippet: String) =
        ChatViewModel.joinDraftWithSnippet(draft, snippet)


    @Test
    fun `trailing newline in draft survives the append`() {
        assertEquals(
            "First paragraph.\nquoted ",
            join("First paragraph.\n", "quoted"),
        )
    }

    @Test
    fun `blank line paragraph break survives the append`() {
        assertEquals(
            "Intro.\n\nquoted ",
            join("Intro.\n\n", "quoted"),
        )
    }

    @Test
    fun `draft is preserved byte for byte including interior whitespace`() {
        val draft = "  leading spaces and\ttabs\nkept  "
        val result = join(draft, "snippet")!!
        assertEquals(draft, result.take(draft.length))
    }


    @Test
    fun `draft ending in a space does not get a second separator`() {
        assertEquals("hello world ", join("hello ", "world"))
    }

    @Test
    fun `draft not ending in whitespace gets exactly one separator space`() {
        assertEquals("hello world ", join("hello", "world"))
    }

    @Test
    fun `newline-terminated draft gets no separator space`() {
        assertEquals("hello\nworld ", join("hello\n", "world"))
    }


    @Test
    fun `empty draft yields just the snippet and a trailing space`() {
        assertEquals("snippet ", join("", "snippet"))
    }

    @Test
    fun `whitespace-only draft is treated as empty, not prefixed`() {
        assertEquals("snippet ", join("   \n  ", "snippet"))
    }

    @Test
    fun `blank snippet is rejected and leaves the draft alone`() {
        assertNull(join("my draft", "   "))
        assertNull(join("my draft", ""))
        assertNull(join("my draft", "\n\t "))
    }


    @Test
    fun `snippet is trimmed on both ends before joining`() {
        assertEquals("draft snippet ", join("draft", "  snippet  "))
    }

    @Test
    fun `interior whitespace inside the snippet is left intact`() {
        assertEquals("draft line1\nline2 ", join("draft", "  line1\nline2  "))
    }


    @Test
    fun `successive appends do not accumulate separators`() {
        var draft = ""
        listOf("alpha", "beta", "gamma").forEach { draft = join(draft, it)!! }
        assertEquals("alpha beta gamma ", draft)
    }


    private val block = "```kotlin\nval x = 1\n```"

    @Test
    fun `a fenced block goes on its own line after a draft`() {
        assertEquals("look at\n$block\n", join("look at", block))
    }

    @Test
    fun `a draft ending in a newline gets no extra blank line before the fence`() {
        assertEquals("look at\n$block\n", join("look at\n", block))
    }

    @Test
    fun `a fence into an empty draft ends with a newline, not a space`() {
        assertEquals("$block\n", join("", block))
    }

    @Test
    fun `prose that ends in a closing fence gets a newline after it`() {
        val snippet = "See this:\n$block"
        assertEquals("draft $snippet\n", join("draft", snippet))
    }

    @Test
    fun `a snippet that opens with a fence but ends in prose keeps a space after`() {
        val snippet = "$block\nthen more"
        assertEquals("draft\n$snippet ", join("draft", snippet))
    }

    @Test
    fun `tilde fences count too`() {
        val tilde = "~~~\ncode\n~~~"
        assertEquals("draft\n$tilde\n", join("draft", tilde))
    }

    @Test
    fun `typing after an appended block starts on a fresh line`() {
        val joined = join("", block)!! + "next"
        assertEquals("```", joined.lines().dropLast(1).last())
        assertEquals("next", joined.lines().last())
    }
}
