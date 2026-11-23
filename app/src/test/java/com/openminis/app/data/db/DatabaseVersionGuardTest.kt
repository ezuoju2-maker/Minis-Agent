package com.openminis.app.data.db

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DatabaseVersionGuardTest {

    private val schemaDir = File("schemas/com.openminis.app.data.db.AppDatabase")

    private fun exportedVersions(): List<Int> =
        (schemaDir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            .sorted()

    @Test
    fun `schema json is exported`() {
        assertTrue(
            "No exported Room schema found at ${schemaDir.absolutePath}. " +
                "exportSchema must stay true and room.schemaLocation must stay configured, " +
                "or MigrationTestHelper has nothing to replay.",
            exportedVersions().isNotEmpty(),
        )
    }

    @Test
    fun `guard constant matches the latest exported schema version`() {
        val latest = exportedVersions().lastOrNull()
            ?: return
        assertEquals(
            "DatabaseVersionGuard.CODE_DB_VERSION must be bumped together with " +
                "@Database(version=...) in AppDatabase — bump both, add a Migration for the new " +
                "version and commit the regenerated schema JSON",
            latest,
            DatabaseVersionGuard.CODE_DB_VERSION,
        )
    }

    @Test
    fun `exported schema has the attribution columns`() {
        val latest = exportedVersions().lastOrNull() ?: return
        val json = JSONObject(File(schemaDir, "$latest.json").readText())
        val entities = json.getJSONObject("database").getJSONArray("entities")
        var messages: JSONObject? = null
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            if (e.getString("tableName") == "messages") messages = e
        }
        requireNotNull(messages) { "messages table missing from exported schema" }

        val fields = messages.getJSONArray("fields")
        val byName = buildMap {
            for (i in 0 until fields.length()) {
                val f = fields.getJSONObject(i)
                put(f.getString("columnName"), f)
            }
        }
        for (col in listOf("model_id", "model_display_name", "provider_type", "provider_instance_id")) {
            val f = byName[col] ?: error("$col missing from exported messages schema")
            assertTrue("$col must be nullable", !f.getBoolean("notNull"))
        }
    }
}
