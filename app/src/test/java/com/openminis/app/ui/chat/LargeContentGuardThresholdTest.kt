package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeContentGuardThresholdTest {

    private fun prose(totalChars: Int): String {
        val para = "word ".repeat(40).trim()
        val sb = StringBuilder()
        while (sb.length < totalChars) sb.append(para).append("\n\n")
        return sb.toString()
    }

    @Test
    fun `whole message is far above the guard threshold`() {
        val body = prose(200_000)
        assertTrue(
            "precondition: test body must exceed the 32K threshold",
            body.length > LARGE_MESSAGE_THRESHOLD_CHARS,
        )
        assertTrue(
            "the guard WOULD fire if it saw the whole message",
            shouldCollapse(body, isStreaming = false),
        )
    }

    @Test
    fun `no post-split fragment can ever trigger the guard`() {
        val body = prose(200_000)
        val fragments = coalesceMarkdownFragments(splitMarkdownIntoBlockTexts(body))

        assertTrue("splitter must produce fragments", fragments.isNotEmpty())

        val collapsing = fragments.count { shouldCollapse(it, isStreaming = false) }
        val largest = fragments.maxOf { it.length }

        assertEquals(
            "no fragment triggers the guard — largest fragment is $largest chars, " +
                "threshold is $LARGE_MESSAGE_THRESHOLD_CHARS",
            0, collapsing,
        )
        assertTrue(
            "every fragment sits under the coalesce budget (largest=$largest)",
            largest <= 2_000,
        )
    }

    @Test
    fun `a single unsplittable oversized paragraph still reaches the guard`() {
        val oneParagraph = "x".repeat(50_000)
        val fragments = coalesceMarkdownFragments(splitMarkdownIntoBlockTexts(oneParagraph))

        assertEquals("stays as one fragment", 1, fragments.size)
        assertTrue(
            "this shape DOES trip the guard",
            shouldCollapse(fragments.first(), isStreaming = false),
        )
    }


    private fun assistantWithText(content: String, streaming: Boolean) = ChatMessage(
        id = "m1",
        role = "assistant",
        content = content,
        isStreaming = streaming,
        toolBlocks = listOf(AssistantBlock(id = "b1", kind = "text", content = content)),
    )

    @Test
    fun `oversized frozen block is emitted un-split so the guard can fire`() {
        val body = prose(200_000)
        val items = buildFlatChatItems(listOf(assistantWithText(body, streaming = false)))
        val blocks = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()

        assertEquals("exactly one un-split row for the oversized block", 1, blocks.size)
        assertEquals("row carries the whole block", body.length, blocks.first().rawText.length)
        assertTrue(
            "the guard now fires on what the renderer actually receives",
            shouldCollapse(blocks.first().rawText, isStreaming = blocks.first().isStreaming),
        )
    }

    @Test
    fun `a small frozen message is still split normally`() {
        val body = prose(10_000)
        val items = buildFlatChatItems(listOf(assistantWithText(body, streaming = false)))
        val blocks = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()

        assertTrue("small message still produces multiple fragments", blocks.size > 1)
        assertTrue(
            "and none of them trips the guard",
            blocks.none { shouldCollapse(it.rawText, isStreaming = it.isStreaming) },
        )
    }

    @Test
    fun `an oversized streaming block keeps its fragmented rows`() {
        val body = prose(200_000)
        val items = buildFlatChatItems(listOf(assistantWithText(body, streaming = true)))
        val blocks = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()

        assertTrue("streaming keeps many fragments (got ${blocks.size})", blocks.size > 1)
    }

    @Test
    fun `streaming content is never collapsed regardless of size`() {
        val body = prose(200_000)
        assertFalse(shouldCollapse(body, isStreaming = true))
    }
}
