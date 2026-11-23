package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelGroupReorderTest {

    private fun reorder(current: List<String>, newOrder: List<String>): List<String> {
        val known = current.toSet()
        val seen = LinkedHashSet<String>()
        val out = ArrayList<String>(current.size)
        for (id in newOrder) {
            if (id !in known) continue
            if (!seen.add(id)) continue
            out.add(id)
        }
        for (id in current) {
            if (seen.add(id)) out.add(id)
        }
        return out
    }

    @Test
    fun `a full permutation is applied verbatim`() {
        assertEquals(
            listOf("coding", "daily", "translate"),
            reorder(
                current = listOf("daily", "coding", "translate"),
                newOrder = listOf("coding", "daily", "translate"),
            ),
        )
    }

    @Test
    fun `unknown ids are dropped rather than inserted`() {
        val result = reorder(current = listOf("a", "b"), newOrder = listOf("b", "ghost", "a"))
        assertEquals(listOf("b", "a"), result)
    }

    @Test
    fun `a group added concurrently is appended, not lost`() {
        val result = reorder(current = listOf("a", "b", "new"), newOrder = listOf("b", "a"))
        assertEquals(listOf("b", "a", "new"), result)
    }

    @Test
    fun `duplicate ids in the incoming order are collapsed`() {
        assertEquals(
            listOf("b", "a", "c"),
            reorder(current = listOf("a", "b", "c"), newOrder = listOf("b", "b", "a")),
        )
    }

    @Test
    fun `no group is ever lost or duplicated`() {
        val current = listOf("a", "b", "c", "d")
        val result = reorder(current, newOrder = listOf("d", "ghost", "b"))
        assertEquals(current.sorted(), result.sorted())
        assertEquals(current.size, result.size)
    }
}
