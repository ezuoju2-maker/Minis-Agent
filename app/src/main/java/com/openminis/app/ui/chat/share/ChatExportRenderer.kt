package com.openminis.app.ui.chat.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import coil.compose.LocalImageLoader
import coil.imageLoader
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.LocalMinisSelectionController
import com.openminis.app.ui.theme.DarkChatPalette
import com.openminis.app.ui.theme.LightChatPalette
import com.openminis.app.ui.theme.MinisAppearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val LOG = "ChatExport"


internal data class ChatExportOptions(
    val format: Format = Format.IMAGE,
    val wideLayout: Boolean = false,
    val dark: Boolean = false,
    val adaptiveTables: Boolean = false,
) {
    enum class Format { IMAGE, PDF }
}

internal sealed class ChatExportError(@StringRes val messageRes: Int) : Exception() {
    object NothingToExport : ChatExportError(R.string.chat_share_error_nothing)
    object ImageTooLarge : ChatExportError(R.string.chat_share_error_image_too_large)
    object WriteFailed : ChatExportError(R.string.chat_share_error_write_failed)
}

internal class ChatExportStage(
    private val host: ViewGroup,
    ownerSource: View,
    private val parentContext: CompositionContext,
) {
    val context: Context get() = host.context
    val density: Float get() = host.resources.displayMetrics.density

    private val layer = object : ViewGroup(host.context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                child.measure(
                    MeasureSpec.makeMeasureSpec(max(1, child.layoutParams.width), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                )
            }
            setMeasuredDimension(1, 1)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                child.layout(0, 0, child.measuredWidth, child.measuredHeight)
            }
        }

        override fun dispatchDraw(canvas: Canvas) = Unit
        override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false
    }

    init {
        layer.setViewTreeLifecycleOwner(ownerSource.findViewTreeLifecycleOwner())
        layer.setViewTreeViewModelStoreOwner(ownerSource.findViewTreeViewModelStoreOwner())
        layer.setViewTreeSavedStateRegistryOwner(ownerSource.findViewTreeSavedStateRegistryOwner())
        host.addView(layer, ViewGroup.LayoutParams(1, 1))
    }

    fun dispose() {
        layer.removeAllViews()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            (layer.parent as? ViewGroup)?.removeView(layer)
        }
    }

    class Laid(val view: ComposeView) {
        val widthPx: Int get() = view.width
        val heightPx: Int get() = view.height
    }

    suspend fun layOut(widthPx: Int, minSettleMs: Long, content: @Composable () -> Unit): Laid {
        val view = ComposeView(host.context).apply {
            setParentCompositionContext(parentContext)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent(content)
        }
        layer.addView(view, ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT))
        try {
            settle(view, minSettleMs)
        } catch (t: Throwable) {
            layer.removeView(view)
            throw t
        }
        return Laid(view)
    }

    fun discard(laid: Laid) {
        layer.removeView(laid.view)
    }

    private suspend fun settle(view: View, minMs: Long) {
        var last = -1
        var stable = 0
        var waited = 0L
        while (waited < MAX_SETTLE_MS) {
            delay(SETTLE_STEP_MS)
            waited += SETTLE_STEP_MS
            if (!view.isLaidOut || view.isLayoutRequested) { stable = 0; continue }
            val h = view.height
            if (h > 0 && h == last) stable++ else { stable = 0; last = h }
            if (stable >= 2 && waited >= minMs) return
        }
        AppLogger.warning(LOG, "[ChatExport] layout did not settle in ${MAX_SETTLE_MS}ms (h=${view.height})")
    }

    private companion object {
        const val SETTLE_STEP_MS = 50L
        const val MAX_SETTLE_MS = 4_000L
    }
}

internal class ChatExportBreakSink {
    val itemBottoms = HashMap<Int, Float>()
    val solidSpans = HashMap<Int, Pair<Float, Float>>()
}

@Composable
internal fun ChatExportSurface(dark: Boolean, content: @Composable () -> Unit) {
    MinisAppearance(darkTheme = dark) {
        val context = LocalContext.current
        val loader = remember(context) { context.imageLoader.newBuilder().allowHardware(false).build() }
        @Suppress("DEPRECATION")
        CompositionLocalProvider(
            LocalImageLoader provides loader,
            LocalMinisSelectionController provides null,
            content = content,
        )
    }
}

