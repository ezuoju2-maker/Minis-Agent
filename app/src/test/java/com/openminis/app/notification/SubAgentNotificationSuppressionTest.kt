package com.openminis.app.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SubAgentNotificationSuppressionTest {

    private fun shouldNotify(parentSessionId: String?): Boolean = parentSessionId == null

    @Test
    fun `a sub agent session is not notified`() {
        assertFalse(shouldNotify("parent-abc"))
    }

    @Test
    fun `a top-level session is still notified`() {
        assertTrue(shouldNotify(null))
    }

    @Test
    fun `a scheduled child is also suppressed`() {
        assertTrue(shouldNotify(null))
        assertFalse(shouldNotify("parent-from-scheduler"))
    }

    @Test
    fun `the guard is still present in BackgroundTaskNotifier`() {
        val f = File("src/main/java/com/openminis/app/notification/BackgroundTaskNotifier.kt")
        assertTrue("BackgroundTaskNotifier.kt not found (cwd=${File(".").absolutePath})", f.isFile)
        val text = f.readText()
        assertTrue(
            "the child-session check is gone — sub agent completions will post " +
                "system notifications again",
            text.contains("session?.isChild == true"),
        )
        val idx = text.indexOf("session?.isChild == true")
        val after = text.substring(idx, minOf(idx + 400, text.length))
        assertTrue(
            "the check no longer aborts the notification",
            after.contains("return@launch"),
        )
    }
}
