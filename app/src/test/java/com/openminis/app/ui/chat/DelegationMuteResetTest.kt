package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationMuteResetTest {

    private val vm by lazy { ProductionSources.read("ui/chat/ChatViewModel.kt") }
    private val clear = "AgentJobRegistry.clearDelegationMute(activeSessionId)"

    private fun head(signature: String, chars: Int = 1500): String {
        val i = vm.indexOf(signature)
        assertTrue("missing $signature", i >= 0)
        return vm.substring(i, minOf(vm.length, i + chars))
    }

    @Test fun `each drained batch starts unmuted`() {
        val loop = head("private suspend fun drainQueuedPrompts(").substringAfter("while (_promptQueue.value.isNotEmpty()) {")
        assertTrue(loop.substringBefore("QueuedPromptBatching.nextDrainBatch").contains(clear))
    }

    @Test fun `an in-loop injected prompt starts unmuted`() {
        assertTrue(head("private suspend fun injectQueuedPromptsAsNewTurn(").contains(clear))
    }

    @Test fun `retry, resume, retry-from-message and headless submits start unmuted`() {
        for (sig in listOf("fun retryLast() {", "fun resume() {", "fun retryFromMessage(messageId: String) {", "private fun sendMessage(")) {
            assertTrue(sig, head(sig, 900).contains(clear))
        }
    }
}
