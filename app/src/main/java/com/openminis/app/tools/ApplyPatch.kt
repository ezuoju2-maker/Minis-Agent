package com.openminis.app.tools

object ApplyPatch {

    const val BEGIN = "*** Begin Patch"
    const val END = "*** End Patch"
    private const val UPDATE = "*** Update File:"
    private const val ADD = "*** Add File:"
    private const val DELETE = "*** Delete File:"
    private const val MOVE = "*** Move to:"

    data class Line(val kind: Char, val text: String)

    data class Hunk(
        val anchor: String?,
        val lines: List<Line>,
        val headerLine: Int,
    )

    sealed class Op {
        abstract val path: String

        data class Update(
            override val path: String,
            val hunks: List<Hunk>,
            val moveTo: String? = null,
        ) : Op()

        data class Add(override val path: String, val lines: List<String>) : Op()

        data class Delete(override val path: String) : Op()
    }

    data class ParseResult(val ops: List<Op>, val error: String?)

    data class FileChange(
        val path: String,
        val before: String,
        val after: String,
        val added: Int,
        val removed: Int,
    ) {
        val isDelete: Boolean get() = after.isEmpty() && before.isNotEmpty()
    }

    class ApplyError(message: String) : Exception(message)


    fun parse(raw: String): ParseResult {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size && lines[i].trim() != BEGIN) i++
        if (i == lines.size) return ParseResult(emptyList(), "Missing `$BEGIN` line.")
        i++
        val end = lines.indexOfLast { it.trim() == END }
        if (end <= i) return ParseResult(emptyList(), "Missing `$END` line after the patch body.")
        val body = lines.subList(i, end)

