package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParallelToolResultImageOrderTest {

    private fun result(id: String, withImage: Boolean) = AgentContentPart.ToolResult(
        id = id,
        name = "read_image",
        content = "ok",
        imageData = if (withImage) byteArrayOf(1, 2, 3) else null,
        imageMimeType = if (withImage) "image/png" else null,
    )

    private fun emit(results: List<AgentContentPart.ToolResult>): List<String> {
        val out = mutableListOf<String>()
        val carriers = mutableListOf<String>()
        for (tr in results) {
            out.add("tool:${tr.id}")
            if (tr.imageData != null) carriers.add("user-image:${tr.id}")
        }
        out.addAll(carriers)
        return out
    }

    private fun emitInterleaved(results: List<AgentContentPart.ToolResult>): List<String> {
        val out = mutableListOf<String>()
        for (tr in results) {
            out.add("tool:${tr.id}")
            if (tr.imageData != null) out.add("user-image:${tr.id}")
        }
        return out
    }

    private fun toolRunIsContiguous(seq: List<String>): Boolean {
        val first = seq.indexOfFirst { it.startsWith("tool:") }
        val last = seq.indexOfLast { it.startsWith("tool:") }
        if (first < 0) return true
        return (first..last).all { seq[it].startsWith("tool:") }
    }

    @Test
    fun `two parallel image results keep the tool run contiguous`() {
        val seq = emit(listOf(result("call_01", true), result("call_02", true)))
        assertEquals(
            listOf("tool:call_01", "tool:call_02", "user-image:call_01", "user-image:call_02"),
            seq,
        )
        assertTrue("both tool replies must be adjacent", toolRunIsContiguous(seq))
    }

    @Test
    fun `the old interleaved order split the run - this is the reported 400`() {
        val seq = emitInterleaved(listOf(result("call_01", true), result("call_02", true)))
        assertEquals(
            listOf("tool:call_01", "user-image:call_01", "tool:call_02", "user-image:call_02"),
            seq,
        )
        assertTrue(
            "the carrier between the two tool replies is what orphaned call_02",
            !toolRunIsContiguous(seq),
        )
    }

    @Test
    fun `a single image result was never split - why the bug needed parallelism`() {
        val one = listOf(result("call_01", true))
        assertEquals(emit(one), emitInterleaved(one))
        assertTrue(toolRunIsContiguous(emit(one)))
    }

    @Test
    fun `results without images are unaffected`() {
        val seq = emit(listOf(result("call_01", false), result("call_02", false)))
        assertEquals(listOf("tool:call_01", "tool:call_02"), seq)
        assertTrue(toolRunIsContiguous(seq))
    }

    @Test
    fun `a mixed batch still emits every tool reply before any carrier`() {
        val seq = emit(
            listOf(result("call_01", true), result("call_02", false), result("call_03", true)),
        )
        assertEquals(
            listOf(
                "tool:call_01", "tool:call_02", "tool:call_03",
                "user-image:call_01", "user-image:call_03",
            ),
            seq,
        )
        assertTrue(toolRunIsContiguous(seq))
    }

    @Test
    fun `carriers stay in call order so captions match their images`() {
        val seq = emit(listOf(result("call_A", true), result("call_B", true), result("call_C", true)))
        assertEquals(
            listOf("user-image:call_A", "user-image:call_B", "user-image:call_C"),
            seq.filter { it.startsWith("user-image:") },
        )
    }
}
