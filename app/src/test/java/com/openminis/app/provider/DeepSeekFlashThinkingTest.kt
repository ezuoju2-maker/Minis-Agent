package com.openminis.app.provider

import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.thinking.ThinkingRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekFlashThinkingTest {

    private fun claims(pattern: String, modelId: String): Boolean =
        ThinkingRule.Scope.ModelPattern(pattern).matches(modelId)


    @Test
    fun `the legacy pattern does not match the bare id`() {
        assertFalse(claims("*deepseek-v4*", "deepseek-flash"))
        assertFalse(claims("*deepseek-v4*", "deepseek-flash-lite"))
    }

    @Test
    fun `the new rule claims the bare id and its lite sibling`() {
        assertTrue(claims("deepseek-flash*", "deepseek-flash"))
        assertTrue(claims("deepseek-flash*", "deepseek-flash-lite"))
    }

    @Test
    fun `the legacy alias stays on the original rule`() {
        assertTrue(claims("*deepseek-v4*", "deepseek-v4-flash"))
        assertTrue(claims("*deepseek-v4*", "deepseek-v4-pro"))
        assertFalse(claims("deepseek-flash*", "deepseek-v4-flash"))
        assertFalse(claims("deepseek-flash*", "deepseek-v4-pro"))
    }

    @Test
    fun `chat and reasoner are NOT claimed`() {
        for (id in listOf("deepseek-chat", "deepseek-reasoner")) {
            assertFalse("`deepseek-flash*` must not claim $id", claims("deepseek-flash*", id))
            assertFalse("`*deepseek-v4*` must not claim $id", claims("*deepseek-v4*", id))
        }
    }

    @Test
    fun `dotted spellings normalise to the same claim`() {
        assertTrue(claims("deepseek-flash*", "deepseek.flash"))
        assertTrue(claims("*deepseek-v4*", "deepseek.v4.flash"))
    }


    @Test
    fun `the catalog caps deepseek at max, not the xhigh default`() {
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("deepseek-flash"))
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("deepseek-flash-lite"))
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("deepseek-v4-flash"))
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("deepseek-v4-pro"))
    }

    @Test
    fun `the catalog rule does not lift other vendors`() {
        assertEquals(ThinkingLevel.HIGH, ThinkingLevelCatalog.declaredMaxLevel("mimo-v2.5"))
        assertEquals(ThinkingLevel.HIGH, ThinkingLevelCatalog.declaredMaxLevel("seed-2.0"))
    }


    private val resolverSrc by lazy {
        java.io.File("src/main/java/com/openminis/app/provider/thinking/ThinkingRuleResolver.kt")
            .readText()
    }

    @Test
    fun `the sibling rule ships the same wire format and echo policy`() {
        fun block(scope: String, label: String): String {
            val scopeAt = resolverSrc.indexOf(scope)
            assertTrue("scope not found: $scope", scopeAt > 0)
            val from = resolverSrc.lastIndexOf("kind = ", scopeAt)
            assertTrue("no kind= above $scope", from > 0)
            val to = resolverSrc.indexOf(label, scopeAt)
            assertTrue("label not found after scope: $label", to > scopeAt)
            return resolverSrc.substring(from, to)
        }
        val legacy = block("ModelPattern(\"*deepseek-v4*\")", "deepseek-v4-official")
        val flash = block("ModelPattern(\"deepseek-flash*\")", "deepseek-flash-official")
        for ((name, b) in listOf("legacy" to legacy, "flash" to flash)) {
            assertTrue(
                "$name must use the sibling wire format",
                b.contains("ThinkingWireFormat.DeepSeekSibling"),
            )
            assertTrue(
                "$name must echo reasoning_content after tool use only",
                b.contains("ReasoningEchoPolicy(\"reasoning_content\"") &&
                    b.contains("AFTER_TOOL_USE_ONLY"),
            )
            assertTrue("$name must be an official-vendor rule", b.contains("Kind.OFFICIAL_VENDOR"))
        }
    }

    @Test
    fun `the rule is registered as a sibling, not by widening the old scope`() {
        assertTrue(
            "the legacy scope must be left intact",
            resolverSrc.contains("ModelPattern(\"*deepseek-v4*\")"),
        )
        assertTrue(
            "the new scope is anchored",
            resolverSrc.contains("ModelPattern(\"deepseek-flash*\")"),
        )
    }
}
