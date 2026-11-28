package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BundledSkillsAssetTest {

    private val assets: android.content.res.AssetManager =
        org.robolectric.RuntimeEnvironment.getApplication().assets

    private fun folders(): List<String> =
        assets.list("bundled_skills").orEmpty().filter { it != "LICENSE-mattpocock-skills.txt" }.sorted()

    private fun read(path: String): String =
        assets.open(path).use { it.bufferedReader().readText() }

    private fun slug(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    @Test
    fun everyBundledSkillParsesToItsFolderName() {
        val folders = folders()
        assertTrue("expected bundled skills, found ${folders.size}", folders.size >= 30)
        val failures = mutableListOf<String>()
        for (folder in folders) {
            val parsed = SkillRepository.parseSkillMd(read("bundled_skills/$folder/SKILL.md"))
            if (parsed == null) {
                failures += "$folder: frontmatter missing"
                continue
            }
            if (parsed.description.isBlank()) failures += "$folder: empty description"
            if (slug(parsed.name) != folder) failures += "$folder: name '${parsed.name}' does not match folder"
            if (parsed.body.isBlank()) failures += "$folder: empty body"
        }
        assertEquals("bundled skills with problems: $failures", emptyList<String>(), failures)
    }

    @Test
    fun descriptionsAreSingleLineAndBounded() {
        val tooLong = folders().mapNotNull { folder ->
            val parsed = SkillRepository.parseSkillMd(read("bundled_skills/$folder/SKILL.md")) ?: return@mapNotNull folder
            when {
                parsed.description.contains('\n') -> folder
                parsed.description.length > 1200 -> folder
                else -> null
            }
        }
        assertEquals("descriptions needing manual trim: $tooLong", emptyList<String>(), tooLong)
    }

    @Test
    fun companionFilesArePresent() {
        val required = mapOf(
            "teach" to "GLOSSARY-FORMAT.md",
            "writing-for-agents" to "SKILL-MECHANICS.md",
            "wizard" to "template.sh",
        )
        for ((folder, file) in required) {
            val listing = assets.list("bundled_skills/$folder").orEmpty()
            assertTrue("$folder should ship $file, has ${listing.toList()}", file in listing)
        }
    }

    @Test
    fun quotedDescriptionIsUnwrapped() {
        val parsed = SkillRepository.parseSkillMd(
            "---\nname: quoted\ndescription: \"Review the diff since a fixed point.\"\n---\nBody\n",
        )
        assertNotNull(parsed)
        assertEquals("Review the diff since a fixed point.", parsed!!.description)
    }
}
