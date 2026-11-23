package com.openminis.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextLayoutResult


data class TextShardId(
    val messageId: String,
    val shardId: String,
)

class TextShard(
    val id: TextShardId,
    val plainText: String,
    val textLayoutResult: TextLayoutResult,
    val positionInWindow: () -> Offset,
    val sizePx: () -> androidx.compose.ui.unit.IntSize,
    val renderedToRawOffset: ((Int) -> Int)? = null,
    val rawMarkdown: String? = null,
    val isAtomicUnit: Boolean = false,
)

data class TextPosition(
    val shard: TextShardId,
    val charOffset: Int,
)

data class TextSelection(
    val start: TextPosition,
    val end: TextPosition,
)

class SelectionController {
    enum class Handle { Start, End }

    val selection = mutableStateOf<TextSelection?>(null)

    val selectionFromMouse = mutableStateOf(false)

    private val messageMarkdownCache = androidx.compose.runtime.mutableStateMapOf<String, String>()

    fun rememberMessageMarkdown(messageId: String, markdown: String) {
        if (markdown.isEmpty()) return
        if (messageMarkdownCache[messageId] == markdown) return
        messageMarkdownCache[messageId] = markdown
    }

    fun selectionMessageMarkdown(): String? {
        val msgId = singleMessageId() ?: return null
        return messageMarkdownCache[msgId]
    }

    class TableActions(
        val copyTableMarkdown: () -> Unit,
        val copyTableImage: () -> Unit,
    )

    private val tableActionsByMessage =
        androidx.compose.runtime.mutableStateMapOf<String, TableActions>()

    fun rememberTableActions(messageId: String, actions: TableActions) {
        tableActionsByMessage[messageId] = actions
    }

    fun forgetTableActions(messageId: String) {
        tableActionsByMessage.remove(messageId)
    }

    fun selectionTableActions(): TableActions? {
        val msgId = singleMessageId() ?: return null
        return tableActionsByMessage[msgId]
    }

    val dragIntent = mutableStateOf<DragIntent?>(null)

    data class DragIntent(val point: Offset, val handle: Handle)

    private val shards = mutableStateMapOf<TextShardId, TextShard>()

    var documentOrder: ((TextShardId) -> DocOrder?)? = null

    private val liveTexts = HashMap<TextShardId, ShardText>()

    private val retired = HashMap<TextShardId, ShardText>()

    fun register(shard: TextShard) {
        shards[shard.id] = shard
        registerText(
            ShardText(shard.id, shard.plainText, shard.rawMarkdown) {
                runCatching { shard.positionInWindow().y }.getOrNull()
            },
        )
    }

    internal fun registerText(text: ShardText) {
        liveTexts[text.id] = text
        retired.remove(text.id)
    }

    fun unregister(id: TextShardId) {
        shards.remove(id)
        val live = liveTexts.remove(id) ?: return
        if (selection.value != null) {
            val lastY = live.y()
            retired[id] = ShardText(live.id, live.plainText, live.rawMarkdown) { lastY }
        }
    }

    internal fun retiredCount(): Int = retired.size

    private fun textOf(id: TextShardId, registry: Map<TextShardId, String> = emptyMap()): String? =
        liveTexts[id]?.plainText ?: retired[id]?.plainText ?: registry[id]

    private fun yOf(id: TextShardId): Float? = liveTexts[id]?.y?.invoke() ?: retired[id]?.y?.invoke()

    internal fun compareShards(a: TextShardId, b: TextShardId): Int? {
        if (a == b) return 0
        documentOrder?.let { order ->
            val oa = order(a)
            val ob = order(b)
            if (oa != null && ob != null) return oa.compareTo(ob)
        }
        sameBlockOrder(a, b)?.let { return it }
        val ya = yOf(a) ?: return null
        val yb = yOf(b) ?: return null
        return ya.compareTo(yb)
    }

    internal fun currentShards(): Map<TextShardId, TextShard> = shards

    fun hitTest(windowPoint: Offset): TextPosition? {
        val (shard, localPoint) = locateShard(windowPoint) ?: return null
        val offset = shard.textLayoutResult.getOffsetForPosition(localPoint)
            .coerceIn(0, shard.plainText.length)
        return TextPosition(shard.id, offset)
    }

