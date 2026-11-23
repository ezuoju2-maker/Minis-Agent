package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [advanced-recall] Hybrid recall: any-term matching, field weighting, and
 * temporal decay, all additive to the legacy search()/topFacts() API.
 */
class MemoryRecallTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun repo(): MemoryFactRepository = MemoryFactRepository(File(tmp.root, "memory"))

    @Test
    fun `recall matches on any term not only all terms`() {
        val r = repo()
        r.upsert("user", "prefers editor", "vim")
        // legacy search requires ALL terms; recall matches on any.
        val legacy = r.search("prefers nonexistent")
        val hybrid = r.recall("prefers nonexistent")
        assertEquals("No remembered facts matched.", legacy)
        assertTrue(hybrid.contains("vim"))
    }

    @Test
    fun `recall ranks subject matches above value-only matches`() {
        val r = repo()
        // Both facts contain "vim", so both match; the one whose SUBJECT is
        // "vim" carries field weight and must outrank the value-only hit.
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
        repeat(3) { r.upsert("user", "editor", "vim") } // confidence 0.5 -> 0.8
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
    fun `legacy search and topFacts unchanged by new API`() {
        val r = repo()
        r.upsert("user", "editor", "vim")
        r.upsert("project", "db", "postgres")
        assertTrue(r.search("vim").contains("seen 1x"))
        assertTrue(r.topFacts().isEmpty()) // 0.5 < 0.6 threshold, unchanged
    }
}
