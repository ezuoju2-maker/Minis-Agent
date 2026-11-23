package com.openminis.app.agent.jobs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperModelIdentityTest {

    private fun bg(resolution: HelperModelResolution?) = JSONObject(
        HelperRunner.backgroundStartJson(
            jobId = "j", childSessionId = "c", modelLabel = "Claude Sonnet 5",
            tierUsed = HelperModelTier.PRIMARY, minutes = 10, converted = false,
            resolution = resolution,
        ),
    )

    private fun resolution(
        origin: HelperModelOrigin = HelperModelOrigin.PINNED,
        groupName: String? = "Fast Group",
        unavailable: Boolean = false,
    ) = HelperModelResolution(
        bindingJson = null, seedModelId = "m", modelLabel = "Claude Sonnet 5",
        tierUsed = HelperModelTier.PRIMARY, origin = origin,
        modelGroupUnavailable = unavailable, modelGroupName = groupName,
        resolvedEntryId = "entry-7", resolvedProviderLabel = "Anthropic (53)",
        resolvedProviderType = "anthropic", resolvedModelId = "claude-sonnet-5",
        resolvedModelName = "Claude Sonnet 5",
    )


    @Test
    fun `a background delegation states which strategy chose its model`() {
        val j = bg(resolution())
        assertEquals(HelperModelOrigin.PINNED.wire, j.getString("model_origin"))
        assertEquals("Fast Group", j.getString("model_group_name"))
    }

    @Test
    fun `a background delegation identifies the entry and provider it resolved to`() {
        val j = bg(resolution())
        assertEquals("entry-7", j.getString("model_resolved_entry_id"))
        assertEquals("Anthropic (53)", j.getString("model_resolved_provider"))
        assertEquals("anthropic", j.getString("model_resolved_provider_type"))
        assertEquals("claude-sonnet-5", j.getString("model_resolved_id"))
        assertEquals("Claude Sonnet 5", j.getString("model_resolved_name"))
    }

    @Test
    fun `an unroutable pinned group is reported`() {
        assertTrue(bg(resolution(unavailable = true)).getBoolean("model_group_unavailable"))
        assertFalse(
            "a working pin must not carry the flag at all",
            bg(resolution()).has("model_group_unavailable"),
        )
    }

    @Test
    fun `a payload with no resolution is unchanged`() {
        val j = bg(null)
        for (k in listOf(
            "model_origin", "model_group_name", "model_resolved_entry_id",
            "model_resolved_provider", "model_resolved_provider_type",
            "model_resolved_id", "model_resolved_name", "model_group_unavailable",
        )) {
            assertFalse("$k must be absent", j.has(k))
        }
        assertEquals("Claude Sonnet 5", j.getString("model_used"))
        assertEquals("running", j.getString("status"))
    }

    @Test
    fun `an inherited binding omits the group and entry keys`() {
        val inherited = HelperModelResolution(
            bindingJson = null, seedModelId = "m", modelLabel = "GPT-6 Astra",
            tierUsed = HelperModelTier.PRIMARY, origin = HelperModelOrigin.INHERITED,
        )
        val j = bg(inherited)
        assertEquals(HelperModelOrigin.INHERITED.wire, j.getString("model_origin"))
        assertFalse(j.has("model_group_name"))
        assertFalse(j.has("model_resolved_entry_id"))
    }


    @Test
    fun `the final payload carries the same resolved identity`() {
        val j = JSONObject(
            HelperRunner.resultJson(
                status = "completed", result = "done", modelLabel = "Claude Sonnet 5",
                tierUsed = HelperModelTier.PRIMARY, tierRequested = "same_as_me",
                turns = 3, elapsedMs = 1000, childSessionId = "c", jobId = "j",
                resolvedEntryId = "entry-7", resolvedProviderLabel = "Anthropic (53)",
                resolvedProviderType = "anthropic", resolvedModelId = "claude-sonnet-5",
                resolvedModelName = "Claude Sonnet 5",
            ),
        )
        assertEquals("entry-7", j.getString("model_resolved_entry_id"))
        assertEquals("Anthropic (53)", j.getString("model_resolved_provider"))
        assertEquals("claude-sonnet-5", j.getString("model_resolved_id"))
    }

    @Test
    fun `the final payload without identity is unchanged`() {
        val j = JSONObject(
            HelperRunner.resultJson(
                status = "completed", result = "done", modelLabel = "m",
                tierUsed = HelperModelTier.PRIMARY, tierRequested = "same_as_me",
                turns = 1, elapsedMs = 1, childSessionId = "c", jobId = "j",
            ),
        )
        assertFalse(j.has("model_resolved_entry_id"))
        assertFalse(j.has("model_resolved_provider"))
    }

    @Test
    fun `the wire key names match iOS exactly`() {
        val j = bg(resolution())
        for (k in listOf(
            "model_used", "tier_used", "model_origin", "model_group_name",
            "model_resolved_entry_id", "model_resolved_provider",
            "model_resolved_provider_type", "model_resolved_id", "model_resolved_name",
        )) {
            assertTrue("$k missing — iOS reads this exact name", j.has(k))
        }
    }
}
