package com.openminis.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KeepScreenOnOrientationTest {

    private val keepOn = 0x00000080

    @Test
    fun `the window is touched only when the flag changes`() {
        assertFalse("already on", keepScreenOnNeedsChange(keepOn, want = true))
        assertFalse("already off", keepScreenOnNeedsChange(0, want = false))
        assertTrue("turn on", keepScreenOnNeedsChange(0, want = true))
        assertTrue("turn off", keepScreenOnNeedsChange(keepOn, want = false))
        assertFalse(keepScreenOnNeedsChange(keepOn or 0x01000000, want = true))
        assertTrue(keepScreenOnNeedsChange(0x01000000, want = true))
    }

    @Test
    fun `applyKeepScreenAwakeFlag returns early when nothing changes`() {
        val src = ProductionSources.read("MainActivity.kt")
        val body = src.substringAfter("private fun applyKeepScreenAwakeFlag(").substringBefore("\n    private fun ")
        val guard = body.indexOf("if (!keepScreenOnNeedsChange(window.attributes.flags, want)) return")
        assertTrue(guard >= 0)
        assertTrue("guard runs before any flag write", guard < body.indexOf("window.addFlags"))
        assertTrue(guard < body.indexOf("window.clearFlags"))
    }

    @Test
    fun `MainActivity follows the user's rotation setting`() {
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml", "src/android/app/src/main/AndroidManifest.xml")
            .map { File(it) }.first { it.isFile }.readText()
        val main = manifest.substringAfter("android:name=\".MainActivity\"").substringBefore(">")
        assertTrue(main.contains("android:screenOrientation=\"user\""))
    }
}