@Composable
internal fun ChatExportGroupView(
    cells: List<ChatShareCell>,
    messages: Map<String, ChatMessage>,
    chunks: Map<String, List<ChatShareTableChunk>>,
    rowWidthDp: Float,
    canvasWidthDp: Float,
    pageColor: Color,
    sink: ChatExportBreakSink? = null,
    afterUserRow: Boolean = false,
    usageRows: Set<String> = emptySet(),
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(ChatShareSpacing.ROW_GAP.dp),
        modifier = Modifier.width(canvasWidthDp.dp).background(pageColor),
    ) {
        cells.forEachIndexed { index, cell ->
            val message = messages[cell.messageId] ?: return@forEachIndexed
            val above = cells.getOrNull(index - 1)
            if (above != null && above.messageId != cell.messageId && above.messageId in usageRows) {
                Spacer(Modifier.height(0.dp))
            }
            val solid = cell.kind != ChatShareCell.Kind.TEXT &&
                ChatShareContent.spacingEdge(cell, message) != ChatShareSpacing.Edge.BUBBLE
            val edge = if (sink == null) Modifier else Modifier.onGloballyPositioned { c ->
                val top = c.positionInRoot().y
                sink.itemBottoms[index] = top + c.size.height
                if (solid) sink.solidSpans[index] = top to top + c.size.height
            }
            val keepsReadingWidth = cell.kind == ChatShareCell.Kind.USER || cell.kind == ChatShareCell.Kind.CALLBACK
            val width = if (keepsReadingWidth) rowWidthDp else canvasWidthDp
            if (cell.kind == ChatShareCell.Kind.USER) {
                Box(modifier = edge.width(canvasWidthDp.dp), contentAlignment = Alignment.TopEnd) {
                    ChatShareCellContent(
                        cell, message, Modifier.width(width.dp),
                        precededByUser = index == 0 && afterUserRow,
                    )
                }
            } else {
                ChatShareCellContent(cell, message, edge.width(width.dp), tableChunks = chunks[cell.id])
            }
        }
    }
}

