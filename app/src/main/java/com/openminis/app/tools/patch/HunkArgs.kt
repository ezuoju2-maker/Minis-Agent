package com.openminis.app.tools.patch

object HunkArgs {

    fun toggle(current: Set<Int>, id: Int): Set<Int> =
        if (id in current) current - id else current + id

    fun selectAll(ids: List<Int>): Set<Int> = ids.toSet()

    fun clear(): Set<Int> = emptySet()

    fun toJson(selected: Set<Int>): String =
        selected.sorted().joinToString(prefix = "[", postfix = "]", separator = ",")

    fun describe(selected: Set<Int>, total: Int): String = when {
        total <= 0 -> "没有可选的改动"
        selected.isEmpty() -> "未选择任何改动（$total 段）"
        selected.size == total -> "全选（$total 段）"
        else -> "已选 ${selected.size}/$total 段"
    }

    fun isNoop(selected: Set<Int>): Boolean = selected.isEmpty()
}