        val ops = ArrayList<Op>()
        var idx = 0
        while (idx < body.size) {
            val line = body[idx]
            val t = line.trim()
            if (t.isEmpty()) { idx++; continue }
            when {
                t.startsWith(UPDATE) -> {
                    val path = t.removePrefix(UPDATE).trim()
                    if (path.isEmpty()) return ParseResult(emptyList(), "Empty path in `$UPDATE` at line ${idx + i + 2}.")
                    val hunks = ArrayList<Hunk>()
                    var moveTo: String? = null
                    idx++
                    val hunkAnchor = StringBuilder()
                    var hunkLines = ArrayList<Line>()
                    var hunkHeader = idx + i + 1
                    fun flush() {
                        if (hunkLines.isNotEmpty() || hunkAnchor.isNotEmpty()) {
                            hunks.add(Hunk(hunkAnchor.toString().ifEmpty { null }, hunkLines.toList(), hunkHeader))
                        }
                        hunkAnchor.setLength(0)
                        hunkLines = ArrayList()
                    }
                    while (idx < body.size && !isOpHeader(body[idx])) {
                        val hl = body[idx]
                        when {
                            hl.startsWith("@@") -> {
                                flush()
                                hunkHeader = idx + i + 2
                                hunkAnchor.append(hl.removePrefix("@@").trim())
                                idx++
                            }
                            hl.startsWith(MOVE) -> { moveTo = hl.removePrefix(MOVE).trim(); idx++ }
                            hl.startsWith("+") -> { hunkLines.add(Line('+', hl.substring(1))); idx++ }
                            hl.startsWith("-") -> { hunkLines.add(Line('-', hl.substring(1))); idx++ }
                            hl.startsWith(" ") -> { hunkLines.add(Line(' ', hl.substring(1))); idx++ }
                            hl.startsWith("\\") -> idx++
                            else -> return ParseResult(
                                emptyList(),
                                "Unexpected line at patch line ${idx + i + 2}: '${hl.take(60)}'. " +
                                    "Every hunk line must start with ' ', '-', or '+'.",
                            )
                        }
                    }
                    flush()
                    val meaningful = hunks.filter { h -> h.lines.any { it.kind != ' ' } }
                    if (meaningful.isEmpty() && moveTo == null) {
                        return ParseResult(emptyList(), "Update block for '$path' contains no changes.")
                    }
                    ops.add(Op.Update(path, hunks, moveTo))
                }
                t.startsWith(ADD) -> {
                    val path = t.removePrefix(ADD).trim()
                    if (path.isEmpty()) return ParseResult(emptyList(), "Empty path in `$ADD`.")
                    idx++
                    val content = ArrayList<String>()
                    while (idx < body.size && !isOpHeader(body[idx])) {
                        val hl = body[idx]
                        when {
                            hl.startsWith("+") -> content.add(hl.substring(1))
                            hl.trim().isEmpty() -> content.add("")
                            hl.startsWith("\\") -> Unit
                            else -> return ParseResult(emptyList(), "Added file '$path' has a line not starting with '+': '${hl.take(60)}'")
                        }
                        idx++
                    }
                    ops.add(Op.Add(path, content))
                }
                t.startsWith(DELETE) -> {
                    val path = t.removePrefix(DELETE).trim()
                    if (path.isEmpty()) return ParseResult(emptyList(), "Empty path in `$DELETE`.")
                    ops.add(Op.Delete(path))
                    idx++
                }
                else -> return ParseResult(
                    emptyList(),
                    "Expected `$UPDATE`, `$ADD` or `$DELETE` at patch line ${idx + i + 2}, got '${t.take(60)}'.",
                )
            }
        }
        if (ops.isEmpty()) return ParseResult(emptyList(), "Patch contains no file operations.")
        val dup = ops.groupBy { it.path }.entries.firstOrNull { it.value.size > 1 }
        if (dup != null) return ParseResult(emptyList(), "The same path appears more than once: '${dup.key}'.")
        return ParseResult(ops, null)
    }

    private fun isOpHeader(line: String): Boolean {
        val t = line.trim()
        return t.startsWith(UPDATE) || t.startsWith(ADD) || t.startsWith(DELETE) || t == END
    }


    fun apply(ops: List<Op>, contents: Map<String, String?>): List<FileChange> {
        val out = ArrayList<FileChange>()
        for (op in ops) {
            when (op) {
                is Op.Add -> {
                    if (contents.containsKey(op.path) && contents[op.path] != null) {
                        throw ApplyError("Cannot add '${op.path}': it already exists. Use `$UPDATE` to edit it.")
                    }
                    val body = op.lines.joinToString("\n").let { if (it.isEmpty()) "" else it + "\n" }
                    out.add(FileChange(op.path, "", body, added = op.lines.size, removed = 0))
                }
                is Op.Delete -> {
                    val before = contents[op.path]
                        ?: throw ApplyError("Cannot delete '${op.path}': file not found.")
                    out.add(FileChange(op.path, before, "", added = 0, removed = before.lineCount()))
                }
                is Op.Update -> {
                    val before = contents[op.path]
                        ?: throw ApplyError("Cannot update '${op.path}': file not found.")
                    val after = applyHunks(op.path, before, op.hunks)
                    val (added, removed) = countChanges(before, after)
                    out.add(FileChange(op.moveTo ?: op.path, before, after, added, removed))
                    if (op.moveTo != null) {
                        out.add(FileChange(op.path, before, "", added = 0, removed = before.lineCount()))
                    }
                }
            }
        }
        return out
    }

    internal fun applyHunks(path: String, before: String, hunks: List<Hunk>): String {
        val hadTrailingNewline = before.endsWith("\n") || before.isEmpty()
        var lines = before.split('\n').let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
        var cursor = 0
        for (hunk in hunks) {
            if (hunk.lines.none { it.kind != ' ' }) continue
            val target = hunk.lines.filter { it.kind != '+' }.map { it.text }
            val replacement = hunk.lines.filter { it.kind != '-' }.map { it.text }

            if (!hunk.anchor.isNullOrBlank()) {
                val anchorIdx = lines.indexOfFirst { it.contains(hunk.anchor, ignoreCase = false) }
                if (anchorIdx >= 0) cursor = anchorIdx
            }

            val pos = if (target.isEmpty()) {
                cursor.coerceIn(0, lines.size)
            } else {
                findMatch(lines, target, cursor)
                    ?: findMatch(lines, target, 0)
                    ?: throw ApplyError(
                        "Hunk at '$path' (patch line ${hunk.headerLine}) does not match the file. " +
                            "Expected context/value:\n" +
                            target.take(3).joinToString("\n") { "  | $it" } +
                            "\nRe-read the file with file_read and regenerate the patch against its current content.",
                    )
            }
            lines = (lines.subList(0, pos) + replacement + lines.subList(pos + target.size, lines.size))
            cursor = pos + replacement.size
        }
        var joined = lines.joinToString("\n")
        if (hadTrailingNewline && joined.isNotEmpty()) joined += "\n"
        return joined
    }

    private fun findMatch(lines: List<String>, target: List<String>, from: Int): Int? {
        if (target.isEmpty()) return from.coerceIn(0, lines.size)
        if (lines.size - from < target.size) return null
        var i = from
        while (i <= lines.size - target.size) {
            if (matchesAt(lines, target, i) { a, b -> a == b }) return i
            i++
        }
        i = from
        while (i <= lines.size - target.size) {
            if (matchesAt(lines, target, i) { a, b -> a.trimEnd() == b.trimEnd() }) return i
            i++
        }
        val normTarget = target.map(::collapseWhitespace)
        val hits = ArrayList<Int>()
        i = from
        while (i <= lines.size - target.size) {
            var ok = true
            for (k in target.indices) {
                if (collapseWhitespace(lines[i + k]) != normTarget[k]) { ok = false; break }
            }
            if (ok) {
                hits.add(i)
                if (hits.size > 1) break
            }
            i++
        }
        return hits.singleOrNull()
    }

    private inline fun matchesAt(
        lines: List<String>,
        target: List<String>,
        at: Int,
        eq: (String, String) -> Boolean,
    ): Boolean {
        for (k in target.indices) if (!eq(lines[at + k], target[k])) return false
        return true
    }

    private fun collapseWhitespace(s: String): String =
        s.trim().replace(Regex("\\s+"), " ")

    private fun String.lineCount(): Int = if (isEmpty()) 0 else split('\n').size

    internal fun countChanges(before: String, after: String): Pair<Int, Int> {
        val stats = ChangePreview.stats(before, after)
        return stats.added to stats.removed
    }
}
