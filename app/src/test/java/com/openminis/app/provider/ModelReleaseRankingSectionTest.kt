package com.openminis.app.provider

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelReleaseRankingSectionTest {

    private fun rank(
        day: String? = null,
        cost: Double = 0.0,
        ctx: Int = 0,
        name: String = "m",
    ) = ModelReleaseIndex.Rank(
        releaseDay = day?.let { ModelReleaseIndex.parseReleaseDay(it) },
        outputCostPerMTok = cost,
        contextWindow = ctx,
        displayName = name,
    )

    private fun order(vararg ranks: ModelReleaseIndex.Rank): List<String> =
        ranks.toList().sortedWith(ModelReleaseIndex.comparator).map { it.displayName }


    @Test
    fun `a mixed-vintage section reads newest first`() {
        assertEquals(
            listOf("newest", "middle", "oldest"),
            order(
                rank(day = "2025-03-01", name = "oldest"),
                rank(day = "2026-09-01", name = "newest"),
                rank(day = "2026-01-15", name = "middle"),
            ),
        )
    }

    @Test
    fun `undated entries sink to the bottom without being lost`() {
        val result = order(
            rank(name = "custom-relay"),
            rank(day = "2026-09-01", name = "dated-new"),
            rank(name = "local-gguf"),
            rank(day = "2024-01-01", name = "dated-old"),
        )
        assertEquals(listOf("dated-new", "dated-old", "custom-relay", "local-gguf"), result)
        assertEquals("nothing may be dropped", 4, result.size)
    }

    @Test
    fun `the tie-break cascade is date, then cost, then context, then name`() {
        assertEquals(
            listOf("pricey", "cheap"),
            order(
                rank(day = "2026-05-05", cost = 1.0, name = "cheap"),
                rank(day = "2026-05-05", cost = 15.0, name = "pricey"),
            ),
        )
        assertEquals(
            listOf("big-ctx", "small-ctx"),
            order(
                rank(day = "2026-05-05", cost = 3.0, ctx = 200_000, name = "big-ctx"),
                rank(day = "2026-05-05", cost = 3.0, ctx = 8_192, name = "small-ctx"),
            ),
        )
        assertEquals(
            listOf("alpha", "beta", "gamma"),
            order(
                rank(day = "2026-05-05", cost = 3.0, ctx = 1000, name = "gamma"),
                rank(day = "2026-05-05", cost = 3.0, ctx = 1000, name = "alpha"),
                rank(day = "2026-05-05", cost = 3.0, ctx = 1000, name = "beta"),
            ),
        )
    }

    @Test
    fun `an earlier key always dominates a later one`() {
        assertEquals(
            "a newer date beats any price",
            listOf("new-and-cheap", "old-and-pricey"),
            order(
                rank(day = "2024-01-01", cost = 99.0, name = "old-and-pricey"),
                rank(day = "2026-09-01", cost = 0.1, name = "new-and-cheap"),
            ),
        )
        assertEquals(
            "price beats context",
            listOf("pricey-small", "cheap-huge"),
            order(
                rank(day = "2026-05-05", cost = 1.0, ctx = 1_000_000, name = "cheap-huge"),
                rank(day = "2026-05-05", cost = 20.0, ctx = 4_096, name = "pricey-small"),
            ),
        )
        assertEquals(
            "context beats name",
            listOf("zzz-huge", "aaa-small"),
            order(
                rank(day = "2026-05-05", ctx = 4_096, name = "aaa-small"),
                rank(day = "2026-05-05", ctx = 1_000_000, name = "zzz-huge"),
            ),
        )
    }

    @Test
    fun `the name tie-break ignores case`() {
        assertEquals(
            listOf("apple", "Banana", "cherry"),
            order(
                rank(name = "cherry"),
                rank(name = "apple"),
                rank(name = "Banana"),
            ),
        )
    }

    @Test
    fun `the order is independent of input order`() {
        val section = listOf(
            rank(day = "2026-09-01", cost = 10.0, ctx = 400_000, name = "flagship"),
            rank(day = "2026-09-01", cost = 2.0, ctx = 400_000, name = "flagship-mini"),
            rank(day = "2025-06-01", cost = 10.0, ctx = 200_000, name = "previous"),
            rank(name = "user-custom"),
            rank(name = "another-custom"),
        )
        val expected = section.sortedWith(ModelReleaseIndex.comparator).map { it.displayName }
        assertEquals(
            listOf("flagship", "flagship-mini", "previous", "another-custom", "user-custom"),
            expected,
        )
        for (seed in 1..20) {
            val shuffled = section.shuffled(java.util.Random(seed.toLong()))
            assertEquals(
                "permutation $seed must sort to the same order",
                expected,
                shuffled.sortedWith(ModelReleaseIndex.comparator).map { it.displayName },
            )
        }
    }

    @Test
    fun `a year-month date ranks among the fully dated, not below them`() {
        assertEquals(
            listOf("sept-full", "aug-short", "jan-full"),
            order(
                rank(day = "2026-01-15", name = "jan-full"),
                rank(day = "2026-08", name = "aug-short"),
                rank(day = "2026-09-20", name = "sept-full"),
            ),
        )
    }


    @Test
    fun `the same model under different vendor prefixes ranks together`() {
        val sameVintage = listOf(
            rank(day = "2026-04-01", cost = 15.0, ctx = 200_000, name = "Claude Opus 5"),
            rank(day = "2026-04-01", cost = 15.0, ctx = 200_000, name = "Claude Opus 5"),
            rank(day = "2026-04-01", cost = 15.0, ctx = 200_000, name = "Claude Opus 5"),
        )
        val sorted = sameVintage.sortedWith(ModelReleaseIndex.comparator)
        assertEquals("all three must survive — they are separate endpoints", 3, sorted.size)
        for (r in sorted) {
            assertEquals(
                "identical ranks must compare equal, so the prefix cannot reorder them",
                0,
                ModelReleaseIndex.comparator.compare(sameVintage.first(), r),
            )
        }
        assertEquals(
            listOf("Claude Opus 5", "Claude Opus 5", "Claude Opus 5", "Claude Opus 4.5"),
            order(
                *(sameVintage + rank(day = "2025-11-01", cost = 15.0, ctx = 200_000, name = "Claude Opus 4.5"))
                    .toTypedArray(),
            ),
        )
    }

    @Test
    fun `equivalent date spellings produce an identical sort key`() {
        assertEquals(
            ModelReleaseIndex.parseReleaseDay("2026-04-01"),
            ModelReleaseIndex.parseReleaseDay("2026-04-1".replace("-1", "-01")),
        )
        assertEquals(
            "the short form is the 1st of the month, i.e. the same key as an explicit -01",
            ModelReleaseIndex.parseReleaseDay("2026-04-01"),
            ModelReleaseIndex.parseReleaseDay("2026-04"),
        )
        assertNotEquals(
            "…but a different day is a different key",
            ModelReleaseIndex.parseReleaseDay("2026-04-01"),
            ModelReleaseIndex.parseReleaseDay("2026-04-02"),
        )
    }


    @Test
    fun `production still ranks once per entry, not once per comparison`() {
        val src = ProductionSources.read("data/repository/ModelEntryRanking.kt")
        assertTrue(
            "the comparator must operate on PRE-RANKED pairs — a Comparator<ModelEntry> is the ANR shape",
            src.contains("Comparator<Pair<ModelEntry, ModelReleaseIndex.Rank>>"),
        )
        assertTrue(
            "…and the sort must consume those pairs",
            src.contains(".sortedWith(releaseRankOrder)"),
        )
        assertTrue(
            "the map that ranks each entry exactly once must still be there",
            src.contains("ModelReleaseIndex.rank(") && src.contains(".map { it.first }"),
        )
        assertEquals(
            "rank() must be called from exactly ONE place in this file — a second call site is " +
                "how per-comparison ranking creeps back",
            1,
            Regex("ModelReleaseIndex\\.rank\\(").findAll(src).count(),
        )
        assertTrue(
            "a list too short to order must not force the catalog index to build",
            src.contains("if (entries.size < 2) return entries"),
        )
    }

    @Test
    fun `the shared picker delegates its section sort to the extracted ranking`() {
        val src = ProductionSources.read("ui/components/ModelEntryPicker.kt")
        assertTrue(
            "the picker must call the one ranking entry point",
            src.contains("ModelEntryRanking") && src.contains(".sortedByReleaseRank("),
        )
        assertEquals(
            "the picker must not hand-roll a rank comparator of its own",
            0,
            Regex("ModelReleaseIndex\\.rank\\(").findAll(src).count(),
        )

        val repo = ProductionSources.read("data/repository/ProviderRepository.kt")
        assertTrue(
            "the repository accessors must route through the same object",
            repo.contains("ModelEntryRanking.sortedByReleaseRank(this)"),
        )
        assertEquals(
            "…and likewise must not rank inline",
            0,
            Regex("ModelReleaseIndex\\.rank\\(").findAll(repo).count(),
        )
    }

    @Test
    fun `there is exactly one release-rank comparator in the tree`() {
        val definitions = ProductionSources.allKotlinFiles().filter {
            it.readText().contains("val comparator: Comparator<Rank>")
        }
        assertEquals(
            "the release-rank comparator must be defined exactly once, found in " +
                definitions.map { it.name },
            1,
            definitions.size,
        )
        assertEquals("ModelReleaseIndex.kt", definitions.single().name)
    }
}
