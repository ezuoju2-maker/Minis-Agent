package com.openminis.app.deeplink

import android.net.Uri
import com.openminis.app.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeepLinkHandlerTest {

    private fun parse(raw: String?) = DeepLinkHandler.parse(raw?.let { Uri.parse(it) })

    @Test
    fun nonMinisUrisAreIgnored() {
        assertTrue(parse(null) is DeepLinkAction.Unknown)
        assertTrue(parse("https://example.com/settings") is DeepLinkAction.Unknown)
        assertTrue(parse("minis") is DeepLinkAction.Unknown)
    }

    @Test
    fun shortcutsAndTerminal() {
        assertTrue(parse("minis://share") is DeepLinkAction.OpenShare)
        assertTrue(parse("minis://views/alarm") is DeepLinkAction.OpenAlarmList)
        val terminal = parse("minis://open_terminal?init_command=ssh%20user%40host")
        assertTrue(terminal is DeepLinkAction.OpenTerminal)
        assertEquals("ssh user@host", (terminal as DeepLinkAction.OpenTerminal).initCommand)
        assertTrue(parse("minis://action/new_chat") is DeepLinkAction.NewChat)
        assertTrue(parse("minis://action/voice_chat") is DeepLinkAction.NewVoiceChat)
        assertTrue(parse("minis://action/camera_chat") is DeepLinkAction.NewCameraChat)
    }

    @Test
    fun settingsPathsMapToTheirScreens() {
        val expected = mapOf(
            "minis://settings/providers" to Routes.PROVIDER_LIST,
            "minis://settings/model-groups" to Routes.MODEL_GROUPS,
            "minis://settings/usage" to Routes.USAGE_STATS,
            "minis://settings/skills" to Routes.SKILLS,
            "minis://settings/memory" to Routes.MEMORY,
            "minis://settings/agents" to Routes.AGENTS,
            "minis://settings/storage" to Routes.STORAGE,
            "minis://settings/shared-folders" to Routes.SHARED_FOLDERS,
            "minis://settings/mount-external" to Routes.MOUNTED_FOLDERS,
            "minis://settings/logs" to Routes.LOGS,
            "minis://settings/appearance" to Routes.APPEARANCE,
            "minis://settings/background" to Routes.BACKGROUND,
            "minis://settings/about" to Routes.ABOUT,
            "minis://settings/network" to Routes.NETWORK,
            "minis://settings/tailscale" to Routes.NETWORK_TAILSCALE,
            "minis://settings/proxy" to Routes.NETWORK_PROXIES,
            "minis://settings/dns" to Routes.CONTAINER_DNS,
            "minis://settings/rootfs" to Routes.ROOTFS_MANAGEMENT,
            "minis://settings/github" to Routes.GITHUB,
        )
        for ((raw, route) in expected) {
            val action = parse(raw)
            assertTrue("$raw should open a settings screen", action is DeepLinkAction.OpenSettingsScreen)
            assertEquals(raw, route, (action as DeepLinkAction.OpenSettingsScreen).route)
        }
    }

    @Test
    fun bareSettingsAndUnknownPathsFallBackToSettingsHome() {
        assertEquals(
            Routes.SETTINGS,
            (parse("minis://settings") as DeepLinkAction.OpenSettingsScreen).route,
        )
        assertEquals(
            Routes.SETTINGS,
            (parse("minis://settings/not-a-screen") as DeepLinkAction.OpenSettingsScreen).route,
        )
    }

    @Test
    fun detailRoutesKeepTheirArguments() {
        assertEquals(
            Routes.providerDetail("abc"),
            (parse("minis://settings/providers/abc") as DeepLinkAction.OpenSettingsScreen).route,
        )
        assertEquals(
            Routes.modelGroupDetail("g1"),
            (parse("minis://settings/model-groups/g1") as DeepLinkAction.OpenSettingsScreen).route,
        )
    }

    @Test
    fun environmentVariableCreationReadsQueryParameters() {
        val action = parse(
            "minis://settings/environments?create_key=FOO&create_value=bar&create_note=n",
        )
        assertTrue(action is DeepLinkAction.CreateEnvironmentVariable)
        val env = action as DeepLinkAction.CreateEnvironmentVariable
        assertEquals("FOO", env.key)
        assertEquals("bar", env.value)
        assertEquals("n", env.note)
        assertEquals(
            Routes.ENV_VARS,
            (parse("minis://settings/environments") as DeepLinkAction.OpenSettingsScreen).route,
        )
    }

    @Test
    fun sessionsAndHtmlPreview() {
        assertEquals(
            "sid1",
            (parse("minis://session/sid1") as DeepLinkAction.OpenSession).sessionId,
        )
        val html = parse("minis://session/sid2/html/index.html?title=Report")
        assertTrue(html is DeepLinkAction.OpenHtmlPreview)
        val preview = html as DeepLinkAction.OpenHtmlPreview
        assertEquals("sid2", preview.sessionId)
        assertEquals("/html/index.html", preview.resourcePath)
        assertEquals("Report", preview.title)
    }

    @Test
    fun permissionsLinkOpensThePermissionScreen() {
        assertTrue(parse("minis://settings/permissions") is DeepLinkAction.OpenPermissionSettings)
    }
}
