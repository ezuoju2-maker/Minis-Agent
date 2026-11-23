package com.openminis.app.tools

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [code-ability] file_edit's real execute path appends a bounded diff preview;
 * the pure edit() default keeps its exact-output contract for existing tests.
 */
class FileEditDiffTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(text: String): File =
        File(tmp.root, "f.txt").apply { writeText(text) }

    @Test
    fun `default edit has no diff (backward compatible)`() {
        val f = file("alpha\nbeta\ngamma")
        val r = FileEditTool.edit(f, "/p", "beta", "BETA", false, "t")
        assertTrue(r.output.startsWith("Edited /p"))
        assertTrue(!r.output.contains("Changes:"))
    }

    @Test
    fun `includeDiff appends change preview with removed and added lines`() {
        val f = file("alpha\nbeta\ngamma")
        val r = FileEditTool.edit(f, "/p", "beta", "BETA", false, "t", includeDiff = true)
        assertTrue(r.output.contains("Changes:"))
        assertTrue(r.output.contains("beta"))
        assertTrue(r.output.contains("BETA"))
    }

    @Test
    fun `diff preview is bounded even for many changed lines`() {
        val before = (1..200).joinToString("\n") { "line$it" }
        val f = file(before)
        val r = FileEditTool.edit(f, "/p", "line1\nline2", "CHANGED\nALSO", false, "t", includeDiff = true)
        assertTrue(r.output.contains("Changes:"))
        assertTrue(r.output.length < 2_000)
    }
}
