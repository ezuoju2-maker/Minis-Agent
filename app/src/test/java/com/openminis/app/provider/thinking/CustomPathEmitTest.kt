package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomPathEmitTest {

    @After
    fun tearDown() = ThinkingRuleResolver.setAllCustomRules(emptyMap())

    private fun ctx(level: ThinkingLevel) = ThinkingResolveContext(
        modelId = "some-model",
        instanceId = "inst-A",
        supportsReasoning = true,
        declaredEffortValues = null,
        level = level,
        maxTokens = 4096,
        isOpenRouter = false,
        usesUnifiedReasoningEffort = false,
        isMistral = false,
        isDashScope = false,
        offEffort = null,
    )

    private fun ruleWith(fmt: ThinkingWireFormat) = mapOf(
        "inst-A" to listOf(
            ThinkingRule(
                kind = ThinkingRule.Kind.CUSTOM,
                scope = ThinkingRule.Scope.AllModels,
                wireFormat = fmt,
                label = "t",
            ),
        ),
    )

    private fun emit(fmt: ThinkingWireFormat, level: ThinkingLevel = ThinkingLevel.HIGH): JSONObject {
        ThinkingRuleResolver.setAllCustomRules(ruleWith(fmt))
        val body = JSONObject()
        ThinkingRuleResolver.apply(body, ctx(level))
        return body
    }


    @Test
    fun `a CustomPath rule no longer throws and writes its nested value`() {
        val body = emit(
            ThinkingWireFormat.CustomPath(
                path = "extra_body.thinking.mode",
                values = mapOf(ThinkingLevel.HIGH to "enabled"),
                offValue = "disabled",
            ),
        )
        assertEquals(
            "enabled",
            body.getJSONObject("extra_body").getJSONObject("thinking").getString("mode"),
        )
    }

    @Test
    fun `a single-segment path writes at the root`() {
        val body = emit(
            ThinkingWireFormat.CustomPath("reasoning_mode", mapOf(ThinkingLevel.HIGH to "on"), null),
        )
        assertEquals("on", body.getString("reasoning_mode"))
    }

    @Test
    fun `BooleanToggle and ExtraBodyToggle are emitted too`() {
        assertTrue(emit(ThinkingWireFormat.BooleanToggle("enable_thinking")).getBoolean("enable_thinking"))
        assertTrue(
            emit(ThinkingWireFormat.ExtraBodyToggle("extra_body.thinking.enabled"))
                .getJSONObject("extra_body").getJSONObject("thinking").getBoolean("enabled"),
        )
    }


    @Test
    fun `a tier with no value of its own falls back to HIGH`() {
        val body = emit(
            ThinkingWireFormat.CustomPath("a.b", mapOf(ThinkingLevel.HIGH to "hi"), null),
            level = ThinkingLevel.MEDIUM,
        )
        assertEquals("hi", body.getJSONObject("a").getString("b"))
    }

    @Test
    fun `OFF writes the off value, and writes nothing at all when none is set`() {
        val withOff = emit(
            ThinkingWireFormat.CustomPath("a.b", mapOf(ThinkingLevel.HIGH to "hi"), "bye"),
            level = ThinkingLevel.OFF,
        )
        assertEquals("bye", withOff.getJSONObject("a").getString("b"))

        val withoutOff = emit(
            ThinkingWireFormat.CustomPath("a.b", mapOf(ThinkingLevel.HIGH to "hi"), null),
            level = ThinkingLevel.OFF,
        )
        assertFalse(withoutOff.has("a"))
    }


    @Test
    fun `a reserved root key is refused rather than corrupting the request`() {
        for (reserved in listOf("messages", "model", "stream", "tools", "response_format")) {
            val body = emit(
                ThinkingWireFormat.CustomPath(reserved, mapOf(ThinkingLevel.HIGH to "x"), null),
            )
            assertFalse("must not write reserved root '$reserved'", body.has(reserved))
        }
    }

    @Test
    fun `reserved words are only reserved at the ROOT`() {
        val body = emit(
            ThinkingWireFormat.CustomPath("extra_body.model", mapOf(ThinkingLevel.HIGH to "x"), null),
        )
        assertEquals("x", body.getJSONObject("extra_body").getString("model"))
    }

    @Test
    fun `a refused or empty path reports no emitted value`() {
        ThinkingRuleResolver.setAllCustomRules(
            ruleWith(ThinkingWireFormat.CustomPath("messages", mapOf(ThinkingLevel.HIGH to "x"), null)),
        )
        val body = JSONObject()
        val trace = ThinkingRuleResolver.apply(body, ctx(ThinkingLevel.HIGH))
        assertTrue("trace must report no emitted keys", trace.emittedKeys.isEmpty())

        assertFalse(
            emit(ThinkingWireFormat.CustomPath("   ", mapOf(ThinkingLevel.HIGH to "x"), null)).keys().hasNext(),
        )
    }

    @Test
    fun `a scalar sitting where the path needs to nest is replaced`() {
        ThinkingRuleResolver.setAllCustomRules(
            ruleWith(ThinkingWireFormat.CustomPath("extra_body.x", mapOf(ThinkingLevel.HIGH to "v"), null)),
        )
        val body = JSONObject().put("extra_body", "i am a string")
        ThinkingRuleResolver.apply(body, ctx(ThinkingLevel.HIGH))
        assertEquals("v", body.getJSONObject("extra_body").getString("x"))
    }
}
