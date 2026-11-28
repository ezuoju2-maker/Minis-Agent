package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemoryRecallTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun repo(): MemoryFactRepository = MemoryFactRepository(File(tmp.root, "memory"))

    @Test
    fun `recall matches on any term not only all terms`() {
        val r = repo()
        r.upsert("user", "prefers editor", "vim")
        val legacy = r.search("prefers nonexistent")
        val hybrid = r.recall("prefers nonexistent")
        assertEquals("No remembered facts matched.", legacy)
        assertTrue(hybrid.contains("vim"))
    }

    @Test
    fun `recall ranks subject matches above value-only matches`() {
        val r = repo()
        r.upsert("user", "editor choice", "I use vim daily")
        r.upsert("vim", "is", "a text editor")
        val out = r.recall("vim")
        assertTrue(out.contains("I use vim daily"))
        assertTrue(out.indexOf("is = a text editor") < out.indexOf("editor choice = I use vim daily"))
    }

    @Test
    fun `empty query yields empty recall`() {
        val r = repo()
        r.upsert("user", "editor", "vim")
        assertEquals("", r.recall(""))
    }

    @Test
    fun `recallForPrompt falls back to topFacts for blank query`() {
        val r = repo()
        repeat(3) { r.upsert("user", "editor", "vim") }
        val fallback = r.recallForPrompt("   ")
        assertTrue(fallback.contains("user: editor = vim"))
    }

    @Test
    fun `recallForPrompt boosts query-relevant facts`() {
        val r = repo()
        r.upsert("user", "language", "Kotlin")
        r.upsert("user", "editor", "vim")
        val out = r.recallForPrompt("editor vim")
        assertTrue(out.contains("editor = vim"))
    }

    @Test
    fun `recall handles chinese queries without spaces`() {
        val r = repo()
        r.upsert("用户", "常用编辑器", "vim")
        r.upsert("项目", "数据库", "postgres")
        val out = r.recall("我平时用哪个编辑器")
        assertTrue(out.contains("vim"))
        assertTrue(!out.contains("postgres"))
    }

    @Test
    fun `recallForPrompt matches chinese sentences`() {
        val r = repo()
        r.upsert("用户", "编程语言", "Kotlin")
        r.upsert("用户", "早餐", "豆浆")
        val out = r.recallForPrompt("我最近在写什么语言的项目")
        assertTrue(out.contains("Kotlin"))
        assertTrue(!out.contains("豆浆"))
    }

    @Test
    fun `recallForPrompt sees facts written after the index was built`() {
        val r = repo()
        r.upsert("user", "editor", "vim")
        r.recallForPrompt("editor")
        r.upsert("user", "terminal", "kitty")
        val out = r.recallForPrompt("terminal kitty")
        assertTrue(out.contains("terminal = kitty"))
    }

    @Test
    fun `legacy search and topFacts unchanged by new API`() {
        val r = repo()
        r.upsert("user", "editor", "vim")
        r.upsert("project", "db", "postgres")
        assertTrue(r.search("vim").contains("seen 1x"))
        assertTrue(r.topFacts().isEmpty())
    }
}
