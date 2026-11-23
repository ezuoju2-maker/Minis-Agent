package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderReorderTest {

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
            listOf("c", "a", "b"),
            reorder(current = listOf("a", "b", "c"), newOrder = listOf("c", "a", "b")),
        )
    }

    @Test
    fun `reordering one section leaves other sections in place`() {
        val current = listOf("o1", "o2", "a1", "a2")
        val result = reorder(current, newOrder = listOf("o2", "o1"))
        assertEquals(listOf("o2", "o1", "a1", "a2"), result)
    }

    @Test
    fun `an unmentioned trailing section keeps its internal order`() {
        val current = listOf("o1", "o2", "a1", "a2", "g1")
        val result = reorder(current, newOrder = listOf("o2", "o1"))
        assertEquals(listOf("o2", "o1", "a1", "a2", "g1"), result)
    }

    @Test
    fun `unknown ids are dropped rather than inserted`() {
        val result = reorder(current = listOf("a", "b"), newOrder = listOf("b", "ghost", "a"))
        assertEquals(listOf("b", "a"), result)
    }

    @Test
    fun `duplicate ids in the incoming order are collapsed`() {
        val result = reorder(current = listOf("a", "b", "c"), newOrder = listOf("b", "b", "a"))
        assertEquals(listOf("b", "a", "c"), result)
    }

    @Test
    fun `an empty new order leaves everything untouched`() {
        val current = listOf("a", "b", "c")
        assertEquals(current, reorder(current, newOrder = emptyList()))
    }

    @Test
    fun `no instance is ever lost or duplicated`() {
        val current = listOf("a", "b", "c", "d")
        val result = reorder(current, newOrder = listOf("d", "ghost", "b"))
        assertEquals(
            "the result must be a permutation of the input",
            current.sorted(), result.sorted(),
        )
        assertEquals(current.size, result.size)
    }
}
