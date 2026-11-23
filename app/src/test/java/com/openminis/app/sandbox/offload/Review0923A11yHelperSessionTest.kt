package com.openminis.app.sandbox.offload

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Review0923A11yHelperSessionTest {

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    private val coord by lazy { src("src/main/java/com/openminis/app/sandbox/ExecutionCoordinator.kt") }
    private val a11y by lazy { src("src/main/java/com/openminis/app/sandbox/offload/AccessibilityOffloadHandler.kt") }
    private val vm by lazy { src("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt") }

    @Test
    fun `premise - helpers read images and mount dirs by the parent's id`() {
        assertTrue(vm.contains("helperConfig?.parentSessionId ?: activeSessionId"))
        assertTrue(vm.contains("ReadImageTool.execute(argsJson, fsSessionId, context)"))
        assertTrue(coord.contains("buildSessionBindMounts(fsSessionId ?: sessionId)"))
    }

    @Test
    fun `BUG the screenshot directory is resolved with the mounted session id`() {
        val envIsDispatchId = coord.contains("sessionId = sessionId,") &&
            !coord.contains("MINIS_FS_SESSION_ID")
        val handlerKeysOnEnvId = a11y.contains("uiSub(args, request.sessionId)") &&
            a11y.contains("resolveSessionHostPath(it, VAR_MINIS_ATTACHMENTS, context)")
        assertFalse(
            "a helper's a11y screenshot lands in the helper's own attachments dir, " +
                "which neither its shell nor read_image can see",
            envIsDispatchId && handlerKeysOnEnvId,
        )
    }
}
