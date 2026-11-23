package com.openminis.app.ui.chat

import android.content.Context
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import android.widget.Toast
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.openminis.app.ui.DisplayBitmapLimits.limitDisplaySize
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.File
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle

val LocalShardId = compositionLocalOf<TextShardId?> { null }

internal class ShardSubIndexAllocator {
    private var counter = 0
    fun next(): Int = counter++
}

internal val LocalShardSubIndexAllocator = compositionLocalOf<ShardSubIndexAllocator?> { null }

@Composable
private fun ShardSubIndexScope(content: @Composable () -> Unit) {
    val allocator = remember { ShardSubIndexAllocator() }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalShardSubIndexAllocator provides allocator,
        content = content,
    )
}

@Composable
@androidx.compose.runtime.ReadOnlyComposable
private fun currentSelectionHighlightColor(): Color {
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary
    return accent.copy(alpha = 0.28f)
}

private data class MdColors(
    val text: Color,
    val codeText: Color,
    val codeBg: Color,
    val inlineCodeText: Color,
    val inlineCodeBg: Color,
    val link: Color,
    val blockquote: Color,
    val divider: Color,
    val tableBorder: Color,
    val tableHeaderBg: Color,
)

@Composable
@androidx.compose.runtime.ReadOnlyComposable
private fun currentMdColors(): MdColors {
    val c = com.openminis.app.ui.theme.LocalChatPalette.current
    return MdColors(
        text = c.primaryText,
        codeText = c.codeBlockText,
        codeBg = c.codeBlockBg,
        inlineCodeText = c.inlineCodeText,
        inlineCodeBg = c.inlineCodeBg,
        link = c.link,
        blockquote = c.secondaryText,
        divider = c.separator,
        tableBorder = c.tableBorder,
        tableHeaderBg = c.secondaryBg,
    )
}

private val MdCodeLangColor = Color.White.copy(alpha = 0.5f)

val LocalMarkdownFontScale = compositionLocalOf { 1f }

val LocalMarkdownUrlClickHandler = compositionLocalOf<((String) -> Unit)?> { null }

val LocalMarkdownBlankTapHandler = compositionLocalOf<((String) -> Unit)?> { null }

val LocalMarkdownImageTapHandler =
    compositionLocalOf<((messageId: String, url: String) -> Unit)?> { null }

val LocalMarkdownSessionId = compositionLocalOf<String?> { null }

private val BaseFontSizeDefault = 16.sp
private val BaseLineHeightDefault = 24.sp

private val BaseFontSize: TextUnit
    @Composable get() = BaseFontSizeDefault * LocalMarkdownFontScale.current

private val BaseLineHeight: TextUnit
    @Composable get() = BaseLineHeightDefault * LocalMarkdownFontScale.current

private val InlineCodeCornerRadius = 6.dp

private class InlineCodeBand(val aboveBaseline: Float, val belowBaseline: Float)

private fun inlineCodeBand(
    fontSize: TextUnit,
    lineHeight: TextUnit,
    density: androidx.compose.ui.unit.Density,
    topInsetPx: Float,
    bottomInsetPx: Float,
): InlineCodeBand {
    val fontSizePx = with(density) { (if (fontSize.isSp) fontSize else BaseFontSizeDefault).toPx() }
    val metrics = android.graphics.Paint().apply {
        typeface = android.graphics.Typeface.DEFAULT
        textSize = fontSizePx
    }.fontMetrics
    val ascent = -metrics.ascent
    val descent = metrics.descent
    val lineHeightPx = when {
        lineHeight.isSp -> with(density) { lineHeight.toPx() }
        lineHeight.isEm -> lineHeight.value * fontSizePx
        else -> ascent + descent
    }
    val halfLeading = ((lineHeightPx - ascent - descent) / 2f).coerceAtLeast(0f)
    return InlineCodeBand(
        aboveBaseline = ascent + halfLeading - topInsetPx,
        belowBaseline = descent + halfLeading - bottomInsetPx,
    )
}

