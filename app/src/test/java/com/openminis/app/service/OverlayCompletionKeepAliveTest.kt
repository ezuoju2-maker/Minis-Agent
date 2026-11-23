package com.openminis.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayCompletionKeepAliveTest {

    private fun shouldRunService(
        activeSessions: Int,
        presentSessions: Int,
        overlayCompletionPending: Boolean,
    ): Boolean = activeSessions > 0 || presentSessions > 0 || overlayCompletionPending

    @Test
    fun `the reported case - turn ends with the Activity already gone`() {
        assertFalse(
            "precondition: without the pending flag the service stops here",
            shouldRunService(activeSessions = 0, presentSessions = 0, overlayCompletionPending = false),
        )
        assertTrue(
            "a pending completion capsule must keep the service alive",
            shouldRunService(activeSessions = 0, presentSessions = 0, overlayCompletionPending = true),
        )
    }

    @Test
    fun `the home-button case already worked and must keep working`() {
        assertTrue(shouldRunService(activeSessions = 0, presentSessions = 1, overlayCompletionPending = false))
    }

    @Test
    fun `clearing the flag releases the last keep-alive reason`() {
        assertTrue(shouldRunService(0, 0, true))
        assertFalse(shouldRunService(0, 0, false))
    }

    @Test
    fun `a live stream still dominates`() {
        assertTrue(shouldRunService(activeSessions = 1, presentSessions = 0, overlayCompletionPending = false))
        assertTrue(shouldRunService(activeSessions = 1, presentSessions = 0, overlayCompletionPending = true))
    }

    @Test
    fun `the restated predicate matches the source`() {
        val src = java.io.File(
            "src/main/java/com/openminis/app/service/SessionActivityTracker.kt",
        ).readText()
        val body = src.substringAfter("private fun shouldRunService(): Boolean =")
            .substringBefore("\n\n")
        for (term in listOf("_activeSessions", "_presentSessions", "_overlayCompletionPending")) {
            assertTrue(
                "shouldRunService() no longer reads $term — update this test's mirror",
                body.contains(term),
            )
        }
    }
}
