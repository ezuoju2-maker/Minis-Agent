package com.openminis.app.sandbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotFlagParityTest {

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing source: ${f.absolutePath}", f.exists())
        return f.readText()
    }

    private val kernelSrc by lazy { src("src/main/java/com/openminis/app/sandbox/PRootKernel.kt") }
    private val terminalSrc by lazy { src("src/main/java/com/openminis/app/sandbox/TerminalSession.kt") }

    private fun builderBody(src: String, marker: String, end: String): String {
        val after = src.substringAfter(marker)
        assertTrue("could not locate $marker", after.isNotEmpty() && after != src)
        return after.substringBefore(end)
    }

    private val kernelBuilder by lazy {
        builderBody(kernelSrc, "fun buildProotCommand", "\n    fun ")
    }

    private val terminalBuilder by lazy {
        builderBody(terminalSrc, "private fun buildInteractiveCommand", "\n}")
    }

    @Test
    fun `both builders emulate rtnetlink`() {
        assertTrue(
            "PRootKernel.buildProotCommand must pass --fake-netlink",
            kernelBuilder.contains("""cmd.add("--fake-netlink")"""),
        )
        assertTrue(
            "TerminalSession.buildInteractiveCommand must pass --fake-netlink too, " +
                "or the Terminal and the agent disagree about whether the sandbox " +
                "has network interfaces",
            terminalBuilder.contains("""cmd.add("--fake-netlink")"""),
        )
    }

    @Test
    fun `the isolation flags match across both builders`() {
        for (flag in listOf("\"-0\"", "\"--link2symlink\"")) {
            assertTrue("PRootKernel must pass $flag", kernelBuilder.contains("cmd.add($flag)"))
            assertTrue("TerminalSession must pass $flag", terminalBuilder.contains("cmd.add($flag)"))
        }
    }

    @Test
    fun `both builders bind the same kernel filesystems and the shared mounts`() {
        for (path in listOf("\"/dev\"", "\"/proc\"", "\"/sys\"")) {
            assertTrue("PRootKernel must bind $path", kernelBuilder.contains(path))
            assertTrue("TerminalSession must bind $path", terminalBuilder.contains(path))
        }
        assertTrue(
            "PRootKernel must replay the user bind mounts",
            kernelBuilder.contains("in bindMounts"),
        )
        assertTrue(
            "TerminalSession must build its mounts with SessionMounts, like the agent's shells",
            terminalBuilder.contains("SessionMounts.toProotArgs(SessionMounts.forContext(context, sessionId).mounts)"),
        )
        assertTrue(
            "TerminalSession must not replay the global table",
            !terminalBuilder.contains("in PRootKernel.bindMounts)"),
        )
    }

    @Test
    fun `both builders register the same native-offload handlers`() {
        for (body in listOf(kernelBuilder, terminalBuilder)) {
            assertTrue(
                "--native-offload must be wired in both, or minis-open / offload " +
                    "tools work in one shell only",
                body.contains("--native-offload="),
            )
        }
    }

    @Test
    fun `every place that assembles a proot argv is accounted for`() {
        val known = listOf(
            "PRootKernel.kt" to "fun buildProotCommand",
            "TerminalSession.kt" to "fun buildInteractiveCommand",
            "FreshProcessShell.kt" to "fun spawn(",
        )
        for ((file, decl) in known) {
            val body = src("src/main/java/com/openminis/app/sandbox/$file")
            assertTrue("$file must still declare its builder ($decl)", body.contains(decl))
        }
        val all = kernelSrc + terminalSrc
        val builders = Regex("""fun build(Proot|Interactive)Command""").findAll(all).count()
        assertEquals("the two named builders", 2, builders)
    }

    @Test
    fun `the fresh-process builder carries the isolation flags too`() {
        val fresh = src("src/main/java/com/openminis/app/sandbox/FreshProcessShell.kt")
        for (flag in listOf("\"-0\"", "\"--link2symlink\"", "--fake-netlink", "--native-offload=")) {
            assertTrue("FreshProcessShell must pass $flag", fresh.contains(flag))
        }
        for (path in listOf("\"/dev\"", "\"/proc\"", "\"/sys\"")) {
            assertTrue("FreshProcessShell must bind $path", fresh.contains(path))
        }
        assertTrue(
            "and replay the session bind mounts",
            fresh.contains("in sessionBindMounts"),
        )
    }
}
