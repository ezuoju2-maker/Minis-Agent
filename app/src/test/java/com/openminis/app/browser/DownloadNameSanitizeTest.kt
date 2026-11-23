package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [security] A download name comes from page-controlled content
 * (`Content-Disposition` / blob name). `uniqueFile` builds the destination as
 * `File(workspace, name)`, so an unsanitized name with `../` or a separator
 * could escape the per-session workspace. These tests pin the pure helper so
 * the isolation guarantee survives refactors.
 */
class DownloadNameSanitizeTest {

    @Test
    fun `normal names pass through unchanged`() {
        assertEquals("report.pdf", BrowserTabPool.sanitizeDownloadName("report.pdf"))
        assertEquals("my photo 2026.png", BrowserTabPool.sanitizeDownloadName("my photo 2026.png"))
        assertEquals("archive.tar.gz", BrowserTabPool.sanitizeDownloadName("archive.tar.gz"))
    }

    @Test
    fun `unix parent traversal is reduced to the last component`() {
        assertEquals("evil.txt", BrowserTabPool.sanitizeDownloadName("../../evil.txt"))
        assertEquals("passwd", BrowserTabPool.sanitizeDownloadName("/etc/passwd"))
    }

    @Test
    fun `windows separators and drive letters are stripped`() {
        assertEquals("evil.exe", BrowserTabPool.sanitizeDownloadName("C:\\Windows\\evil.exe"))
        assertEquals("evil.exe", BrowserTabPool.sanitizeDownloadName("..\\..\\evil.exe"))
    }

    @Test
    fun `dot and empty names fall back to a safe default`() {
        assertEquals("download", BrowserTabPool.sanitizeDownloadName(".."))
        assertEquals("download", BrowserTabPool.sanitizeDownloadName("."))
        assertEquals("download", BrowserTabPool.sanitizeDownloadName("   "))
    }

    @Test
    fun `overlong names are bounded`() {
        val long = "a".repeat(500)
        assertEquals(200, BrowserTabPool.sanitizeDownloadName(long).length)
    }
}
