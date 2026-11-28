package com.openminis.app.browser.cdp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CdpCodecTest {

    @Test
    fun encodesIdAndMethod() {
        val json = JSONObject(CdpCodec.encodeCall(7, "Page.navigate"))
        assertEquals(7, json.getInt("id"))
        assertEquals("Page.navigate", json.getString("method"))
    }

    @Test
    fun includesParamsWhenPresent() {
        val params = JSONObject().put("url", "https://example.com")
        val json = JSONObject(CdpCodec.encodeCall(3, "Page.navigate", params))
        assertEquals("https://example.com", json.getJSONObject("params").getString("url"))
    }

    @Test
    fun omitsNullParamsAndSession() {
        val json = JSONObject(CdpCodec.encodeCall(1, "Target.createTarget"))
        assertTrue(!json.has("params"))
        assertTrue(!json.has("sessionId"))
    }

    @Test
    fun attachesSessionWhenProvided() {
        val json = JSONObject(CdpCodec.encodeCall(2, "Runtime.evaluate", JSONObject(), "SID-1"))
        assertEquals("SID-1", json.getString("sessionId"))
    }

    @Test
    fun returnsResultObject() {
        val result = CdpCodec.resultOf("""{"id":4,"result":{"targetId":"FID-1"}}""")
        assertEquals("FID-1", result.getString("targetId"))
    }

    @Test(expected = IllegalStateException::class)
    fun throwsOnProtocolError() {
        CdpCodec.resultOf("""{"id":5,"error":{"message":"no such target"}}""")
    }

    @Test
    fun readsPrimitiveValue() {
        val result = JSONObject("""{"result":{"type":"string","value":"complete"}}""")
        assertEquals("complete", CdpCodec.stringValue(result))
    }

    @Test
    fun readsObjectValueAsJson() {
        val result = JSONObject("""{"result":{"type":"object","value":{"a":1}}}""")
        assertEquals("""{"a":1}""", CdpCodec.stringValue(result))
    }

    @Test
    fun extractsExceptionText() {
        val result = JSONObject(
            """{"exceptionDetails":{"text":"Uncaught","exception":{"description":"SyntaxError: bad"}}}""",
        )
        assertEquals("SyntaxError: bad", CdpCodec.exceptionText(result))
    }

    @Test
    fun noExceptionDetailsMeansNull() {
        assertNull(CdpCodec.exceptionText(JSONObject("""{"result":{"value":1}}""")))
    }

    @Test
    fun clampKeepsShortText() {
        assertEquals("abc", CdpCodec.clamp("abc", 10))
    }

    @Test
    fun clampMarksTruncation() {
        val out = CdpCodec.clamp("abcdefghij", 4)
        assertTrue(out.startsWith("abcd"))
        assertTrue(out.contains("truncated 6 chars"))
    }
}
