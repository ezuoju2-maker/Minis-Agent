package com.openminis.app.provider

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyKeyRefreshTest {

    private fun instance(
        type: ProviderType,
        credential: ProviderCredential,
        baseURL: String?,
    ) = ProviderInstance(
        id = "test",
        label = "Test",
        providerType = type,
        credentialType = credential,
        customBaseURL = baseURL,
    )


    @Test
    fun `self-hosted OpenAI-compatible endpoint may have an empty key`() {
        assertTrue(
            instance(ProviderType.openAI, ProviderCredential.apiKey, "http://192.168.1.10:11434/v1")
                .allowsEmptyAPIKey,
        )
    }

    @Test
    fun `self-hosted Anthropic-compatible endpoint may have an empty key`() {
        assertTrue(
            instance(ProviderType.anthropic, ProviderCredential.apiKey, "http://10.0.0.5:8080")
                .allowsEmptyAPIKey,
        )
    }


    @Test
    fun `official endpoint with no custom base URL still requires a key`() {
        assertFalse(
            instance(ProviderType.openAI, ProviderCredential.apiKey, null).allowsEmptyAPIKey,
        )
        assertFalse(
            instance(ProviderType.anthropic, ProviderCredential.apiKey, null).allowsEmptyAPIKey,
        )
    }

    @Test
    fun `blank custom base URL counts as no custom base URL`() {
        assertFalse(
            instance(ProviderType.openAI, ProviderCredential.apiKey, "   ").allowsEmptyAPIKey,
        )
    }

    @Test
    fun `OAuth instances are never empty-key valid`() {
        assertFalse(
            instance(ProviderType.openAI, ProviderCredential.oauth, "https://relay.example.com/v1")
                .allowsEmptyAPIKey,
        )
    }

    @Test
    fun `non OpenAI-compatible families are not covered`() {
        for (type in listOf(ProviderType.gemini, ProviderType.openRouter)) {
            assertFalse(
                "expected $type to require a key",
                instance(type, ProviderCredential.apiKey, "https://example.com/v1")
                    .allowsEmptyAPIKey,
            )
        }
    }


    private fun src(path: String) = com.openminis.app.ProductionSources.read(path)

    private val addScreenSrc by lazy { src("ui/settings/AddProviderScreen.kt") }

    @Test
    fun `every add path refreshes the catalog immediately after addInstance`() {
        val addCalls = Regex("""providerRepository\.addInstance\(""")
            .findAll(addScreenSrc).count()
        val refreshCalls = Regex("""providerRepository\.triggerAsyncModelReconcile\(""")
            .findAll(addScreenSrc).count()
        assertTrue("expected the three add paths, found $addCalls", addCalls >= 3)
        assertEquals(
            "every addInstance must be paired with a refreshModels — an unpaired " +
                "one is GH#265 (a brand-new provider showing this build's " +
                "compiled-in catalog until the user finds the manual Refresh)",
            addCalls,
            refreshCalls,
        )
    }

    @Test
    fun `the refresh follows the add rather than preceding it`() {
        var searchFrom = 0
        var pairs = 0
        while (true) {
            val add = addScreenSrc.indexOf("providerRepository.addInstance(", searchFrom)
            if (add < 0) break
            val refresh = addScreenSrc.indexOf("providerRepository.triggerAsyncModelReconcile(", add)
            assertTrue("addInstance at $add has no refreshModels after it", refresh > add)
            assertTrue(
                "addInstance at $add and its refresh are ${refresh - add} chars apart",
                refresh - add < 900,
            )
            pairs++
            searchFrom = add + 1
        }
        assertTrue("expected at least three add/refresh pairs, found $pairs", pairs >= 3)
    }


    @Test
    fun `xAI refresh fetches live and falls back to the built-in list`() {
        val repoSrc = src("data/repository/ProviderRepository.kt")
        val branch = repoSrc.substringAfter("ProviderType.xAI -> OpenAIModelsApi.fetchModels(")
            .substringBefore("ProviderType.kimiCode")
        assertTrue(
            "the built-in list must be reachable only through ifEmpty {}",
            branch.contains("ifEmpty") && branch.contains("XAIModelsApi.fetchModelsOAuth()"),
        )
        assertFalse(
            "xAI must not return the built-in catalog directly",
            repoSrc.contains("ProviderType.xAI -> com.openminis.app.provider.xai.XAIModelsApi.fetchModelsOAuth()"),
        )
    }

    @Test
    fun `the built-in xAI seed still exists and carries the reported model`() {
        val ids = com.openminis.app.data.model.LLMModel.allXAI.map { it.id }
        assertTrue("the seed must not be empty", ids.isNotEmpty())
        assertTrue("grok-4.6 is the GH#265 model: $ids", ids.contains("grok-4.6"))
    }
}
