package com.openminis.app.data.model

import com.openminis.app.backup.Iso8601MillisSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class SubAgentUpdatedAtIso8601Test {

    private val json = Json { ignoreUnknownKeys = true }

    private fun utcFormatter() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Test
    fun `decodes the real ISO8601 string payload that previously crashed the whole file`() {
        val wire = """
            {"id":"builtin.general","name":"General Sub Agent","description":"Sub Agent",
             "sortOrder":0,"updatedAt":"2026-09-06T11:41:21Z"}
        """.trimIndent()

        val def = json.decodeFromString(SubAgentDefinition.serializer(), wire)

        val expectedMillis = utcFormatter().parse("2026-09-06T11:41:21Z")!!.time
        assertEquals(expectedMillis, def.updatedAt)
    }

    @Test
    fun `decodes the legacy plain numeric epoch millis payload`() {
        val epochMillis = 1_757_159_281_000L
        val wire = """
            {"id":"custom-1","name":"Researcher","description":"desc",
             "sortOrder":1,"updatedAt":$epochMillis}
        """.trimIndent()

        val def = json.decodeFromString(SubAgentDefinition.serializer(), wire)

        assertEquals(epochMillis, def.updatedAt)
    }

    @Test
    fun `ISO8601 string and equivalent numeric epoch decode to the same value`() {
        val instantMillis = utcFormatter().parse("2026-01-15T03:04:05Z")!!.time

        val stringWire = """
            {"id":"a","name":"A","description":"d","sortOrder":0,
             "updatedAt":"2026-01-15T03:04:05Z"}
        """.trimIndent()
        val numericWire = """
            {"id":"a","name":"A","description":"d","sortOrder":0,
             "updatedAt":$instantMillis}
        """.trimIndent()

        val fromString = json.decodeFromString(SubAgentDefinition.serializer(), stringWire)
        val fromNumeric = json.decodeFromString(SubAgentDefinition.serializer(), numericWire)

        assertEquals(instantMillis, fromString.updatedAt)
        assertEquals(fromNumeric.updatedAt, fromString.updatedAt)
    }

    @Test
    fun `encoding a SubAgentDefinition writes updatedAt as an ISO8601 string`() {
        val millis = utcFormatter().parse("2026-03-01T00:00:00Z")!!.time
        val def = SubAgentDefinition(
            id = "x",
            name = "X",
            description = "d",
            sortOrder = 0,
            updatedAt = millis,
        )

        val encoded = json.encodeToString(SubAgentDefinition.serializer(), def)

        assertTrue("expected an ISO-8601 string on the wire, got: $encoded", "\"2026-03-01T00:00:00Z\"" in encoded)

        val decoded = json.decodeFromString(SubAgentDefinition.serializer(), encoded)
        assertEquals(millis, decoded.updatedAt)
    }

    @Test
    fun `a roster array containing an ISO8601 updatedAt decodes entirely`() {
        val wire = """
            [
              {"id":"builtin.general","name":"General Sub Agent","description":"Sub Agent",
               "sortOrder":0,"updatedAt":"2026-09-06T11:41:21Z"},
              {"id":"custom-1","name":"Researcher","description":"desc",
               "sortOrder":1,"updatedAt":1700000000000}
            ]
        """.trimIndent()

        val roster = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(SubAgentDefinition.serializer()),
            wire,
        )

        assertEquals(2, roster.size)
        assertEquals("builtin.general", roster[0].id)
        assertEquals("custom-1", roster[1].id)
        assertEquals(1_700_000_000_000L, roster[1].updatedAt)
    }
}
