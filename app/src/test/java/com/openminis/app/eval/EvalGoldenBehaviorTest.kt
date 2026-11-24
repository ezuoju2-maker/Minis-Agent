package com.openminis.app.eval

import com.openminis.app.agent.SubAgentBudgetPolicy
import com.openminis.app.memory.retrieval.HybridMemoryIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalGoldenBehaviorTest {

    private val memoryCorpus = listOf(
        "mem-dark-theme" to "用户偏好深色主题，界面字号偏大",
        "mem-deploy" to "服务部署在阿里云，使用 Docker 与 Nginx",
        "mem-review" to "每周三下午三点技术评审会，会议室在三楼",
        "mem-voice" to "语音输入走讯飞接口，失败重试三次后降级",
        "mem-backup" to "备份到 WebDAV，每天凌晨两点自动执行",
    )

    private fun corpusIndex(): HybridMemoryIndex {
        val idx = HybridMemoryIndex()
        for ((id, text) in memoryCorpus) idx.upsert(id, text)
        return idx
    }

    private val memoryCases = listOf(
        "深色主题" to "mem-dark-theme",
        "Docker 部署" to "mem-deploy",
        "技术评审会 三楼" to "mem-review",
        "讯飞 重试" to "mem-voice",
        "WebDAV 备份" to "mem-backup",
    )

    @Test
    fun `memory retrieval top1 is a golden contract`() {
        val idx = corpusIndex()
        for ((query, expected) in memoryCases) {
            assertEquals("query=$query", expected, idx.search(query, limit = 3).first().id)
        }
    }

    @Test
    fun `memory golden ranking is stable across repeated builds`() {
        val idx = corpusIndex()
        val first = memoryCases.map { idx.search(it.first, limit = 3).map { h -> h.id } }
        val again = memoryCases.map { idx.search(it.first, limit = 3).map { h -> h.id } }
        assertEquals(first, again)
    }

    @Test
    fun `sub agent budget golden boundary`() {
        assertEquals(60, SubAgentBudgetPolicy.coerceTurns(null))
        assertEquals(8, SubAgentBudgetPolicy.coerceTurns(1))
        assertEquals(8, SubAgentBudgetPolicy.coerceTurns(8))
        assertEquals(60, SubAgentBudgetPolicy.coerceTurns(60))
        assertEquals(200, SubAgentBudgetPolicy.coerceTurns(999))
        assertEquals(3, SubAgentBudgetPolicy.warningLead(8))
        assertEquals(12, SubAgentBudgetPolicy.warningLead(60))
        assertEquals(40, SubAgentBudgetPolicy.warningLead(200))
    }

    @Test
    fun `warning fires only inside the lead window`() {
        val cap = 60
        val lead = SubAgentBudgetPolicy.warningLead(cap)
        assertTrue(SubAgentBudgetPolicy.shouldWarn(lead, cap))
        assertTrue(SubAgentBudgetPolicy.shouldWarn(1, cap))
        assertTrue(!SubAgentBudgetPolicy.shouldWarn(lead + 1, cap))
        assertTrue(!SubAgentBudgetPolicy.shouldWarn(0, cap))
    }

    @Test
    fun `golden corpus never leaks a removed fact`() {
        val idx = corpusIndex()
        idx.remove("mem-review")
        assertTrue(idx.search("技术评审会 三楼").none { it.id == "mem-review" })
        assertEquals(4, idx.size)
    }

    @Test
    fun `golden relevance beats unrelated docs for every case`() {
        val idx = corpusIndex()
        for ((query, expected) in memoryCases) {
            val hits = idx.search(query, limit = 3)
            val top = hits.first()
            assertEquals(expected, top.id)
            val others = hits.drop(1)
            assertTrue(others.all { it.score <= top.score })
        }
    }
}
