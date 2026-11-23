package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactDividerPlacementTest {

    private data class Row(
        val id: String,
        val role: String,
        val content: String = "",
        val blocks: List<String> = emptyList(),
        val isStreaming: Boolean = false,
    )

    private fun compactedContinuation(
        current: List<Row>,
        sealedId: String,
        accumulatedText: String,
        accumulatedBlocks: List<String>,
        freshId: String,
    ): List<Row> {
        var out = current.map { row ->
            if (row.id != sealedId) row
            else row.copy(
                content = accumulatedText,
                blocks = accumulatedBlocks,
                isStreaming = false,
            )
        }
        val sealed = out.firstOrNull { it.id == sealedId }
        if (sealed != null && sealed.content.isEmpty() && sealed.blocks.isEmpty()) {
            out = out.filterNot { it.id == sealedId }
        }
        out = out + Row(id = "sysinfo_1", role = "system")
        return out + Row(id = freshId, role = "assistant", isStreaming = true)
    }

    @Test
    fun `post-compaction output lands below the divider`() {
        val before = listOf(
            Row("u1", "user"),
            Row("a1", "assistant", content = "older turn"),
            Row("u2", "user"),
            Row("live", "assistant", isStreaming = true),
        )

        val after = compactedContinuation(
            current = before,
            sealedId = "live",
            accumulatedText = "thinking so far",
            accumulatedBlocks = listOf("tool:shell_execute"),
            freshId = "fresh",
        )

        val dividerIdx = after.indexOfFirst { it.role == "system" }
        val sealedIdx = after.indexOfFirst { it.id == "live" }
        val freshIdx = after.indexOfFirst { it.id == "fresh" }

        assertTrue("sealed bubble stays above the divider", sealedIdx < dividerIdx)
        assertTrue("continuation bubble sits below the divider", freshIdx > dividerIdx)
        assertEquals("fresh", after.last().id)
    }

    @Test
    fun `sealed bubble stops streaming and keeps its content`() {
        val before = listOf(Row("live", "assistant", isStreaming = true))

        val after = compactedContinuation(
            current = before,
            sealedId = "live",
            accumulatedText = "partial answer",
            accumulatedBlocks = emptyList(),
            freshId = "fresh",
        )

        val sealed = after.first { it.id == "live" }
        assertFalse("sealed bubble must not still be streaming", sealed.isStreaming)
        assertEquals("partial answer", sealed.content)
        assertTrue("only the fresh bubble streams", after.first { it.id == "fresh" }.isStreaming)
    }

    @Test
    fun `an empty sealed bubble is dropped`() {
        val before = listOf(
            Row("u1", "user"),
            Row("live", "assistant", isStreaming = true),
        )

        val after = compactedContinuation(
            current = before,
            sealedId = "live",
            accumulatedText = "",
            accumulatedBlocks = emptyList(),
            freshId = "fresh",
        )

        assertFalse("empty sealed bubble must be removed", after.any { it.id == "live" })
        assertEquals(listOf("u1", "sysinfo_1", "fresh"), after.map { it.id })
    }

    @Test
    fun `a tool-only sealed bubble is kept`() {
        val before = listOf(Row("live", "assistant", isStreaming = true))

        val after = compactedContinuation(
            current = before,
            sealedId = "live",
            accumulatedText = "",
            accumulatedBlocks = listOf("tool:file_read"),
            freshId = "fresh",
        )

        assertTrue("tool-only bubble must survive", after.any { it.id == "live" })
        val dividerIdx = after.indexOfFirst { it.role == "system" }
        assertTrue(after.indexOfFirst { it.id == "live" } < dividerIdx)
    }

    @Test
    fun `back-to-back compactions keep chronological order`() {
        var rows = listOf(
            Row("u1", "user"),
            Row("live1", "assistant", isStreaming = true),
        )

        rows = compactedContinuation(rows, "live1", "first chunk", emptyList(), "live2")
        rows = compactedContinuation(rows, "live2", "second chunk", emptyList(), "live3")

        assertEquals(
            listOf("u1", "live1", "sysinfo_1", "live2", "sysinfo_1", "live3"),
            rows.map { it.id },
        )
        assertEquals("live3", rows.last().id)
        assertTrue("only the newest bubble streams", rows.first { it.id == "live3" }.isStreaming)
        assertFalse(rows.first { it.id == "live2" }.isStreaming)
    }
}
