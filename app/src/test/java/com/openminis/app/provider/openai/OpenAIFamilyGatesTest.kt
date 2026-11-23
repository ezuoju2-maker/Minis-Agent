package com.openminis.app.provider.openai

import com.openminis.app.ProductionSources
import com.openminis.app.data.FastModePrefs
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.thinking.ThinkingResolveContext
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIFamilyGatesTest {

    @After
    fun tearDown() {
        FastModePrefs.setCachedEnabledForTest(false)
    }

    private fun model(
        id: String,
        supportsReasoning: Boolean? = true,
        reasoningEffortValues: List<String>? = null,
    ) = LLMModel(
        id = id,
        displayName = id,
        provider = "Test",
        supportsReasoning = supportsReasoning,
        reasoningEffortValues = reasoningEffortValues,
    )

    private val history = listOf(LLMMessage(LLMMessage.Role.USER, "hello"))

    private fun chat(
        m: LLMModel,
        base: String = "https://api.openai.com/v1",
        level: ThinkingLevel = ThinkingLevel.HIGH,
        maxTokens: Int = 4096,
        temperature: Double? = null,
    ): JSONObject = OpenAIProvider(apiKey = "k", model = m, basePath = base).buildRequestBody(
        messages = history,
        systemPrompt = null,
        maxTokens = maxTokens,
        stream = false,
        temperature = temperature,
        imageParts = emptyList(),
        tools = emptyList(),
        thinkingLevel = level,
    )

    private fun responses(
        m: LLMModel,
        base: String = "https://api.openai.com/v1",
        maxTokens: Int = 4096,
    ): JSONObject = OpenAIProvider(
        apiKey = "k", model = m, basePath = base, useResponsesAPI = true,
    ).buildResponsesAPIBody(
        messages = history,
        systemPrompt = null,
        maxTokens = maxTokens,
        stream = false,
        imageParts = emptyList(),
    )


    @Test
    fun `qwen thinking_budget stays strictly below the output cap at every level`() {
        val m = model("qwen3-32b")
        for (maxTokens in listOf(2, 64, 1024, 4096, 8192, 16384, 65536, 200_000)) {
            for (level in listOf(
                ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH,
                ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA,
            )) {
                val b = chat(m, base = "https://dashscope.aliyuncs.com/compatible-mode/v1", level = level, maxTokens = maxTokens)
                val cap = b.getInt("max_completion_tokens")
                assertEquals("the cap must be the one the caller asked for", maxTokens, cap)
                if (!b.has("thinking_budget")) continue
                val budget = b.getInt("thinking_budget")
                assertTrue(
                    "maxTokens=$maxTokens $level: thinking_budget=$budget must be > 0",
                    budget > 0,
                )
                assertTrue(
                    "maxTokens=$maxTokens $level: thinking_budget=$budget must be STRICTLY below cap=$cap (#35)",
                    budget < cap,
                )
            }
        }
    }

    @Test
    fun `qwen drops the budget entirely when the cap leaves no room`() {
        val b = chat(
            model("qwen3-32b"),
            base = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            maxTokens = 1,
        )
        assertTrue("the dual-send switch is still on", b.getBoolean("enable_thinking"))
        assertFalse("no room → omit the budget, never send 0: $b", b.has("thinking_budget"))
        assertFalse(
            "…on the extra_body copy too: $b",
            b.getJSONObject("extra_body").has("thinking_budget"),
        )
    }

    @Test
    fun `the root and extra_body copies of the budget always agree`() {
        for (maxTokens in listOf(1, 2, 1024, 65536)) {
            for (level in listOf(ThinkingLevel.LOW, ThinkingLevel.HIGH, ThinkingLevel.MAX)) {
                val b = chat(
                    model("qwen3-32b"),
                    base = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    level = level,
                    maxTokens = maxTokens,
                )
                val extra = b.getJSONObject("extra_body")
                assertEquals(
                    "maxTokens=$maxTokens $level: enable_thinking must match across both copies",
                    b.getBoolean("enable_thinking"),
                    extra.getBoolean("enable_thinking"),
                )
                assertEquals(
                    "maxTokens=$maxTokens $level: the budget key must be present in both or neither: $b",
                    b.has("thinking_budget"),
                    extra.has("thinking_budget"),
                )
                if (b.has("thinking_budget")) {
                    assertEquals(
                        "maxTokens=$maxTokens $level: the two copies must carry the SAME budget",
                        b.getInt("thinking_budget"),
                        extra.getInt("thinking_budget"),
                    )
                }
            }
        }
    }


    @Test
    fun `a qwen id on OpenRouter nests the effort instead of dual-sending`() {
        val b = chat(model("qwen3-32b"), base = "https://openrouter.ai/api/v1")
        assertFalse("DashScope's extension must not reach OpenRouter: $b", b.has("enable_thinking"))
        assertFalse(b.has("thinking_budget"))
        assertEquals("high", b.getJSONObject("reasoning").getString("effort"))
    }

    @Test
    fun `a qwen id on a unified gateway keeps the dual-send`() {
        val b = chat(model("qwen3-32b"), base = "https://ark.cn-beijing.volces.com/api/v3")
        assertTrue("the qwen rule outranks the gateway rule: $b", b.getBoolean("enable_thinking"))
        assertFalse(b.has("reasoning_effort"))
    }

    @Test
    fun `DashScope dual-sends for a non-qwen id as well`() {
        val b = chat(model("some-other-model"), base = "https://dashscope.aliyuncs.com/compatible-mode/v1")
        assertTrue(b.getBoolean("enable_thinking"))
        assertFalse("never the generic root tier on this mechanism: $b", b.has("reasoning_effort"))
    }

    @Test
    fun `a qwen id on an unrecognised relay keeps the id-matched mechanism`() {
        val b = chat(model("qwen3-32b"), base = "https://relay.example.com/v1")
        assertTrue(
            "the qwen rule is an ID match, not an endpoint match — preserved from the pre-refactor chain: $b",
            b.getBoolean("enable_thinking"),
        )
    }


    @Test
    fun `Fast Mode carries priority for gpt-family ids on the Responses path`() {
        FastModePrefs.setCachedEnabledForTest(true)
        for (id in listOf("gpt-5.3", "gpt-5.6-sol", "gpt-6-astra", "gpt-4o", "GPT-5.3")) {
            val b = responses(model(id))
            assertEquals(
                "$id is gpt-family and must carry the fast tier: $b",
                "priority",
                b.optString("service_tier", null),
            )
        }
    }

    @Test
    fun `Fast Mode off omits the key rather than stating a default`() {
        FastModePrefs.setCachedEnabledForTest(false)
        val b = responses(model("gpt-5.3"))
        assertFalse(
            "omission keeps the body byte-identical to before the feature existed: $b",
            b.has("service_tier"),
        )
    }

    @Test
    fun `a non-gpt id never carries the fast tier`() {
        FastModePrefs.setCachedEnabledForTest(true)
        for (id in listOf("o3-mini", "claude-opus-5", "deepseek-v4", "qwen3-32b", "grok-4-20")) {
            val b = responses(model(id))
            assertFalse("$id is not gpt-family and must not carry it: $b", b.has("service_tier"))
        }
    }

    @Test
    fun `the gpt fast-mode gate does not leak onto the Chat path`() {
        FastModePrefs.setCachedEnabledForTest(true)
        val b = chat(model("gpt-5.3"))
        assertFalse(
            "the Chat path's tier belongs to xAI Priority Processing, not Fast Mode: $b",
            b.has("service_tier"),
        )
    }

    @Test
    fun `flipping Fast Mode applies to the next request on the same provider`() {
        val provider = OpenAIProvider(
            apiKey = "k", model = model("gpt-5.3"),
            basePath = "https://api.openai.com/v1", useResponsesAPI = true,
        )
        fun build() = provider.buildResponsesAPIBody(history, null, 4096, false, emptyList())

        FastModePrefs.setCachedEnabledForTest(false)
        assertFalse(build().has("service_tier"))
        FastModePrefs.setCachedEnabledForTest(true)
        assertEquals("priority", build().optString("service_tier", null))
        FastModePrefs.setCachedEnabledForTest(false)
        assertFalse("and back off again", build().has("service_tier"))
    }


    @Test
    fun `an anthropic-prefixed id on OpenRouter opts into prompt caching`() {
        for (id in listOf("anthropic/claude-sonnet-4.5", "anthropic/claude-opus-5", "Anthropic/Claude-Opus-5")) {
            val b = chat(model(id), base = "https://openrouter.ai/api/v1")
            assertEquals(
                "$id must carry an automatic cache breakpoint: $b",
                "ephemeral",
                b.getJSONObject("cache_control").getString("type"),
            )
        }
    }

    @Test
    fun `other models on OpenRouter are unchanged`() {
        for (id in listOf("openai/gpt-5.3", "google/gemini-3-pro", "qwen3-32b", "moonshotai/kimi-k2")) {
            val b = chat(model(id), base = "https://openrouter.ai/api/v1")
            assertFalse("$id must not get cache_control: $b", b.has("cache_control"))
        }
    }

    @Test
    fun `an anthropic-prefixed id off OpenRouter gets no cache_control`() {
        for (base in listOf("https://api.openai.com/v1", "https://relay.example.com/v1")) {
            val b = chat(model("anthropic/claude-opus-5"), base = base)
            assertFalse("$base must not inject the field: $b", b.has("cache_control"))
        }
    }


    @Test
    fun `exactly one output-cap field is sent, chosen by gateway`() {
        val onOpenRouter = chat(model("gpt-5.3"), base = "https://openrouter.ai/api/v1", maxTokens = 1234)
        assertEquals("OpenRouter takes the legacy spelling", 1234, onOpenRouter.getInt("max_tokens"))
        assertFalse("…and only that one: $onOpenRouter", onOpenRouter.has("max_completion_tokens"))

        for (base in listOf("https://api.openai.com/v1", "https://relay.example.com/v1", "https://api.x.ai/v1")) {
            val b = chat(model("gpt-5.3"), base = base, maxTokens = 1234)
            assertEquals("$base takes the modern spelling", 1234, b.getInt("max_completion_tokens"))
            assertFalse("…and only that one: $b", b.has("max_tokens"))
        }
    }

    @Test
    fun `the Responses path states the cap as max_output_tokens`() {
        val b = responses(model("gpt-5.3"), maxTokens = 4321)
        assertEquals(4321, b.getInt("max_output_tokens"))
        assertFalse(b.has("max_completion_tokens"))
        assertFalse(b.has("max_tokens"))
    }


    @Test
    fun `temperature is written only when the caller supplies one`() {
        assertFalse(
            "no temperature from the caller → no field",
            chat(model("gpt-5.3"), temperature = null).has("temperature"),
        )
        assertEquals(
            0.7,
            chat(model("gpt-4o"), temperature = 0.7).getDouble("temperature"),
            1e-9,
        )
        assertEquals(
            "an explicit 0.0 is a real request for determinism, not 'unset'",
            0.0,
            chat(model("gpt-4o"), temperature = 0.0).getDouble("temperature"),
            1e-9,
        )
    }

    @Test
    fun `the Responses path never carries temperature`() {
        assertFalse(responses(model("gpt-5.3")).has("temperature"))
        assertFalse(responses(model("o3-mini")).has("temperature"))
    }


    @Test
    fun `the o-series and gpt-5 families take a root reasoning_effort`() {
        for (id in listOf("o1", "o1-mini", "o3", "o3-mini", "o4-mini", "gpt-5.3", "gpt-5.6-sol")) {
            val b = chat(model(id))
            assertEquals("$id must take a root tier: $b", "high", b.optString("reasoning_effort", null))
        }
    }

    @Test
    fun `the native branch ignores an incomplete declaration while the generic branch clamps`() {
        val declared = listOf("low", "medium")
        assertEquals(
            "native: the tier goes out as asked",
            "high",
            chat(model("gpt-5.3", reasoningEffortValues = declared)).optString("reasoning_effort", null),
        )
        assertEquals(
            "generic: an undeclared tier is snapped down to the declared top",
            "medium",
            chat(model("some-relay-reasoner", reasoningEffortValues = declared)).optString("reasoning_effort", null),
        )
    }

    @Test
    fun `KNOWN GAP - the broad startsWith o predicate routes non-o-series ids as native`() {
        val notReasoningModels = listOf("olmo-2-13b", "omni-x-7b", "openchat-3.5", "orca-2-13b")
        val misrouted = mutableListOf<String>()
        for (id in notReasoningModels) {
            val b = chat(model(id, reasoningEffortValues = listOf("low")))
            if (b.optString("reasoning_effort", null) == "high") misrouted += id
        }
        if (misrouted.isNotEmpty()) {
            println(
                "⚠️ KNOWN GAP [M20]: Android's OpenAI-native predicate is a bare startsWith(\"o\"), so " +
                    "these non-o-series ids are routed as OpenAI reasoning models and their declared " +
                    "effort set is ignored: $misrouted. iOS narrows the same predicate to " +
                    "o1/o3/o4/gpt-5/gpt-4. DELIBERATE on this side — ThinkingRuleResolver.kt ~190 " +
                    "records that the broad prefix is preserved verbatim because narrowing it would " +
                    "change behaviour for every id starting with \"o\" and Phase 1 must not. " +
                    "Consequence: an undeclared tier reaches such a backend unclamped. Expected " +
                    "behaviour if ever narrowed: these ids fall to the generic branch and clamp to " +
                    "\"low\". Recorded, not fixed.",
            )
        } else {
            println("✅ [M20] the OpenAI-native predicate has been narrowed; tighten this test to assert it.")
        }

        for (id in listOf("some-relay-reasoner", "gpt-4o", "llama-3.1-70b", "mistral-large")) {
            assertEquals(
                "$id must take the generic branch and clamp",
                "low",
                chat(model(id, reasoningEffortValues = listOf("low"))).optString("reasoning_effort", null),
            )
        }
    }

    @Test
    fun `the rule scope and the emitter predicate agree on what is native`() {
        fun nativeByEmitter(id: String): Boolean {
            val b = JSONObject()
            ThinkingRuleResolver.apply(
                b,
                ThinkingResolveContext(
                    modelId = id,
                    supportsReasoning = true,
                    declaredEffortValues = listOf("low"),
                    level = ThinkingLevel.HIGH,
                    maxTokens = 4096,
                    isOpenRouter = false,
                    usesUnifiedReasoningEffort = false,
                    isMistral = false,
                    isDashScope = false,
                    offEffort = null,
                ),
            )
            return b.optString("reasoning_effort", null) == "high"
        }
        for (id in listOf("o3-mini", "gpt-5.3", "olmo-2-13b")) {
            assertTrue(
                "$id matches an openai-native rule scope, so the emitter must agree it is native",
                nativeByEmitter(id),
            )
        }
        for (id in listOf("gpt-4o", "some-relay-reasoner")) {
            assertFalse(
                "$id matches no native scope, so the emitter must take the generic branch",
                nativeByEmitter(id),
            )
        }
    }


    @Test
    fun `the production gates still carry their scoping literals`() {
        val src = ProductionSources.read("provider/openai/OpenAIProvider.kt")
        for (needle in listOf(
            "if (!body.has(\"service_tier\") && model.id.contains(\"gpt\", ignoreCase = true)) {",
            ".effective(com.openminis.app.data.FastModePrefs.tier(), model)",
            "isOpenRouter && model.id.lowercase().startsWith(\"anthropic/\")",
            "body.put(\"cache_control\", JSONObject().put(\"type\", \"ephemeral\"))",
            "body.put(\"max_tokens\", maxTokens)",
            "body.put(\"max_completion_tokens\", maxTokens)",
            "basePath.contains(\"dashscope\")",
        )) {
            assertTrue("gate literal is gone from OpenAIProvider: $needle", src.contains(needle))
        }
    }

    @Test
    fun `the resolver still carries the budget margin and the documented broad prefix`() {
        val src = ProductionSources.read("provider/thinking/ThinkingRuleResolver.kt")
        assertTrue(
            "the budget must keep a margin below the cap (#35)",
            src.contains("val margin = maxOf(2048, ctx.maxTokens / 8)"),
        )
        assertTrue(
            "…and a hard strictly-below ceiling",
            src.contains("val ceiling = maxOf(1, minOf(ctx.maxTokens - margin, ctx.maxTokens - 1))"),
        )
        assertTrue(
            "the emitter's native predicate must stay in lockstep with the rule scopes",
            src.contains("lid.startsWith(\"o\")") &&
                src.contains("lid.startsWith(\"gpt-5\")") &&
                src.contains("lid.startsWith(\"gpt-6\")"),
        )
        assertTrue(
            "the broad-\"o\" breadth must stay DOCUMENTED as deliberate, not silently inherited",
            src.contains("the broad \"o\" prefix is"),
        )
        assertTrue(
            "the openai-native rule scopes are what the emitter mirrors",
            src.contains("ThinkingRule.Scope.ModelPattern(\"o*\")") &&
                src.contains("ThinkingRule.Scope.ModelPattern(\"gpt-5*\")") &&
                src.contains("ThinkingRule.Scope.ModelPattern(\"gpt-6*\")"),
        )
    }
}
