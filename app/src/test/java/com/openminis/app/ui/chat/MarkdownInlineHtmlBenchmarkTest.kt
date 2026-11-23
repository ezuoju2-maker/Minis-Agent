package com.openminis.app.ui.chat

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class MarkdownInlineHtmlBenchmarkTest {

    private val prose = (1..400).joinToString("\n\n") {
        "第 $it 段：这是一段**加粗**和*斜体*混合的正文，含 `inline code`、[链接](https://e.x/$it) 与 ~~删除~~，" +
            "English text follows to make the line long enough to wrap twice on a phone screen."
    }
    private val table = "| 名称 | 说明 | 数值 | 备注 |\n|---|---|---|---|\n" +
        (1..400).joinToString("\n") { "| 项目$it | **说明**文本 $it | ${it * 3} | `code$it` 备注文字 |" }
    private val proseWithLt = (1..400).joinToString("\n\n") {
        "第 $it 段：若 1 < 2 且 3 > 1，则 a<b 成立；**加粗** 与 `x<y` 以及 <div>块</div> 都按字面显示。"
    }
    private val tableWithBr = "| 名称 | 说明 |\n|---|---|\n" +
        (1..400).joinToString("\n") { "| 项目$it | 第一行<br>第二行 <b>粗$it</b> |" }

    private fun medianMs(text: String, runs: Int = 101): Double {
        repeat(40) { chatSearchMarkdownPieces(text) }
        val times = (1..runs).map {
            val t0 = System.nanoTime()
            chatSearchMarkdownPieces(text)
            (System.nanoTime() - t0) / 1e6
        }.sorted()
        return times[runs / 2]
    }

    @Test
    fun `parse timings`() {
        assumeTrue(System.getenv("MD_BENCH") == "1")
        val label = System.getenv("MD_BENCH_LABEL") ?: "run"
        val rows = buildList {
            add("prose 400 paragraphs (${prose.length} chars, no '<')" to medianMs(prose))
            add("table 400 rows (${table.length} chars, no '<')" to medianMs(table))
            add("prose with literal '<' (${proseWithLt.length} chars)" to medianMs(proseWithLt))
            val noLt = proseWithLt.replace('<', '\uFF1C')
            add("same prose, '<' replaced by U+FF1C" to medianMs(noLt))
            add("table with <br>/<b> (${tableWithBr.length} chars)" to medianMs(tableWithBr))
            val unclosed = (1..2000).joinToString("") { "<b>x$it " }
            add("2000 unclosed <b> on one line (${unclosed.length} chars)" to medianMs(unclosed, runs = 11))
        }
        val out = rows.joinToString("\n") { (name, ms) -> "$label\t$name\t%.2f ms".format(ms) }
        println(out)
        File("build/md_html_bench.txt").appendText(out + "\n")
    }

    private fun previewUs(rows: List<String>): Double {
        val json = rows.map { org.json.JSONArray().put(org.json.JSONObject().put("type", "text").put("value", it)).toString() }
        repeat(20) { json.forEach { com.openminis.app.data.repository.ChatRepository.extractTextPreview(it) } }
        val times = (1..41).map {
            val t0 = System.nanoTime()
            json.forEach { com.openminis.app.data.repository.ChatRepository.extractTextPreview(it) }
            (System.nanoTime() - t0) / 1e3 / rows.size
        }.sorted()
        return times[20]
    }

    @Test
    fun `preview timings`() {
        assumeTrue(System.getenv("MD_BENCH") == "1")
        val label = System.getenv("MD_BENCH_LABEL") ?: "run"
        val plainRows = (1..2000).map { "第 $it 条回复：**结论**是使用 `cache` 并参考 [文档](https://e.x/$it)，然后继续下一步的说明文字。" }
        val tagRows = (1..2000).map { "第 $it 条：x<br>y <b>粗</b> 与 `a<br>b`，1 < 2 &amp; 3" }
        val longRow = listOf(prose)
        val rows = listOf(
            "preview 2000 rows, no '<' (µs/row)" to previewUs(plainRows),
            "preview 2000 rows with tags (µs/row)" to previewUs(tagRows),
            "preview of one ${prose.length}-char reply, no '<' (µs)" to previewUs(longRow),
        )
        val out = rows.joinToString("\n") { (name, v) -> "$label\t$name\t%.2f".format(v) }
        println(out)
        File("build/md_html_bench.txt").appendText(out + "\n")
    }
}
