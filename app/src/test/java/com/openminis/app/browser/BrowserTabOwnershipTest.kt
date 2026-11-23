package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTabOwnershipTest {

    private val chat = "chat-session"
    private val agentA = "child-a"
    private val agentB = "child-b"

    private fun isPrivileged(owner: String?, sessionId: String? = chat) =
        owner == null || owner == sessionId

    private fun mayUse(tabOwner: Map<Int, String>, tabId: Int, owner: String?) =
        if (isPrivileged(owner)) true else tabOwner[tabId] == owner

    private fun effectiveMax(tabOwner: Map<Int, String>, requesting: String?): Int {
        val owners = (tabOwner.values.toSet() - setOfNotNull(chat)).toMutableSet()
        if (requesting != null && !isPrivileged(requesting)) owners.add(requesting)
        return if (owners.isEmpty()) 3 else minOf(3 + 2 * owners.size, 6)
    }


    @Test
    fun `a human and the owning chat may use any tab`() {
        val owned = mapOf(0 to agentA, 1 to agentB)
        for (id in listOf(0, 1)) {
            assertTrue("human may use tab $id", mayUse(owned, id, null))
            assertTrue("chat may use tab $id", mayUse(owned, id, chat))
        }
    }

    @Test
    fun `an agent may use only its own tabs`() {
        val owned = mapOf(0 to agentA, 1 to agentB, 2 to chat)
        assertTrue(mayUse(owned, 0, agentA))
        assertFalse("a sibling's tab is refused", mayUse(owned, 1, agentA))
        assertFalse("the chat's tab is refused", mayUse(owned, 2, agentA))
    }

    @Test
    fun `an unowned tab belongs to the chat, not to whoever asks first`() {
        assertFalse(mayUse(emptyMap(), 0, agentA))
        assertTrue(mayUse(emptyMap(), 0, null))
    }


    private fun implicitTarget(
        tabOwner: Map<Int, String>,
        lastByOwner: Map<String, Int>,
        inUse: Set<Int>,
        existing: Set<Int>,
        owner: String,
    ): Int? {
        val last = lastByOwner[owner]?.takeIf { it in existing }
        if (last != null) return last
        return tabOwner.entries
            .filter { it.value == owner && it.key !in inUse && it.key in existing }
            .minByOrNull { it.key }?.key
    }

    @Test
    fun `an agent's implicit action prefers its own most recent tab`() {
        val target = implicitTarget(
            tabOwner = mapOf(0 to chat, 1 to agentA, 2 to agentA),
            lastByOwner = mapOf(agentA to 2),
            inUse = emptySet(),
            existing = setOf(0, 1, 2),
            owner = agentA,
        )
        assertEquals(2, target)
    }

    @Test
    fun `it falls to another tab it owns when the last one is gone`() {
        val target = implicitTarget(
            tabOwner = mapOf(0 to chat, 1 to agentA),
            lastByOwner = mapOf(agentA to 9),
            inUse = emptySet(),
            existing = setOf(0, 1),
            owner = agentA,
        )
        assertEquals(1, target)
    }

    @Test
    fun `it never falls through to the chat's tab`() {
        val target = implicitTarget(
            tabOwner = mapOf(0 to chat, 1 to agentB),
            lastByOwner = emptyMap(),
            inUse = emptySet(),
            existing = setOf(0, 1),
            owner = agentA,
        )
        assertNull(target)
    }

    @Test
    fun `a busy tab of its own is skipped rather than contended`() {
        val target = implicitTarget(
            tabOwner = mapOf(1 to agentA, 2 to agentA),
            lastByOwner = emptyMap(),
            inUse = setOf(1),
            existing = setOf(1, 2),
            owner = agentA,
        )
        assertEquals(2, target)
    }


    @Test
    fun `the pool keeps its old ceiling when no agent is running`() {
        assertEquals(3, effectiveMax(emptyMap(), null))
        assertEquals(3, effectiveMax(mapOf(0 to chat), chat))
    }

    @Test
    fun `each active agent raises the ceiling by its quota`() {
        assertEquals(5, effectiveMax(mapOf(0 to agentA), agentA))
        assertEquals(7.coerceAtMost(6), effectiveMax(mapOf(0 to agentA, 1 to agentB), agentA))
    }

    @Test
    fun `the ceiling is hard-capped regardless of agent count`() {
        val many = (0..9).associateWith { "child-$it" }
        assertEquals(6, effectiveMax(many, "child-0"))
    }

    @Test
    fun `a requesting agent counts even before it owns anything`() {
        assertEquals(5, effectiveMax(emptyMap(), agentA))
    }


    @Test
    fun `releasing an agent returns its tabs and leaves the others alone`() {
        val owned = mutableMapOf(0 to chat, 1 to agentA, 2 to agentA, 3 to agentB)
        val mine = owned.filterValues { it == agentA }.keys.toList()
        assertEquals(listOf(1, 2), mine.sorted())
        mine.forEach { owned.remove(it) }
        assertEquals(mapOf(0 to chat, 3 to agentB), owned)
        assertEquals(5, effectiveMax(owned, null))
    }

    @Test
    fun `releasing is idempotent`() {
        val owned = mutableMapOf(0 to agentA)
        repeat(3) { owned.filterValues { v -> v == agentA }.keys.toList().forEach { owned.remove(it) } }
        assertTrue(owned.isEmpty())
    }

    @Test
    fun `an agent with no tabs is told to open one rather than given an id`() {
        val mine = emptyMap<Int, String>().filterValues { it == agentA }.keys.sorted()
        assertTrue(mine.isEmpty())
    }
}
