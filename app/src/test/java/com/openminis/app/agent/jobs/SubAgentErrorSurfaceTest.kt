package com.openminis.app.agent.jobs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentErrorSurfaceTest {

    private fun kind(s: String?) = HelperRunner.errorKind(s)


    @Test
    fun `nothing to classify yields no attribution`() {
        assertNull(kind(null))
        assertNull(kind(""))
        assertNull(kind("   "))
    }

    @Test
    fun `an unrecognisable error is not given an invented category`() {
        assertNull(kind("Something went sideways in module 7"))
        assertNull(kind("segmentation fault"))
    }

    @Test
    fun `the common provider failures are each attributed`() {
        assertEquals(HelperRunner.ErrorKind.RATE_LIMITED, kind("HTTP 429 Too Many Requests"))
        assertEquals(HelperRunner.ErrorKind.QUOTA_EXHAUSTED, kind("insufficient credit on this account"))
        assertEquals(HelperRunner.ErrorKind.PROVIDER_OVERLOADED, kind("Error 529: overloaded"))
        assertEquals(HelperRunner.ErrorKind.TIMED_OUT, kind("The request timed out"))
        assertEquals(HelperRunner.ErrorKind.AUTH_FAILED, kind("401 Unauthorized: bad api key"))
        assertEquals(HelperRunner.ErrorKind.NETWORK_ERROR, kind("Unable to resolve host"))
        assertEquals(HelperRunner.ErrorKind.CANCELLED, kind("Cancelled by user"))
    }

    @Test
    fun `classification ignores case and surrounding whitespace`() {
        assertEquals(HelperRunner.ErrorKind.RATE_LIMITED, kind("  RATE LIMIT exceeded  "))
    }

    @Test
    fun `a context overflow is not misread as a quota problem`() {
        assertEquals(
            HelperRunner.ErrorKind.CONTEXT_LIMIT,
            kind("This model's maximum context length is 200000 tokens, however you requested 214000"),
        )
    }

    @Test
    fun `an overload that also mentions rate limiting is reported as overload`() {
        assertEquals(
            HelperRunner.ErrorKind.PROVIDER_OVERLOADED,
            kind("503 Service Unavailable — server overloaded"),
        )
    }


    @Test
    fun `every kind survives the wire round-trip`() {
        for (k in HelperRunner.ErrorKind.entries) {
            assertEquals(k, HelperRunner.ErrorKind.fromWire(k.wire))
        }
    }

    @Test
    fun `an unknown or absent wire value yields no kind`() {
        assertNull(HelperRunner.ErrorKind.fromWire(null))
        assertNull(HelperRunner.ErrorKind.fromWire(""))
        assertNull(HelperRunner.ErrorKind.fromWire("volcano_eruption"))
    }


    private fun payload(errorText: String?) = JSONObject(
        HelperRunner.resultJson(
            status = "failed", result = "", modelLabel = "m",
            tierUsed = HelperModelTier.PRIMARY, tierRequested = "same_as_me",
            turns = 2, elapsedMs = 5_000, childSessionId = "c", jobId = "j",
            errorText = errorText,
        ),
    )

    @Test
    fun `a failed run carries both the raw text and its classification`() {
        val json = payload("HTTP 429 Too Many Requests")
        assertEquals("HTTP 429 Too Many Requests", json.getString("error_detail"))
        assertEquals(HelperRunner.ErrorKind.RATE_LIMITED.wire, json.getString("error_kind"))
    }

    @Test
    fun `an unrecognised error still carries its detail`() {
        val json = payload("Something went sideways in module 7")
        assertEquals("Something went sideways in module 7", json.getString("error_detail"))
        assertFalse("no category may be invented", json.has("error_kind"))
    }

    @Test
    fun `a run with no error carries neither key`() {
        for (empty in listOf(null, "", "   ")) {
            val json = payload(empty)
            assertFalse("error_detail must be absent for ${empty?.let { "'$it'" } ?: "null"}", json.has("error_detail"))
            assertFalse("error_kind must be absent", json.has("error_kind"))
        }
    }

    @Test
    fun `the detail is trimmed before it is stored`() {
        assertEquals("boom", payload("  boom  ").getString("error_detail"))
    }


    @Test
    fun `a failed run with no result is not relabelled as no-deliverable`() {
        assertEquals("failed", HelperRunner.resolvedStatus("failed", ""))
        assertEquals(HelperRunner.NO_DELIVERABLE, HelperRunner.resolvedStatus("completed", ""))
        assertTrue(HelperRunner.resolvedStatus("completed", "here it is") == "completed")
    }
}