    fun hitTestStrict(windowPoint: Offset): TextPosition? {
        for (shard in shards.values) {
            val origin = shard.positionInWindow()
            if (origin == Offset.Zero) continue
            val size = shard.sizePx()
            val rect = Rect(origin, Offset(size.width.toFloat(), size.height.toFloat()).let { d ->
                androidx.compose.ui.geometry.Size(d.x, d.y)
            })
            if (rect.contains(windowPoint)) {
                val local = windowPoint - origin
                val offset = shard.textLayoutResult.getOffsetForPosition(local)
                    .coerceIn(0, shard.plainText.length)
                return TextPosition(shard.id, offset)
            }
        }
        return null
    }

    private fun locateShard(windowPoint: Offset): Pair<TextShard, Offset>? {
        var best: Pair<TextShard, Offset>? = null
        for (shard in shards.values) {
            val origin = shard.positionInWindow()
            val size = shard.sizePx()
            val rect = Rect(origin, Offset(size.width.toFloat(), size.height.toFloat()).let { d ->
                androidx.compose.ui.geometry.Size(d.x, d.y)
            })
            if (rect.contains(windowPoint)) {
                return shard to (windowPoint - origin)
            }
            if (best == null) {
                val clampedX = windowPoint.x.coerceIn(rect.left, rect.right.coerceAtLeast(rect.left))
                val clampedY = windowPoint.y.coerceIn(rect.top, rect.bottom.coerceAtLeast(rect.top))
                best = shard to Offset(clampedX - origin.x, clampedY - origin.y)
            } else {
                val (curBest, _) = best!!
                val curOrigin = curBest.positionInWindow()
                val curSize = curBest.sizePx()
                val curCenterY = curOrigin.y + curSize.height / 2f
                val newCenterY = origin.y + size.height / 2f
                if (kotlin.math.abs(newCenterY - windowPoint.y) <
                    kotlin.math.abs(curCenterY - windowPoint.y)
                ) {
                    val clampedX = windowPoint.x.coerceIn(rect.left, rect.right.coerceAtLeast(rect.left))
                    val clampedY = windowPoint.y.coerceIn(rect.top, rect.bottom.coerceAtLeast(rect.top))
                    best = shard to Offset(clampedX - origin.x, clampedY - origin.y)
                }
            }
        }
        return best
    }

    fun beginSelection(pos: TextPosition) {
        retired.clear()
        selection.value = TextSelection(start = pos, end = pos)
    }

    fun beginSelectionWord(pos: TextPosition) {
        retired.clear()
        val shard = currentShards()[pos.shard]
        if (shard == null) {
            beginSelection(pos)
            return
        }
        val text = shard.plainText
        if (text.isEmpty()) {
            beginSelection(pos)
            return
        }
        if (shard.isAtomicUnit) {
            val start = text.indexOfFirst { !it.isWhitespace() }
            if (start < 0) {
                beginSelection(pos)
                return
            }
            val end = text.indexOfLast { !it.isWhitespace() } + 1
            selection.value = TextSelection(
                start = TextPosition(pos.shard, start),
                end = TextPosition(pos.shard, end),
            )
            return
        }
        val (lo, hi) = wordBoundsAt(text, pos.charOffset)
        if (hi <= lo) {
            beginSelection(pos)
            return
        }
        selection.value = TextSelection(
            start = TextPosition(pos.shard, lo),
            end = TextPosition(pos.shard, hi),
        )
    }

    private fun wordBoundsAt(text: String, offset: Int): Pair<Int, Int> {
        if (text.isEmpty()) return 0 to 0
        val len = text.length
        val clamped = offset.coerceIn(0, len)

        val sentenceStops = setOf(
            '。', '？', '！', '；', '：', '，', '、',
            '.', '?', '!', ';', ':', ',',
            '\n', '\r',
        )
        var lo = clamped.coerceAtMost(len - 1).coerceAtLeast(0)
        while (lo > 0 && text[lo - 1] !in sentenceStops) lo--
        while (lo < len && text[lo].isWhitespace()) lo++
        var hi = clamped.coerceIn(0, len)
        while (hi < len && text[hi] !in sentenceStops) hi++
        if (hi < len) hi++
        while (hi > lo && text[hi - 1].isWhitespace()) hi--
        if (hi <= lo) {
            val s = clamped.coerceIn(0, (len - 1).coerceAtLeast(0))
            return s to (s + 1).coerceAtMost(len)
        }
        return lo to hi
    }

