package com.openminis.app.ui.chat

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.openminis.app.R
import kotlinx.coroutines.delay

@Composable
internal fun contextUsagePageText(hint: ContextUsageHint, page: UsageHintPage): AnnotatedString {
    return when (page) {
        UsageHintPage.CONTEXT -> {
            val percent = hint.highlights.getOrNull(0) ?: ""
            val size = hint.highlights.getOrNull(1) ?: ""
            val context = stringResource(R.string.chat_context_usage_hint, percent, size)
            val color = ContextUsageColors.textHighlight(hint.tier)
            buildAnnotatedString {
                append(context)
                for (segment in hint.highlights) {
                    if (segment.isEmpty()) continue
                    val start = context.indexOf(segment)
                    if (start < 0) continue
                    addStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium), start, start + segment.length)
                }
            }
        }
        UsageHintPage.QUOTA -> {
            val quota = hint.quotaText.orEmpty()
            val color = ContextUsageColors.textHighlight(hint.quotaTier)
            buildAnnotatedString {
                append(quota)
                val q = hint.quotaHighlight
                if (!q.isNullOrEmpty() && hint.quotaTier != ContextUsage.Tier.NORMAL) {
                    val start = quota.indexOf(q)
                    if (start >= 0) addStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium), start, start + q.length)
                }
            }
        }
    }
}

@Composable
internal fun contextUsageSpokenText(hint: ContextUsageHint): String {
    val parts = ArrayList<String>(2)
    for (p in hint.pages()) parts.add(contextUsagePageText(hint, p).text)
    return parts.joinToString(" · ")
}

@Composable
internal fun UsageHintPageText(hint: ContextUsageHint, page: UsageHintPage, fontSize: TextUnit, color: Color) {
    val text = contextUsagePageText(hint, page)
    val spoken = contextUsageSpokenText(hint)
    var scale by remember(text.text, fontSize) { mutableFloatStateOf(1f) }
    var ready by remember(text.text, fontSize) { mutableStateOf(false) }
    Text(
        text = text,
        color = color,
        fontSize = fontSize * scale,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout ->
            if (layout.hasVisualOverflow && scale > MIN_FIT_SCALE) {
                scale = (scale - FIT_STEP).coerceAtLeast(MIN_FIT_SCALE)
            } else {
                ready = true
            }
        },
        modifier = Modifier
            .semantics { contentDescription = spoken }
            .drawWithContent { if (ready) drawContent() },
    )
}

private const val MIN_FIT_SCALE = 0.75f
private const val FIT_STEP = 0.05f

@Composable
internal fun rememberHintCarouselPage(hint: ContextUsageHint?, composerEmpty: Boolean, screenReaderOn: Boolean): Int {
    val carousel = remember { ComposerHintCarousel() }
    var page by remember { mutableIntStateOf(0) }
    if (hint != null && hint.generation != carousel.generation) {
        carousel.adopt(hint.generation)
        page = carousel.page
    }
    val pageCount = hint?.pages()?.size ?: 0
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(hint?.generation, pageCount, composerEmpty, screenReaderOn, lifecycleOwner) {
        if (!carousel.canTurn(pageCount, hint != null, composerEmpty, screenReaderOn)) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (carousel.canTurn(pageCount, true, composerEmpty, screenReaderOn)) {
                delay(ComposerHintCarousel.INTERVAL_MS)
                page = carousel.turn(pageCount)
            }
        }
    }
    return if (hint == null) 0 else page
}
