package com.openminis.app.accessibility

/**
 * [screen-control / incremental-tree] Token-cheap incremental diff of a screen
 * snapshot. `ui dump` currently re-serialises the whole accessibility tree
 * every call; most of it is unchanged between two frames. This computes a
 * stable per-node signature and reports only what actually changed, so the
 * agent can be fed a delta instead of a full tree — faster and far fewer
 * tokens, without losing information (a full snapshot is always reconstructible).
 *
 * Pure JVM logic, no Android dependency: works with any list of node descriptors.
 */
object ScreenTreeDiff {

    /** A minimal, comparable view of one UI node. */
    data class Node(
        val id: String,            // stable-ish handle (resource-id or generated index)
        val text: String = "",
        val contentDesc: String = "",
        val className: String = "",
        val bounds: String = "",   // e.g. "0,0-100,50"
        val flags: Int = 0,        // bitmask: clickable/scrollable/checked/enabled/…
    ) {
        /** Signature of everything except the handle: a change here means the node changed. */
        val signature: String get() = "$text\u0000$contentDesc\u0000$className\u0000$bounds\u0000$flags"
    }

    data class Delta(
        val added: List<Node>,
        val removed: List<String>,          // ids no longer present
        val changed: List<Pair<Node, String>>, // node + its previous signature
        val unchangedCount: Int,
    ) {
        val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
    }

    /**
     * Diff [current] against [previous]. Identity is [Node.id]; a node with a
     * changed [Node.signature] at the same id is reported in [Delta.changed].
     * Duplicate ids are disambiguated by insertion order (occurrence index) so
     * repeated resource-ids (common in lists) don't collapse incorrectly.
     */
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
        val removed = prevMap.keys.filter { it !in currMap }.map { it.first }
        return Delta(added, removed, changed, unchanged)
    }

    /** key = (id, occurrence index) to survive duplicate resource-ids. */
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

    /**
     * Render a compact, human/agent-readable delta. Respects [maxChars]; when
     * the delta is empty it says so, which lets the caller skip re-injection.
     */
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
