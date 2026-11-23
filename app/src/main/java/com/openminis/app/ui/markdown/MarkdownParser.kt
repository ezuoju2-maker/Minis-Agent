package com.openminis.app.ui.markdown

object MarkdownParser {

    sealed class Block {
        data class Heading(val level: Int, val content: String) : Block()
        data class Paragraph(val content: String) : Block()
        data class CodeBlock(val language: String, val code: String) : Block()
        data class Blockquote(val blocks: List<Block>) : Block()
        data class BulletList(val items: List<ListItem>) : Block()
        data class NumberedList(val startNumber: Int, val items: List<ListItem>) : Block()
        data class Table(val headers: List<String>, val alignments: List<Alignment>, val rows: List<List<String>>) : Block()
        data object ThematicBreak : Block()

        data class MathBlock(val latex: String) : Block()

        data class Image(val alt: String, val url: String) : Block()
        data class Video(val alt: String, val url: String) : Block()
        data class Audio(val alt: String, val url: String) : Block()
    }

    data class MathSpan(val placeholder: String, val latex: String, val isBlock: Boolean)

    data class ParseResult(val blocks: List<Block>, val mathSpans: List<MathSpan>)

    private const val ORC = '￼'

    private val nativeVideoExts = setOf("mp4", "mov", "m4v", "avi", "mkv", "webm")
    private val nativeAudioExts = setOf("mp3", "m4a", "wav", "aac", "ogg", "flac")

    private val standaloneImageRegex = Regex("""^\s*!\[([^\]]*)\]\(([^)\s]+)\)\s*$""")

    private val thematicBreakRegex = Regex("^\\s{0,3}([-*_])\\s*\\1\\s*\\1(\\s*\\1)*\\s*$")
    private val headingRegex = Regex("^(#{1,6})\\s+(.+)$")
    private val bulletItemRegex = Regex("^\\s{0,3}[-*+]\\s+(.*)$")
    private val bulletMarkerRegex = Regex("^\\s{0,3}[-*+]\\s")
    private val bulletStartRegex = Regex("^\\s{0,3}[-*+]\\s+")
    private val numberedItemRegex = Regex("^\\s{0,3}(\\d{1,2})[.)\\s]\\s*(.*)$")
    private val numberedStartRegex = Regex("^\\s{0,3}\\d{1,2}[.)\\s]\\s")
    private val tableSeparatorCellRegex = Regex("^:?-+:?$")
    private val blankLineRegex = Regex("\\n[ \\t]*\\n")
    private val trailingNewlineRegex = Regex("\\n[ \\t]*$")
    private val mathPlaceholderRegex = Regex("$ORC" + "MATH(\\d+)" + "$ORC")

