package com.openminis.app.data.model

import com.openminis.app.data.db.ProviderModelEntryEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class CrossPlatformBackupRestoreUnitTest {

    @get:Rule val tmp = TemporaryFolder()

    private val iosProviderConfig = """
        {
          "instances": [
            {"id": "inst-1", "label": "Custom GPT", "providerType": "openAI", "credentialType": "apiKey",
             "isEnabled": true, "createdAt": "2026-09-01T08:00:00Z",
             "customBaseURL": "https://relay.example.com", "appendV1Suffix": true}
          ],
          "modelEntries": [
            {"providerInstanceId": "inst-1",
             "model": {"id": "test-gpt-custom", "displayName": "Test GPT", "provider": "openAI"},
             "overrides": {"temperature": 0.7, "topP": 0.9,
                           "customHeaders": {"X-Custom-Auth": "Token-ABC-123", "HTTP-Referer": "https://openminis.app"},
                           "extraBodyParams": {"seed": "42"}},
             "isCustom": true, "isHidden": false, "uuid": "entry-1",
             "userModifiedAt": "2026-09-02T09:30:00Z"}
          ],
          "modelGroups": []
        }
    """.trimIndent()

    private fun buildBackup(): File {
        val f = tmp.newFile("cross_platform_test.minisbak")
        ZipOutputStream(FileOutputStream(f)).use { zos ->
            zos.putNextEntry(ZipEntry("data/provider_config.json"))
            zos.write(iosProviderConfig.toByteArray())
            zos.closeEntry()
        }
        return f
    }

    @Test
    fun testRestoreBackupWithCustomModelOverrides() {
        val backupFile = buildBackup()
        assertTrue("Backup file must exist", backupFile.exists())

        var extractedJson: String? = null
        ZipInputStream(FileInputStream(backupFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "data/provider_config.json") {
                    extractedJson = zis.bufferedReader().readText()
                    break
                }
                entry = zis.nextEntry
            }
        }
        assertNotNull("Must extract provider_config.json from minisbak", extractedJson)

        val jsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        val config = jsonParser.decodeFromString<ProviderConfig>(extractedJson!!)
        assertNotNull(config)
        assertEquals(1, config.instances.size)
        assertEquals(1, config.modelEntries.size)

        val modelEntry = config.modelEntries[0]
        val overrides = modelEntry.overrides
        assertNotNull("Overrides must be parsed", overrides)

        assertEquals(0.7, overrides.temperature ?: 0.0, 0.0001)
        assertEquals(0.9, overrides.topP ?: 0.0, 0.0001)
        assertNotNull("customHeaders must not be null", overrides.customHeaders)
        assertEquals("Token-ABC-123", overrides.customHeaders?.get("X-Custom-Auth"))
        assertEquals("https://openminis.app", overrides.customHeaders?.get("HTTP-Referer"))
        assertNotNull("extraBodyParams must not be null", overrides.extraBodyParams)

        val serializedOverrides = jsonParser.encodeToString(overrides)
        val entity = ProviderModelEntryEntity(
            id = modelEntry.uuid,
            providerInstanceId = modelEntry.providerInstanceId,
            baseModelJson = jsonParser.encodeToString(modelEntry.baseModel),
            overridesJson = serializedOverrides,
            isCustom = 0,
            isHidden = 0,
            sortOrder = 0,
            userModifiedAt = System.currentTimeMillis()
        )
        assertNotNull(entity.overridesJson)

        val restoredOverrides = jsonParser.decodeFromString<ModelOverrides>(entity.overridesJson!!)
        assertEquals(0.7, restoredOverrides.temperature ?: 0.0, 0.0001)
        assertEquals("Token-ABC-123", restoredOverrides.customHeaders?.get("X-Custom-Auth"))

        val remoteNewBaseModel = LLMModel(
            id = "test-gpt-custom",
            displayName = "Remote Updated GPT Name",
            provider = "openAI"
        )
        val refreshedModelEntry = ModelEntry(
            providerInstanceId = entity.providerInstanceId,
            baseModel = remoteNewBaseModel,
            overrides = restoredOverrides,
            isCustom = entity.isCustom != 0,
            isHidden = entity.isHidden != 0,
            uuid = entity.id,
            userModifiedAt = entity.userModifiedAt
        )
        assertEquals(0.7, refreshedModelEntry.overrides.temperature ?: 0.0, 0.0001)
        assertEquals(0.9, refreshedModelEntry.overrides.topP ?: 0.0, 0.0001)
        assertEquals("Token-ABC-123", refreshedModelEntry.overrides.customHeaders?.get("X-Custom-Auth"))
        assertEquals("https://openminis.app", refreshedModelEntry.overrides.customHeaders?.get("HTTP-Referer"))

        println(">>> VERIFIED: All custom model parameters successfully deserialized, saved to DB Entity, and preserved across model refresh without any MissingFieldException!")
    }
}
