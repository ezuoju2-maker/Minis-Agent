package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val n = listOf("file_write", "file_read", "file_read")
        assertEquals(listOf(listOf(0), listOf(1, 2)), BatchToolOrder.waves(n))
    }
}