    fun extendSelectionTo(pos: TextPosition) {
        val cur = selection.value ?: return
        selection.value = cur.copy(end = pos)
    }

    fun replaceStart(pos: TextPosition) {
        val cur = selection.value ?: return
        if (cur.start == pos) return
        selection.value = cur.copy(start = pos)
    }

    fun replaceEnd(pos: TextPosition) {
        val cur = selection.value ?: return
        if (cur.end == pos) return
        selection.value = cur.copy(end = pos)
    }

    fun clearSelection() {
        selection.value = null
        retired.clear()
        messageMarkdownCache.clear()
        contextMenuRequest.value = null
    }

    val contextMenuRequest = mutableStateOf<Offset?>(null)

    fun requestContextMenu(windowPoint: Offset) {
        contextMenuRequest.value = windowPoint
    }

    fun dismissContextMenu() {
        contextMenuRequest.value = null
    }

    fun selectionContains(pos: TextPosition): Boolean {
        val sel = selection.value ?: return false
        val (first, last) = orderedEndpoints(sel) ?: return false
        if (first.shard == last.shard) {
            return pos.shard == first.shard &&
                pos.charOffset >= first.charOffset &&
                pos.charOffset <= last.charOffset
        }
        return when (pos.shard) {
            first.shard -> pos.charOffset >= first.charOffset
            last.shard -> pos.charOffset <= last.charOffset
            else -> isShardBetween(first.shard, last.shard, pos.shard)
        }
    }

    fun singleMessageId(): String? {
        val sel = selection.value ?: return null
        val a = sel.start.shard.messageId
        val b = sel.end.shard.messageId
        return if (a == b) a else null
    }

    fun handleAnchor(handle: Handle): Offset? {
        val sel = selection.value ?: return null
        val ordered = orderedEndpoints(sel) ?: return null
        val (first, last) = ordered
        val endpoint = when (handle) {
            Handle.Start -> first
            Handle.End -> last
        }
        val shard = shards[endpoint.shard] ?: return null
        val origin = shard.positionInWindow()
        if (origin == Offset.Zero) return null
        val tlr = shard.textLayoutResult
        val laidOutLen = tlr.layoutInput.text.length
        val clampedOffset = endpoint.charOffset.coerceIn(0, laidOutLen)
        if (laidOutLen <= 0) return null
        val box = runCatching {
            when (handle) {
                Handle.Start -> {
                    val charForStart = clampedOffset.coerceAtMost(laidOutLen - 1).coerceAtLeast(0)
                    tlr.getBoundingBox(charForStart)
                }
                Handle.End -> {
                    val charForEnd = (clampedOffset - 1).coerceIn(0, laidOutLen - 1)
                    tlr.getBoundingBox(charForEnd)
                }
            }
        }.getOrNull() ?: return null
        val (lx, by) = when (handle) {
            Handle.Start -> box.left to box.bottom
            Handle.End -> box.right to box.bottom
        }
        val result = Offset(origin.x + lx, origin.y + by)
        return result
    }

