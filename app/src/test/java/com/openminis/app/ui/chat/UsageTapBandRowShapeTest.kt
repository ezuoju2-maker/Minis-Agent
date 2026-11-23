package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTapBandRowShapeTest {
    private val body by lazy {
        File("src/main/java/com/openminis/app/ui/chat/UsageTapBand.kt").readText()
            .substringAfter("internal fun UsageTapBandRowScope(")
            .substringBefore("\n}\n")
    }

    @Test
    fun `content is composed from exactly one call site`() {
        assertEquals(1, Regex("""\bcontent\(\)""").findAll(body).count())
    }

    @Test
    fun `no early return or row-dependent branch before the content`() {
        val beforeContent = body.substringBefore("content()")
        assertFalse("an early return gives off-band rows another shape", beforeContent.contains("return"))
        assertFalse("a branch on isLastRow moves the content between groups", beforeContent.contains("if (!isLastRow)"))
        assertFalse(beforeContent.contains("if (isLastRow)"))
    }

    @Test
    fun `only values depend on the flags`() {
        assertTrue(body.contains("val reportsPosition = inBand && isLastRow"))
        assertTrue("the Box's modifier carries the difference", body.contains("modifier = if (reportsPosition"))
        assertTrue("a row leaving last place still clears its anchor", body.contains("DisposableEffect(registry, id, reportsPosition)"))
    }
}
