package com.openminis.app.config

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatConfigPrefsBindingTest {

    private fun source(relative: String): String {
        val f = File("src/main/java/com/openminis/app/$relative")
        assertTrue("source not found: ${f.absolutePath}", f.isFile)
        return f.readText()
    }

    private val builtins by lazy { source("config/ConfigBuiltins.kt") }

    private fun registrationFor(path: String): String {
        val marker = "path = \"$path\""
        val at = builtins.indexOf(marker)
        assertTrue("no builtin registers $path", at >= 0)
        val end = builtins.indexOf("\n        )", at)
        return builtins.substring(at, if (end > at) end else at + 600)
    }


    @Test
    fun `chat_returnKey binds to the appearance prefs the UI reads`() {
        val reg = registrationFor("chat.returnKey")
        assertTrue(
            "chat.returnKey must use appearancePrefs — writing minis_settings " +
                "makes `minis-config set` a silent no-op:\n$reg",
            reg.contains("prefs = appearancePrefs"),
        )
        assertTrue(
            "chat.returnKey must bind the shared KEY_RETURN_KEY_BEHAVIOR " +
                "constant rather than a hand-typed key:\n$reg",
            reg.contains("KEY_RETURN_KEY_BEHAVIOR"),
        )
    }

    @Test
    fun `chat_keepScreenAwake binds to the appearance prefs the UI reads`() {
        val reg = registrationFor("chat.keepScreenAwake")
        assertTrue("must use appearancePrefs:\n$reg", reg.contains("prefs = appearancePrefs"))
        assertTrue("must use KEY_KEEP_SCREEN_AWAKE:\n$reg", reg.contains("KEY_KEEP_SCREEN_AWAKE"))
    }

    @Test
    fun `chat_toolPreview stays correct`() {
        val reg = registrationFor("chat.toolPreview")
        assertTrue(reg.contains("prefs = appearancePrefs"))
        assertTrue(reg.contains("KEY_TOOL_PREVIEW"))
    }


    @Test
    fun `no builtin writes a UI-owned key into the wrong prefs store`() {
        val appearance = source("ui/settings/AppearanceScreen.kt")
        val uiKeyValues = Regex("""const val KEY_\w+ = "([^"]+)"""")
            .findAll(appearance).map { it.groupValues[1] }.toSet()

        // `prefs` is re-declared per registerX() function, each opening a
        // DIFFERENT store, so the handle NAME alone says nothing. Walk the file
        // in order and track which store the nearest preceding declaration
        // opened — that is the one a `prefs = prefs` reference resolves to.
        val events = Regex(
            """val (\w+) = context\.getSharedPreferences\(\s*(?:"([^"]+)"|([\w.]+))""" +
                """|prefs = (\w+),\s*\n\s*key = (?:"([^"]+)"|[\w.]+\.(KEY_\w+))""",
        ).findAll(builtins)

        val storeOf = mutableMapOf<String, String>()
        val offenders = mutableListOf<String>()
        for (m in events) {
            val declName = m.groupValues[1]
            if (declName.isNotEmpty()) {
                val literal = m.groupValues[2]
                val viaConst = m.groupValues[3]
                storeOf[declName] =
                    if (literal.isNotEmpty()) literal
                    else if (viaConst.endsWith("PREF_APPEARANCE")) "appearance_prefs"
                    else viaConst
                continue
            }
            val handle = m.groupValues[4]
            val literalKey = m.groupValues[5]
            if (literalKey.isEmpty()) continue
            if (literalKey in uiKeyValues && storeOf[handle] != "appearance_prefs") {
                offenders.add("key=$literalKey via prefs=$handle -> ${storeOf[handle]}")
            }
        }
        assertEquals(
            "these builtins write a UI-owned key into a store the UI never " +
                "reads, so `minis-config set` silently no-ops: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the appearance prefs store is resolved from the shared constant`() {
        assertTrue(
            "appearancePrefs must be built from PREF_APPEARANCE",
            builtins.contains("com.openminis.app.ui.settings.PREF_APPEARANCE"),
        )
    }
}
