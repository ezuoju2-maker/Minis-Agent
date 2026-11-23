package com.openminis.app.sandbox

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Review0923SandboxEdgeTest {

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    private fun code(path: String): String = src(path).lineSequence()
        .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
        .joinToString("\n")

    private val freshCode by lazy { code("src/main/java/com/openminis/app/sandbox/FreshProcessShell.kt") }
    private val coordCode by lazy { code("src/main/java/com/openminis/app/sandbox/ExecutionCoordinator.kt") }


    @Test
    fun `BUG erase-line after CR leaves only the new text`() {
        assertEquals("Done", TerminalSanitizer.sanitize("Downloading 50%\r\u001B[KDone"))
    }

    @Test
    fun `BUG a coloured segment overwritten by a plain one is not garbled`() {
        assertEquals("100%", TerminalSanitizer.sanitize("\u001B[32m50%\u001B[0m\r100%"))
    }

    @Test
    fun `plain-text overwrite semantics from 71e813d03 still hold`() {
        assertEquals("BBAA", TerminalSanitizer.sanitize("AAAA\rBB"))
        assertEquals("100%[====]", TerminalSanitizer.sanitize(" 50%[==  ]\r100%[====]"))
    }


    @Test
    fun `BUG the default fresh path keeps the GH#186 seccomp self-heal`() {
        val consulted = freshCode.contains("SeccompFallbackPolicy.shouldRetryWithoutSeccomp") ||
            coordCode.contains("SeccompFallbackPolicy.shouldRetryWithoutSeccomp")
        assertTrue("fresh path must consult SeccompFallbackPolicy", consulted)
    }

    @Test
    fun `per-chunk UTF-8 decoding corrupts a CJK character split across reads`() {
        val bytes = "中文输出".toByteArray(StandardCharsets.UTF_8)
        val stream = ByteArrayInputStream(bytes)
        val out = StringBuilder()
        val first = ByteArray(1)
        stream.read(first)
        out.append(String(first, 0, 1, StandardCharsets.UTF_8))
        val rest = stream.readBytes()
        out.append(String(rest, 0, rest.size, StandardCharsets.UTF_8))
        assertNotEquals("中文输出", out.toString())
        assertTrue(out.contains('�'))
    }

    @Test
    fun `BUG the fresh read loop decodes UTF-8 across read boundaries`() {
        assertFalse(
            "FreshProcessShell must not decode each read() chunk independently",
            freshCode.contains("String(buf, 0, n, StandardCharsets.UTF_8)"),
        )
    }

    @Test
    fun `a user Stop and a timeout stay distinguishable in the coordinator`() {
        assertNotEquals(124, FreshProcessShell.CANCELLED_EXIT)
        assertTrue(coordCode.contains("exitCode != 0 && exitCode != 124"))
    }


    @Test
    fun `fresh wrap passes the command as one argv element, whatever it contains`() {
        val nasty = "echo 'a&b' \"\$HOME\" ; curl 'x?a=1&b=2' & wait\n# trailing comment"
        val argv = ForegroundCommandGroup.wrapForFreshProcess(nasty, "/.minis-exit/t")
        assertEquals(
            listOf(ForegroundCommandGroup.SETSID, "/bin/sh", "-c", ForegroundCommandGroup.FRESH_RUNNER, "sh", nasty, "/.minis-exit/t"),
            argv,
        )
    }

    @Test
    fun `pgid parse never yields a group that would hit init, the whole group, or a guess`() {
        val m = "abc"
        assertEquals(null, ForegroundCommandGroup.parsePgid("__MINIS_PGID_abc_0__", m))
        assertEquals(null, ForegroundCommandGroup.parsePgid("__MINIS_PGID_abc_1__", m))
        assertEquals(null, ForegroundCommandGroup.parsePgid("__MINIS_PGID_abc_-5__", m))
        assertEquals(null, ForegroundCommandGroup.parsePgid("__MINIS_PGID_abc_", m))
        assertEquals(null, ForegroundCommandGroup.parsePgid("__MINIS_PGID_other_42__", m))
        assertEquals(42, ForegroundCommandGroup.parsePgid("noise\n__MINIS_PGID_abc_42__\nmore", m))
    }

    @Test
    fun `warm wrap keeps a single quote in the command intact`() {
        val wrapped = ForegroundCommandGroup.wrap("echo 'hi'", "m")
        assertTrue(wrapped.contains("echo '\\''hi'\\''"))
    }
}
