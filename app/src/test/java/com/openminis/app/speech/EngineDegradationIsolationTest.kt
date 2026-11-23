package com.openminis.app.speech

import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineDegradationIsolationTest {

    private class FakeEngine(
        override val id: String,
        private var installed: Boolean,
    ) : SpeechRecognitionEngine {
        private var degraded = false
        override val displayName: String = id
        override val supportsPartialResults: Boolean = false
        override val supportedLocales: List<Locale> = emptyList()
        override val isAvailable: Boolean get() = !degraded && installed
        override fun start(locale: Locale, listener: SpeechRecognitionEngine.Listener) {}
        override fun stop() {}
        override fun cancel() {}
        override fun markDegraded() { degraded = true }
        override fun clearDegraded() { degraded = false }
    }

    private fun anyAvailable(vararg e: SpeechRecognitionEngine) = e.any { it.isAvailable }

    @Test
    fun `a degraded system engine does not mask a working provider engine`() {
        val system = FakeEngine("system", installed = true)
        val provider = FakeEngine("provider", installed = true)

        system.markDegraded()

        assertFalse("system must report itself unavailable", system.isAvailable)
        assertTrue("provider is untouched", provider.isAvailable)
        assertTrue(
            "overall availability must survive on the provider engine",
            anyAvailable(system, provider),
        )
    }

    @Test
    fun `overall availability only drops when every engine is out`() {
        val system = FakeEngine("system", installed = true)
        val provider = FakeEngine("provider", installed = false)

        assertTrue(anyAvailable(system, provider))
        system.markDegraded()
        assertFalse(
            "with no provider configured, a degraded system engine leaves nothing",
            anyAvailable(system, provider),
        )
    }

    @Test
    fun `degradation is reversible so a transient failure is not permanent`() {
        val system = FakeEngine("system", installed = true)
        system.markDegraded()
        assertFalse(system.isAvailable)

        system.clearDegraded()
        assertTrue(
            "a retry must give the engine another chance — degradation used to " +
                "have no reset path at all",
            system.isAvailable,
        )
    }

    @Test
    fun `clearing degradation cannot resurrect an engine that is not installed`() {
        val provider = FakeEngine("provider", installed = false)
        provider.markDegraded()
        provider.clearDegraded()
        assertFalse(provider.isAvailable)
    }
}
