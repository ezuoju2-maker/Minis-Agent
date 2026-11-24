package com.openminis.app.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLoopProgressDetectionTest {

    @Test
    fun `clock churn still trips the poll warning`() {
        val detector = ToolLoopDetector()
        var warned = false
        repeat(12) { i ->
            val result = detector.record(
                toolName = "command_status",
                params = mapOf("command" to "c1"),
                result = "running at 2026-11-24T10:00:%02dZ pid=%d elapsed %d.5s".format(i, 1000 + i, i),
                errorMessage = null,
            )
            if (result.level == Level.WARNING) warned = true
        }
        assertTrue(warned)
    }

    @Test
    fun `integral double args collapse into one call identity`() {
        val detector = ToolLoopDetector()
        var warned = false
        repeat(5) {
            detector.record("file_read", mapOf("path" to "/a", "limit" to 20), "same")
        }
        repeat(5) {
            val result = detector.record("file_read", mapOf("path" to "/a", "limit" to 20.0), "same")
            if (result.level == Level.WARNING) warned = true
        }
        assertTrue(warned)
    }
}
