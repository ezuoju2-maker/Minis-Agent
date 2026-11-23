package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Review0923StreamOverlaySubsetTest {

    private fun user(id: String) = ChatMessage(id = id, role = "user", content = "q$id")
    private fun asst(id: String, text: String = "a$id") = ChatMessage(
        id = id, role = "assistant", content = text,
        toolBlocks = listOf(AssistantBlock(id = "$id-t", kind = "text", content = text)),
    )

    private val history = (1..50).flatMap { listOf(user("u$it"), asst("a$it")) }

    private val delta = StreamingDelta(
        content = "live text",
        toolBlocks = listOf(
            AssistantBlock(id = "a50-t", kind = "text", content = "live text"),
            AssistantBlock(id = "a50-tool", kind = "tool_use", toolStatus = ToolBlockStatus.RUNNING),
        ),
        isAwaitingModelResponse = false,
    )

    @Test
    fun `empty streaming map gives an empty subset, never the full list`() {
        assertTrue(streamingOverlaySubset(history, emptyMap()).isEmpty())
        assertSame(history, mergeStreamingOverlay(history, emptyMap()))
    }

    @Test
    fun `subset entries equal the full merge's entries field for field`() {
        val streaming = mapOf("a50" to delta, "a10" to delta.copy(content = "older"))
        val subset = streamingOverlaySubset(history, streaming)
        val full = mergeStreamingOverlay(history, streaming)
        val fromFull = full.filter { it.id in streaming }
        assertEquals("same messages, same order", fromFull, subset)
        assertEquals(listOf("a10", "a50"), subset.map { it.id })
        assertTrue(subset.all { it.isStreaming })
    }

    @Test
    fun `last assistant from the subset equals the full merge when the tail streams`() {
        val streaming = mapOf("a50" to delta)
        val subsetLast = streamingOverlaySubset(history, streaming).lastOrNull { it.role == "assistant" }
        val fullLast = mergeStreamingOverlay(history, streaming).lastOrNull { it.role == "assistant" }
        assertEquals(fullLast, subsetLast)
        assertEquals(ToolBlockStatus.RUNNING, subsetLast!!.toolBlocks.last().toolStatus)
    }

    @Test
    fun `a delta whose message is not in the list yields nothing, so callers must fall back`() {
        val subset = streamingOverlaySubset(history, mapOf("not-yet-inserted" to delta))
        assertTrue(subset.isEmpty())
        val chosen = subset.lastOrNull { it.role == "assistant" }
            ?: history.lastOrNull { it.role == "assistant" }
        assertEquals("a50", chosen!!.id)
    }
}
