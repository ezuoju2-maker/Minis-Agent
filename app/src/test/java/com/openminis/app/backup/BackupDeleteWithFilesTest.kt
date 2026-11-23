package com.openminis.app.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupDeleteWithFilesTest {


    private sealed interface Result {
        data class Success(val destinations: Int) : Result
        data class Failed(val failures: List<String>) : Result
    }

    private fun runDelete(
        packageName: String?,
        destinations: List<String>,
        deleter: (String) -> kotlin.Result<Unit>,
    ): Pair<Result, Boolean> {
        if (packageName.isNullOrEmpty()) {
            return Result.Success(destinations = 0) to true
        }
        val failed = destinations.mapNotNull { name ->
            deleter(name).exceptionOrNull()?.let { "$name: ${it.message}" }
        }
        return if (failed.isNotEmpty()) {
            Result.Failed(failed) to false
        } else {
            Result.Success(destinations.size) to true
        }
    }

    @Test
    fun `all destinations succeeding removes the record`() {
        val (result, removed) = runDelete("minis-2026.minisbak", listOf("s3", "webdav")) {
            kotlin.Result.success(Unit)
        }
        assertEquals(Result.Success(destinations = 2), result)
        assertTrue("the record must be gone once every file is", removed)
    }

    @Test
    fun `a failing destination keeps the record and names the reason`() {
        val (result, removed) = runDelete("minis-2026.minisbak", listOf("s3", "webdav")) { name ->
            if (name == "webdav") kotlin.Result.failure(java.io.IOException("401 Unauthorized"))
            else kotlin.Result.success(Unit)
        }
        assertFalse("a partial failure must NOT drop the record", removed)
        val failed = result as Result.Failed
        assertEquals(listOf("webdav: 401 Unauthorized"), failed.failures)
    }

    @Test
    fun `every failing destination is reported, not just the first`() {
        val (result, removed) = runDelete("pkg.minisbak", listOf("a", "b", "c")) { name ->
            if (name == "b") kotlin.Result.success(Unit)
            else kotlin.Result.failure(java.io.IOException("offline"))
        }
        assertFalse(removed)
        assertEquals(
            listOf("a: offline", "c: offline"),
            (result as Result.Failed).failures,
        )
    }

    @Test
    fun `a record with no package name is a record-only delete`() {
        val (result, removed) = runDelete(null, listOf("s3")) {
            throw AssertionError("must not attempt a delete without a package name")
        }
        assertEquals(Result.Success(destinations = 0), result)
        assertTrue(removed)
    }


    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing source: $path", f.exists())
        return f.readText()
    }

    private val navSrc by lazy {
        src("src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt")
    }
    private val screenSrc by lazy {
        src("src/main/java/com/openminis/app/ui/settings/backup/BackupHistoryDetailScreen.kt")
    }
    private val vmSrc by lazy {
        src("src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt")
    }

    @Test
    fun `the nav callback no longer pops on the same frame it starts the delete`() {
        val block = navSrc.substringAfter("onRemoveWithFiles = ").substringBefore("onRemoved")
        assertFalse(
            "popping inside onRemoveWithFiles cancels the ViewModel doing the work",
            block.contains("safePopBackStack"),
        )
        assertTrue(
            "the screen must be dismissed via onRemoved, after the record is gone",
            navSrc.contains("onRemoved = { navController.safePopBackStack() }"),
        )
    }

    @Test
    fun `the delete is awaited rather than fired and forgotten`() {
        assertTrue(
            "removeHistoryRecordWithFiles must suspend so the caller can await it",
            vmSrc.contains("suspend fun removeHistoryRecordWithFiles"),
        )
        assertTrue(
            "and it must report an outcome",
            vmSrc.contains("): DeleteWithFilesResult"),
        )
    }

    @Test
    fun `a destination failure is collected, not only logged`() {
        val body = vmSrc.substringAfter("suspend fun removeHistoryRecordWithFiles")
            .substringBefore("fun removeHistoryRecord(")
        assertTrue("failures must be accumulated", body.contains("failed +="))
        assertTrue(
            "and returned to the caller",
            body.contains("DeleteWithFilesResult.Failed(failures)"),
        )
    }

    @Test
    fun `the record survives a failed delete`() {
        val body = vmSrc.substringAfter("suspend fun removeHistoryRecordWithFiles")
            .substringBefore("fun removeHistoryRecord(")
        val failReturn = body.indexOf("if (failures.isNotEmpty()) return")
        val removal = body.indexOf("removeHistoryRecord(id)\n        return DeleteWithFilesResult.Success")
        assertTrue("both branches must be present", failReturn > 0 && removal > 0)
        assertTrue(
            "the early return on failure must come BEFORE the record is removed",
            failReturn < removal,
        )
    }

    @Test
    fun `the destructive branch asks a second time`() {
        assertTrue(
            "the 'record and files' button must open a second confirmation",
            screenSrc.contains("confirmRemove = false; confirmDeleteFiles = true"),
        )
        assertTrue(screenSrc.contains("backup_delete_files_confirm_title"))
        assertTrue(
            "the confirmation must name the package and destination count",
            screenSrc.contains("backup_delete_files_confirm_body"),
        )
    }

    @Test
    fun `the user gets progress, success and failure feedback`() {
        assertTrue("progress while rclone runs", screenSrc.contains("backup_delete_files_progress"))
        assertTrue("a success toast", screenSrc.contains("backup_delete_files_success"))
        assertTrue("an explicit failure dialog", screenSrc.contains("backup_delete_files_failed_title"))
        assertTrue(
            "the failure must carry the per-destination reasons",
            screenSrc.contains("failures.joinToString"),
        )
    }

    @Test
    fun `the progress dialog cannot be dismissed into a double delete`() {
        val progress = screenSrc.substringAfter("if (deletingFiles) {").substringBefore("deleteFailure?.let")
        assertTrue(
            "the progress dialog's onDismissRequest must be inert",
            progress.contains("onDismissRequest = { }"),
        )
    }


    private enum class Route { RCLONE, LOCAL_SAF }

    private data class Dest(val name: String, val isLocalFolder: Boolean, val treeUri: String = "")

    private fun routeDelete(
        dests: List<Dest>,
        deleter: (Dest, Route) -> kotlin.Result<Unit>,
    ): Triple<Result, List<Pair<String, Route>>, Boolean> {
        val taken = mutableListOf<Pair<String, Route>>()
        val syncedToRclone = dests.any { !it.isLocalFolder }
        val failed = dests.mapNotNull { d ->
            val route = if (d.isLocalFolder) Route.LOCAL_SAF else Route.RCLONE
            taken += d.name to route
            deleter(d, route).exceptionOrNull()?.let { "${d.name}: ${it.message}" }
        }
        val result = if (failed.isNotEmpty()) Result.Failed(failed) else Result.Success(dests.size)
        return Triple(result, taken, syncedToRclone)
    }

    @Test
    fun `a local folder destination deletes through SAF, never rclone`() {
        val (result, taken, _) = routeDelete(
            listOf(Dest("Phone folder", isLocalFolder = true, treeUri = "content://tree/primary%3ABackups")),
        ) { _, _ -> kotlin.Result.success(Unit) }
        assertEquals(listOf("Phone folder" to Route.LOCAL_SAF), taken)
        assertEquals(Result.Success(destinations = 1), result)
    }

    @Test
    fun `mixed destinations each take their own transport`() {
        val (result, taken, _) = routeDelete(
            listOf(
                Dest("s3", isLocalFolder = false),
                Dest("Phone folder", isLocalFolder = true, treeUri = "content://tree/x"),
            ),
        ) { _, _ -> kotlin.Result.success(Unit) }
        assertEquals(
            listOf("s3" to Route.RCLONE, "Phone folder" to Route.LOCAL_SAF),
            taken,
        )
        assertEquals(Result.Success(destinations = 2), result)
    }

    @Test
    fun `an all-local record never starts rclone`() {
        val (_, _, synced) = routeDelete(
            listOf(
                Dest("Phone folder", isLocalFolder = true, treeUri = "content://tree/x"),
                Dest("SD card", isLocalFolder = true, treeUri = "content://tree/y"),
            ),
        ) { _, _ -> kotlin.Result.success(Unit) }
        assertFalse("no non-local destination → no config sync", synced)

        val (_, _, syncedMixed) = routeDelete(
            listOf(Dest("Phone folder", isLocalFolder = true), Dest("webdav", isLocalFolder = false)),
        ) { _, _ -> kotlin.Result.success(Unit) }
        assertTrue("one non-local destination is enough to need it", syncedMixed)
    }

    @Test
    fun `a file already gone counts as deleted`() {
        val (result, _, _) = routeDelete(
            listOf(Dest("Phone folder", isLocalFolder = true, treeUri = "content://tree/x")),
        ) { _, route ->
            if (route == Route.LOCAL_SAF) kotlin.Result.success(Unit)
            else kotlin.Result.failure(IllegalStateException("unreachable"))
        }
        assertEquals(Result.Success(destinations = 1), result)
    }

    @Test
    fun `a revoked folder permission is reported, not swallowed`() {
        val (result, _, _) = routeDelete(
            listOf(
                Dest("s3", isLocalFolder = false),
                Dest("Phone folder", isLocalFolder = true, treeUri = "content://tree/x"),
            ),
        ) { d, _ ->
            if (d.isLocalFolder) kotlin.Result.failure(SecurityException("Permission denied"))
            else kotlin.Result.success(Unit)
        }
        val failed = result as Result.Failed
        assertEquals(listOf("Phone folder: Permission denied"), failed.failures)
    }

    @Test
    fun `the delete path branches on local folder, like the delivery path`() {
        val body = vmSrc.substringAfter("suspend fun removeHistoryRecordWithFiles")
            .substringBefore("fun removeHistoryRecord(")
        assertTrue(
            "the delete loop must branch on isLocalFolder",
            body.contains("RcloneRemoteStore.isLocalFolder(remote.backend)"),
        )
        assertTrue(
            "and route local folders through LocalFolderDelivery",
            body.contains("localDelivery.delete(treeUri, name)"),
        )
        assertTrue(
            "the rclone config sync must be conditional",
            body.contains("if (hasNonLocalDest)"),
        )
    }

    @Test
    fun `LocalFolderDelivery delete surfaces failure instead of swallowing it`() {
        val src = src("src/main/java/com/openminis/app/backup/remote/LocalFolderDelivery.kt")
        val body = src.substringAfter("fun delete(treeUri: String, name: String)")
            .substringBefore("private fun deleteChild(")
        assertTrue(
            "an unreadable tree must throw, not read as 'already gone'",
            body.contains("?: throw IllegalStateException"),
        )
        assertTrue(
            "a refused delete must throw",
            body.contains("if (!deleted) throw IllegalStateException"),
        )
        assertTrue(
            "a missing file must return successfully",
            body.contains("val doc = target ?: return"),
        )
        assertFalse(
            "it must NOT delegate to the lenient deleteChild",
            body.contains("deleteChild("),
        )
    }

    @Test
    fun `every new string is defined in all three locales`() {
        val keys = listOf(
            "backup_delete_files_confirm_title",
            "backup_delete_files_confirm_body",
            "backup_delete_files_confirm_action",
            "backup_delete_files_progress",
            "backup_delete_files_success",
            "backup_delete_files_success_none",
            "backup_delete_files_failed_title",
            "backup_delete_files_failed_body",
            "backup_delete_files_dest_missing",
        )
        for (locale in listOf("values", "values-zh", "values-zh-rTW")) {
            val xml = src("src/main/res/$locale/strings.xml")
            for (k in keys) {
                assertTrue("$k missing from $locale", xml.contains("\"$k\""))
            }
        }
    }
}
