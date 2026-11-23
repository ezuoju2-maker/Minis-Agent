package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewFlickerTest {

    private fun preview(json: String) = ChatRepository.extractTextPreview(json)

    private val toolResultRow = """
        [{"type":"toolResult","value":{"toolUseId":"t1","name":"shell_execute",
          "output":"git version 2.47.3","success":true,
          "snapshot":{"type":"text","text":"git version 2.47.3"}}}]
    """.trimIndent()


    @Test
    fun `a tool-result row yields no preview`() {
        assertNull(preview(toolResultRow))
    }

    @Test
    fun `a tool-use row still yields a preview`() {
        val toolUse =
            """[{"type":"toolUse","value":{"name":"shell_execute","description":"Install Git"}}]"""
        assertNotNull("tool_use must summarise, or the list has nothing to show", preview(toolUse))
    }

    @Test
    fun `assistant text still wins over everything else`() {
        val mixed = """
            [{"type":"toolUse","value":{"name":"shell_execute"}},
             {"type":"text","value":"Git is installed."}]
        """.trimIndent()
        assertEquals("Git is installed.", preview(mixed))
    }


    @Test
    fun `an empty parts array yields no preview`() {
        assertNull(preview("[]"))
    }

    @Test
    fun `a blank-text row yields no preview`() {
        assertNull(preview("""[{"type":"text","value":"   "}]"""))
    }

    @Test
    fun `media-only rows summarise rather than blanking`() {
        assertEquals("[Image]", preview("""[{"type":"mediaRef","value":{"kind":"image"}}]"""))
    }


    @Test
    fun `the last tool use wins when several are present`() {
        val two = """
            [{"type":"toolUse","value":{"name":"shell_execute","description":"First"}},
             {"type":"toolUse","value":{"name":"shell_execute","description":"Second"}}]
        """.trimIndent()
        val out = preview(two)
        assertNotNull(out)
        assertEquals(true, out!!.contains("Second"))
    }
}
