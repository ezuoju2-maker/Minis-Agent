package com.openminis.app.ui.settings

import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageAttributionTest {


    @Test
    fun `snapshot that resolves in config is measured`() {
        assertEquals(
            Attribution.MEASURED,
            classifyAttribution(modelId = "grok-4.5", hasSnapshot = true, resolvesInConfig = true),
        )
    }


    @Test
    fun `row without a snapshot is estimated even when the id resolves`() {
        assertEquals(
            Attribution.ESTIMATED,
            classifyAttribution(modelId = "grok-4.5", hasSnapshot = false, resolvesInConfig = true),
        )
    }

    @Test
    fun `row without a snapshot whose id does not resolve is still estimated`() {
        assertEquals(
            Attribution.ESTIMATED,
            classifyAttribution(modelId = "ghost", hasSnapshot = false, resolvesInConfig = false),
        )
    }


    @Test
    fun `null model id is an unknown session`() {
        assertEquals(
            Attribution.UNKNOWN_SESSION,
            classifyAttribution(modelId = null, hasSnapshot = false, resolvesInConfig = false),
        )
    }


    @Test
    fun `snapshot whose provider was deleted is still measured`() {
        assertEquals(
            Attribution.MEASURED_REMOVED,
            classifyAttribution(modelId = "my-private-model", hasSnapshot = true, resolvesInConfig = false),
        )
    }

    @Test
    fun `orphaned row that has a snapshot is still measured`() {
        assertEquals(
            Attribution.MEASURED,
            classifyAttribution(modelId = "grok-4.5", hasSnapshot = true, resolvesInConfig = true),
        )
        assertEquals(
            Attribution.MEASURED_REMOVED,
            classifyAttribution(modelId = "grok-4.5", hasSnapshot = true, resolvesInConfig = false),
        )
    }

    @Test
    fun `the four states are mutually exclusive`() {
        val seen = setOf(
            classifyAttribution("m", hasSnapshot = true, resolvesInConfig = true),
            classifyAttribution("m", hasSnapshot = false, resolvesInConfig = true),
            classifyAttribution(null, hasSnapshot = false, resolvesInConfig = false),
            classifyAttribution("m", hasSnapshot = true, resolvesInConfig = false),
        )
        assertEquals(4, seen.size)
    }


    @Test
    fun `provider rawValue maps to its display name`() {
        assertEquals(ProviderType.openAI.displayName, providerDisplayName("openAI"))
        assertEquals(ProviderType.gemini.displayName, providerDisplayName("gemini"))
        assertEquals(ProviderType.anthropic.displayName, providerDisplayName("anthropic"))
    }

    @Test
    fun `unknown provider rawValue degrades to the raw string`() {
        assertEquals("someFutureProvider", providerDisplayName("someFutureProvider"))
        assertEquals("", providerDisplayName(""))
    }
}
