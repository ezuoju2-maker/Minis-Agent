package com.openminis.app.tools.patch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HunkArgsTest {

    @Test
    fun `toggle adds and removes`() {
        assertEquals(setOf(0), HunkArgs.toggle(emptySet(), 0))
        assertEquals(setOf(0, 2), HunkArgs.toggle(setOf(0), 2))
        assertEquals(setOf(2), HunkArgs.toggle(setOf(0, 2), 0))
    }

    @Test
    fun `json is sorted and compact`() {
        assertEquals("[]", HunkArgs.toJson(emptySet()))
        assertEquals("[1]", HunkArgs.toJson(setOf(1)))
        assertEquals("[0,2,5]", HunkArgs.toJson(setOf(5, 0, 2)))
    }

    @Test
    fun `describe covers empty partial and full`() {
        assertEquals("已选 1/3 段", HunkArgs.describe(setOf(1), 3))
        assertEquals("全选（3 段）", HunkArgs.describe(setOf(0, 1, 2), 3))
        assertEquals("未选择任何改动（3 段）", HunkArgs.describe(emptySet(), 3))
        assertEquals("没有可选的改动", HunkArgs.describe(emptySet(), 0))
    }

    @Test
    fun `select all and clear`() {
        assertEquals(setOf(0, 1, 2), HunkArgs.selectAll(listOf(0, 1, 2)))
        assertTrue(HunkArgs.clear().isEmpty())
        assertTrue(HunkArgs.isNoop(emptySet()))
        assertFalse(HunkArgs.isNoop(setOf(0)))
    }
}
