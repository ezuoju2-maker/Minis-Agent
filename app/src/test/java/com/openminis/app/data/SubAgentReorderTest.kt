package com.openminis.app.data

import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentRoster
import org.junit.Assert.assertEquals
import org.junit.Test

class SubAgentReorderTest {

    private fun custom(name: String, order: Int) = SubAgentDefinition(
        id = "id-$name", name = name, description = "d", sortOrder = order,
    )

    private fun roster() = SubAgentRoster.normalize(
        listOf(
            SubAgentDefinition.makeBuiltIn(),
            custom("alpha", 1),
            custom("beta", 2),
            custom("gamma", 3),
        )
    )

    private fun applyMove(list: List<SubAgentDefinition>, from: Int, to: Int): List<String> {
        if (from !in list.indices || to !in list.indices) return list.map { it.name }
        if (from == 0 || to == 0) return list.map { it.name }
        val ids = list.map { it.id }.toMutableList()
        ids.add(to, ids.removeAt(from))
        val byId = list.associateBy { it.id }
        val reordered = ids.mapNotNull { byId[it] }
            .mapIndexed { i, d -> d.copy(sortOrder = i) }
        return SubAgentRoster.normalize(reordered).map { it.name }
    }

    @Test
    fun `moving a custom agent up reorders it`() {
        assertEquals(
            listOf(SubAgentDefinition.BUILT_IN_NAME, "beta", "alpha", "gamma"),
            applyMove(roster(), from = 2, to = 1),
        )
    }

    @Test
    fun `moving a custom agent down reorders it`() {
        assertEquals(
            listOf(SubAgentDefinition.BUILT_IN_NAME, "beta", "alpha", "gamma"),
            applyMove(roster(), from = 1, to = 2),
        )
    }

    @Test
    fun `the built-in cannot be moved off the front`() {
        assertEquals(
            listOf(SubAgentDefinition.BUILT_IN_NAME, "alpha", "beta", "gamma"),
            applyMove(roster(), from = 0, to = 2),
        )
    }

    @Test
    fun `nothing can displace the built-in from index 0`() {
        assertEquals(
            listOf(SubAgentDefinition.BUILT_IN_NAME, "alpha", "beta", "gamma"),
            applyMove(roster(), from = 3, to = 0),
        )
    }

    @Test
    fun `sortOrder stays dense after a move`() {
        val ids = roster().map { it.id }.toMutableList()
        ids.add(1, ids.removeAt(3))
        val byId = roster().associateBy { it.id }
        val out = SubAgentRoster.normalize(
            ids.mapNotNull { byId[it] }.mapIndexed { i, d -> d.copy(sortOrder = i) }
        )
        assertEquals(listOf(0, 1, 2, 3), out.map { it.sortOrder })
    }
}
