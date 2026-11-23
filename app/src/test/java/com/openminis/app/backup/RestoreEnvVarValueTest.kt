package com.openminis.app.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreEnvVarValueTest {

    private enum class Outcome { IMPORTED, SKIPPED }

    private fun decide(existingValue: String?, restored: String?): Outcome = when {
        !existingValue.isNullOrEmpty() -> Outcome.SKIPPED
        restored.isNullOrEmpty() -> Outcome.SKIPPED
        else -> Outcome.IMPORTED
    }

    @Test
    fun `an existing key with an empty value is repaired`() {
        assertEquals(Outcome.IMPORTED, decide(existingValue = "", restored = "ghp_realtoken"))
    }

    @Test
    fun `a key that is absent entirely is added with its value`() {
        assertEquals(Outcome.IMPORTED, decide(existingValue = null, restored = "ghp_realtoken"))
    }

    @Test
    fun `a live value is never replaced by the package's copy`() {
        assertEquals(Outcome.SKIPPED, decide(existingValue = "current-secret", restored = "older-secret"))
    }

    @Test
    fun `metadata with no value in the package does not create a blank entry`() {
        assertEquals(Outcome.SKIPPED, decide(existingValue = null, restored = null))
        assertEquals(Outcome.SKIPPED, decide(existingValue = null, restored = ""))
        assertEquals(Outcome.SKIPPED, decide(existingValue = "", restored = ""))
    }


    private val importerSrc: String by lazy {
        val f = File("src/main/java/com/openminis/app/backup/BackupImporter.kt")
        assertTrue("missing ${f.absolutePath}", f.exists())
        f.readText()
    }

    private val envImportCode: String by lazy {
        importerSrc
            .substringAfter("private fun importEnvironmentVariables(")
            .substringBefore("\n    /** Read + decode `secrets.json`")
            .lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
    }

    @Test
    fun `the importer does not skip purely because the key exists`() {
        assertFalse(
            "gating on isDuplicateKey skips keys whose value is empty — the reported bug",
            envImportCode.contains("if (repo.isDuplicateKey(meta.key))"),
        )
        assertTrue(
            "it must look at the stored value instead",
            envImportCode.contains("repo.getValue("),
        )
    }

    @Test
    fun `the importer never writes an empty value as if it were restored`() {
        assertFalse(
            "`?: \"\"` turns a missing secret into a blank entry that counts as imported",
            envImportCode.contains("""valuesByName[meta.key] ?: """"),
        )
        assertTrue(
            "a missing/blank restored value must be skipped",
            envImportCode.contains("restored.isNullOrEmpty()"),
        )
    }

    @Test
    fun `an existing entry is updated in place rather than re-added`() {
        // add() refuses a duplicate key, so repairing an existing entry has to
        // go through update() or the value silently never lands.
        assertTrue(envImportCode.contains("repo.update("))
    }
}
