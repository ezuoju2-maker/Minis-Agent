package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.isSpecified

internal object UsageTapBand {
    const val LINES = 5

    fun endsWithText(lastKind: String?, lastHasContent: Boolean): Boolean =
        lastKind == "text" && lastHasContent

    fun lastLineHeightPx(lastLineTop: Float?, lastLineBottom: Float?, styleLineHeightPx: Float?): Float {
        val box = if (lastLineTop != null && lastLineBottom != null) lastLineBottom - lastLineTop else 0f
        return maxOf(box, styleLineHeightPx?.takeIf { it > 0f } ?: 0f)
    }

    fun band(anchorBottomPx: Float?, lastLineHeightPx: Float): BlankTapGeometry.Band {
        if (anchorBottomPx == null || anchorBottomPx.isNaN() || !(lastLineHeightPx > 0f)) return BlankTapGeometry.Band.None
        return BlankTapGeometry.Band.Range(top = anchorBottomPx - LINES * lastLineHeightPx, bottom = anchorBottomPx)
    }
}

internal data class UsageTapBandRow(val messageId: String, val isLastRow: Boolean)

internal val LocalUsageTapBandRow = compositionLocalOf<UsageTapBandRow?> { null }

internal val LocalUsageTapBandRegistry = staticCompositionLocalOf<UsageTapBandRegistry?> { null }

internal class UsageTapBandRegistry {
    class TextNode(val coords: () -> LayoutCoordinates?, val layout: () -> TextLayoutResult?)

    private val lastRows = HashMap<String, LayoutCoordinates>()
    private val lastRowTexts = HashMap<String, ArrayList<TextNode>>()

    fun setLastRow(messageId: String, coords: LayoutCoordinates) { lastRows[messageId] = coords }

    fun clearLastRow(messageId: String, coords: LayoutCoordinates?) {
        if (coords == null || lastRows[messageId] === coords) lastRows.remove(messageId)
    }

    fun addText(messageId: String, node: TextNode) { lastRowTexts.getOrPut(messageId) { ArrayList() }.add(node) }

    fun removeText(messageId: String, node: TextNode) {
        val list = lastRowTexts[messageId] ?: return
        list.remove(node)
        if (list.isEmpty()) lastRowTexts.remove(messageId)
    }

    fun bandFor(row: UsageTapBandRow?, tapped: LayoutCoordinates?, tappedLayout: TextLayoutResult): BlankTapGeometry.Band {
        if (row == null || tapped == null || !tapped.isAttached) return BlankTapGeometry.Band.None
        val last = lastRows[row.messageId]?.takeIf { it.isAttached } ?: return BlankTapGeometry.Band.None
        val anchorBottom = runCatching {
            tapped.localPositionOf(last, Offset(0f, last.size.height.toFloat())).y
        }.getOrNull() ?: return BlankTapGeometry.Band.None
        val unitLayout = lastRowTexts[row.messageId]
            ?.mapNotNull { n -> n.coords()?.takeIf { it.isAttached }?.let { c -> n.layout()?.let { l -> c to l } } }
            ?.maxByOrNull { (c, _) -> runCatching { tapped.localPositionOf(c, Offset(0f, c.size.height.toFloat())).y }.getOrDefault(Float.NEGATIVE_INFINITY) }
            ?.second
            ?: tappedLayout
        return UsageTapBand.band(anchorBottom, lastLineHeightPx(unitLayout))
    }

    companion object {
        fun lastLineHeightPx(layout: TextLayoutResult): Float {
            val last = layout.lineCount - 1
            val style = layout.layoutInput.style.lineHeight
            val stylePx = if (style.isSpecified && style.isSp) with(layout.layoutInput.density) { style.toPx() } else null
            return UsageTapBand.lastLineHeightPx(
                lastLineTop = if (last >= 0) layout.getLineTop(last) else null,
                lastLineBottom = if (last >= 0) layout.getLineBottom(last) else null,
                styleLineHeightPx = stylePx,
            )
        }
    }
}

@Composable
internal fun UsageTapBandRowScope(
    messageId: String,
    inFinalTextBlock: Boolean,
    isLastRow: Boolean,
    content: @Composable () -> Unit,
) {
    val registry = LocalUsageTapBandRegistry.current
    val inBand = inFinalTextBlock && registry != null
    val id = messageId.substringBefore('#')
    val row = remember(inBand, id, isLastRow) { if (inBand) UsageTapBandRow(id, isLastRow) else null }
    val reportsPosition = inBand && isLastRow
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    CompositionLocalProvider(LocalUsageTapBandRow provides row) {
        Box(
            modifier = if (reportsPosition && registry != null) {
                Modifier.onGloballyPositioned {
                    holder[0] = it
                    registry.setLastRow(id, it)
                }
            } else {
                Modifier
            },
        ) { content() }
    }
    DisposableEffect(registry, id, reportsPosition) {
        onDispose { if (reportsPosition) registry?.clearLastRow(id, holder[0]) }
    }
}
