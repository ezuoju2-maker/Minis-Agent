package com.openminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuBackendStateTest {

    private fun decide(binder: Boolean, granted: Boolean, apk: Boolean) =
        ShizukuBackend.decideState(
            binderAlive = binder,
            permissionGranted = granted,
            managerApkInstalled = apk,
        )


    @Test
    fun `sui - live authorized binder with NO manager apk is READY`() {
        assertEquals(
            ShizukuManager.State.READY,
            decide(binder = true, granted = true, apk = false),
        )
    }

    @Test
    fun `sui - live unauthorized binder with NO manager apk asks for permission`() {
        assertEquals(
            ShizukuManager.State.NEED_PERMISSION,
            decide(binder = true, granted = false, apk = false),
        )
    }


    @Test
    fun `shizuku - authorized binder with manager apk is READY`() {
        assertEquals(
            ShizukuManager.State.READY,
            decide(binder = true, granted = true, apk = true),
        )
    }

    @Test
    fun `shizuku - unauthorized binder with manager apk needs permission`() {
        assertEquals(
            ShizukuManager.State.NEED_PERMISSION,
            decide(binder = true, granted = false, apk = true),
        )
    }

    @Test
    fun `manager apk installed but service not started is NOT_RUNNING`() {
        assertEquals(
            ShizukuManager.State.NOT_RUNNING,
            decide(binder = false, granted = false, apk = true),
        )
    }

    @Test
    fun `nothing available at all is NOT_INSTALLED`() {
        assertEquals(
            ShizukuManager.State.NOT_INSTALLED,
            decide(binder = false, granted = false, apk = false),
        )
    }


    @Test
    fun `binder always wins over the package scan`() {
        for (granted in listOf(true, false)) {
            assertEquals(
                "apk presence must not affect state when binder is alive (granted=$granted)",
                decide(binder = true, granted = granted, apk = true),
                decide(binder = true, granted = granted, apk = false),
            )
        }
    }

    @Test
    fun `a dead binder can never report READY`() {
        assertEquals(
            ShizukuManager.State.NOT_RUNNING,
            decide(binder = false, granted = true, apk = true),
        )
        assertEquals(
            ShizukuManager.State.NOT_INSTALLED,
            decide(binder = false, granted = true, apk = false),
        )
    }

    @Test
    fun `full decision table is exhaustive and stable`() {
        val table = listOf(
            Triple(true, true, true) to ShizukuManager.State.READY,
            Triple(true, true, false) to ShizukuManager.State.READY,
            Triple(true, false, true) to ShizukuManager.State.NEED_PERMISSION,
            Triple(true, false, false) to ShizukuManager.State.NEED_PERMISSION,
            Triple(false, true, true) to ShizukuManager.State.NOT_RUNNING,
            Triple(false, true, false) to ShizukuManager.State.NOT_INSTALLED,
            Triple(false, false, true) to ShizukuManager.State.NOT_RUNNING,
            Triple(false, false, false) to ShizukuManager.State.NOT_INSTALLED,
        )
        for ((input, expected) in table) {
            val (binder, granted, apk) = input
            assertEquals(
                "binder=$binder granted=$granted apk=$apk",
                expected,
                decide(binder, granted, apk),
            )
        }
    }
}
