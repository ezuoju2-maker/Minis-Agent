package com.openminis.app.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatEchoFilterTest {

    private fun isEcho(rawLine: String): Boolean {
        val slashIdx = rawLine.indexOf('/')
        val parenIdx = if (slashIdx >= 0) rawLine.indexOf('(', slashIdx) else -1
        if (slashIdx < 0 || parenIdx <= slashIdx) return false
        val tag = rawLine.substring(slashIdx + 1, parenIdx).trim()
        return tag.startsWith("Minis.") || tag == "AppLogger" ||
            tag == "System.out" || tag == "System.err"
    }

    @Test
    fun `stdout and stderr echoes are dropped`() {
        assertTrue(isEcho("09-15 13:59:56.292 I/System.out(26955): [T-HANG-DIAG] tick=37230"))
        assertTrue(isEcho("09-15 13:59:56.292 W/System.err(26955): stack trace line"))
    }

    @Test
    fun `our own AppLogger tags stay filtered`() {
        assertTrue(isEcho("09-15 13:59:40.304 I/Minis.AppLogger(26955): session started"))
        assertTrue(isEcho("09-15 13:59:40.304 I/Minis.ChatVMStream(26955): send"))
        assertTrue(isEcho("09-15 13:59:40.304 I/AppLogger(26955): x"))
    }

    @Test
    fun `framework and other-app lines are still captured`() {
        assertFalse(isEcho("09-15 13:59:43.310 I/m.openminis.app(26955): NativeAlloc concurrent mark compact GC freed 204MB"))
        assertFalse(isEcho("09-15 14:00:07.563 I/HangDetector(26955): hang count reset after quiet period"))
        assertFalse(isEcho("09-15 14:00:01.000 I/Choreographer(26955): Davey! duration=1526ms"))
    }

    @Test
    fun `a tag that merely starts with System is not treated as an echo`() {
        assertFalse(isEcho("09-15 14:00:01.000 I/SystemUI(26955): something"))
        assertFalse(isEcho("09-15 14:00:01.000 I/SystemClock(26955): something"))
    }

    @Test
    fun `a malformed line is kept rather than silently dropped`() {
        assertFalse(isEcho("no tag here at all"))
        assertFalse(isEcho(""))
    }
}
