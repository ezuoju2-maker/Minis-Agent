package com.openminis.app.ui.chat

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal object ToggleAnchorMath {
    fun keepTopScroll(oldHeight: Int, newHeight: Int): Float = (newHeight - oldHeight).toFloat()

    fun revealShift(cardTop: Float, cardBottom: Float, visibleTop: Float, visibleBottom: Float): Float {
        val overflow = cardBottom - visibleBottom
        if (overflow <= 0f) return 0f
        return minOf(overflow, cardTop - visibleTop).coerceAtLeast(0f)
    }

    fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).let { it * it * it } / 2
    }

    fun heightAt(from: Int, to: Int, t: Float): Int = from + Math.round((to - from) * ease(t))

    fun lazyIndex(flatPos: Int, flatSize: Int, leading: Int): Int = (flatSize - 1 - flatPos) + leading

    fun leadingCount(lazyIndex: Int, flatPos: Int, flatSize: Int): Int = lazyIndex - (flatSize - 1 - flatPos)

    fun restoreScrollOffset(offset: Int): Int = -offset
}

internal class ChatToggleAnchor(private val listState: LazyListState) {
    var listCoords: () -> LayoutCoordinates? = { null }
    var bottomInsetPx: Float = 0f
    var scope: CoroutineScope? = null

    @Volatile private var busyUntilMs = 0L
    val isBusy: Boolean get() = android.os.SystemClock.uptimeMillis() < busyUntilMs
    fun markBusy(forMs: Long = BUSY_TAIL_MS) {
        busyUntilMs = maxOf(busyUntilMs, android.os.SystemClock.uptimeMillis() + forMs)
    }

    private class PendingRow(val key: String, val offset: Int, val leading: Int, val size: Int)
    private var pendingRow: PendingRow? = null

    fun captureRow(key: String, rows: List<com.openminis.app.ui.chat.FlatChatItem>) {
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val pos = rows.indexOfFirst { it.key == key }
        if (pos < 0) return
        pendingRow = PendingRow(key, info.offset, ToggleAnchorMath.leadingCount(info.index, pos, rows.size), rows.size)
    }

    fun restoreRow(rows: List<com.openminis.app.ui.chat.FlatChatItem>) {
        val p = pendingRow ?: return
        pendingRow = null
        val pos = rows.indexOfFirst { it.key == p.key }
        if (pos < 0) return
        val index = ToggleAnchorMath.lazyIndex(pos, rows.size, p.leading)
        if (index < 0) return
        markBusy()
        listState.requestScrollToItem(index, ToggleAnchorMath.restoreScrollOffset(p.offset))
        val added = rows.size - p.size
        if (added > 0) scope?.launch { revealRows(p.key, index, added) }
    }

    private suspend fun revealRows(key: String, index: Int, added: Int) {
        withFrameNanos { }
        withFrameNanos { }
        val info = listState.layoutInfo
        val head = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val last = info.visibleItemsInfo.firstOrNull { it.index == index - added }
        val blockBottom = last?.offset ?: Int.MIN_VALUE / 2
        val shift = ToggleAnchorMath.revealShift(
            cardTop = -(head.offset + head.size).toFloat(),
            cardBottom = -blockBottom.toFloat(),
            visibleTop = -(info.viewportEndOffset - info.afterContentPadding).toFloat(),
            visibleBottom = 0f,
        )
        if (shift > 0f) { markBusy(); listState.animateScrollBy(-shift) }
    }

    fun keepTop(oldHeight: Int, newHeight: Int) {
        markBusy()
        val d = ToggleAnchorMath.keepTopScroll(oldHeight, newHeight)
        if (d != 0f) listState.dispatchRawDelta(d)
    }

    suspend fun reveal(card: LayoutCoordinates?) {
        val list = listCoords() ?: return
        if (card == null || !card.isAttached || !list.isAttached) return
        val box = list.localBoundingBoxOf(card, clipBounds = false)
        val shift = ToggleAnchorMath.revealShift(box.top, box.bottom, 0f, list.size.height - bottomInsetPx)
        if (shift > 0f) { markBusy(); listState.animateScrollBy(-shift) }
    }
}

internal val LocalChatToggleAnchor = staticCompositionLocalOf<ChatToggleAnchor?> { null }

private const val BUSY_TAIL_MS = 450L

internal class TopAnchoredResize(private val durationMs: Long = 260) {
    private var natural = -1
    private var shown: Int? by mutableStateOf(null)
    private var job: Job? = null
    var coords: LayoutCoordinates? = null

    val modifier: Modifier = Modifier
        .clipToBounds()
        .layout { measurable, constraints ->
            val p = measurable.measure(constraints)
            natural = p.height
            val h = shown ?: p.height
            layout(p.width, h) { p.place(0, 0) }
        }

    fun toggle(scope: CoroutineScope, anchor: ChatToggleAnchor?, flip: () -> Unit) {
        val from = shown ?: natural
        job?.cancel()
        if (from < 0) { flip(); return }
        shown = from
        anchor?.markBusy(durationMs + 450)
        flip()
        job = scope.launch {
            withFrameNanos { }
            withFrameNanos { }
            val to = natural
            var current = from
            val start = withFrameNanos { it }
            while (true) {
                val done = withFrameNanos { now ->
                    val t = ((now - start) / 1_000_000f) / durationMs
                    val h = if (t >= 1f) to else ToggleAnchorMath.heightAt(from, to, t)
                    anchor?.keepTop(current, h)
                    current = h
                    shown = h
                    t >= 1f
                }
                if (done) break
            }
            shown = null
            if (to > from) {
                withFrameNanos { }
                anchor?.reveal(coords)
            }
        }
    }
}
