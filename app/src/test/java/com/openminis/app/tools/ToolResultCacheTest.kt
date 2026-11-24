package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultCacheTest {

    @Test
    fun `title does not change the identity of a read`() {
        val a = ToolResultCache.keyFor("file_read", "{\"path\":\"/a.txt\",\"tool_title\":\"read A\"}")
        val b = ToolResultCache.keyFor("file_read", "{\"tool_title\":\"another title\",\"path\":\"/a.txt\"}")
        assertEquals(a, b)
        assertTrue(a!!.startsWith("file_read|"))
    }

    @Test
    fun `different path means different key`() {
        val a = ToolResultCache.keyFor("file_read", "{\"path\":\"/a.txt\"}")
        val b = ToolResultCache.keyFor("file_read", "{\"path\":\"/b.txt\"}")
        assertTrue(a != b)
    }

    @Test
    fun `writers are never cached`() {
        assertNull(ToolResultCache.keyFor("file_write", "{\"path\":\"/a.txt\"}"))
        assertNull(ToolResultCache.keyFor("shell_execute", "{\"command\":\"ls\"}"))
        assertTrue(ToolResultCache.isWriter("apply_patch"))
        assertTrue(ToolResultCache.isReadOnly("repo_map"))
    }

    @Test
    fun `entry expires after the ttl`() {
        ToolResultCache.invalidateAll()
        val key = ToolResultCache.keyFor("file_read", "{\"path\":\"/ttl.txt\"}")!!
        ToolResultCache.put(key, "body", now = 1000L)
        assertEquals("body", ToolResultCache.get(key, now = 2000L, ttlMs = 5000L))
        assertNull(ToolResultCache.get(key, now = 9000L, ttlMs = 5000L))
    }

    @Test
    fun `invalidate drops everything`() {
        ToolResultCache.invalidateAll()
        val key = ToolResultCache.keyFor("file_read", "{\"path\":\"/x.txt\"}")!!
        ToolResultCache.put(key, "body")
        ToolResultCache.invalidateAll()
        assertNull(ToolResultCache.get(key))
        assertEquals(0, ToolResultCache.size())
    }

    @Test
    fun `entry count stays bounded`() {
        ToolResultCache.invalidateAll()
        for (i in 0 until 40) {
            val key = ToolResultCache.keyFor("file_read", "{\"path\":\"/f" + i + ".txt\"}")!!
            ToolResultCache.put(key, "body " + i)
        }
        assertTrue(ToolResultCache.size() <= 16)
    }

    @Test
    fun `oversized payloads are not stored`() {
        ToolResultCache.invalidateAll()
        val key = ToolResultCache.keyFor("file_read", "{\"path\":\"/big.txt\"}")!!
        ToolResultCache.put(key, "x".repeat(300_000))
        assertNull(ToolResultCache.get(key))
    }
}
