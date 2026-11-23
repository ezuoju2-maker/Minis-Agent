package com.openminis.app.ui.chat.share

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt


data class ChatShareUnit(val kind: Kind, val messageIds: List<String>) {
    enum class Kind { USER, CALLBACK, REPLY }

    val id: String get() = messageIds[0]
}

data class ChatShareUnitSource(
    val id: String,
    val role: Role,
    val isCallback: Boolean,
    val isHidden: Boolean,
) {
    enum class Role { USER, ASSISTANT, OTHER }
}

object ChatShareUnits {
    fun group(sources: List<ChatShareUnitSource>): List<ChatShareUnit> {
        val units = mutableListOf<ChatShareUnit>()
        val reply = mutableListOf<String>()
        fun closeReply() {
            if (reply.isNotEmpty()) units += ChatShareUnit(ChatShareUnit.Kind.REPLY, reply.toList())
            reply.clear()
        }
        for (s in sources) {
            if (s.isHidden) continue
            when (s.role) {
                ChatShareUnitSource.Role.ASSISTANT -> reply += s.id
                ChatShareUnitSource.Role.USER -> {
                    closeReply()
                    units += ChatShareUnit(
                        if (s.isCallback) ChatShareUnit.Kind.CALLBACK else ChatShareUnit.Kind.USER,
                        listOf(s.id),
                    )
                }
                ChatShareUnitSource.Role.OTHER -> closeReply()
            }
        }
        closeReply()
        return units
    }

    fun unitContaining(messageId: String, units: List<ChatShareUnit>): ChatShareUnit? =
        units.firstOrNull { messageId in it.messageIds }
}

data class ChatShareCell(
    val kind: Kind,
    val messageId: String,
    val blockId: String?,
    val unitId: String,
) {
    enum class Kind { USER, CALLBACK, TEXT, TOOL, THINKING, NOTE }

    val id: String
        get() = when {
            blockId != null -> "$messageId/$blockId"
            kind == Kind.NOTE -> "$messageId/error"
            else -> messageId
        }
}

data class ChatShareBlockSource(val id: String, val kind: Kind, val isEmpty: Boolean) {
    enum class Kind { TEXT, TOOL, THINKING, HIDDEN }
}

object ChatShareCells {
    fun cells(
        units: List<ChatShareUnit>,
        blocks: Map<String, List<ChatShareBlockSource>>,
        errors: Set<String>,
    ): List<ChatShareCell> {
        val out = mutableListOf<ChatShareCell>()
        for (unit in units) {
            when (unit.kind) {
                ChatShareUnit.Kind.USER ->
                    out += ChatShareCell(ChatShareCell.Kind.USER, unit.id, null, unit.id)
                ChatShareUnit.Kind.CALLBACK ->
                    out += ChatShareCell(ChatShareCell.Kind.CALLBACK, unit.id, null, unit.id)
                ChatShareUnit.Kind.REPLY -> for (mid in unit.messageIds) {
                    for (b in blocks[mid].orEmpty()) {
                        if (b.isEmpty) continue
                        val kind = when (b.kind) {
                            ChatShareBlockSource.Kind.TEXT -> ChatShareCell.Kind.TEXT
                            ChatShareBlockSource.Kind.TOOL -> ChatShareCell.Kind.TOOL
                            ChatShareBlockSource.Kind.THINKING -> ChatShareCell.Kind.THINKING
                            ChatShareBlockSource.Kind.HIDDEN -> continue
                        }
                        out += ChatShareCell(kind, mid, b.id, unit.id)
                    }
                    if (mid in errors) out += ChatShareCell(ChatShareCell.Kind.NOTE, mid, null, unit.id)
                }
            }
        }
        return out
    }

    fun roundSelection(unit: String?, cells: List<ChatShareCell>): Set<String> {
        if (unit == null) return emptySet()
        val idx = cells.indexOfFirst { it.unitId == unit }
        if (idx < 0) return emptySet()
        var start = idx
        while (start > 0 && cells[start].kind != ChatShareCell.Kind.USER) start--
        if (cells[start].kind != ChatShareCell.Kind.USER) start = idx
        var end = start + 1
        while (end < cells.size && cells[end].kind != ChatShareCell.Kind.USER) end++
        return cells.subList(start, end).mapTo(LinkedHashSet()) { it.id }
    }

    fun setBelow(id: String, cells: List<ChatShareCell>, selected: Set<String>, on: Boolean): Set<String> {
        val start = cells.indexOfFirst { it.id == id }
        if (start < 0) return selected
        val ids = cells.subList(start, cells.size).map { it.id }
        return if (on) selected + ids else selected - ids.toSet()
    }

