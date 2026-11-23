package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageInputPreflightTest {

    private fun model(provider: String, inputs: List<String>?) =
        LLMModel(id = "m", displayName = "M", provider = provider, inputModalities = inputs)

    @Test
    fun `a provider 400 on a request that carried images reads as an image rejection`() {
        assertTrue(ImageInputPreflight.isLikelyImageRejection(LLMError.ProviderError("bad request", httpStatus = 400), sentImageCount = 1))
    }

    @Test
    fun `other statuses, no images, or non-provider errors are not flagged`() {
        assertFalse(ImageInputPreflight.isLikelyImageRejection(LLMError.ProviderError("boom", httpStatus = 500), 1))
        assertFalse(ImageInputPreflight.isLikelyImageRejection(LLMError.ProviderError("bad request", httpStatus = 400), 0))
        assertFalse(ImageInputPreflight.isLikelyImageRejection(LLMError.ProviderError("no status"), 1))
        assertFalse(ImageInputPreflight.isLikelyImageRejection(IllegalStateException("x"), 1))
    }
}
