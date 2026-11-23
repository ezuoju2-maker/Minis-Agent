package com.openminis.app.agent.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SubAgentModelStrategyTest {

    @Test
    fun `every origin has a stable wire value the card branches on`() {
        assertEquals("pinned", HelperModelOrigin.PINNED.wire)
        assertEquals("inherited", HelperModelOrigin.INHERITED.wire)
        assertEquals("default_group", HelperModelOrigin.DEFAULT_GROUP.wire)
        assertEquals("sub_group", HelperModelOrigin.SUB_GROUP.wire)
        assertEquals(
            "a new origin needs a matching branch in helperModelStrategyLabel",
            4, HelperModelOrigin.entries.size,
        )
    }

    @Test
    fun `a group-backed resolution carries the group name for the card`() {
        val r = HelperModelResolution(
            bindingJson = """{"type":"group","groupId":"g1"}""",
            seedModelId = "m1",
            modelLabel = "Model One",
            tierUsed = HelperModelTier.PRIMARY,
            origin = HelperModelOrigin.PINNED,
            modelGroupName = "Fast Group",
        )
        assertEquals("Fast Group", r.modelGroupName)
        assertNotNull(r.bindingJson)
    }

    @Test
    fun `inherited needs no group name`() {
        val r = HelperModelResolution(
            bindingJson = null, seedModelId = "m1", modelLabel = "Model One",
            tierUsed = HelperModelTier.PRIMARY, origin = HelperModelOrigin.INHERITED,
        )
        assertEquals(null, r.modelGroupName)
    }
}
