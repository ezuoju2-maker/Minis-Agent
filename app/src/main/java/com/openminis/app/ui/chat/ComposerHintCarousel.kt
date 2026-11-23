package com.openminis.app.ui.chat

internal enum class UsageHintPage { CONTEXT, QUOTA }

internal fun ContextUsageHint.pages(): List<UsageHintPage> =
    if (quotaText.isNullOrEmpty()) listOf(UsageHintPage.CONTEXT)
    else listOf(UsageHintPage.CONTEXT, UsageHintPage.QUOTA)

internal class ComposerHintCarousel {
    var generation: Int = -1
        private set
    var page: Int = 0
        private set
    var turns: Int = 0
        private set
    var finished: Boolean = false
        private set

    fun adopt(generation: Int) {
        if (generation == this.generation) return
        this.generation = generation
        page = 0
        turns = 0
        finished = false
    }

    fun canTurn(pageCount: Int, lineShowing: Boolean, composerEmpty: Boolean, screenReaderOn: Boolean): Boolean =
        pageCount > 1 && !finished && lineShowing && composerEmpty && !screenReaderOn

    fun turn(pageCount: Int): Int {
        if (pageCount <= 1 || finished) return page
        page = (page + 1) % pageCount
        turns += 1
        if (turns >= ROUNDS * pageCount) {
            finished = true
            page = 0
        }
        return page
    }

    companion object {
        const val INTERVAL_MS = 5_000L
        const val ROUNDS = 3
    }
}

internal object ComposerPlaceholderPool {
    fun size(latest: ContextUsageHint?): Int =
        ComposerPlaceholderRotation.POOL_SIZE + (latest?.pages()?.size ?: 0)

    fun usagePage(index: Int, latest: ContextUsageHint?): UsageHintPage? {
        if (index < ComposerPlaceholderRotation.POOL_SIZE) return null
        return latest?.pages()?.getOrNull(index - ComposerPlaceholderRotation.POOL_SIZE)
    }

    fun textIndex(index: Int): Int =
        if (index in 0 until ComposerPlaceholderRotation.POOL_SIZE) index else ComposerPlaceholderRotation.DEFAULT_INDEX
}
