package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchToolOrderTest {

    @Test
    fun `apply patch is treated as a writer`() {
        val names = listOf("apply_patch", "file_read")
        assertEquals(listOf(0), BatchToolOrder.writersBefore(1, names))
    }

    @Test
    fun `writers depend on earlier writers in the same batch`() {
        val names = listOf("file_write", "apply_patch")
        assertEquals(listOf(0), BatchToolOrder.writersBefore(1, names))
    }

    @Test
    fun `skill load waits for a file write`() {
        val names = listOf("file_write", "skill_load")
        assertEquals(listOf(0), BatchToolOrder.writersBefore(1, names))
    }

    @Test
    fun `waves split write and read`() {
        val waves = BatchToolOrder.waves(listOf("file_write", "file_read"))
        assertEquals(2, waves.size)
        assertEquals(listOf(0), waves[0])
        assertEquals(listOf(1), waves[1])
    }

    @Test
    fun `unclassified tool has no dependency`() {
        val names = listOf("file_write", "todo_write")
        assertTrue(BatchToolOrder.writersBefore(1, names).isEmpty())
        assertEquals(1, BatchToolOrder.waves(names).size)
    }
}
