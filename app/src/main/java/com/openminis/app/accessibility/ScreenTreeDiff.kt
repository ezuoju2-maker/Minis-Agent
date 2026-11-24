package com.openminis.app.accessibility

object ScreenTreeDiff {

    data class Node(
        val id: String,
        val text: String = "",
        val contentDesc: String = "",
        val className: String = "",
        val bounds: String = "",
        val flags: Int = 0,
    ) {
        val signature: String get() = "$text\u0000$contentDesc\u0000$className\u0000$bounds\u0000$flags"
    }

    data class Delta(
        val added: List<Node>,
        val removed: List<String>,
        val changed: List<Pair<Node, String>>,
        val unchangedCount: Int,
    ) {
        val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
    }

    fun diff(previous: List<Node>, current: List<Node>): Delta {
        val prevMap = keyed(previous)
        val currMap = keyed(current)

        val added = ArrayList<Node>()
        val changed = ArrayList<Pair<Node, String>>()
        var unchanged = 0
        for ((key, node) in currMap) {
            val old = prevMap[key]
            when {
                old == null -> added.add(node)
                old.signature == node.signature -> unchanged++
                else -> changed.add(node to old.signature)
            }
        }
        val removed = prevMap.keys.filter { it !in currMap }.map { it.first }.distinct()
        return Delta(added, removed, changed, unchanged)
    }

    private fun keyed(nodes: List<Node>): LinkedHashMap<Pair<String, Int>, Node> {
        val counts = HashMap<String, Int>()
        val out = LinkedHashMap<Pair<String, Int>, Node>()
        for (n in nodes) {
            val occ = counts[n.id] ?: 0
            counts[n.id] = occ + 1
            out[n.id to occ] = n
        }
        return out
    }

    fun render(delta: Delta, maxChars: Int = 3_000): String {
        if (delta.isEmpty) return "UI unchanged (${delta.unchangedCount} nodes)."
        val sb = StringBuilder()
        for (n in delta.added) if (!appendLine(sb, "+ ${describe(n)}", maxChars)) return finish(sb, maxChars)
        for ((n, old) in delta.changed) if (!appendLine(sb, "~ ${describe(n)} (was: ${old.take(60)})", maxChars)) return finish(sb, maxChars)
        if (delta.removed.isNotEmpty()) {
            appendLine(sb, "- removed: ${delta.removed.take(20).joinToString(", ")}", maxChars)
        }
        return finish(sb, maxChars)
    }

    private fun describe(n: Node): String {
        val label = n.text.ifEmpty { n.contentDesc }.ifEmpty { n.className }
        return "#${n.id} ${label.take(80)} [${n.bounds}]".trim()
    }

    private fun appendLine(sb: StringBuilder, line: String, maxChars: Int): Boolean {
        if (sb.length + line.length + 1 > maxChars) return false
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(line)
        return true
    }

    private fun finish(sb: StringBuilder, maxChars: Int): String =
        sb.toString().take(maxChars).trimEnd()
}
