package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemoryFactRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun repo(): MemoryFactRepository = MemoryFactRepository(File(tmp.root, "memory"))

    @Test
    fun `a new fact is stored once`() {
        val r = repo()
        r.upsert("user", "prefers editor", "vim")
        assertEquals(1, r.count())
    }

    @Test
    fun `re-stating the same fact strengthens instead of duplicating`() {
        val r = repo()
        r.upsert("user", "prefers editor", "vim")
        r.upsert("user", "prefers editor", "vim")
        r.upsert("user", "prefers editor", "vim")
        assertEquals(1, r.count())
        val out = r.search("vim")
        assertTrue(out.contains("seen 3x"))
        assertTrue(out.contains("confidence 0.80"))
    }

    @Test
    fun `same subjectPredicate is case insensitive`() {
        val r = repo()
        r.upsert("User", "Prefers Editor", "vim")
        r.upsert("user", "prefers editor", "vim")
        assertEquals(1, r.count())
    }

    @Test
    fun `search matches on any term and excludes non-matches`() {
        val r = repo()
        r.upsert("user", "prefers editor", "vim")
        r.upsert("project Alpha", "database", "PostgreSQL")
        val hit = r.search("database")
        assertTrue(hit.contains("PostgreSQL"))
        assertTrue(!hit.contains("vim"))
        assertEquals("No remembered facts matched.", r.search("nonexistent"))
    }

    @Test
    fun `topFacts only injects confident facts and respects the char budget`() {
        val r = repo()
        r.upsert("user", "maybe likes", "tea")
        repeat(4) { r.upsert("user", "prefers editor", "vim") }
        val top = r.topFacts()
        assertTrue(top.contains("prefers editor"))
        assertTrue(!top.contains("maybe likes"))
    }

    @Test
    fun `facts persist across repository instances (cross-session)`() {
        val dir = File(tmp.root, "memory")
        val a = MemoryFactRepository(dir)
        a.upsert("user", "timezone", "Asia/Shanghai")
        val b = MemoryFactRepository(dir)
        assertEquals(1, b.count())
        assertTrue(b.search("Shanghai").contains("Asia/Shanghai"))
    }

    @Test
    fun `malformed and blank inputs are rejected`() {
        val r = repo()
        r.upsert("", "predicate", "value")
        r.upsert("subject", "", "value")
        assertEquals(0, r.count())
        assertTrue(r.upsert("s", "p", "").startsWith("Error"))
    }
}