@Composable
private fun MdText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = BaseFontSize,
    lineHeight: TextUnit = BaseLineHeight,
    fontWeight: FontWeight? = null,
    color: Color = currentMdColors().text,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    inlineContent: Map<String, androidx.compose.foundation.text.InlineTextContent> = emptyMap(),
    isAtomicSelectionUnit: Boolean = false,
    textAlign: TextAlign? = null,
) {
    val searchShown = com.openminis.app.ui.chat.search.rememberChatSearchHighlighted(
        text,
        LocalChatSearchPath.current?.let { com.openminis.app.ui.chat.search.ChatSearchUnits.markdown(it) },
    )
    val layoutResultHolder = remember { arrayOfNulls<androidx.compose.ui.text.TextLayoutResult>(1) }
    var layoutGen by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val layoutCoordinatesHolder = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val baseShardId = LocalShardId.current
    val allocator = LocalShardSubIndexAllocator.current
    val subIndex = remember(baseShardId) { allocator?.next() ?: 0 }
    val shardId = remember(baseShardId, subIndex) {
        baseShardId?.let {
            if (allocator == null) it
            else it.copy(shardId = "${it.shardId}#$subIndex")
        }
    }
    val selectionController = LocalMinisSelectionController.current
    val currentShard = remember(shardId, layoutGen, text, isAtomicSelectionUnit) {
        val sid = shardId
        val result = layoutResultHolder[0]
        if (sid == null || result == null) null else buildTextShard(
            id = sid,
            plainText = text.text,
            layoutResult = result,
            coordinatesProvider = { layoutCoordinatesHolder[0] },
            isAtomicUnit = isAtomicSelectionUnit,
        )
    }
    RegisterSelectionShard(currentShard)
    val selectionHighlightColor = currentSelectionHighlightColor()
    val selectionState = selectionController?.selection
    val cornerPx = with(androidx.compose.ui.platform.LocalDensity.current) { InlineCodeCornerRadius.toPx() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val inlineCodeTopInsetPx = with(density) { 4.5.dp.toPx() }
    val inlineCodeBottomInsetPx = with(density) { 1.5.dp.toPx() }
    val inlineCodeBand = remember(fontSize, lineHeight, density) {
        inlineCodeBand(fontSize, lineHeight, density, inlineCodeTopInsetPx, inlineCodeBottomInsetPx)
    }
    val inlineCodeBg = currentMdColors().inlineCodeBg
    val urlClickHandler = LocalMarkdownUrlClickHandler.current
    val clipboardManager = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val hasUrlAnnotation = remember(text) { text.getStringAnnotations("url", 0, text.length).isNotEmpty() }
    val hasInlineCodeAnnotation = remember(text) { text.getStringAnnotations("inline_code", 0, text.length).isNotEmpty() }

    val fadeEnabled = LocalAppendOnlyFade.current
    val fadeFromBirth = remember { fadeEnabled }
    val fadeController = if (fadeEnabled) rememberFadeController() else null
    if (fadeController != null) {
        val fadeKey = baseShardId?.let { "${it.messageId}/${it.shardId}" }
        if (!fadeController.hasSeenText) {
            val plan = fadeBirthPlan(fadeFromBirth, fadeKey?.let { FadeShownText.get(it) } ?: emptyList(), text.text)
            when (plan) {
                FadeBirth.SeedAll -> fadeController.seed(text.text)
                is FadeBirth.SeedPrefix -> {
                    fadeController.seed(plan.shown)
                    fadeController.ingest(text.text)
                }
                FadeBirth.FadeAll -> fadeController.ingest(text.text)
            }
            if (plan == FadeBirth.FadeAll && text.text.length > 80) {
                com.openminis.app.logging.AppLogger.trace("StreamFade") {
                    "[T-android-stream-fade-reentry] fade-from-zero key=$fadeKey len=${text.text.length}"
                }
            }
        } else {
            fadeController.ingest(text.text)
        }
        if (fadeKey != null) FadeShownText.put(fadeKey, text.text)
        FadeFrameDriver(fadeController)
    }
    val fadeMaskColor = ChatColors.background
    val fadeMaskModifier = if (fadeController != null) {
        Modifier
            .graphicsLayer()
            .drawWithContent {
                drawContent()
                val result = layoutResultHolder[0] ?: return@drawWithContent
                val maxOffset = result.layoutInput.text.length
                fadeController.forEachActive { start, end, alpha ->
                    if (alpha >= 1f) return@forEachActive
                    val e = end.coerceAtMost(maxOffset)
                    if (e <= start) return@forEachActive
                    drawPath(result.getPathForRange(start, e), color = fadeMaskColor.copy(alpha = 1f - alpha))
                }
            }
            .graphicsLayer()
    } else Modifier
    val blankTapHandler = LocalMarkdownBlankTapHandler.current
    val bandRegistry = LocalUsageTapBandRegistry.current
    val bandRow = LocalUsageTapBandRow.current
    val blankTapMessageId =
        if (bandRegistry != null) bandRow?.messageId else LocalShardId.current?.messageId
    val wantsBlankTap = blankTapHandler != null && blankTapMessageId != null
    if (bandRegistry != null && bandRow != null && bandRow.isLastRow) {
        androidx.compose.runtime.DisposableEffect(bandRegistry, bandRow.messageId) {
            val node = UsageTapBandRegistry.TextNode(
                coords = { layoutCoordinatesHolder[0] },
                layout = { layoutResultHolder[0] },
            )
            bandRegistry.addText(bandRow.messageId, node)
            onDispose { bandRegistry.removeText(bandRow.messageId, node) }
        }
    }
    val textToolbar by androidx.compose.runtime.rememberUpdatedState(
        androidx.compose.ui.platform.LocalTextToolbar.current,
    )
    val tapModifier = if (hasUrlAnnotation || hasInlineCodeAnnotation || wantsBlankTap) {
        Modifier.pointerInput(text, wantsBlankTap, bandRow) {
            fun selecting(): Boolean =
                selectionController?.selection?.value != null ||
                    textToolbar.status == androidx.compose.ui.platform.TextToolbarStatus.Shown
            var pressStartMs = 0L
            var selectingAtPress = false
            detectTapGestures(
                onPress = {
                    pressStartMs = android.os.SystemClock.uptimeMillis()
                    selectingAtPress = selecting()
                },
            ) { pos ->
                val result = layoutResultHolder[0] ?: return@detectTapGestures
                val spans = tapTargetSpans(text, result)
                val band = if (bandRegistry == null) {
                    BlankTapGeometry.Band.Anywhere
                } else {
                    bandRegistry.bandFor(bandRow, layoutCoordinatesHolder[0], result)
                }
                val kind = BlankTapGeometry.decide(
                    tap = pos,
                    lines = lineInkBoxes(result),
                    interactive = spans.map { it.second },
                    density = this@pointerInput.density,
                    selectionActive = selectingAtPress || selecting(),
                    longPress = android.os.SystemClock.uptimeMillis() - pressStartMs >=
                        viewConfiguration.longPressTimeoutMillis,
                    band = band,
                )
                com.openminis.app.logging.AppLogger.info(
                    "BlankTap",
                    "[BlankTap] kind=$kind pos=${pos.x.toInt()},${pos.y.toInt()} lines=${result.lineCount} " +
                        "spans=${spans.size} selAtPress=$selectingAtPress sel=${selecting()} " +
                        "held=${android.os.SystemClock.uptimeMillis() - pressStartMs}ms " +
                        "handler=$wantsBlankTap msg=${blankTapMessageId?.take(8)} band=$band",
                )
                if (kind == BlankTapGeometry.Kind.BLANK) {
                    if (wantsBlankTap) blankTapHandler?.invoke(blankTapMessageId!!)
                    return@detectTapGestures
                }
                if (kind != BlankTapGeometry.Kind.TEXT) return@detectTapGestures
                val hit = spans.firstOrNull { it.second.contains(pos) }?.first ?: return@detectTapGestures
                if (hit.tag == "url") {
                    urlClickHandler?.invoke(hit.item)
                    return@detectTapGestures
                }
                val snippet = text.text.substring(hit.start, hit.end)
                if (snippet.isNotEmpty()) {
                    clipboardManager.setText(AnnotatedString(snippet))
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val preview = if (snippet.length > 40) snippet.take(37) + "…" else snippet
                    Toast.makeText(context, context.getString(R.string.selection_copied_toast, preview), Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    } else Modifier
    Text(
        text = searchShown,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        inlineContent = inlineContent,
        textAlign = textAlign,
        style = LocalTextStyle.current.merge(
            TextStyle(
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
        ),
        onTextLayout = { result ->
            val prev = layoutResultHolder[0]
            layoutResultHolder[0] = result
            if (prev == null ||
                prev.layoutInput.text !== result.layoutInput.text ||
                prev.size != result.size ||
                prev.lineCount != result.lineCount
            ) {
                layoutGen++
            }
        },
        modifier = modifier
            .then(fadeMaskModifier)
            .then(tapModifier)
            .onGloballyPositioned { layoutCoordinatesHolder[0] = it }
            .drawBehind {
                val result0 = layoutResultHolder[0]
                val shardId0 = shardId
                val sel = selectionState?.value
                if (result0 != null && shardId0 != null && sel != null) {
                    drawSelectionForShard(
                        shardId = shardId0,
                        result = result0,
                        selection = sel,
                        controller = selectionController,
                        color = selectionHighlightColor,
                    )
                }
            }
            .drawBehind {
            val result = layoutResultHolder[0] ?: return@drawBehind
            val laidOutText = result.layoutInput.text.text
            val maxOffset = laidOutText.length
            val lineCount = result.lineCount
            if (maxOffset == 0 || lineCount == 0) return@drawBehind
            val annotations = text.getStringAnnotations("inline_code", 0, text.length)
            for (ann in annotations) {
                val startOffset = ann.start.coerceIn(0, maxOffset)
                val endOffset = ann.end.coerceIn(0, maxOffset)
                if (endOffset <= startOffset) continue
                val startLine = result.getLineForOffset(startOffset).coerceIn(0, lineCount - 1)
                val endLine = result.getLineForOffset(endOffset - 1).coerceIn(0, lineCount - 1)
                if (endLine < startLine) {
                    android.util.Log.d(
                        "StreamingMd",
                        "skip inline_code: endLine<startLine annStart=${ann.start} annEnd=${ann.end} " +
                            "clamped=[$startOffset,$endOffset) lineCount=$lineCount maxOffset=$maxOffset"
                    )
                    continue
                }
                for (line in startLine..endLine) {
                    val lineStart = if (line == startLine) startOffset else result.getLineStart(line)
                    val lineEnd = if (line == endLine) endOffset else result.getLineEnd(line)
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
                    val baseline = result.getLineBaseline(line)
                    val top = baseline - inlineCodeBand.aboveBaseline
                    val bottom = baseline + inlineCodeBand.belowBaseline
                    drawRoundRect(
                        color = inlineCodeBg,
                        topLeft = androidx.compose.ui.geometry.Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx, cornerPx),
                    )
                }
            }
        },
    )
}

private fun streamingThrottleFor(content: String): Long = when {
    content.length < 500 -> 200L
    content.length < 2_000 -> 300L
    content.length < 32_000 -> 500L
    content.length < 64_000 -> 1_000L
    content.length < 128_000 -> 1_500L
    else -> 2_000L
}


@Composable
fun StreamingMarkdownText(
    content: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    shardId: TextShardId? = null,
) {
    if (shardId != null) {
        androidx.compose.runtime.CompositionLocalProvider(LocalShardId provides shardId) {
            StreamingMarkdownTextBody(content, isStreaming, modifier)
        }
        return
    }
    StreamingMarkdownTextBody(content, isStreaming, modifier)
}

@Composable
private fun StreamingMarkdownTextBody(
    content: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    val displayContent by produceState(initialValue = content, content, isStreaming) {
        if (!isStreaming) {
            value = content
            return@produceState
        }
        snapshotFlow { content }
            .conflate()
            .collect { latest ->
                value = latest
                delay(streamingThrottleFor(latest))
            }
    }
    val mdColors = currentMdColors()
    var blocks by remember { mutableStateOf<List<MdBlock>>(emptyList()) }
    val parseState = remember { IncrementalParseState() }
    LaunchedEffect(displayContent) {
        val computed = withContext(Dispatchers.Default) {
            parseMarkdownIncremental(displayContent, parseState).also {
                MarkdownParseCaches.prewarm(it, mdColors)
            }
        }
        coroutineContext.ensureActive()
        blocks = computed
    }

    ShardSubIndexScope {
        Column(modifier = modifier) {
            val lastIdx = blocks.size - 1
            if (isStreaming) {
                blocks.forEachIndexed { idx, block ->
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalAppendOnlyFade provides (idx == lastIdx),
                    ) { RenderBlock(block) }
                }
            } else {
                blocks.forEachIndexed { idx, block -> WithSearchPath("b$idx") { RenderBlock(block) } }
            }
        }
    }
}

@Composable
fun MarkdownDocument(
    content: String,
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(0.dp),
) {
    val mdColors = currentMdColors()
    var blocks by remember(content) { mutableStateOf<List<MdBlock>>(emptyList()) }
    LaunchedEffect(content) {
        val computed = withContext(Dispatchers.Default) {
            markdownParseGate.withPermit { parseMarkdownBlocks(content) }.also {
                MarkdownParseCaches.prewarm(it, mdColors)
            }
        }
        coroutineContext.ensureActive()
        blocks = computed
    }
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        itemsIndexed(blocks, key = { idx, b -> "$idx:${b.raw}" }) { _, block ->
            RenderBlock(block)
        }
    }
}


fun splitMarkdownIntoBlockTexts(content: String): List<String> {
    if (content.isEmpty()) return emptyList()
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var inFence = false
    val lines = content.lines()
    fun flush() {
        if (cur.isNotEmpty()) {
            out.add(cur.toString().trimEnd('\n'))
            cur.clear()
        }
    }
    for (line in lines) {
        val trimmed = line.trimStart()
        val isFence = trimmed.startsWith("```")
        if (isFence) {
            if (!inFence) {
                flush()
                cur.append(line).append('\n')
                inFence = true
            } else {
                cur.append(line).append('\n')
                inFence = false
                flush()
            }
            continue
        }
        if (inFence) {
            cur.append(line).append('\n')
            continue
        }
        if (line.isBlank()) {
            flush()
            continue
        }
        cur.append(line).append('\n')
    }
    flush()
    return out
}

private fun isFenceFragment(fragment: String): Boolean {
    val firstLine = fragment.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
    return firstLine.trimStart().startsWith("```")
}

fun coalesceMarkdownFragments(fragments: List<String>, maxChars: Int = 2000): List<String> {
    if (fragments.size <= 1) return fragments
    val out = ArrayList<String>(fragments.size)
    val acc = StringBuilder()
    fun flush() {
        if (acc.isNotEmpty()) {
            out.add(acc.toString())
            acc.setLength(0)
        }
    }
    for (frag in fragments) {
        if (isFenceFragment(frag)) {
            flush()
            out.add(frag)
            continue
        }
        if (acc.isNotEmpty() && acc.length + 2 + frag.length > maxChars) {
            flush()
        }
        if (acc.isNotEmpty()) acc.append("\n\n")
        acc.append(frag)
    }
    flush()
    return out
}

@Composable
fun MarkdownBlock(
    rawText: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    shardId: TextShardId? = null,
) {
    if (shardId != null) {
        androidx.compose.runtime.CompositionLocalProvider(LocalShardId provides shardId) {
            ShardSubIndexScope {
                MarkdownBlockBody(rawText, isStreaming, modifier)
            }
        }
        return
    }
    MarkdownBlockBody(rawText, isStreaming, modifier)
}

@Composable
private fun MarkdownBlockBody(
    rawText: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    val liveCarry = remember { arrayOf<Pair<String, List<MdBlock>>?>(null) }
    if (!isStreaming) {
        val cached = remember(rawText) {
            MarkdownParseCaches.cachedBlocks(rawText)
                ?: liveCarry[0]?.takeIf { it.first == rawText }?.second
        }
        if (cached != null) {
            Column(modifier = modifier) {
                cached.forEachIndexed { idx, block -> WithSearchPath("b$idx") { RenderBlock(block) } }
            }
            return
        }
        val mdColors = currentMdColors()
        var parsed by remember(rawText) { mutableStateOf<List<MdBlock>?>(null) }
        LaunchedEffect(rawText) {
            val tStartNs = System.nanoTime()
            val computed = withContext(Dispatchers.Default) {
                MarkdownParseCaches.blocks(rawText).also {
                    MarkdownParseCaches.prewarm(it, mdColors)
                }
            }
            coroutineContext.ensureActive()
            parsed = computed
            com.openminis.app.logging.AppLogger.info(
                "Perf",
                "[Perf][ColdParse] step=coldParse.offmain chars=${rawText.length} " +
                    "blocks=${computed.size} parseMs=${(System.nanoTime() - tStartNs) / 1_000_000}",
            )
        }
        val blocks = parsed ?: liveCarry[0]?.takeIf { rawText.startsWith(it.first) }?.second
        if (parsed == null) {
            android.util.Log.i(
                "FreezeEdge",
                "chars=${rawText.length} paint=" +
                    (if (blocks != null) "carry(prefix=${liveCarry[0]?.first?.length})" else "preview"),
            )
        }
        Column(modifier = modifier) {
            if (blocks == null) {
                Text(
                    text = rawText.take(COLD_PARSE_PREVIEW_CHARS),
                    fontSize = BaseFontSize,
                    lineHeight = BaseLineHeight,
                    color = currentMdColors().text,
                )
            } else {
                blocks.forEachIndexed { idx, block -> WithSearchPath("b$idx") { RenderBlock(block) } }
            }
        }
        return
    }
    if (rawText.length > LIVE_FRAGMENT_DEGRADE_CHARS) {
        LaunchedEffect(Unit) {
            com.openminis.app.logging.AppLogger.info(
                "StreamDegrade",
                "live fragment over $LIVE_FRAGMENT_DEGRADE_CHARS chars (${rawText.length}) rendered as a plain tail",
            )
        }
        Column(modifier = modifier) {
            Text(
                text = "…" + rawText.takeLast(LIVE_FRAGMENT_TAIL_CHARS),
                fontSize = BaseFontSize,
                lineHeight = BaseLineHeight,
                color = currentMdColors().text,
            )
        }
        return
    }
    val displayContent by produceState(initialValue = rawText, rawText) {
        snapshotFlow { rawText }.conflate().collect { value = it }
    }
    val mdColors = currentMdColors()
    var blocks by remember { mutableStateOf<List<MdBlock>>(emptyList()) }
    val parseState = remember { IncrementalParseState() }
    val liveDepositKey = remember { arrayOf<String?>(null) }
    LaunchedEffect(displayContent) {
        val parseStartNs = System.nanoTime()
        val computed = withContext(Dispatchers.Default) {
            parseMarkdownIncremental(displayContent, parseState).also {
                if (it.size > 1) MarkdownParseCaches.prewarm(it.dropLast(1), mdColors)
                if (it.lastOrNull() is MdBlock.Paragraph) {
                    MarkdownParseCaches.prewarmLiveTail(it, mdColors)
                } else {
                    it.lastOrNull()?.let { last -> MarkdownParseCaches.prewarm(listOf(last), mdColors) }
                }
                liveDepositKey[0]?.let { prev ->
                    if (prev != displayContent) MarkdownParseCaches.removeBlocks(prev)
                }
                MarkdownParseCaches.putBlocks(displayContent, it)
                liveDepositKey[0] = displayContent
            }
        }
        coroutineContext.ensureActive()
        StreamRenderProfiler.recordParse(displayContent.length, (System.nanoTime() - parseStartNs) / 1_000_000.0)
        blocks = computed
        liveCarry[0] = displayContent to computed
    }
    Column(modifier = modifier) {
        val lastIdx = blocks.size - 1
        blocks.forEachIndexed { idx, block ->
            val isLast = idx == lastIdx
            androidx.compose.runtime.CompositionLocalProvider(
                LocalAppendOnlyFade provides isLast,
                LocalLiveIncremental provides isLast,
                LocalChatSearchPath provides "b$idx",
            ) { RenderBlock(block) }
        }
    }
}

private const val LIVE_FRAGMENT_DEGRADE_CHARS = 8_000
private const val LIVE_FRAGMENT_TAIL_CHARS = 3_000

private const val COLD_PARSE_OFFMAIN_THRESHOLD_CHARS = 2_000
private const val COLD_PARSE_PREVIEW_CHARS = 4_000

@Composable
internal fun rememberMarkdownPrewarmer(): suspend (List<String>) -> Unit {
    val mdColors = currentMdColors()
    return remember(mdColors) {
        val fn: suspend (List<String>) -> Unit = { raws ->
            for (raw in raws) {
                markdownParseGate.withPermit {
                    MarkdownParseCaches.prewarm(MarkdownParseCaches.blocks(raw), mdColors)
                }
                coroutineContext.ensureActive()
            }
        }
        fn
    }
}

internal fun fencedCodeContent(fenceLine: String, contentLines: List<String>): String {
    val indent = fenceLine.length - fenceLine.trimStart(' ').length
    if (indent == 0) return contentLines.joinToString("\n")
    return contentLines.joinToString("\n") { line ->
        var n = 0
        while (n < indent && n < line.length && line[n] == ' ') n++
        line.substring(n)
    }
}

private fun parseMarkdownBlocksBlocking(content: String): List<MdBlock> =
    kotlinx.coroutines.runBlocking { parseMarkdownBlocks(content) }


internal val LocalChatSearchPath = androidx.compose.runtime.compositionLocalOf<String?> { null }

@Composable
private fun WithSearchPath(path: String?, content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(LocalChatSearchPath provides path, content = content)

internal data class ChatSearchPiece(val path: String, val text: String, val isWholeBlock: Boolean = false)

private val chatSearchColors = MdColors(
    text = Color.Unspecified, codeText = Color.Unspecified, codeBg = Color.Unspecified,
    inlineCodeText = Color.Unspecified, inlineCodeBg = Color.Unspecified, link = Color.Unspecified,
    blockquote = Color.Unspecified, divider = Color.Unspecified, tableBorder = Color.Unspecified,
    tableHeaderBg = Color.Unspecified,
)

@androidx.annotation.VisibleForTesting
internal fun parseInlineForTest(text: String): AnnotatedString = parseInline(text, chatSearchColors)

internal fun chatSearchMarkdownPieces(rawText: String): List<ChatSearchPiece> {
    val blocks = MarkdownParseCaches.cachedBlocks(rawText) ?: parseMarkdownBlocksBlocking(rawText)
    val out = ArrayList<ChatSearchPiece>()
    fun inline(raw: String): String =
        com.openminis.app.ui.chat.search.ChatSearchMarkdown.searchable(parseInline(raw, chatSearchColors))
    fun table(t: MdBlock.Table): String = buildList {
        if (t.headers.isNotEmpty()) add(t.headers)
        addAll(t.rows)
    }.joinToString("\n") { row -> row.joinToString("\t") { inline(it) } }
    fun add(path: String, text: String, whole: Boolean = false) {
        if (text.isNotEmpty()) out.add(ChatSearchPiece(path, text, whole))
    }
    fun item(path: String, text: String, children: List<MdBlock>) {
        if (children.isEmpty()) {
            add(path, inline(text))
            return
        }
        children.forEachIndexed { k, child ->
            when (child) {
                is MdBlock.MathDisplay -> Unit
                is MdBlock.Table -> add("$path/$k", table(child), whole = true)
                else -> add("$path/$k", inline(child.raw))
            }
        }
    }
    fun walk(list: List<MdBlock>, prefix: String) {
        list.forEachIndexed { i, block ->
            val p = "${prefix}b$i"
            when (block) {
                is MdBlock.Paragraph -> add(p, inline(block.raw))
                is MdBlock.Heading -> add(p, inline(block.text))
                is MdBlock.CodeBlock -> add(p, block.code, whole = true)
                is MdBlock.BlockQuote -> walk(block.innerBlocks, "$p/q/")
                is MdBlock.UnorderedList -> block.items.forEachIndexed { j, it -> item("$p/$j", it.text, it.children) }
                is MdBlock.OrderedList -> block.items.forEachIndexed { j, it -> item("$p/$j", it.text, it.children) }
                is MdBlock.TaskList -> block.items.forEachIndexed { j, it -> item("$p/$j", it.text, it.children) }
                is MdBlock.Table -> add(p, table(block), whole = true)
                is MdBlock.HorizontalRule, is MdBlock.Image, is MdBlock.Video,
                is MdBlock.Audio, is MdBlock.MathDisplay -> Unit
            }
        }
    }
    walk(blocks, "")
    return out
}


private sealed class MdBlock(val raw: String) {
    class Paragraph(raw: String) : MdBlock(raw)
    class Heading(raw: String, val level: Int, val text: String) : MdBlock(raw)
    class CodeBlock(raw: String, val language: String, val code: String) : MdBlock(raw)
    class BlockQuote(raw: String, val innerBlocks: List<MdBlock>) : MdBlock(raw)
    class UnorderedList(raw: String, val items: List<ListItem>) : MdBlock(raw)
    class OrderedList(raw: String, val items: List<ListItem>, val startNum: Int = 1) : MdBlock(raw)
    class TaskList(raw: String, val items: List<TaskItem>) : MdBlock(raw)
    class HorizontalRule(raw: String) : MdBlock(raw)
    class Table(
        raw: String,
        val headers: List<String>,
        val rows: List<List<String>>,
        val alignments: List<MdTableAlign> = emptyList(),
    ) : MdBlock(raw)
    class Image(raw: String, val alt: String, val url: String) : MdBlock(raw)
    class Video(raw: String, val alt: String, val url: String) : MdBlock(raw)
    class Audio(raw: String, val alt: String, val url: String) : MdBlock(raw)
    class MathDisplay(raw: String, val latex: String) : MdBlock(raw)
}

private val nativeVideoExts = setOf("mp4", "mov", "m4v", "avi", "mkv", "webm")
private val nativeAudioExts = setOf("mp3", "m4a", "wav", "aac", "ogg", "flac")

private fun mediaBlockFrom(raw: String, alt: String, url: String): MdBlock {
    val lastSeg = url.substringAfterLast('/')
    val decoded = runCatching { java.net.URLDecoder.decode(lastSeg, "UTF-8") }.getOrDefault(lastSeg)
    val ext = decoded.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in nativeVideoExts -> MdBlock.Video(raw, alt, url)
        in nativeAudioExts -> MdBlock.Audio(raw, alt, url)
        else -> MdBlock.Image(raw, alt, url)
    }
}

private val inlineMediaRegex = Regex("""!\[([^\]\n]*)]\(([^)\s]+)\)""")

private val thematicBreakRegex = Regex("^[-*_]{3,}\\s*$")
private val standaloneImageLineRegex = Regex("^!\\[.*]\\(.*\\)\\s*$")
private val imageMatchRegex = Regex("^!\\[(.*)\\]\\((.*)\\)")
private val tableSeparatorRegex = Regex("^\\|?[\\s\\-:|]+\\|?$")
private val taskListItemRegex = Regex("^[-*+]\\s+\\[[ xX]\\]\\s+.*")
private val taskListPrefixRegex = Regex("^[-*+]\\s+\\[[ xX]\\]\\s+")
private val bulletListItemRegex = Regex("^[-*+]\\s+.*")
private val bulletListPrefixRegex = Regex("^[-*+]\\s+")
private val numberedListItemRegex = Regex("^\\d+[.)\\s]+.*")
private val numberedListStartRegex = Regex("^(\\d+)")
private val numberedListPrefixRegex = Regex("^\\d+[.)\\s]+")

private fun isBlockquoteLine(trimmed: String): Boolean {
    if (!trimmed.startsWith(">")) return false
    if (trimmed.length == 1) return true
    val next = trimmed[1]
    return next == ' ' || next == '\t'
}

private fun splitParagraphOnInlineMedia(text: String): List<MdBlock> {
    val matches = inlineMediaRegex.findAll(text).toList()
    if (matches.isEmpty()) return listOf(MdBlock.Paragraph(text))

    val hasMediaExt = matches.any { m ->
        val url = m.groupValues[2]
        val lastSeg = url.substringAfterLast('/')
        val decoded = runCatching { java.net.URLDecoder.decode(lastSeg, "UTF-8") }.getOrDefault(lastSeg)
        val ext = decoded.substringAfterLast('.', "").lowercase()
        ext in nativeVideoExts || ext in nativeAudioExts
    }
    if (!hasMediaExt) return listOf(MdBlock.Paragraph(text))

    val result = mutableListOf<MdBlock>()
    var cursor = 0
    for (m in matches) {
        val url = m.groupValues[2]
        val lastSeg = url.substringAfterLast('/')
        val decoded = runCatching { java.net.URLDecoder.decode(lastSeg, "UTF-8") }.getOrDefault(lastSeg)
        val ext = decoded.substringAfterLast('.', "").lowercase()
        val isMedia = ext in nativeVideoExts || ext in nativeAudioExts
        if (!isMedia) continue

        val preceding = text.substring(cursor, m.range.first).trim('\n', ' ', '\t')
        if (preceding.isNotBlank()) result.add(MdBlock.Paragraph(preceding))
        val alt = m.groupValues[1]
        result.add(mediaBlockFrom(m.value, alt, url))
        cursor = m.range.last + 1
    }
    val tail = text.substring(cursor).trim('\n', ' ', '\t')
    if (tail.isNotBlank()) result.add(MdBlock.Paragraph(tail))
    return result
}

private fun looksLikeWideMath(latex: String): Boolean {
    if (latex.length > 30) return true
    if (latex.contains("\\begin{")) return true
    if (latex.contains("\\\\")) return true
    if (latex.contains("\\frac")) return true
    if (latex.contains("\\sum") || latex.contains("\\int") || latex.contains("\\prod")) return true
    if (latex.contains("\\sqrt")) return true
    if (latex.contains("\\mathbf{") || latex.contains("\\mathbb{") || latex.contains("\\mathcal{")) return true
    if (latex.contains("\\overline") || latex.contains("\\underline")) return true
    if (latex.contains("\\binom")) return true
    return false
}

private fun splitParagraphOnWideMath(text: String): List<MdBlock> {
    if (!text.contains('\\') && !text.contains('$')) return listOf(MdBlock.Paragraph(text))

    data class Span(val start: Int, val end: Int, val latex: String)
    val spans = mutableListOf<Span>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 1 < text.length && text[i + 1] != '(' && text[i + 1] != '[') {
            i += 2; continue
        }
        if (c == '\\' && i + 1 < text.length && text[i + 1] == '(') {
            val end = text.indexOf("\\)", i + 2)
            if (end != -1) {
                val latex = text.substring(i + 2, end)
                if (looksLikeMath(latex) && looksLikeWideMath(latex)) {
                    spans.add(Span(i, end + 2, latex))
                }
                i = end + 2; continue
            }
        }
        if (c == '$' && i + 1 < text.length && text[i + 1] != '$' && text[i + 1] != ' ') {
            val end = findInlineMathClose(text, i + 1)
            if (end != -1) {
                val latex = text.substring(i + 1, end)
                if (looksLikeMath(latex) && looksLikeWideMath(latex)) {
                    spans.add(Span(i, end + 1, latex))
                }
                i = end + 1; continue
            }
        }
        i++
    }
    if (spans.isEmpty()) return listOf(MdBlock.Paragraph(text))

    val result = mutableListOf<MdBlock>()
    var cursor = 0
    for (s in spans) {
        val before = text.substring(cursor, s.start).trim('\n', ' ', '\t')
        if (before.isNotBlank()) result.add(MdBlock.Paragraph(before))
        result.add(MdBlock.MathDisplay(text.substring(s.start, s.end), s.latex))
        cursor = s.end
    }
    val tail = text.substring(cursor).trim('\n', ' ', '\t')
    if (tail.isNotBlank()) result.add(MdBlock.Paragraph(tail))
    return result
}

private data class ListItem(val text: String, val children: List<MdBlock> = emptyList())

private fun ListItem.withWideMathSplit(): ListItem {
    val split = splitListItemBody(text)
    return if (split.any { it is MdBlock.MathDisplay || it is MdBlock.Table }) copy(children = split) else this
}

private fun splitListItemBody(text: String): List<MdBlock> {
    if (!text.contains('|')) return splitParagraphOnWideMath(text)
    val lines = text.lines()
    val out = mutableListOf<MdBlock>()
    val prose = mutableListOf<String>()
    fun flushProse() {
        val joined = prose.joinToString("\n")
        if (joined.isNotBlank()) out.addAll(splitParagraphOnWideMath(joined))
        prose.clear()
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.contains('|') && i + 1 < lines.size &&
            lines[i + 1].trim().matches(tableSeparatorRegex)
        ) {
            flushProse()
            val tableLines = mutableListOf<String>()
            while (i < lines.size && (lines[i].contains('|') ||
                    lines[i].trim().matches(tableSeparatorRegex))) {
                tableLines.add(lines[i])
                i++
            }
            val parsed = parseTable(tableLines)
            out.add(MdBlock.Table(tableLines.joinToString("\n"), parsed.headers, parsed.rows, parsed.alignments))
            continue
        }
        prose.add(line)
        i++
    }
    flushProse()
    return out
}
private data class TaskItem(val checked: Boolean, val text: String, val children: List<MdBlock> = emptyList())

