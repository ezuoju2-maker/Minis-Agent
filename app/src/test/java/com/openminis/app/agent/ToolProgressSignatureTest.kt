package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolProgressSignatureTest {

    @Test
    fun `timestamps ids and durations do not count as progress`() {
        val a = "poll ok at 2026-11-24T10:01:22Z running pid=4321 elapsed 12.5s"
        val b = "poll ok at 2026-11-24T10:09:58Z running pid=9900 elapsed 91.2s"
        assertFalse(ToolProgressSignature.hasProgress(a, b))
    }

    @Test
    fun `real state change counts as progress`() {
        val a = "status running at 2026-11-24T10:01:22Z"
        val b = "status done at 2026-11-24T10:01:22Z"
        assertTrue(ToolProgressSignature.hasProgress(a, b))
    }

    @Test
    fun `uuid and pointer churn does not count as progress`() {
        val a = "call 550e8400-e29b-41d4-a716-446655440000 ptr 0x7f8a9b0c1d"
        val b = "call 9c5b2a41-1d3e-4f57-8a2b-77c1d0e9f111 ptr 0x55d3aa41bb"
        assertFalse(ToolProgressSignature.hasProgress(a, b))
    }

    @Test
    fun `error text differences count as progress`() {
        assertFalse(
            ToolProgressSignature.signature("out", "exit 1") ==
                ToolProgressSignature.signature("out", "exit 2"),
        )
    }

    @Test
    fun `null and empty results are equivalent`() {
        assertEquals(ToolProgressSignature.signature(null, null), ToolProgressSignature.signature("", ""))
    }

    @Test
    fun `canonical numbers collapse integral doubles`() {
        assertEquals("1", ToolProgressSignature.canonicalNumber(1))
        assertEquals("1", ToolProgressSignature.canonicalNumber(1.0))
        assertEquals("1", ToolProgressSignature.canonicalNumber(1.0f))
        assertEquals("1.5", ToolProgressSignature.canonicalNumber(1.5))
    }

    @Test
    fun `normalize keeps meaningful digits intact`() {
        assertEquals("id 12345 unchanged", ToolProgressSignature.normalize("id 12345 unchanged"))
    }
}
