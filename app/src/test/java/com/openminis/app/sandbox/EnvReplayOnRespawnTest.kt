package com.openminis.app.sandbox

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EnvReplayOnRespawnTest {

    private fun read(rel: String): String {
        val f = File("src/main/java/com/openminis/app/sandbox/$rel")
        assertTrue("missing source: ${f.absolutePath}", f.exists())
        return f.readText()
    }

    @Test
    fun `boot is serialized by a mutex, not a bare isBooted check`() {
        val src = read("PRootKernel.kt")
        assertTrue(
            "PRootKernel.boot() must hold bootMutex — a bare isBooted check is a " +
                "check-then-act race, because isBooted flips only after the slow " +
                "install work finishes.",
            src.contains("bootMutex.withLock"),
        )
        assertTrue(
            "boot() must re-check isBooted once it holds the lock, or the caller " +
                "queued behind a completed boot will redo it.",
            src.contains("Already booted (post-lock)"),
        )
    }

    @Test
    fun `applyEnvironment records the snapshot before the liveness check`() {
        val src = read("PersistentShell.kt")
        val body = src.substringAfter("suspend fun applyEnvironment(")
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        val recordAt = body.indexOf("pendingEnv = envVars")
        val bailAt = body.indexOf("if (!isAlive) return")
        assertTrue("applyEnvironment must assign pendingEnv", recordAt >= 0)
        assertTrue("applyEnvironment must still bail out when dead", bailAt >= 0)
        assertTrue(
            "pendingEnv must be recorded BEFORE the !isAlive bail-out. If the " +
                "bail-out comes first, exports aimed at a dead shell are dropped " +
                "with no record, and the respawn runs the command bare — the " +
                "original first-turn bug.",
            recordAt < bailAt,
        )
    }

    @Test
    fun `ensureStarted replays the remembered environment`() {
        val src = read("PersistentShell.kt")
        assertTrue(
            "ensureStarted() must call replayEnvironmentIfAny(): a freshly spawned " +
                "/bin/sh has an empty environment, so without a replay the exports " +
                "written to the previous incarnation are gone.",
            src.contains("replayEnvironmentIfAny()"),
        )
        assertTrue(
            "the replay must re-export the snapshot",
            src.contains("writeEnvironment(pendingEnv"),
        )
    }
}