private fun TaskItem.withWideMathSplit(): TaskItem {
    val split = splitParagraphOnWideMath(text)
    return if (split.any { it is MdBlock.MathDisplay }) copy(children = split) else this
}


private fun findDisplayMathClose(lines: List<String>, from: Int): Int? {
    var j = from
    val body = StringBuilder()
    while (j < lines.size) {
        val l = lines[j]
        val t = l.trimStart()
        if (t.startsWith("```") || t.startsWith("~~~")) return null
        if (t.isBlank()) return null
        val close = l.indexOf("$$")
        if (close >= 0) {
            body.append(l.substring(0, close))
            val text = body.toString()
            if (t == "$$") return j
            if (text.isBlank()) return j
            return if (text.any { it == '\\' || it == '^' || it == '_' || it == '{' || it == '}' }) j else null
        }
        body.append(l).append('\n')
        j++
    }
    return null
}

private val markdownParseGate = Semaphore(1)


private class IncrementalParseState {
    var prefixLength: Int = 0
    var prefixBlocks: List<MdBlock> = emptyList()
    var prefixHash: Int = 0
}

private fun prefixFingerprint(content: String, end: Int): Int {
    if (end <= 0) return 0
    val from = (end - 512).coerceAtLeast(0)
    var h = end
    for (i in from until end) h = h * 31 + content[i].code
    return h
}

internal fun debugParseBlockRaws(content: String): List<String> =
    kotlinx.coroutines.runBlocking { parseMarkdownBlocks(content).map { it.raw } }

internal fun debugParseBlockShapes(content: String): List<String> =
    kotlinx.coroutines.runBlocking {
        parseMarkdownBlocks(content).map { b ->
            val children = when (b) {
                is MdBlock.UnorderedList -> b.items.flatMap { it.children }
                is MdBlock.OrderedList -> b.items.flatMap { it.children }
                else -> emptyList()
            }
            val name = b::class.simpleName.orEmpty()
            if (children.isEmpty()) name else "$name[${children.joinToString(",") { it::class.simpleName.orEmpty() }}]"
        }
    }

internal fun debugParseIncrementalRaws(chunks: List<String>): List<String> =
    kotlinx.coroutines.runBlocking {
        val state = IncrementalParseState()
        var last: List<MdBlock> = emptyList()
        for (c in chunks) last = parseMarkdownIncremental(c, state)
        last.map { it.raw }
    }

internal fun stableParsePrefixEnd(content: String, searchFrom: Int): Int {
    var idx = content.lastIndexOf("\n\n")
    while (idx >= searchFrom) {
        val candidate = idx + 2
        if (isSealedBoundary(content, candidate)) return candidate
        if (idx == 0) break
        idx = content.lastIndexOf("\n\n", idx - 1)
    }
    return searchFrom
}

internal fun isSealedBoundary(content: String, end: Int): Boolean {
    var fences = 0
    var maths = 0
    var bracketMathOpen = false
    var lastNonBlankStart = -1
    var lastNonBlankEnd = -1
    var lineStart = 0
    while (lineStart < end) {
        var lineEnd = content.indexOf('\n', lineStart)
        if (lineEnd < 0 || lineEnd > end) lineEnd = end
        var p = lineStart
        while (p < lineEnd && (content[p] == ' ' || content[p] == '\t')) p++
        if (bracketMathOpen) {
            if (content.indexOf("\\]", lineStart).let { it in lineStart until lineEnd }) {
                bracketMathOpen = false
            }
        } else if (content.startsWith("\\[", p)) {
            bracketMathOpen = content.indexOf("\\]", p + 2).let { it !in 0 until lineEnd }
        }
        if (content.startsWith("```", p)) fences++
        else if (content.startsWith("$$", p)) {
            maths += if (content.indexOf("$$", p + 2).let { it in 0 until lineEnd }) 0 else 1
        }
        if (p < lineEnd && content.substring(p, lineEnd).isNotBlank()) {
            lastNonBlankStart = lineStart
            lastNonBlankEnd = lineEnd
        }
        lineStart = lineEnd + 1
    }
    if (fences % 2 != 0 || maths % 2 != 0 || bracketMathOpen) return false
    if (lastNonBlankStart < 0) return true
    val prev = content.substring(lastNonBlankStart, lastNonBlankEnd)
    if (!isListContinuationLine(prev)) return true
    var q = end
    while (q < content.length) {
        val nl = content.indexOf('\n', q)
        if (nl < 0) return false
        val line = content.substring(q, nl)
        if (line.isNotBlank()) return !isListContinuationLine(line)
        q = nl + 1
    }
    return false
}

private fun isListContinuationLine(line: String): Boolean {
    if (line.isEmpty()) return false
    if (line[0] == ' ' || line[0] == '\t') return true
    return line.matches(bulletListItemRegex) || line.matches(numberedListItemRegex) ||
        line.matches(taskListItemRegex)
}

