package com.openminis.app.data.repository

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ModelEntryRankingTest {

    private fun entry(id: String, name: String = id, ctx: Int? = null) = ModelEntry(
        providerInstanceId = "inst",
        baseModel = LLMModel(id = id, displayName = name, provider = "openai", contextWindow = ctx),
    )

    @Test
    fun `nothing is dropped, whatever the ranking says`() {
        val entries = listOf(entry("my-local-llama"), entry("gpt-5"), entry("azure-voice-xiaoxiao"))
        val sorted = ModelEntryRanking.sortedByReleaseRank(entries)
        assertEquals(entries.size, sorted.size)
        assertEquals(entries.map { it.baseModel.id }.toSet(), sorted.map { it.baseModel.id }.toSet())
    }

    @Test
    fun `equally ranked entries order by id, independent of input order`() {
        val a = entry("zzz-unknown-a")
        val b = entry("zzz-unknown-b")
        val c = entry("zzz-unknown-c")
        val expected = listOf("zzz-unknown-a", "zzz-unknown-b", "zzz-unknown-c")
        assertEquals(expected, ModelEntryRanking.sortedByReleaseRank(listOf(c, a, b)).map { it.baseModel.id })
        assertEquals(expected, ModelEntryRanking.sortedByReleaseRank(listOf(b, c, a)).map { it.baseModel.id })
    }

    @Test
    fun `larger context window sorts ahead among undated entries`() {
        val small = entry("zzz-model-a", ctx = 8_000)
        val large = entry("zzz-model-b", ctx = 200_000)
        val sorted = ModelEntryRanking.sortedByReleaseRank(listOf(small, large))
        assertEquals("zzz-model-b", sorted.first().baseModel.id)
    }

    @Test
    fun `lists too short to order are returned as-is`() {
        val empty = emptyList<ModelEntry>()
        assertSame(empty, ModelEntryRanking.sortedByReleaseRank(empty))
        val single = listOf(entry("only"))
        assertSame(single, ModelEntryRanking.sortedByReleaseRank(single))
    }
}
