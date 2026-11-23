package com.openminis.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import kotlinx.coroutines.channels.Channel


internal val LocalAppendOnlyFade = compositionLocalOf { false }

internal sealed class FadeBirth {
    object FadeAll : FadeBirth()

    object SeedAll : FadeBirth()

    data class SeedPrefix(val shown: String) : FadeBirth()
}

internal fun fadeBirthPlan(fadeFromBirth: Boolean, shown: List<String>, text: String): FadeBirth {
    if (!fadeFromBirth) return FadeBirth.SeedAll
    val prior = shown.filter { it.isNotEmpty() && text.startsWith(it) }.maxByOrNull { it.length }
        ?: return FadeBirth.FadeAll
    return if (prior.length == text.length) FadeBirth.SeedAll else FadeBirth.SeedPrefix(prior)
}

internal object FadeShownText {
    private const val MAX_FRAGMENTS = 64
    private const val TEXTS_PER_FRAGMENT = 6

    private val shown = object : LinkedHashMap<String, ArrayDeque<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<String>>): Boolean =
            size > MAX_FRAGMENTS
    }

    fun get(key: String): List<String> = synchronized(shown) { shown[key]?.toList() ?: emptyList() }

    fun put(key: String, text: String) {
        if (text.isEmpty()) return
        synchronized(shown) {
            val q = shown.getOrPut(key) { ArrayDeque() }
            val head = q.firstOrNull()
            if (head != null && (text.startsWith(head) || head.startsWith(text))) {
                if (text.length >= head.length) q[0] = text
            } else {
                q.addFirst(text)
                while (q.size > TEXTS_PER_FRAGMENT) q.removeLast()
            }
        }
    }

    internal fun clearForTests() = synchronized(shown) { shown.clear() }
}

internal val LocalLiveIncremental = compositionLocalOf { false }

internal const val FADE_DURATION_MS = 450L
internal const val STAGGER_WINDOW_MS = 100L
private const val MAX_FADE_WORDS = 160

internal fun perWordStaggerMs(wordCount: Int): Long =
    if (wordCount <= 0) 0L else STAGGER_WINDOW_MS / wordCount

private data class FadeRange(
    val start: Int,
    val end: Int,
    val staggerMs: Long,
)

internal class FadeController {
    var lastPlainText: String = ""
        private set

    var hasSeenText: Boolean = false
        private set

    private val rangesState: SnapshotStateList<FadeRange> = mutableListOf<FadeRange>().toMutableStateList()

    private val rangeStartNanos = ArrayDeque<Long>()

    val alphas: SnapshotStateMap<Int, Float> = SnapshotStateMap()

    val hasActiveRanges: Boolean get() = rangesState.isNotEmpty()

    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun seed(plainText: String) {
        hasSeenText = true
        rangesState.clear()
        rangeStartNanos.clear()
        alphas.clear()
        lastPlainText = plainText
    }

    fun ingest(newPlainText: String) {
        hasSeenText = true
        if (newPlainText == lastPlainText) return
        if (!newPlainText.startsWith(lastPlainText)) {
            rangesState.clear()
            rangeStartNanos.clear()
            alphas.clear()
            lastPlainText = newPlainText
            return
        }
        val base = lastPlainText.length
        val suffix = newPlainText.substring(base)
        lastPlainText = newPlainText
        if (suffix.isEmpty()) return

        val words = mutableListOf<IntRange>()
        var cursor = 0
        var inWord = false
        var wordStart = 0
        for (i in suffix.indices) {
            val c = suffix[i]
            val isWs = c.isWhitespace()
            if (!isWs && !inWord) {
                wordStart = i; inWord = true
            } else if (isWs && inWord) {
                words.add(wordStart until i); inWord = false
            }
        }
        if (inWord) words.add(wordStart until suffix.length)

        if (words.isEmpty()) return

        val totalWords = words.size + rangesState.size
        if (totalWords > MAX_FADE_WORDS) {
            rangesState.clear()
            rangeStartNanos.clear()
            alphas.clear()
            return
        }
        val perWordStaggerMs = perWordStaggerMs(words.size)

        for ((idx, wr) in words.withIndex()) {
            val absStart = base + wr.first
            val absEnd = base + wr.last + 1
            val staggerMs = idx * perWordStaggerMs
            rangesState.add(FadeRange(absStart, absEnd, staggerMs))
            rangeStartNanos.addLast(System.nanoTime())
        }
        wake.trySend(Unit)
    }

    suspend fun runTicks(awaitFrame: suspend (onFrame: (Long) -> Boolean) -> Boolean): Nothing {
        while (true) {
            while (awaitFrame(::tick)) Unit
            wake.receive()
        }
    }

    fun tick(nowNanos: Long): Boolean {
        if (rangesState.isEmpty()) return false
        val finished = mutableListOf<Int>()
        for (i in rangesState.indices) {
            val r = rangesState[i]
            val startNs = rangeStartNanos.elementAt(i)
            val elapsedMs = (nowNanos - startNs) / 1_000_000L - r.staggerMs
            val alpha = if (elapsedMs <= 0) 0f
            else if (elapsedMs >= FADE_DURATION_MS) 1f
            else {
                val t = elapsedMs.toFloat() / FADE_DURATION_MS
                val inv = 1f - t
                1f - inv * inv * inv
            }
            alphas[r.start] = alpha
            if (alpha >= 1f) finished.add(i)
        }
        for (i in finished.asReversed()) {
            val r = rangesState.removeAt(i)
            rangeStartNanos.removeAt(i)
            alphas.remove(r.start)
        }
        return rangesState.isNotEmpty()
    }

    fun forEachActive(block: (start: Int, end: Int, alpha: Float) -> Unit) {
        for (r in rangesState) block(r.start, r.end, alphas[r.start] ?: 0f)
    }
}

@Composable
internal fun rememberFadeController(): FadeController =
    remember { FadeController() }

@Composable
internal fun FadeFrameDriver(controller: FadeController) {
    LaunchedEffect(controller) {
        controller.runTicks { onFrame -> withFrameNanos(onFrame) }
    }
}

internal data class FadeColorHolder(var color: Color = Color.Unspecified) {
    val state = mutableStateOf(color)
}
