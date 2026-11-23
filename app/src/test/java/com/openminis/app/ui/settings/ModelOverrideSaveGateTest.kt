package com.openminis.app.ui.settings

import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelOverrideSaveGateTest {


    private fun oldSupportsReasoning(thinkingEnabled: Boolean, baseSupports: Boolean?): Boolean? =
        thinkingEnabled.takeIf { it != (baseSupports ?: false) }

    private fun oldModalities(new: List<String>, base: List<String>): List<String>? =
        if (new.toSet() != base.toSet()) new else null

    private fun oldDisplayName(typed: String, baseName: String): String? =
        typed.trim().takeIf { it.isNotEmpty() && it != baseName }

    private fun save(
        existing: ModelOverrides = ModelOverrides(),
        displayNameText: String = "",
        displayNameTouched: Boolean = false,
        maxOutputTokensText: String = "",
        contextWindowText: String = "",
        thinkingEnabled: Boolean = false,
        thinkingTouched: Boolean = false,
        inputModalities: List<String> = emptyList(),
        inputModalitiesTouched: Boolean = false,
        outputModalities: List<String> = emptyList(),
        outputModalitiesTouched: Boolean = false,
    ) = buildModelOverrides(
        existing = existing,
        displayNameText = displayNameText,
        displayNameTouched = displayNameTouched,
        maxOutputTokensText = maxOutputTokensText,
        contextWindowText = contextWindowText,
        thinkingEnabled = thinkingEnabled,
        thinkingTouched = thinkingTouched,
        inputModalities = inputModalities,
        inputModalitiesTouched = inputModalitiesTouched,
        outputModalities = outputModalities,
        outputModalitiesTouched = outputModalitiesTouched,
    )


    @Test
    fun `a context window equal to the current auto value is still recorded`() {
        val result = save(contextWindowText = "400000")
        assertEquals(400_000, result.contextWindow)
    }

    @Test
    fun `an empty context window clears the override and returns to auto`() {
        val result = save(existing = ModelOverrides(contextWindow = 400_000), contextWindowText = "")
        assertNull(result.contextWindow)
    }

    @Test
    fun `a whitespace-only context window also returns to auto`() {
        val result = save(existing = ModelOverrides(contextWindow = 400_000), contextWindowText = "   ")
        assertNull(result.contextWindow)
    }

    @Test
    fun `a non-positive context window is rejected rather than stored`() {
        assertNull(save(contextWindowText = "0").contextWindow)
        assertNull(save(contextWindowText = "abc").contextWindow)
    }


    @Test
    fun `a thinking toggle set to the same value as base is still recorded`() {
        val result = save(thinkingEnabled = true, thinkingTouched = true)
        assertEquals(true, result.supportsReasoning)

        assertNull("old rule must fail here", oldSupportsReasoning(true, baseSupports = true))
    }

    @Test
    fun `an untouched thinking toggle records nothing and keeps inheriting`() {
        val result = save(thinkingEnabled = true, thinkingTouched = false)
        assertNull(result.supportsReasoning)
        assertTrue(result.isEmpty)
    }

    @Test
    fun `an existing thinking override survives the vendor catching up`() {
        val existing = ModelOverrides(supportsReasoning = true)
        val result = save(existing = existing, thinkingEnabled = true, thinkingTouched = false)
        assertEquals(true, result.supportsReasoning)

        assertNull("old rule must fail here", oldSupportsReasoning(true, baseSupports = true))
    }

    @Test
    fun `turning the thinking toggle off is recorded as an explicit false`() {
        val result = save(thinkingEnabled = false, thinkingTouched = true)
        assertEquals(false, result.supportsReasoning)
    }


    @Test
    fun `a touched modality set equal to base is still recorded`() {
        val base = listOf("image", "pdf")
        val result = save(inputModalities = base, inputModalitiesTouched = true)
        assertEquals(base, result.inputModalities)

        assertNull("old rule must fail here", oldModalities(base, base))
    }

    @Test
    fun `an untouched modality set records nothing`() {
        val result = save(inputModalities = listOf("image"), inputModalitiesTouched = false)
        assertNull(result.inputModalities)
        assertTrue(result.isEmpty)
    }

    @Test
    fun `an existing modality override survives an untouched save`() {
        val existing = ModelOverrides(inputModalities = listOf("image"))
        val result = save(existing = existing, inputModalities = listOf("image"), inputModalitiesTouched = false)
        assertEquals(listOf("image"), result.inputModalities)
    }

    @Test
    fun `clearing every input switch records an empty list not null`() {
        val existing = ModelOverrides(inputModalities = listOf("image"))
        val result = save(existing = existing, inputModalities = emptyList(), inputModalitiesTouched = true)
        assertNotNull(result.inputModalities)
        assertEquals(emptyList<String>(), result.inputModalities)
    }

    @Test
    fun `input and output modalities are tracked independently`() {
        val result = save(
            inputModalities = listOf("image"),
            inputModalitiesTouched = true,
            outputModalities = listOf("audio"),
            outputModalitiesTouched = false,
        )
        assertEquals(listOf("image"), result.inputModalities)
        assertNull(result.outputModalities)
    }


    @Test
    fun `a display name equal to the base name is still recorded when touched`() {
        val result = save(displayNameText = "GPT-5", displayNameTouched = true)
        assertEquals("GPT-5", result.displayName)

        assertNull("old rule must fail here", oldDisplayName("GPT-5", baseName = "GPT-5"))
    }

    @Test
    fun `an emptied display name clears the override`() {
        val existing = ModelOverrides(displayName = "My Name")
        val result = save(existing = existing, displayNameText = "  ", displayNameTouched = true)
        assertNull(result.displayName)
    }

    @Test
    fun `an untouched display name keeps the existing override`() {
        val existing = ModelOverrides(displayName = "My Name")
        val result = save(existing = existing, displayNameText = "My Name", displayNameTouched = false)
        assertEquals("My Name", result.displayName)
    }


    @Test
    fun `per-model tuning set elsewhere is not wiped by saving this screen`() {
        val existing = ModelOverrides(
            maxThinkingLevel = ThinkingLevel.HIGH,
            temperature = 0.0,
            topP = 0.9,
            customHeaders = mapOf("X-Test" to "1"),
        )
        val result = save(existing = existing, contextWindowText = "1000")

        assertEquals(ThinkingLevel.HIGH, result.maxThinkingLevel)
        assertEquals(0.0, result.temperature!!, 0.0)
        assertEquals(0.9, result.topP!!, 0.0)
        assertEquals(mapOf("X-Test" to "1"), result.customHeaders)
    }


    @Test
    fun `a fresh entry saved with no edits at all stays empty`() {
        assertTrue(save().isEmpty)
    }
}
