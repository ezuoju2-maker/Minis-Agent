package com.openminis.app.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PastePlaceholderTest {

    private val longCjk = "中".repeat(1201)
    private val longEnglish = (1..1001).joinToString(" ") { "word" }
    private val shortText = "hello world"

    private fun tfv(s: String) = TextFieldValue(s, TextRange(s.length))

    private class RecordingStash {
        val stashed = mutableListOf<String>()
        var next = 1
        fun stash(text: String): String {
            stashed += text
            return PastedText.placeholderFor(next++)
        }
    }


    @Test
    fun `cjk over 1200 chars is long, at or under is not`() {
        assertTrue(isLongPastedText("中".repeat(1201)))
        assertFalse(isLongPastedText("中".repeat(1200)))
    }

    @Test
    fun `english over 1000 words is long, at or under is not`() {
        assertTrue(isLongPastedText((1..1001).joinToString(" ") { "word" }))
        assertFalse(isLongPastedText((1..1000).joinToString(" ") { "word" }))
    }

    @Test
    fun `long english under 1200 chars still folds via the word rule`() {
        assertTrue(isLongPastedText((1..1001).joinToString(" ") { "a" }))
    }

    @Test
    fun `english prose over 1200 chars but under 1000 words does NOT fold`() {
        val prose = (1..300).joinToString(" ") { "internationalization" }
        assertTrue("setup: should exceed 1200 chars", prose.length > 1200)
        assertFalse(isLongPastedText(prose))
    }


    @Test
    fun `typing one character never folds`() {
        val s = RecordingStash()
        val out = foldLongPasteIfNeeded(tfv("hell"), tfv("hello"), s::stash)
        assertEquals("hello", out.text)
        assertTrue(s.stashed.isEmpty())
    }

    @Test
    fun `deleting text never folds`() {
        val s = RecordingStash()
        val out = foldLongPasteIfNeeded(tfv(longCjk), tfv("short"), s::stash)
        assertEquals("short", out.text)
        assertTrue(s.stashed.isEmpty())
    }

    @Test
    fun `paste into empty field folds to a marker`() {
        val s = RecordingStash()
        val out = foldLongPasteIfNeeded(tfv(""), tfv(longCjk), s::stash)
        assertEquals("[Pasted#1]", out.text)
        assertEquals(listOf(longCjk), s.stashed)
        assertEquals(out.text.length, out.selection.start)
    }

    @Test
    fun `paste in the MIDDLE keeps surrounding text intact`() {
        val s = RecordingStash()
        val out = foldLongPasteIfNeeded(
            tfv("before after"),
            tfv("before $longCjk after"),
            s::stash,
        )
        assertEquals("before [Pasted#1]after", out.text)
        assertTrue("inserted run carries the trailing space", s.stashed.single().endsWith(" "))
    }

    @Test
    fun `paste over a selection folds the replacement`() {
        val s = RecordingStash()
        val out = foldLongPasteIfNeeded(
            tfv("keep OLD keep"),
            tfv("keep $longCjk keep"),
            s::stash,
        )
        assertEquals("keep [Pasted#1] keep", out.text)
        assertEquals(listOf(longCjk), s.stashed)
    }

    @Test
    fun `two consecutive pastes get independent numbers`() {
        val s = RecordingStash()
        val first = foldLongPasteIfNeeded(tfv(""), tfv(longCjk), s::stash)
        assertEquals("[Pasted#1]", first.text)
        val second = foldLongPasteIfNeeded(
            first,
            tfv(first.text + longEnglish),
            s::stash,
        )
        assertEquals("[Pasted#1][Pasted#2]", second.text)
        assertEquals(2, s.stashed.size)
    }


    private fun buf(vararg pairs: Pair<Int, String>) =
        pairs.map { PastedText(it.first, it.second) }

    @Test
    fun `placeholder expands to the buffered text and is consumed`() {
        val (out, used) = expandPastePlaceholders("a [Pasted#1] b", buf(1 to "BODY"))
        assertEquals("a BODY b", out)
        assertEquals(setOf(1), used)
    }

    @Test
    fun `unknown id is left verbatim and consumes nothing`() {
        val (out, used) = expandPastePlaceholders("see [Pasted#99]", buf(1 to "BODY"))
        assertEquals("see [Pasted#99]", out)
        assertTrue(used.isEmpty())
    }

    @Test
    fun `damaged markers are left verbatim`() {
        for (broken in listOf("[Pasted#1", "[Pasted#]", "[Pasted# 1]", "[Pasted#-1]", "Pasted#1]")) {
            val (out, used) = expandPastePlaceholders(broken, buf(1 to "BODY"))
            assertEquals("unchanged: $broken", broken, out)
            assertTrue(used.isEmpty())
        }
    }

    @Test
    fun `expansion is single-pass — pasted content shaped like a marker is NOT re-expanded`() {
        val (out, used) = expandPastePlaceholders(
            "x [Pasted#1] y",
            buf(1 to "body mentioning [Pasted#2] inline", 2 to "SHOULD-NOT-APPEAR"),
        )
        assertEquals("x body mentioning [Pasted#2] inline y", out)
        assertEquals(setOf(1), used)
        assertFalse(out.contains("SHOULD-NOT-APPEAR"))
    }

    @Test
    fun `a hand-typed lowercase marker still expands`() {
        val (out, used) = expandPastePlaceholders("a [pasted#1] b", buf(1 to "BODY"))
        assertEquals("a BODY b", out)
        assertEquals(setOf(1), used)
    }

    @Test
    fun `the marker we generate is capitalized`() {
        assertEquals("[Pasted#7]", PastedText.placeholderFor(7))
        assertEquals("[Pasted#7]", PastedText(7, "x").placeholder)
    }

    @Test
    fun `multiple placeholders expand in one pass, each consumed once`() {
        val (out, used) = expandPastePlaceholders(
            "[Pasted#1] mid [Pasted#2]",
            buf(1 to "A", 2 to "B"),
        )
        assertEquals("A mid B", out)
        assertEquals(setOf(1, 2), used)
    }

    @Test
    fun `the same placeholder twice expands both occurrences`() {
        val (out, used) = expandPastePlaceholders("[Pasted#1]/[Pasted#1]", buf(1 to "A"))
        assertEquals("A/A", out)
        assertEquals(setOf(1), used)
    }

    @Test
    fun `text with no placeholders is returned untouched`() {
        val (out, used) = expandPastePlaceholders("plain message", buf(1 to "A"))
        assertEquals("plain message", out)
        assertTrue(used.isEmpty())
    }

    @Test
    fun `empty buffer leaves markers verbatim`() {
        val (out, used) = expandPastePlaceholders("[Pasted#1]", emptyList())
        assertEquals("[Pasted#1]", out)
        assertTrue(used.isEmpty())
    }

    @Test
    fun `marker at the very start and end survives trimming`() {
        val (out, _) = expandPastePlaceholders("[Pasted#1]", buf(1 to "  BODY  "))
        assertEquals("  BODY  ", out)
        assertEquals("BODY", out.trim())
    }


    @Test
    fun `removing a marker does not disturb a longer id sharing its prefix`() {
        val text = "a [Pasted#2] b [Pasted#20] c"
        val marker = PastedText.placeholderFor(2)
        val at = text.indexOf(marker)
        val stripped = text.substring(0, at) + text.substring(at + marker.length)
        assertEquals("a  b [Pasted#20] c", stripped)
        assertTrue(stripped.contains("[Pasted#20]"))
    }

    @Test
    fun `removing a marker that is not present leaves the text alone`() {
        val text = "no markers here"
        assertEquals(-1, text.indexOf(PastedText.placeholderFor(1)))
    }


    @Test
    fun `preview is the first non-blank line, clipped`() {
        val p = PastedText(1, "\n\n  first line  \nsecond line")
        assertEquals("first line", p.preview)
    }

    @Test
    fun `preview of a very long single line is clipped to 60 chars`() {
        val p = PastedText(1, "x".repeat(500))
        assertEquals(60, p.preview.length)
    }

    @Test
    fun `preview of all-blank text is empty, not a crash`() {
        assertEquals("", PastedText(1, "\n\n   \n").preview)
    }
}
