package com.openminis.app.sandbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellExecutionStrategyTest {


    @Test
    fun `the fresh process path is what users now get`() {
        assertEquals(ShellExecutionStrategy.FRESH_PROCESS, ShellExecutionStrategy.DEFAULT)
    }

    @Test
    fun `the warm shell survives as a rollback path`() {
        assertTrue(ShellExecutionStrategy.entries.contains(ShellExecutionStrategy.WARM_SHELL))
        assertTrue(ShellExecutionStrategy.entries.contains(ShellExecutionStrategy.FRESH_PROCESS))
    }


    @Test
    fun `only the warm shell needs an in-band marker`() {
        assertTrue(ShellExecutionStrategy.needsInBandMarker(ShellExecutionStrategy.WARM_SHELL))
        assertFalse(ShellExecutionStrategy.needsInBandMarker(ShellExecutionStrategy.FRESH_PROCESS))
    }

    @Test
    fun `only the warm shell must be recycled after a timeout`() {
        assertTrue(ShellExecutionStrategy.recyclesShellOnTimeout(ShellExecutionStrategy.WARM_SHELL))
        assertFalse(ShellExecutionStrategy.recyclesShellOnTimeout(ShellExecutionStrategy.FRESH_PROCESS))
    }


    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    private val freshSrc by lazy { src("src/main/java/com/openminis/app/sandbox/FreshProcessShell.kt") }

    @Test
    fun `the fresh path never emits a completion marker`() {
        val code = freshSrc.lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
        assertFalse("must not append __MINIS_DONE__", code.contains("__MINIS_DONE_"))
        assertFalse("must not carry bytes between reads", code.contains("carryOver"))
    }

    @Test
    fun `completion comes from the process, not from parsing`() {
        assertTrue("exit status is the completion signal", freshSrc.contains("process.waitFor("))
        assertTrue("and reads run to EOF", freshSrc.contains("if (n < 0) break"))
    }

    @Test
    fun `the command is passed as an argument, never interpolated`() {
        val wrap = src("src/main/java/com/openminis/app/sandbox/ForegroundCommandGroup.kt")
            .substringAfter("fun wrapForFreshProcess(")
            .substringBefore("\n\n")
        assertTrue("command must be its own argv entry", wrap.contains(""""sh", command, statusPath"""))
        assertFalse("and never spliced into a string", wrap.contains("\$" + "command"))
    }

    @Test
    fun `a timeout kills the process and keeps partial output`() {
        val body = freshSrc.substringAfter("if (collected == null)").substringBefore("Pair(output.toString()")
        assertTrue("must kill", body.contains("destroyForcibly()"))
        assertTrue("must report 124", body.contains("124"))
        assertTrue("must keep what the command produced", body.contains("partial"))
    }

    @Test
    fun `the sandbox is configured identically to the warm path`() {
        for (flag in listOf("\"-0\"", "\"--link2symlink\"", "--fake-netlink", "--native-offload=")) {
            assertTrue("fresh path must pass $flag", freshSrc.contains(flag))
        }
        for (envKey in listOf("PROOT_LOADER", "PROOT_TMP_DIR", "LD_LIBRARY_PATH", "MINIS_CHAT_SESSION_ID")) {
            assertTrue("fresh path must set $envKey", freshSrc.contains(envKey))
        }
    }

    @Test
    fun `user env vars are real environment entries, not exports on a shared stdin`() {
        assertTrue(freshSrc.contains("for ((k, v) in envVars) env[k] = v"))
    }


    @Test
    fun `the fresh path runs its command as a process-group leader`() {
        assertTrue(freshSrc.contains("ForegroundCommandGroup.wrapForFreshProcess(command, statusGuestPath)"))
        val wrap = src("src/main/java/com/openminis/app/sandbox/ForegroundCommandGroup.kt")
            .substringAfter("fun wrapForFreshProcess(")
            .substringBefore("\n\n")
        assertTrue("must go through setsid", wrap.contains("listOf(SETSID"))
    }

    @Test
    fun `a timeout kills the command's group, not the proot a daemon depends on`() {
        val watchdog = freshSrc.substringAfter("val watchdog =").substringBefore("val exitCode =")
        assertTrue(watchdog.contains("killCommand(process, readEof)"))
        val kill = freshSrc.substringAfter("private fun killCommand(").substringBefore("\n    /**")
        assertTrue("the group comes from the runner's pid file", kill.contains("readPgid("))
        assertTrue(
            "a negative pid needs Os.kill: Process.sendSignal drops pid <= 0",
            kill.contains("android.system.Os.kill(-pgid"),
        )
        val known = kill.substringAfter("} else {").substringBefore("// Belt and braces")
        assertFalse("with the group known, proot is not destroyed", known.contains("destroyForcibly"))
        assertTrue(
            "and the stream is closed so a blocked read cannot outlive the deadline",
            kill.contains("process.inputStream.close()"),
        )
    }

    @Test
    fun `the pid is read in a way API 26 actually supports`() {
        val body = freshSrc.substringAfter("private fun killProcessTree").substringBefore("\n    }")
        assertFalse("must not call the Java 9 API", body.contains("process.pid()"))
        assertTrue("reads the platform field instead", body.contains("""getDeclaredField("pid")"""))
        assertTrue("and degrades rather than throwing", body.contains("?: return"))
    }

    @Test
    fun `the descendant walk is depth-bounded`() {
        assertTrue(freshSrc.contains("MAX_TREE_DEPTH"))
        assertTrue(freshSrc.contains("if (depth > MAX_TREE_DEPTH) return emptyList()"))
    }


    private val coordSrc by lazy { src("src/main/java/com/openminis/app/sandbox/ExecutionCoordinator.kt") }

    @Test
    fun `execute dispatches on the strategy before taking a lease`() {
        val body = coordSrc.substringAfter("val lease = acquireShellLease")
        assertTrue("the branch must exist", coordSrc.contains("executionStrategy == ShellExecutionStrategy.FRESH_PROCESS"))
        val branchIdx = coordSrc.indexOf("executionStrategy == ShellExecutionStrategy.FRESH_PROCESS")
        val leaseIdx = coordSrc.indexOf("val lease = acquireShellLease")
        assertTrue("dispatch must come before the lease", branchIdx in 1 until leaseIdx)
        assertTrue(body.isNotEmpty())
    }

    @Test
    fun `the fresh path reuses the same bind mounts and env source`() {
        val body = coordSrc.substringAfter("private suspend fun executeFresh").substringBefore("\n    }")
        assertTrue("same mounts", body.contains("buildSessionBindMounts("))
        assertTrue("same env repository", body.contains("envVarRepository?.allAsDict()"))
        assertTrue("same sanitising", body.contains("TerminalSanitizer.sanitize("))
        assertTrue("same truncation", body.contains("TerminalSanitizer.truncateIfNeeded("))
    }

    @Test
    fun `the strategy is switchable at runtime for the comparison harness`() {
        assertTrue(coordSrc.contains("var executionStrategy: ShellExecutionStrategy"))
        assertTrue("must be safe to flip from another thread", coordSrc.contains("@Volatile"))
    }


    @Test
    fun `a fresh command is reachable by the Stop button`() {
        assertTrue("must be registered before running", coordSrc.contains("freshShells.compute(sessionId)"))
        assertTrue("and stopped when the user asks", coordSrc.contains("stopFreshShells(sessionId)"))
        assertTrue("including the stop-everything path", coordSrc.contains("ArrayList(freshShells.keys).forEach"))
    }

    @Test
    fun `the registry cannot leak an entry when a command fails`() {
        val body = coordSrc.substringAfter("private suspend fun executeFresh").substringBefore("\n    }")
        assertTrue("removal must be in a finally", body.contains("} finally {"))
        assertTrue(body.contains("freshShells.computeIfPresent(sessionId)"))
    }

    @Test
    fun `Stop kills the whole tree, the same way a timeout does`() {
        val body = freshSrc.substringAfter("    fun stop() {").substringBefore("\n    }")
        assertTrue(body.contains("killCommand(p, readEof = null)"))
        assertFalse("proot alive says nothing about the command", body.contains("if (!p.isAlive)"))
    }

    @Test
    fun `a Stop racing a starting command still takes effect`() {
        assertTrue(freshSrc.contains("if (stopped) {"))
        assertTrue("the live handle must be published for stop() to find", freshSrc.contains("live = process"))
        assertTrue("and both must be safe across threads", freshSrc.contains("@Volatile private var stopped"))
    }

    @Test
    fun `a cancellation is not reported as a timeout`() {
        assertTrue(freshSrc.contains("const val CANCELLED_EXIT = 130"))
        val body = freshSrc.substringBefore("if (timedOut.get())")
            .substringAfterLast("if (stopped) {")
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertTrue("cancellation returns its own code", body.contains("CANCELLED_EXIT"))
        assertFalse("and never 124", body.contains("124"))
    }


    @Test
    fun `the process is never awaited without a bound`() {
        val code = freshSrc.lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
        val bare = Regex("""waitFor\(\s*\)""").findAll(code).count()
        assertEquals("no unbounded waitFor() may remain", 0, bare)
        assertTrue(
            "the bounded wait must be the shared helper",
            freshSrc.contains("private fun resolveExitCode("),
        )
        assertTrue(
            "and it must pass a real deadline",
            freshSrc.contains("process.waitFor(REAP_GRACE_MS"),
        )
    }

    @Test
    fun `a finished command leaves a daemon's tracer running`() {
        val body = freshSrc.substringAfter("private fun resolveExitCode(").substringBefore("private fun readStatus(")
        assertTrue(body.contains("if (closedOnStatus) return readStatus(statusFile) ?: 0"))
        assertFalse(body.contains("killProcessTree"))
        assertFalse(body.contains("destroyForcibly"))
    }

    @Test
    fun `the group kill does not depend on reading proc`() {
        val body = freshSrc.substringAfter("private fun killProcessTree").substringBefore("\n    /**")
        val groupKill = body.indexOf("sendSignal(-pid, 9)")
        val walk = body.indexOf("descendantsOf(pid)")
        assertTrue("the group kill must be present", groupKill >= 0)
        assertTrue("the walk must be present", walk >= 0)
        assertTrue("the group kill must come first", groupKill < walk)
    }
}
