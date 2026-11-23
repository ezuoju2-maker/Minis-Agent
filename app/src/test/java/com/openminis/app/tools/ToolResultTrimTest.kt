package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultTrimTest {

    @Test
    fun `small text returned unchanged same instance`() {
        val text = "line1\nline2\nline3"
        val r = ToolResultTrim.trim(text, maxLines = 100, maxChars = 1000)
        assertSame(text, r.text)
        assertEquals(3, r.originalLines)
        assertFalse(r.wasTrimmed)
    }

    @Test
    fun `large text keeps head and tail`() {
        val lines = (1..100).map { "line$it" }
        val r = ToolResultTrim.trim(lines.joinToString("\n"), maxLines = 20, maxChars = 100_000)
        assertTrue(r.wasTrimmed)
        assertTrue(r.text.contains("line1"))
        assertTrue(r.text.contains("line100"))
        assertTrue(r.text.contains("lines omitted"))
    }

    @Test
    fun `error in the middle is preserved`() {
        val lines = (1..100).map { if (it == 50) "FATAL: something broke" else "line$it" }
        val r = ToolResultTrim.trim(lines.joinToString("\n"), maxLines = 20, maxChars = 100_000)
        assertTrue(r.text.contains("FATAL: something broke"))
    }

    @Test
    fun `important detection covers common failure tokens`() {
        assertTrue(ToolResultTrim.isImportant("ERROR: connection failed"))
        assertTrue(ToolResultTrim.isImportant("java.lang.Exception: boom"))
        assertTrue(ToolResultTrim.isImportant("Traceback (most recent call last):"))
        assertTrue(ToolResultTrim.isImportant("FAILED tests"))
        assertFalse(ToolResultTrim.isImportant("all good, building module"))
    }

    @Test
    fun `char cap is respected and tail survives`() {
        val lines = (1..50).map { "verylongline_number_${it}_padding_padding_padding" }
        val r = ToolResultTrim.trim(lines.joinToString("\n"), maxLines = 1000, maxChars = 300)
        assertTrue(r.text.length <= 300)
        assertTrue(r.text.contains("number_50"))
    }

    @Test
    fun `empty text is stable`() {
        val r = ToolResultTrim.trim("")
        assertEquals(0, r.originalLines)
        assertFalse(r.wasTrimmed)
    }
}