private suspend fun parseMarkdownIncremental(
    content: String,
    state: IncrementalParseState,
): List<MdBlock> = markdownParseGate.withPermit {
    val reusable = state.prefixLength in 1..content.length &&
        prefixFingerprint(content, state.prefixLength) == state.prefixHash
    if (!reusable) {
        state.prefixLength = 0
        state.prefixBlocks = emptyList()
        state.prefixHash = 0
    }

    val head = state.prefixLength
    val sealedEnd = stableParsePrefixEnd(content, head)
    val sealedTail = if (sealedEnd > head) {
        parseMarkdownBlocks(content.substring(head, sealedEnd))
    } else {
        emptyList()
    }
    val liveTail = if (sealedEnd < content.length) {
        parseMarkdownBlocks(content.substring(sealedEnd))
    } else {
        emptyList()
    }

    if (sealedEnd > head) {
        state.prefixBlocks = state.prefixBlocks + sealedTail
        state.prefixLength = sealedEnd
        state.prefixHash = prefixFingerprint(content, sealedEnd)
    }
    state.prefixBlocks + liveTail
}

private suspend fun parseMarkdownBlocks(content: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = content.lines()
    var i = 0
    var sinceLastCheck = 0

    while (i < lines.size) {
        if (sinceLastCheck >= 64) {
            coroutineContext.ensureActive()
            sinceLastCheck = 0
        }
        sinceLastCheck++
        val line = lines[i]
        val trimmed = line.trimStart()

        when {
            trimmed.startsWith("$$") -> {
                val rest = trimmed.removePrefix("$$")
                val inlineEnd = rest.indexOf("$$")
                if (inlineEnd >= 0) {
                    val latex = rest.substring(0, inlineEnd).trim()
                    blocks.add(MdBlock.MathDisplay(line, latex))
                    i++
                } else {
                    val closeIdx = findDisplayMathClose(lines, i + 1)
                    if (closeIdx == null) {
                        blocks.add(MdBlock.Paragraph(line))
                        i++
                    } else {
                        val rawLines = mutableListOf(line)
                        val mathLines = mutableListOf<String>()
                        if (rest.isNotEmpty()) mathLines.add(rest)
                        i++
                        while (i <= closeIdx) {
                            rawLines.add(lines[i])
                            if (i == closeIdx) {
                                val close = lines[i].indexOf("$$")
                                val pre = lines[i].substring(0, close)
                                if (pre.isNotEmpty()) mathLines.add(pre)
                                i++
                                break
                            }
                            mathLines.add(lines[i])
                            i++
                        }
                        blocks.add(
                            MdBlock.MathDisplay(
                                rawLines.joinToString("\n"),
                                mathLines.joinToString("\n").trim(),
                            ),
                        )
                    }
                }
            }
            trimmed.startsWith("\\[") -> {
                val rest = trimmed.removePrefix("\\[")
                val inlineEnd = rest.indexOf("\\]")
                if (inlineEnd >= 0) {
                    val latex = rest.substring(0, inlineEnd).trim()
                    blocks.add(MdBlock.MathDisplay(line, latex))
                    i++
                } else {
                    val rawLines = mutableListOf(line)
                    val mathLines = mutableListOf<String>()
                    if (rest.isNotEmpty()) mathLines.add(rest)
                    i++
                    while (i < lines.size) {
                        rawLines.add(lines[i])
                        val close = lines[i].indexOf("\\]")
                        if (close >= 0) {
                            val pre = lines[i].substring(0, close)
                            if (pre.isNotEmpty()) mathLines.add(pre)
                            i++
                            break
                        }
                        mathLines.add(lines[i])
                        i++
                    }
                    blocks.add(MdBlock.MathDisplay(rawLines.joinToString("\n"), mathLines.joinToString("\n").trim()))
                }
            }
            trimmed.startsWith("```") -> {
                val lang = trimmed.removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                val rawLines = mutableListOf(line)
                i++
                while (i < lines.size) {
                    rawLines.add(lines[i])
                    if (lines[i].trimStart().startsWith("```")) { i++; break }
                    codeLines.add(lines[i])
                    i++
                }
                blocks.add(MdBlock.CodeBlock(rawLines.joinToString("\n"), lang, fencedCodeContent(line, codeLines)))
            }

            trimmed.startsWith("#") && (trimmed.length == 1 || trimmed[trimmed.indexOfFirst { it != '#' }.coerceAtLeast(0)] == ' ') -> {
                val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(6)
                val text = trimmed.drop(level).trimStart()
                blocks.add(MdBlock.Heading(line, level, text))
                i++
            }

            trimmed.matches(thematicBreakRegex) -> {
                blocks.add(MdBlock.HorizontalRule(line))
                i++
            }

            trimmed.matches(standaloneImageLineRegex) -> {
                val match = imageMatchRegex.find(trimmed)
                if (match != null) {
                    val alt = match.groupValues[1]
                    val url = match.groupValues[2]
                    val blk = mediaBlockFrom(line, alt, url)
                    android.util.Log.d("MdStream", "media match: alt=\"$alt\" url=$url -> ${blk::class.simpleName}")
                    blocks.add(blk)
                }
                i++
            }

            trimmed.contains('|') && i + 1 < lines.size &&
                lines[i + 1].trim().matches(tableSeparatorRegex) -> {
                val tableLines = mutableListOf<String>()
                while (i < lines.size && (lines[i].contains('|') ||
                        lines[i].trim().matches(tableSeparatorRegex))) {
                    tableLines.add(lines[i])
                    i++
                }
                val parsed = parseTable(tableLines)
                blocks.add(MdBlock.Table(tableLines.joinToString("\n"), parsed.headers, parsed.rows, parsed.alignments))
            }

            isBlockquoteLine(trimmed) -> {
                val rawLines = mutableListOf<String>()
                val innerLines = mutableListOf<String>()
                while (i < lines.size && isBlockquoteLine(lines[i].trimStart())) {
                    rawLines.add(lines[i])
                    innerLines.add(lines[i].trimStart().removePrefix(">").removePrefix(" "))
                    i++
                }
                val innerBlocks = parseMarkdownBlocks(innerLines.joinToString("\n"))
                blocks.add(MdBlock.BlockQuote(rawLines.joinToString("\n"), innerBlocks))
            }

            trimmed.matches(taskListItemRegex) -> {
                val items = mutableListOf<TaskItem>()
                val rawLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().matches(taskListItemRegex)) {
                    rawLines.add(lines[i])
                    val t = lines[i].trimStart()
                    val checked = t.contains("[x]", ignoreCase = true)
                    val text = t.replaceFirst(taskListPrefixRegex, "")
                    items.add(TaskItem(checked, text))
                    i++
                }
                blocks.add(MdBlock.TaskList(rawLines.joinToString("\n"), items.map { it.withWideMathSplit() }))
            }

            trimmed.matches(bulletListItemRegex) -> {
                val items = mutableListOf<ListItem>()
                val rawLines = mutableListOf<String>()
                val baseIndent = line.length - trimmed.length
                while (i < lines.size) {
                    val l = lines[i]
                    val t = l.trimStart()
                    val indent = l.length - t.length
                    if (t.isEmpty()) { i++; continue }
                    if (!t.matches(bulletListItemRegex) && indent <= baseIndent) break
                    if (indent > baseIndent) {
                        if (items.isNotEmpty()) {
                            val last = items.last()
                            items[items.lastIndex] = last.copy(text = last.text + "\n" + t)
                        }
                    } else {
                        rawLines.add(l)
                        items.add(ListItem(t.replaceFirst(bulletListPrefixRegex, "")))
                    }
                    i++
                }
                blocks.add(MdBlock.UnorderedList(rawLines.joinToString("\n"), items.map { it.withWideMathSplit() }))
            }

            trimmed.matches(numberedListItemRegex) -> {
                val items = mutableListOf<ListItem>()
                val rawLines = mutableListOf<String>()
                val startMatch = numberedListStartRegex.find(trimmed)
                val startNum = startMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val baseIndent = line.length - trimmed.length
                while (i < lines.size) {
                    val l = lines[i]
                    val t = l.trimStart()
                    val indent = l.length - t.length
                    if (t.isEmpty()) { i++; continue }
                    if (!t.matches(numberedListItemRegex) && indent <= baseIndent) break
                    if (indent > baseIndent) {
                        if (items.isNotEmpty()) {
                            val last = items.last()
                            items[items.lastIndex] = last.copy(text = last.text + "\n" + t)
                        }
                    } else {
                        rawLines.add(l)
                        items.add(ListItem(t.replaceFirst(numberedListPrefixRegex, "")))
                    }
                    i++
                }
                blocks.add(MdBlock.OrderedList(rawLines.joinToString("\n"), items.map { it.withWideMathSplit() }, startNum))
            }

            trimmed.isEmpty() -> { i++ }

            else -> {
                val paraLines = mutableListOf<String>()
                while (i < lines.size) {
                    val l = lines[i]
                    val t = l.trimStart()
                    if (t.isEmpty() || t.startsWith("#") || t.startsWith("```") ||
                        isBlockquoteLine(t) || t.matches(thematicBreakRegex) ||
                        t.matches(bulletListItemRegex) || t.matches(numberedListItemRegex) ||
                        t.matches(standaloneImageLineRegex) ||
                        (t.contains('|') && i + 1 < lines.size &&
                            lines[i + 1].trim().matches(tableSeparatorRegex))
                    ) break
                    paraLines.add(l)
                    i++
                }
                val text = paraLines.joinToString("\n")
                if (text.isNotBlank()) {
                    val mediaBlocks = splitParagraphOnInlineMedia(text)
                    for (b in mediaBlocks) {
                        if (b is MdBlock.Paragraph) {
                            blocks.addAll(splitParagraphOnWideMath(b.raw))
                        } else {
                            blocks.add(b)
                        }
                    }
                }
            }
        }
    }
    return blocks
}

enum class MdTableAlign { START, CENTER, END }

internal class ParsedTable(
    val headers: List<String>,
    val rows: List<List<String>>,
    val alignments: List<MdTableAlign>,
)

internal fun parseTableAlignments(separator: String): List<MdTableAlign> =
    separator.trim().removePrefix("|").removeSuffix("|").split("|").map { raw ->
        val cell = raw.trim()
        val left = cell.startsWith(':')
        val right = cell.endsWith(':') && cell.length > 1
        when {
            left && right -> MdTableAlign.CENTER
            right -> MdTableAlign.END
            else -> MdTableAlign.START
        }
    }

internal fun parseTable(lines: List<String>): ParsedTable {
    val headers = mutableListOf<String>()
    val rows = mutableListOf<List<String>>()
    var alignments: List<MdTableAlign> = emptyList()
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.matches(tableSeparatorRegex)) {
            if (alignments.isEmpty() && headers.isNotEmpty() && rows.isEmpty()) {
                alignments = parseTableAlignments(trimmed)
            }
            continue
        }
        val core = trimmed.removePrefix("|").removeSuffix("|")
        val cells = core.split("|").map { it.trim() }
        if (headers.isEmpty()) headers.addAll(cells) else rows.add(cells)
    }
    return ParsedTable(headers, rows, alignments)
}


