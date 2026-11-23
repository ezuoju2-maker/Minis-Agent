package com.openminis.app.ui.sandbox

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePreviewStatusBarTest {

    @Test
    fun `the preview is still a dialog destination`() {
        val nav = ProductionSources.read("ui/navigation/AppNavigation.kt")
        assertTrue(nav.contains("dialog(\n            route = Routes.FILE_PREVIEW,"))
    }

    @Test
    fun `the preview sets its own window's status bar icons from the theme`() {
        val src = ProductionSources.read("ui/sandbox/FilePreviewScreen.kt")
        assertTrue(src.contains("filePreviewDialogWindow(hostView)?.let { w ->"))
        assertTrue(src.contains("isAppearanceLightStatusBars = !previewDark"))
        assertTrue(src.contains("isAppearanceLightNavigationBars = !previewDark"))
        assertTrue(src.contains("if (p is androidx.compose.ui.window.DialogWindowProvider) return p.window"))
        val effect = src.indexOf("val previewDark = com.openminis.app.ui.theme.ChatColors.isDark")
        assertTrue(effect > src.indexOf("if (item.isImageFile) {"))
        assertTrue(effect < src.indexOf("    Scaffold(\n        topBar"))
    }
}
