package com.openminis.app.provider

import com.openminis.app.ProductionSources
import com.openminis.app.data.FastModePrefs
import com.openminis.app.data.SpeedTier
import com.openminis.app.data.SpeedTiers
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.CodexModelsApi
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexRoutingHintAndTiersTest {

    @After fun tearDown() = FastModePrefs.setCachedTierForTest(SpeedTier.STANDARD)


    @Test
    fun `routing hint value matches the official format with and without a tier`() {
        assertEquals("model=gpt-6-astra", OpenAIProvider.codexRoutingHint("gpt-6-astra", null))
        assertEquals("model=gpt-6-astra", OpenAIProvider.codexRoutingHint("gpt-6-astra", ""))
        assertEquals("model=gpt-6-astra;tier=priority", OpenAIProvider.codexRoutingHint("gpt-6-astra", "priority"))
        assertEquals("model=gpt-6.1-sol;tier=ultrafast", OpenAIProvider.codexRoutingHint("gpt-6.1-sol", "ultrafast"))
        assertNull(OpenAIProvider.codexRoutingHint("", "priority"))
        assertNull(OpenAIProvider.codexRoutingHint(null, null))
        assertEquals("x-codex-routing-hint", OpenAIProvider.CODEX_ROUTING_HINT_HEADER)
    }

    @Test
    fun `the hint is derived from the final body and sent only on the codex oauth branch`() {
        val src = ProductionSources.read("provider/openai/OpenAIProvider.kt")
        assertTrue(src.contains(
            "codexRoutingHint(body.optString(\"model\"), body.optString(\"service_tier\")),\n            promptCacheKey = body.optString(\"prompt_cache_key\"),",
        ))
        val build = src.substring(src.indexOf("internal suspend fun buildRequest(bodyStr: String, routingHint: String? = null, promptCacheKey: String? = null): Request {"))
        val oauthStart = build.indexOf("if (isOAuth && !forceChatCompletions) {")
        val oauthEnd = build.indexOf("return builder.build()", oauthStart)
        val set = build.indexOf("routingHint?.let { builder.header(CODEX_ROUTING_HINT_HEADER, it) }")
        assertTrue("set inside the Codex OAuth branch", set in oauthStart until oauthEnd)
        assertEquals(1, src.split("builder.header(CODEX_ROUTING_HINT_HEADER").size - 1)
    }

    @Test
    fun `an api-key responses body still carries the tier but the hint is codex-only`() {
        FastModePrefs.setCachedTierForTest(SpeedTier.FAST)
        val body = OpenAIProvider(apiKey = "k", model = LLMModel("gpt-5.5", "GPT-5.5", "OpenAI", supportsReasoning = true),
            basePath = "https://api.openai.com/v1", useResponsesAPI = true)
            .buildResponsesAPIBody(listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 1024, false, emptyList())
        assertEquals("priority", body.getString("service_tier"))
    }


    @Test
    fun `service_tiers and the deprecated additional_speed_tiers are parsed`() {
        assertEquals(
            listOf("priority", "ultrafast"),
            CodexModelsApi.parseServiceTiers(JSONObject(
                """{"service_tiers":[{"id":"priority","name":"Fast","description":"x"},{"id":"Ultrafast","name":"Ultrafast"}]}""",
            )),
        )
        assertEquals(listOf("priority"), CodexModelsApi.parseServiceTiers(JSONObject("""{"additional_speed_tiers":["priority"]}""")))
        assertEquals(emptyList<String>(), CodexModelsApi.parseServiceTiers(JSONObject("""{"service_tiers":[]}""")))
        assertNull(CodexModelsApi.parseServiceTiers(JSONObject("""{"slug":"x"}""")))
        val models = CodexModelsApi.parseModels(
            """{"models":[{"slug":"gpt-6-astra","service_tiers":[{"id":"priority","name":"Fast","description":""}]},{"slug":"gpt-5.5"}]}""",
        )
        assertEquals(listOf("priority"), models.first { it.id == "gpt-6-astra" }.serviceTiers)
        assertNull(models.first { it.id == "gpt-5.5" }.serviceTiers)
    }

    @Test
    fun `ultrafast is catalogue-declared or whitelisted, as on ios`() {
        fun m(id: String, tiers: List<String>?) = LLMModel(id, id, "OpenAI", serviceTiers = tiers)
        val none = emptyList<String>()
        assertTrue(SpeedTiers.supportsUltrafast(m("gpt-7", listOf("priority", "ultrafast")), none))
        assertTrue(SpeedTiers.supportsUltrafast(m("GPT-5.6-Sol", null), listOf("gpt-5.6-sol")))
        assertTrue(SpeedTiers.supportsUltrafast(m("gpt-6-astra", listOf("priority")), none))
        assertTrue(SpeedTiers.supportsUltrafast(m("gpt-6-astra", null), none))
        assertFalse(SpeedTiers.supportsUltrafast(m("gpt-6.1-sol", emptyList()), none))
        assertFalse(SpeedTiers.supportsUltrafast(m("gpt-6.1-sol", listOf("priority")), none))
        assertFalse(SpeedTiers.supportsUltrafast(m("gpt-6-sol", null), none))
    }

    @Test
    fun `the request falls back to priority when neither the catalog nor the whitelist offers ultrafast`() {
        FastModePrefs.setCachedTierForTest(SpeedTier.ULTRAFAST)
        fun tier(model: LLMModel) = OpenAIProvider(apiKey = "k", model = model, basePath = "https://api.openai.com/v1", useResponsesAPI = true)
            .buildResponsesAPIBody(listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 1024, false, emptyList())
            .optString("service_tier")
        assertEquals("ultrafast", tier(LLMModel("gpt-6-astra", "A", "OpenAI", serviceTiers = listOf("priority"))))
        assertEquals("priority", tier(LLMModel("gpt-6.1-sol", "S", "OpenAI", serviceTiers = listOf("priority"))))
        assertEquals("ultrafast", tier(LLMModel("gpt-7", "7", "OpenAI", serviceTiers = listOf("priority", "ultrafast"))))
        assertEquals("ultrafast", tier(LLMModel("gpt-6-astra", "A", "OpenAI", serviceTiers = listOf("priority", "ultrafast"))))
        assertEquals("ultrafast", tier(LLMModel("gpt-6-astra", "A", "OpenAI")))
    }

    @Test
    fun `the discovery merge keeps the catalog tiers`() {
        val fresh = listOf(LLMModel("gpt-6-astra", "A", "OpenAI", serviceTiers = listOf("priority")))
        val enriched = listOf(LLMModel("gpt-6-astra", "A", "OpenAI"))
        assertEquals(listOf("priority"), CodexModelsApi.restoreAuthoritative(fresh, enriched).single().serviceTiers)
    }
}
