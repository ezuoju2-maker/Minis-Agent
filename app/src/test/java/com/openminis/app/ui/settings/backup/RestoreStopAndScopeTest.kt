package com.openminis.app.ui.settings.backup

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreStopAndScopeTest {

    private val vm by lazy {
        ProductionSources.read("ui/settings/backup/BackupViewModel.kt")
    }
    private val screen by lazy {
        ProductionSources.read("ui/settings/backup/BackupAndRestoreScreen.kt")
    }
    private val importer by lazy {
        ProductionSources.read("backup/BackupImporter.kt")
    }


    @Test
    fun `the restore coroutine is retained so it can be cancelled`() {
        assertTrue("restoreJob field missing", vm.contains("private var restoreJob"))
        assertTrue(
            "startRestore must assign the job",
            vm.contains("restoreJob = viewModelScope.launch {"),
        )
    }

    @Test
    fun `stopRunningRestore cancels and clears the live state`() {
        val fn = vm.substringAfter("fun stopRunningRestore()").substringBefore("\n    }")
        assertTrue("must cancel the job", fn.contains("job.cancel()"))
        assertTrue("must clear isRunning", fn.contains("_isRunning.value = false"))
        assertTrue("must clear progress", fn.contains("_restoreProgress.value = null"))
        assertTrue("must clear the estimate", fn.contains("_restoreEtaSeconds.value = null"))
    }

    @Test
    fun `cancellation is not reported to the user as a failure`() {
        val body = vm.substringAfter("restoreJob = viewModelScope.launch {")
            .substringBefore("fun stopRunningRestore()")
        val cancelAt = body.indexOf("catch (e: kotlinx.coroutines.CancellationException)")
        val genericAt = body.indexOf("catch (e: Exception)")
        assertTrue("no CancellationException clause", cancelAt > 0)
        assertTrue("it must precede the generic catch", cancelAt < genericAt)
        val clause = body.substring(cancelAt, genericAt)
        assertTrue("must rethrow", clause.contains("throw e"))
        assertTrue("must not set an error", !clause.contains("_errorText"))
    }

    @Test
    fun `the importer checks for cancellation inside its long loops`() {
        assertTrue(
            "the messages loop must check",
            importer.contains("coroutineContext.ensureActive()"),
        )
        val msgLoop = importer.substringAfter("""readJsonl(dataDir, "messages")""")
            .substringBefore("val createdAt")
        assertTrue(
            "the check belongs on the throttled beat",
            msgLoop.contains("if (++seen % 200 == 0)") && msgLoop.contains("ensureActive()"),
        )
    }


    @Test
    fun `progress is not collected at the screen's top level`() {
        val topLevel = screen.substringBefore("private fun RestoreProgressSection")
        assertEquals(
            "restoreProgress must not be collected outside RestoreProgressSection",
            0,
            Regex("""vm\.restoreProgress\.collectAsState\(\)""").findAll(topLevel).count(),
        )
    }

    @Test
    fun `the section owns both progress and eta`() {
        val section = screen.substringAfter("private fun RestoreProgressSection")
        assertTrue(
            "progress must be collected in the section",
            section.contains("vm.restoreProgress.collectAsState()"),
        )
        assertTrue(
            "eta must be collected in the section",
            section.contains("vm.restoreEtaSeconds.collectAsState()"),
        )
    }


    @Test
    fun `the ring carries a stop target only while running`() {
        val section = screen.substringAfter("private fun RestoreProgressSection")
        assertTrue(
            "the stop handler must be gated on running",
            section.contains("onStopClick = { confirmStop = true }.takeIf { running }"),
        )
    }

    @Test
    fun `stopping asks for confirmation before cancelling`() {
        val section = screen.substringAfter("private fun RestoreProgressSection")
        assertTrue("no dialog", section.contains("AlertDialog("))
        val confirm = section.substringAfter("confirmButton = {").substringBefore("dismissButton")
        assertTrue("confirm must call the ViewModel", confirm.contains("vm.stopRunningRestore()"))
        assertTrue(
            "the destructive action must be coloured as such",
            confirm.contains("MaterialTheme.colorScheme.error"),
        )
        val dismiss = section.substringAfter("dismissButton = {")
        assertTrue("dismiss must not cancel the restore", !dismiss.contains("stopRunningRestore"))
    }

    @Test
    fun `the stop target is big enough to hit`() {
        val content = screen.substringAfter("fun RowScope.PrimaryActionContent")
            .substringBefore("fun ")
        assertTrue("hit target must be 36dp", content.contains(".size(36.dp)"))
        assertTrue("ring should be 24dp", content.contains(".size(24.dp)"))
        assertTrue("stop glyph should be 8dp", content.contains(".size(8.dp)"))
    }

    @Test
    fun `the button is inert while a restore runs`() {
        val section = screen.substringAfter("private fun RestoreProgressSection")
        assertTrue(
            "the button's enabled flag must come from the caller's guard",
            section.contains("enabled = enabled,"),
        )
    }


    @Test
    fun `the label leads with a percentage`() {
        val section = screen.substringAfter("private fun RestoreProgressSection")
        assertTrue(
            "must use the percent string",
            section.contains("R.string.backup_restoring_percent"),
        )
        assertTrue(
            "percent must be clamped",
            section.contains("coerceIn(0, 100)"),
        )
    }

    @Test
    fun `the percentage cannot overflow on a large package`() {
        val section = screen.substringAfter("private fun RestoreProgressSection")
        assertTrue(
            "percent must be computed in Long",
            section.contains("p.done.toLong() * 100 / total"),
        )
    }
}
