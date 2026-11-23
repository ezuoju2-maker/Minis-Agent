package com.openminis.app.ui.sessions

internal object FolderPages {
    const val PAGE_SIZE = 20

    fun page(total: Int, pages: Int, pageSize: Int = PAGE_SIZE): Pair<Int, Int> {
        val all = maxOf(0, total)
        val shown = minOf(all.toLong(), maxOf(1, pages).toLong() * maxOf(1, pageSize)).toInt()
        return shown to (all - shown)
    }

    fun revealing(index: Int?, stored: Int, pageSize: Int = PAGE_SIZE): Int {
        val floor = maxOf(1, stored)
        if (index == null || index < 0) return floor
        return maxOf(floor, index / maxOf(1, pageSize) + 1)
    }

    fun nextPageCount(hidden: Int, pageSize: Int = PAGE_SIZE): Int = minOf(maxOf(0, hidden), maxOf(1, pageSize))


    fun afterOpenChanged(pages: Map<String, Int>, open: String?): Map<String, Int> =
        pages.filterKeys { it == open }

    fun afterShowMore(pages: Map<String, Int>, open: String?, folderId: String, pagesShown: Int): Map<String, Int> =
        if (folderId != open) pages else pages + (folderId to maxOf(1, pagesShown) + 1)

    fun afterReveal(pages: Map<String, Int>, open: String?, folderId: String, revealed: Int): Map<String, Int> =
        if (folderId != open || (pages[folderId] ?: 1) >= revealed) pages else pages + (folderId to revealed)


    fun afterSearchShowMore(pages: Map<String, Int>, folderId: String, pagesShown: Int): Map<String, Int> =
        pages + (folderId to maxOf(1, pagesShown) + 1)
}
