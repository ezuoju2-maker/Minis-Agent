package com.openminis.app.observability.otlp

import com.openminis.app.observability.TraceEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OtlpTraceTest {

    private fun event(seq: Long, turn: Int, kind: String, name: String, ms: Long, ok: Boolean, summary: String = "") =
        TraceEvent(seq = seq, turn = turn, kind = kind, name = name, durationMs = ms, ok = ok, summary = summary)

    private fun spans(json: String): List<JSONObject> {
        val root = JSONObject(json)
        val scopeSpans = root.getJSONArray("resourceSpans")
            .getJSONObject(0).getJSONArray("scopeSpans").getJSONObject(0)
        val arr = scopeSpans.getJSONArray("spans")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    @Test
    fun oneSpanPerEvent() {
        val json = OtlpTrace.toOtlpJson(
            listOf(
                event(1, 0, "tool", "file_read", 12, true),
                event(2, 0, "tool", "file_write", 30, false, "denied"),
            ),
            sessionId = "s1",
            baseEpochMs = 1_700_000_000_000,
        )
        assertEquals(2, spans(json).size)
    }

    @Test
    fun idsHaveOpenTelemetryWidth() {
        val spans = spans(OtlpTrace.toOtlpJson(listOf(event(1, 0, "tool", "shell_execute", 5, true)), "s1", 0))
        assertEquals(32, spans[0].getString("traceId").length)
        assertEquals(16, spans[0].getString("spanId").length)
        assertEquals(16, spans[0].getString("parentSpanId").length)
    }

    @Test
    fun idsAreHex() {
        val hex = OtlpTrace.hex(32, "trace:s1")
        assertEquals(32, hex.length)
        assertTrue(hex.all { it in "0123456789abcdef" })
    }

    @Test
    fun failedEventsCarryErrorStatus() {
        val spans = spans(OtlpTrace.toOtlpJson(
            listOf(event(1, 0, "guard", "file_write", 0, false, "denied /etc"), "s1", 0),
        ))
        assertEquals(2, spans[0].getJSONObject("status").getInt("code"))
    }

    @Test
    fun toolEventsExposeToolNameAttribute() {
        val json = OtlpTrace.toOtlpJson(listOf(event(1, 2, "tool", "file_read", 8, true)), "s1", 0)
        assertTrue(json.contains("gen_ai.tool.name"))
        assertTrue(json.contains("file_read"))
        assertTrue(json.contains("gen_ai.operation.name"))
    }

    @Test
    fun spanNamesAreReadable() {
        assertEquals("execute_tool file_read", OtlpTrace.spanName(event(1, 0, "tool", "file_read", 1, true)))
        assertEquals("guard file_write", OtlpTrace.spanName(event(1, 0, "guard", "file_write", 1, false)))
        assertEquals("forge create", OtlpTrace.spanName(event(1, 0, "forge", "create", 1, true)))
    }

    @Test
    fun timestampsAdvanceWithoutOverlap() {
        val spans = spans(OtlpTrace.toOtlpJson(
            listOf(event(1, 0, "tool", "a", 10, true), event(2, 0, "tool", "b", 20, true)),
            "s1",
            1000,
        ))
        val end0 = spans[0].getString("endTimeUnixNano").toLong()
        val start1 = spans[1].getString("startTimeUnixNano").toLong()
        assertTrue(end0 <= start1)
    }

    @Test
    fun emptyLogStillProducesValidEnvelope() {
        val json = OtlpTrace.toOtlpJson(emptyList(), "s1", 0)
        assertTrue(JSONObject(json).has("resourceSpans"))
        assertEquals(0, spans(json).size)
    }

    @Test
    fun quotesInSummaryDoNotBreakJson() {
        val json = OtlpTrace.toOtlpJson(
            listOf(event(1, 0, "tool", "shell_execute", 1, false, "bad \"quote\" and \\ slash")), "s1", 0,
        )
        assertTrue(JSONObject(json).has("resourceSpans"))
    }
}