    fun renderGroups(cells: List<ChatShareCell>, selected: Set<String>): List<List<ChatShareCell>> {
        val groups = mutableListOf<MutableList<ChatShareCell>>()
        for (cell in cells) {
            if (cell.id !in selected) continue
            val last = groups.lastOrNull()?.lastOrNull()
            if (last != null && last.unitId == cell.unitId) groups.last() += cell else groups += mutableListOf(cell)
        }
        return groups
    }
}

object ChatShareSpacing {
    enum class Edge { BUBBLE, CARD, REPLY }

    enum class Speaker { USER, ASSISTANT, SKIPPED }

    const val ROW_GAP = 2f

    const val TURN_GAP = 16f

    fun edge(kind: ChatShareCell.Kind, isScheduledCard: Boolean): Edge = when (kind) {
        ChatShareCell.Kind.USER -> if (isScheduledCard) Edge.CARD else Edge.BUBBLE
        ChatShareCell.Kind.CALLBACK -> Edge.CARD
        else -> Edge.REPLY
    }

    fun headerAbove(speakers: List<Speaker>, index: Int): Boolean {
        for (i in index - 1 downTo 0) {
            when (speakers[i]) {
                Speaker.ASSISTANT -> return false
                Speaker.USER -> return true
                Speaker.SKIPPED -> {}
            }
        }
        return true
    }

    fun gapBetween(usageRowAbove: Boolean, headerAbove: Boolean): Float =
        ROW_GAP + (if (usageRowAbove) ROW_GAP else 0f) + (if (headerAbove) TURN_GAP else 0f)

    fun followsUserRow(previous: Edge?): Boolean = previous == Edge.BUBBLE || previous == Edge.CARD
}

object ChatShareMarkdownChunker {
    data class Chunk(val text: String, val isTable: Boolean)

    fun split(markdown: String): List<Chunk> {
        val lines = markdown.split("\n")
        val chunks = mutableListOf<Chunk>()
        val prose = mutableListOf<String>()
        var fence: String? = null
        fun flushProse() {
            val text = prose.joinToString("\n")
            if (text.isNotBlank()) chunks += Chunk(text, false)
            prose.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val lead = line.trimStart(' ')
            val indent = line.length - lead.length
            val marker = if (indent <= 3) fenceMarker(lead) else null
            if (marker != null) {
                if (fence == null) fence = marker else if (lead.startsWith(fence)) fence = null
                prose += line; i++; continue
            }
            if (fence == null && indent <= 3 && line.contains("|") &&
                i + 1 < lines.size && isSeparator(lines[i + 1])
            ) {
                flushProse()
                val table = mutableListOf(line, lines[i + 1])
                i += 2
                while (i < lines.size && lines[i].contains("|") && lines[i].isNotBlank()) {
                    table += lines[i]; i++
                }
                chunks += Chunk(table.joinToString("\n") + "\n", true)
                continue
            }
            prose += line; i++
        }
        flushProse()
        return chunks
    }

    private fun fenceMarker(lead: String): String? = when {
        lead.startsWith("```") -> "```"
        lead.startsWith("~~~") -> "~~~"
        else -> null
    }

    fun isSeparator(line: String): Boolean {
        var core = line.trim(' ', '\t')
        if (!core.contains("-")) return false
        if (core.startsWith("|")) core = core.drop(1)
        if (core.endsWith("|")) core = core.dropLast(1)
        val cells = core.split("|")
        if (cells.isEmpty()) return false
        for (cell in cells) {
            var c = cell.trim(' ', '\t')
            if (c.startsWith(":")) c = c.drop(1)
            if (c.endsWith(":")) c = c.dropLast(1)
            if (c.isEmpty() || !c.all { it == '-' }) return false
        }
        return true
    }
}

object ChatSharePagination {
    data class Cursor(
        var page: Int = 0,
        var y: Float = 0f,
    )

    data class Slice(
        val page: Int,
        val unitY: Float,
        val height: Float,
        val pageY: Float,
    )

    const val MIN_FILL = 0.5f

    const val MOVE_WHOLE_BELOW = 0.3f

    fun withoutCutsThrough(breaks: List<Float>, solid: List<Pair<Float, Float>>): List<Float> {
        if (solid.isEmpty()) return breaks
        return breaks.filter { b -> solid.none { (top, bottom) -> b > top + EDGE_SLOP && b < bottom - EDGE_SLOP } }
    }

    private const val EDGE_SLOP = 0.5f

