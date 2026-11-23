package com.openminis.app.ui.chat.search

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.FlatChatItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Stable
internal data class ChatSearchHighlight(
    val query: String,
    val countByUnit: Map<ChatSearchUnitKey, Int>,
    val current: ChatSearchMatch?,
) {
    fun count(key: ChatSearchUnitKey): Int = countByUnit[key] ?: 0

    fun currentOrdinal(key: ChatSearchUnitKey): Int = if (current?.key == key) current.ordinal else -1

    fun rowIsCurrent(rowKey: String): Boolean = current?.key?.rowKey == rowKey

    companion object {
        val NONE = ChatSearchHighlight("", emptyMap(), null)
    }
}

@Stable
internal class ChatSearchController(
    private val scope: CoroutineScope,
    private val source: ChatSearchUnits.TextSource,
    private val background: CoroutineDispatcher = Dispatchers.Default,
) {
    enum class Notice { WRAPPED_TO_FIRST, WRAPPED_TO_LAST }

    var isActive by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var matchCount by mutableIntStateOf(0)
        private set
    var isTruncated by mutableStateOf(false)
        private set
    var currentIndex by mutableStateOf<Int?>(null)
        private set
    var isSearching by mutableStateOf(false)
        private set
    var notice by mutableStateOf<Notice?>(null)
        private set
    var focusRequest by mutableIntStateOf(0)
        private set
    var highlight by mutableStateOf(ChatSearchHighlight.NONE)
        private set

    private var matches: List<ChatSearchMatch> = emptyList()

    private val _navigations = MutableSharedFlow<ChatSearchMatch>(extraBufferCapacity = 4)
    val navigations: SharedFlow<ChatSearchMatch> = _navigations

    val currentMatch: ChatSearchMatch? get() = currentIndex?.let { matches.getOrNull(it) }
    val counterText: String get() = ChatSearchNavigation.counterText(currentIndex, matchCount, isTruncated)

    fun rowFor(key: String): FlatChatItem? = rows.firstOrNull { it.key == key }

    private var rows: List<FlatChatItem> = emptyList()
    private var searchedSignature: Int? = null
    private var pieceCache: Map<String, ChatSearchUnits.PieceCacheEntry> = emptyMap()

    private var pending: Job? = null
    private var searchJob: Job? = null
    private var refreshJob: Job? = null
    private var noticeJob: Job? = null
    private var generation = 0


    fun activate(prefill: String? = null) {
        val trimmed = prefill?.let { ChatSearchMatcher.normalizedQuery(it) }.orEmpty()
        if (!isActive) {
            isActive = true
            AppLogger.info(TAG, "[ChatSearch] open prefill=${trimmed.length} chars")
        }
        focusRequest++
        if (trimmed.isNotEmpty() && trimmed != query) {
            query = trimmed
            schedule(0, landOnNewest = true)
        }
    }

    fun deactivate() {
        if (!isActive) return
        AppLogger.info(TAG, "[ChatSearch] close query=${query.length} chars results=${matches.size}")
        isActive = false
        cancelAll()
        query = ""
        apply(ChatSearchResult.EMPTY, "", landOnNewest = false)
        notice = null
    }

    fun updateQuery(value: String) {
        if (value == query) return
        query = value
        if (isActive) schedule(TYPING_DEBOUNCE_MS, landOnNewest = true)
    }


    fun next() = step(forward = true)

    fun previous() = step(forward = false)

    private fun step(forward: Boolean) {
        val moved = ChatSearchNavigation.step(currentIndex, matches.size, forward) ?: return
        currentIndex = moved.index
        highlight = highlight.copy(current = currentMatch)
        if (moved.wrapped) showNotice(if (forward) Notice.WRAPPED_TO_FIRST else Notice.WRAPPED_TO_LAST)
        navigateToCurrent()
    }


    fun onRows(newRows: List<FlatChatItem>) {
        rows = newRows
        if (!isActive || ChatSearchMatcher.normalizedQuery(query).isEmpty()) return
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            delay(STREAMING_THROTTLE_MS)
            searchJob?.takeIf { it.isActive }?.join()
            if (!this@ChatSearchController.isActive || pending?.isActive == true || isSearching) return@launch
            if (signature(rows) == searchedSignature) return@launch
            runSearch(landOnNewest = false)
        }
    }

    private fun signature(rows: List<FlatChatItem>): Int {
        var h = rows.size
        for (r in rows) {
            h = h * 31 + r.key.hashCode()
            h = h * 31 + when (r) {
                is FlatChatItem.AssistantMarkdownBlock -> r.rawText.length
                is FlatChatItem.AssistantText -> r.block.content.length
                is FlatChatItem.AssistantLegacyContent -> r.content.length
                is FlatChatItem.UserBubble -> r.message.content.length + r.message.attachmentNames.size
                is FlatChatItem.AssistantToolUse -> r.block.toolTitle.length + r.block.content.length
                is FlatChatItem.AssistantInfo -> r.block.content.length
                is FlatChatItem.AssistantError -> r.error.length
                else -> 0
            }
        }
        return h
    }


    private fun cancelAll() {
        pending?.cancel(); pending = null
        searchJob?.cancel(); searchJob = null
        refreshJob?.cancel(); refreshJob = null
        generation++
        isSearching = false
    }

    private fun schedule(delayMs: Long, landOnNewest: Boolean) {
        pending?.cancel()
        if (landOnNewest) {
            searchJob?.cancel()
            generation++
            isSearching = ChatSearchMatcher.normalizedQuery(query).isNotEmpty()
        }
        pending = scope.launch {
            if (delayMs > 0) delay(delayMs)
            runSearch(landOnNewest)
        }
    }

    private fun runSearch(landOnNewest: Boolean) {
        pending = null
        val q = ChatSearchMatcher.normalizedQuery(query)
        if (!isActive || q.isEmpty()) {
            searchJob?.cancel()
            isSearching = false
            apply(ChatSearchResult.EMPTY, q, landOnNewest)
            return
        }
        searchJob?.cancel()
        val gen = ++generation
        val snapshot = rows
        val sig = signature(snapshot)
        val cache = pieceCache
        val started = System.nanoTime()
        searchJob = scope.launch {
            val outcome = withContext(background) {
                val built = ChatSearchUnits.build(snapshot, source, cache) { !isActive } ?: return@withContext null
                val result = ChatSearchEngine.search(built.first, q) { !isActive } ?: return@withContext null
                Triple(built.first.size, built.second, result)
            } ?: return@launch
            if (gen != generation || !this@ChatSearchController.isActive) return@launch
            pieceCache = outcome.second
            searchedSignature = sig
            searchJob = null
            AppLogger.info(
                TAG,
                "[ChatSearch] searched units=${outcome.first} matches=${outcome.third.matches.size}" +
                    "${if (outcome.third.truncated) "+" else ""} ms=${(System.nanoTime() - started) / 1_000_000} " +
                    "landOnNewest=$landOnNewest",
            )
            apply(outcome.third, q, landOnNewest)
        }
    }

    private fun apply(result: ChatSearchResult, q: String, landOnNewest: Boolean) {
        val old = currentMatch
        val oldIndex = currentIndex
        matches = result.matches
        matchCount = result.matches.size
        isTruncated = result.truncated
        isSearching = false
        currentIndex = if (landOnNewest) ChatSearchNavigation.initialIndex(matches.size)
            else ChatSearchNavigation.relocate(old, oldIndex, matches)
        highlight = if (result.matches.isEmpty()) ChatSearchHighlight.NONE
            else ChatSearchHighlight(q, result.countByUnit, currentMatch)
        if (landOnNewest) navigateToCurrent()
    }

    private fun navigateToCurrent() {
        currentMatch?.let { _navigations.tryEmit(it) }
    }


    private fun showNotice(n: Notice) {
        notice = n
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(NOTICE_MS)
            notice = null
        }
    }

    companion object {
        private const val TAG = "ChatSearch"
        const val TYPING_DEBOUNCE_MS = 250L
        const val STREAMING_THROTTLE_MS = 300L
        const val NOTICE_MS = 1_600L
    }
}
