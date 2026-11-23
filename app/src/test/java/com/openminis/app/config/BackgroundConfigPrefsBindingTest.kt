package com.openminis.app.config

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackgroundConfigPrefsBindingTest {

    private val builtins = File("src/main/java/com/openminis/app/config/ConfigBuiltins.kt").readText()

    private fun registrationFor(path: String): String {
        val at = builtins.indexOf("path = \"$path\"")
        assertTrue("no builtin registers $path", at >= 0)
        return builtins.substring(at, minOf(builtins.length, at + 900))
    }

    @Test
    fun `background_notifications binds the Task Notifications key`() {
        assertTrue(registrationFor("background.notifications").contains("key = BackgroundSettingsRepository.KEY_TASK_NOTIFICATIONS"))
    }

    @Test
    fun `background_dynamicIsland binds the repository key`() {
        assertTrue(registrationFor("background.dynamicIsland").contains("key = BackgroundSettingsRepository.KEY_DYNAMIC_ISLAND_ENABLED"))
    }

    @Test
    fun `the background fields use the repository's prefs file`() {
        assertTrue(builtins.contains("getSharedPreferences(BackgroundSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)"))
    }
}
