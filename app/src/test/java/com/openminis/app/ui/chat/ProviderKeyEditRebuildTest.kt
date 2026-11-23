package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.data.repository.ProviderCredentialEdits
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderKeyEditRebuildTest {

    @Test fun `saving or removing a key announces its instance`() = runBlocking {
        val got = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(2_000) { ProviderCredentialEdits.ids.first() } }
        ProviderCredentialEdits.changed("inst-1")
        assertEquals("inst-1", got.await())
        val repo = ProductionSources.read("data/repository/ProviderRepository.kt")
        assertEquals("saveApiKey and deleteApiKey", 2, Regex("ProviderCredentialEdits\\.changed\\(instanceId\\)").findAll(repo).count())
    }

    @Test fun `the open chat treats a key change as an edit of the instance`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val watch = vm.substringAfter("private val providerKeyWatch = viewModelScope.launch {").substringBefore("\n    }")
        assertTrue(watch.contains("ProviderCredentialEdits.ids.collect"))
        assertTrue(watch.contains("pendingProviderEdits = pendingProviderEdits + id"))
    }
}
