package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillStaleMetadataTest {

    private data class Healed(
        val name: String,
        val description: String,
        val version: String,
        val wrote: Boolean,
    )

    private fun heal(
        storedName: String,
        storedDescription: String,
        storedVersion: String = "1.0.0",
        parsedName: String? = "Real Name",
        parsedDescription: String = "Real description",
        parsedVersion: String = "2.0.0",
        skillMdExists: Boolean = true,
    ): Healed {
        var name = storedName
        var description = storedDescription
        var version = storedVersion

        val nameStale = name.isBlank()
        val descStale = description == ">" || description == "|" || description.isBlank()
        var wrote = false
        if ((descStale || nameStale) && skillMdExists && parsedName != null) {
            var changed = false
            if (descStale && parsedDescription.isNotBlank()) {
                description = parsedDescription
                changed = true
            }
            if (nameStale && parsedName.isNotBlank()) {
                name = parsedName
                changed = true
            }
            if (version.isBlank()) {
                version = parsedVersion
                changed = true
            }
            wrote = changed
        }
        return Healed(name, description, version, wrote)
    }


    @Test
    fun `a blank name is healed even when the description is perfectly fine`() {
        val r = heal(storedName = "", storedDescription = "A good description")
        assertEquals("Real Name", r.name)
        assertEquals("A good description", r.description)
        assertTrue("must persist the healed name", r.wrote)
    }

    @Test
    fun `a blank name and a blank description are both healed in one write`() {
        val r = heal(storedName = "", storedDescription = "")
        assertEquals("Real Name", r.name)
        assertEquals("Real description", r.description)
        assertTrue(r.wrote)
    }


    @Test
    fun `a block-scalar leftover description is still healed`() {
        for (leftover in listOf(">", "|")) {
            val r = heal(storedName = "Kept Name", storedDescription = leftover)
            assertEquals("Real description", r.description)
            assertEquals("name must not be touched", "Kept Name", r.name)
            assertTrue(r.wrote)
        }
    }

    @Test
    fun `a healthy row is left completely alone and writes nothing`() {
        val r = heal(storedName = "妙想", storedDescription = "Imagination skill")
        assertEquals("妙想", r.name)
        assertEquals("Imagination skill", r.description)
        assertFalse("no DB write for a healthy row", r.wrote)
    }


    @Test
    fun `a deliberately set name is never overwritten by the file`() {
        val r = heal(
            storedName = "My Renamed Skill",
            storedDescription = "",
            parsedName = "Old File Name",
        )
        assertEquals("My Renamed Skill", r.name)
        assertEquals("only the description heals", "Real description", r.description)
    }

    @Test
    fun `an unparseable SKILL_md heals nothing rather than writing blanks`() {
        val r = heal(storedName = "", storedDescription = "", parsedName = null)
        assertEquals("", r.name)
        assertEquals("", r.description)
        assertFalse(r.wrote)
    }

    @Test
    fun `a missing SKILL_md heals nothing`() {
        val r = heal(storedName = "", storedDescription = "", skillMdExists = false)
        assertEquals("", r.name)
        assertFalse(r.wrote)
    }

    @Test
    fun `a blank parsed description does not overwrite a stale one`() {
        val r = heal(
            storedName = "",
            storedDescription = "",
            parsedDescription = "",
        )
        assertEquals("the name still heals", "Real Name", r.name)
        assertEquals("", r.description)
        assertTrue(r.wrote)
    }


    @Test
    fun `a blank version is filled while healing`() {
        val r = heal(storedName = "", storedDescription = "ok", storedVersion = "")
        assertEquals("2.0.0", r.version)
        assertTrue(r.wrote)
    }

    @Test
    fun `a blank version alone does NOT trigger a heal`() {
        val r = heal(storedName = "Name", storedDescription = "Desc", storedVersion = "")
        assertEquals("", r.version)
        assertFalse(r.wrote)
    }


    @Test
    fun `a whitespace-only name counts as stale`() {
        val r = heal(storedName = "   ", storedDescription = "ok")
        assertEquals("Real Name", r.name)
        assertTrue(r.wrote)
    }
}