@Composable
private fun RenderBlock(block: MdBlock) {
    val colors = currentMdColors()
    val liveIncremental = LocalLiveIncremental.current
    when (block) {
        is MdBlock.Paragraph -> {
            MdText(
                text = if (liveIncremental) MarkdownParseCaches.inlineIncremental(block.raw, colors)
                       else MarkdownParseCaches.inline(block.raw, colors),
                fontSize = BaseFontSize,
                lineHeight = BaseLineHeight,
                color = colors.text,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                inlineContent = rememberKatexInlineContent(
                    BaseFontSize,
                    if (liveIncremental) MarkdownParseCaches.mathLatexIncremental(block.raw)
                    else MarkdownParseCaches.mathLatex(block.raw),
                ),
            )
        }

        is MdBlock.Heading -> {
            val (size, weight) = when (block.level) {
                1 -> (BaseFontSize * 1.5f) to FontWeight.Bold
                2 -> (BaseFontSize * 1.3f) to FontWeight.Bold
                3 -> (BaseFontSize * 1.15f) to FontWeight.SemiBold
                4 -> BaseFontSize to FontWeight.SemiBold
                5 -> (BaseFontSize * 0.875f) to FontWeight.SemiBold
                else -> (BaseFontSize * 0.85f) to FontWeight.SemiBold
            }
            MdText(
                text = MarkdownParseCaches.inline(block.text, colors),
                fontSize = size,
                fontWeight = weight,
                lineHeight = size * 1.3f,
                color = colors.text,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                inlineContent = rememberKatexInlineContent(size, MarkdownParseCaches.mathLatex(block.text)),
            )
        }

        is MdBlock.CodeBlock -> {
            val codeSearchCurrent = com.openminis.app.ui.chat.search.chatSearchFieldIsCurrent(
                LocalChatSearchPath.current?.let { com.openminis.app.ui.chat.search.ChatSearchUnits.markdown(it) },
            )
            val clipboardManager = LocalClipboardManager.current
            var copied by remember { mutableStateOf(false) }
            if (copied) {
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(1500)
                    copied = false
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .then(
                        if (codeSearchCurrent) {
                            Modifier.border(2.dp, com.openminis.app.ui.chat.search.ChatSearchCurrentBackground, RoundedCornerShape(8.dp))
                        } else Modifier,
                    )
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.codeBg),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 8.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = block.language.ifEmpty { "code" },
                        fontSize = 11.sp,
                        color = MdCodeLangColor,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = if (copied) {
                            stringResource(R.string.image_copied_toast)
                        } else {
                            stringResource(R.string.copy_code_block)
                        },
                        tint = if (copied) Color(0xFF34C759) else Color.White.copy(alpha = 0.4f),
                        modifier = Modifier
                            .size(16.dp)
                            .clickable {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(block.code))
                                copied = true
                            },
                    )
                }
                val vScroll = rememberScrollState()
                val hScroll = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(vScroll)
                        .padding(bottom = 8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .horizontalScroll(hScroll)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = block.code,
                            fontSize = BaseFontSize * 0.85f,
                            fontFamily = FontFamily.Monospace,
                            color = colors.codeText,
                            lineHeight = BaseLineHeight * 0.9f,
                        )
                    }
                }
            }
        }

        is MdBlock.BlockQuote -> {
            val barColor = Color(0xFFFF9500)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .drawBehind {
                        drawRect(
                            color = barColor,
                            topLeft = Offset.Zero,
                            size = Size(3.dp.toPx(), size.height),
                        )
                    }
                    .padding(start = 15.dp),
            ) {
                val quotePath = LocalChatSearchPath.current
                block.innerBlocks.forEachIndexed { k, inner ->
                    WithSearchPath(quotePath?.let { "$it/q/b$k" }) { RenderBlock(inner) }
                }
            }
        }

        is MdBlock.UnorderedList -> {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                val listPath = LocalChatSearchPath.current
                block.items.forEachIndexed { j, item ->
                    Row(modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)) {
                        Text("•  ", fontSize = BaseFontSize * 1.3f, color = colors.text)
                        WithSearchPath(listPath?.let { "$it/$j" }) { RenderListItemBody(item, colors, Modifier.weight(1f)) }
                    }
                }
            }
        }

        is MdBlock.OrderedList -> {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                block.items.forEachIndexed { index, item ->
                    Row(modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)) {
                        Text(
                            "${block.startNum + index}.  ",
                            fontSize = BaseFontSize,
                            color = colors.text,
                        )
                        WithSearchPath(LocalChatSearchPath.current?.let { "$it/$index" }) {
                            RenderListItemBody(item, colors, Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        is MdBlock.TaskList -> {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                val taskPath = LocalChatSearchPath.current
                block.items.forEachIndexed { j, item ->
                    Row(
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                        verticalAlignment = if (item.children.isEmpty()) Alignment.CenterVertically else Alignment.Top,
                    ) {
                        Checkbox(
                            checked = item.checked,
                            onCheckedChange = null,
                            modifier = Modifier.size(20.dp),
                            colors = CheckboxDefaults.colors(
                                checkedColor = colors.link,
                            ),
                        )
                        Spacer(Modifier.width(6.dp))
                        WithSearchPath(taskPath?.let { "$it/$j" }) {
                            RenderListItemBody(
                                text = item.text,
                                children = item.children,
                                colors = colors,
                                modifier = Modifier.weight(1f),
                                textColor = if (item.checked) colors.text.copy(alpha = 0.5f) else colors.text,
                            )
                        }
                    }
                }
            }
        }

        is MdBlock.HorizontalRule -> {
            HorizontalDivider(
                color = colors.divider,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        is MdBlock.Image -> {
            android.util.Log.d("MdStream", "render Image url=${block.url}")
            val imageTapHandler = LocalMarkdownImageTapHandler.current
            val urlTapHandler = LocalMarkdownUrlClickHandler.current
            val ambientMessageId = LocalShardId.current?.messageId
            val onTap: (() -> Unit)? = when {
                imageTapHandler != null && ambientMessageId != null ->
                    { -> imageTapHandler(ambientMessageId, block.url) }
                urlTapHandler != null -> { -> urlTapHandler(block.url) }
                else -> null
            }
            val context = LocalContext.current
            val sessionId = LocalMarkdownSessionId.current
            val file = remember(block.url, sessionId) { resolveMdMediaFile(context, block.url, sessionId) }
            val imageShape = RoundedCornerShape(8.dp)
            val imageBaseModifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .shadow(
                    elevation = 2.dp,
                    shape = imageShape,
                    clip = false,
                    ambientColor = ChatColors.inputShadow,
                    spotColor = ChatColors.inputShadow,
                )
                .clip(imageShape)
                .border(1.dp, ChatColors.thumbnailBorder, imageShape)
                .let { m -> if (onTap != null) m.clickable { onTap() } else m }
            val imageRequest = remember(file, block.url, sessionId) {
                ImageRequest.Builder(context)
                    .data(file ?: block.url)
                    .apply { if (file == null && sessionId != null) setParameter(com.openminis.app.ui.MinisImageFetcher.SESSION_PARAM, sessionId) }
                    .limitDisplaySize()
                    .build()
            }
            SubcomposeAsyncImage(
                model = imageRequest,
                contentDescription = block.alt,
                modifier = imageBaseModifier,
                contentScale = ContentScale.FillWidth,
            ) {
                when (painter.state) {
                    is AsyncImagePainter.State.Error -> BrokenImagePlaceholder(alt = block.alt)
                    else -> SubcomposeAsyncImageContent()
                }
            }
        }

        is MdBlock.Video -> {
            android.util.Log.d("MdStream", "render Video url=${block.url}")
            RenderMdVideo(block)
        }

        is MdBlock.Audio -> {
            android.util.Log.d("MdStream", "render Audio url=${block.url}")
            RenderMdAudio(block)
        }

        is MdBlock.Table -> {
            RenderSearchableTable(block)
        }

        is MdBlock.MathDisplay -> {
            RenderMathDisplay(block.latex)
        }
    }
}


@Composable
private fun rememberKatexInlineContent(
    fontSize: TextUnit,
    latexList: List<String>,
): Map<String, androidx.compose.foundation.text.InlineTextContent> {
    if (latexList.isEmpty()) return emptyMap()
    return remember(fontSize, latexList) {
        val map = HashMap<String, androidx.compose.foundation.text.InlineTextContent>(latexList.size)
        for (latex in latexList.toSet()) {
            val (w, h) = estimateInlineMathSize(latex, fontSize)
            map[katexInlineTagFor(latex)] = androidx.compose.foundation.text.InlineTextContent(
                placeholder = androidx.compose.ui.text.Placeholder(
                    width = w,
                    height = h,
                    placeholderVerticalAlign = androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter,
                ),
            ) { _ ->
                RenderInlineMath(latex = latex, fontSize = fontSize)
            }
        }
        map
    }
}

@Composable
private fun RenderInlineMath(latex: String, fontSize: TextUnit) {
    val context = LocalContext.current
    val isDark = ChatColors.isDark
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val fontSizeCssPx = (fontSize.value * fontScale).toInt().coerceAtLeast(12)
    val palette = currentMdColors()
    val result by androidx.compose.runtime.produceState<KatexRenderResult?>(
        initialValue = null,
        key1 = latex,
        key2 = isDark,
        key3 = fontSizeCssPx,
    ) {
        value = KatexWebViewPool.render(
            context = context,
            latex = latex,
            displayMode = false,
            isDark = isDark,
            fontSizePx = fontSizeCssPx,
        )
    }
    val rendered = result
    if (rendered != null) {
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        val naturalWidthDp = (rendered.bitmap.width / density).dp
        val naturalHeightDp = (rendered.bitmap.height / density).dp
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterStart,
        ) {
            val fit = minOf(
                1f,
                if (naturalWidthDp > maxWidth) maxWidth / naturalWidthDp else 1f,
                if (naturalHeightDp > maxHeight) maxHeight / naturalHeightDp else 1f,
            )
            androidx.compose.foundation.Image(
                bitmap = rendered.bitmap.asImageBitmap(),
                contentDescription = "math: $latex",
                modifier = Modifier.size(naturalWidthDp * fit, naturalHeightDp * fit),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        }
    } else {
        Text(
            text = latex,
            fontSize = fontSize * 0.9f,
            fontFamily = FontFamily.Monospace,
            color = palette.text,
        )
    }
}

@Composable
private fun RenderListItemBody(item: ListItem, colors: MdColors, modifier: Modifier) =
    RenderListItemBody(item.text, item.children, colors, modifier, colors.text)

@Composable
private fun RenderListItemBody(
    text: String,
    children: List<MdBlock>,
    colors: MdColors,
    modifier: Modifier,
    textColor: androidx.compose.ui.graphics.Color,
) {
    if (children.isEmpty()) {
        MdText(
            text = MarkdownParseCaches.inline(text, colors),
            fontSize = BaseFontSize,
            lineHeight = BaseLineHeight,
            color = textColor,
            modifier = modifier,
            inlineContent = rememberKatexInlineContent(BaseFontSize, MarkdownParseCaches.mathLatex(text)),
        )
        return
    }
    val itemPath = LocalChatSearchPath.current
    Column(modifier = modifier) {
        for ((k, child) in children.withIndex()) {
            WithSearchPath(itemPath?.let { "$it/$k" }) {
            when (child) {
                is MdBlock.MathDisplay -> RenderMathDisplay(child.latex)
                is MdBlock.Table -> RenderSearchableTable(child)
                else -> MdText(
                    text = MarkdownParseCaches.inline(child.raw, colors),
                    fontSize = BaseFontSize,
                    lineHeight = BaseLineHeight,
                    color = textColor,
                    inlineContent = rememberKatexInlineContent(BaseFontSize, MarkdownParseCaches.mathLatex(child.raw)),
                )
            }
            }
        }
    }
}

@Composable
private fun RenderMathDisplay(latex: String) {
    val context = LocalContext.current
    val isDark = ChatColors.isDark
    val displayFontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val fontSizeCssPx = (BaseFontSize.value * displayFontScale).toInt().coerceAtLeast(12)
    val palette = currentMdColors()

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        val lineMaxWidth = maxWidth
        val maxWidthPx = (lineMaxWidth.value * density).toInt()
        val rows by androidx.compose.runtime.produceState<List<KatexRenderResult>?>(
            initialValue = null,
            latex, isDark, fontSizeCssPx, maxWidthPx,
        ) {
            suspend fun render(tex: String) = KatexWebViewPool.render(
                context = context,
                latex = tex,
                displayMode = true,
                isDark = isDark,
                fontSizePx = fontSizeCssPx,
            )
            val full = render(latex)
            if (full == null || full.bitmap.width <= maxWidthPx || maxWidthPx <= 0) {
                value = full?.let { listOf(it) } ?: emptyList()
                return@produceState
            }
            val pieces = MathLineBreaker.splitAtTopLevelRelations(latex)
            if (pieces.size <= 1) {
                value = listOf(full)
                return@produceState
            }
            val rowTex = MathLineBreaker.packRows(pieces) { candidate ->
                (render(candidate)?.bitmap?.width ?: Int.MAX_VALUE) <= maxWidthPx
            }
            val rendered = rowTex.map { render(it) }
            value = if (rendered.all { it != null }) rendered.filterNotNull() else listOf(full)
        }

        val rendered = rows
        if (!rendered.isNullOrEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                    (fontSizeCssPx * 0.25f).dp,
                ),
            ) {
                for ((rowIdx, row) in rendered.withIndex()) {
                    val naturalWidthDp = (row.bitmap.width / density).dp
                    val naturalHeightDp = (row.bitmap.height / density).dp
                    val scale = if (naturalWidthDp > lineMaxWidth) lineMaxWidth / naturalWidthDp else 1f
                    androidx.compose.foundation.Image(
                        bitmap = row.bitmap.asImageBitmap(),
                        contentDescription = if (rowIdx == 0) "math: $latex" else null,
                        modifier = Modifier.size(naturalWidthDp * scale, naturalHeightDp * scale),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                }
            }
        } else {
            Text(
                text = latex,
                fontSize = BaseFontSize * 0.95f,
                fontFamily = FontFamily.Monospace,
                color = palette.text,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}


@Composable
private fun BrokenImagePlaceholder(alt: String?) {
    val palette = com.openminis.app.ui.theme.LocalChatPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .background(palette.toolBg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.BrokenImage,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = palette.secondaryText,
            )
            Text(
                text = alt?.takeIf { it.isNotBlank() } ?: "Image not available",
                fontSize = 12.sp,
                color = palette.secondaryText,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


internal fun resolveMdMediaFile(context: Context, url: String, sessionId: String? = null): File? {
    if (url.isBlank()) return null
    val stripped = url.substringBefore('?')
    val primary: File? = when {
        stripped.startsWith("minis://") -> {
            val decoded = java.net.URLDecoder.decode(stripped.removePrefix("minis://"), "UTF-8")
            val linuxPath = "/var/minis/$decoded"
            if (sessionId != null) PRootKernel.resolveSessionHostPath(sessionId, linuxPath, context)
            else PRootKernel.resolveHostPath(linuxPath)
        }
        stripped.startsWith("file://") -> File(Uri.parse(stripped).path ?: return null)
        stripped.startsWith("/") -> File(stripped)
        else -> null
    }
    if (primary?.let { it.exists() && it.isFile } == true) {
        android.util.Log.d("MdStream", "resolveMdMediaFile url=$url sid=$sessionId -> primary=${primary.absolutePath}")
        return primary
    }

    if (!stripped.startsWith("minis://")) {
        android.util.Log.d("MdStream", "resolveMdMediaFile primary miss url=$url (non-minis scheme, no fallback)")
        return null
    }
    val decoded = java.net.URLDecoder.decode(stripped.removePrefix("minis://"), "UTF-8")
    val basename = decoded.substringAfterLast('/')
    val subdir = decoded.substringBefore('/', missingDelimiterValue = "").takeIf { it.isNotEmpty() } ?: "attachments"
    val root = File(context.filesDir, "minis-sessions")
    if (root.isDirectory) {
        root.listFiles()?.forEach { sessionDir ->
            val candidate = File(sessionDir, "$subdir/$basename")
            if (candidate.exists() && candidate.isFile) {
                android.util.Log.d("MdStream", "resolveMdMediaFile url=$url -> fallback=${candidate.absolutePath}")
                return candidate
            }
        }
    }
    val globalCandidate = File(context.filesDir, "minis-global/$subdir/$basename")
    if (globalCandidate.exists() && globalCandidate.isFile) {
        android.util.Log.d("MdStream", "resolveMdMediaFile url=$url -> global=${globalCandidate.absolutePath}")
        return globalCandidate
    }
    android.util.Log.w("MdStream", "resolveMdMediaFile url=$url -> NOT FOUND (primary=${primary?.absolutePath})")
    return null
}

private fun filenameFromMdUrl(url: String): String {
    val stripped = url.substringBefore('?')
    val last = stripped.substringAfterLast('/')
    return try { java.net.URLDecoder.decode(last, "UTF-8") } catch (_: Throwable) { last }
}

private fun openMdMediaExternally(context: Context, file: File, mime: String) {
    android.util.Log.d("MdStream", "openMdMediaExternally file=${file.absolutePath} mime=$mime")
    val authority = context.packageName + ".fileprovider"
    val uri = try {
        androidx.core.content.FileProvider.getUriForFile(context, authority, file)
    } catch (t: Throwable) {
        android.util.Log.w("MdStream", "FileProvider failed: ${t.message}")
        Uri.fromFile(file)
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val chooser = Intent.createChooser(intent, file.name).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try { context.startActivity(chooser) } catch (t: Throwable) {
        android.util.Log.w("MdStream", "startActivity failed: ${t.message}")
    }
}

private fun formatMdMediaMs(ms: Int): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

@Composable
private fun RenderMdVideo(block: MdBlock.Video) {
    val context = LocalContext.current
    val colors = currentMdColors()
    val sessionId = LocalMarkdownSessionId.current
    val file = remember(block.url, sessionId) { resolveMdMediaFile(context, block.url, sessionId) }
    val filename = remember(block.url) { filenameFromMdUrl(block.url) }
    var showPlayer by remember { mutableStateOf(false) }

    val thumbnail by produceState<Bitmap?>(initialValue = null, key1 = file?.absolutePath) {
        val f = file ?: run { value = null; return@produceState }
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(f.absolutePath)
                val bmp = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                android.util.Log.d("MdStream", "video thumbnail ${f.name} -> ${bmp?.width}x${bmp?.height}")
                bmp
            } catch (t: Throwable) {
                android.util.Log.w("MdStream", "video thumbnail failed: ${t.message}")
                null
            } finally {
                try { retriever.release() } catch (_: Throwable) {}
            }
        }
    }

    if (showPlayer && file != null) {
        com.openminis.app.ui.media.MinisFullscreenVideoPlayer(
            file = file,
            onDismiss = { showPlayer = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.inlineCodeBg)
            .border(0.5.dp, colors.tableBorder, RoundedCornerShape(8.dp))
            .clickable(enabled = file != null) {
                android.util.Log.d("MdStream", "open fullscreen video for ${file?.absolutePath}")
                showPlayer = true
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 280.dp),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = thumbnail
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = block.alt.ifEmpty { filename },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Icon(
                imageVector = Icons.Filled.PlayCircleFilled,
                contentDescription = "Play video",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(56.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Videocam,
                contentDescription = null,
                tint = colors.blockquote,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            MdText(
                text = AnnotatedString(filename),
                fontSize = 12.sp,
                color = colors.blockquote,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RenderMdAudio(block: MdBlock.Audio) {
    val context = LocalContext.current
    val colors = currentMdColors()
    val sessionId = LocalMarkdownSessionId.current
    val file = remember(block.url, sessionId) { resolveMdMediaFile(context, block.url, sessionId) }
    val filename = remember(block.url) { filenameFromMdUrl(block.url) }

    val player = remember(file?.absolutePath) {
        if (file == null) null else try {
            MediaPlayer().apply { setDataSource(file.absolutePath); prepare() }
        } catch (t: Throwable) {
            android.util.Log.w("MdStream", "audio prepare failed: ${t.message}")
            null
        }
    }
    DisposableEffect(player) {
        onDispose { try { player?.release() } catch (_: Throwable) {} }
    }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0) }
    val durationMs = player?.duration ?: 0

    LaunchedEffect(isPlaying) {
        while (isPlaying && player != null) {
            positionMs = try { player.currentPosition } catch (_: Throwable) { 0 }
            if (!player.isPlaying) { isPlaying = false; break }
            delay(200)
        }
    }
    DisposableEffect(player) {
        player?.setOnCompletionListener {
            isPlaying = false
            positionMs = 0
            try { player.seekTo(0) } catch (_: Throwable) {}
        }
        onDispose { try { player?.setOnCompletionListener(null) } catch (_: Throwable) {} }
    }

    val tint = colors.link
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.inlineCodeBg)
            .border(0.5.dp, colors.tableBorder, RoundedCornerShape(10.dp))
            .clickable(enabled = file != null) {
                if (player == null) {
                    file?.let { openMdMediaExternally(context, it, "audio/*") }
                } else {
                    if (isPlaying) { try { player.pause() } catch (_: Throwable) {} ; isPlaying = false }
                    else { try { player.start(); isPlaying = true } catch (_: Throwable) {} }
                }
            }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Audiotrack,
            contentDescription = null,
            tint = colors.blockquote,
            modifier = Modifier.size(18.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            MdText(
                text = AnnotatedString(block.alt.ifEmpty { filename }),
                fontSize = 13.sp,
                color = colors.text,
                maxLines = 1,
            )
            val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(3.dp),
                color = tint,
                trackColor = tint.copy(alpha = 0.2f),
            )
            if (durationMs > 0) {
                MdText(
                    text = AnnotatedString("${formatMdMediaMs(positionMs)} / ${formatMdMediaMs(durationMs)}"),
                    fontSize = 11.sp,
                    color = colors.blockquote,
                )
            }
        }
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = tint,
            modifier = Modifier.size(28.dp),
        )
    }
}

internal object TableHScrollStates {
    private const val MAX_ENTRIES = 64

    private val states = object : LinkedHashMap<String, ScrollState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ScrollState>): Boolean =
            size > MAX_ENTRIES
    }

    fun stateFor(key: String): ScrollState =
        synchronized(states) {
            states.getOrPut(key) { ScrollState(0) }
        }
}

internal val LocalTableNaturalWidthSink =
    androidx.compose.runtime.staticCompositionLocalOf<((Float) -> Unit)?> { null }

@Composable
private fun RenderSearchableTable(block: MdBlock.Table) {
    val searchPath = LocalChatSearchPath.current
    val searchCurrent = com.openminis.app.ui.chat.search.chatSearchFieldIsCurrent(
        searchPath?.let { com.openminis.app.ui.chat.search.ChatSearchUnits.markdown(it) },
    )
    WithSearchPath(null) {
        Box(
            modifier = if (searchCurrent) {
                Modifier.border(2.dp, com.openminis.app.ui.chat.search.ChatSearchCurrentBackground, RoundedCornerShape(6.dp))
            } else Modifier,
        ) { RenderTable(block) }
    }
}

@Composable
private fun RenderTable(block: MdBlock.Table) {
    val colors = currentMdColors()
    val naturalWidthSink = LocalTableNaturalWidthSink.current
    val colCount = maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0)
    if (colCount == 0) return

    val allRows: List<List<String>> = buildList {
        if (block.headers.isNotEmpty()) add(block.headers)
        addAll(block.rows)
    }

    val context = LocalContext.current
    val tableScope = rememberCoroutineScope()
    val tableGraphicsLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val tableCopiedToast = stringResource(R.string.markdown_table_copied_toast)
    val tableImageCopiedToast = stringResource(R.string.markdown_table_image_copied_toast)
    val tableImageCopyFailedToast = stringResource(R.string.markdown_table_image_copy_failed_toast)

    val selectionController = LocalMinisSelectionController.current
    val shardIdForTable = LocalShardId.current
    val messageId = shardIdForTable?.messageId

    val tableHScroll = if (shardIdForTable != null) {
        val hScrollKey = "${shardIdForTable.messageId}/${shardIdForTable.shardId}" +
            "/tbl:${block.headers.joinToString("|")}"
        remember(hScrollKey) { TableHScrollStates.stateFor(hScrollKey) }
    } else {
        rememberScrollState()
    }
    if (selectionController != null && messageId != null) {
        androidx.compose.runtime.DisposableEffect(selectionController, messageId, block.raw) {
            val actions = SelectionController.TableActions(
                copyTableMarkdown = {
                    val md = block.raw
                    if (md.isNotEmpty()) {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("table", md))
                        Toast.makeText(context, tableCopiedToast, Toast.LENGTH_SHORT).show()
                    }
                },
                copyTableImage = {
                    tableScope.launch {
                        try {
                            val imageBitmap = tableGraphicsLayer.toImageBitmap()
                            val androidBitmap = imageBitmap.asAndroidBitmap()
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val shareDir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
                                val outFile = java.io.File(shareDir, "table_${System.currentTimeMillis()}.png")
                                outFile.outputStream().use {
                                    androidBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                                }
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    outFile,
                                )
                                context.grantUriPermission(
                                    "*", uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                )
                                val clip = android.content.ClipData.newUri(
                                    context.contentResolver, "table-image", uri,
                                )
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    cm.setPrimaryClip(clip)
                                    Toast.makeText(context, tableImageCopiedToast, Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, tableImageCopyFailedToast, Toast.LENGTH_SHORT).show()
                        }
                    }
                    Unit
                },
            )
            selectionController.rememberTableActions(messageId, actions)
            onDispose { selectionController.forgetTableActions(messageId) }
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        val viewportWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }.toInt()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, colors.tableBorder, RoundedCornerShape(6.dp))
                .drawWithContent {
                    tableGraphicsLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(tableGraphicsLayer)
                }
                .horizontalScroll(tableHScroll),
        ) {
        val lineColor = colors.tableBorder
        val lineStrokePx = with(androidx.compose.ui.platform.LocalDensity.current) { 1.dp.toPx() }
        val totalRowCount = allRows.size
        val cellFontSize = BaseFontSize
        val tableDensity = androidx.compose.ui.platform.LocalDensity.current
        val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
        val unbreakableDp = remember(allRows, colCount, cellFontSize, tableDensity) {
            FloatArray(colCount) { colIdx ->
                var widest = 0f
                for ((rowIndex, cells) in allRows.withIndex()) {
                    val plain = MarkdownParseCaches.inline(cells.getOrElse(colIdx) { "" }, colors).text
                    val word = TableColumnPlanner.longestUnbreakableRun(plain) ?: continue
                    val bold = rowIndex == 0 && block.headers.isNotEmpty()
                    val px = textMeasurer.measure(
                        word,
                        TextStyle(fontSize = cellFontSize, fontWeight = if (bold) FontWeight.SemiBold else null),
                        softWrap = false,
                        maxLines = 1,
                    ).size.width
                    widest = maxOf(widest, with(tableDensity) { px.toDp().value } + TABLE_CELL_PAD_H_DP * 2 + 4f)
                }
                widest
            }
        }
        val mathMinDp = remember(allRows, colCount, cellFontSize, tableDensity) {
            FloatArray(colCount) { colIdx ->
                var widest = 0f
                for (cells in allRows) {
                    for (latex in MarkdownParseCaches.mathLatex(cells.getOrElse(colIdx) { "" })) {
                        val slot = with(tableDensity) { estimateInlineMathSize(latex, cellFontSize).first.toDp().value }
                        widest = maxOf(widest, slot + TABLE_CELL_PAD_H_DP * 2)
                    }
                }
                widest
            }
        }
        val widthFloor = remember(block.headers, colCount) { TableWidthFloor() }
        androidx.compose.ui.layout.Layout(
            content = {
                for ((rowIndex, cells) in allRows.withIndex()) {
                    val isHeader = rowIndex == 0 && block.headers.isNotEmpty()
                    val isLastRow = rowIndex == totalRowCount - 1
                    for (colIndex in 0 until colCount) {
                        val isLastCol = colIndex == colCount - 1
                        val columnAlign = block.alignments.getOrElse(colIndex) { MdTableAlign.START }
                        Box(
                            modifier = Modifier
                                .then(if (isHeader) Modifier.background(colors.tableHeaderBg) else Modifier)
                                .drawBehind {
                                    if (!isLastCol) {
                                        drawLine(
                                            color = lineColor,
                                            start = androidx.compose.ui.geometry.Offset(size.width - lineStrokePx / 2, 0f),
                                            end = androidx.compose.ui.geometry.Offset(size.width - lineStrokePx / 2, size.height),
                                            strokeWidth = lineStrokePx,
                                        )
                                    }
                                    if (!isLastRow) {
                                        drawLine(
                                            color = lineColor,
                                            start = androidx.compose.ui.geometry.Offset(0f, size.height - lineStrokePx / 2),
                                            end = androidx.compose.ui.geometry.Offset(size.width, size.height - lineStrokePx / 2),
                                            strokeWidth = lineStrokePx,
                                        )
                                    }
                                }
                                .padding(horizontal = TABLE_CELL_PAD_H_DP.dp, vertical = 8.dp),
                            contentAlignment = when (columnAlign) {
                                MdTableAlign.CENTER -> Alignment.TopCenter
                                MdTableAlign.END -> Alignment.TopEnd
                                MdTableAlign.START -> Alignment.TopStart
                            },
                        ) {
                            val cellText = cells.getOrElse(colIndex) { "" }
                            MdText(
                                text = MarkdownParseCaches.inline(cellText, colors),
                                fontSize = cellFontSize,
                                fontWeight = if (isHeader) FontWeight.SemiBold else null,
                                color = colors.text,
                                inlineContent = rememberKatexInlineContent(cellFontSize, MarkdownParseCaches.mathLatex(cellText)),
                                isAtomicSelectionUnit = true,
                                textAlign = when (columnAlign) {
                                    MdTableAlign.CENTER -> TextAlign.Center
                                    MdTableAlign.END -> TextAlign.End
                                    MdTableAlign.START -> TextAlign.Start
                                },
                            )
                        }
                    }
                }
            },
        ) { measurables, _ ->
            val rowCount = allRows.size

            val maxCellWidth = (viewportWidthPx * 5).coerceAtLeast(1)

            val naturalPx = IntArray(colCount)
            for (rowIdx in 0 until rowCount) {
                for (colIdx in 0 until colCount) {
                    val idx = rowIdx * colCount + colIdx
                    if (idx < measurables.size) {
                        val intrinsic = measurables[idx].maxIntrinsicWidth(0)
                            .coerceIn(0, maxCellWidth)
                        naturalPx[colIdx] = maxOf(naturalPx[colIdx], intrinsic)
                    }
                }
            }

            val dpPerPx = 1f / density
            val viewportDp = viewportWidthPx * dpPerPx
            naturalWidthSink?.invoke(
                (0 until colCount).sumOf { i ->
                    maxOf(TableColumnPlanner.FLOOR_WIDTH, kotlin.math.ceil(naturalPx[i] * dpPerPx + 12f)).toDouble()
                }.toFloat(),
            )
            var plan = TableColumnPlanner.plan(
                List(colCount) {
                    TableColumnPlanner.Column(naturalPx[it] * dpPerPx + 12f, unbreakableDp[it], mathMinDp[it])
                },
                viewportDp,
            )
            plan = TableColumnPlanner.applyingStreamingFloor(
                plan,
                widthFloor.widths.takeIf { widthFloor.viewportPx == viewportWidthPx },
                viewportDp,
            )
            widthFloor.viewportPx = viewportWidthPx
            widthFloor.widths = plan.widths
            val colWidths = IntArray(colCount) { kotlin.math.round(plan.widths[it] * density).toInt() }
            val roundedWidth = colWidths.sum()
            if (roundedWidth < viewportWidthPx && colCount > 0) {
                colWidths[colCount - 1] += viewportWidthPx - roundedWidth
            }

            val colW = IntArray(colCount) { colWidths[it].coerceIn(0, maxCellWidth) }
            val rowHeights = IntArray(rowCount) { rowIdx ->
                var h = 0
                for (colIdx in 0 until colCount) {
                    h = maxOf(h, measurables[rowIdx * colCount + colIdx].maxIntrinsicHeight(colW[colIdx]))
                }
                h
            }

            val placeables = Array(rowCount) { rowIdx ->
                Array(colCount) { colIdx ->
                    val p = measurables[rowIdx * colCount + colIdx].measure(
                        androidx.compose.ui.unit.Constraints(
                            minWidth = colW[colIdx],
                            maxWidth = colW[colIdx],
                            minHeight = rowHeights[rowIdx],
                        )
                    )
                    rowHeights[rowIdx] = maxOf(rowHeights[rowIdx], p.height)
                    p
                }
            }

            val totalWidth = colWidths.sum()
            val totalHeight = rowHeights.sum()

            layout(totalWidth, totalHeight) {
                var y = 0
                for (rowIdx in 0 until rowCount) {
                    var x = 0
                    for (colIdx in 0 until colCount) {
                        val p = placeables[rowIdx][colIdx]
                        p.place(x, y)
                        x += colWidths[colIdx]
                    }
                    y += rowHeights[rowIdx]
                }
            }
        }
        }
    }
}