    private fun mediaBlockFor(alt: String, url: String): Block {
        val lastSeg = url.substringAfterLast('/')
        val decoded = runCatching { java.net.URLDecoder.decode(lastSeg, "UTF-8") }.getOrDefault(lastSeg)
        val ext = decoded.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in nativeVideoExts -> Block.Video(alt, url)
            in nativeAudioExts -> Block.Audio(alt, url)
            else -> Block.Image(alt, url)
        }
    }

    data class ListItem(val content: String, val checked: Boolean? = null)

    enum class Alignment { LEFT, CENTER, RIGHT }

    fun parseWithMath(markdown: String): ParseResult {
        val (cleaned, spans) = extractMath(markdown)
        val rawBlocks = parse(cleaned)
        val restored = restoreMath(rawBlocks, spans)
        return ParseResult(restored, spans)
    }

    fun parse(markdown: String): List<Block> {
        android.util.Log.d("MdParser", "parse() len=${markdown.length} preview=${markdown.take(160).replace("\n","\\n")}")
        val lines = markdown.lines()
        val blocks = mutableListOf<Block>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            if (line.trimStart().startsWith("```")) {
                val indent = line.indexOf('`')
                val fence = line.trimStart()
                val lang = fence.removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size) {
                    val cl = lines[i]
                    if (cl.trimStart().startsWith("```") && cl.trim() == "```") {
                        i++
                        break
                    }
                    codeLines.add(cl)
                    i++
                }
                blocks.add(Block.CodeBlock(lang, codeLines.joinToString("\n")))
                continue
            }

            if (line.matches(thematicBreakRegex)) {
                blocks.add(Block.ThematicBreak)
                i++
                continue
            }

            val headingMatch = headingRegex.find(line)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                val content = headingMatch.groupValues[2].trimEnd().removeSuffix("#").trimEnd()
                blocks.add(Block.Heading(level, content))
                i++
                continue
            }

            if (line.trimStart().startsWith("> ") || line.trimStart() == ">") {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && (lines[i].trimStart().startsWith("> ") || lines[i].trimStart() == ">")) {
                    val ql = lines[i].trimStart()
                    quoteLines.add(if (ql == ">") "" else ql.removePrefix("> "))
                    i++
                }
                val inner = parse(quoteLines.joinToString("\n"))
                blocks.add(Block.Blockquote(inner))
                continue
            }

            if (i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
                val table = parseTable(lines, i)
                if (table != null) {
                    blocks.add(table.first)
                    i = table.second
                    continue
                }
            }

            val bulletMatch = bulletItemRegex.find(line)
            if (bulletMatch != null) {
                val items = mutableListOf<ListItem>()
                while (i < lines.size) {
                    val bm = bulletItemRegex.find(lines[i])
                    if (bm == null) break
                    val content = bm.groupValues[1]
                    items.add(parseListItem(content))
                    i++
                    while (i < lines.size && lines[i].startsWith("  ") && !bulletMarkerRegex.matches(lines[i])) {
                        items[items.lastIndex] = items.last().copy(
                            content = items.last().content + "\n" + lines[i].trimStart()
                        )
                        i++
                    }
                }
                blocks.add(Block.BulletList(items))
                continue
            }

            val numMatch = numberedItemRegex.find(line)
            if (numMatch != null) {
                val startNum = numMatch.groupValues[1].toIntOrNull() ?: 1
                val items = mutableListOf<ListItem>()
                while (i < lines.size) {
                    val nm = numberedItemRegex.find(lines[i])
                    if (nm == null) break
                    items.add(ListItem(nm.groupValues[2]))
                    i++
                }
                blocks.add(Block.NumberedList(startNum, items))
                continue
            }

            val mediaMatch = standaloneImageRegex.find(line)
            if (mediaMatch != null) {
                val alt = mediaMatch.groupValues[1]
                val url = mediaMatch.groupValues[2]
                val blk = mediaBlockFor(alt, url)
                android.util.Log.d("MdParser", "media match: alt=\"$alt\" url=$url -> ${blk::class.simpleName}")
                blocks.add(blk)
                i++
                continue
            } else if (line.contains("![") && line.contains("](")) {
                android.util.Log.d("MdParser", "image-like line did NOT match standalone regex: ${line.take(160)}")
            }

            if (line.isBlank()) {
                i++
                continue
            }

            val paraLines = mutableListOf(line)
            i++
            while (i < lines.size) {
                val pl = lines[i]
                if (pl.isBlank() || pl.trimStart().startsWith("```") ||
                    pl.trimStart().startsWith("# ") || pl.trimStart().startsWith("> ") ||
                    bulletStartRegex.containsMatchIn(pl) ||
                    numberedStartRegex.containsMatchIn(pl) ||
                    pl.matches(thematicBreakRegex) ||
                    standaloneImageRegex.containsMatchIn(pl)
                ) break
                paraLines.add(pl)
                i++
            }
            blocks.add(Block.Paragraph(paraLines.joinToString("\n")))
        }

        return blocks
    }

    private fun parseListItem(content: String): ListItem {
        if (content.startsWith("[x] ") || content.startsWith("[X] ")) {
            return ListItem(content.substring(4), checked = true)
        }
        if (content.startsWith("[ ] ")) {
            return ListItem(content.substring(4), checked = false)
        }
        return ListItem(content)
    }

    private fun isTableSeparator(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.contains('-')) return false
        val cells = trimmed.split('|').filter { it.isNotBlank() }
        return cells.all { it.trim().matches(tableSeparatorCellRegex) }
    }

    private fun parseTable(lines: List<String>, startIdx: Int): Pair<Block.Table, Int>? {
        val headerLine = lines[startIdx]
        val separatorLine = lines[startIdx + 1]

        val headers = splitTableRow(headerLine)
        val sepCells = splitTableRow(separatorLine)
        if (headers.isEmpty() || sepCells.isEmpty()) return null

        val alignments = sepCells.map { cell ->
            val trimmed = cell.trim()
            when {
                trimmed.startsWith(':') && trimmed.endsWith(':') -> Alignment.CENTER
                trimmed.endsWith(':') -> Alignment.RIGHT
                else -> Alignment.LEFT
            }
        }

        var i = startIdx + 2
        val rows = mutableListOf<List<String>>()
        while (i < lines.size) {
            val row = lines[i]
            if (row.isBlank() || !row.contains('|')) break
            rows.add(splitTableRow(row))
            i++
        }

        return Block.Table(headers, alignments, rows) to i
    }

    private fun splitTableRow(line: String): List<String> {
        val trimmed = line.trim().removePrefix("|").removeSuffix("|")
        if (!trimmed.contains("](")) {
            return trimmed.split('|').map { it.trim() }
        }
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (ch in trimmed) {
            if (ch == '(' && depth == 0) depth++
            else if (ch == ')' && depth > 0) depth--
            if (ch == '|' && depth == 0) {
                cells.add(current.toString().trim())
                current.clear()
            } else {
                current.append(ch)
            }
        }
        cells.add(current.toString().trim())
        return cells
    }


    private fun extractMath(markdown: String): Pair<String, List<MathSpan>> {
        val spans = mutableListOf<MathSpan>()
        val out = StringBuilder()
        val chars = markdown
        val n = chars.length
        var i = 0

        var inFence = false
        var fenceChar = '`'
        var fenceLen = 0

        val codeMask = buildCodeMask(chars)

        fun atLineStart(idx: Int): Boolean = idx == 0 || chars[idx - 1] == '\n' || chars[idx - 1] == '\r'

        while (i < n) {
            if (!inFence && atLineStart(i)) {
                val c = chars[i]
                if (c == '`' || c == '~') {
                    var fl = 0
                    var j = i
                    while (j < n && chars[j] == c) { fl++; j++ }
                    if (fl >= 3) {
                        inFence = true
                        fenceChar = c
                        fenceLen = fl
                        while (i < n && chars[i] != '\n') { out.append(chars[i]); i++ }
                        if (i < n) { out.append(chars[i]); i++ }
                        continue
                    }
                }
            }

            if (inFence) {
                if (atLineStart(i) && chars[i] == fenceChar) {
                    var fl = 0
                    var j = i
                    while (j < n && chars[j] == fenceChar) { fl++; j++ }
                    if (fl >= fenceLen) {
                        inFence = false
                        while (i < n && chars[i] != '\n') { out.append(chars[i]); i++ }
                        if (i < n) { out.append(chars[i]); i++ }
                        continue
                    }
                }
                out.append(chars[i]); i++
                continue
            }

            if (chars[i] == '`') {
                var run = 0
                var j = i
                while (j < n && chars[j] == '`') { run++; j++ }
                var k = j
                var found = false
                while (k <= n - run) {
                    var match = 0
                    while (k + match < n && chars[k + match] == '`') match++
                    if (match == run) {
                        for (idx in i until k + match) out.append(chars[idx])
                        i = k + match
                        found = true
                        break
                    }
                    k += if (match > 0) match else 1
                }
                if (found) continue
                for (idx in i until j) out.append(chars[idx])
                i = j
                continue
            }

            if (chars[i] == '\\' && i + 1 < n && chars[i + 1] == '$') {
                out.append(chars[i]); out.append(chars[i + 1])
                i += 2
                continue
            }

            if (chars[i] == '\\' && i + 1 < n && chars[i + 1] == '[') {
                val end = findClose(chars, i + 2, "\\]")
                if (end != null) {
                    val latex = stripBlockquoteMarkers(chars.substring(i + 2, end))
                    spans.add(MathSpan(makePlaceholder(spans.size), latex, true))
                    out.append(spans.last().placeholder)
                    i = end + 2
                    continue
                }
            }

            if (chars[i] == '\\' && i + 1 < n && chars[i + 1] == '(') {
                val end = findClose(chars, i + 2, "\\)")
                if (end != null) {
                    val latex = chars.substring(i + 2, end)
                    spans.add(MathSpan(makePlaceholder(spans.size), latex, false))
                    out.append(spans.last().placeholder)
                    i = end + 2
                    continue
                }
            }

            if (chars[i] == '$' && i + 1 < n && chars[i + 1] == '$') {
                val end = findDoubleDollar(chars, i + 2, codeMask)
                if (end != null && isPlausibleDisplayBody(chars.substring(i + 2, end))) {
                    val latex = stripBlockquoteMarkers(chars.substring(i + 2, end))
                    spans.add(MathSpan(makePlaceholder(spans.size), latex, true))
                    out.append(spans.last().placeholder)
                    i = end + 2
                    continue
                }
            }

            if (chars[i] == '$' && i + 1 < n && chars[i + 1] != '$' && chars[i + 1] != ' ' &&
                !opensVariable(chars, i)
            ) {
                val end = findSingleDollar(chars, i + 1, codeMask)
                if (end != null) {
                    val latex = chars.substring(i + 1, end)
                    if (looksLikeMath(latex) && !isTablePipeArtifact(latex)) {
                        spans.add(MathSpan(makePlaceholder(spans.size), latex, false))
                        out.append(spans.last().placeholder)
                        i = end + 1
                        continue
                    }
                }
            }

            out.append(chars[i])
            i++
        }

        return out.toString() to spans
    }

    private fun makePlaceholder(idx: Int): String = "${ORC}MATH$idx$ORC"

    private fun stripBlockquoteMarkers(latex: String): String {
        if (!latex.contains('\n')) return latex

        val lines = latex.split("\n").toMutableList()
        var sawMarker = false
        for (idx in 1 until lines.size) {
            val line = lines[idx]
            var cursor = 0
            while (cursor < line.length && line[cursor] == ' ' && cursor < 3) cursor++
            if (cursor >= line.length) continue
            if (line[cursor] != '>') return latex
            sawMarker = true
            cursor++
            if (cursor < line.length && line[cursor] == ' ') cursor++
            lines[idx] = line.substring(cursor)
        }
        if (!sawMarker) return latex
        return lines.joinToString("\n")
    }

    private fun findClose(chars: String, from: Int, close: String): Int? {
        val n = chars.length
        var i = from
        while (i <= n - close.length) {
            var match = true
            for (j in close.indices) {
                if (chars[i + j] != close[j]) { match = false; break }
            }
            if (match) return i
            i++
        }
        return null
    }

    private fun buildCodeMask(chars: String): BooleanArray {
        val n = chars.length
        val mask = BooleanArray(n)
        var i = 0
        var inFence = false
        var fenceChar = '`'
        var fenceLen = 0

        fun atLineStart(idx: Int): Boolean =
            idx == 0 || chars[idx - 1] == '\n' || chars[idx - 1] == '\r'

        while (i < n) {
            if (!inFence && atLineStart(i)) {
                val c = chars[i]
                if (c == '`' || c == '~') {
                    var fl = 0
                    var j = i
                    while (j < n && chars[j] == c) { fl++; j++ }
                    if (fl >= 3) {
                        inFence = true; fenceChar = c; fenceLen = fl
                        while (i < n && chars[i] != '\n') { mask[i] = true; i++ }
                        if (i < n) { mask[i] = true; i++ }
                        continue
                    }
                }
            }
            if (inFence) {
                if (atLineStart(i) && chars[i] == fenceChar) {
                    var fl = 0
                    var j = i
                    while (j < n && chars[j] == fenceChar) { fl++; j++ }
                    if (fl >= fenceLen) {
                        inFence = false
                        while (i < n && chars[i] != '\n') { mask[i] = true; i++ }
                        if (i < n) { mask[i] = true; i++ }
                        continue
                    }
                }
                mask[i] = true; i++
                continue
            }
            if (chars[i] == '`') {
                var run = 0
                var j = i
                while (j < n && chars[j] == '`') { run++; j++ }
                var k = j
                var closed = -1
                while (k < n) {
                    if (chars[k] == '`') {
                        var r2 = 0
                        var m = k
                        while (m < n && chars[m] == '`') { r2++; m++ }
                        if (r2 == run) { closed = m; break }
                        k = m
                    } else k++
                }
                val end = if (closed > 0) closed else j
                for (idx in i until end) mask[idx] = true
                i = end
                continue
            }
            i++
        }
        return mask
    }

    private fun isPlausibleDisplayBody(body: String): Boolean {
        if (!body.contains('\n')) return true
        if (blankLineRegex.containsMatchIn(body)) return false
        if (trailingNewlineRegex.containsMatchIn(body)) return true
        return body.any { it == '\\' || it == '^' || it == '_' || it == '{' || it == '}' }
    }

    private fun findDoubleDollar(chars: String, from: Int, codeMask: BooleanArray?): Int? {
        val n = chars.length
        var i = from
        while (i < n - 1) {
            if (chars[i] == '$' && chars[i + 1] == '$' &&
                (codeMask == null || !codeMask[i])
            ) return i
            i++
        }
        return null
    }

    private fun findSingleDollar(chars: String, from: Int, codeMask: BooleanArray?): Int? {
        val n = chars.length
        var i = from
        while (i < n) {
            if (chars[i] == '\\' && i + 1 < n) { i += 2; continue }
            val plainDollar = chars[i] == '$' && (codeMask == null || !codeMask[i])
            if (plainDollar && startsAmountOrVariable(chars, i)) return null
            if (plainDollar && (i == 0 || chars[i - 1] != ' ')) return i
            if (plainDollar && from < n && isAsciiDigit(chars[from])) return null
            if (chars[i] == '\n') return null
            i++
        }
        return null
    }


    private fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'
    private fun isAsciiUpper(c: Char): Boolean = c in 'A'..'Z'
    private fun isAsciiLower(c: Char): Boolean = c in 'a'..'z'

    private fun variableNameLength(chars: String, from: Int): Int {
        val n = chars.length
        fun upper(i: Int) = i < n && isAsciiUpper(chars[i])
        fun part(i: Int) = i < n && (isAsciiUpper(chars[i]) || isAsciiDigit(chars[i]))
        if (!upper(from)) return 0
        var len = 1
        while (part(from + len)) len++
        if (len < 2) return 0
        while (from + len < n && chars[from + len] == '_' && upper(from + len + 1)) {
            len += 2
            while (part(from + len)) len++
        }
        val continued = from + len < n && isAsciiLower(chars[from + len])
        val subscripted = from + len < n && chars[from + len] == '_'
        return if (continued || subscripted) 0 else len
    }

    private fun isBracedVariable(chars: String, from: Int): Boolean {
        val n = chars.length
        if (from >= n || chars[from] != '{') return false
        var name = 0
        while (from + 1 + name < n) {
            val c = chars[from + 1 + name]
            if (!(c in 'a'..'z' || isAsciiUpper(c) || isAsciiDigit(c) || c == '_')) break
            name++
        }
        if (name == 0 || isAsciiDigit(chars[from + 1])) return false
        if (from + 1 + name >= n || chars[from + 1 + name] != '}') return false
        val after = from + 2 + name
        return !(after < n && (chars[after] == '^' || chars[after] == '_'))
    }

    private fun startsAmountOrVariable(chars: String, at: Int): Boolean {
        if (at + 1 < chars.length && isAsciiDigit(chars[at + 1])) return true
        return isBracedVariable(chars, at + 1) || variableNameLength(chars, at + 1) > 0
    }

    private fun opensVariable(chars: String, at: Int): Boolean {
        val n = chars.length
        if (isBracedVariable(chars, at + 1)) return true
        val len = variableNameLength(chars, at + 1)
        if (len == 0) return false
        val after = at + 1 + len
        if (after >= n) return true
        val c = chars[after]
        if (c == '\n' || c == '\r') return true
        if (c in "/:;,.\"')]") return true
        if (c == ' ' || c == '\t') {
            var k = after
            while (k < n && (chars[k] == ' ' || chars[k] == '\t')) k++
            if (k >= n) return true
            return chars[k].isLetterOrDigit() || chars[k] == '\n' || chars[k] == '\r'
        }
        return false
    }

    private fun looksLikeMath(content: String): Boolean {
        if (content.isEmpty()) return false
        val mathChars = charArrayOf('\\', '^', '_', '{', '}', '∫', '∑', '∏', '√', '+', '-', '=', '<', '>')
        for (c in content) {
            if (c in mathChars) return true
        }
        if (content[0].isDigit()) return false
        if (content.first().isWhitespace() || content.last().isWhitespace()) return false
        if (content.length <= 30 && content.all { it.isLetterOrDigit() }) return true
        return content.length > 2
    }

    private fun isTablePipeArtifact(content: String): Boolean {
        var bare = 0
        for (idx in content.indices) {
            if (content[idx] != '|') continue
            if (idx > 0 && content[idx - 1] == '\\') continue
            bare++
            val prevIsSpace = idx > 0 && content[idx - 1].isWhitespace()
            val nextIsSpace = idx + 1 < content.length && content[idx + 1].isWhitespace()
            if (prevIsSpace || nextIsSpace) return true
        }
        return bare % 2 == 1
    }

    private fun restoreMath(blocks: List<Block>, spans: List<MathSpan>): List<Block> {
        if (spans.isEmpty()) return blocks
        val byPlaceholder = spans.associateBy { it.placeholder }
        return blocks.flatMap { restoreBlock(it, byPlaceholder) }
    }

    private fun restoreBlock(block: Block, map: Map<String, MathSpan>): List<Block> {
        return when (block) {
            is Block.Paragraph -> restoreParagraph(block.content, map)
            is Block.Heading -> listOf(block)
            is Block.Blockquote -> listOf(Block.Blockquote(block.blocks.flatMap { restoreBlock(it, map) }))
            is Block.BulletList,
            is Block.NumberedList,
            is Block.Table,
            is Block.CodeBlock,
            is Block.MathBlock,
            is Block.ThematicBreak,
            is Block.Image,
            is Block.Video,
            is Block.Audio -> listOf(block)
        }
    }

    private fun restoreParagraph(content: String, map: Map<String, MathSpan>): List<Block> {
        if (!content.contains(ORC)) return listOf(Block.Paragraph(content))

        val out = mutableListOf<Block>()
        val buf = StringBuilder()
        val regex = mathPlaceholderRegex
        var lastEnd = 0
        for (match in regex.findAll(content)) {
            val full = match.value
            val span = map[full] ?: continue
            if (match.range.first > lastEnd) {
                buf.append(content, lastEnd, match.range.first)
            }
            if (span.isBlock) {
                if (buf.isNotBlank()) {
                    out.add(Block.Paragraph(buf.toString().trimEnd()))
                }
                buf.clear()
                out.add(Block.MathBlock(span.latex))
            } else {
                buf.append(full)
            }
            lastEnd = match.range.last + 1
        }
        if (lastEnd < content.length) buf.append(content, lastEnd, content.length)
        if (buf.isNotBlank()) out.add(Block.Paragraph(buf.toString().trimEnd()))
        return if (out.isEmpty()) listOf(Block.Paragraph(content)) else out
    }
}
