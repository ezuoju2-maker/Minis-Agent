package com.openminis.app.provider.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantWireTest {

    @Test
    fun moonshotEndpointsAreStrict() {
        assertTrue(AssistantWire.strictBlankContent("https://api.moonshot.ai/v1", "kimi-k2"))
        assertTrue(AssistantWire.strictBlankContent("https://api.example/v1", "kimi-k2-turbo"))
    }

    @Test
    fun openAiEndpointsAreTolerant() {
        assertFalse(AssistantWire.strictBlankContent("https://api.openai.com/v1", "gpt-4o"))
        assertFalse(AssistantWire.strictBlankContent("https://example.invalid/v1", "deepseek-chat"))
    }

    @Test
    fun blankContentOmittedOnlyForStrictEndpoints() {
        assertNull(AssistantWire.contentField("", hasToolCalls = true, strictBlankContent = true))
        assertEquals("", AssistantWire.contentField("", hasToolCalls = true, strictBlankContent = false))
    }

    @Test
    fun visibleTextAlwaysKept() {
        assertEquals("done", AssistantWire.contentField("done", hasToolCalls = true, strictBlankContent = true))
        assertEquals("done", AssistantWire.contentField("done", hasToolCalls = false, strictBlankContent = true))
    }

    @Test
    fun blankContentKeptWithoutToolCalls() {
        assertEquals("", AssistantWire.contentField("", hasToolCalls = false, strictBlankContent = true))
    }
}
