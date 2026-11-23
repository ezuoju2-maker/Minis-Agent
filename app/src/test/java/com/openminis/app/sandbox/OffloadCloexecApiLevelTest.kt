package com.openminis.app.sandbox

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OffloadCloexecApiLevelTest {

    @Test
    fun `close-on-exec is only attempted where fcntlInt is public API`() {
        for (sdk in 26..29) assertFalse("API $sdk", canSetCloseOnExec(sdk))
        for (sdk in 30..36) assertTrue("API $sdk", canSetCloseOnExec(sdk))
    }

    @Test
    fun `the fcntlInt call is gated by API level and guarded against Errors`() {
        val src = ProductionSources.read("sandbox/NativeOffload.kt")
        val body = src.substringAfter("private fun setCloseOnExec(s: LocalServerSocket) {").substringBefore("\n    }\n")
        val gate = body.indexOf("if (!canSetCloseOnExec(android.os.Build.VERSION.SDK_INT))")
        val call = body.indexOf("android.system.Os.fcntlInt(")
        assertTrue("gate present", gate >= 0)
        assertTrue("gate precedes the call", gate in 0 until call)
        assertTrue("an Error must not escape", body.contains("} catch (t: Throwable) {"))
        assertFalse(body.contains("} catch (e: Exception) {"))
        assertTrue("no other fcntlInt call site", src.split("Os.fcntlInt(").size == 2)
    }
}
