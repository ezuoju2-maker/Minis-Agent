package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageEditRoutingMatrixTest {

    private enum class Route {
        GENERATE,

        EDIT,

        CHAT_FALLTHROUGH,
    }

    private fun route(
        outputs: Set<String>,
        isOpenAICompatibleProvider: Boolean,
        providerType: String,
        isOAuth: Boolean,
        inputImageCount: Int,
    ): Route {
        val wantsImageOutput = "image" in outputs
        if (!wantsImageOutput || !isOpenAICompatibleProvider) return Route.CHAT_FALLTHROUGH
        if (providerType !in setOf("openAI", "openRouter", "xAI")) return Route.CHAT_FALLTHROUGH
        if (isOAuth) return Route.CHAT_FALLTHROUGH

        val isPureImageGenerator = "image" in outputs && "text" !in outputs
        if (inputImageCount > 0 && !isPureImageGenerator) return Route.CHAT_FALLTHROUGH

        return if (inputImageCount > 0) Route.EDIT else Route.GENERATE
    }

    private fun route(
        outputs: Set<String>,
        inputImageCount: Int,
        isOpenAICompatibleProvider: Boolean = true,
        providerType: String = "openAI",
        isOAuth: Boolean = false,
    ) = route(outputs, isOpenAICompatibleProvider, providerType, isOAuth, inputImageCount)


    @Test
    fun `pure image generator with one reference image routes to edits`() {
        assertEquals(Route.EDIT, route(outputs = setOf("image"), inputImageCount = 1))
    }

    @Test
    fun `pure image generator with multiple reference images routes to edits`() {
        assertEquals(Route.EDIT, route(outputs = setOf("image"), inputImageCount = 3))
    }

    @Test
    fun `openRouter and xAI image instances also reach edits`() {
        for (pt in listOf("openRouter", "xAI")) {
            assertEquals(
                "providerType=$pt should reach edits",
                Route.EDIT,
                route(outputs = setOf("image"), inputImageCount = 1, providerType = pt),
            )
        }
    }


    @Test
    fun `pure image generator without reference images still routes to generations`() {
        assertEquals(Route.GENERATE, route(outputs = setOf("image"), inputImageCount = 0))
    }

    @Test
    fun `mixed text and image model with reference image falls through to chat`() {
        assertEquals(
            Route.CHAT_FALLTHROUGH,
            route(outputs = setOf("text", "image"), inputImageCount = 1),
        )
    }

    @Test
    fun `mixed text and image model without reference image still uses images route`() {
        assertEquals(
            Route.GENERATE,
            route(outputs = setOf("text", "image"), inputImageCount = 0),
        )
    }

    @Test
    fun `text-only model with reference image is untouched by the image route`() {
        assertEquals(Route.CHAT_FALLTHROUGH, route(outputs = setOf("text"), inputImageCount = 1))
    }

    @Test
    fun `oauth instance with reference image never reaches edits`() {
        assertEquals(
            Route.CHAT_FALLTHROUGH,
            route(outputs = setOf("image"), inputImageCount = 1, isOAuth = true),
        )
    }

    @Test
    fun `non openai-compatible provider with reference image is unaffected`() {
        assertEquals(
            Route.CHAT_FALLTHROUGH,
            route(
                outputs = setOf("image"),
                inputImageCount = 1,
                isOpenAICompatibleProvider = false,
            ),
        )
        assertEquals(
            Route.CHAT_FALLTHROUGH,
            route(outputs = setOf("image"), inputImageCount = 1, providerType = "anthropic"),
        )
    }
}
