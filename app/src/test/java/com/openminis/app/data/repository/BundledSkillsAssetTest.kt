package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BundledSkillsAssetTest {

    private val root = File("src/main/assets/bundled_skills")

    private fun folders(): List<String> =
        root.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    private fun read(folder: String): String = File(root, "$folder/SKILL.md").readText()

    private fun slug(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    @Test
    fun bundledSkillTreeExists() {
        assertTrue("missing ${root.absolutePath}", root.isDirectory)
        assertTrue("expected bundled skills, found ${folders().size}", folders().size >= 30)
        assertTrue("license must ship with the bundled skills", File(root, "LICENSE-mattpocock-skills.txt").isFile)
    }

    @Test
    fun everyBundledSkillParsesToItsFolderName() {
        val failures = mutableListOf<String>()
        for (folder in folders()) {
            val parsed = SkillRepository.parseSkillMd(read(folder))
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
    fun descriptionsFitSingleLinePromptBudget() {
        val offenders = folders().mapNotNull { folder ->
            val parsed = SkillRepository.parseSkillMd(read(folder)) ?: return@mapNotNull folder
            when {
                parsed.description.contains('\n') -> folder
                parsed.description.length > 1200 -> folder
                else -> null
            }
        }
        assertEquals("descriptions needing manual trim: $offenders", emptyList<String>(), offenders)
    }

    @Test
    fun companionFilesArePresent() {
        val required = mapOf(
            "teach" to "GLOSSARY-FORMAT.md",
            "writing-for-agents" to "SKILL-MECHANICS.md",
            "wizard" to "template.sh",
        )
        for ((folder, file) in required) {
            assertTrue("$folder should ship $file", File(root, "$folder/$file").isFile)
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
