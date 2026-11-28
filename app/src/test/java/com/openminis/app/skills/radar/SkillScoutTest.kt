package com.openminis.app.skills.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillScoutTest {

    private val pdf = SkillCandidate.of("anthropics/skills", "main", "pdf/SKILL.md", true)
        .copy(description = "Fill, extract and create PDF documents. Form filling.")
    private val poster = SkillCandidate.of("someone/poster-kit", "main", "poster-design/SKILL.md", false)
        .copy(description = "生成海报、活动主视觉与社媒配图，含电商主图模板")
    private val slides = SkillCandidate.of("ComposioHQ/awesome-claude-skills", "main", "slides/SKILL.md", false)
        .copy(description = "Design presentation decks.")

    @Test
    fun chineseTaskIsTaskLike() {
        assertTrue(SkillScout.isTaskLike("帮我做一份电商主图，产品是保温杯"))
    }

    @Test
    fun englishTaskIsTaskLike() {
        assertTrue(SkillScout.isTaskLike("build a landing page and deploy it"))
    }

    @Test
    fun greetingsAndIdentityQuestionsAreNotTasks() {
        assertFalse(SkillScout.isTaskLike("你是谁"))
        assertFalse(SkillScout.isTaskLike("what can you do"))
        assertFalse(SkillScout.isTaskLike("hi"))
        assertFalse(SkillScout.isTaskLike("你好呀"))
    }

    @Test
    fun taskFindsTheMatchingSkill() {
        val hits = SkillScout.suggest("帮我做一份电商主图", listOf(pdf, poster, slides), emptySet())
        assertEquals(1, hits.size)
        assertEquals(poster.id, hits.first().candidate.id)
    }

    @Test
    fun installedSkillsAreNotSuggestedAgain() {
        val hits = SkillScout.suggest("帮我做一份电商主图", listOf(poster), setOf(poster.url))
        assertTrue(hits.isEmpty())
        assertNull(SkillScout.fragment("帮我做一份电商主图", listOf(poster), setOf(poster.url)))
    }

    @Test
    fun unrelatedTaskGetsNoFragment() {
        assertNull(SkillScout.fragment("帮我把这段对话翻译成法语", listOf(pdf, slides), emptySet()))
    }

    @Test
    fun fragmentTellsTheModelToInstallFirst() {
        val frag = SkillScout.fragment("fill a pdf form for me", listOf(pdf, slides), emptySet())
        assertNotNull(frag)
        assertTrue(frag!!.contains("find_skills action=install"))
        assertTrue(frag.contains("skill_load"))
        assertTrue(frag.contains("pdf"))
    }
}

class SkillDiscoveryTest {

    private fun repo(name: String, stars: Int, desc: String = "skill", fork: Boolean = false, archived: Boolean = false): String =
        "{\"full_name\":\"$name\",\"default_branch\":\"main\",\"stargazers_count\":$stars," +
            "\"description\":\"$desc\",\"fork\":$fork,\"archived\":$archived}"

    @Test
    fun keepsPopularSkillReposAndSortsByStars() {
        val json = "{\"items\":[" +
            repo("a/small", 120) + "," +
            repo("b/big", 5000) + "]}"
        val out = SkillDiscovery.parse(json, emptySet())
        assertEquals("b/big", out.first().repo)
        assertEquals(2, out.size)
    }

    @Test
    fun dropsForksArchivedAndLowStarRepos() {
        val json = "{\"items\":[" +
            repo("x/forked", 900, fork = true) + "," +
            repo("x/old", 900, archived = true) + "," +
            repo("x/tiny", 9) + "]}"
        assertTrue(SkillDiscovery.parse(json, emptySet()).isEmpty())
    }

    @Test
    fun dropsReposWithoutASkillSignalOrAlreadyKnown() {
        val json = "{\"items\":[" +
            repo("x/random", 800, desc = "a rust game engine") + "," +
            repo("anthropics/skills", 900) + "]}"
        assertTrue(SkillDiscovery.parse(json, setOf("anthropics/skills")).isEmpty())
    }

    @Test
    fun capsResults() {
        val items = (1..12).joinToString(",") { repo("o/r$it", 100 + it) }
        assertEquals(8, SkillDiscovery.parse("{\"items\":[$items]}", emptySet()).size)
    }

    @Test
    fun badJsonIsEmptyNotACrash() {
        assertTrue(SkillDiscovery.parse("nope", emptySet()).isEmpty())
        assertTrue(SkillDiscovery.parse("{}", emptySet()).isEmpty())
    }
}
