package com.openminis.app.memory.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridMemoryIndexTest {

    private fun index(vararg docs: Pair<String, String>): HybridMemoryIndex {
        val idx = HybridMemoryIndex()
        for ((id, text) in docs) idx.upsert(id, text)
        return idx
    }

    @Test
    fun `chinese bigram lookup finds the right fact`() {
        val idx = index(
            "a" to "用户偏好使用深色主题，界面字号调大",
            "b" to "项目部署在阿里云服务器上，使用 Docker",
            "c" to "每周三下午三点开技术评审会",
        )
        val hits = idx.search("深色主题")
        assertTrue(hits.isNotEmpty())
        assertEquals("a", hits.first().id)
    }

    @Test
    fun `english tokens match with stemming-free substring tokens`() {
        val idx = index(
            "a" to "prefers dark mode in the editor",
            "b" to "database backfills run nightly",
        )
        assertEquals("a", idx.search("dark mode").first().id)
        assertEquals("b", idx.search("nightly backfills").first().id)
    }

    @Test
    fun `partial overlap still ranks the closest doc first`() {
        val idx = index(
            "a" to "语音输入使用讯飞接口，超时重试三次",
            "b" to "语音合成使用豆包接口",
            "c" to "日历事件写入系统日历",
        )
        val hits = idx.search("语音输入接口重试")
        assertEquals("a", hits.first().id)
        assertTrue(hits.size >= 2)
    }

    @Test
    fun `removed docs never come back`() {
        val idx = index("a" to "临时会议记录", "b" to "长期偏好设置")
        idx.remove("a")
        val hits = idx.search("临时会议")
        assertTrue(hits.none { it.id == "a" })
    }

    @Test
    fun `upsert replaces content instead of duplicating`() {
        val idx = index("a" to "旧的内容")
        idx.upsert("a", "新的内容关于向量检索")
        assertEquals(1, idx.size)
        assertEquals("a", idx.search("向量检索").first().id)
        assertTrue(idx.search("旧的内容").isEmpty())
    }

    @Test
    fun `limit and ordering are deterministic`() {
        val idx = index(
            "a" to "alpha beta gamma",
            "b" to "alpha beta",
            "c" to "alpha",
            "d" to "delta",
        )
        val hits = idx.search("alpha beta", limit = 2)
        assertEquals(2, hits.size)
        assertEquals(hits.map { it.id }, idx.search("alpha beta", limit = 2).map { it.id })
        assertTrue(hits.all { it.score > 0.0 })
    }

    @Test
    fun `empty query and empty index are safe`() {
        val idx = HybridMemoryIndex()
        assertTrue(idx.search("anything").isEmpty())
        idx.upsert("a" to "content here")
        assertTrue(idx.search("   ").isEmpty())
    }

    @Test
    fun `rrf fuses two ranked lists without losing either`() {
        val fused = HybridMemoryIndex().rrfFuse(
            listOf(listOf("a", "b", "c"), listOf("c", "d")),
            limit = 4,
        )
        assertEquals(setOf("a", "b", "c", "d"), fused.toSet())
        assertEquals("c", fused.first())
    }

    @Test
    fun `vector component contributes when lexical misses`() {
        val idx = index("a" to "配置项里有 temperature 参数", "b" to "unrelated content about deployment")
        val hit = idx.search("temperature").first()
        assertEquals("a", hit.id)
        assertTrue(hit.vector > 0.0)
    }
}
