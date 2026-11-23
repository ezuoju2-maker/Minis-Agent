package com.openminis.app.sandbox

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SpawnSignalMaskTest {

    private val original = SpawnSignalMask.native
    private val events = mutableListOf<String>()

    private class FakeNative(
        private val events: MutableList<String>,
        private val unblockResult: () -> Int,
        private val blockResult: Int = 0,
    ) : SpawnSignalMask.Native {
        override fun unblockSigpipe(): Int {
            events += "unblock"
            return unblockResult()
        }

        override fun blockSigpipe(): Int {
            events += "block"
            return blockResult
        }
    }

    @Before
    fun setUp() {
        events.clear()
    }

    @After
    fun tearDown() {
        SpawnSignalMask.native = original
    }

    @Test
    fun `spawn runs between unblock and re-block when SIGPIPE was blocked`() {
        SpawnSignalMask.native = FakeNative(events, { SpawnSignalMask.WAS_BLOCKED })
        val value = SpawnSignalMask.withSigpipeUnblocked { events += "spawn"; 42 }
        assertEquals(42, value)
        assertEquals(listOf("unblock", "spawn", "block"), events)
    }

    @Test
    fun `mask is restored even when the spawn throws`() {
        SpawnSignalMask.native = FakeNative(events, { SpawnSignalMask.WAS_BLOCKED })
        try {
            SpawnSignalMask.withSigpipeUnblocked<Unit> { events += "spawn"; throw java.io.IOException("no proot") }
            fail("the spawn failure must propagate unchanged")
        } catch (e: java.io.IOException) {
            assertEquals("no proot", e.message)
        }
        assertEquals(listOf("unblock", "spawn", "block"), events)
    }

    @Test
    fun `a thread that never had SIGPIPE blocked is left unblocked`() {
        SpawnSignalMask.native = FakeNative(events, { 0 })
        SpawnSignalMask.withSigpipeUnblocked { events += "spawn" }
        assertEquals(listOf("unblock", "spawn"), events)
    }

    @Test
    fun `an ignored disposition still restores the mask`() {
        SpawnSignalMask.native = FakeNative(
            events,
            { SpawnSignalMask.WAS_BLOCKED or SpawnSignalMask.DISPOSITION_IGNORED },
        )
        SpawnSignalMask.withSigpipeUnblocked { events += "spawn" }
        assertEquals(listOf("unblock", "spawn", "block"), events)
    }

    @Test
    fun `a native error still spawns and does not touch the mask again`() {
        SpawnSignalMask.native = FakeNative(events, { -22 })
        SpawnSignalMask.withSigpipeUnblocked { events += "spawn" }
        assertEquals(listOf("unblock", "spawn"), events)
    }

    @Test
    fun `a missing native library still spawns`() {
        SpawnSignalMask.native = FakeNative(events, { throw UnsatisfiedLinkError("libpty_bridge.so") })
        val value = SpawnSignalMask.withSigpipeUnblocked { events += "spawn"; "ran" }
        assertEquals("ran", value)
        assertEquals(listOf("unblock", "spawn"), events)
    }

    @Test
    fun `a failed re-block does not turn a good spawn into a failure`() {
        SpawnSignalMask.native = FakeNative(events, { SpawnSignalMask.WAS_BLOCKED }, blockResult = -1)
        assertEquals("ok", SpawnSignalMask.withSigpipeUnblocked { "ok" })
        assertEquals(listOf("unblock", "block"), events)
    }

    @Test
    fun `start really starts the process through the wrapper`() {
        SpawnSignalMask.native = FakeNative(events, { SpawnSignalMask.WAS_BLOCKED })
        val p = SpawnSignalMask.start(ProcessBuilder("/bin/sh", "-c", "printf ok").redirectErrorStream(true))
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.waitFor())
        assertEquals("ok", out)
        assertEquals(listOf("unblock", "block"), events)
    }

    @Test
    fun `agent shell spawns all go through SpawnSignalMask`() {
        val sites = listOf("FreshProcessShell.kt", "PersistentShell.kt", "ShellExecutor.kt")
        for (name in sites) {
            val code = File("src/main/java/com/openminis/app/sandbox/$name").readLines()
                .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                .joinToString("\n")
            assertTrue("$name must spawn via SpawnSignalMask.start", code.contains("SpawnSignalMask.start("))
            assertFalse(
                "$name calls ProcessBuilder.start() directly",
                Regex("""\b(pb|processBuilder)\.start\(\)""").containsMatchIn(code),
            )
        }
    }

    @Test
    fun `no other sandbox file starts a ProcessBuilder directly`() {
        val offenders = File("src/main/java/com/openminis/app/sandbox").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "SpawnSignalMask.kt" }
            .filter { f ->
                val code = f.readLines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                    .joinToString("\n")
                val builders = Regex("""va[lr]\s+(\w+)\s*=\s*ProcessBuilder\(""").findAll(code).map { it.groupValues[1] }
                builders.any { Regex("""\b$it\.start\(\)""").containsMatchIn(code) } ||
                    Regex("""ProcessBuilder\([^\n]*\)\.start\(\)""").containsMatchIn(code)
            }
            .map { it.name }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `native bridge exports the SIGPIPE helpers`() {
        val c = File("src/main/cpp/pty_bridge.c").readText()
        assertTrue(c.contains("Java_com_openminis_app_sandbox_PtyBridge_unblockSigpipe("))
        assertTrue(c.contains("Java_com_openminis_app_sandbox_PtyBridge_blockSigpipe("))
        assertTrue("must change only the calling thread's mask", c.contains("pthread_sigmask(SIG_UNBLOCK"))
        val unblockBody = c.substringAfter("PtyBridge_unblockSigpipe(").substringBefore("\n}\n")
        assertFalse("SIGQUIT must stay blocked for ART's signal catcher", unblockBody.contains("SIGQUIT"))
        assertFalse("SIGUSR1 must stay blocked for ART's signal catcher", unblockBody.contains("SIGUSR1"))
        val drain = unblockBody.indexOf("sigtimedwait(&set, NULL, &zero)")
        assertTrue("pending SIGPIPE is drained", drain >= 0)
        assertTrue(unblockBody.contains("sigpending(&pending) == 0 && sigismember(&pending, SIGPIPE) == 1"))
        assertTrue("drained BEFORE the unblock", drain < unblockBody.indexOf("pthread_sigmask(SIG_UNBLOCK"))
    }
}
