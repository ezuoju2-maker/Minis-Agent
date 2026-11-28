package com.openminis.app.skills.radar

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillMatcherTest {

    private fun c(repo: String, path: String, desc: String = "", official: Boolean = false): SkillCandidate {
        val base = SkillCandidate.of(repo, "main", path, official)
        return base.copy(description = desc)
    }

    private val pdf = c("anthropics/skills", "pdf/SKILL.md", "Fill, extract and create PDF documents. Form filling, text extraction.")
    private val brand = c("anthropics/skills", "brand-guidelines/SKILL.md", "Apply brand colors and typography.")
    private val review = c("affaan-m/ECC", "skills/code-reviewer/SKILL.md", "Review code for bugs and security issues.")
    private val slides = c("ComposioHQ/awesome-claude-skills", "slides/SKILL.md", "Design presentation decks.")

    @Test
    fun exactNameOutranksDescriptionOnly() {
        val ranked = SkillMatcher.rank("pdf", listOf(brand, pdf, review), 3)
        assertEquals("pdf", ranked.first().candidate.name)
    }

    @Test
    fun multiWordQueryFindsTheRightSkill() {
        val ranked = SkillMatcher.rank("fill pdf forms", listOf(brand, slides, pdf), 3)
        assertEquals(pdf.id, ranked.first().candidate.id)
        assertTrue(ranked.first().score > 10.0)
    }

    @Test
    fun unrelatedQueryScoresZero() {
        val ranked = SkillMatcher.rank("kubernetes cluster autoscaling", listOf(brand, slides, pdf), 3)
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun emptyQueryReturnsNothing() {
        assertTrue(SkillMatcher.rank("   ", listOf(pdf, brand), 3).isEmpty())
    }

    @Test
    fun installedFlagIsOnlyForTheModelNotTheMatcher() {
        // matcher stays independent of repository state; pinned here so the contract is visible
        val ranked = SkillMatcher.rank("brand", listOf(brand), 1)
        assertEquals(1, ranked.size)
    }

    @Test
    fun chineseQueryMatchesChineseDescription() {
        val cn = c("some/repo", "pdf-cn/SKILL.md", "填写和提取 PDF 表单")
        val ranked = SkillMatcher.rank("填写 PDF 表单", listOf(cn, slides), 3)
        assertEquals(cn.id, ranked.first().candidate.id)
    }

    @Test
    fun officialSourceGetsASmallTieBreak() {
        val plain = c("someone/else", "pdf/SKILL.md", "")
        val official = c("anthropics/skills", "pdf/SKILL.md", "", official = true)
        val ranked = SkillMatcher.rank("pdf", listOf(plain, official), 2)
        assertTrue(ranked.first().candidate.official)
    }

    @Test
    fun topLevelSkillKeepsItsDirectoryName() {
        assertEquals("pdf", SkillCandidate.of("anthropics/skills", "main", "pdf/SKILL.md", false).name)
        assertEquals("x", SkillCandidate.of("a/b", "main", "deep/x/SKILL.md", false).name)
        assertEquals("b", SkillCandidate.of("a/b", "main", "SKILL.md", false).name)
    }

    @Test
    fun idIsStableAndReadable() {
        assertEquals("anthropics/skills/pdf", pdf.id)
        assertEquals("https://github.com/anthropics/skills/blob/main/pdf/SKILL.md", pdf.url)
    }

    @Test
    fun limitIsRespected() {
        val many = (1..20).map { c("r/x", "pdf-$it/SKILL.md") }
        assertEquals(5, SkillMatcher.rank("pdf", many, 5).size)
    }
}

class SkillTreeTest {

    private fun tree(vararg paths: String): String {
        val arr = paths.joinToString(",") { "\"$it\"" }
        val nodes = paths.joinToString(",") { "{\"path\":\"$it\",\"type\":\"blob\"}" }
        return "{\"sha\":\"x\",\"truncated\":false,\"tree\":[$nodes]}"
    }

    @Test
    fun collectsSkillMdFilesOnly() {
        val json = tree(
            "pdf/SKILL.md",
            "pdf/scripts/fill.py",
            "README.md",
            "code-reviewer/SKILL.md",
        )
        val out = SkillTree.parse(json, "anthropics/skills", "main", official = true)
        assertEquals(2, out.size)
        assertTrue(out.all { it.path.endsWith("SKILL.md") })
        assertTrue(out.all { it.official })
        assertEquals("pdf", out[0].name)
    }

    @Test
    fun skipsNoiseDirectories() {
        val json = tree("node_modules/foo/SKILL.md", ".github/x/SKILL.md", "good/SKILL.md")
        val out = SkillTree.parse(json, "o/r", "main", false)
        assertEquals(1, out.size)
        assertEquals("good", out[0].name)
    }

    @Test
    fun badJsonIsEmptyNotACrash() {
        assertTrue(SkillTree.parse("not json", "o/r", "main", false).isEmpty())
        assertTrue(SkillTree.parse("{}", "o/r", "main", false).isEmpty())
    }

    @Test
    fun entryCountIsCapped() {
        val paths = (1..2000).map { "s$it/SKILL.md" }
        val nodes = paths.joinToString(",") { "{\"path\":\"$it\",\"type\":\"blob\"}" }
        val json = "{\"tree\":[$nodes]}"
        assertEquals(SkillTree.MAX_PER_SOURCE, SkillTree.parse(json, "o/r", "main", false).size)
    }
}

class SkillIndexStoreTest {

    @Test
    fun roundTripsThroughDisk() {
        val dir = File(System.getProperty("java.io.tmpdir"), "radar-test-" + System.nanoTime())
        val file = File(dir, "index.json")
        val store = SkillIndexStore(file)
        assertNull(store.load())
        val entries = listOf(
            SkillCandidate("a/b#x", "x", "a/b", "main", "x/SKILL.md", "does x"),
            SkillCandidate("a/b#y", "y", "a/b", "main", "y/SKILL.md", ""),
        )
        store.save(SkillIndexStore.Snapshot(1234L, entries, listOf("a/b")))
        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(2, loaded!!.entries.size)
        assertEquals(1234L, loaded.fetchedAt)
        assertEquals(listOf("a/b"), loaded.sources)
        file.delete()
        dir.delete()
    }

    @Test
    fun patchesDescriptionsInPlace() {
        val dir = File(System.getProperty("java.io.tmpdir"), "radar-test-" + System.nanoTime())
        val file = File(dir, "index.json")
        val store = SkillIndexStore(file)
        val entry = SkillCandidate("a/b#x", "x", "a/b", "main", "x/SKILL.md", "")
        store.save(SkillIndexStore.Snapshot(1L, listOf(entry), listOf("a/b")))
        store.patchDescriptions(listOf(entry.copy(name = "X Skill", description = "fills forms")))
        val loaded = store.load()!!
        assertEquals("fills forms", loaded.entries.first().description)
        assertEquals("X Skill", loaded.entries.first().name)
        file.delete()
        dir.delete()
    }

    @Test
    fun corruptIndexFallsBackToNull() {
        val dir = File(System.getProperty("java.io.tmpdir"), "radar-test-" + System.nanoTime())
        val file = File(dir, "index.json")
        file.parentFile?.mkdirs()
        file.writeText("}}}")
        assertNull(SkillIndexStore(file).load())
        file.delete()
        dir.delete()
    }
}

class SkillCandidateJsonTest {

    @Test
    fun jsonRoundTripKeepsIdentity() {
        val c = SkillCandidate("r#p", "p", "r", "dev", "p/SKILL.md", "desc", true)
        val back = SkillCandidate.fromJson(JSONObject(c.toJson().toString()))
        assertEquals(c, back)
    }

    @Test
    fun missingIdIsRejected() {
        assertNull(SkillCandidate.fromJson(JSONObject().put("repo", "a/b")))
    }
}
