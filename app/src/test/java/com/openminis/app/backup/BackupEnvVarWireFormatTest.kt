package com.openminis.app.backup

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupEnvVarWireFormatTest {

    private val listSerializer = ListSerializer(BackupEnvVarMeta.serializer())

    @Test
    fun `createdAt is written as an ISO-8601 string`() {
        val millis = 1787270400000L
        val json = BackupFormat.json.encodeToString(
            listSerializer,
            listOf(BackupEnvVarMeta(id = "e1", key = "FOO", note = "n", createdAt = millis)),
        )
        assertTrue(
            "createdAt should be a quoted ISO-8601 string, was: $json",
            json.contains("\"createdAt\":\"2026-08-21T00:00:00Z\""),
        )
        assertTrue(
            "createdAt must not be a bare number, was: $json",
            !json.contains("\"createdAt\":1787270400000"),
        )
    }

    @Test
    fun `importer reads an ISO-8601 string createdAt`() {
        val json = """[{"id":"e1","key":"FOO","note":"","createdAt":"2026-08-21T00:00:00Z"}]"""
        val metas = BackupFormat.json.decodeFromString(listSerializer, json)
        assertEquals(1, metas.size)
        assertEquals("FOO", metas[0].key)
        assertEquals(1787270400000L, metas[0].createdAt)
    }

    @Test
    fun `importer still reads legacy numeric epoch-millis createdAt`() {
        val json = """[{"id":"e1","key":"BAR","note":"","createdAt":1787270400000}]"""
        val metas = BackupFormat.json.decodeFromString(listSerializer, json)
        assertEquals(1, metas.size)
        assertEquals("BAR", metas[0].key)
        assertEquals(1787270400000L, metas[0].createdAt)
    }

    @Test
    fun `missing createdAt defaults to zero`() {
        val json = """[{"id":"e1","key":"BAZ"}]"""
        val metas = BackupFormat.json.decodeFromString(listSerializer, json)
        assertEquals(0L, metas[0].createdAt)
    }

    @Test
    fun `provider instance createdAt is written as ISO and reads both forms`() {
        val millis = 1787270400000L
        val inst = com.openminis.app.data.model.ProviderInstance(
            id = "i1",
            label = "L",
            providerType = com.openminis.app.data.model.ProviderType.openAI,
            credentialType = com.openminis.app.data.model.ProviderCredential.apiKey,
            createdAt = millis,
        )
        val json = BackupFormat.json.encodeToString(
            com.openminis.app.data.model.ProviderInstance.serializer(), inst,
        )
        assertTrue(
            "provider createdAt should be an ISO string, was: $json",
            json.contains("\"createdAt\":\"2026-08-21T00:00:00Z\""),
        )
        val back = BackupFormat.json.decodeFromString(
            com.openminis.app.data.model.ProviderInstance.serializer(), json,
        )
        assertEquals(millis, back.createdAt)
        val legacy = """{"id":"i1","label":"L","providerType":"openAI",""" +
            """"credentialType":"apiKey","createdAt":1787270400000}"""
        val old = BackupFormat.json.decodeFromString(
            com.openminis.app.data.model.ProviderInstance.serializer(), legacy,
        )
        assertEquals(millis, old.createdAt)
    }

    @Test
    fun `model entry userModifiedAt is ISO, nullable, and reads legacy numbers`() {
        val millis = 1787270400000L
        val ser = Iso8601MillisNullableSerializer
        assertEquals("\"2026-08-21T00:00:00Z\"", BackupFormat.json.encodeToString(ser, millis))
        assertEquals("null", BackupFormat.json.encodeToString(ser, null))
        assertEquals(millis, BackupFormat.json.decodeFromString(ser, "\"2026-08-21T00:00:00Z\""))
        assertEquals(millis, BackupFormat.json.decodeFromString(ser, "1787270400000"))
        assertEquals(null, BackupFormat.json.decodeFromString(ser, "null"))
    }

    @Test
    fun `round-trip preserves createdAt at second precision`() {
        val millis = 1787270400000L
        val encoded = BackupFormat.json.encodeToString(
            listSerializer, listOf(BackupEnvVarMeta(id = "e1", key = "K", createdAt = millis)),
        )
        val decoded = BackupFormat.json.decodeFromString(listSerializer, encoded)
        assertEquals(millis, decoded[0].createdAt)
    }
}
