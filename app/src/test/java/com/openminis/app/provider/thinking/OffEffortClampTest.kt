package com.openminis.app.provider.thinking

import org.junit.Assert.assertEquals
import org.junit.Test

class OffEffortClampTest {

    private val gpt6Astra = listOf("low", "medium", "high", "xhigh", "max")

    @Test
    fun `the reported case snaps none to the lowest supported tier`() {
        assertEquals("low", ThinkingRuleResolver.clampOffEffort("none", gpt6Astra))
    }

    @Test
    fun `a model that supports none keeps none`() {
        assertEquals(
            "none",
            ThinkingRuleResolver.clampOffEffort("none", listOf("none", "low", "high")),
        )
    }

    @Test
    fun `no declared values is left exactly as-is`() {
        assertEquals("none", ThinkingRuleResolver.clampOffEffort("none", null))
        assertEquals("none", ThinkingRuleResolver.clampOffEffort("none", emptyList()))
        assertEquals("minimal", ThinkingRuleResolver.clampOffEffort("minimal", null))
    }

    @Test
    fun `OFF never escalates to a high tier`() {
        assertEquals("high", ThinkingRuleResolver.clampEffort("none", listOf("high", "max")))
        assertEquals("high", ThinkingRuleResolver.clampOffEffort("none", listOf("high", "max")))
        assertEquals("low", ThinkingRuleResolver.clampOffEffort("none", listOf("max", "low")))
    }

    @Test
    fun `declaration order does not matter`() {
        assertEquals("low", ThinkingRuleResolver.clampOffEffort("none", listOf("max", "high", "low")))
        assertEquals("low", ThinkingRuleResolver.clampOffEffort("none", gpt6Astra.reversed()))
    }

    @Test
    fun `the Ark minimal off-tier clamps too`() {
        assertEquals(
            "low",
            ThinkingRuleResolver.clampOffEffort("minimal", listOf("low", "medium", "high")),
        )
    }

    @Test
    fun `unrecognisable declarations are not guessed at`() {
        assertEquals("none", ThinkingRuleResolver.clampOffEffort("none", listOf("turbo", "ultra-max")))
    }
}
