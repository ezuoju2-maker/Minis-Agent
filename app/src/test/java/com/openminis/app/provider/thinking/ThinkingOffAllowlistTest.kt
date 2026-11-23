package com.openminis.app.provider.thinking

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingOffAllowlistTest {

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

    private fun body(
        base: String,
        m: LLMModel,
        level: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject = OpenAIProvider(apiKey = "k", model = m, basePath = base).buildRequestBody(
        messages = listOf(LLMMessage(LLMMessage.Role.USER, "q")),
        systemPrompt = null,
        maxTokens = 4096,
        stream = false,
        temperature = null,
        imageParts = emptyList(),
        tools = emptyList(),
        thinkingLevel = level,
    )

    private fun effort(b: JSONObject): String? =
        if (b.has("reasoning_effort")) b.getString("reasoning_effort") else null


    @Test
    fun `official OpenAI states none as its off tier`() {
        assertEquals(
            "none",
            effort(body("https://api.openai.com/v1", model("gpt-5.3"))),
        )
    }

    @Test
    fun `Volcano Ark states minimal, by base or by family`() {
        assertEquals(
            "ark base",
            "minimal",
            effort(body("https://ark.cn-beijing.volces.com/api/v3", model("doubao-pro"))),
        )
        assertEquals(
            "volces base",
            "minimal",
            effort(body("https://open.volces.com/api/v3", model("some-model"))),
        )
        assertEquals(
            "seed- family on an unrecognised base",
            "minimal",
            effort(body("https://relay.example.com/v1", model("seed-1.6"))),
        )
        assertEquals(
            "doubao family on an unrecognised base",
            "minimal",
            effort(body("https://relay.example.com/v1", model("doubao-1.5-pro"))),
        )
    }

    @Test
    fun `everyone else omits the off tier`() {
        val unlisted = listOf(
            "https://my-resource.openai.azure.com/openai/deployments/gpt5",
            "https://api.x.ai/v1",
            "https://openrouter.ai/api/v1",
            "https://integrate.api.nvidia.com/v1",
            "https://relay.example.com/v1",
        )
        for (base in unlisted) {
            val b = body(base, model("gpt-5.3"))
            assertNull("$base must omit the off tier, got $b", effort(b))
        }
    }

    @Test
    fun `a relay that only mentions the OpenAI host does not inherit its tier`() {
        assertNull(effort(body("https://proxy.example.com/api.openai.com/v1", model("gpt-5.3"))))
    }


    @Test
    fun `strict-enum families are exempt from every off tier`() {
        for (id in listOf("mimo-2.5", "mimo-v2.5", "mimo-v2.5-pro", "agnes-1", "MiMo-V2.5")) {
            assertNull(
                "$id must receive no off tier even on the OpenAI base",
                effort(body("https://api.openai.com/v1", model(id))),
            )
            assertNull(
                "$id must receive no off tier even on an Ark base",
                effort(body("https://ark.cn-beijing.volces.com/api/v3", model(id))),
            )
        }
    }

    @Test
    fun `the strict-enum exemption does not mute the enabled path`() {
        assertEquals(
            "high",
            effort(body("https://api.openai.com/v1", model("mimo-v2.5"), ThinkingLevel.XHIGH)),
        )
        assertEquals(
            "max is not xhigh and is not clamped by this rule",
            "max",
            effort(body("https://api.openai.com/v1", model("mimo-v2.5"), ThinkingLevel.MAX)),
        )
    }


    private fun ctx(
        modelId: String,
        offEffort: String?,
        declared: List<String>? = null,
        supportsReasoning: Boolean? = true,
        unified: Boolean = false,
        level: ThinkingLevel = ThinkingLevel.OFF,
        isXAI: Boolean = false,
        declaresNoEffortTiers: Boolean = false,
        isDashScope: Boolean = false,
        isOpenRouter: Boolean = false,
    ) = ThinkingResolveContext(
        modelId = modelId,
        supportsReasoning = supportsReasoning,
        declaredEffortValues = declared,
        declaresNoEffortTiers = declaresNoEffortTiers,
        level = level,
        maxTokens = 4096,
        isOpenRouter = isOpenRouter,
        usesUnifiedReasoningEffort = unified,
        isMistral = false,
        isDashScope = isDashScope,
        isXAI = isXAI,
        offEffort = offEffort,
    )

    private fun resolve(c: ThinkingResolveContext): Pair<JSONObject, ThinkingResolveTrace> {
        val b = JSONObject()
        return b to ThinkingRuleResolver.apply(b, c)
    }

    @Test
    fun `OpenAI-native ids send the off tier without consulting the declaration`() {
        val (withNothing, _) = resolve(ctx("gpt-5.3", offEffort = "none"))
        assertEquals("none", withNothing.getString("reasoning_effort"))

        val (againstDeclaration, _) = resolve(
            ctx("gpt-5.3", offEffort = "none", declared = listOf("low", "medium", "high")),
        )
        assertEquals(
            "a declaration that omits 'none' must not suppress it for this family",
            "none",
            againstDeclaration.getString("reasoning_effort"),
        )
    }

    @Test
    fun `self-reasoning families need a gateway or an explicit declaration`() {
        val (bare, _) = resolve(ctx("glm-4.6", offEffort = "minimal"))
        assertFalse("no gateway, no declaration → omit: $bare", bare.has("reasoning_effort"))

        val (onGateway, _) = resolve(ctx("glm-4.6", offEffort = "minimal", unified = true))
        assertEquals(
            "a unified gateway normalizes the field, so the tier is safe",
            "minimal",
            onGateway.getString("reasoning_effort"),
        )

        val (declaring, _) = resolve(
            ctx("glm-4.6", offEffort = "minimal", declared = listOf("minimal", "high")),
        )
        assertEquals(
            "the model naming the tier itself is the other sufficient signal",
            "minimal",
            declaring.getString("reasoning_effort"),
        )

        val (declaringOther, _) = resolve(
            ctx("kimi-k2", offEffort = "minimal", declared = listOf("low", "high")),
        )
        assertFalse(
            "declaring OTHER tiers is not declaring this one: $declaringOther",
            declaringOther.has("reasoning_effort"),
        )
    }

    @Test
    fun `everyone else sends the tier unless the declaration excludes it`() {
        val (silent, _) = resolve(ctx("some-relay-reasoner", offEffort = "minimal"))
        assertEquals("minimal", silent.getString("reasoning_effort"))

        val (declaresIt, _) = resolve(
            ctx("some-relay-reasoner", offEffort = "minimal", declared = listOf("minimal", "high")),
        )
        assertEquals("minimal", declaresIt.getString("reasoning_effort"))

        val (excludesIt, _) = resolve(
            ctx("some-relay-reasoner", offEffort = "minimal", declared = listOf("low", "high")),
        )
        assertFalse(
            "a declaration without the tier suppresses it: $excludesIt",
            excludesIt.has("reasoning_effort"),
        )

        val (nonReasoning, _) = resolve(
            ctx("gpt-4o-audio", offEffort = "none", supportsReasoning = false),
        )
        assertFalse(
            "a model that cannot reason takes no tier at all: $nonReasoning",
            nonReasoning.has("reasoning_effort"),
        )
    }

    @Test
    fun `the off tier is never clamped upward into a thinking tier`() {
        val (b, _) = resolve(
            ctx("some-relay-reasoner", offEffort = "minimal", declared = listOf("high", "max")),
        )
        assertFalse("OFF must not become 'high': $b", b.has("reasoning_effort"))
    }


    @Test
    fun `a gpt-5 id on a unified gateway keeps the OpenAI-native mechanism`() {
        val (b, trace) = resolve(ctx("gpt-5.3", offEffort = "minimal", unified = true, level = ThinkingLevel.HIGH))
        assertEquals("openai-native", trace.matchedRuleLabel)
        assertEquals("high", b.getString("reasoning_effort"))
        assertFalse("must not flip to the qwen mechanism: $b", b.has("enable_thinking"))
    }

    @Test
    fun `a qwen id on a unified gateway keeps the qwen dual-send`() {
        val (b, trace) = resolve(ctx("qwen3-32b", offEffort = "minimal", unified = true, level = ThinkingLevel.HIGH))
        assertEquals("qwen-dashscope", trace.matchedRuleLabel)
        assertTrue("qwen mechanism, not the gateway's: $b", b.getBoolean("enable_thinking"))
        assertFalse("and never the gateway's root tier: $b", b.has("reasoning_effort"))
    }

    @Test
    fun `a DashScope endpoint claims a non-qwen id too`() {
        val (b, trace) = resolve(
            ctx("some-other-model", offEffort = null, isDashScope = true, level = ThinkingLevel.HIGH),
        )
        assertEquals("qwen-dashscope", trace.matchedRuleLabel)
        assertTrue(b.getBoolean("enable_thinking"))
    }

    @Test
    fun `the gateway rule does claim the families registered below it`() {
        val (b, trace) = resolve(
            ctx("deepseek-v4-pro", offEffort = "minimal", unified = true, level = ThinkingLevel.HIGH),
        )
        assertEquals("unified-gateway(ark|azure|venice)", trace.matchedRuleLabel)
        assertEquals("high", b.getString("reasoning_effort"))
        assertFalse("no vendor-native thinking object on a gateway: $b", b.has("thinking"))
    }

    @Test
    fun `OpenRouter outranks every model rule and nests the effort`() {
        val (on, trace) = resolve(
            ctx("gpt-5.3", offEffort = "none", isOpenRouter = true, level = ThinkingLevel.HIGH),
        )
        assertEquals("openrouter", trace.matchedRuleLabel)
        assertEquals("high", on.getJSONObject("reasoning").getString("effort"))
        assertFalse("nested, never at the root: $on", on.has("reasoning_effort"))

        val (off, _) = resolve(ctx("gpt-5.3", offEffort = "none", isOpenRouter = true))
        assertFalse("OpenRouter omits at OFF: $off", off.has("reasoning"))
    }


    @Test
    fun `deepseek-flash and deepseek-v4 share a shape but not a rule`() {
        for ((id, label) in listOf(
            "deepseek-flash" to "deepseek-flash-official",
            "deepseek-flash-lite" to "deepseek-flash-official",
            "deepseek-v4-pro" to "deepseek-v4-official",
            "deepseek-v4-flash" to "deepseek-v4-official",
        )) {
            val (b, trace) = resolve(ctx(id, offEffort = null, level = ThinkingLevel.HIGH))
            assertEquals("$id must resolve through $label", label, trace.matchedRuleLabel)
            assertEquals("$id: sibling shape", "enabled", b.getJSONObject("thinking").getString("type"))
            assertEquals("$id: root tier", "high", b.getString("reasoning_effort"))
        }
    }

    @Test
    fun `the anchored flash scope leaves chat and reasoner alone`() {
        for (id in listOf("deepseek-chat", "deepseek-reasoner")) {
            val (b, trace) = resolve(ctx(id, offEffort = null, level = ThinkingLevel.HIGH))
            assertFalse(
                "$id must not take the sibling shape (label=${trace.matchedRuleLabel}): $b",
                b.has("thinking"),
            )
        }
    }


    @Test
    fun `an xAI model declaring no tiers omits the field on the enabled path`() {
        val (on, _) = resolve(
            ctx(
                "grok-build-0.1",
                offEffort = "minimal",
                isXAI = true,
                declaresNoEffortTiers = true,
                level = ThinkingLevel.HIGH,
            ),
        )
        assertFalse("the enabled-path skip must hold even when an off tier exists: $on", on.has("reasoning_effort"))
    }

    @Test
    fun `the xAI empty-tier skip also applies on the OFF path`() {
        val (off, trace) = resolve(
            ctx(
                "grok-build-0.1",
                offEffort = "minimal",
                isXAI = true,
                declaresNoEffortTiers = true,
                level = ThinkingLevel.OFF,
            ),
        )
        assertFalse(
            "an xAI model the catalog says takes NO effort tiers must not receive reasoning_effort " +
                "even at OFF — that field is exactly what grok-build-0.1 answers with a 400 " +
                "(rule=${trace.matchedRuleLabel}): $off",
            off.has("reasoning_effort"),
        )

        val (noOffTier, _) = resolve(
            ctx(
                "grok-build-0.1",
                offEffort = null,
                isXAI = true,
                declaresNoEffortTiers = true,
                level = ThinkingLevel.OFF,
            ),
        )
        assertFalse(
            "with no off tier — today's real xAI config — OFF must still emit nothing: $noOffTier",
            noOffTier.has("reasoning_effort"),
        )
    }


    @Test
    fun `the allowlist literals still live in the production predicate`() {
        val src = com.openminis.app.ProductionSources.read("provider/openai/OpenAIProvider.kt")
        for (needle in listOf(
            "private fun explicitOffEffort(): String?",
            "if (isAzure) return null",
            "startsWith(\"https://api.openai.com\")",
            "base.contains(\"volces\")",
            "base.contains(\"ark.\")",
            "lid.contains(\"seed-\")",
            "lid.contains(\"doubao\")",
        )) {
            assertTrue("explicitOffEffort no longer carries: $needle", src.contains(needle))
        }

        val resolver = com.openminis.app.ProductionSources.read("provider/thinking/ThinkingRuleResolver.kt")
        assertTrue(
            "the strict-enum exemption must stay keyed on the mimo/agnes FAMILY",
            resolver.contains("val strictEffortEnum = lid.contains(\"mimo\") || lid.contains(\"agnes\")"),
        )
        assertTrue(
            "the self-reasoning family list drives the OFF dispatch",
            resolver.contains("listOf(\"deepseek\", \"glm\", \"kimi\", \"minimax\")"),
        )
        assertTrue(
            "the gateway rule must still be registered under its own label",
            resolver.contains("label = \"unified-gateway(ark|azure|venice)\""),
        )
    }

    @Test
    fun `the unified-gateway rule is registered below openai-native and qwen`() {
        val src = com.openminis.app.ProductionSources.read("provider/thinking/ThinkingRuleResolver.kt")
        val openrouter = src.indexOf("label = \"openrouter\"")
        val native = src.indexOf("label = \"openai-native\"")
        val qwen = src.indexOf("label = \"qwen-dashscope\"")
        val gateway = src.indexOf("label = \"unified-gateway(ark|azure|venice)\"")
        val v4 = src.indexOf("label = \"deepseek-v4-official\"")
        val flash = src.indexOf("label = \"deepseek-flash-official\"")
        val default = src.indexOf("label = \"openai-compatible-default\"")
        for ((name, at) in listOf(
            "openrouter" to openrouter, "openai-native" to native, "qwen-dashscope" to qwen,
            "unified-gateway" to gateway, "deepseek-v4" to v4, "deepseek-flash" to flash,
            "default" to default,
        )) {
            assertTrue("rule label not found in the registry: $name", at > 0)
        }
        assertTrue("OpenRouter must outrank the model rules", openrouter < native)
        assertTrue("openai-native must outrank the gateway rule (866387d63)", native < gateway)
        assertTrue("qwen-dashscope must outrank the gateway rule (866387d63)", qwen < gateway)
        assertTrue("the gateway rule must outrank the deepseek families", gateway < v4)
        assertTrue("the flash sibling sits next to the v4 rule", v4 < flash)
        assertTrue("the provider default is last, so stage A always matches", flash < default)
    }
}
