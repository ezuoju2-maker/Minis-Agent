package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextWindowDefaultsTest {

    private fun model(id: String, ctx: Int? = null) =
        LLMModel(id = id, displayName = id, provider = "P", contextWindow = ctx)

    private fun window(id: String) = model(id).contextWindowTokens


    @Test
    fun `an unknown Grok is not the generic 128K default`() {
        assertNotEquals(
            "a Grok 4+ id must not inherit the trailing default",
            128_000,
            window("grok-4.6"),
        )
        assertEquals(256_000, window("grok-4.6"))
    }

    @Test
    fun `modern Claude is 1M, not 200K`() {
        for (id in listOf(
            "claude-fable-5-1", "claude-fable-5", "claude-opus-5",
            "claude-opus-4-8", "claude-opus-4-6", "claude-sonnet-5", "claude-sonnet-4-6",
        )) {
            assertEquals("$id must be 1M", 1_000_000, window(id))
        }
    }


    @Test
    fun `Haiku and the legacy Claude generations stay at 200K`() {
        assertEquals(200_000, window("claude-haiku-4-5"))
        assertEquals(200_000, window("claude-3-5-haiku-20241022"))
        assertEquals(200_000, window("claude-3-5-sonnet-20241022"))
        assertEquals(200_000, window("claude-3-opus-20240229"))
        assertEquals(200_000, window("claude-2.1"))
    }

    @Test
    fun `Haiku wins over the generation check regardless of version`() {
        assertEquals(200_000, window("claude-haiku-5"))
        assertEquals(200_000, window("claude-haiku-4-5-20251001"))
    }

    @Test
    fun `prefixed and namespaced Claude ids still resolve to the family`() {
        assertEquals(1_000_000, window("anthropic/claude-sonnet-4-6"))
        assertEquals(1_000_000, window("openrouter/anthropic/claude-opus-4-8"))
        assertEquals(200_000, window("us.anthropic.claude-3-5-sonnet-20241022-v1:0"))
    }


    @Test
    fun `Grok 2 and 3 are the 131K generation`() {
        assertEquals(131_072, window("grok-2-1212"))
        assertEquals(131_072, window("grok-3-mini"))
        assertEquals(131_072, window("grok-3-mini-fast"))
    }

    @Test
    fun `Grok 4 and later floor at 256K`() {
        for (id in listOf(
            "grok-4.6", "grok-4.5", "grok-4.3", "grok-4-fast",
            "grok-4.20-0309-reasoning", "grok-code-fast-1", "xai/grok-4.1",
        )) {
            assertEquals("$id must floor at 256K", 256_000, window(id))
        }
    }

    @Test
    fun `no earlier family branch shadows a Grok id`() {
        val grokIds = listOf(
            "grok-4.6", "grok-2-1212", "grok-3-mini", "grok-4-20-fast",
            "xai/grok-4.1", "grok-code-fast-1",
        )
        for (id in grokIds) {
            val w = window(id)
            assertTrue(
                "$id resolved to $w — a non-Grok branch matched first",
                w == 131_072 || w == 256_000,
            )
        }
    }


    @Test
    fun `Gemini is 1M except the 1_0 generation`() {
        assertEquals(1_000_000, window("gemini-3-pro-preview"))
        assertEquals(1_000_000, window("gemini-2.5-pro"))
        assertEquals(1_000_000, window("gemini-2.5-flash-lite"))
        assertEquals(32_000, window("gemini-1.0-pro"))
    }

    @Test
    fun `the OpenAI family keeps its per-generation values`() {
        assertEquals(16_000, window("gpt-3.5-turbo"))
        assertEquals(128_000, window("gpt-4o"))
        assertEquals(128_000, window("gpt-4-turbo"))
        assertEquals(400_000, window("gpt-5.6-terra"))
        assertEquals(8_000, window("gpt-4"))
        assertEquals(200_000, window("o3"))
        assertEquals(200_000, window("o4-mini"))
        assertEquals(200_000, window("codex-mini-latest"))
    }

    @Test
    fun `a gpt-5 codex id takes the gpt-5 window, not the legacy codex one`() {
        assertEquals(400_000, window("gpt-5.3-codex"))
        assertEquals(400_000, window("gpt-5.1-codex-max"))
        assertEquals(400_000, window("gpt-5-codex"))
    }

    @Test
    fun `gpt-4o and gpt-4-turbo are not caught by the bare gpt-4 branch`() {
        assertNotEquals(8_000, window("gpt-4o"))
        assertNotEquals(8_000, window("gpt-4o-mini"))
        assertNotEquals(8_000, window("gpt-4-turbo-2024-04-09"))
        assertEquals(8_000, window("gpt-4-0613"))
    }

    @Test
    fun `DeepSeek and everything unrecognised land on 128K`() {
        assertEquals(128_000, window("deepseek-v4"))
        assertEquals(128_000, window("deepseek-flash"))
        assertEquals(128_000, window("some-relay/unknown-model-v9"))
        assertEquals(128_000, window(""))
    }


    @Test
    fun `a declared context window beats the heuristic in both directions`() {
        assertEquals(131_072, model("grok-4.6", ctx = 131_072).contextWindowTokens)
        assertEquals(2_000_000, model("grok-4.6", ctx = 2_000_000).contextWindowTokens)
        assertEquals(200_000, model("claude-sonnet-4-6", ctx = 200_000).contextWindowTokens)
    }

    @Test
    fun `a zero or negative declared window falls through to the heuristic`() {
        assertEquals(1_000_000, model("claude-sonnet-5", ctx = 0).contextWindowTokens)
        assertEquals(256_000, model("grok-4.6", ctx = -1).contextWindowTokens)
    }

    @Test
    fun `the built-in Anthropic catalog declares its windows explicitly`() {
        for (m in LLMModel.allAnthropic) {
            val declared = requireNotNull(m.contextWindow) {
                "${m.id} must declare a contextWindow rather than lean on the heuristic"
            }
            assertTrue("${m.id} declared $declared", declared > 0)
            assertEquals("${m.id} heuristic must agree", declared, m.contextWindowTokens)
        }
        assertEquals(200_000, LLMModel.claudeHaiku45.contextWindow)
    }

    @Test
    fun `a user override wins over the catalog and the heuristic`() {
        val entry = ModelEntry(
            providerInstanceId = "inst",
            baseModel = LLMModel.claudeSonnet5,
            overrides = ModelOverrides(contextWindow = 300_000),
        )
        assertEquals(300_000, entry.model.contextWindowTokens)

        val heuristicOnly = ModelEntry(
            providerInstanceId = "inst",
            baseModel = model("grok-4.6"),
            overrides = ModelOverrides(contextWindow = 131_072),
        )
        assertEquals(131_072, heuristicOnly.model.contextWindowTokens)
    }


    @Test
    fun `the slider heuristic agrees with the loop for modern Claude and Gemini`() {
        for (id in listOf("claude-opus-4-8", "claude-sonnet-4-6", "gemini-2.5-pro", "gemini-3-pro-preview")) {
            val m = model(id)
            assertEquals(
                "$id: slider ${inferContextWindowTokens(m)} vs loop ${m.contextWindowTokens}",
                m.contextWindowTokens,
                inferContextWindowTokens(m),
            )
        }
    }

    @Test
    fun `the slider heuristic also prefers a declared window`() {
        assertEquals(777_000, inferContextWindowTokens(model("anything", ctx = 777_000)))
        assertEquals(1_000_000, inferContextWindowTokens(model("claude-opus-4-8", ctx = 0)))
    }

    @Test
    fun `the slider heuristic carries the Grok branch and agrees with the loop`() {
        for (id in listOf(
            "grok-4.6", "grok-4.5", "grok-4.3", "grok-4-fast",
            "grok-4.20-0309-reasoning", "grok-code-fast-1", "xai/grok-4.1",
            "grok-2-1212", "grok-3-mini", "grok-3-mini-fast",
        )) {
            val m = model(id)
            val slider = inferContextWindowTokens(m)
            val loop = m.contextWindowTokens
            assertEquals("$id: slider $slider vs loop $loop", loop, slider)
            assertTrue("slider ceiling $slider exceeds the real window $loop", slider <= loop)
        }
        assertEquals(256_000, inferContextWindowTokens(model("grok-4.6")))
        assertEquals(131_072, inferContextWindowTokens(model("grok-3-mini")))
    }

    @Test
    fun `both heuristics agree on every family they know`() {
        val ids = listOf(
            "claude-opus-4-8", "claude-sonnet-4-6", "claude-3-5-sonnet-20241022",
            "claude-haiku-4-5", "claude-2-1",
            "gemini-2.5-pro", "gemini-3-pro-preview", "gemini-2.5-flash",
            "gpt-5", "gpt-5.6-sol", "gpt-5.3-codex", "openai/gpt-5",
            "o3", "o3-mini", "o4-mini", "openai/o3",
            "codex-mini-latest",
            "gpt-4", "gpt-4-0613",
            "gpt-4o", "gpt-4-turbo", "gpt-3.5-turbo",
            "deepseek-v4", "grok-4.6", "grok-3-mini", "xai/grok-4.1",
            "llama-3-70b-instruct", "some-relay-model",
        )
        for (id in ids) {
            val m = model(id)
            val slider = inferContextWindowTokens(m)
            val loop = m.contextWindowTokens
            assertEquals("$id: slider $slider vs loop $loop", loop, slider)
            assertTrue(
                "$id: slider ceiling $slider exceeds the real window $loop",
                slider <= loop,
            )
        }
    }

    @Test
    fun `the aligned OpenAI rows carry the loop's numbers`() {
        assertEquals(400_000, inferContextWindowTokens(model("gpt-5.6-sol")))
        assertEquals(200_000, inferContextWindowTokens(model("o4-mini")))
        assertEquals(200_000, inferContextWindowTokens(model("codex-mini-latest")))
        assertEquals(8_000, inferContextWindowTokens(model("gpt-4-0613")))
        assertEquals(400_000, inferContextWindowTokens(model("gpt-5.3-codex")))
    }

    @Test
    fun `the slider does not claim a family from a mid-name marker`() {
        for (id in listOf("my-o3-tune", "relay-codex-proxy", "some-gpt-5-clone")) {
            assertEquals(
                "$id should fall through to the default on the slider side",
                128_000,
                inferContextWindowTokens(model(id)),
            )
        }
    }


    @Test
    fun `the heuristic source still carries both fixed branches`() {
        val src = com.openminis.app.ProductionSources.read("data/model/LLMModel.kt")
        val body = src.substringAfter("val contextWindowTokens: Int")
            .substringBefore("fun capabilityPromptFragment(")
        assertTrue("the xAI branch must exist", body.contains("""lid.contains("grok")"""))
        assertTrue("Grok 2/3 stay 131_072", body.contains("131_072"))
        assertTrue("modern Claude must be 1M", body.contains("1_000_000"))
        assertTrue(
            "the declared-value guard must reject non-positive values",
            body.contains("contextWindow?.let { if (it > 0) return it }"),
        )
    }

    @Test
    fun `the slider heuristic source still carries the xAI branch`() {
        val src = com.openminis.app.ProductionSources.read("data/model/ModelContextWindowHeuristic.kt")
        assertTrue("the xAI branch must exist", src.contains("""idLower.startsWith("grok")"""))
        assertTrue("Grok 2/3 stay 131_072", src.contains("131_072"))
        assertTrue("Grok 4+ floors at 256_000", src.contains("256_000"))
    }
}
