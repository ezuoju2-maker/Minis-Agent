package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TableGridAlignmentTest {

    private val table by lazy {
        val f = File("src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt")
        assertTrue("missing ${f.absolutePath}", f.exists())
        f.readText()
            .substringAfter("private fun RenderTable(block: MdBlock.Table)")
            .substringBefore("\nprivate fun ")
            .lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") }
            .joinToString("\n")
    }

    @Test
    fun `row heights are settled before any cell is measured`() {
        val rows = table.indexOf("maxIntrinsicHeight(colW[colIdx])")
        val measure = table.indexOf(CELL_MEASURE)
        assertTrue("row height must come from every cell's intrinsic height", rows >= 0)
        assertTrue("…and before the single measure() of each cell", measure > rows)
    }

    @Test
    fun `every cell is at least as tall as its row, so its dividers reach the row edges`() {
        val call = table.substringAfter(CELL_MEASURE).substringBefore("\n                    )")
        assertTrue(call.contains("minHeight = rowHeights[rowIdx]"))
        assertFalse(
            "fixedWidth alone lets a cell stay shorter than its row — the broken grid",
            table.contains("Constraints.fixedWidth(colW)"),
        )
    }

    @Test
    fun `cells use the body font size, which follows the in-app font scale`() {
        assertTrue(table.contains("val cellFontSize = BaseFontSize"))
        assertTrue(table.contains("fontSize = cellFontSize"))
        assertTrue("inline math must match the cell text", table.contains("rememberKatexInlineContent(cellFontSize"))
        assertFalse("a fixed size ignores LocalMarkdownFontScale", table.contains("14.sp"))
    }

    private companion object {
        const val CELL_MEASURE = "measurables[rowIdx * colCount + colIdx].measure("
    }
}
