package com.openminis.app.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreSourceEntriesTest {

    private val screen: String by lazy {
        val f = File("src/main/java/com/openminis/app/ui/settings/backup/BackupAndRestoreScreen.kt")
        assertTrue("expected to find ${f.path} relative to the app module", f.isFile)
        f.readText()
    }

    private val restoreTab: String by lazy {
        val start = screen.indexOf("private fun RestoreTab(")
        assertTrue("RestoreTab not found", start >= 0)
        val end = screen.indexOf("\n@Composable", start + 1).let { if (it < 0) screen.length else it }
        screen.substring(start, end)
    }

    @Test
    fun `restore offers exactly two sources — local file and server`() {
        assertTrue(
            "the local-file source row should still exist",
            restoreTab.contains("R.string.backup_choose_file"),
        )
        assertTrue(
            "the server source row should still exist",
            restoreTab.contains("R.string.backup_choose_server"),
        )
    }

    @Test
    fun `the shared-folders source entry is gone`() {
        assertFalse(
            "the Shared Folders restore source should not come back",
            screen.contains("backup_choose_shared"),
        )
        assertFalse(
            "SharedFoldersDialog should not come back",
            screen.contains("SharedFoldersDialog"),
        )
    }

    @Test
    fun `its strings are gone from every locale`() {
        val dead = listOf("backup_choose_shared", "backup_shared_title", "backup_shared_empty")
        for (locale in listOf("values", "values-zh", "values-es")) {
            val f = File("src/main/res/$locale/strings.xml")
            assertTrue("missing $locale/strings.xml", f.isFile)
            val xml = f.readText()
            for (key in dead) {
                assertFalse("$locale still declares $key", xml.contains("\"$key\""))
            }
        }
    }

    @Test
    fun `the server row always opens the restore-server list, with no empty-case branch`() {
        val rowStart = restoreTab.indexOf("R.string.backup_choose_server")
        assertTrue("server row not found in RestoreTab", rowStart >= 0)
        val rowEnd = restoreTab.indexOf("showDivider", rowStart)
        assertTrue("could not find the end of the server row", rowEnd > rowStart)
        val body = restoreTab.substring(rowStart, rowEnd)

        assertTrue(
            "the server row must go to the restore-server list",
            body.contains("onClick = onChooseRestoreServer"),
        )
        assertFalse(
            "the empty state must not be a special case again",
            body.contains("destinations.isEmpty()"),
        )
    }

    @Test
    fun `the dead-end server dialog is gone`() {
        assertFalse(
            "ServerRestoreDialog should not come back",
            screen.contains("ServerRestoreDialog"),
        )
        assertFalse(
            "its empty-state string should no longer be rendered",
            screen.contains("backup_server_empty"),
        )
    }

    @Test
    fun `the restore-server list always offers Add Server, in both states`() {
        val list = File(
            "src/main/java/com/openminis/app/ui/settings/backup/RestoreServersScreen.kt",
        ).readText()
        val loopEnd = list.indexOf("remotes.forEach")
        assertTrue("the servers loop should exist", loopEnd >= 0)
        val addIdx = list.indexOf("R.string.backup_dest_add_server")
        assertTrue("an Add Server row should exist", addIdx > loopEnd)
        assertTrue(
            "picking a server should browse it",
            list.contains("onClick = { onPickServer(r.name) }"),
        )
    }

    @Test
    fun `the Add Server form is reused, not reimplemented`() {
        val list = File(
            "src/main/java/com/openminis/app/ui/settings/backup/RestoreServersScreen.kt",
        ).readText()
        assertTrue(
            "the list should host the shared AddServerForm",
            list.contains("AddServerForm("),
        )
        assertFalse(
            "it must not declare its own form",
            list.contains("fun AddServerForm("),
        )
        assertEquals(
            "there should be exactly one AddServerForm in the app",
            1,
            Regex("""fun AddServerForm\(""")
                .findAll(
                    File(
                        "src/main/java/com/openminis/app/ui/settings/backup/RcloneDestinationsScreen.kt",
                    ).readText(),
                ).count(),
        )
    }

    @Test
    fun `cancelling the add form returns to the list, not out of the screen`() {
        val list = File(
            "src/main/java/com/openminis/app/ui/settings/backup/RestoreServersScreen.kt",
        ).readText()
        assertTrue(
            "cancel should return to the list",
            list.contains("onCancel = { adding = false }"),
        )
    }

    @Test
    fun `the abandoned direct-to-form entry point is gone`() {
        val dest = File(
            "src/main/java/com/openminis/app/ui/settings/backup/RcloneDestinationsScreen.kt",
        ).readText()
        val nav = File(
            "src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt",
        ).readText()
        for (marker in listOf("startOnAddForm", "BACKUP_DESTINATIONS_ADD")) {
            assertFalse("$marker should have been withdrawn", dest.contains(marker))
            assertFalse("$marker should have been withdrawn", nav.contains(marker))
        }
    }
}