private const val TABLE_CELL_PAD_H_DP = 8f

private class TableWidthFloor {
    var viewportPx: Int = -1
    var widths: FloatArray? = null
}

internal object StreamRenderProfiler {
    private const val FLUSH_TICKS = 20
    private var ticks = 0
    private var parseMsSum = 0.0
    private var parseMsMax = 0.0
    private var lastFragLen = 0
    private var maxFragLen = 0

    @Synchronized
    fun recordParse(fragLen: Int, ms: Double) {
        com.openminis.app.diagnostics.JankDiag.onStreamParse(fragLen, ms)
        parseMsSum += ms
        if (ms > parseMsMax) parseMsMax = ms
        lastFragLen = fragLen
        if (fragLen > maxFragLen) maxFragLen = fragLen
        ticks++
        if (ticks < FLUSH_TICKS) return
        val n = ticks
        com.openminis.app.logging.AppLogger.info(
            "StreamRender",
            "[StreamRender] ticks=$n fragLen=$lastFragLen(max=$maxFragLen) " +
                "parseMs avg=${"%.1f".format(parseMsSum / n)} max=${"%.1f".format(parseMsMax)}",
        )
        ticks = 0; parseMsSum = 0.0; parseMsMax = 0.0; maxFragLen = 0
    }
}

