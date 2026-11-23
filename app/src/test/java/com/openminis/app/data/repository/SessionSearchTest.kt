package com.openminis.app.data.repository

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSearchTest {

    private fun hit(t: String, q: String, max: Int = 90, lead: Int = 18) =
        SessionSearch.searchHitLine(t, q, max, lead)

    private fun has(s: String?, q: String) = s?.contains(q, ignoreCase = true) == true


    @Test fun `no hit and blank query give null`() {
        assertNull(hit("hello world", "gpt"))
        assertNull(hit("hello", ""))
        assertNull(hit("hello", "   "))
    }

    @Test fun `short lines and casing`() {
        assertEquals("Switch to GPT-5 now", hit("Switch to GPT-5 now", "gpt"))
        assertEquals("use Gpt here", hit("use Gpt here", "GPT"))
        assertEquals("a GPT b", hit("a GPT b", " GPT "))
    }

    @Test fun `only the line holding the hit`() {
        assertEquals("second has the GPT keyword", hit("first line\nsecond has the GPT keyword\nthird line", "gpt"))
        assertEquals("two GPT two", hit("one\r\ntwo GPT two\r\nthree", "gpt"))
        assertEquals("GPT first", hit("GPT first\nsecond", "gpt"))
        assertEquals("last GPT", hit("first\nlast GPT", "gpt"))
        assertEquals("GPT one", hit("a\nGPT one\nGPT two", "gpt"))
    }

    @Test fun `long line is windowed around the hit`() {
        val w = hit("x".repeat(200) + " GPT " + "y".repeat(200), "gpt")!!
        assertTrue(w.contains("GPT"))
        assertTrue(w.startsWith("…") && w.endsWith("…"))
        assertTrue(w.length <= 92)
        val before = w.indexOf("GPT") - 1
        assertTrue("about lead characters before the hit: $before", before in 17..18)
    }

    @Test fun `hits at the ends of a long line`() {
        val early = hit("GPT " + "z".repeat(300), "gpt")!!
        assertTrue(early.startsWith("GPT") && early.endsWith("…"))
        val late = hit("z".repeat(300) + " GPT", "gpt")!!
        assertTrue(late.endsWith("GPT") && late.startsWith("…"))
    }

    @Test fun `REGRESSION a late hit stays near the window's start`() {
        val w = hit("a".repeat(150) + " GPT " + "b".repeat(20), "gpt")!!
        assertTrue(w.indexOf("GPT") <= 19)
    }

    @Test fun `CJK, emoji and long queries`() {
        val cjk = "这是一段很长的中文内容".repeat(20) + "关键字命中" + "后面还有更多文字".repeat(20)
        val c = hit(cjk, "关键字")!!
        assertTrue(c.contains("关键字") && c.length <= 92)
        val e = hit("🙂".repeat(150) + "GPT" + "🎉".repeat(150), "gpt")!!
        assertTrue(e.contains("GPT"))
        assertTrue("no lone surrogate", e.codePoints().noneMatch { it in 0xD800..0xDFFF })
        val longQuery = "q".repeat(120)
        assertTrue(has(hit("aa $longQuery bb", longQuery), longQuery))
    }

    @Test fun `whitespace handling and tiny windows`() {
        assertEquals("GPT is here", hit("    GPT is here    ", "gpt"))
        assertEquals("say  hello world  end", hit("say  hello world  end", "hello world"))
        assertTrue(has(hit("0123456789 GPT 0123456789", "gpt", 5, 2), "GPT"))
    }


    @Test fun `tool input string leaves, keys sorted, depth-first`() {
        assertEquals(listOf("one", "three", "two"), SessionSearch.toolInputStrings("""{"b":"two","a":["one",{"c":"three"}],"n":5}"""))
        val lines = SessionSearch.toolInputStrings("""{"text":"line one\n4. GPT-5 cache"}""")
        assertEquals(listOf("line one" + "\n" + "4. GPT-5 cache"), lines)
        assertEquals("4. GPT-5 cache", hit(lines[0], "gpt"))
        assertTrue(SessionSearch.toolInputStrings("ls -la").isEmpty())
        assertEquals(listOf("just text"), SessionSearch.toolInputStrings("\"just text\""))
    }


    private fun text(v: String) = """{"type":"text","value":${JSONObject.quote(v)}}"""
    private fun toolUse(name: String, input: String, id: String = "call_x9GpTq2") =
        """{"type":"toolUse","value":{"toolUseId":"$id","name":"$name","input":${JSONObject.quote(input)}}}"""
    private fun toolResult(output: String, id: String = "call_x9GpTq2") =
        """{"type":"toolResult","value":{"toolUseId":"$id","output":${JSONObject.quote(output)},"success":true}}"""
    private fun parts(vararg p: String) = "[" + p.joinToString(",") + "]"

    @Test fun `visible text wins over tool input and output`() {
        val json = parts(toolUse("shell_execute", """{"command":"grep gpt src"}"""), toolResult("gpt-5.1"), text("switch to GPT-5 please"))
        assertEquals("switch to GPT-5 please", SessionSearch.searchSnippet(json, "gpt"))
    }

    @Test fun `tool input then tool output, marked with the wrench`() {
        assertEquals(
            "🔧 shell_execute: grep -r gpt-5 src",
            SessionSearch.searchSnippet(parts(toolUse("shell_execute", """{"command":"grep -r gpt-5 src"}"""), toolResult("gpt ok")), "gpt"),
        )
        assertEquals("🔧 gpt-5.1-codex", SessionSearch.searchSnippet(parts(toolResult("gpt-5.1-codex\nclaude")), "gpt"))
    }

    @Test fun `a hit only in a tool-call id or a reminder shows nothing`() {
        assertNull(SessionSearch.searchSnippet(parts(toolUse("shell_execute", """{"command":"ls"}"""), toolResult("done")), "gpt"))
        assertNull(SessionSearch.searchSnippet(parts(text("<system-reminder>model: gpt-5</system-reminder>")), "gpt"))
        assertNull(SessionSearch.searchSnippet(parts(text("see file\n<user-attached-files>gpt.txt</user-attached-files>")), "gpt"))
        assertNull(SessionSearch.searchSnippet(parts(text("[attached image: /var/minis/gpt.png]")), "gpt"))
    }

    @Test fun `markdown is stripped from the line only when the hit survives`() {
        assertEquals("Use GPT-5 here", SessionSearch.searchSnippet(parts(text("## Use **GPT-5** here")), "gpt"))
        assertEquals("see docs for gpt", SessionSearch.searchSnippet(parts(text("see [docs](https://x.y) for gpt")), "gpt"))
        assertEquals("see [docs](https://gpt.example)", SessionSearch.searchSnippet(parts(text("see [docs](https://gpt.example)")), "gpt"))
    }

    @Test fun `malformed parts give null`() {
        assertNull(SessionSearch.searchSnippet("not json gpt", "gpt"))
    }
}
