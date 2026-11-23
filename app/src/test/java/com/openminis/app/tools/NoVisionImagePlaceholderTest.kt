package com.openminis.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoVisionImagePlaceholderTest {

    private val path = "/var/minis/attachments/img_1.png"

    private fun text(path: String?, group: Boolean) =
        VisionGroupResolver.noVisionImagePlaceholder(path, visionGroupConfigured = group)

    @Test
    fun `no vision group still hands over the path`() {
        val out = text(path, group = false)
        assertTrue("the path must be in the text", out.contains(path))
        assertTrue("and a usable way to act on it", out.contains("shell_execute"))
    }

    @Test
    fun `no vision group does NOT invite read_image`() {
        assertFalse(text(path, group = false).contains("read_image"))
    }

    @Test
    fun `vision group names read_image and the path`() {
        val out = text(path, group = true)
        assertTrue(out.contains(path))
        assertTrue(out.contains("read_image"))
    }

    @Test
    fun `vision group without a path still names the tool`() {
        val out = text(null, group = true)
        assertTrue(out.contains("read_image"))
        assertFalse("must not print a null path", out.contains("null"))
    }

    @Test
    fun `no path and no group is the only dead end left`() {
        val out = text(null, group = false)
        assertFalse(out.contains("read_image"))
        assertFalse(out.contains("shell_execute"))
        assertFalse("must not print a null path", out.contains("null"))
    }

    @Test
    fun `a blank path is treated as no path`() {
        for (blank in listOf("", "   ")) {
            assertFalse(text(blank, group = false).contains("shell_execute"))
            assertTrue(text(blank, group = true).contains("read_image"))
        }
    }

    @Test
    fun `every tier names the image so it is never implied to be gone`() {
        for (g in listOf(true, false)) {
            for (p in listOf(path, null)) {
                assertTrue(text(p, g).contains("Image attached"))
            }
        }
    }


    private fun note(p: String?) = VisionGroupResolver.visionImagePathNote(p)

    @Test
    fun `a vision model is told where the image it can see lives`() {
        val out = note(path)!!
        assertTrue(out.contains(path))
    }

    @Test
    fun `the note says it is the SAME image, not an extra one`() {
        val out = note(path)!!.lowercase()
        assertTrue("must identify the image as the one already shown", out.contains("same image"))
        assertTrue("and explicitly deny it is additional", out.contains("not an additional one"))
    }

    @Test
    fun `no path means no note at all, never a null string`() {
        for (p in listOf(null, "", "   ")) {
            assertNull("blank/absent path must yield no note", note(p))
        }
    }

    @Test
    fun `the vision note does not invite read_image as a fetch`() {
        val out = note(path)!!
        assertTrue(out.contains("only if you need to work on the file"))
    }
}
