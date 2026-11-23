package com.openminis.app.data

import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThinkingLevelTest {

    @Test
    fun decoded_returnsKnownLevels() {
        assertEquals(ThinkingLevel.MAX, ThinkingLevel.decoded("MAX"))
        assertEquals(ThinkingLevel.ULTRA, ThinkingLevel.decoded("ULTRA"))
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.decoded("HIGH"))
        assertEquals(ThinkingLevel.OFF, ThinkingLevel.decoded("OFF"))
    }

    @Test
    fun decoded_unknownValueClampsToXHigh_neverThrows() {
        assertEquals(ThinkingLevel.XHIGH, ThinkingLevel.decoded("SUPREME"))
        assertEquals(ThinkingLevel.XHIGH, ThinkingLevel.decoded(""))
        assertEquals(ThinkingLevel.XHIGH, ThinkingLevel.decoded("garbage-token"))
    }

    @Test
    fun rank_followsDeclarationOrder() {
        assertEquals(0, ThinkingLevel.OFF.rank)
        assertEquals(4, ThinkingLevel.XHIGH.rank)
        assertEquals(5, ThinkingLevel.MAX.rank)
        assertEquals(6, ThinkingLevel.ULTRA.rank)
        assertEquals(
            ThinkingLevel.HIGH,
            minOf(ThinkingLevel.ULTRA, ThinkingLevel.HIGH, compareBy { it.rank }),
        )
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    @Test
    fun modelGroup_unknownThinkingLevel_clampsToXHigh_notThrow() {
        val wire = """{"id":"g1","name":"G","memberEntryIds":[],"defaultThinkingLevel":"SUPREME"}"""
        val group = json.decodeFromString(ModelGroup.serializer(), wire)
        assertEquals(ThinkingLevel.XHIGH, group.defaultThinkingLevel)
        assertEquals("G", group.name)
    }

    @Test
    fun modelOverrides_unknownMaxThinkingLevel_clampsToXHigh() {
        val wire = """{"maxThinkingLevel":"SUPREME","displayName":"X"}"""
        val ov = json.decodeFromString(ModelOverrides.serializer(), wire)
        assertEquals(ThinkingLevel.XHIGH, ov.maxThinkingLevel)
        assertEquals("X", ov.displayName)
    }

    @Test
    fun decoded_acceptsIosRawValues() {
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.decoded("high"))
        assertEquals(ThinkingLevel.MAX, ThinkingLevel.decoded("max"))
        assertEquals(ThinkingLevel.OFF, ThinkingLevel.decoded("off"))
        assertEquals(null, ThinkingLevel.parseOrNull("SUPREME"))
    }

    @Test
    fun modelOverrides_knownMaxThinkingLevel_roundTrips() {
        val wire = """{"maxThinkingLevel":"MAX"}"""
        val ov = json.decodeFromString(ModelOverrides.serializer(), wire)
        assertEquals(ThinkingLevel.MAX, ov.maxThinkingLevel)
    }
}
