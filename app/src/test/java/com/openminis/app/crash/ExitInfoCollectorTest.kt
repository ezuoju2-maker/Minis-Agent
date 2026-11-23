package com.openminis.app.crash

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitInfoCollectorTest {


    @Test
    fun `native crashes are collected — the reason this exists`() {
        assertTrue(ExitInfoCollector.isInteresting(5))
    }

    @Test
    fun `JVM crashes and ANRs are collected too`() {
        assertTrue("REASON_CRASH", ExitInfoCollector.isInteresting(4))
        assertTrue("REASON_ANR", ExitInfoCollector.isInteresting(6))
    }

    @Test
    fun `ordinary exits are not written to the logs dir`() {
        assertFalse("REASON_EXIT_SELF", ExitInfoCollector.isInteresting(1))
        assertFalse("REASON_SIGNALED", ExitInfoCollector.isInteresting(2))
        assertFalse("REASON_LOW_MEMORY", ExitInfoCollector.isInteresting(3))
        assertFalse("REASON_USER_REQUESTED", ExitInfoCollector.isInteresting(10))
    }

    @Test
    fun `reason names are stable for the collected kinds`() {
        assertEquals("CRASH_NATIVE", ExitInfoCollector.reasonName(5))
        assertEquals("CRASH", ExitInfoCollector.reasonName(4))
        assertEquals("ANR", ExitInfoCollector.reasonName(6))
    }

    @Test
    fun `an unknown reason still renders rather than throwing`() {
        assertEquals("reason=999", ExitInfoCollector.reasonName(999))
    }

    @Test
    fun `the trace cap is bounded but large enough to carry a tombstone head`() {
        assertTrue(ExitInfoCollector.MAX_TRACE_BYTES >= 64 * 1024)
        assertTrue(ExitInfoCollector.MAX_TRACE_BYTES <= 1024 * 1024)
    }


    private val src: String by lazy { ProductionSources.read("crash/ExitInfoCollector.kt") }

    @Test
    fun `the collector asks the platform for exit records`() {
        assertTrue(
            "getHistoricalProcessExitReasons is the only permission-free route to a tombstone",
            src.contains("getHistoricalProcessExitReasons"),
        )
    }

    @Test
    fun `the abort message and the tombstone are both read`() {
        assertTrue(
            "description IS the abort message — the field the issue was filed for",
            src.contains("info.description"),
        )
        assertTrue(
            "traceInputStream carries the full tombstone incl. the memory map",
            src.contains("traceInputStream"),
        )
    }

    @Test
    fun `de-duplication keeps a persisted high-water mark`() {
        assertTrue(src.contains(ExitInfoCollector.KEY_HIGH_WATER))
        assertTrue("the mark must be persisted, not just held in memory", src.contains("getSharedPreferences"))
        assertTrue("…and advanced", src.contains("putLong(KEY_HIGH_WATER"))
        assertTrue("…and consulted", src.contains("if (info.timestamp <= highWater) continue"))
    }

    @Test
    fun `reports land where the log UI and the other crash writers already look`() {
        assertTrue("same dir as crash-*.log / native-crash-*.log", src.contains("File(context.filesDir, \"logs\")"))
        assertTrue("same .log extension LogManagementScreen filters for", src.contains("exitinfo-"))
    }

    @Test
    fun `collection is gated on the API that provides the trace`() {
        assertTrue(
            "getTraceInputStream carries the tombstone from API 31 (S)",
            src.contains("Build.VERSION_CODES.S"),
        )
    }

    @Test
    fun `a diagnostic must never crash the app it is diagnosing`() {
        assertTrue("the whole collect() body is guarded", src.contains("catch (t: Throwable)"))
    }
}
