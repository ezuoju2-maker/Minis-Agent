package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoulIconSidecarTest {

    @Test
    fun `the sidecar filename matches the one iOS writes`() {
        assertEquals("SOUL.icon.png", SoulIcon.SIDECAR_NAME)
    }

    @Test
    fun `a stored sidecar reference is recognised as one`() {
        assertTrue(SoulIcon.isSidecarRef("SOUL.icon.png"))
        assertTrue(SoulIcon.isSidecarRef("  SOUL.icon.png  "))
    }

    @Test
    fun `emoji, inline data URIs and empty values are not sidecar references`() {
        assertFalse("an inline URI is bytes, not a filename",
            SoulIcon.isSidecarRef("data:image/png;base64,iVBORw0KGgo="))
        assertFalse(SoulIcon.isSidecarRef("⚡"))
        assertFalse(SoulIcon.isSidecarRef(""))
        assertFalse(SoulIcon.isSidecarRef("SOUL.icon.jpg"))
        assertFalse(SoulIcon.isSidecarRef("soul.icon.png"))
    }

    @Test
    fun `an inline data URI is still recognised as image bytes`() {
        assertTrue(SoulIcon.isDataUri("data:image/png;base64,iVBORw0KGgo="))
        assertFalse(SoulIcon.isDataUri(SoulIcon.SIDECAR_NAME))
    }

    @Test
    fun `the raw filename is unusable as an icon source, which is why it is resolved first`() {
        assertTrue(SoulIcon.classifySource(SoulIcon.SIDECAR_NAME) is SoulIcon.Source.Unsupported)
    }
}
