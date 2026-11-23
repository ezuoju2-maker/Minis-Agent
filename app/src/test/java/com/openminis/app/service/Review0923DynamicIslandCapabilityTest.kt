package com.openminis.app.service

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test

class Review0923DynamicIslandCapabilityTest {

    @Test
    fun `the capability cache has a way to be invalidated`() {
        val src = ProductionSources.read("service/DynamicIslandSupport.kt")
        if (!src.contains("capableCache")) return
        val invalidated = Regex("capableCache\\s*=\\s*null").containsMatchIn(src)
        val expires = Regex("(?i)\\bttl|elapsedRealtime|currentTimeMillis|nanoTime|expir").containsMatchIn(
            src.substringAfter("capableCache"),
        )
        assertTrue(
            "DynamicIslandSupport caches canPostPromotedNotifications() for the process " +
                "lifetime, but the user can toggle Live Updates at runtime — a revoked grant " +
                "leaves the overlay suppressed with no island shown",
            invalidated || expires,
        )
    }

    @Test
    fun `the class contract and the settings re-probe still assume a runtime flip`() {
        val doc = ProductionSources.read("service/DynamicIslandSupport.kt")
        val settings = ProductionSources.read("ui/settings/BackgroundSettingsScreen.kt")
        assertTrue(doc.contains("can flip at runtime"))
        assertTrue(settings.contains("Lifecycle.Event.ON_RESUME") && settings.contains("isDynamicIslandCapable"))
    }
}
