package com.openminis.app.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppThemeSourceGuardTest {

    private val allowed = setOf(
        "MainActivity.kt",
        "Theme.kt",
    )

    @Test
    fun `ui code reads the in-app theme, not the system theme`() {
        val root = File("src/main/java/com/openminis/app")
        assertTrue(
            "source root not found — this test locates sources relative to the " +
                "module dir (cwd=${File(".").absolutePath})",
            root.isDirectory,
        )

        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name !in allowed }
            .forEach { f ->
                f.readLines().forEachIndexed { i, line ->
                    val code = line.substringBefore("//").substringBefore("/*")
                    if (code.contains("isSystemInDarkTheme")) {
                        offenders += "${f.path}:${i + 1}: ${line.trim()}"
                    }
                }
            }

        assertTrue(
            buildString {
                append("isSystemInDarkTheme() tracks the OS setting, not the user's ")
                append("in-app theme (Settings → Appearance). Use ChatColors.isDark ")
                append("so the component follows the theme the app actually resolved.\n")
                append("See GH OpenMinis#187 and commit 8154d9b05.\n\n")
                offenders.forEach { append("  ").append(it).append('\n') }
            },
            offenders.isEmpty(),
        )
    }
}
