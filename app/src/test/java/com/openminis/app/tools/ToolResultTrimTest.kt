package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultTrimTest {

    @Test
    fun `short output is returned untouched`() {
        val text = "ok\ndone"
        val trimmed = ToolResultTrim.trim(text, maxLines = 10, maxChars = 100)
        assertFalse(trimmed.wasTrimmed)
        assertEquals(text, trimmed.text)
        assertEquals(2, trimmed.originalLines)
    }

    @Test
    fun `middle is dropped but head tail and errors survive`() {
        val lines = ArrayList<String>()
        for (i in 1..400) lines.add("line " + i)
        lines[200] = "ERROR: disk full at step 200"
        val trimmed = ToolResultTrim.trim(lines.joinToString("\n"), maxLines = 40, maxChars = 4000)
        assertTrue(trimmed.wasTrimmed)
        assertTrue(trimmed.text.contains("line 1\n"))
        assertTrue(trimmed.text.contains("line 400"))
        assertTrue(trimmed.text.contains("ERROR: disk full at step 200"))
        assertEquals(400, trimmed.originalLines)
    }

    @Test
    fun `hard line cap is never exceeded by payload lines`() {
        val lines = ArrayList<String>()
        for (i in 1..50) lines.add("l" + i)
        val trimmed = ToolResultTrim.trim(lines.joinToString("\n"), maxLines = 1, maxChars = 1000)
        val payload = trimmed.text.lines().filter { !it.startsWith("\u2026") }
        assertEquals(1, payload.size)
        assertEquals(1, trimmed.keptLines)
    }

    @Test
    fun `huge single line keeps both ends`() {
        val body = "START" + "x".repeat(50_000) + "END"
        val trimmed = ToolResultTrim.trim(body, maxLines = 10, maxChars = 400)
        assertTrue(trimmed.text.startsWith("START"))
        assertTrue(trimmed.text.endsWith("END"))
        assertTrue(trimmed.text.length <= 400)
        assertTrue(trimmed.text.contains("chars omitted"))
    }

    @Test
    fun `notice reports what was dropped`() {
        val lines = (1..1000).joinToString("\n") { "row " + it }
        val trimmed = ToolResultTrim.trim(lines, maxLines = 100, maxChars = 5000)
        val notice = trimmed.noticeText()
        assertTrue(notice.contains("kept " + trimmed.keptLines + " of 1000"))
        assertTrue(trimmed.omittedPercent > 50)
    }

    @Test
    fun `chinese error lines survive trimming`() {
        val lines = ArrayList<String>()
        for (i in 1..300) lines.add("第 " + i + " 行")
        lines[150] = "错误：命令未找到：apt-get"
        val trimmed = ToolResultTrim.trim(lines.joinToString("\n"), maxLines = 30, maxChars = 3000)
        assertTrue(trimmed.wasTrimmed)
        assertTrue(trimmed.text.contains("错误：命令未找到：apt-get"))
    }
}