private object MarkdownParseCaches {
    private const val CHAR_BUDGET_PER_CACHE = 2_000_000

    private class Lru<K, V>(
        private val budget: Int,
        private val sizer: (K, V) -> Int,
    ) : LinkedHashMap<K, V>(64, 0.75f, true) {
        var totalChars: Int = 0
            private set

        override fun put(key: K, value: V): V? {
            val prior = super.put(key, value)
            if (prior != null) totalChars -= sizer(key, prior).coerceAtLeast(0)
            totalChars += sizer(key, value).coerceAtLeast(0)
            while (totalChars > budget && size > 1) {
                val eldest = entries.iterator().next()
                totalChars -= sizer(eldest.key, eldest.value).coerceAtLeast(0)
                entries.remove(eldest)
            }
            return prior
        }

        override fun remove(key: K): V? {
            val v = super.remove(key) ?: return null
            totalChars -= sizer(key, v).coerceAtLeast(0)
            return v
        }

        override fun clear() {
            super.clear()
            totalChars = 0
        }

        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = false
    }

    private val inlineLru = Lru<Pair<String, MdColors>, AnnotatedString>(
        budget = CHAR_BUDGET_PER_CACHE,
        sizer = { k, _ -> k.first.length },
    )
    private val mathLru = Lru<String, List<String>>(
        budget = CHAR_BUDGET_PER_CACHE,
        sizer = { k, _ -> k.length },
    )
    private val blocksLru = Lru<String, List<MdBlock>>(
        budget = CHAR_BUDGET_PER_CACHE,
        sizer = { k, _ -> k.length },
    )

    fun inline(text: String, colors: MdColors): AnnotatedString {
        val key = text to colors
        synchronized(inlineLru) { inlineLru[key] }?.let { return it }
        val t0 = System.nanoTime()
        val computed = parseInline(text, colors)
        maybeLogSlowParse("inline", text.length, (System.nanoTime() - t0) / 1_000_000)
        synchronized(inlineLru) { inlineLru[key] = computed }
        return computed
    }

    fun mathLatex(text: String): List<String> {
        synchronized(mathLru) { mathLru[text] }?.let { return it }
        val t0 = System.nanoTime()
        val computed = collectInlineMathLatex(text)
        maybeLogSlowParse("math", text.length, (System.nanoTime() - t0) / 1_000_000)
        synchronized(mathLru) { mathLru[text] = computed }
        return computed
    }


    private const val INCREMENTAL_MIN_CHARS = 1_500

    fun inlineIncremental(text: String, colors: MdColors): AnnotatedString {
        synchronized(inlineLru) { inlineLru[text to colors] }?.let { return it }
        if (text.length < INCREMENTAL_MIN_CHARS) return inline(text, colors)
        val split = safeInlineSplitOffset(text)
        if (split <= 0) return inline(text, colors)
        val prefixAnn = inline(text.substring(0, split), colors)
        val t0 = System.nanoTime()
        val suffixAnn = parseInline(text.substring(split), colors)
        maybeLogSlowParse("inline-incr", text.length - split, (System.nanoTime() - t0) / 1_000_000)
        return androidx.compose.ui.text.buildAnnotatedString {
            append(prefixAnn)
            append(suffixAnn)
        }
    }

    fun mathLatexIncremental(text: String): List<String> {
        synchronized(mathLru) { mathLru[text] }?.let { return it }
        if (text.length < INCREMENTAL_MIN_CHARS) return mathLatex(text)
        val split = safeInlineSplitOffset(text)
        if (split <= 0) return mathLatex(text)
        val prefix = mathLatex(text.substring(0, split))
        val suffix = collectInlineMathLatex(text.substring(split))
        return if (suffix.isEmpty()) prefix else prefix + suffix
    }

    private const val SLOW_PARSE_LOG_MS = 80L
    private fun maybeLogSlowParse(layer: String, chars: Int, ms: Long, extra: String = "") {
        if (ms < SLOW_PARSE_LOG_MS) return
        val thread = if (android.os.Looper.getMainLooper().isCurrentThread) {
            "main"
        } else {
            Thread.currentThread().name
        }
        com.openminis.app.logging.AppLogger.info(
            "JankDiag",
            "[JankDiag] slow markdown parse layer=$layer chars=$chars ms=$ms thread=$thread$extra",
        )
    }

    fun cachedBlocks(raw: String): List<MdBlock>? =
        synchronized(blocksLru) { blocksLru[raw] }

    fun putBlocks(raw: String, blocks: List<MdBlock>) {
        synchronized(blocksLru) { blocksLru[raw] = blocks }
    }

    fun removeBlocks(raw: String) {
        synchronized(blocksLru) { blocksLru.remove(raw) }
    }

    fun blocks(raw: String): List<MdBlock> {
        synchronized(blocksLru) { blocksLru[raw] }?.let { return it }
        val t0 = System.nanoTime()
        val computed = parseMarkdownBlocksBlocking(raw)
        maybeLogSlowParse(
            "blocks", raw.length, (System.nanoTime() - t0) / 1_000_000,
            extra = " blocks=${computed.size}",
        )
        synchronized(blocksLru) { blocksLru[raw] = computed }
        return computed
    }

    fun prewarm(blocks: List<MdBlock>, colors: MdColors) {
        for (b in blocks) {
            when (b) {
                is MdBlock.Paragraph -> { inline(b.raw, colors); mathLatex(b.raw) }
                is MdBlock.Heading -> { inline(b.text, colors); mathLatex(b.text) }
                is MdBlock.UnorderedList -> b.items.forEach {
                    inline(it.text, colors); mathLatex(it.text); prewarm(it.children, colors)
                }
                is MdBlock.OrderedList -> b.items.forEach {
                    inline(it.text, colors); mathLatex(it.text); prewarm(it.children, colors)
                }
                is MdBlock.TaskList -> b.items.forEach {
                    inline(it.text, colors); mathLatex(it.text); prewarm(it.children, colors)
                }
                is MdBlock.Table -> {
                    b.headers.forEach { inline(it, colors); mathLatex(it) }
                    b.rows.forEach { row -> row.forEach { inline(it, colors); mathLatex(it) } }
                }
                is MdBlock.BlockQuote -> prewarm(b.innerBlocks, colors)
                else -> Unit
            }
        }
    }

    fun prewarmLiveTail(blocks: List<MdBlock>, colors: MdColors) {
        val last = blocks.lastOrNull() as? MdBlock.Paragraph ?: return
        val ann = inlineIncremental(last.raw, colors)
        synchronized(inlineLru) { inlineLru[last.raw to colors] = ann }
        val math = mathLatexIncremental(last.raw)
        synchronized(mathLru) { mathLru[last.raw] = math }
    }
}


private const val INCR_TAIL_MARGIN = 256

@androidx.annotation.VisibleForTesting
internal fun safeInlineSplitOffset(text: String): Int {
    var boldStar = false
    var boldUnder = false
    var strike = false
    var inLabel = false
    var inUrl = false
    var lastSafeNewlineEnd = 0
    val limit = text.length - INCR_TAIL_MARGIN
    if (limit <= 0) return 0

    var i = 0
    while (i < limit) {
        val c = text[i]
        when {
            c == '\\' && i + 1 < text.length -> { i += 2; continue }
            c == '`' -> {
                val close = findInlineCodeClose(text, i + 1)
                i = if (close != -1) close + 1 else i + 1
                continue
            }
            text.startsWith("~~", i) -> { strike = !strike; i += 2; continue }
            text.startsWith("**", i) -> { boldStar = !boldStar; i += 2; continue }
            text.startsWith("__", i) -> { boldUnder = !boldUnder; i += 2; continue }
            inLabel && text.startsWith("](", i) -> { inLabel = false; inUrl = true; i += 2; continue }
            c == '[' -> { inLabel = true; i++ }
            c == ']' && inLabel -> { inLabel = false; i++ }
            c == ')' && inUrl -> { inUrl = false; i++ }
            c == '\n' -> {
                if (!boldStar && !boldUnder && !strike && !inLabel && !inUrl) {
                    lastSafeNewlineEnd = i + 1
                }
                i++
            }
            else -> i++
        }
    }
    return lastSafeNewlineEnd
}

