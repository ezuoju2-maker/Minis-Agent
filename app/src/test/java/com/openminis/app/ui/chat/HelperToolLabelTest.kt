package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class HelperToolLabelTest {

    @Test
    fun `underscores and hyphens become spaces with a leading capital`() {
        assertEquals("Browser use", helperToolLabel("browser_use"))
        assertEquals("Shell execute", helperToolLabel("shell_execute"))
        assertEquals("File write", helperToolLabel("file_write"))
        assertEquals("Browser use", helperToolLabel("browser-use"))
    }

    @Test
    fun `single words and odd input survive`() {
        assertEquals("Compact", helperToolLabel("compact"))
        assertEquals("", helperToolLabel(""))
        assertEquals("HTTP fetch", helperToolLabel("HTTP_fetch"))
    }

    @Test
    fun `it is not the Minis-is-using vocabulary`() {
        assertEquals("browser", toolDisplayName("browser_use"))
        assertEquals("Browser use", helperToolLabel("browser_use"))
        assertEquals("terminal", toolDisplayName("shell_execute"))
        assertEquals("Shell execute", helperToolLabel("shell_execute"))
    }
}
