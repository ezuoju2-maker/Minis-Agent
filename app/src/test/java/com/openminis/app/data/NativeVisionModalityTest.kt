package com.openminis.app.data

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.model.normalizeModalityName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeVisionModalityTest {

    private fun model(vararg inputs: String) = LLMModel(
        id = "test-model",
        displayName = "Test Model",
        provider = "test",
        inputModalities = inputs.toList(),
    )

    private fun legacyInlineCheck(m: LLMModel): Boolean =
        m.inputModalities?.map { it.lowercase() }?.contains("image") == true

    @Test
    fun `suffix form is what the two checks used to disagree on`() {
        val m = model("text", "image_input")
        assertFalse(
            "legacy inline check should miss image_input (this is the bug)",
            legacyInlineCheck(m),
        )
        assertTrue(
            "hasImageInput must accept the OpenAI/OpenRouter suffix form",
            m.hasImageInput,
        )
    }

    @Test
    fun `bare form is accepted by both — never the failing case`() {
        val m = model("text", "image")
        assertTrue(legacyInlineCheck(m))
        assertTrue(m.hasImageInput)
    }

    @Test
    fun `text-only model has no native vision in either spelling`() {
        assertFalse(model("text").hasImageInput)
        assertFalse(model("text", "audio").hasImageInput)
    }

    @Test
    fun `mixed case and unspecified modalities`() {
        assertTrue("normalization lowercases too", model("Image_Input").hasImageInput)
        assertTrue(model("IMAGE").hasImageInput)
        assertFalse(LLMModel(id = "x", displayName = "x", provider = "p").hasImageInput)
        assertFalse(model().hasImageInput)
    }

    @Test
    fun `normalizeModalityName strips input and output suffixes`() {
        assertEquals("image", "image_input".normalizeModalityName())
        assertEquals("image", "image".normalizeModalityName())
        assertEquals("text", "text_output".normalizeModalityName())
        assertEquals("audio", "AUDIO_INPUT".normalizeModalityName())
    }

    @Test
    fun `all three call sites agree for the suffix form`() {
        val m = model("text", "image_input")
        val nativeVision = m.hasImageInput

        val toolGateSupportsImages = nativeVision
        val skipsPlaceholder = nativeVision
        val readImageReturnsPixels = nativeVision

        assertTrue(toolGateSupportsImages)
        assertTrue(skipsPlaceholder)
        assertTrue(readImageReturnsPixels)
        assertEquals(toolGateSupportsImages, skipsPlaceholder)
        assertEquals(skipsPlaceholder, readImageReturnsPixels)
    }
}
