package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatViewModelStoreTrimMemoryTest {

    private fun source(path: String): String {
        val f = File(path)
        assertTrue("missing source: ${f.absolutePath}", f.exists())
        return f.readText()
    }

    private val storeSrc: String by lazy {
        source("src/main/java/com/openminis/app/ui/chat/ChatViewModelStore.kt")
    }
    private val appSrc: String by lazy {
        source("src/main/java/com/openminis/app/MinisApp.kt")
    }

    @Test
    fun `the store exposes a memory-pressure release path`() {
        assertTrue(
            "ChatViewModelStore must expose handleMemoryPressure() — the " +
                "count-based trimToCapacity() cannot respond to external pressure",
            storeSrc.contains("fun handleMemoryPressure()"),
        )
    }

    @Test
    fun `a running agent loop is never released`() {
        val body = storeSrc.substringAfter("fun handleMemoryPressure()")
            .substringBefore("\n    /**")
        assertTrue(
            "must take its busy set from busyKeys()",
            body.contains("val busy = busyKeys()"),
        )
        val busyKeys = storeSrc.substringAfter("private fun busyKeys(").substringBefore("fun ownerFor(")
        assertTrue(
            "must consult SessionActivityTracker — clearing a session whose " +
                "agent loop is live cancels viewModelScope and kills the stream",
            busyKeys.contains("SessionActivityTracker.activeSessions"),
        )
        assertTrue(
            "busy sessions must be excluded from the release set",
            body.contains("it !in busy"),
        )
    }

    @Test
    fun `the on-screen session is never released`() {
        val body = storeSrc.substringAfter("fun handleMemoryPressure()")
            .substringBefore("\n    /**")
        assertTrue(
            "must read the currently-displayed session",
            body.contains("activeSessionIdInternal"),
        )
        assertTrue(
            "the on-screen session must be excluded — releasing it tears the " +
                "store down underneath the composition reading it",
            body.contains("it != onScreen"),
        )
    }

    @Test
    fun `onTrimMemory calls into the store`() {
        assertTrue(
            "MinisApp.onTrimMemory must drive the store's release path",
            appSrc.contains("ChatViewModelStore.handleMemoryPressure()"),
        )
    }

    @Test
    fun `level selection does not use a greater-than comparison`() {
        val block = appSrc.substringAfter("val dropCachedSessions")
            .substringBefore("if (dropCachedSessions)")
        assertFalse(
            "must not use `level >= TRIM_MEMORY_RUNNING_CRITICAL` — that also " +
                "matches UI_HIDDEN (20), i.e. any backgrounding",
            appSrc.contains("level >= TRIM_MEMORY_RUNNING_CRITICAL"),
        )
        assertTrue(
            "RUNNING_CRITICAL is the foreground pressure signal we act on",
            block.contains("TRIM_MEMORY_RUNNING_CRITICAL"),
        )
        assertFalse(
            "UI_HIDDEN must NOT trigger a release",
            block.contains("TRIM_MEMORY_UI_HIDDEN"),
        )
        assertFalse(
            "RUNNING_LOW must NOT trigger a release — dropping a session costs " +
                "a DB reload + re-flatten, too visible for a mild dip",
            block.contains("TRIM_MEMORY_RUNNING_LOW"),
        )
    }
}
