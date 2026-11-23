package com.openminis.app.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelOverridesCustomParamsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `legacy JSON without the new keys decodes with nulls`() {
        val legacy = """{"displayName":"Old Model","maxOutputTokens":4096}"""
        val decoded = json.decodeFromString<ModelOverrides>(legacy)

        assertEquals("Old Model", decoded.displayName)
        assertEquals(4096, decoded.maxOutputTokens)
        assertNull("absent key must stay null, not 0.0", decoded.temperature)
        assertNull(decoded.topP)
        assertNull(decoded.customHeaders)
        assertNull(decoded.extraBodyParams)
    }

    @Test
    fun `a completely empty legacy object decodes`() {
        val decoded = json.decodeFromString<ModelOverrides>("{}")
        assertTrue("an all-default overrides object is empty", decoded.isEmpty)
    }

    @Test
    fun `round trip preserves all four new fields`() {
        val original = ModelOverrides(
            displayName = "Tuned",
            temperature = 0.35,
            topP = 0.9,
            customHeaders = mapOf("X-Trace" to "abc", "X-Tier" to "pro"),
            extraBodyParams = JsonObject(
                mapOf(
                    "reasoning_effort" to JsonPrimitive("high"),
                    "seed" to JsonPrimitive(42),
                ),
            ),
        )

        val decoded = json.decodeFromString<ModelOverrides>(json.encodeToString(original))

        assertEquals(0.35, decoded.temperature!!, 1e-9)
        assertEquals(0.9, decoded.topP!!, 1e-9)
        assertEquals(mapOf("X-Trace" to "abc", "X-Tier" to "pro"), decoded.customHeaders)
        assertEquals("high", (decoded.extraBodyParams!!["reasoning_effort"] as JsonPrimitive).content)
        assertEquals(original, decoded)
    }

    @Test
    fun `temperature of zero survives and is not mistaken for absent`() {
        val decoded = json.decodeFromString<ModelOverrides>(
            json.encodeToString(ModelOverrides(temperature = 0.0)),
        )
        assertEquals(0.0, decoded.temperature!!, 1e-9)
        assertTrue("a zero temperature is a real override", !decoded.isEmpty)
    }

    @Test
    fun `isEmpty accounts for each new field on its own`() {
        assertTrue(ModelOverrides().isEmpty)
        assertTrue(!ModelOverrides(temperature = 0.7).isEmpty)
        assertTrue(!ModelOverrides(topP = 0.5).isEmpty)
        assertTrue(!ModelOverrides(customHeaders = mapOf("a" to "b")).isEmpty)
        assertTrue(
            !ModelOverrides(
                extraBodyParams = JsonObject(mapOf("k" to JsonPrimitive("v"))),
            ).isEmpty,
        )
    }

    @Test
    fun `unknown future keys are ignored rather than fatal`() {
        val future = """{"temperature":0.2,"someFutureKnob":{"a":1}}"""
        val decoded = json.decodeFromString<ModelOverrides>(future)
        assertEquals(0.2, decoded.temperature!!, 1e-9)
    }
}
