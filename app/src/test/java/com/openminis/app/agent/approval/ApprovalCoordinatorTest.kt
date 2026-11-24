package com.openminis.app.agent.approval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalCoordinatorTest {

    private val now = 5_000_000L

    @Test
    fun `safe command runs without asking`() {
        val c = ApprovalCoordinator()
        assertTrue(c.submit("ls -la", CommandRisk.SAFE, now) is Submission.Run)
    }

    @Test
    fun `blocked command is refused without asking`() {
        val c = ApprovalCoordinator()
        val s = c.submit("rm -rf /", CommandRisk.BLOCK, now)
        assertTrue(s is Submission.Refused)
        assertEquals(0, c.openCount())
    }

    @Test
    fun `warn command waits for the user`() {
        val c = ApprovalCoordinator()
        val s = c.submit("rm -rf build", CommandRisk.WARN, now)
        assertTrue(s is Submission.NeedsUser)
        assertEquals(1, c.openCount())
    }

    @Test
    fun `session approval runs now and remembers`() {
        val c = ApprovalCoordinator()
        val pending = (c.submit("rm -rf build", CommandRisk.WARN, now) as Submission.NeedsUser).pending
        val r = c.resolve(pending, ApprovalMode.ALLOW_SESSION, now + 1000)
        assertTrue(r is Resolution.Approved)
        assertEquals(0, c.openCount())
        assertTrue(c.submit("rm -rf build", CommandRisk.WARN, now + 2000) is Submission.Run)
    }

    @Test
    fun `denial is remembered and explained to the model`() {
        val c = ApprovalCoordinator()
        val pending = (c.submit("rm -rf build", CommandRisk.WARN, now) as Submission.NeedsUser).pending
        val r = c.resolve(pending, ApprovalMode.DENY, now)
        assertTrue(r is Resolution.Denied)
        val text = c.feedback(r, CommandRisk.WARN)
        assertTrue(text.contains("Do not retry"))
        assertTrue(c.submit("rm -rf build", CommandRisk.WARN, now + 100) is Submission.Refused)
    }

    @Test
    fun `dismissing the prompt denies the turn`() {
        val c = ApprovalCoordinator()
        val pending = (c.submit("chmod -R 777 /data", CommandRisk.WARN, now) as Submission.NeedsUser).pending
        val r = c.resolve(pending, ApprovalMode.ASK, now)
        assertTrue(r is Resolution.Denied)
    }

    @Test
    fun `timeout closes the request and a late tap is rejected`() {
        val c = ApprovalCoordinator(timeoutMs = 60_000)
        val pending = (c.submit("rm -rf dist", CommandRisk.WARN, now) as Submission.NeedsUser).pending
        assertEquals(1, c.expireOutstanding(now + 90_000).size)
        assertEquals(0, c.openCount())
        val late = c.resolve(pending, ApprovalMode.ALLOW_SESSION, now + 90_000)
        assertTrue(late is Resolution.Denied)
        assertTrue((late as Resolution.Denied).reason.contains("already closed"))
    }

    @Test
    fun `double resolve is ignored`() {
        val c = ApprovalCoordinator()
        val pending = (c.submit("rm -rf build", CommandRisk.WARN, now) as Submission.NeedsUser).pending
        assertTrue(c.resolve(pending, ApprovalMode.ALLOW_ALWAYS, now) is Resolution.Approved)
        assertTrue(c.resolve(pending, ApprovalMode.ALLOW_ALWAYS, now) is Resolution.Denied)
    }
}
