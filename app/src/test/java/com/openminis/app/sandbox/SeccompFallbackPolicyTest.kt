package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeccompFallbackPolicyTest {

    private fun decide(
        exit: Int?,
        ms: Long = 120,
        output: Boolean = false,
        retried: Boolean = false,
    ) = SeccompFallbackPolicy.shouldRetryWithoutSeccomp(exit, ms, output, retried)


    @Test
    fun `early SIGBUS with no output retries — the reported apk --version failure`() {
        assertTrue(decide(exit = 135, ms = 40))
    }

    @Test
    fun `early SIGSEGV with no output retries — the reported apk update failure`() {
        assertTrue(decide(exit = 139, ms = 80))
    }

    @Test
    fun `SIGSYS retries — the seccomp filter killing the child directly`() {
        assertTrue(decide(exit = 159, ms = 10))
    }

    @Test
    fun `SIGILL retries — a mis-mapped text segment lands on garbage instructions`() {
        assertTrue(decide(exit = 132, ms = 10))
    }


    @Test
    fun `a normal non-zero exit never retries`() {
        for (code in listOf(1, 2, 126, 127)) {
            assertFalse("exit=$code must not retry", decide(exit = code))
        }
    }

    @Test
    fun `success never retries`() {
        assertFalse(decide(exit = 0))
    }

    @Test
    fun `a late crash never retries — it may have had side effects`() {
        assertFalse(decide(exit = 139, ms = SeccompFallbackPolicy.EARLY_DEATH_MS))
        assertFalse(decide(exit = 139, ms = 60_000))
    }

    @Test
    fun `a crash after output never retries — the program had begun running`() {
        assertTrue(decide(exit = 139, ms = 40, output = false))
        assertFalse(decide(exit = 139, ms = 40, output = true))
    }

    @Test
    fun `our own kill signals never retry`() {
        for (code in listOf(130, 137, 143)) {
            assertFalse("exit=$code must not retry", decide(exit = code, ms = 5))
        }
    }

    @Test
    fun `SIGABRT never retries — that is the program aborting itself`() {
        assertFalse(decide(exit = 134, ms = 5))
    }

    @Test
    fun `the shell executor timeout code never retries`() {
        assertFalse(decide(exit = 124, ms = 5))
        assertFalse(decide(exit = -1, ms = 5))
    }

    @Test
    fun `an unobservable exit never retries`() {
        assertFalse(decide(exit = null, ms = 5))
    }


    @Test
    fun `the retry is spent only once even if the crash repeats identically`() {
        assertTrue(decide(exit = 135, ms = 40, retried = false))
        assertFalse(decide(exit = 135, ms = 40, retried = true))
    }


    @Test
    fun `retryable codes are exactly the four fatal-signal codes`() {
        assertEquals(setOf(132, 135, 139, 159), SeccompFallbackPolicy.RETRYABLE_EXIT_CODES)
    }

    @Test
    fun `every retryable code has a signal name and non-retryable ones do not`() {
        for (code in SeccompFallbackPolicy.RETRYABLE_EXIT_CODES) {
            assertTrue("no name for $code", SeccompFallbackPolicy.signalName(code) != null)
        }
        assertNull(SeccompFallbackPolicy.signalName(0))
        assertNull(SeccompFallbackPolicy.signalName(137))
        assertNull(SeccompFallbackPolicy.signalName(null))
    }

    @Test
    fun `the retry log line is greppable and names the signal`() {
        val line = SeccompFallbackPolicy.retryLogLine(135, 42, "shell command")
        assertTrue(line.contains("[proot-retry]"))
        assertTrue(line.contains("SIGBUS"))
        assertTrue(line.contains("PROOT_NO_SECCOMP=1"))
        assertTrue(line.contains("shell command"))
    }

    @Test
    fun `the boundary is exclusive so the threshold itself does not retry`() {
        assertTrue(decide(exit = 135, ms = SeccompFallbackPolicy.EARLY_DEATH_MS - 1))
        assertFalse(decide(exit = 135, ms = SeccompFallbackPolicy.EARLY_DEATH_MS))
    }
}
