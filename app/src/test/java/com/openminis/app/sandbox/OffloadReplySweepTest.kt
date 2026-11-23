package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OffloadReplySweepTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val prefix = ".native-offload-"
    private val ttlMs = 10 * 60 * 1000L

    private fun eligible(file: File, all: Boolean, now: Long): Boolean {
        if (!file.isFile || !file.name.startsWith(prefix)) return false
        return all || file.lastModified() <= now - ttlMs
    }

    private fun write(name: String, ageMs: Long): File {
        val f = File(tmp.root, name)
        f.writeText("output")
        f.setLastModified(System.currentTimeMillis() - ageMs)
        return f
    }

    @Test
    fun `start sweep removes every reply file regardless of age`() {
        val fresh = write("${prefix}123-1", 0)
        val old = write("${prefix}456-2", 24 * 60 * 60 * 1000L)
        val now = System.currentTimeMillis()

        assertTrue(eligible(fresh, all = true, now = now))
        assertTrue(eligible(old, all = true, now = now))
    }

    @Test
    fun `in-session sweep must NOT touch a file still awaiting its cat`() {
        val justWritten = write("${prefix}789-3", 0)
        val secondsOld = write("${prefix}789-4", 5_000L)
        val now = System.currentTimeMillis()

        assertTrue(
            "a just-written reply must survive the in-session sweep",
            !eligible(justWritten, all = false, now = now),
        )
        assertTrue(
            "a seconds-old reply must still survive",
            !eligible(secondsOld, all = false, now = now),
        )
    }

    @Test
    fun `in-session sweep removes files past the TTL`() {
        val stale = write("${prefix}111-5", ttlMs + 60_000L)
        assertTrue(eligible(stale, all = false, now = System.currentTimeMillis()))
    }

    @Test
    fun `unrelated files in tmp are never deleted`() {
        val userFile = write("important.txt", 24 * 60 * 60 * 1000L)
        val toolFile = write("pip-build-abc", 24 * 60 * 60 * 1000L)
        val now = System.currentTimeMillis()

        for (all in listOf(true, false)) {
            assertTrue("must not claim $userFile", !eligible(userFile, all, now))
            assertTrue("must not claim $toolFile", !eligible(toolFile, all, now))
        }
    }

    @Test
    fun `a directory named like a reply file is not deleted`() {
        val dir = File(tmp.root, "${prefix}222-6")
        assertTrue(dir.mkdirs())
        assertEquals(false, eligible(dir, all = true, now = System.currentTimeMillis()))
    }
}