    fun grabHandleAt(p: Offset, hitSlopPx: Float): Handle? {
        val start = handleAnchor(Handle.Start)
        val end = handleAnchor(Handle.End)
        fun within(anchor: Offset?): Float {
            if (anchor == null) return Float.MAX_VALUE
            val cx = anchor.x
            val cy = anchor.y + hitSlopPx / 2f
            val dx = p.x - cx
            val dy = p.y - cy
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
        val ds = within(start)
        val de = within(end)
        val nearest = if (ds <= de) Handle.Start to ds else Handle.End to de
        return if (nearest.second <= hitSlopPx * 1.5f) nearest.first else null
    }

    private fun distance(a: Offset, b: Offset): Float {
        val dx = a.x - b.x; val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun selectionWindowRect(): Rect? {
        val sel = selection.value ?: return null
        val ordered = orderedEndpoints(sel) ?: return null
        val (first, last) = ordered
        val firstShard = shards[first.shard] ?: return null
        val lastShard = shards[last.shard] ?: return null
        val firstBox = firstShard.textLayoutResult.getBoundingBox(
            first.charOffset.coerceIn(0, firstShard.textLayoutResult.layoutInput.text.length - 1)
                .coerceAtLeast(0)
        )
        val lastBox = lastShard.textLayoutResult.getBoundingBox(
            last.charOffset.coerceIn(0, lastShard.textLayoutResult.layoutInput.text.length - 1)
                .coerceAtLeast(0)
        )
        val topLeft = firstShard.positionInWindow() + Offset(firstBox.left, firstBox.top)
        val bottomRight = lastShard.positionInWindow() + Offset(lastBox.right, lastBox.bottom)
        return Rect(topLeft, bottomRight)
    }

    fun handlesCenterX(): Float? {
        val s = handleAnchor(Handle.Start)
        val e = handleAnchor(Handle.End)
        return when {
            s != null && e != null -> (s.x + e.x) / 2f
            s != null -> s.x
            e != null -> e.x
            else -> null
        }
    }

    fun draggedHandleLineRect(handle: Handle): Rect? {
        val sel = selection.value ?: return null
        val ordered = orderedEndpoints(sel) ?: return null
        val endpoint = when (handle) {
            Handle.Start -> ordered.first
            Handle.End -> ordered.second
        }
        val shard = shards[endpoint.shard] ?: return null
        val origin = shard.positionInWindow()
        if (origin == Offset.Zero) return null
        val tlr = shard.textLayoutResult
        val len = tlr.layoutInput.text.length
        if (len <= 0) return null
        val clamped = endpoint.charOffset.coerceIn(0, len)
        val charIdx = when (handle) {
            Handle.Start -> clamped.coerceAtMost(len - 1).coerceAtLeast(0)
            Handle.End -> (clamped - 1).coerceIn(0, len - 1)
        }
        val box = runCatching { tlr.getBoundingBox(charIdx) }.getOrNull() ?: return null
        return Rect(
            left = origin.x + box.left,
            top = origin.y + box.top,
            right = origin.x + box.right,
            bottom = origin.y + box.bottom,
        )
    }

    fun visibleSelectionEndLineRect(): Rect? {
        val sel = selection.value ?: return null
        val ordered = orderedEndpoints(sel) ?: return null
        val (first, last) = ordered

        val lastShard = shards[last.shard]
        if (lastShard != null && lastShard.positionInWindow() != Offset.Zero) {
            val tlr = lastShard.textLayoutResult
            val len = tlr.layoutInput.text.length
            if (len > 0) {
                val charIdx = (last.charOffset - 1).coerceIn(0, len - 1)
                val box = runCatching { tlr.getBoundingBox(charIdx) }.getOrNull()
                if (box != null) {
                    val origin = lastShard.positionInWindow()
                    return Rect(
                        left = origin.x + box.left,
                        top = origin.y + box.top,
                        right = origin.x + box.right,
                        bottom = origin.y + box.bottom,
                    )
                }
            }
        }

        var bestShard: TextShard? = null
        var bestBottom = Float.NEGATIVE_INFINITY
        for ((id, shard) in shards) {
            val origin = shard.positionInWindow()
            if (origin == Offset.Zero) continue
            val isFirst = id == first.shard
            val isLast = id == last.shard
            val isBetween = !isFirst && !isLast &&
                isShardBetween(first.shard, last.shard, id)
            if (!isFirst && !isLast && !isBetween) continue
            val bottom = origin.y + shard.sizePx().height
            if (bottom > bestBottom) {
                bestBottom = bottom
                bestShard = shard
            }
        }
        val tail = bestShard ?: return null
        val tlr = tail.textLayoutResult
        val len = tlr.layoutInput.text.length
        if (len <= 0) return null
        val lineCount = tlr.lineCount
        if (lineCount <= 0) return null
        val lastLine = lineCount - 1
        val lineTop = runCatching { tlr.getLineTop(lastLine) }.getOrNull() ?: return null
        val lineBottom = runCatching { tlr.getLineBottom(lastLine) }.getOrNull() ?: return null
        val origin = tail.positionInWindow()
        return Rect(
            left = origin.x,
            top = origin.y + lineTop,
            right = origin.x + tail.sizePx().width,
            bottom = origin.y + lineBottom,
        )
    }

    fun visibleSelectionWindowRect(): Rect? {
        val sel = selection.value ?: return null
        val ordered = orderedEndpoints(sel) ?: return null
        val (first, last) = ordered
        var minL = Float.POSITIVE_INFINITY
        var minT = Float.POSITIVE_INFINITY
        var maxR = Float.NEGATIVE_INFINITY
        var maxB = Float.NEGATIVE_INFINITY
        for ((id, shard) in shards) {
            val isFirstHere = id == first.shard
            val isLastHere = id == last.shard
            val isBetween = !isFirstHere && !isLastHere &&
                isShardBetween(first.shard, last.shard, id)
            if (!isFirstHere && !isLastHere && !isBetween) continue
            val tlr = shard.textLayoutResult
            val laidOutLen = tlr.layoutInput.text.length
            if (laidOutLen <= 0) continue
            val from = if (isFirstHere) first.charOffset.coerceIn(0, laidOutLen) else 0
            val to = if (isLastHere) last.charOffset.coerceIn(0, laidOutLen) else laidOutLen
            val lo = minOf(from, to)
            val hi = maxOf(from, to)
            if (hi <= lo) continue
            val origin = shard.positionInWindow()
            if (origin == Offset.Zero) continue
            val startBox = runCatching {
                tlr.getBoundingBox((lo).coerceAtMost(laidOutLen - 1).coerceAtLeast(0))
            }.getOrNull() ?: continue
            val endBox = runCatching {
                tlr.getBoundingBox((hi - 1).coerceIn(0, laidOutLen - 1))
            }.getOrNull() ?: continue
            val left = origin.x + minOf(startBox.left, endBox.left)
            val top = origin.y + minOf(startBox.top, endBox.top)
            val right = origin.x + maxOf(startBox.right, endBox.right)
            val bottom = origin.y + maxOf(startBox.bottom, endBox.bottom)
            if (left < minL) minL = left
            if (top < minT) minT = top
            if (right > maxR) maxR = right
            if (bottom > maxB) maxB = bottom
        }
        if (!minL.isFinite() || !maxR.isFinite() || maxR <= minL || maxB <= minT) return null
        return Rect(minL, minT, maxR, maxB)
    }

    fun orderedEndpoints(sel: TextSelection): Pair<TextPosition, TextPosition>? {
        val a = sel.start
        val b = sel.end
        if (a.shard == b.shard) {
            return if (a.charOffset <= b.charOffset) a to b else b to a
        }
        val c = compareShards(a.shard, b.shard) ?: return null
        return if (c <= 0) a to b else b to a
    }

    fun selectedPlainText(documentRegistry: Map<TextShardId, String> = emptyMap()): String {
        val sel = selection.value ?: return ""

        val ordered = orderedEndpoints(sel) ?: run {
            val walked = crossShardSelectedText(sel.start, sel.end, documentRegistry)
            return walked.ifEmpty { collapsedFallback(sel) }
        }
        val (first, last) = ordered

        if (first.shard == last.shard) {
            val txt = textOf(first.shard, documentRegistry) ?: return ""
            val a = first.charOffset.coerceIn(0, txt.length)
            val b = last.charOffset.coerceIn(0, txt.length)
            return txt.substring(minOf(a, b), maxOf(a, b))
        }

        return crossShardSelectedText(first, last, documentRegistry)
    }

    private fun crossShardSelectedText(
        first: TextPosition,
        last: TextPosition,
        documentRegistry: Map<TextShardId, String>,
    ): String {
        val sb = StringBuilder()
        val visitedOrder = knownShardsInOrder(documentRegistry)
        var started = false
        for (id in visitedOrder) {
            val txt = textOf(id, documentRegistry) ?: continue
            when (id) {
                first.shard -> {
                    sb.append(txt.substring(first.charOffset.coerceIn(0, txt.length), txt.length))
                    started = true
                }
                last.shard -> {
                    if (started) sb.append('\n')
                    sb.append(txt.substring(0, last.charOffset.coerceIn(0, txt.length)))
                    started = true
                    break
                }
                else -> if (started) {
                    sb.append('\n').append(txt)
                }
            }
        }
        val walk = sb.toString()

        if (first.shard.messageId == last.shard.messageId) {
            spliceNonShardSpan(first, last, walk, documentRegistry)?.let { return it }
        }
        return walk
    }

    private fun spliceNonShardSpan(
        first: TextPosition,
        last: TextPosition,
        walk: String,
        documentRegistry: Map<TextShardId, String>,
    ): String? {
        val md = messageMarkdownCache[first.shard.messageId]?.takeIf { it.isNotEmpty() } ?: return null

        val firstText = textOf(first.shard, documentRegistry) ?: return null
        val lastText = textOf(last.shard, documentRegistry) ?: return null

        val headSel = firstText.substring(first.charOffset.coerceIn(0, firstText.length))
        val tailSel = lastText.substring(0, last.charOffset.coerceIn(0, lastText.length))

        val headAnchor = anchorFor(headSel, fromStart = true)
        val tailAnchor = anchorFor(tailSel, fromStart = false)
        if (headAnchor.isEmpty() || tailAnchor.isEmpty()) return null

        val startIdx = md.indexOf(headAnchor)
        if (startIdx < 0) return null
        val tailIdx = md.indexOf(tailAnchor, startIdx + headAnchor.length)
        if (tailIdx < 0) return null
        val endIdx = tailIdx + tailAnchor.length
        if (endIdx <= startIdx) return null

        val slice = md.substring(startIdx, endIdx)
        return if (sliceCrossesNonShardBlock(slice)) slice else null
    }

    private fun sliceCrossesNonShardBlock(slice: String): Boolean {
        if (slice.contains("```") || slice.contains("~~~")) return true
        if (slice.contains("$$")) return true
        val tableDelimiter = Regex("""(?m)^\s*\|?\s*:?-{3,}.*\|""")
        return tableDelimiter.containsMatchIn(slice)
    }

    private fun anchorFor(selected: String, fromStart: Boolean): String {
        val trimmed = selected.trim()
        if (trimmed.isEmpty()) return ""
        val cap = 24
        return if (trimmed.length <= cap) trimmed
        else if (fromStart) trimmed.substring(0, cap)
        else trimmed.substring(trimmed.length - cap)
    }

    private fun collapsedFallback(sel: TextSelection): String {
        val txt = textOf(sel.start.shard) ?: return ""
        val a = sel.start.charOffset.coerceIn(0, txt.length)
        val b = sel.end.charOffset.coerceIn(0, txt.length)
        return txt.substring(minOf(a, b), maxOf(a, b))
    }

    private fun knownShardsInOrder(registry: Map<TextShardId, String>): List<TextShardId> {
        val order = documentOrder
        return (liveTexts.keys + retired.keys + registry.keys)
            .sortedWith(compareBy<TextShardId>({ order?.invoke(it) }, { yOf(it) }))
    }
}

val LocalMinisSelectionController = compositionLocalOf<SelectionController?> { null }

@Composable
fun ProvideSelectionController(
    controller: SelectionController,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalMinisSelectionController provides controller) {
        content()
    }
}

@Composable
fun RegisterSelectionShard(shard: TextShard?) {
    val controller = LocalMinisSelectionController.current ?: return
    if (shard == null) return
    DisposableEffect(controller, shard.id, shard) {
        controller.register(shard)
        onDispose { controller.unregister(shard.id) }
    }
}

fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSelectionForShard(
    shardId: TextShardId,
    result: TextLayoutResult,
    selection: TextSelection,
    controller: SelectionController?,
    color: androidx.compose.ui.graphics.Color,
) {
    val laidOutText = result.layoutInput.text.text
    val maxOffset = laidOutText.length
    val lineCount = result.lineCount
    if (maxOffset == 0 || lineCount == 0) return

    val ordered = controller?.orderedEndpoints(selection) ?: run {
        val a = selection.start
        val b = selection.end
        if (a.shard == b.shard && a.charOffset > b.charOffset) b to a else a to b
    }
    val (first, last) = ordered

    val isFirstHere = first.shard == shardId
    val isLastHere = last.shard == shardId
    if (!isFirstHere && !isLastHere) {
        if (controller == null) {
            return
        }
        val between = controller.isShardBetween(first.shard, last.shard, shardId)
        if (!between) return
    }

    val from = if (isFirstHere) first.charOffset.coerceIn(0, maxOffset) else 0
    val to = if (isLastHere) last.charOffset.coerceIn(0, maxOffset) else maxOffset
    val lo = minOf(from, to)
    val hi = maxOf(from, to)
    if (hi <= lo) return

    val startLine = result.getLineForOffset(lo).coerceIn(0, lineCount - 1)
    val endLine = result.getLineForOffset((hi - 1).coerceAtLeast(0)).coerceIn(0, lineCount - 1)
    if (endLine < startLine) return

    for (line in startLine..endLine) {
        val lineStart = if (line == startLine) lo else result.getLineStart(line)
        val lineEndRaw = if (line == endLine) hi else result.getLineEnd(line)
        val lineEnd = lineEndRaw.coerceAtMost(maxOffset)
        if (lineEnd <= lineStart) continue
        var left = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        for (offset in lineStart until lineEnd) {
            val box = result.getBoundingBox(offset)
            if (box.width <= 0f) continue
            if (box.left < left) left = box.left
            if (box.right > right) right = box.right
        }
        if (!left.isFinite() || right <= left) continue
        val isFinalLineHere = line == endLine && isLastHere
        val drawRight = if (!isFinalLineHere) {
            val lineRight = result.getLineRight(line)
            maxOf(right, lineRight)
        } else right
        val top = result.getLineTop(line)
        val bottom = result.getLineBottom(line)
        drawRect(
            color = color,
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(drawRight - left, bottom - top),
        )
    }
}

internal fun sameBlockOrder(a: TextShardId, b: TextShardId): Int? {
    if (a.messageId != b.messageId) return null
    fun parse(id: TextShardId): Triple<String, Int, Int>? {
        val s = id.shardId
        if (!s.startsWith("mdblock:")) return null
        val hash = s.lastIndexOf('#')
        val base = if (hash >= 0) s.substring(0, hash) else s
        val colon = base.lastIndexOf(':')
        if (colon <= "mdblock:".length - 1) return null
        val index = base.substring(colon + 1).toIntOrNull() ?: return null
        return Triple(base.substring(0, colon), index, shardSubIndex(s))
    }
    val pa = parse(a) ?: return null
    val pb = parse(b) ?: return null
    if (pa.first != pb.first) return null
    return compareValuesBy(pa, pb, { it.second }, { it.third })
}

internal fun SelectionController.isShardBetween(
    a: TextShardId,
    b: TextShardId,
    middle: TextShardId,
): Boolean {
    val ab = compareShards(a, b) ?: return false
    val (lo, hi) = if (ab <= 0) a to b else b to a
    val loToMiddle = compareShards(lo, middle) ?: return false
    val middleToHi = compareShards(middle, hi) ?: return false
    return loToMiddle <= 0 && middleToHi <= 0
}

fun buildTextShard(
    id: TextShardId,
    plainText: String,
    layoutResult: TextLayoutResult,
    coordinatesProvider: () -> LayoutCoordinates?,
    rawMarkdown: String? = null,
    renderedToRawOffset: ((Int) -> Int)? = null,
    isAtomicUnit: Boolean = false,
): TextShard = TextShard(
    id = id,
    plainText = plainText,
    textLayoutResult = layoutResult,
    isAtomicUnit = isAtomicUnit,
    positionInWindow = {
        val c = coordinatesProvider()
        if (c != null && c.isAttached) c.positionInWindow() else Offset.Zero
    },
    sizePx = {
        val c = coordinatesProvider()
        if (c != null && c.isAttached) {
            androidx.compose.ui.unit.IntSize(c.size.width, c.size.height)
        } else {
            androidx.compose.ui.unit.IntSize.Zero
        }
    },
    renderedToRawOffset = renderedToRawOffset,
    rawMarkdown = rawMarkdown,
)
