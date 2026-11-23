package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

class AgentToolsCacheTest {

    private fun make(
        vision: Boolean = true,
        memory: Boolean = true,
        helper: Boolean = false,
        delegate: Boolean = true,
        browser: Boolean = true,
        roster: List<String> = listOf("builder"),
        userAsk: Boolean = false,
        skills: Boolean = false,
    ) = AgentTools.makeAgentTools(
        supportsImageInput = vision,
        visionGroupConfigured = false,
        memoryEnabled = memory,
        isHelper = helper,
        delegateEnabled = delegate,
        browserEnabled = browser,
        rosterNames = roster,
        userAskExposed = userAsk,
        skillsAvailable = skills,
    )

    @Test
    fun `identical arguments return the memoized instance`() {
        val a = make()
        val b = make()
        assertSame(a, b)
    }

    @Test
    fun `each distinct flag change produces a fresh list`() {
        val base = make()
        assertNotSame(base, make(memory = false))
        assertNotSame(base, make(helper = true))
        assertNotSame(base, make(delegate = false))
        assertNotSame(base, make(browser = false))
        assertNotSame(base, make(roster = listOf("builder", "researcher")))
        assertNotSame(base, make(skills = true))
        assertNotSame(base, make(userAsk = true))
    }

    @Test
    fun `equal roster content with a new list instance still hits cache`() {
        val a = make(roster = listOf("builder", "researcher"))
        val b = make(roster = listOf("builder", "researcher"))
        assertSame(a, b)
    }

    @Test
    fun `concurrent callers all observe one stable instance`() {
        val results = ConcurrentLinkedQueue<Any>()
        val latch = CountDownLatch(24)
        repeat(24) {
            Thread {
                try { results.add(make()) } finally { latch.countDown() }
            }.start()
        }
        latch.await()
        assertEquals(24, results.size)
        assertEquals(1, results.toSet().size)
    }
}
