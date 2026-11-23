package com.openminis.app.data.model

import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.openai.OpenAIModelsApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportsReasoningDefaultsTest {


    @Test
    fun `GPT-5x and o-series built-ins declare reasoning explicitly`() {
        val byId = LLMModel.allOpenAI.associateBy { it.id }
        for (id in listOf(
            "gpt-5.5", "gpt-5.3-codex", "gpt-5.2-codex", "gpt-5.1-codex-max",
            "gpt-5.2", "o3", "o4-mini", "codex-mini-latest",
        )) {
            val m = requireNotNull(byId[id]) { "$id missing from allOpenAI" }
            assertEquals("$id must default to supportsReasoning=true", true, m.supportsReasoning)
        }
    }

    @Test
    fun `GPT-4o class built-ins do NOT claim reasoning`() {
        val byId = LLMModel.allOpenAI.associateBy { it.id }
        assertNull(byId["gpt-4o"]!!.supportsReasoning)
        assertNull(byId["gpt-4o-mini"]!!.supportsReasoning)
    }

    @Test
    fun `every chat model on the Codex OAuth path claims reasoning`() {
        val models = OpenAIModelsApi.fetchModelsOAuth()
        assertTrue("the Codex OAuth list must not be empty", models.isNotEmpty())
        val chat = models.filter { it.outputModalities?.contains("image") != true }
        assertTrue("expected chat SKUs in the list", chat.isNotEmpty())
        for (m in chat) {
            assertEquals(
                "${m.id} on the Codex OAuth path must declare supportsReasoning=true — " +
                    "/v1/responses REQUIRES the reasoning object on this auth path",
                true,
                m.supportsReasoning,
            )
        }
        assertTrue(
            "gpt-6-astra is the current flagship on this path",
            chat.any { it.id == "gpt-6-astra" },
        )
    }

    @Test
    fun `the Codex image SKUs do not claim reasoning`() {
        for (m in OpenAIModelsApi.codexImageModels()) {
            assertNull("${m.id} must not claim reasoning", m.supportsReasoning)
            assertEquals(listOf("image"), m.outputModalities)
        }
    }


    private fun knownReasoning(id: String): Boolean {
        val idLower = id.lowercase()
        return idLower.startsWith("gpt-5") ||
            idLower.startsWith("gpt-6") ||
            idLower.startsWith("o1") ||
            idLower.startsWith("o3") ||
            idLower.startsWith("o4") ||
            idLower.contains("codex")
    }

    @Test
    fun `the parse-time prefill covers the reasoning families`() {
        for (id in listOf(
            "gpt-5", "gpt-5.5", "gpt-5.6-terra", "gpt-5.4-mini", "GPT-5.2",
            "o1", "o1-mini", "o3", "o3-mini", "o4-mini",
            "codex-mini-latest", "gpt-5.3-codex", "some-relay/codex-max",
        )) {
            assertTrue("$id should be prefilled", knownReasoning(id))
        }
    }

    @Test
    fun `the prefill is anchored, so unrelated ids are not swept in`() {
        for (id in listOf(
            "gpt-4o", "gpt-4-turbo", "gpt-3.5-turbo",
            "olmo-2-13b", "omni-moderation-latest", "orca-mini",
            "text-embedding-3-large", "whisper-large-v3", "claude-sonnet-4-6",
        )) {
            assertFalse("$id must not be prefilled", knownReasoning(id))
        }
    }

    @Test
    fun `the prefill predicate anchors gpt-6 as well as gpt-5`() {
        for (id in listOf("gpt-6-astra", "gpt-6", "gpt-6.1-mini", "GPT-6-ASTRA")) {
            assertTrue("$id should be prefilled", knownReasoning(id))
        }
        assertTrue(knownReasoning("gpt-5.5"))
        assertTrue(knownReasoning("o3-mini"))
        assertFalse(knownReasoning("relay-for-gpt-6"))
        assertFalse(knownReasoning("gpt-4o"))

        assertEquals(
            true,
            OpenAIModelsApi.fetchModelsOAuth().first { it.id == "gpt-6-astra" }.supportsReasoning,
        )
    }

    @Test
    fun `the restated prefill predicate matches the source`() {
        val src = com.openminis.app.ProductionSources.read("provider/openai/OpenAIModelsApi.kt")
        assertTrue(src.contains("""idLower.startsWith("gpt-5")"""))
        assertTrue(src.contains("""idLower.startsWith("gpt-6")"""))
        assertTrue(src.contains("""idLower.startsWith("o1")"""))
        assertTrue(src.contains("""idLower.startsWith("o3")"""))
        assertTrue(src.contains("""idLower.startsWith("o4")"""))
        assertTrue(src.contains("""idLower.contains("codex")"""))
        assertTrue(
            "the parse must stamp true / null, never false",
            src.contains("supportsReasoning = if (knownReasoning) true else null"),
        )
    }


    @Test
    fun `direct Claude reasoning models are stamped from the id`() {
        for (id in listOf(
            "claude-fable-5-1", "claude-fable-5", "claude-opus-5", "claude-opus-4-8",
            "claude-opus-4-6", "claude-sonnet-5", "claude-sonnet-4-6", "claude-haiku-4-5",
            "claude-3-7-sonnet-20250219",
        )) {
            assertTrue("$id must support thinking", AnthropicProvider.supportsThinking(id))
        }
    }

    @Test
    fun `pre-3_7 Claude and non-Claude ids are not stamped`() {
        assertFalse(AnthropicProvider.supportsThinking("claude-3-5-sonnet-20241022"))
        assertFalse(AnthropicProvider.supportsThinking("claude-3-opus-20240229"))
        assertFalse(AnthropicProvider.supportsThinking("claude-2.1"))
        assertFalse(AnthropicProvider.supportsThinking("gpt-4o"))
        assertFalse(AnthropicProvider.supportsThinking("llama-4-maverick"))
        assertFalse(AnthropicProvider.supportsThinking(""))
    }

    @Test
    fun `the built-in Anthropic catalog declares reasoning on every entry`() {
        for (m in LLMModel.allAnthropic) {
            assertEquals("${m.id} must declare supportsReasoning=true", true, m.supportsReasoning)
        }
    }

    @Test
    fun `the catalog stamp agrees with the wire-format predicate`() {
        for (m in LLMModel.allAnthropic) {
            assertEquals(
                "${m.id}: catalog says ${m.supportsReasoning}, wire says " +
                    AnthropicProvider.supportsThinking(m.id),
                m.supportsReasoning == true,
                AnthropicProvider.supportsThinking(m.id),
            )
        }
    }

    @Test
    fun `the Anthropic parse still stamps from supportsThinking`() {
        val src = com.openminis.app.ProductionSources.read("provider/anthropic/AnthropicModelsApi.kt")
        assertTrue(
            "the id-driven stamp must survive",
            src.contains("if (AnthropicProvider.supportsThinking(id)) true else null"),
        )
    }


    @Test
    fun `xAI reasoning variants are stamped and the non-reasoning ones are not`() {
        val byId = LLMModel.allXAI.associateBy { it.id }
        for (id in listOf("grok-4.6", "grok-4.5", "grok-4.3", "grok-4-fast", "grok-code-fast-1")) {
            assertEquals("$id must declare reasoning", true, byId[id]?.supportsReasoning)
        }
        assertNull(byId["grok-4-fast-non-reasoning"]?.supportsReasoning)
        assertNull(byId["grok-4.20-0309-non-reasoning"]?.supportsReasoning)
    }


    @Test
    fun `a user override wins over the catalog default`() {
        val off = ModelEntry(
            providerInstanceId = "inst",
            baseModel = LLMModel.claudeSonnet5,
            overrides = ModelOverrides(supportsReasoning = false),
        )
        assertEquals(false, off.model.supportsReasoning)

        val on = ModelEntry(
            providerInstanceId = "inst",
            baseModel = LLMModel("relay/unknown-r1", "Unknown R1", "Custom"),
            overrides = ModelOverrides(supportsReasoning = true),
        )
        assertEquals(true, on.model.supportsReasoning)
    }

    @Test
    fun `an absent override inherits the base model, null included`() {
        val stamped = ModelEntry("inst", LLMModel.claudeSonnet5)
        assertEquals(true, stamped.model.supportsReasoning)

        val unknown = ModelEntry("inst", LLMModel("relay/x", "X", "Custom"))
        assertNull(unknown.model.supportsReasoning)
    }

    @Test
    fun `an explicit false is not mistaken for an empty override`() {
        assertFalse(ModelOverrides(supportsReasoning = false).isEmpty)
        assertFalse(ModelOverrides(supportsReasoning = true).isEmpty)
        assertTrue(ModelOverrides().isEmpty)
    }

    @Test
    fun `null and false are distinct states even though the gate treats them alike`() {
        val unknown = LLMModel("a", "A", "P", supportsReasoning = null)
        val stated = LLMModel("b", "B", "P", supportsReasoning = false)
        assertNull(unknown.supportsReasoning)
        assertEquals(false, stated.supportsReasoning)
        assertFalse(unknown.supportsReasoning == true)
        assertFalse(stated.supportsReasoning == true)
        assertFalse("the two must remain distinguishable", unknown == stated)
    }
}