    fun place(height: Float, breaks: List<Float>, pageHeight: Float, gap: Float, cursor: Cursor): List<Slice> {
        val h = height
        if (h <= 0f || pageHeight <= 0f) return emptyList()
        if (cursor.y > 0f) {
            cursor.y += gap
            if (cursor.y >= pageHeight) { cursor.page += 1; cursor.y = 0f }
        }
        if (h <= pageHeight - cursor.y) {
            val slice = Slice(cursor.page, 0f, h, cursor.y)
            cursor.y += h
            return listOf(slice)
        }
        if (h <= pageHeight && pageHeight - cursor.y < pageHeight * MOVE_WHOLE_BELOW) {
            cursor.page += 1
            cursor.y = h
            return listOf(Slice(cursor.page, 0f, h, 0f))
        }
        val sorted = breaks.filter { it > 0f && it < h }.sorted()
        val slices = mutableListOf<Slice>()
        var s = 0f
        while (h - s > 0.5f) {
            val avail = pageHeight - cursor.y
            if (h - s <= avail) {
                slices += Slice(cursor.page, s, h - s, cursor.y)
                cursor.y += h - s
                break
            }
            val best = sorted.lastOrNull { it > s + avail * MIN_FILL && it <= s + avail }
            if (best == null && cursor.y > 0f) {
                cursor.page += 1
                cursor.y = 0f
                continue
            }
            val end = best ?: (s + avail)
            slices += Slice(cursor.page, s, end - s, cursor.y)
            s = end
            cursor.page += 1
            cursor.y = 0f
        }
        return slices
    }
}

object ChatShareImageBudget {
    const val MAX_PIXELS = 60_000_000f

    const val MAX_PIXEL_HEIGHT = 50_000f

    const val PREFERRED_SCALE = 3f

    fun scale(width: Float, height: Float, preferred: Float = PREFERRED_SCALE): Float? {
        if (width <= 0f || height <= 0f) return preferred
        val fit = min(sqrt(MAX_PIXELS / (width * height)), MAX_PIXEL_HEIGHT / height)
        if (fit >= preferred) return preferred
        val floored = floor(fit * 4f) / 4f
        return if (floored >= 1f) floored else null
    }
}

data class ChatShareEstimateInput(val kind: ChatShareCell.Kind, val text: String)

object ChatShareHeightEstimate {
    const val FONT_SIZE = 16.5f
    val lineHeight: Float get() = FONT_SIZE * 1.45f

    const val HEADER_HEIGHT = 80f
    const val PER_CELL_GAP = 10f

    fun points(inputs: List<ChatShareEstimateInput>, width: Float): Float {
        val content = max(width - 32f, 100f)
        return inputs.fold(HEADER_HEIGHT) { total, input -> total + PER_CELL_GAP + height(input, content) }
    }

    fun tooTallForImage(points: Float): Boolean =
        points * ChatShareImageBudget.PREFERRED_SCALE > ChatShareImageBudget.MAX_PIXEL_HEIGHT

    fun height(input: ChatShareEstimateInput, contentWidth: Float): Float = when (input.kind) {
        ChatShareCell.Kind.USER -> wrappedHeight(input.text, contentWidth * 0.8f - 28f) + 20f
        ChatShareCell.Kind.CALLBACK -> 72f
        ChatShareCell.Kind.TOOL -> 36f
        ChatShareCell.Kind.THINKING -> 40f
        ChatShareCell.Kind.NOTE -> 30f
        ChatShareCell.Kind.TEXT -> {
            var total = 0f
            var inFence = false
            for (line in input.text.split("\n")) {
                val trimmed = line.trim(' ', '\t')
                if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                    inFence = !inFence; total += 12f; continue
                }
                if (inFence) { total += 20f; continue }
                if (trimmed.isEmpty()) { total += 8f; continue }
                if (trimmed.startsWith("|") || (trimmed.contains("|") && trimmed.contains("---"))) {
                    total += if (trimmed.contains("---")) 0f else 44f
                    continue
                }
                total += wrappedHeight(trimmed, contentWidth) * (if (trimmed.startsWith("#")) 1.3f else 1f)
            }
            total
        }
    }

    fun wrappedHeight(text: String, width: Float): Float {
        var lines = 0f
        for (paragraph in text.split("\n")) {
            var visual = 0f
            var i = 0
            while (i < paragraph.length) {
                val cp = paragraph.codePointAt(i)
                visual += if (cp >= 0x2E80) FONT_SIZE else FONT_SIZE * 0.55f
                i += Character.charCount(cp)
            }
            lines += max(1f, ceil(visual / max(width, 1f)))
        }
        return max(1f, lines) * lineHeight
    }
}

object ChatShareGeometry {
    const val ROW_PADDING = 32f

    fun maxCanvasWidth(base: Float): Float = max(1000f, (base * 1.4f).roundToInt().toFloat())

    fun tableWidth(natural: Float, contentWidth: Float, base: Float): Float =
        min(max(contentWidth, ceil(natural + 2f)), maxCanvasWidth(base) - ROW_PADDING)

    fun canvasWidth(base: Float, tableRowWidths: List<Float>): Float =
        min(max(base, tableRowWidths.maxOrNull() ?: 0f), maxCanvasWidth(base))
}
