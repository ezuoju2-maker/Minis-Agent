package com.openminis.app.provider.anthropic

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeVersionParseTest {

    private fun parseClaudeVersion(modelId: String): Pair<Int, Int>? {
        val lower = modelId.lowercase()
        val claudeAt = lower.indexOf("claude")
        if (claudeAt < 0) return null
        val afterClaude = lower.substring(claudeAt + "claude".length)
        val regex = Regex("""[-/]?(\d+)(?:[-.](\d{1,2}))?(?:$|[^0-9])""")
        val match = regex.find(afterClaude) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: 0
        return major to minor
    }

    private fun assertVersion(expected: Pair<Int, Int>?, id: String) {
        assertEquals("parseClaudeVersion(\"$id\")", expected, parseClaudeVersion(id))
    }


    @Test
    fun `a single-segment version parses as minor zero, not null`() {
        assertVersion(5 to 0, "claude-fable-5")
        assertVersion(5 to 0, "claude-opus-5")
        assertVersion(5 to 0, "claude-sonnet-5")
        assertVersion(5 to 0, "claude-haiku-5")
        assertVersion(6 to 0, "claude-sonnet-6")
    }

    @Test
    fun `a real version pair still beats the single-segment reading`() {
        assertVersion(3 to 5, "claude-3-5-sonnet")
        assertVersion(3 to 7, "claude-3-7-sonnet")
        assertVersion(4 to 6, "claude-sonnet-4-6")
        assertVersion(4 to 8, "claude-opus-4-8")
        assertVersion(4 to 1, "claude-opus-4-1")
        assertTrue(
            "the minor must be the second segment, never 0 — that is the greedy match",
            parseClaudeVersion("claude-3-5-sonnet")!!.second == 5,
        )
    }

    @Test
    fun `the minor separator may be a hyphen or a dot`() {
        assertVersion(4 to 8, "claude-opus-4-8")
        assertVersion(4 to 8, "claude-opus-4.8")
        assertVersion(3 to 5, "claude-3.5-sonnet")
        assertVersion(4 to 5, "anthropic/claude-sonnet-4.5")
    }


    @Test
    fun `a non-Claude id is null however it is versioned`() {
        for (id in listOf(
            "gpt-5.3", "gemini-3-pro-preview", "deepseek-v4", "grok-4-20",
            "mimo-v2.5", "o3-mini", "qwen3-32b", "",
        )) {
            assertNull("non-Claude id must not parse: $id", parseClaudeVersion(id))
        }
    }

    @Test
    fun `a Claude id with no version digits is null`() {
        assertNull(parseClaudeVersion("claude"))
        assertNull(parseClaudeVersion("claude-instant"))
        assertNull(parseClaudeVersion("claude-latest"))
    }

    @Test
    fun `the parse is case-insensitive`() {
        assertVersion(5 to 0, "Claude-Fable-5")
        assertVersion(4 to 6, "CLAUDE-SONNET-4-6")
    }

    @Test
    fun `a date-stamped id parses on its version pair, not on the stamp`() {
        assertVersion(3 to 5, "claude-3-5-sonnet-20241022")
        assertVersion(4 to 5, "claude-sonnet-4-5-20250929")
        assertVersion(4 to 8, "claude-opus-4-8-20260115")
    }

    @Test
    fun `namespaced and suffixed vendor ids read their version segment`() {
        assertVersion(4 to 5, "us.anthropic.claude-sonnet-4-5-v1:0")
        assertVersion(3 to 5, "anthropic.claude-3-5-sonnet-20240620-v1:0")
        assertVersion(5 to 0, "anthropic/claude-opus-5")
    }


    @Test
    fun `the production consumers agree with the ported parse at the 4_6 boundary`() {
        val below = listOf("claude-sonnet-4-5", "claude-opus-4-1", "claude-3-7-sonnet", "claude-3-5-sonnet")
        val atOrAbove = listOf("claude-sonnet-4-6", "claude-opus-4-8", "claude-fable-5", "claude-opus-5")

        for (id in below) {
            val (major, minor) = parseClaudeVersion(id)!!
            assertFalse(
                "port says ${major}.$minor < 4.6, so production must accept temperature: $id",
                AnthropicProvider.modelRejectsTemperature(id),
            )
            assertFalse(
                "port says ${major}.$minor < 4.6, so production must use legacy budget thinking: $id",
                AnthropicProvider.modelUsesAdaptiveThinking(id),
            )
        }
        for (id in atOrAbove) {
            val (major, minor) = parseClaudeVersion(id)!!
            assertTrue(
                "port says ${major}.$minor >= 4.6, so production must reject temperature: $id",
                AnthropicProvider.modelRejectsTemperature(id),
            )
            assertTrue(
                "port says ${major}.$minor >= 4.6, so production must use adaptive thinking: $id",
                AnthropicProvider.modelUsesAdaptiveThinking(id),
            )
        }
    }

    @Test
    fun `the thinking-toggle threshold is 3_7, distinct from the 4_6 threshold`() {
        for (id in listOf("claude-3-7-sonnet", "claude-sonnet-4-5", "claude-sonnet-4-6", "claude-fable-5", "claude-opus-5")) {
            assertTrue("the toggle must be offered for $id", AnthropicProvider.supportsThinking(id))
        }
        for (id in listOf("claude-3-5-sonnet", "claude-3-haiku", "claude-2-1")) {
            assertTrue(
                "…and withheld below 3.7: $id",
                !AnthropicProvider.supportsThinking(id),
            )
        }
        assertFalse("a non-Claude id never gets the Anthropic toggle", AnthropicProvider.supportsThinking("gpt-5.3"))
    }

    @Test
    fun `the parse selects the thinking wire shape, adaptive versus legacy`() {
        for (id in listOf("claude-fable-5", "claude-opus-5", "claude-sonnet-4-6")) {
            val on = com.openminis.app.provider.thinking.ThinkingRuleResolver
                .anthropicThinkingShape(id, true, ThinkingLevel.HIGH, 8192)
            assertEquals("$id must take adaptive effort", mapOf("effort" to "high"), on)
        }
        for (id in listOf("claude-sonnet-4-6", "claude-opus-4-8")) {
            assertEquals(
                "$id thinks by default, so OFF must be stated explicitly",
                mapOf("disabled" to true),
                com.openminis.app.provider.thinking.ThinkingRuleResolver
                    .anthropicThinkingShape(id, true, ThinkingLevel.OFF, 8192),
            )
        }
        for (id in listOf("claude-fable-5", "claude-opus-5", "claude-sonnet-5", "claude-sonnet-5-5", "claude-opus-5-5", "claude-fable-5-1")) {
            assertEquals(
                "Claude 5 OFF must carry the explicit off intent: $id",
                mapOf("disabled" to true),
                com.openminis.app.provider.thinking.ThinkingRuleResolver
                    .anthropicThinkingShape(id, true, ThinkingLevel.OFF, 8192),
            )
        }
        val legacyOn = com.openminis.app.provider.thinking.ThinkingRuleResolver
            .anthropicThinkingShape("claude-sonnet-4-5", true, ThinkingLevel.HIGH, 8192)
        assertTrue("a pre-4.6 model takes a token budget: $legacyOn", legacyOn.containsKey("budget_tokens"))
        val legacyOff = com.openminis.app.provider.thinking.ThinkingRuleResolver
            .anthropicThinkingShape("claude-sonnet-4-5", true, ThinkingLevel.OFF, 8192)
        assertTrue("…and sends nothing at OFF: $legacyOff", legacyOff.isEmpty())
    }

    @Test
    fun `off thinking follows the per-model table`() {
        val off = AnthropicProvider.Companion::offThinking
        for (id in listOf("claude-sonnet-4-6", "claude-opus-4-7", "claude-opus-4.8", "claude-opus-4-99", "claude-opus-4-8", "claude-opus-4-6")) {
            assertEquals("4.6–4.x: thinking.type=disabled ($id)", AnthropicProvider.OffThinking.DISABLED, off(id))
        }
        for (id in listOf("claude-sonnet-5", "claude-sonnet-5-20260401")) {
            assertEquals("Sonnet 5 accepts disabled ($id)", AnthropicProvider.OffThinking.DISABLED, off(id))
        }
        for (id in listOf("claude-sonnet-5-5", "anthropic/claude-sonnet-5.5")) {
            assertEquals("Sonnet 5.5 400s on disabled → between_tools ($id)", AnthropicProvider.OffThinking.BETWEEN_TOOLS, off(id))
        }
        for (id in listOf("claude-fable-5", "claude-opus-5", "claude-opus-5-5", "claude-fable-5-1", "claude-mythos-5", "claude-sonnet-6", "claude-sonnet-5-7")) {
            assertEquals(
                "always-on / tool-leak / unknown 5+ → adaptive + low, never disabled ($id)",
                AnthropicProvider.OffThinking.LOW_EFFORT,
                off(id),
            )
        }
        for (id in listOf("claude-sonnet-4-5", "claude-3-5-sonnet", "claude-3-7-sonnet", "claude-opus-4-20250514")) {
            assertEquals("≤ 4.5 omits the field — off is already its default ($id)", AnthropicProvider.OffThinking.OMIT, off(id))
        }
        for (id in listOf("gpt-5", "MiniMax-M3", "deepseek-v4")) {
            assertEquals("non-Claude ids never trip it ($id)", AnthropicProvider.OffThinking.OMIT, off(id))
        }
        for (id in listOf("claude-sonnet-4-6", "claude-fable-5", "claude-sonnet-5-5", "claude-opus-5-5")) {
            assertTrue(
                "every adaptive model states off explicitly: $id must not fall to OMIT",
                AnthropicProvider.modelUsesAdaptiveThinking(id) && off(id) != AnthropicProvider.OffThinking.OMIT,
            )
        }
    }


    @Test
    fun `the production parser still carries the ported regex and defaults`() {
        val src = ProductionSources.read("provider/anthropic/AnthropicProvider.kt")
        assertTrue(
            "the ported regex literal is gone from AnthropicProvider — re-port it",
            src.contains("""Regex(""${'"'}[-/]?(\d+)(?:[-.](\d{1,2}))?(?:${'$'}|[^0-9])""${'"'})"""),
        )
        assertTrue(
            "the minor group must stay capped at 1-2 digits — an uncapped group swallows a " +
                "snapshot stamp and reads claude-sonnet-4-20250514 as (4, 20250514)",
            src.contains("""(?:[-.](\d{1,2}))?"""),
        )
        assertTrue(
            "the search must stay anchored AFTER the word claude, or a digit-bearing namespace " +
                "(v2-gateway/claude-opus-5) is read as the version",
            src.contains("val afterClaude = lower.substring(claudeAt + \"claude\".length)") &&
                src.contains("regex.find(afterClaude)"),
        )
        assertTrue(
            "the minor segment must stay OPTIONAL with a 0 default — this is the 5-series fix",
            src.contains("val minor = match.groupValues[2].toIntOrNull() ?: 0"),
        )
        assertTrue(
            "the non-Claude gate must stay ahead of the regex (now expressed as the anchor index)",
            src.contains("val claudeAt = lower.indexOf(\"claude\")") &&
                src.contains("if (claudeAt < 0) return null"),
        )
        assertTrue("temperature threshold moved", src.contains("return major > 4 || (major == 4 && minor >= 6)"))
        assertTrue(
            "offThinking must keep the ported per-model table ([T-claude5-thinking-off])",
            src.contains(
                "            if (major == 4) return OffThinking.DISABLED\n" +
                    "            if (major == 5 && modelId.lowercase().contains(\"sonnet\")) {\n" +
                    "                if (minor == 0) return OffThinking.DISABLED\n" +
                    "                if (minor == 5) return OffThinking.BETWEEN_TOOLS\n" +
                    "            }\n" +
                    "            return OffThinking.LOW_EFFORT",
            ),
        )
        assertTrue(
            "the request builder must encode OFF per model",
            src.contains("when (offThinking(model.id)) {") &&
                src.contains("body.put(\"thinking\", JSONObject().put(\"type\", \"between_tools\"))") &&
                src.contains("body.put(\"output_config\", JSONObject().put(\"effort\", \"low\"))"),
        )
        val resolver = ProductionSources.read("provider/thinking/ThinkingRuleResolver.kt")
        assertTrue(
            "the resolver's OFF shape must state off for every non-legacy model",
            resolver.contains("AnthropicProvider.offThinking(modelId) ==") &&
                resolver.contains("AnthropicProvider.OffThinking.OMIT"),
        )
        assertTrue(
            "the thinking-toggle threshold moved off 3.7",
            src.contains("return major > 4 || major == 4 || (major == 3 && minor >= 7)"),
        )
    }

    @Test
    fun `a digit-bearing namespace before the word claude is not read as the version`() {
        assertVersion(5 to 0, "v2-gateway/claude-opus-5")
        assertVersion(4 to 5, "inst-53/claude-haiku-4-5")
        assertVersion(5 to 0, "inst-7/anthropic/claude-sonnet-5")
        assertVersion(5 to 0, "anthropic/claude-opus-5")

        assertTrue(
            "a namespaced 5-series id must reject temperature",
            AnthropicProvider.modelRejectsTemperature("v2-gateway/claude-opus-5"),
        )
        assertTrue(
            "…and use adaptive thinking",
            AnthropicProvider.modelUsesAdaptiveThinking("v2-gateway/claude-opus-5"),
        )
    }

    @Test
    fun `a single-segment version does not swallow a trailing date stamp as its minor`() {
        assertVersion(5 to 0, "claude-opus-5-20260401")
        assertVersion(5 to 0, "claude-sonnet-5-20260101")
        assertVersion(4 to 8, "claude-opus-4-8-20260115")
        assertVersion(3 to 5, "claude-3-5-sonnet-20241022")

        for (id in listOf(
            "claude-sonnet-4-20250514",
            "claude-opus-4-20250514",
            "anthropic/claude-sonnet-4-20250514",
        )) {
            assertVersion(4 to 0, id)
            assertFalse(
                "a Claude 4.0 snapshot id must keep temperature: $id",
                AnthropicProvider.modelRejectsTemperature(id),
            )
            assertFalse(
                "…and must use legacy budget thinking, not adaptive effort: $id",
                AnthropicProvider.modelUsesAdaptiveThinking(id),
            )
        }

        val dated = "claude-opus-5-20260401"
        assertTrue("a dated 5-series id must still reject temperature", AnthropicProvider.modelRejectsTemperature(dated))
        assertTrue("…and still use adaptive thinking", AnthropicProvider.modelUsesAdaptiveThinking(dated))
        assertTrue("…and still offer the toggle", AnthropicProvider.supportsThinking(dated))
    }
}