private fun parseInline(text: String, colors: MdColors): AnnotatedString {
    return buildAnnotatedString {
        var htmlPairs: com.openminis.app.ui.markdown.InlineHtmlTags.LazyPairs? = null
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("\\(", i) -> {
                    val end = text.indexOf("\\)", i + 2)
                    if (end != -1) {
                        appendInlineContent(katexInlineTagFor(text.substring(i + 2, end)), text.substring(i + 2, end))
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                text[i] == '\\' && i + 1 < text.length -> {
                    append(text[i + 1]); i += 2
                }
                text.startsWith("![", i) -> {
                    val cb = text.indexOf(']', i + 2)
                    if (cb != -1 && cb + 1 < text.length && text[cb + 1] == '(') {
                        val cp = text.indexOf(')', cb + 2)
                        if (cp != -1) {
                            val alt = text.substring(i + 2, cb).ifEmpty { "image" }
                            withStyle(SpanStyle(color = colors.link)) { append("[$alt]") }
                            i = cp + 1
                        } else { append(text[i]); i++ }
                    } else { append(text[i]); i++ }
                }
                text.startsWith("***", i) -> {
                    val end = text.indexOf("***", i + 3)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                            appendRecursive(text.substring(i + 3, end), colors)
                        }
                        i = end + 3
                    } else { append(text[i]); i++ }
                }
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            appendRecursive(text.substring(i + 2, end), colors)
                        }
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                text.startsWith("__", i) -> {
                    val end = text.indexOf("__", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            appendRecursive(text.substring(i + 2, end), colors)
                        }
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            appendRecursive(text.substring(i + 2, end), colors)
                        }
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                text.startsWith("```", i) -> {
                    append("```"); i += 3
                }
                text[i] == '$' && i + 1 < text.length && text[i + 1] != '$' && text[i + 1] != ' ' -> {
                    val end = findInlineMathClose(text, i + 1)
                    if (end != -1) {
                        val latex = text.substring(i + 1, end)
                        if (looksLikeMath(latex) && !isTablePipeArtifact(latex)) {
                            appendInlineContent(katexInlineTagFor(latex), latex)
                            i = end + 1
                        } else { append(text[i]); i++ }
                    } else { append(text[i]); i++ }
                }
                text[i] == '`' -> {
                    val end = findInlineCodeClose(text, i + 1)
                    if (end != -1) {
                        val codeStyle = SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            color = colors.inlineCodeText,
                        )
                        withStyle(codeStyle) { append("\u2006") }
                        val annStart = length
                        withStyle(codeStyle) { append(text.substring(i + 1, end)) }
                        val annEnd = length
                        withStyle(codeStyle) { append("\u2006") }
                        addStringAnnotation("inline_code", "", annStart, annEnd)
                        i = end + 1
                    } else { append(text[i]); i++ }
                }
                text[i] == '[' && !text.startsWith("![", i - 1.coerceAtLeast(0)) -> {
                    val cb = text.indexOf(']', i + 1)
                    if (cb != -1 && cb + 1 < text.length && text[cb + 1] == '(') {
                        val cp = text.indexOf(')', cb + 2)
                        if (cp != -1) {
                            val url = text.substring(cb + 2, cp).trim()
                            val linkStart = length
                            val label = text.substring(i + 1, cb)
                            withStyle(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)) {
                                if ('<' in label || '&' in label) appendRecursive(label, colors) else append(label)
                            }
                            addStringAnnotation("url", url, linkStart, length)
                            i = cp + 1
                        } else { append(text[i]); i++ }
                    } else { append(text[i]); i++ }
                }
                (text[i] == '*' || text[i] == '_') && i + 1 < text.length && text[i + 1] != text[i] && text[i + 1] != ' ' -> {
                    val delim = text[i]
                    val end = text.indexOf(delim, i + 1)
                    if (end != -1 && end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            appendRecursive(text.substring(i + 1, end), colors)
                        }
                        i = end + 1
                    } else { append(text[i]); i++ }
                }
                text[i] == '&' && com.openminis.app.ui.markdown.InlineHtmlTags.entityAt(text, i) != null -> i += appendEntity(text, i)
                text[i] == '<' && com.openminis.app.ui.markdown.InlineHtmlTags.mayStartTag(text, i) -> {
                    val next = appendInlineHtml(
                        text, i, colors,
                        htmlPairs ?: com.openminis.app.ui.markdown.InlineHtmlTags.LazyPairs(text).also { htmlPairs = it },
                    )
                    if (next == -1) { append('<'); i++ } else i = next
                }
                text[i] == '\n' -> {
                    append('\n'); i++
                }
                else -> { append(text[i]); i++ }
            }
        }
    }
}

private fun AnnotatedString.Builder.appendInlineHtml(
    text: String,
    i: Int,
    colors: MdColors,
    pairs: com.openminis.app.ui.markdown.InlineHtmlTags.LazyPairs,
): Int {
    val tags = com.openminis.app.ui.markdown.InlineHtmlTags
    val br = tags.brLength(text, i)
    if (br > 0) {
        append('\n')
        return tags.afterBr(text, i, br)
    }
    val open = tags.openAt(text, i) ?: return -1
    val start = i + open.length
    val close = pairs.closeOf(i)
    if (close == -1) return -1
    val content = text.substring(start, close)
    if (open.kind == com.openminis.app.ui.markdown.InlineHtmlTags.Kind.CODE) {
        val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace, color = colors.inlineCodeText)
        withStyle(codeStyle) { append("\u2006") }
        val annStart = length
        withStyle(codeStyle) { append(content) }
        val annEnd = length
        withStyle(codeStyle) { append("\u2006") }
        addStringAnnotation("inline_code", "", annStart, annEnd)
    } else {
        val inner = parseInline(content, colors)
        withStyle(inlineHtmlSpan(open.kind)) { append(inner) }
    }
    return tags.closeEnd(text, close, open)
}

private fun AnnotatedString.Builder.appendEntity(text: String, i: Int): Int {
    val entity = com.openminis.app.ui.markdown.InlineHtmlTags.entityAt(text, i) ?: return 0
    append(entity.first)
    return entity.second
}

private fun inlineHtmlSpan(kind: com.openminis.app.ui.markdown.InlineHtmlTags.Kind): SpanStyle = when (kind) {
    com.openminis.app.ui.markdown.InlineHtmlTags.Kind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
    com.openminis.app.ui.markdown.InlineHtmlTags.Kind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
    com.openminis.app.ui.markdown.InlineHtmlTags.Kind.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
    com.openminis.app.ui.markdown.InlineHtmlTags.Kind.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    com.openminis.app.ui.markdown.InlineHtmlTags.Kind.SUB -> SpanStyle(
        baselineShift = androidx.compose.ui.text.style.BaselineShift.Subscript,
        fontSize = androidx.compose.ui.unit.TextUnit(0.75f, androidx.compose.ui.unit.TextUnitType.Em),
    )
    com.openminis.app.ui.markdown.InlineHtmlTags.Kind.SUP -> SpanStyle(
        baselineShift = androidx.compose.ui.text.style.BaselineShift.Superscript,
        fontSize = androidx.compose.ui.unit.TextUnit(0.75f, androidx.compose.ui.unit.TextUnitType.Em),
    )
    com.openminis.app.ui.markdown.InlineHtmlTags.Kind.CODE -> SpanStyle(fontFamily = FontFamily.Monospace)
}

private fun AnnotatedString.Builder.appendRecursive(text: String, colors: MdColors) {
    var htmlPairs: com.openminis.app.ui.markdown.InlineHtmlTags.LazyPairs? = null
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("\\(", i) -> {
                val end = text.indexOf("\\)", i + 2)
                if (end != -1) {
                    appendInlineContent(katexInlineTagFor(text.substring(i + 2, end)), text.substring(i + 2, end))
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '$' && i + 1 < text.length && text[i + 1] != '$' && text[i + 1] != ' ' -> {
                val end = findInlineMathClose(text, i + 1)
                if (end != -1) {
                    val latex = text.substring(i + 1, end)
                    if (looksLikeMath(latex) && !isTablePipeArtifact(latex)) {
                        appendInlineContent(katexInlineTagFor(latex), latex)
                        i = end + 1
                    } else { append(text[i]); i++ }
                } else { append(text[i]); i++ }
            }
            text[i] == '\\' && i + 1 < text.length -> { append(text[i + 1]); i += 2 }
            text.startsWith("```", i) -> { append("```"); i += 3 }
            text[i] == '`' -> {
                val end = findInlineCodeClose(text, i + 1)
                if (end != -1) {
                    val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace, color = colors.inlineCodeText)
                    withStyle(codeStyle) { append("\u2006") }
                    val annStart = length
                    withStyle(codeStyle) { append(text.substring(i + 1, end)) }
                    val annEnd = length
                    withStyle(codeStyle) { append("\u2006") }
                    addStringAnnotation("inline_code", "", annStart, annEnd)
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '[' -> {
                val cb = text.indexOf(']', i + 1)
                if (cb != -1 && cb + 1 < text.length && text[cb + 1] == '(') {
                    val cp = text.indexOf(')', cb + 2)
                    if (cp != -1) {
                        val url = text.substring(cb + 2, cp).trim()
                        val linkStart = length
                        val label = text.substring(i + 1, cb)
                        withStyle(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)) {
                            if ('<' in label || '&' in label) appendRecursive(label, colors) else append(label)
                        }
                        addStringAnnotation("url", url, linkStart, length)
                        i = cp + 1
                    } else { append(text[i]); i++ }
                } else { append(text[i]); i++ }
            }
            text[i] == '&' && com.openminis.app.ui.markdown.InlineHtmlTags.entityAt(text, i) != null -> i += appendEntity(text, i)
            text[i] == '<' && com.openminis.app.ui.markdown.InlineHtmlTags.mayStartTag(text, i) -> {
                val next = appendInlineHtml(
                    text, i, colors,
                    htmlPairs ?: com.openminis.app.ui.markdown.InlineHtmlTags.LazyPairs(text).also { htmlPairs = it },
                )
                if (next == -1) { append('<'); i++ } else i = next
            }
            else -> { append(text[i]); i++ }
        }
    }
}

private fun findInlineCodeClose(text: String, from: Int): Int {
    var k = from
    while (k < text.length) {
        val c = text[k]
        if (c == '`') return k
        if (c == '\n') return -1
        k++
    }
    return -1
}


internal const val KATEX_INLINE_TAG_PREFIX = "katex_inline:"

internal fun katexInlineTagFor(latex: String): String = KATEX_INLINE_TAG_PREFIX + latex

internal fun estimateInlineMathSize(latex: String, fontSize: TextUnit): Pair<TextUnit, TextUnit> {
    val visibleCharCount = run {
        var c = 0
        var i = 0
        while (i < latex.length) {
            val ch = latex[i]
            if (ch == '\\' && i + 1 < latex.length) {
                i++
                while (i < latex.length && latex[i].isLetter()) i++
                c += 1
                continue
            }
            if (ch == '{' || ch == '}' || ch == ' ') { i++; continue }
            c++
            i++
        }
        c.coerceAtLeast(1)
    }

    val charWidthEm = 0.95f
    val widthEm = (visibleCharCount * charWidthEm).coerceIn(1.5f, 22f)

    var heightEm = 1.7f
    if (latex.contains("\\frac") || latex.contains("\\binom") ||
        latex.contains("\\sum") || latex.contains("\\int") ||
        latex.contains("\\prod") || latex.contains("\\sqrt[")
    ) heightEm = 3.2f
    if (latex.contains("\\begin{") || latex.contains("\\\\")) heightEm = 4.5f

    return (fontSize * widthEm) to (fontSize * heightEm)
}

internal fun collectInlineMathLatex(text: String): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 1 < text.length && text[i + 1] != '(' && text[i + 1] != '[') {
            i += 2; continue
        }
        if (c == '\\' && i + 1 < text.length && text[i + 1] == '(') {
            val end = text.indexOf("\\)", i + 2)
            if (end != -1) {
                out.add(text.substring(i + 2, end))
                i = end + 2; continue
            }
        }
        if (c == '$' && i + 1 < text.length && text[i + 1] != '$' && text[i + 1] != ' ') {
            val end = findInlineMathClose(text, i + 1)
            if (end != -1) {
                val latex = text.substring(i + 1, end)
                if (looksLikeMath(latex) && !isTablePipeArtifact(latex)) {
                    out.add(latex)
                    i = end + 1; continue
                }
            }
        }
        i++
    }
    return out
}

private fun findInlineMathClose(text: String, from: Int): Int {
    var k = from
    while (k < text.length) {
        val c = text[k]
        if (c == '\n') return -1
        if (c == '\\' && k + 1 < text.length) { k += 2; continue }
        if (c == '$') {
            if (k + 1 < text.length && text[k + 1] == '$') return -1
            return k
        }
        k++
    }
    return -1
}

private fun looksLikeMath(latex: String): Boolean {
    if (latex.isBlank()) return false
    if (latex.contains('\\')) return true
    if (latex.contains('{') || latex.contains('}')) return true
    if (latex.contains('^') || latex.contains('_')) return true
    val mathChars = "=+-*/<>≤≥≠∑∫∏√∞αβγθπφλμωΔΩ"
    if (latex.any { it in mathChars }) return true
    if (latex[0].isDigit()) return false
    if (latex.first().isWhitespace() || latex.last().isWhitespace()) return false
    if (latex.length <= 30 && latex.all { it.isLetterOrDigit() }) return true
    return latex.length > 2
}

private fun isTablePipeArtifact(content: String): Boolean {
    var bareCount = 0
    for (idx in content.indices) {
        if (content[idx] != '|') continue
        if (idx > 0 && content[idx - 1] == '\\') continue
        bareCount++
        val prevIsSpace = idx > 0 && content[idx - 1].isWhitespace()
        val nextIsSpace = idx + 1 < content.length && content[idx + 1].isWhitespace()
        if (prevIsSpace || nextIsSpace) return true
    }
    return bareCount % 2 == 1
}

internal fun lineInkBoxes(result: TextLayoutResult): List<androidx.compose.ui.geometry.Rect> =
    (0 until result.lineCount).map { i ->
        val ink = visibleRange(result, i, result.getLineStart(i), result.getLineEnd(i))
        val top = result.getLineTop(i)
        val bottom = result.getLineBottom(i)
        if (ink == null) {
            androidx.compose.ui.geometry.Rect(0f, top, 0f, bottom)
        } else {
            val a = result.getBoundingBox(ink.first)
            val b = result.getBoundingBox(ink.last)
            androidx.compose.ui.geometry.Rect(minOf(a.left, b.left), top, maxOf(a.right, b.right), bottom)
        }
    }

private fun visibleRange(result: TextLayoutResult, line: Int, start: Int, end: Int): IntRange? {
    val chars = result.layoutInput.text.text
    var s = start.coerceAtLeast(result.getLineStart(line))
    val e = minOf(end, result.getLineEnd(line, visibleEnd = true), chars.length)
    while (s < e && chars[s].isWhitespace()) s++
    return if (e > s) s until e else null
}

internal fun tapTargetSpans(
    text: AnnotatedString,
    result: TextLayoutResult,
): List<Pair<AnnotatedString.Range<String>, androidx.compose.ui.geometry.Rect>> {
    val laidOut = result.layoutInput.text.length
    if (laidOut == 0) return emptyList()
    val out = ArrayList<Pair<AnnotatedString.Range<String>, androidx.compose.ui.geometry.Rect>>()
    for (tag in listOf("url", "inline_code")) {
        for (ann in text.getStringAnnotations(tag, 0, text.length)) {
            val start = ann.start.coerceIn(0, laidOut)
            val end = ann.end.coerceIn(0, laidOut)
            if (end <= start) continue
            val firstLine = result.getLineForOffset(start)
            val lastLine = result.getLineForOffset((end - 1).coerceAtLeast(start))
            for (line in firstLine..lastLine) {
                val ink = visibleRange(result, line, start, end) ?: continue
                val b = runCatching { result.getPathForRange(ink.first, ink.last + 1).getBounds() }.getOrNull() ?: continue
                if (b.width > 0f && b.height > 0f) out += ann to b
            }
        }
    }
    return out
}
