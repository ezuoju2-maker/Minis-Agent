package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.provider.thinking.ThinkingResolveContext
import com.openminis.app.provider.thinking.ThinkingRule
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import com.openminis.app.provider.thinking.ThinkingWireFormat
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ThinkingRulesRegressionTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }


    private fun model(
        id: String,
        supportsReasoning: Boolean? = true,
        reasoningEffortValues: List<String>? = null,
        interleavedReasoningField: String? = null,
    ) = LLMModel(
        id = id,
        displayName = id,
        provider = "TestProvider",
        supportsReasoning = supportsReasoning,
        reasoningEffortValues = reasoningEffortValues,
        interleavedReasoningField = interleavedReasoningField,
    )

    private fun plainHistory(): List<LLMMessage> = listOf(
        LLMMessage(LLMMessage.Role.USER, "question"),
    )

    private fun historyWithReasoning(): List<LLMMessage> = listOf(
        LLMMessage(LLMMessage.Role.USER, "first question"),
        LLMMessage(LLMMessage.Role.ASSISTANT, "first answer")
            .copy(reasoningContent = "captured chain of thought"),
        LLMMessage(LLMMessage.Role.USER, "second question"),
    )

    private fun capture(
        model: LLMModel,
        level: ThinkingLevel,
        basePath: String = server.url("/v1").toString().trimEnd('/'),
        history: List<LLMMessage> = plainHistory(),
        maxTokens: Int = 4096,
    ): JSONObject {
        val ok = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        repeat(4) {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(ok),
            )
        }
        val provider = OpenAIProvider(apiKey = "test-key", model = model, basePath = basePath)
        runCatching {
            runBlocking {
                provider.sendMessageClamped(
                    messages = history,
                    systemPrompt = null,
                    maxTokens = maxTokens,
                    temperature = null,
                    imageParts = emptyList(),
                    tools = emptyList(),
                    thinkingLevel = level,
                )
            }
        }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    private fun thinkingKeysPresent(body: JSONObject): List<String> {
        val watched = listOf(
            "reasoning_effort", "reasoning", "thinking", "enable_thinking",
            "thinking_budget", "reasoningEffort", "thinkingConfig",
        )
        return watched.filter { body.has(it) }
    }

    private fun anyMessageHasKey(body: JSONObject, key: String): Boolean {
        val msgs = body.optJSONArray("messages") ?: return false
        for (i in 0 until msgs.length()) {
            if (msgs.getJSONObject(i).has(key)) return true
        }
        return false
    }


    @Test
    fun `mistral sends no thinking request parameter at any level`() {
        for (level in listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.HIGH, ThinkingLevel.MAX)) {
            val body = capture(
                model = model("mistral-large-latest", reasoningEffortValues = listOf("low", "high")),
                level = level,
                basePath = server.url("/mistral.ai/v1").toString().trimEnd('/'),
            )
            assertEquals(
                "Mistral must carry zero thinking control keys at level=$level (422 extra_forbidden): $body",
                emptyList<String>(),
                thinkingKeysPresent(body),
            )
        }
    }

    @Test
    fun `mistral sends no thinking control when off`() {
        val body = capture(
            model = model("mistral-large-latest", reasoningEffortValues = listOf("low", "high")),
            level = ThinkingLevel.OFF,
            basePath = server.url("/mistral.ai/v1").toString().trimEnd('/'),
        )
        assertEquals(
            "Mistral must carry zero thinking control keys at OFF: $body",
            emptyList<String>(),
            thinkingKeysPresent(body),
        )
    }

    @Test
    fun `mistral sends no reasoning on the responses api path`() {
        for (level in listOf(ThinkingLevel.OFF, ThinkingLevel.HIGH)) {
            val ok = """{"type":"response.completed","response":{"output":[],"status":"completed"}}"""
            repeat(4) {
                server.enqueue(
                    MockResponse().setHeader("Content-Type", "application/json").setBody(ok),
                )
            }
            val provider = OpenAIProvider(
                apiKey = "test-key",
                model = model("mistral-large-latest", reasoningEffortValues = listOf("low", "high")),
                basePath = server.url("/mistral.ai/v1").toString().trimEnd('/'),
                useResponsesAPI = true,
            )
            runCatching {
                runBlocking {
                    provider.sendMessageClamped(
                        messages = plainHistory(),
                        systemPrompt = null,
                        maxTokens = 4096,
                        temperature = null,
                        imageParts = emptyList(),
                        tools = emptyList(),
                        thinkingLevel = level,
                    )
                }
            }
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertFalse(
                "Responses-path Mistral must not carry `reasoning` at level=$level (422 extra_forbidden): $body",
                body.has("reasoning"),
            )
        }
    }

    @Test
    fun `non-mistral responses api still receives reasoning`() {
        val ok = """{"type":"response.completed","response":{"output":[],"status":"completed"}}"""
        repeat(4) {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(ok),
            )
        }
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = model("gpt-5.3", reasoningEffortValues = listOf("low", "medium", "high")),
            basePath = server.url("/v1").toString().trimEnd('/'),
            useResponsesAPI = true,
        )
        runCatching {
            runBlocking {
                provider.sendMessageClamped(
                    messages = plainHistory(),
                    systemPrompt = null,
                    maxTokens = 4096,
                    temperature = null,
                    imageParts = emptyList(),
                    tools = emptyList(),
                    thinkingLevel = ThinkingLevel.HIGH,
                )
            }
        }
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(
            "a non-Mistral Responses provider must still get reasoning — the gate must be vendor-specific: $body",
            body.has("reasoning"),
        )
    }

    @Test
    fun `mistral strips reasoning_content from assistant history`() {
        val body = capture(
            model = model("mistral-large-latest"),
            level = ThinkingLevel.MEDIUM,
            basePath = server.url("/mistral.ai/v1").toString().trimEnd('/'),
            history = historyWithReasoning(),
        )
        assertFalse(
            "reasoning_content must never reach Mistral (422 extra_forbidden): $body",
            anyMessageHasKey(body, "reasoning_content"),
        )
    }

    @Test
    fun `venice never receives root thinking key even when off`() {
        for (level in listOf(ThinkingLevel.OFF, ThinkingLevel.HIGH)) {
            val body = capture(
                model = model("deepseek-v4-flash", reasoningEffortValues = listOf("low", "high", "max")),
                level = level,
                basePath = server.url("/api.venice.ai/api/v1").toString().trimEnd('/'),
            )
            assertFalse(
                "root `thinking` must never be sent to Venice at level=$level (400 unrecognized_keys): $body",
                body.has("thinking"),
            )
        }
    }

    @Test
    fun `undeclared glm family sends no thinking field`() {
        val body = capture(
            model = model("glm-4.5-air", supportsReasoning = null, reasoningEffortValues = null),
            level = ThinkingLevel.HIGH,
        )
        assertEquals(
            "a glm id with no declared effort tiers must send no thinking control: $body",
            emptyList<String>(),
            thinkingKeysPresent(body),
        )
    }


    @Test
    fun `declared glm model receives clamped root reasoning_effort`() {
        val body = capture(
            model = model("glm-5.2", reasoningEffortValues = listOf("high", "max")),
            level = ThinkingLevel.HIGH,
        )
        assertEquals(
            "declared effort model must carry root reasoning_effort: $body",
            "high",
            body.optString("reasoning_effort", null),
        )
    }

    @Test
    fun `sparse declared set clamps xhigh onto a declared tier`() {
        val body = capture(
            model = model("glm-5.2", reasoningEffortValues = listOf("high", "max")),
            level = ThinkingLevel.XHIGH,
        )
        val sent = body.optString("reasoning_effort", null)
        assertTrue(
            "xhigh must be clamped onto the declared set [high,max], got '$sent': $body",
            sent == "high" || sent == "max",
        )
    }

    @Test
    fun `ultra never reaches the wire as a literal`() {
        val body = capture(
            model = model("gpt-5.6-sol", reasoningEffortValues = listOf("low", "medium", "high", "xhigh", "max")),
            level = ThinkingLevel.ULTRA,
        )
        assertFalse(
            "literal 'ultra' must never be sent (backends 400 on it): $body",
            body.optString("reasoning_effort", "") == "ultra",
        )
    }

    @Test
    fun `gpt-6-astra reaches max by id rule and sends ultra as max`() {
        val m = model("gpt-6-astra")
        assertEquals(
            "undeclared gpt-6-astra must reach MAX through the id rule",
            ThinkingLevel.MAX,
            m.catalogMaxThinkingLevel,
        )
        val body = capture(model = m, level = ThinkingLevel.ULTRA)
        assertEquals(
            "ULTRA on gpt-6-astra must go out as 'max', never 'ultra': $body",
            "max",
            body.optString("reasoning_effort", null),
        )
    }


    @Test
    fun `mimo omits reasoning_effort entirely when off`() {
        for (id in listOf("mimo-v2.5", "mimo-2.5", "agnes-1")) {
            val body = capture(model = model(id), level = ThinkingLevel.OFF)
            assertFalse(
                "$id must OMIT reasoning_effort at OFF, never send an off-tier string: $body",
                body.has("reasoning_effort"),
            )
        }
    }

    @Test
    fun `unknown custom base omits the off tier`() {
        val body = capture(
            model = model("some-relay-model", reasoningEffortValues = listOf("low", "medium", "high")),
            level = ThinkingLevel.OFF,
        )
        assertFalse(
            "an unlisted custom base must keep OFF omission, not invent an off tier: $body",
            body.has("reasoning_effort"),
        )
    }

    @Test
    fun `volcano ark sends minimal as its off tier`() {
        val body = capture(
            model = model("doubao-pro"),
            level = ThinkingLevel.OFF,
            basePath = server.url("/ark.volces.com/api/v3").toString().trimEnd('/'),
        )
        assertEquals(
            "Ark's documented off tier is 'minimal' so the vendor default can't silently reason: $body",
            "minimal",
            body.optString("reasoning_effort", null),
        )
    }


    @Test
    fun `deepseek v4 sends thinking and reasoning_effort as root siblings`() {
        val body = capture(
            model = model("deepseek-v4-pro", reasoningEffortValues = listOf("high", "max")),
            level = ThinkingLevel.HIGH,
        )
        val thinking = body.optJSONObject("thinking")
        assertTrue("`thinking` object must be present when enabled: $body", thinking != null)
        assertEquals("thinking.type must be 'enabled': $body", "enabled", thinking?.optString("type"))
        assertTrue("root reasoning_effort must be present: $body", body.has("reasoning_effort"))
        assertFalse(
            "reasoning_effort must NOT be nested inside thinking{} — that is the silent 847822eb bug: $body",
            thinking?.has("reasoning_effort") ?: false,
        )
    }

    @Test
    fun `deepseek v4 explicitly disables when off`() {
        val body = capture(
            model = model("deepseek-v4-pro", reasoningEffortValues = listOf("high", "max")),
            level = ThinkingLevel.OFF,
        )
        assertEquals(
            "V4 reasons by default, so OFF must be explicit: $body",
            "disabled",
            body.optJSONObject("thinking")?.optString("type"),
        )
    }

    @Test
    fun `ark hosted deepseek uses uniform reasoning_effort not vendor thinking object`() {
        val body = capture(
            model = model("deepseek-v4-flash", reasoningEffortValues = listOf("low", "high", "max")),
            level = ThinkingLevel.HIGH,
            basePath = server.url("/ark.volces.com/api/v3").toString().trimEnd('/'),
        )
        assertFalse(
            "on Ark the vendor-native thinking:{} must NOT be sent: $body",
            body.has("thinking"),
        )
        assertTrue("on Ark the tier travels in reasoning_effort: $body", body.has("reasoning_effort"))
    }

    @Test
    fun `qwen dual-sends thinking params at root and extra_body`() {
        val body = capture(model = model("qwen3-32b"), level = ThinkingLevel.MEDIUM)
        assertTrue("root enable_thinking expected: $body", body.has("enable_thinking"))
        assertTrue(
            "extra_body.enable_thinking expected (DashScope reads it there): $body",
            body.optJSONObject("extra_body")?.has("enable_thinking") ?: false,
        )
    }


    @Test
    fun `qwen thinking_budget stays strictly below max_tokens`() {
        for (maxTokens in listOf(16384, 64000, 4096)) {
            val body = capture(model = model("qwen3-32b"), level = ThinkingLevel.MAX, maxTokens = maxTokens)
            val budget = body.optInt("thinking_budget", -1)
            if (budget > 0) {
                assertTrue(
                    "thinking_budget($budget) must be STRICTLY < max_completion_tokens($maxTokens): $body",
                    budget < maxTokens,
                )
            }
        }
    }

    @Test
    fun `qwen drops thinking_budget when max_tokens leaves no room`() {
        val body = capture(model = model("qwen3-32b"), level = ThinkingLevel.MAX, maxTokens = 1)
        val budget = body.optInt("thinking_budget", 0)
        assertTrue(
            "with max_tokens=1 no positive budget can be strictly below it; expected omitted/0, got $budget: $body",
            budget <= 0,
        )
    }


    @Test
    fun `interleaved model echoes reasoning_content on assistant history`() {
        val body = capture(
            model = model(
                "deepseek-v4-flash",
                reasoningEffortValues = listOf("low", "high", "max"),
                interleavedReasoningField = "reasoning_content",
            ),
            level = ThinkingLevel.MEDIUM,
            history = historyWithReasoning(),
        )
        assertTrue(
            "interleaved-reasoning models 400 when history omits reasoning_content: $body",
            anyMessageHasKey(body, "reasoning_content"),
        )
    }


    @Test
    fun `both mimo spellings are clamped below xhigh`() {
        for (id in listOf("mimo-2.5", "mimo-v2.5", "mimo-v2.5-pro")) {
            val body = capture(model = model(id), level = ThinkingLevel.XHIGH)
            val sent = body.optString("reasoning_effort", "")
            assertFalse(
                "$id must never receive xhigh (backend 400s); got '$sent': $body",
                sent == "xhigh",
            )
        }
    }


    @Test
    fun `declared tiers override the substring ceiling`() {
        val m = model("glm-5.2", reasoningEffortValues = listOf("high", "max"))
        assertEquals(
            "a declared top tier of 'max' must be reachable from the UI: ${m.reasoningEffortValues}",
            ThinkingLevel.MAX,
            m.catalogMaxThinkingLevel,
        )
    }

    @Test
    fun `sparse declaration yields distinct selectable levels`() {
        val m = model("glm-5.2", reasoningEffortValues = listOf("high", "max"))
        val levels = m.selectableThinkingLevels
        assertEquals(
            "selectable levels must be distinct — duplicates mean a slider that changes nothing",
            levels.size,
            levels.toSet().size,
        )
        assertEquals(
            "a [high, max] declaration must surface exactly two options, got $levels",
            listOf(ThinkingLevel.HIGH, ThinkingLevel.MAX),
            levels,
        )
    }

    @Test
    fun `deepseek v4 max request reaches the wire as max`() {
        for (id in listOf("deepseek-v4-flash", "deepseek-v4-pro", "glm-5.2")) {
            val body = capture(
                model = model(id, reasoningEffortValues = listOf("high", "max")),
                level = ThinkingLevel.MAX,
            )
            assertEquals(
                "$id must send reasoning_effort=max when the user asks for MAX: $body",
                "max",
                body.optString("reasoning_effort", null),
            )
        }
    }

    @Test
    fun `narrow declaration lowers the ceiling below the default`() {
        val m = model("gpt-5.3", reasoningEffortValues = listOf("low", "high"))
        assertEquals(
            "a declared set topping out at 'high' must cap the ceiling there: " +
                "${m.reasoningEffortValues}",
            ThinkingLevel.HIGH,
            m.catalogMaxThinkingLevel,
        )
        val body = capture(model = m, level = ThinkingLevel.MAX)
        assertEquals(
            "an undeclared tier must never reach the wire: $body",
            "high",
            body.optString("reasoning_effort", null),
        )
    }

    @Test
    fun `incomplete declaration does not lower a known rule ceiling`() {
        assertEquals(
            "precondition: the family rule reaches MAX",
            ThinkingLevel.MAX,
            ThinkingLevelCatalog.declaredMaxLevel("gpt-5.6-sol"),
        )
        val m = model("gpt-5.6-sol", reasoningEffortValues = listOf("low", "medium", "high"))
        assertEquals(
            "an incomplete declaration must not cut the rule's ceiling to HIGH",
            ThinkingLevel.MAX,
            m.catalogMaxThinkingLevel,
        )
    }

    @Test
    fun `declaration topping at xhigh keeps max reachable`() {
        val m = model("gpt-5.6-sol", reasoningEffortValues = listOf("high", "xhigh"))
        assertEquals(ThinkingLevel.MAX, m.catalogMaxThinkingLevel)
    }

    @Test
    fun `declaration above the rule still raises the ceiling`() {
        val m = model("glm-5.2", reasoningEffortValues = listOf("high", "max"))
        assertEquals(ThinkingLevel.MAX, m.catalogMaxThinkingLevel)
    }

    @Test
    fun `non-reasoning model ignores both the rule and the declaration`() {
        val m = model(
            "gpt-5.6-sol",
            supportsReasoning = false,
            reasoningEffortValues = listOf("high", "max"),
        )
        assertEquals(ThinkingLevel.OFF, m.catalogMaxThinkingLevel)
    }

    @Test
    fun `undeclared model still uses the id-rule ceiling`() {
        assertEquals(
            "no declaration → hardcoded default is still XHIGH",
            ThinkingLevel.XHIGH,
            model("some-unknown-reasoner").catalogMaxThinkingLevel,
        )
        assertEquals(
            "no declaration → an id rule still applies (mimo caps at high)",
            ThinkingLevel.HIGH,
            model("mimo-v2.5").catalogMaxThinkingLevel,
        )
        assertEquals(
            "a non-reasoning model is OFF regardless of declarations",
            ThinkingLevel.OFF,
            model("gpt-4o", supportsReasoning = false, reasoningEffortValues = listOf("high", "max"))
                .catalogMaxThinkingLevel,
        )
    }


    @Test
    fun `deepseek-flash resolves to the vendor-native sibling shape`() {
        val m = model("deepseek-flash", reasoningEffortValues = listOf("high", "max"))
        val on = capture(m, ThinkingLevel.HIGH)
        assertEquals("enabled", on.getJSONObject("thinking").getString("type"))
        assertEquals("high", on.optString("reasoning_effort", null))
        assertFalse("tier must be a ROOT sibling, never nested: $on", on.getJSONObject("thinking").has("reasoning_effort"))
        val off = capture(m, ThinkingLevel.OFF)
        assertEquals("disabled", off.getJSONObject("thinking").getString("type"))
        assertFalse("OFF carries no tier on this shape: $off", off.has("reasoning_effort"))

        val undeclared = capture(model("deepseek-flash-lite"), ThinkingLevel.MAX)
        assertEquals("enabled", undeclared.getJSONObject("thinking").getString("type"))
        assertEquals("the catalog rule caps at MAX, so MAX goes out as max", "max", undeclared.optString("reasoning_effort", null))
    }

    @Test
    fun `deepseek-chat does not inherit the flash rule`() {
        val body = capture(model("deepseek-chat", supportsReasoning = null), ThinkingLevel.HIGH)
        assertFalse("deepseek-chat must not get the sibling thinking object: $body", body.has("thinking"))
    }

    @Test
    fun `declared xhigh ceiling keeps max reachable and the round trip is stable`() {
        val m = model("gpt-5.6-sol", reasoningEffortValues = listOf("low", "medium", "high", "xhigh"))
        assertEquals(ThinkingLevel.MAX, m.catalogMaxThinkingLevel)
        assertEquals(
            "the declared tiers themselves are unchanged — only the ceiling is lifted",
            listOf(ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.XHIGH),
            m.selectableThinkingLevels,
        )
        val provider = OpenAIProvider(apiKey = "k", model = m, basePath = server.url("/v1").toString().trimEnd('/'))
        assertEquals(ThinkingLevel.XHIGH, provider.clampThinkingLevel(ThinkingLevel.XHIGH))
        assertEquals(ThinkingLevel.MAX, provider.clampThinkingLevel(ThinkingLevel.MAX))
        assertEquals("selecting XHigh again after Max must not lower anything", ThinkingLevel.XHIGH, provider.clampThinkingLevel(ThinkingLevel.XHIGH))
        assertEquals(ThinkingLevel.MAX, provider.clampThinkingLevel(ThinkingLevel.ULTRA))
        val body = capture(m, ThinkingLevel.MAX)
        assertEquals("max", body.optString("reasoning_effort", null))
    }

    @Test
    fun `an unruled model keeps a narrow declaration as its ceiling`() {
        val m = model("some-relay-reasoner", reasoningEffortValues = listOf("low", "medium", "high"))
        assertEquals(ThinkingLevel.HIGH, m.catalogMaxThinkingLevel)
        assertEquals("high", capture(m, ThinkingLevel.MAX).optString("reasoning_effort", null))
    }

    @Test
    fun `a custom rule is scoped to the provider instance, not the model name`() {
        fun ctx(instanceId: String?) = ThinkingResolveContext(
            modelId = "MiniMax-M3",
            instanceId = instanceId,
            supportsReasoning = true,
            declaredEffortValues = null,
            level = ThinkingLevel.HIGH,
            maxTokens = 4096,
            isOpenRouter = false,
            usesUnifiedReasoningEffort = false,
            isMistral = false,
            isDashScope = false,
            offEffort = null,
        )
        try {
            ThinkingRuleResolver.setCustomRules(
                "inst-anthropic-compat",
                listOf(
                    ThinkingRule(
                        kind = ThinkingRule.Kind.CUSTOM,
                        scope = ThinkingRule.Scope.AllModels,
                        wireFormat = ThinkingWireFormat.DeepSeekSibling,
                        label = "minimax-via-anthropic-compat",
                    ),
                ),
            )
            val scoped = JSONObject()
            val scopedTrace = ThinkingRuleResolver.apply(scoped, ctx("inst-anthropic-compat"))
            assertEquals("enabled", scoped.getJSONObject("thinking").getString("type"))
            assertEquals("high", scoped.getString("reasoning_effort"))
            assertEquals(ThinkingRule.Kind.CUSTOM, scopedTrace.matchedRuleKind)
            assertEquals("minimax-via-anthropic-compat", scopedTrace.matchedRuleLabel)

            val other = JSONObject()
            val otherTrace = ThinkingRuleResolver.apply(other, ctx("inst-other"))
            assertFalse("another instance's model must not pick up the rule: $other", other.has("thinking"))
            assertFalse(other.has("reasoning_effort"))
            assertEquals("openai-compatible-default", otherTrace.matchedRuleLabel)

            val unscoped = JSONObject()
            ThinkingRuleResolver.apply(unscoped, ctx(null))
            assertFalse("no instance → built-ins only", unscoped.has("thinking"))

            val ok = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
            repeat(4) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(ok)) }
            val provider = OpenAIProvider(apiKey = "k", model = model("MiniMax-M3"), basePath = server.url("/v1").toString().trimEnd('/'))
            provider.thinkingRuleInstanceId = "inst-anthropic-compat"
            runCatching {
                runBlocking {
                    provider.sendMessageClamped(plainHistory(), null, 4096, null, emptyList(), emptyList(), ThinkingLevel.HIGH)
                }
            }
            val wire = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("enabled", wire.getJSONObject("thinking").getString("type"))
        } finally {
            ThinkingRuleResolver.setAllCustomRules(emptyMap())
        }
    }


    @Test
    fun `the ceiling is the maximum of the rule top and the declared top`() {
        assertEquals(
            "rule MAX, nothing declared → MAX",
            ThinkingLevel.MAX,
            model("gpt-5.6-sol").catalogMaxThinkingLevel,
        )
        assertEquals(
            "no rule, declared top HIGH → HIGH",
            ThinkingLevel.HIGH,
            model("some-relay-reasoner", reasoningEffortValues = listOf("low", "high")).catalogMaxThinkingLevel,
        )
        assertEquals(
            "rule HIGH, declared MAX → MAX",
            ThinkingLevel.MAX,
            model("mimo-v2.5", reasoningEffortValues = listOf("high", "max")).catalogMaxThinkingLevel,
        )
        assertEquals(
            "rule MAX, declared HIGH → MAX, never HIGH",
            ThinkingLevel.MAX,
            model("gpt-5.6-sol", reasoningEffortValues = listOf("low", "high")).catalogMaxThinkingLevel,
        )
    }

    @Test
    fun `a two-tier declaration does not hide Max from a MAX-rule model`() {
        val m = model("gpt-5.6-sol", reasoningEffortValues = listOf("low", "medium"))
        assertEquals(
            "the declared top is MEDIUM, but the family rule reaches MAX",
            ThinkingLevel.MEDIUM,
            m.selectableThinkingLevels.last(),
        )
        assertEquals(
            "…and the ceiling must follow the RULE: ${m.reasoningEffortValues}",
            ThinkingLevel.MAX,
            m.catalogMaxThinkingLevel,
        )
    }

    @Test
    fun `lifting the ceiling does not loosen the wire clamp`() {
        val nativeModel = model("gpt-5.6-sol", reasoningEffortValues = listOf("low", "medium"))
        assertEquals(ThinkingLevel.MAX, nativeModel.catalogMaxThinkingLevel)
        assertEquals(
            "openai-native trusts the rule and sends the tier as asked",
            "max",
            capture(nativeModel, ThinkingLevel.MAX).optString("reasoning_effort", null),
        )

        val genericModel = model("relay-hosted-reasoner", reasoningEffortValues = listOf("low", "medium"))
        assertEquals(
            "generic branch: MAX is not declared, so the wire value clamps to the declared top",
            "medium",
            capture(genericModel, ThinkingLevel.MAX).optString("reasoning_effort", null),
        )
    }

    @Test
    fun `an XHigh to Max to XHigh round trip is stable in both directions`() {
        val m = model("gpt-5.6-sol", reasoningEffortValues = listOf("low", "medium"))
        val provider = OpenAIProvider(
            apiKey = "k", model = m, basePath = server.url("/v1").toString().trimEnd('/'),
        )
        val walk = listOf(
            ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.XHIGH,
            ThinkingLevel.MAX, ThinkingLevel.HIGH, ThinkingLevel.MAX, ThinkingLevel.XHIGH,
        )
        for ((step, level) in walk.withIndex()) {
            assertEquals(
                "step $step: the ceiling must not drift as the selection moves",
                ThinkingLevel.MAX,
                m.catalogMaxThinkingLevel,
            )
            assertEquals(
                "step $step: $level must survive the clamp under a MAX ceiling",
                level,
                provider.clampThinkingLevel(level),
            )
        }
        assertEquals(
            "ULTRA collapses onto MAX — it is a client-side concept, never a wire tier",
            ThinkingLevel.MAX,
            provider.clampThinkingLevel(ThinkingLevel.ULTRA),
        )
    }

    @Test
    fun `a non-reasoning family member is OFF despite both the rule and the declaration`() {
        for (id in listOf("mimo-v2.5-tts", "mimo-v2.5-asr", "gpt-5.6-sol-audio")) {
            assertEquals(
                "$id cannot reason, so no rule or declaration may lift its ceiling",
                ThinkingLevel.OFF,
                model(
                    id,
                    supportsReasoning = false,
                    reasoningEffortValues = listOf("high", "max"),
                ).catalogMaxThinkingLevel,
            )
        }
    }

    @Test
    fun `the ceiling comparison still takes the higher of rule and declaration`() {
        val src = com.openminis.app.ProductionSources.read("provider/ThinkingLevelCatalog.kt")
        assertTrue(
            "the ceiling must be a max() of the two sources — this line IS ef95c007d",
            src.contains("return if (ruleTop != null && ruleTop.rank > declaredTop.rank) ruleTop else declaredTop"),
        )
        assertTrue(
            "the non-reasoning check must stay AHEAD of the rule lookup",
            src.indexOf("if (supportsReasoning == false) return ThinkingLevel.OFF") <
                src.indexOf("val ruleTop = ThinkingLevelCatalog.declaredMaxLevel(id)"),
        )
        assertTrue(
            "…and the undeclared fallback must stay the conservative XHIGH default",
            src.contains("return ruleTop ?: ThinkingLevel.XHIGH"),
        )
    }
}
