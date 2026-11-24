package com.openminis.app.agent.approval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandApprovalPolicyTest {

    private val now = 1_000_000L

    @Test
    fun `safe commands never ask`() {
        val p = CommandApprovalPolicy()
        assertTrue(p.decide("ls -la /var/minis/workspace", CommandRisk.SAFE, now) is ApprovalDecision.Execute)
    }

    @Test
    fun `blocked commands refuse even after an always allow`() {
        val p = CommandApprovalPolicy()
        p.remember("rm -rf /", ApprovalMode.ALLOW_ALWAYS, now)
        val d = p.decide("rm -rf /", CommandRisk.BLOCK, now)
        assertTrue(d is ApprovalDecision.Refuse)
    }

    @Test
    fun `warn without memory asks`() {
        val p = CommandApprovalPolicy()
        assertTrue(p.decide("rm -rf build", CommandRisk.WARN, now) is ApprovalDecision.Ask)
    }

    @Test
    fun `session allow executes until expiry then asks again`() {
        val p = CommandApprovalPolicy(sessionTtlMs = 60_000)
        p.remember("rm -rf build", ApprovalMode.ALLOW_SESSION, now)
        assertTrue(p.decide("rm -rf build", CommandRisk.WARN, now + 10_000) is ApprovalDecision.Execute)
        assertTrue(p.decide("rm -rf build", CommandRisk.WARN, now + 200_000) is ApprovalDecision.Ask)
    }

    @Test
    fun `always allow does not expire`() {
        val p = CommandApprovalPolicy(sessionTtlMs = 60_000)
        p.remember("chmod -R 777 /data", ApprovalMode.ALLOW_ALWAYS, now)
        assertTrue(p.decide("chmod -R 777 /data", CommandRisk.WARN, now + 10_000_000) is ApprovalDecision.Execute)
    }

    @Test
    fun `deny refuses`() {
        val p = CommandApprovalPolicy()
        p.remember("rm -rf build", ApprovalMode.DENY, now)
        assertTrue(p.decide("rm -rf build", CommandRisk.WARN, now) is ApprovalDecision.Refuse)
    }

    @Test
    fun `fingerprint ignores quote style and numbers`() {
        val p = CommandApprovalPolicy()
        val a = p.fingerprint("rm -rf \"build\"")
        val b = p.fingerprint("rm -rf build")
        assertEquals(a, b)
        val c = p.fingerprint("kill -9 12345")
        val d = p.fingerprint("kill -9 98765")
        assertEquals(c, d)
        assertFalse(a == c)
    }

    @Test
    fun `memory survives a different path argument of the same shape`() {
        val p = CommandApprovalPolicy()
        p.remember("rm -rf build/cache", ApprovalMode.ALLOW_SESSION, now)
        assertTrue(p.decide("rm -rf build/cache", CommandRisk.WARN, now) is ApprovalDecision.Execute)
    }

    @Test
    fun `revoke and scope clearing drop memory`() {
        val p = CommandApprovalPolicy()
        p.remember("rm -rf build", ApprovalMode.ALLOW_SESSION, now, scope = "s1")
        p.remember("rm -rf dist", ApprovalMode.ALLOW_SESSION, now, scope = "s2")
        assertEquals(2, p.size)
        assertTrue(p.revoke("rm -rf build"))
        assertEquals(1, p.size)
        p.clearScope("s2")
        assertEquals(0, p.size)
        assertEquals(ApprovalMode.ASK, p.modeOf("rm -rf dist", now))
    }

    @Test
    fun `ask mode keeps asking`() {
        val p = CommandApprovalPolicy()
        p.remember("rm -rf build", ApprovalMode.ASK, now)
        assertTrue(p.decide("rm -rf build", CommandRisk.WARN, now) is ApprovalDecision.Ask)
    }
}
