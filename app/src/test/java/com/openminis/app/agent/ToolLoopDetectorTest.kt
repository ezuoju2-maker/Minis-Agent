package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLoopDetectorTest {


    @Test
    fun `generic_repeat does not warn under threshold`() {
        val detector = ToolLoopDetector()
        repeat(9) { i ->
            val pre = detector.check("file_read", mapOf("path" to "/foo"))
            assertEquals("call ${i + 1} should not be flagged pre-execution", Level.NONE, pre.level)
            val post = detector.record("file_read", mapOf("path" to "/foo"),
                result = "ok-$i", errorMessage = null)
            assertEquals("call ${i + 1} should not warn", Level.NONE, post.level)
        }
    }

    @Test
    fun `generic_repeat warns at threshold and uses bucketed throttle`() {
        val detector = ToolLoopDetector()
        var warnings = 0
        repeat(20) {
            detector.check("file_read", mapOf("path" to "/foo"))
            val post = detector.record("file_read", mapOf("path" to "/foo"),
                result = "ok", errorMessage = null)
            if (post.level == Level.WARNING) warnings++
        }
        assertEquals("should emit exactly two warnings (buckets 10 and 20)", 2, warnings)
    }

    @Test
    fun `generic_repeat ignores poll tools`() {
        val detector = ToolLoopDetector()
        repeat(15) {
            val post = detector.record("command_status", mapOf("id" to "job-1"),
                result = "running", errorMessage = null)
            if (post.level == Level.WARNING) {
                assertTrue("poll-path warning expected, got: ${post.message}",
                    post.message?.contains("Stop polling") == true)
            }
        }
    }

    @Test
    fun `generic_repeat is order-stable across param key reordering`() {
        val a: Map<String, Any?> = linkedMapOf("a" to 1, "b" to 2)
        val b: Map<String, Any?> = linkedMapOf("b" to 2, "a" to 1)
        var warnings = 0
        val detector = ToolLoopDetector()
        repeat(5) {
            val r = detector.record("custom_tool", a, "ok", null)
            if (r.level == Level.WARNING) warnings++
        }
        repeat(5) {
            val r = detector.record("custom_tool", b, "ok", null)
            if (r.level == Level.WARNING) warnings++
        }
        assertEquals("identical-hash params should yield exactly one warning at count 10",
            1, warnings)
    }

    @Test
    fun `argsHash ignores tool_title counter suffix`() {
        val detector = ToolLoopDetector()
        repeat(10) { i ->
            detector.record(
                toolName = "file_read",
                params = mapOf(
                    "path" to "/etc/hostname",
                    "tool_title" to "Read /etc/hostname #${i + 1}",
                ),
                result = "localhost",
                errorMessage = null,
            )
        }
        val pre = detector.check(
            "file_read",
            mapOf(
                "path" to "/etc/hostname",
                "tool_title" to "Read /etc/hostname #11",
            ),
        )
        assertTrue(
            "tool_title variation must NOT defeat repeat detection (got ${pre.level})",
            pre.level != Level.NONE,
        )
    }


    @Test
    fun `unknown_tool_repeat blocks at threshold`() {
        val detector = ToolLoopDetector()
        repeat(9) {
            detector.record("foobar", emptyMap(),
                result = null, errorMessage = "Error: unknown tool: foobar")
        }
        val preUnder = detector.check("foobar", emptyMap())
        assertEquals("9 unknown records → still allowed", Level.NONE, preUnder.level)
        detector.record("foobar", emptyMap(),
            result = null, errorMessage = "Error: unknown tool: foobar")
        val pre = detector.check("foobar", emptyMap())
        assertEquals(Level.CRITICAL, pre.level)
        assertTrue("message should mention foobar",
            pre.message?.contains("foobar") == true)
        assertTrue("message should mention block",
            pre.message?.contains("LOOP BLOCKED") == true)
    }

    @Test
    fun `unknown_tool_repeat resets on different tool name`() {
        val detector = ToolLoopDetector()
        repeat(10) {
            detector.record("foobar", emptyMap(),
                result = null, errorMessage = "unknown tool: foobar")
        }
        detector.record("bazquux", emptyMap(),
            result = null, errorMessage = "unknown tool: bazquux")
        val preFoobar = detector.check("foobar", emptyMap())
        assertTrue("foobar streak interrupted; must not be CRITICAL",
            preFoobar.level != Level.CRITICAL)
    }

    @Test
    fun `unknown_tool_repeat parses tool not found wording`() {
        val detector = ToolLoopDetector()
        repeat(10) {
            detector.record("ghost", emptyMap(),
                result = null, errorMessage = "tool 'ghost' not found in registry")
        }
        val pre = detector.check("ghost", emptyMap())
        assertEquals(Level.CRITICAL, pre.level)
    }

    @Test
    fun `unknown_tool_repeat does not match unrelated error`() {
        val detector = ToolLoopDetector()
        repeat(10) {
            detector.record("file_read", mapOf("path" to "/x"),
                result = null, errorMessage = "Error: file not found: /x")
        }
        val pre = detector.check("file_read", mapOf("path" to "/x"))
        assertNull("unrelated error must not be parsed as unknown-tool",
            pre.message?.takeIf { it.contains("unavailable tool") })
    }


    @Test
    fun `known_poll_no_progress warns at threshold`() {
        val detector = ToolLoopDetector()
        repeat(9) {
            detector.record("command_status", mapOf("id" to "job-1"),
                result = "running", errorMessage = null)
        }
        val preBefore = detector.check("command_status", mapOf("id" to "job-1"))
        assertEquals("9 prior records, streak=9, no warn yet",
            Level.NONE, preBefore.level)
        val post = detector.record("command_status", mapOf("id" to "job-1"),
            result = "running", errorMessage = null)
        assertEquals("10th record triggers WARNING", Level.WARNING, post.level)
        assertTrue(post.message?.contains("Stop polling") == true)
    }

    @Test
    fun `known_poll_no_progress blocks at critical threshold`() {
        val detector = ToolLoopDetector()
        repeat(20) {
            detector.record("command_status", mapOf("id" to "job-1"),
                result = "running", errorMessage = null)
        }
        val pre = detector.check("command_status", mapOf("id" to "job-1"))
        assertEquals(Level.CRITICAL, pre.level)
        assertTrue(pre.message?.contains("Session execution blocked") == true)
    }

    @Test
    fun `known_poll_no_progress resets when result changes`() {
        val detector = ToolLoopDetector()
        repeat(9) {
            detector.record("command_status", mapOf("id" to "job-1"),
                result = "running", errorMessage = null)
        }
        detector.record("command_status", mapOf("id" to "job-1"),
            result = "completed", errorMessage = null)
        val pre = detector.check("command_status", mapOf("id" to "job-1"))
        assertEquals("progress resets streak", Level.NONE, pre.level)
    }

    @Test
    fun `known_poll_no_progress recognizes process action poll`() {
        val detector = ToolLoopDetector()
        repeat(10) {
            detector.record("process",
                mapOf("action" to "poll", "id" to "p1"),
                result = "still_running", errorMessage = null)
        }
        val pre = detector.check("process", mapOf("action" to "poll", "id" to "p1"))
        assertEquals(Level.WARNING, pre.level)
    }


    @Test
    fun `global_circuit_breaker blocks any tool at 30`() {
        val detector = ToolLoopDetector()
        repeat(30) {
            detector.record("custom_widget", mapOf("k" to "v"),
                result = "no-progress", errorMessage = null)
        }
        val pre = detector.check("custom_widget", mapOf("k" to "v"))
        assertEquals(Level.CRITICAL, pre.level)
        assertTrue("breaker message expected, got: ${pre.message}",
            pre.message?.contains("global circuit breaker") == true)
    }

    @Test
    fun `global_circuit_breaker does not trip when results vary`() {
        val detector = ToolLoopDetector()
        repeat(30) { i ->
            detector.record("custom_widget", mapOf("k" to "v"),
                result = "result-$i", errorMessage = null)
        }
        val pre = detector.check("custom_widget", mapOf("k" to "v"))
        assertTrue("breaker must not fire on varying results",
            pre.level != Level.CRITICAL ||
                pre.message?.contains("global circuit breaker") != true)
    }


    @Test
    fun `reset clears history and warning buckets`() {
        val detector = ToolLoopDetector()
        repeat(10) {
            detector.record("file_read", mapOf("path" to "/foo"),
                result = "ok", errorMessage = null)
        }
        detector.reset()
        assertEquals(0, detector.historySnapshot().size)
        var warnings = 0
        repeat(10) {
            val r = detector.record("file_read", mapOf("path" to "/foo"),
                result = "ok", errorMessage = null)
            if (r.level == Level.WARNING) warnings++
        }
        assertEquals("first bucket should warn once after reset", 1, warnings)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `config rejects warning gte critical`() {
        ToolLoopConfig(warningThreshold = 10, criticalThreshold = 10)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `config rejects critical gte breaker`() {
        ToolLoopConfig(criticalThreshold = 30, globalCircuitBreakerThreshold = 30)
    }


    @Test
    fun `tool title reminder fires on 1st 11th 21st missing call only`() {
        val detector = ToolLoopDetector()
        val fired = mutableListOf<Int>()
        var missing = 0
        for (i in 1..40) {
            val titled = i % 4 == 0
            val declared = i % 5 != 0
            val args = org.json.JSONObject().put("command", "ls")
            if (titled) args.put("tool_title", "Step $i")
            if (declared && !titled) missing++
            if (detector.recordToolTitle(args, declared) != null) fired.add(missing)
        }
        assertEquals(listOf(1, 11, 21), fired)
        assertEquals(24, missing)
    }

    @Test
    fun `tool title reminder returns the shared text and restarts after reset`() {
        val detector = ToolLoopDetector()
        val first = detector.recordToolTitle(org.json.JSONObject(), true)
        assertEquals(com.openminis.app.ui.chat.ToolTitleReminder.TEXT, first)
        assertNull(detector.recordToolTitle(org.json.JSONObject(), true))
        detector.reset()
        assertEquals(com.openminis.app.ui.chat.ToolTitleReminder.TEXT,
            detector.recordToolTitle(org.json.JSONObject(), true))
    }

    @Test
    fun `tool title reminder period is configurable`() {
        val detector = ToolLoopDetector(ToolLoopConfig(toolTitleReminderEvery = 3))
        val fired = (1..7).filter { detector.recordToolTitle(org.json.JSONObject(), true) != null }
        assertEquals(listOf(1, 4, 7), fired)
    }

    @Test
    fun `tool title counting does not disturb loop warnings`() {
        val detector = ToolLoopDetector()
        var warned = false
        repeat(10) {
            detector.recordToolTitle(org.json.JSONObject(), true)
            val r = detector.record("file_read", mapOf("path" to "/foo"), result = "ok", errorMessage = null)
            if (r.level == Level.WARNING) warned = true
        }
        assertTrue(warned)
    }

}
