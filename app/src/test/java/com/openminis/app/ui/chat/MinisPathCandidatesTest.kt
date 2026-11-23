package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MinisPathCandidatesTest {

    private fun candidates(path: String) = ChatLinkResolver.minisPathCandidates(path)

    private val cjkDir = "%E7%A4%BA%E4%BE%8B%E5%87%AD%E8%AF%81%E7%9B%AE%E5%BD%95"
    private val cjkFile = "%E7%A4%BA%E4%BE%8B-2026-03-001.pdf"


    @Test
    fun `single-encoded CJK path decodes in one pass`() {
        val out = candidates("workspace/$cjkDir/$cjkFile")
        assertEquals("workspace/示例凭证目录/示例-2026-03-001.pdf", out.first())
    }

    @Test
    fun `a fully-decoded path is returned unchanged`() {
        val raw = "workspace/示例凭证目录/示例-2026-03-001.pdf"
        assertEquals(raw, candidates(raw).first())
    }

    @Test
    fun `an ASCII path yields exactly one candidate`() {
        val out = candidates("workspace/sample-vouchers/report.pdf")
        assertEquals(listOf("workspace/sample-vouchers/report.pdf"), out)
    }


    @Test
    fun `double-encoded CJK path is recovered by the second candidate`() {
        val doubled = "workspace/" +
            cjkDir.replace("%", "%25") + "/" + cjkFile.replace("%", "%25")
        val out = candidates(doubled)
        assertTrue("must offer a second candidate", out.size >= 2)
        assertEquals(
            "workspace/示例凭证目录/示例-2026-03-001.pdf",
            out[1],
        )
    }

    @Test
    fun `double-encoded ASCII-dir path is also recovered`() {
        val doubled = "workspace/sample-vouchers/" +
            "%E7%A4%BA%E4%BE%8B-2026-03-002.pdf".replace("%", "%25")
        val out = candidates(doubled)
        assertTrue(out.size >= 2)
        assertEquals("workspace/sample-vouchers/示例-2026-03-002.pdf", out[1])
    }

    @Test
    fun `the single-decoded candidate always comes first`() {
        val out = candidates("workspace/100%25-done/report.pdf")
        assertEquals("workspace/100%-done/report.pdf", out.first())
    }


    @Test
    fun `plus in a path segment is not turned into a space`() {
        val out = candidates("workspace/a+b/file.pdf")
        assertEquals("workspace/a+b/file.pdf", out.first())
        assertTrue(
            "no candidate may contain 'a b'",
            out.none { it.contains("a b") },
        )
    }

    @Test
    fun `encoded space still decodes to a space`() {
        assertEquals("workspace/my dir/file.pdf", candidates("workspace/my%20dir/file.pdf").first())
    }


    @Test
    fun `a malformed percent escape does not throw`() {
        val out = candidates("workspace/broken%ZZ/file.pdf")
        assertTrue(out.isNotEmpty())
    }

    @Test
    fun `an empty path yields one empty candidate`() {
        assertEquals(listOf(""), candidates(""))
    }

    @Test
    fun `hash in a filename survives decoding`() {
        val out = candidates("attachments/foo %23China.mp4")
        assertEquals("attachments/foo #China.mp4", out.first())
    }
}
