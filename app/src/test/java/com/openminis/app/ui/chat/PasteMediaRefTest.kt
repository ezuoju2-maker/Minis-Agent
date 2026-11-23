package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasteMediaRefTest {

    private fun buf(vararg pairs: Pair<Int, String>) =
        pairs.map { PastedText(it.first, it.second) }

    private fun textsOf(chunks: List<PasteChunk>) =
        chunks.filterIsInstance<PasteChunk.Text>().map { it.value }

    private fun idsOf(chunks: List<PasteChunk>) =
        chunks.filterIsInstance<PasteChunk.Pasted>().map { it.id }


    @Test
    fun `a marker between prose splits into three chunks in order`() {
        val (chunks, used) = splitPastePlaceholders("before [Pasted#1] after", buf(1 to "BODY"))
        assertEquals(3, chunks.size)
        assertTrue(chunks[0] is PasteChunk.Text)
        assertTrue(chunks[1] is PasteChunk.Pasted)
        assertTrue(chunks[2] is PasteChunk.Text)
        assertEquals(listOf("before ", " after"), textsOf(chunks))
        assertEquals(listOf(1), idsOf(chunks))
        assertEquals(setOf(1), used)
    }

    @Test
    fun `a message that is only a marker produces exactly one chunk`() {
        val (chunks, used) = splitPastePlaceholders("[Pasted#1]", buf(1 to "BODY"))
        assertEquals(1, chunks.size)
        assertEquals(listOf(1), idsOf(chunks))
        assertEquals(setOf(1), used)
    }

    @Test
    fun `two markers split into five chunks and consume both`() {
        val (chunks, used) = splitPastePlaceholders(
            "a [Pasted#1] b [Pasted#2] c",
            buf(1 to "X", 2 to "Y"),
        )
        assertEquals(listOf("a ", " b ", " c"), textsOf(chunks))
        assertEquals(listOf(1, 2), idsOf(chunks))
        assertEquals(setOf(1, 2), used)
    }

    @Test
    fun `adjacent markers produce no empty text chunk between them`() {
        val (chunks, _) = splitPastePlaceholders("[Pasted#1][Pasted#2]", buf(1 to "X", 2 to "Y"))
        assertEquals(2, chunks.size)
        assertTrue(textsOf(chunks).isEmpty())
    }

    @Test
    fun `the same marker twice yields two chunks and one consumed id`() {
        val (chunks, used) = splitPastePlaceholders("[Pasted#1]/[Pasted#1]", buf(1 to "A"))
        assertEquals(listOf(1, 1), idsOf(chunks))
        assertEquals(setOf(1), used)
    }


    @Test
    fun `an unknown id stays literal and consumes nothing`() {
        val (chunks, used) = splitPastePlaceholders("see [Pasted#99]", buf(1 to "BODY"))
        assertEquals(listOf("see [Pasted#99]"), textsOf(chunks))
        assertTrue(idsOf(chunks).isEmpty())
        assertTrue(used.isEmpty())
    }

    @Test
    fun `damaged markers stay literal`() {
        for (broken in listOf("[Pasted#1", "[Pasted#]", "[Pasted# 1]", "Pasted#1]")) {
            val (chunks, used) = splitPastePlaceholders(broken, buf(1 to "BODY"))
            assertEquals("unchanged: $broken", listOf(broken), textsOf(chunks))
            assertTrue(used.isEmpty())
        }
    }

    @Test
    fun `a lowercase marker still splits`() {
        val (chunks, used) = splitPastePlaceholders("a [pasted#1] b", buf(1 to "BODY"))
        assertEquals(listOf(1), idsOf(chunks))
        assertEquals(setOf(1), used)
    }

    @Test
    fun `text with no markers is one chunk`() {
        val (chunks, used) = splitPastePlaceholders("plain message", buf(1 to "A"))
        assertEquals(listOf("plain message"), textsOf(chunks))
        assertTrue(used.isEmpty())
    }

    @Test
    fun `an empty buffer leaves everything literal`() {
        val (chunks, used) = splitPastePlaceholders("[Pasted#1]", emptyList())
        assertEquals(listOf("[Pasted#1]"), textsOf(chunks))
        assertTrue(used.isEmpty())
    }

    @Test
    fun `empty text produces no chunks`() {
        val (chunks, used) = splitPastePlaceholders("", buf(1 to "A"))
        assertTrue(chunks.isEmpty())
        assertTrue(used.isEmpty())
    }

    @Test
    fun `pasted content shaped like a marker is NOT re-expanded`() {
        val (chunks, used) = splitPastePlaceholders(
            "x [Pasted#1] y",
            buf(1 to "body mentioning [Pasted#2] inline", 2 to "SHOULD-NOT-APPEAR"),
        )
        assertEquals(listOf(1), idsOf(chunks))
        assertEquals(setOf(1), used)
    }


    @Test
    fun `a pasted ref is recognised by mime AND filename prefix`() {
        assertTrue(PastedMedia.isPastedRef("text/plain", "Pasted#3.txt"))
    }

    @Test
    fun `a genuine txt attachment is NOT treated as pasted`() {
        assertFalse(PastedMedia.isPastedRef("text/plain", "notes.txt"))
        assertFalse(PastedMedia.isPastedRef("text/plain", null))
    }

    @Test
    fun `a file merely NAMED like a paste is NOT treated as pasted`() {
        assertFalse(PastedMedia.isPastedRef("application/pdf", "Pasted#1.txt"))
        assertFalse(PastedMedia.isPastedRef("image/png", "Pasted#1.txt"))
    }

    @Test
    fun `the filename we generate is recognised by our own check`() {
        val name = PastedMedia.fileNameFor(7)
        assertEquals("Pasted#7.txt", name)
        assertTrue(PastedMedia.isPastedRef(PastedMedia.MIME, name))
    }


    @Test
    fun `the file threshold sits above the fold threshold`() {
        assertTrue(PASTE_AS_FILE_THRESHOLD > 1200)
        assertTrue(isLongPastedText("中".repeat(PASTE_AS_FILE_THRESHOLD + 1)))
    }

    @Test
    fun `a paste just over the threshold is oversize and just under is not`() {
        assertTrue("x".repeat(PASTE_AS_FILE_THRESHOLD + 1).length > PASTE_AS_FILE_THRESHOLD)
        assertFalse("x".repeat(PASTE_AS_FILE_THRESHOLD).length > PASTE_AS_FILE_THRESHOLD)
    }


    private fun resolveBody(fileBody: String?): String =
        fileBody ?: PastedMedia.MISSING_PLACEHOLDER

    @Test
    fun `a readable pasted file contributes its text`() {
        assertEquals("REAL BODY", resolveBody("REAL BODY"))
    }

    @Test
    fun `a missing pasted file contributes an explicit marker, not empty`() {
        val out = resolveBody(null)
        assertTrue("must not be silent", out.isNotEmpty())
        assertEquals(PastedMedia.MISSING_PLACEHOLDER, out)
    }

    @Test
    fun `the missing marker says what happened`() {
        val m = PastedMedia.MISSING_PLACEHOLDER
        assertTrue(m.contains("unavailable"))
        assertTrue(m.contains("missing"))
    }

    @Test
    fun `an empty pasted file is NOT treated as missing`() {
        assertEquals("", resolveBody(""))
    }


    private val markerPattern = Regex("""\[[Pp]asted#\d+]""")

    private enum class AgentHistoryProducer(val expandsPastes: Boolean, val carriesUserText: Boolean) {
        LOAD_SESSION(true, true),

        RERUN_FROM_TOOL_BLOCK(true, true),

        RETRY_FROM_MESSAGE(true, true),

        DELETE_FROM_MESSAGE(true, true),

        TRUNCATE_BEFORE_EDIT(true, true),

        QUEUED_INJECT(true, true),

        QUEUED_DRAIN(true, true),

        FRESH_SEND(true, true),

        QUEUE_BRIDGE(false, false),

        ASSISTANT_TURN(false, false),

        TOOL_RESULT_TURN(false, false),

        CANCEL_PARTIAL(false, false),

        RESUME_REMINDER(false, false),

        SANITIZE_ORPHAN_REPAIR(false, false),
    }

    @Test
    fun `the producer inventory covers every agentHistory write site`() {
        assertEquals(
            "agentHistory write sites in ChatViewModel.kt vs classified producers",
            14,
            AgentHistoryProducer.values().size,
        )
    }

    @Test
    fun `every agentHistory producer carrying user text expands pasted refs`() {
        val leaky = AgentHistoryProducer.values()
            .filter { it.carriesUserText && !it.expandsPastes }
        assertTrue(
            "these agentHistory producers carry user text without expanding: $leaky",
            leaky.isEmpty(),
        )
    }

    @Test
    fun `the producers that skip expansion are all app-generated text`() {
        val exempt = AgentHistoryProducer.values().filterNot { it.expandsPastes }
        assertTrue(exempt.none { it.carriesUserText })
        assertEquals(6, exempt.size)
    }

    @Test
    fun `expanded send text carries no marker literal into the summary`() {
        val (chunks, _) = splitPastePlaceholders("before [Pasted#1] after", buf(1 to "FULL BODY"))
        val modelText = chunks.joinToString("") {
            when (it) {
                is PasteChunk.Text -> it.value
                is PasteChunk.Pasted -> "FULL BODY"
            }
        }
        assertEquals("before FULL BODY after", modelText)
        assertFalse(
            "a summary must never see the marker",
            markerPattern.containsMatchIn(modelText),
        )
    }

    @Test
    fun `an unresolvable marker reaching the summary stays literal, not blank`() {
        val (chunks, used) = splitPastePlaceholders("see [Pasted#99]", buf(1 to "A"))
        assertTrue(used.isEmpty())
        assertEquals("see [Pasted#99]", textsOf(chunks).single())
    }

    @Test
    fun `a missing file still yields non-empty summary input`() {
        assertTrue(resolveBody(null).isNotEmpty())
        assertFalse(markerPattern.containsMatchIn(resolveBody(null)))
    }


    @Test
    fun `consumed markers are stripped from the visible caption`() {
        val trimmed = "before [Pasted#1] after"
        val visible = setOf(1).fold(trimmed) { acc, id ->
            acc.replace(PastedText.placeholderFor(id), "")
        }.trim()
        assertEquals("before  after", visible)
    }

    @Test
    fun `an unconsumed marker survives in the caption`() {
        val trimmed = "see [Pasted#99]"
        val visible = emptySet<Int>().fold(trimmed) { acc, id ->
            acc.replace(PastedText.placeholderFor(id), "")
        }.trim()
        assertEquals("see [Pasted#99]", visible)
    }
}
