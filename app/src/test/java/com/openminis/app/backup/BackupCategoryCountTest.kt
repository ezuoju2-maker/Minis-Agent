package com.openminis.app.backup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupCategoryCountTest {

    private val tmp: File = File.createTempFile("count-test", "").apply {
        delete(); mkdirs()
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun skill(id: String, vararg files: String) {
        val dir = File(tmp, "skills/$id").apply { mkdirs() }
        files.forEach { File(dir, it).apply { parentFile?.mkdirs() }.writeText("x") }
    }

    private fun serversJson(text: String): File =
        File(tmp, "servers.json").apply { parentFile?.mkdirs(); writeText(text) }


    @Test
    fun `skill count is directories, not the files inside them`() {
        skill("alpha", "SKILL.md", "scripts/run.sh", "assets/a.png", "assets/b.png")
        skill("beta", "SKILL.md", "data/1.json", "data/2.json")
        skill("gamma", "SKILL.md")

        assertEquals(3, BackupExporter.skillCount(File(tmp, "skills")))
    }

    @Test
    fun `a skill carrying hundreds of files still counts as one skill`() {
        val many = (1..569).map { "assets/file$it.bin" }.toTypedArray()
        skill("heavy", *many)

        assertEquals(1, BackupExporter.skillCount(File(tmp, "skills")))
    }

    @Test
    fun `loose files at the skills root are not skills`() {
        skill("real", "SKILL.md")
        File(tmp, "skills/stray.md").writeText("not a skill")

        assertEquals(1, BackupExporter.skillCount(File(tmp, "skills")))
    }

    @Test
    fun `no skills directory yields zero, not a crash`() {
        assertEquals(0, BackupExporter.skillCount(File(tmp, "skills")))
    }


    @Test
    fun `mcp count reports the number of servers`() {
        val f = serversJson(
            """{"mcpServers":{"context7":{"url":"https://a"},"playwright":{"command":"npx"}}}"""
        )
        assertEquals(2, BackupExporter.mcpServerCount(f))
    }

    @Test
    fun `mcp count handles a single server and an empty set`() {
        assertEquals(1, BackupExporter.mcpServerCount(serversJson("""{"mcpServers":{"only":{}}}""")))
        assertEquals(0, BackupExporter.mcpServerCount(serversJson("""{"mcpServers":{}}""")))
    }

    @Test
    fun `malformed or unexpected servers json falls back to one, never zero`() {
        assertEquals(1, BackupExporter.mcpServerCount(serversJson("{ not json")))
        assertEquals(1, BackupExporter.mcpServerCount(serversJson("""{"somethingElse":{}}""")))
    }


    @Test
    fun `provider stat separates thinking rules from provider count`() {
        val stat = BackupManifest.CategoryStat(
            entries = 8, bytes = 26_000, encrypted = false,
            includesCredentials = true, thinkingRules = 1,
        )

        assertEquals(8, stat.entries)
        assertEquals(1, stat.thinkingRules)
    }

    @Test
    fun `no custom thinking rules leaves the field absent rather than zero`() {
        val json = BackupFormat.json.encodeToString(
            BackupManifest.CategoryStat.serializer(),
            BackupManifest.CategoryStat(entries = 8, bytes = 1, encrypted = false),
        )
        assertFalse(json.contains("thinking_rules"))
    }


    @Test
    fun `old package without the new fields still decodes`() {
        val stat = BackupFormat.json.decodeFromString(
            BackupManifest.CategoryStat.serializer(),
            """{"entries":569,"bytes":84400000,"encrypted":false}""",
        )
        assertEquals(569, stat.entries)
        assertNull(stat.files)
        assertNull(stat.thinkingRules)
    }

    @Test
    fun `thinking rules serialises under its snake_case wire key`() {
        val json = BackupFormat.json.encodeToString(
            BackupManifest.CategoryStat.serializer(),
            BackupManifest.CategoryStat(entries = 8, bytes = 1, encrypted = false, thinkingRules = 2),
        )
        assertTrue(json.contains("\"thinking_rules\":2"))

        val back = BackupFormat.json.decodeFromString(
            BackupManifest.CategoryStat.serializer(), json,
        )
        assertEquals(2, back.thinkingRules)
        assertEquals(8, back.entries)
    }

    @Test
    fun `skills stat carries skills in entries and files in files`() {
        val stat = BackupManifest.CategoryStat(
            entries = 12, bytes = 84_400_000, encrypted = false, files = 569,
        )
        assertEquals(12, stat.entries)
        assertEquals(569, stat.files)
    }
}
