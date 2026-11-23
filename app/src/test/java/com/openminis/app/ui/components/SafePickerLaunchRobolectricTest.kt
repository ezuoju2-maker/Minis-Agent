package com.openminis.app.ui.components

import android.app.Application
import android.content.ActivityNotFoundException
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SafePickerLaunchRobolectricTest {

    private fun activity(): ComponentActivity {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        shadowOf(activity.application).checkActivities(true)
        return activity
    }

    @Test
    fun `without the helper an unresolvable picker throws out of launch`() {
        val activity = activity()
        val launcher = activity.activityResultRegistry.register("bare", ActivityResultContracts.OpenMultipleDocuments()) {}
        try {
            launcher.launch(arrayOf("*/*"))
            fail("expected ActivityNotFoundException - the simulation does not reproduce the crash")
        } catch (_: ActivityNotFoundException) {
        }
    }

    @Test
    fun `with the helper the same launch is caught, logged and reported`() {
        val activity = activity()
        var results = 0
        val launcher = activity.activityResultRegistry.register("safe", ActivityResultContracts.OpenMultipleDocuments()) {
            results++
        }
        val logs = mutableListOf<String>()
        var notified = 0

        val launched = launchPickerSafely(
            launch = { launcher.launch(arrayOf("*/*")) },
            log = { logs += it },
            notify = { notified++ },
        )

        assertFalse(launched)
        assertEquals(1, logs.size)
        assertTrue(logs.single(), logs.single().startsWith("picker unavailable:"))
        assertEquals(1, notified)
        assertEquals(0, results)
    }

    @Test
    fun `when the picker resolves it is launched and no toast is shown`() {
        val activity = activity()
        shadowOf(activity.application).checkActivities(false)
        val launcher = activity.activityResultRegistry.register("ok", ActivityResultContracts.OpenMultipleDocuments()) {}

        assertTrue(launcher.safeLaunchPicker(arrayOf("*/*"), activity, "Test"))
        assertEquals("android.intent.action.OPEN_DOCUMENT", shadowOf(activity).nextStartedActivityForResult.intent.action)
        assertNull(ShadowToast.getLatestToast())
    }
}
