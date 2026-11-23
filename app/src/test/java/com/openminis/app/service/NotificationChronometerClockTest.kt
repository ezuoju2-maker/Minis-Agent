package com.openminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationChronometerClockTest {

    private val upFiveDaysMs = 5L * 24 * 60 * 60 * 1000
    private val nowWall = 1_789_000_000_000L

    @Test
    fun `a run that started 90s ago maps to 90s before now`() {
        val nowElapsed = upFiveDaysMs
        val startElapsed = nowElapsed - 90_000L

        val wall = AgentForegroundService.elapsedRealtimeToWallClock(
            startElapsed, nowElapsed, nowWall,
        )

        assertEquals(nowWall - 90_000L, wall)
    }

    @Test
    fun `the result is not the raw elapsedRealtime value`() {
        val nowElapsed = upFiveDaysMs
        val startElapsed = nowElapsed - 90_000L

        val wall = AgentForegroundService.elapsedRealtimeToWallClock(
            startElapsed, nowElapsed, nowWall,
        )

        assertTrue(
            "must not pass elapsedRealtime through unconverted",
            wall != startElapsed,
        )
        val naiveErrorMs = wall - startElapsed
        assertTrue("naive error should be enormous", naiveErrorMs > 365L * 24 * 60 * 60 * 1000)
    }

    @Test
    fun `an instant equal to now maps to now`() {
        val nowElapsed = upFiveDaysMs
        assertEquals(
            nowWall,
            AgentForegroundService.elapsedRealtimeToWallClock(nowElapsed, nowElapsed, nowWall),
        )
    }

    @Test
    fun `two instants keep their spacing`() {
        val nowElapsed = upFiveDaysMs
        val startElapsed = nowElapsed - 120_000L
        val finishElapsed = nowElapsed - 90_000L

        val startWall = AgentForegroundService.elapsedRealtimeToWallClock(
            startElapsed, nowElapsed, nowWall,
        )
        val finishWall = AgentForegroundService.elapsedRealtimeToWallClock(
            finishElapsed, nowElapsed, nowWall,
        )

        assertEquals(30_000L, finishWall - startWall)
    }

    @Test
    fun `works on a freshly booted device`() {
        assertEquals(
            nowWall - 5_000L,
            AgentForegroundService.elapsedRealtimeToWallClock(1_000L, 6_000L, nowWall),
        )
    }
}