internal class ChatExportRenderer(
    private val stage: ChatExportStage,
    private val prewarmMarkdown: suspend (List<String>) -> Unit,
) {
    class Result(
        val file: File,
        val width: Int,
        val height: Int,
        val scale: Float,
        val pageCount: Int,
        val units: Int,
    )

    private class Part(
        val markdown: List<String>,
        val minSettleMs: Long,
        val sink: ChatExportBreakSink?,
        val gapBefore: Float = 0f,
        val content: @Composable () -> Unit,
    )

    suspend fun render(
        groups: List<List<ChatShareCell>>,
        messages: List<ChatMessage>,
        title: String,
        subtitle: List<String>,
        options: ChatExportOptions,
        progress: (Int, Int) -> Unit,
    ): Result {
        val started = System.currentTimeMillis()
        val byId = LinkedHashMap<String, ChatMessage>().also { map -> messages.forEach { map.putIfAbsent(it.id, it) } }
        val pageColor = if (options.dark) DarkChatPalette.background else LightChatPalette.background
        val base = rowWidthDp(options.wideLayout, stage.context)
        val kept = groups.map { g -> g.filter { byId[it.messageId] != null } }.filter { it.isNotEmpty() }
        if (kept.isEmpty()) throw ChatExportError.NothingToExport
        var chunks = if (options.adaptiveTables) tableChunks(kept, byId, base, options.dark) else emptyMap()
        val canvas = ChatShareGeometry.canvasWidth(
            base, chunks.values.flatten().map { it.widthDp + ChatShareGeometry.ROW_PADDING },
        )
        val proseWidth = canvas - ChatShareGeometry.ROW_PADDING
        chunks = chunks.mapValues { (_, split) ->
            split.map { if (it.isTable) it else ChatShareTableChunk(it.text, proseWidth) }
        }
        val header = Part(emptyList(), BASE_SETTLE_MS, null) {
            ChatExportSurface(options.dark) { ChatExportHeaderView(title, subtitle, canvas, pageColor) }
        }
        val edges = kept.map { group -> ChatShareContent.spacingEdge(group[0], byId.getValue(group[0].messageId)) }
        val speakers = messages.map(ChatShareContent::speaker)
        val chatIndex = HashMap<String, Int>().also { map -> messages.forEachIndexed { i, m -> map.putIfAbsent(m.id, i) } }
        val usageRows = byId.values.filter(ChatShareContent::hasUsageRow).mapTo(HashSet()) { it.id }
        val parts = listOf(header) + kept.mapIndexed { groupIndex, group ->
            val texts = group.filter { it.kind == ChatShareCell.Kind.TEXT }.flatMap { cell ->
                chunks[cell.id]?.map { it.text }
                    ?: byId[cell.messageId]?.let { ChatShareContent.block(cell, it) }
                        ?.let { ChatShareContent.textFragments(it) }.orEmpty()
            }
            val waitsForMedia = group.any { cell ->
                val m = byId[cell.messageId]
                cell.kind == ChatShareCell.Kind.USER && m != null && m.imageUris.isNotEmpty()
            } || texts.any { it.contains('$') || it.contains("\\(") || it.contains("\\[") || it.contains("![") }
            val sink = ChatExportBreakSink()
            val previous = edges.getOrNull(groupIndex - 1)
            val header = edges[groupIndex] == ChatShareSpacing.Edge.REPLY &&
                chatIndex[group[0].unitId]?.let { ChatShareSpacing.headerAbove(speakers, it) } ?: true
            val usageAbove = kept.getOrNull(groupIndex - 1)?.last()?.messageId in usageRows
            val gap = if (previous == null) 0f else ChatShareSpacing.gapBetween(usageAbove, header)
            Part(texts, if (waitsForMedia) MEDIA_SETTLE_MS else BASE_SETTLE_MS, sink, gap) {
                ChatExportSurface(options.dark) {
                    ChatExportGroupView(
                        group, byId, chunks, base, canvas, pageColor, sink,
                        afterUserRow = ChatShareSpacing.followsUserRow(previous),
                        usageRows = usageRows,
                    )
                }
            }
        }
        AppLogger.info(
            LOG,
            "[ChatExport] start format=${options.format} groups=${kept.size} cells=${kept.sumOf { it.size }} " +
                "canvas=${canvas.roundToInt()} wide=${options.wideLayout} dark=${options.dark} " +
                "adaptiveTables=${options.adaptiveTables} splitBlocks=${chunks.size}",
        )
        val result = when (options.format) {
            ChatExportOptions.Format.IMAGE -> renderImage(parts, canvas, title, pageColor, progress)
            ChatExportOptions.Format.PDF -> renderPdf(parts, canvas, title, pageColor, footerColor(options.dark), progress)
        }
        AppLogger.info(
            LOG,
            "[ChatExport] done format=${options.format} size=${result.width}x${result.height} pages=${result.pageCount} " +
                "bytes=${result.file.length()} ms=${System.currentTimeMillis() - started}",
        )
        return result
    }

    private suspend fun layOut(part: Part, widthPx: Int): ChatExportStage.Laid {
        if (part.markdown.isNotEmpty()) withContext(Dispatchers.Default) { prewarmMarkdown(part.markdown) }
        return stage.layOut(widthPx, part.minSettleMs, part.content)
    }


    private suspend fun renderImage(
        parts: List<Part>,
        canvasDp: Float,
        title: String,
        pageColor: Color,
        progress: (Int, Int) -> Unit,
    ): Result {
        val first = drawImage(parts, canvasDp, title, pageColor, ChatShareImageBudget.PREFERRED_SCALE, progress)
        if (first.second != null) return first.second!!
        val heightDp = first.first
        val scale = ChatShareImageBudget.scale(canvasDp + MARGIN * 2, heightDp)
        if (scale == null) {
            AppLogger.warning(LOG, "[ChatExport] image too large ${(canvasDp + MARGIN * 2).roundToInt()}x${heightDp.roundToInt()}dp")
            throw ChatExportError.ImageTooLarge
        }
        AppLogger.info(LOG, "[ChatExport] image past the budget at 3x, drawing again at ${scale}x")
        return drawImage(parts, canvasDp, title, pageColor, scale, progress).second
            ?: throw ChatExportError.ImageTooLarge
    }

    private suspend fun drawImage(
        parts: List<Part>,
        canvasDp: Float,
        title: String,
        pageColor: Color,
        scale: Float,
        progress: (Int, Int) -> Unit,
    ): Pair<Float, Result?> = coroutineScope {
        val density = stage.density
        val viewWidthPx = (canvasDp * density).roundToInt()
        val outWidth = ceil((canvasDp + MARGIN * 2) * scale).toInt()
        val k = scale / density
        val page = pageColor.toArgb()
        val file = exportFile(stage.context, title, "png")
        var writer: ChatSharePngWriter? = null
        var strip: Bitmap? = null
        val encoder = Dispatchers.Default.limitedParallelismCompat()
        var encoding: Job? = null
        var rows = 0
        var heightDp = 0f
        var overBudget = false
        val maxRows = min(
            ChatShareImageBudget.MAX_PIXEL_HEIGHT,
            ChatShareImageBudget.MAX_PIXELS / outWidth,
        ).toInt()

        suspend fun emit(pixels: IntArray, count: Int) {
            val previous = encoding
            previous?.join()
            val w = writer!!
            encoding = launch(encoder) { w.writeRows(pixels, 0, count) }
            rows += count
        }

        suspend fun blank(dp: Float) {
            heightDp += dp
            if (overBudget) return
            var remaining = (dp * scale).roundToInt()
            while (remaining > 0) {
                val n = min(remaining, STRIP_PX)
                emit(IntArray(outWidth * n) { page }, n)
                remaining -= n
            }
        }

        val trailingRows = ceil(MARGIN * scale).toInt()
        try {
            writer = ChatSharePngWriter(outWidth, File(file.parentFile, "image.idat"))
            val bitmap = Bitmap.createBitmap(outWidth, STRIP_PX, Bitmap.Config.ARGB_8888).also { strip = it }
            val drawCanvas = Canvas(bitmap)
            blank(MARGIN)
            parts.forEachIndexed { index, part ->
                coroutineContext.ensureActive()
                val laid = layOut(part, viewWidthPx)
                try {
                    val partDp = laid.heightPx / density
                    heightDp += partDp
                    if (!overBudget && rows + ceil(partDp * scale).toInt() + trailingRows > maxRows) overBudget = true
                    if (!overBudget) {
                        val outHeight = (partDp * scale).roundToInt()
                        var y = 0
                        while (y < outHeight) {
                            val n = min(STRIP_PX, outHeight - y)
                            bitmap.eraseColor(page)
                            drawCanvas.save()
                            drawCanvas.translate(MARGIN * scale, -y.toFloat())
                            drawCanvas.scale(k, k)
                            try {
                                laid.view.draw(drawCanvas)
                            } catch (e: IllegalArgumentException) {
                                AppLogger.error(LOG, "[ChatExport] draw failed: ${e.message}")
                                throw ChatExportError.WriteFailed
                            } finally {
                                drawCanvas.restore()
                            }
                            val pixels = IntArray(outWidth * n)
                            bitmap.getPixels(pixels, 0, outWidth, 0, 0, outWidth, n)
                            emit(pixels, n)
                            y += n
                            yield()
                        }
                    }
                } finally {
                    stage.discard(laid)
                }
                if (index > 0) progress(index, parts.size - 1)
                if (index < parts.lastIndex) blank(if (index == 0) HEADER_GAP else parts[index + 1].gapBefore)
            }
            blank(MARGIN)
            encoding?.join()
            val w = writer!!
            if (overBudget) {
                w.close()
                file.parentFile?.deleteRecursively()
                return@coroutineScope heightDp to null
            }
            withContext(Dispatchers.IO) { w.finish(file) }
            heightDp to Result(file, outWidth, w.rows, scale, 1, parts.size - 1)
        } catch (t: Throwable) {
            withContext(NonCancellable) { encoding?.cancelAndJoin() }
            writer?.close()
            file.parentFile?.deleteRecursively()
            throw if (t is java.io.IOException) ChatExportError.WriteFailed else t
        } finally {
            strip?.recycle()
        }
    }


    private suspend fun tableChunks(
        groups: List<List<ChatShareCell>>,
        byId: Map<String, ChatMessage>,
        base: Float,
        dark: Boolean,
    ): Map<String, List<ChatShareTableChunk>> {
        val contentWidth = base - ChatShareGeometry.ROW_PADDING
        val splits = LinkedHashMap<String, List<ChatShareMarkdownChunker.Chunk>>()
        for (cell in groups.flatten()) {
            if (cell.kind != ChatShareCell.Kind.TEXT) continue
            val text = byId[cell.messageId]?.let { ChatShareContent.block(cell, it)?.content } ?: continue
            val split = ChatShareMarkdownChunker.split(text)
            if (split.any { it.isTable }) splits[cell.id] = split
        }
        if (splits.isEmpty()) return emptyMap()
        val tables = splits.values.flatten().filter { it.isTable }.map { it.text }
        val naturals = FloatArray(tables.size)
        val probe = Part(tables, BASE_SETTLE_MS, null) {
            ChatExportSurface(dark) {
                Column(modifier = Modifier.width(contentWidth.dp)) {
                    tables.forEachIndexed { i, text ->
                        CompositionLocalProvider(
                            com.openminis.app.ui.chat.LocalTableNaturalWidthSink provides { w -> naturals[i] = w },
                        ) {
                            com.openminis.app.ui.chat.MarkdownBlock(rawText = text, isStreaming = false)
                        }
                    }
                }
            }
        }
        val laid = layOut(probe, (contentWidth * stage.density).roundToInt())
        stage.discard(laid)
        var t = 0
        return splits.mapValues { (_, split) ->
            split.map { chunk ->
                if (!chunk.isTable) return@map ChatShareTableChunk(chunk.text, contentWidth)
                val natural = naturals[t++]
                val width = if (natural > 0f) ChatShareGeometry.tableWidth(natural, contentWidth, base) else contentWidth
                ChatShareTableChunk(chunk.text, width, isTable = true)
            }
        }
    }


    private suspend fun renderPdf(
        parts: List<Part>,
        canvasDp: Float,
        title: String,
        pageColor: Color,
        footerColor: Color,
        progress: (Int, Int) -> Unit,
    ): Result {
        val density = stage.density
        val viewWidthPx = (canvasDp * density).roundToInt()
        val m = MARGIN
        val pageWidth = ceil(canvasDp + m * 2).toInt()
        val pageHeight = (pageWidth * 297f / 210f).roundToInt()
        val contentHeight = pageHeight - m * 2 - PDF_FOOTER
        val file = exportFile(stage.context, title, "pdf")
        val doc = PdfDocument()
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = footerColor.toArgb()
            textSize = 9f
        }
        var openPage: PdfDocument.Page? = null
        var openIndex = -1

        fun closePage() {
            val page = openPage ?: return
            val label = "$title · ${openIndex + 1}"
            val top = pageHeight - m - 12f
            page.canvas.drawText(label, m, top - footerPaint.fontMetrics.ascent, footerPaint)
            doc.finishPage(page)
            openPage = null
        }

        fun ensurePage(index: Int): Canvas {
            while (openIndex < index) {
                closePage()
                openIndex++
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, openIndex + 1).create())
                page.canvas.drawColor(pageColor.toArgb())
                openPage = page
            }
            return openPage!!.canvas
        }

        try {
            val cursor = ChatSharePagination.Cursor()
            parts.forEachIndexed { index, part ->
                kotlin.coroutines.coroutineContext.ensureActive()
                val laid = layOut(part, viewWidthPx)
                try {
                    val heightDp = laid.heightPx / density
                    val breaks = collectBreaks(laid, part.sink, density)
                    val gap = if (index <= 1) UNIT_GAP else part.gapBefore
                    val slices = ChatSharePagination.place(heightDp, breaks, contentHeight, gap, cursor)
                    for (slice in slices) {
                        val canvas = ensurePage(slice.page)
                        canvas.save()
                        canvas.translate(m, m + slice.pageY - slice.unitY)
                        canvas.clipRect(0f, slice.unitY, canvasDp, slice.unitY + slice.height)
                        canvas.scale(1f / density, 1f / density)
                        try {
                            laid.view.draw(canvas)
                        } catch (e: IllegalArgumentException) {
                            AppLogger.error(LOG, "[ChatExport] draw failed: ${e.message}")
                            throw ChatExportError.WriteFailed
                        } finally {
                            canvas.restore()
                        }
                    }
                } finally {
                    stage.discard(laid)
                }
                if (index > 0) progress(index, parts.size - 1)
                yield()
            }
            closePage()
            withContext(Dispatchers.IO) { file.outputStream().use { doc.writeTo(it) } }
            return Result(file, pageWidth, pageHeight, 1f, openIndex + 1, parts.size - 1)
        } catch (t: Throwable) {
            file.parentFile?.deleteRecursively()
            throw if (t is java.io.IOException) ChatExportError.WriteFailed else t
        } finally {
            openPage?.let { page -> runCatching { doc.finishPage(page) } }
            runCatching { doc.close() }
        }
    }

    private fun collectBreaks(laid: ChatExportStage.Laid, sink: ChatExportBreakSink?, density: Float): List<Float> {
        val out = ArrayList<Float>()
        sink?.itemBottoms?.values?.forEach { out += it / density }
        val root = (laid.view.getChildAt(0) as? ViewRootForTest)?.semanticsOwner?.unmergedRootSemanticsNode
            ?: return out
        fun walk(node: SemanticsNode) {
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            if (action != null) {
                val results = ArrayList<TextLayoutResult>()
                if (runCatching { action(results) }.getOrDefault(false)) {
                    val top = node.positionInRoot.y
                    results.firstOrNull()?.let { r ->
                        for (i in 0 until r.lineCount) out += (top + r.getLineBottom(i)) / density
                    }
                }
            }
            node.children.forEach(::walk)
        }
        runCatching { walk(root) }.onFailure { AppLogger.warning(LOG, "[ChatExport] break scan failed: ${it.message}") }
        val solid = sink?.solidSpans?.values?.map { (top, bottom) -> top / density to bottom / density }.orEmpty()
        return ChatSharePagination.withoutCutsThrough(out, solid)
    }

    companion object {
        const val MARGIN = 8f
        const val UNIT_GAP = 18f
        private const val HEADER_GAP = 8f
        private const val STRIP_PX = 1024
        private const val PDF_FOOTER = 22f
        private const val BASE_SETTLE_MS = 150L
        private const val MEDIA_SETTLE_MS = 700L

        fun footerColor(dark: Boolean): Color =
            if (dark) Color(0x99EBEBF5) else Color(0x993C3C43)

        fun rowWidthDp(wide: Boolean, context: Context): Float =
            if (wide) 1024f else context.resources.configuration.screenWidthDp.toFloat().coerceIn(360f, 460f)

        private fun exportRoot(context: Context) = File(context.cacheDir, "share/ChatExport")

        fun clearExports(context: Context) {
            exportRoot(context).deleteRecursively()
        }

        fun exportFile(context: Context, title: String, ext: String): File {
            val dir = File(exportRoot(context), UUID.randomUUID().toString())
            if (!dir.mkdirs() && !dir.isDirectory) throw ChatExportError.WriteFailed
            val clean = takeGraphemes(
                title.map { if (it in "/\\:?%*|\"<>\n\r") ' ' else it }.joinToString("").trim(), 60,
            )
            val stamp = SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US).format(Date())
            return File(dir, "${clean.ifEmpty { "Chat" }} $stamp.$ext")
        }
    }
}

internal fun takeGraphemes(text: String, max: Int): String {
    val chars = java.text.BreakIterator.getCharacterInstance()
    chars.setText(text)
    var end = 0
    repeat(max) {
        val next = chars.next()
        if (next == java.text.BreakIterator.DONE) return text
        end = next
    }
    return text.substring(0, end)
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun kotlinx.coroutines.CoroutineDispatcher.limitedParallelismCompat() = limitedParallelism(1)
