package com.openminis.app.data.session

object SessionTree {

    suspend fun descendantsDeepestFirst(
        rootId: String,
        childrenOf: suspend (String) -> List<String>,
    ): List<String> {
        val seen = mutableSetOf(rootId)
        val order = mutableListOf<String>()
        suspend fun walk(id: String) {
            for (child in childrenOf(id)) {
                if (!seen.add(child)) continue
                walk(child)
                order += child
            }
        }
        walk(rootId)
        return order
    }

    suspend fun subtreeDeepestFirst(
        rootId: String,
        childrenOf: suspend (String) -> List<String>,
    ): List<String> = descendantsDeepestFirst(rootId, childrenOf) + rootId

    fun rootOf(id: String, parentOf: Map<String, String?>): String {
        var cur = id
        val seen = mutableSetOf(cur)
        while (true) {
            val p = parentOf[cur] ?: return cur
            if (p !in parentOf || !seen.add(p)) return cur
            cur = p
        }
    }

    fun aggregateToRoots(
        parentOf: Map<String, String?>,
        sizes: Map<String, Long>,
    ): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        for (id in parentOf.keys) {
            val root = rootOf(id, parentOf)
            out[root] = (out[root] ?: 0L) + (sizes[id] ?: 0L)
        }
        return out
    }

    fun membersOf(rootId: String, parentOf: Map<String, String?>): List<String> =
        parentOf.keys.filter { rootOf(it, parentOf) == rootId }
}
