package com.openminis.app.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthStateCheckTest {

    @Test
    fun matchingStateIsAccepted() {
        assertTrue(OAuthManager.stateMatches("abc123", "abc123"))
    }

    @Test
    fun missingOrDifferentStateIsRejected() {
        assertFalse(OAuthManager.stateMatches("abc123", null))
        assertFalse(OAuthManager.stateMatches("abc123", ""))
        assertFalse(OAuthManager.stateMatches("abc123", "abc124"))
        assertFalse(OAuthManager.stateMatches(null, "abc123"))
        assertFalse(OAuthManager.stateMatches(null, null))
    }
}
