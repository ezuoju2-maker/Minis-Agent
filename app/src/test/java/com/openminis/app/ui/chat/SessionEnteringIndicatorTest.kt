package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEnteringIndicatorTest {

    @Test
    fun `dots show only while entering a real session with nothing to draw yet`() {
        val table = listOf(
            listOf(false, false, true, false) to true,
            listOf(false, false, false, false) to false,
            listOf(false, true, true, false) to false,
            listOf(false, true, false, false) to false,
            listOf(true, false, true, false) to false,
            listOf(false, false, true, true) to false,
        )
        table.forEach { (a, want) ->
            assertEquals(a.toString(), want, showSessionEnteringIndicator(a[0], a[1], a[2], a[3]))
        }
    }

    @Test
    fun `the dots follow the iOS LoadingDotsView timing`() {
        assertEquals(1_000, SessionEnteringDots.PERIOD_MS)
        assertEquals(160, SessionEnteringDots.STAGGER_MS)
        assertEquals(0.55f, SessionEnteringDots.MIN_SCALE)
        assertEquals(0.3f, SessionEnteringDots.MIN_ALPHA)
        assertEquals(0.95f, SessionEnteringDots.MAX_ALPHA)
        assertEquals(280, SessionEnteringDots.EXIT_MS)
    }

    @Test
    fun `the view model reports loaded from loadSession's finally block`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("internal val sessionLoadedState: StateFlow<Boolean> = sessionLoaded.asStateFlow()"))
        assertTrue(vm.contains("internal val isDraftSession: Boolean get() = isDraft"))
        val fin = vm.substringAfter("// T201: open the gate even on early `return@launch`").substringBefore("[T-HANG-DIAG] loadSession EXIT")
        assertTrue(fin.contains("sessionLoaded.value = true"))
    }

    @Test
    fun `the chat screen fades the dots out and the list in`() {
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("val sessionLoadedForDots by viewModel.sessionLoadedState.collectAsState()"))
        assertTrue(screen.contains("showSessionEnteringIndicator(\n"))
        assertTrue(screen.contains("visible = enteringSession,"))
        assertTrue(screen.contains("SessionEnteringDots()"))
        assertTrue(screen.contains(".graphicsLayer { alpha = listEntryAlpha }"))
    }
}
