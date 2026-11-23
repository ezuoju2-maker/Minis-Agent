package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextPolicyUserCapTest {

    @Test
    fun `user cap of 32K keeps auto-compact available`() {
        val policy = ContextPolicy.forUserCap(32_000)
        assertEquals(27_200, policy.compactThreshold)
        assertEquals(22_400, policy.offloadThreshold)
        assertEquals(17_600, policy.offloadTarget)
        assertFalse(policy.exhaustedOnly)
        assertTrue(policy.manualCompactAllowed)
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            policy.check(27_200, 32_000),
        )
        assertEquals(ContextPolicy.CheckResult.OK, policy.check(27_199, 32_000))
    }

    @Test
    fun `native window of 32K keeps the legacy tier`() {
        val policy = ContextPolicy.forContextWindow(32_000)
        assertEquals(0, policy.compactThreshold)
        assertTrue(policy.exhaustedOnly)
    }

    @Test
    fun `user cap of 128K compacts proportionally`() {
        val policy = ContextPolicy.forUserCap(128_000)
        assertEquals(108_800, policy.compactThreshold)
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            policy.check(108_800, 128_000),
        )
    }

    @Test
    fun `user cap of 128K compacts when already over the cap`() {
        val policy = ContextPolicy.forUserCap(128_000)
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            policy.check(138_800, 128_000),
        )
    }

    @Test
    fun `overflow on an exhausted-only tier compacts when a summary is viable`() {
        val policy = ContextPolicy.forContextWindow(48_000)
        assertEquals(0, policy.compactThreshold)
        assertTrue(policy.manualCompactAllowed)
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            policy.check(48_000, 48_000),
        )
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            policy.check(60_000, 48_000),
        )
    }

    @Test
    fun `overflow on a tiny window reports exhausted`() {
        val policy = ContextPolicy.forContextWindow(8_192)
        assertFalse(policy.manualCompactAllowed)
        assertEquals(
            ContextPolicy.CheckResult.EXHAUSTED,
            policy.check(9_000, 8_192),
        )
    }

    @Test
    fun `overflow handling leaves the large-window path alone`() {
        val policy = ContextPolicy.forContextWindow(200_000)
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            policy.check(180_000, 200_000),
        )
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            policy.check(250_000, 200_000),
        )
        assertEquals(ContextPolicy.CheckResult.OK, policy.check(179_999, 200_000))
    }
}
