package com.openminis.app.observability.otlp

import com.openminis.app.observability.TraceEvent
import org.json.JSONArray
import org.json.JSONObject

object OtlpTrace {

    private const val SERVICE_NAME = "minis-agent"
    private const val SCOPE_NAME = "com.openminis.app"
    private const val SPAN_KIND_INTERNAL = 1
    private const val STATUS_ERROR = 2

    fun toOtlpJson(
        events: List<TraceEvent>,
        sessionId: String,
        baseEpochMs: Long,
        serviceName: String = SERVICE_NAME,
    ): String {
        val traceId = hex(64, "trace:$sessionId")
        val root = JSONObject()
        val resourceSpans = JSONArray()
        val resource = JSONObject().put("attributes", JSONArray().put(attr("service.name", serviceName)))
        val scopeSpans = JSONArray()
        val spans = JSONArray()
        var cursorNs = baseEpochMs.coerceAtLeast(0L) * 1_000_000L
        for (e in events) {
            val durationNs = e.durationMs.coerceAtLeast(0L) * 1_000_000L
            val startNs = cursorNs
            val endNs = startNs + durationNs
            cursorNs = endNs
            val span = JSONObject()
                .put("traceId", traceId)
                .put("spanId", hex(16, "span:${e.seq}"))
                .put("parentSpanId", hex(16, "turn:${e.turn}"))
                .put("name", spanName(e))
                .put("kind", SPAN_KIND_INTERNAL)
                .put("startTimeUnixNano", startNs.toString())
                .put("endTimeUnixNano", endNs.toString())
                .put("attributes", attributes(e))
            if (!e.ok) span.put("status", JSONObject().put("code", STATUS_ERROR)) else span.put(
                "status", JSONObject().put("code", 1),
            )
            spans.put(span)
        }
        val scope = JSONObject().put("name", SCOPE_NAME).put("version", "2.0")
        scopeSpans.put(JSONObject().put("scope", scope).put("spans", spans))
        resourceSpans.put(JSONObject().put("resource", resource).put("scopeSpans", scopeSpans))
        root.put("resourceSpans", resourceSpans)
        return root.toString()
    }

    fun spanName(e: TraceEvent): String = when (e.kind) {
        "tool" -> "execute_tool ${e.name}"
        "guard" -> "guard ${e.name}"
        "forge" -> "forge ${e.name}"
        "approval" -> "approval ${e.name}"
        else -> "${e.kind} ${e.name}"
    }

    private fun attributes(e: TraceEvent): JSONArray = JSONArray().apply {
        put(attr("gen_ai.operation.name", operationOf(e.kind)))
        if (e.kind == "tool") put(attr("gen_ai.tool.name", e.name))
        put(attr("minis.trace.kind", e.kind))
        put(attr("minis.trace.name", e.name))
        put(attr("minis.turn", e.turn.toString()))
        put(attr("minis.seq", e.seq.toString()))
        if (e.summary.isNotBlank()) put(attr("minis.summary", e.summary.take(240)))
    }

    private fun operationOf(kind: String): String = when (kind) {
        "tool" -> "execute_tool"
        "guard" -> "guard_check"
        "forge" -> "agent_forge"
        else -> kind
    }

    private fun attr(key: String, value: String): JSONObject =
        JSONObject().put("key", key).put("value", JSONObject().put("stringValue", value))

    fun hex(len: Int, seed: String): String {
        var h = -0x340d631b7bdddcdbL
        for (c in seed) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        val one = java.lang.Long.toHexString(h).padStart(16, '0')
        val two = java.lang.Long.toHexString(h * 0x9e3779b97f4a7c15UL.toLong()).padStart(16, '0')
        return (one + two).takeLast(len)
    }
}
