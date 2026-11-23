package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [advanced-parallel] Dependency-wave scheduling: independent calls share a
 * wave (run concurrently), readers land strictly after the writers they need.
 */
class BatchWavesTest {

    @Test
    fun `all independent calls are one wave`() {
        val n = listOf("shell_execute", "browser_use", "shell_execute")
        assertEquals(listOf(listOf(0, 1, 2)), BatchToolOrder.waves(n))
        assertFalse(BatchToolOrder.needsOrdering(n))
    }

    @Test
    fun `reader after writer is pushed to the next wave`() {
        val n = listOf("shell_execute", "read_image")
        assertEquals(listOf(listOf(0), listOf(1)), BatchToolOrder.waves(n))
        assertTrue(BatchToolOrder.needsOrdering(n))
    }

    @Test
    fun `multiple writers then reader all precede it`() {
        val n = listOf("file_write", "shell_execute", "file_read")
        // file_read waits for both writers (both wave 0) → wave 1.
        assertEquals(listOf(listOf(0, 1), listOf(2)), BatchToolOrder.waves(n))
    }

    @Test
    fun `reader before writer stays in first wave`() {
        val n = listOf("read_image", "file_write")
        assertEquals(listOf(listOf(0, 1)), BatchToolOrder.waves(n))
        assertFalse(BatchToolOrder.needsOrdering(n))
    }

    @Test
    fun `empty batch yields no waves`() {
        assertTrue(BatchToolOrder.waves(emptyList()).isEmpty())
    }

    @Test
    fun `waves preserve every index exactly once`() {
        val n = listOf("shell_execute", "file_read", "browser_use", "file_edit", "read_image", "file_read")
        val all = BatchToolOrder.waves(n).flatten().sorted()
        assertEquals(n.indices.toList(), all)
    }

    @Test
    fun `nested reader chain produces ordered waves`() {
        // A reader with a writer before it goes to wave 1; that wave-1 reader
        // is not itself a writer, so a following reader only waits for the
        // wave-0 writer, and also lands in wave 1 — no unnecessary serialization.
        val n = listOf("file_write", "file_read", "file_read")
        assertEquals(listOf(listOf(0), listOf(1, 2)), BatchToolOrder.waves(n))
    }
}
