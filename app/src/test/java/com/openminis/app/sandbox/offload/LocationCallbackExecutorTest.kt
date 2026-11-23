package com.openminis.app.sandbox.offload

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationCallbackExecutorTest {
    private val src = ProductionSources.read("sandbox/offload/LocationOffloadHandler.kt")

    @Test fun `no executor is created per call`() {
        assertFalse(src.contains("Executors.newSingleThreadExecutor"))
        assertFalse(src.contains("import java.util.concurrent.Executors"))
    }

    @Test fun `the callback only stores the fix and counts down, on a direct executor`() {
        assertTrue(src.contains("private val DIRECT_EXECUTOR = Executor { it.run() }"))
        val call = src.substringAfter("lm.getCurrentLocation(").substringBefore("} else {")
        assertTrue(call.contains("DIRECT_EXECUTOR,"))
        val callback = call.substringAfter("{ loc ->")
        assertTrue(callback.contains("holder[0] = loc") && callback.contains("latch.countDown()"))
    }
}
