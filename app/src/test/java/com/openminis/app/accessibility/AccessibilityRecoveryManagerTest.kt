package com.openminis.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityRecoveryManagerTest {

    private val PKG = "com.openminis.app"
    private val CLS = "com.openminis.app.accessibility.MinisAccessibilityService"

    private fun revoked(value: String?) =
        AccessibilityRecoveryManager.isRevokedIn(value, PKG, CLS)


    @Test
    fun `null value reads as revoked`() {
        assertTrue(revoked(null))
    }

    @Test
    fun `empty value reads as revoked`() {
        assertTrue(revoked(""))
        assertTrue(revoked("   "))
    }


    @Test
    fun `fully qualified entry is recognized`() {
        assertFalse(revoked("$PKG/$CLS"))
    }

    @Test
    fun `abbreviated entry is recognized`() {
        assertFalse(revoked("$PKG/.accessibility.MinisAccessibilityService"))
    }

    @Test
    fun `case differences are tolerated`() {
        assertFalse(revoked("$PKG/$CLS".uppercase()))
    }

    @Test
    fun `entry surrounded by whitespace is recognized`() {
        assertFalse(revoked("  $PKG/$CLS  "))
    }


    @Test
    fun `ours found among several enabled services`() {
        val talkback = "com.google.android.marvin.talkback/.TalkBackService"
        val bitwarden = "com.x8bit.bitwarden/com.x8bit.bitwarden.Accessibility.AccessibilityService"
        assertFalse(revoked("$talkback:$PKG/$CLS:$bitwarden"))
    }

    @Test
    fun `other services present but ours absent reads as revoked`() {
        val talkback = "com.google.android.marvin.talkback/.TalkBackService"
        assertTrue(revoked(talkback))
    }

    @Test
    fun `a different package with a same-named class does not match`() {
        assertTrue(revoked("com.other.app/com.openminis.app.accessibility.MinisAccessibilityService"))
    }

    @Test
    fun `our package with a different service class does not match`() {
        assertTrue(revoked("$PKG/$PKG.accessibility.SomeOtherService"))
    }


    @Test
    fun `prompt timeout is 60 seconds`() {
        assertEquals(60_000L, AccessibilityRecoveryManager.PROMPT_TIMEOUT_MS)
    }


    @Test
    fun `respond without a pending prompt is a no-op`() {
        AccessibilityRecoveryManager.respond(AccessibilityRecoveryManager.Decision.CANCEL)
        AccessibilityRecoveryManager.respond(AccessibilityRecoveryManager.Decision.REPAIR)
        assertEquals(null, AccessibilityRecoveryManager.pendingPrompt.value)
    }
}
