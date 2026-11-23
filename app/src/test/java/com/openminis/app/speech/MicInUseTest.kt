package com.openminis.app.speech

import android.media.AudioManager
import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicInUseTest {

    @Test
    fun `another app is blamed only with evidence`() {
        assertEquals(RecognitionError.MIC_IN_USE, MicInUse.classify(callActive = true, silenced = false))
        assertEquals(RecognitionError.MIC_IN_USE, MicInUse.classify(callActive = false, silenced = true))
        assertEquals(RecognitionError.AUDIO_ERROR, MicInUse.classify(callActive = false, silenced = false))
    }

    @Test
    fun `call and VoIP modes count as a call, ringing and normal do not`() {
        assertTrue(MicInUse.isCallMode(AudioManager.MODE_IN_CALL))
        assertTrue(MicInUse.isCallMode(AudioManager.MODE_IN_COMMUNICATION))
        assertTrue(MicInUse.isCallMode(4))
        assertTrue(MicInUse.isCallMode(5))
        assertTrue(MicInUse.isCallMode(6))
        assertFalse(MicInUse.isCallMode(AudioManager.MODE_NORMAL))
        assertFalse(MicInUse.isCallMode(AudioManager.MODE_RINGTONE))
    }

    @Test
    fun `a take the system silenced is not offered for retry`() {
        assertFalse(VoiceAsrFailurePolicy.shouldPromptRetry(RecognitionError.MIC_IN_USE, 12.0))
        assertTrue(VoiceAsrFailurePolicy.shouldPromptRetry(RecognitionError.AUDIO_ERROR, 12.0))
    }

    @Test
    fun `the panel shows translated text for capture failures, never the engine's English`() {
        val panel = ProductionSources.read("ui/chat/voice/InlineVoiceInputPanel.kt")
        val handler = panel.substringAfter("fun onRecognitionError(").substringBefore("fun failureSink(")
        assertTrue(handler.contains("RecognitionError.MIC_IN_USE ->\n                transcribeError = panelContext.getString(R.string.voice_mic_in_use)"))
        assertTrue(handler.contains("RecognitionError.AUDIO_ERROR ->\n                transcribeError = panelContext.getString(R.string.voice_mic_unavailable)"))
    }

    @Test
    fun `every capture path reports a preempted mic`() {
        val vad = ProductionSources.read("speech/VoiceActivityDetector.kt")
        assertTrue("silenced capture is checked while recording", vad.contains("if (MicInUse.isSilenced(context, rec.audioSessionId)) {"))
        assertTrue("a failed start is classified", vad.contains("MicInUse.captureFailure(context, rec.audioSessionId) == RecognitionError.MIC_IN_USE"))

        val provider = ProductionSources.read("speech/ProviderSpeechRecognitionEngine.kt")
        assertTrue(provider.contains("override fun onMicInUse(detail: String) {"))
        assertTrue(provider.contains("listener.onError(MicInUse.captureFailure(appContext), err)"))
        assertTrue("the non-VAD recorder too", provider.contains("if (MicInUse.isSilenced(appContext, recorder.audioSessionId)) {"))
        assertFalse(provider.contains("listener.onError(RecognitionError.AUDIO_ERROR, \"AudioRecord not initialized.\")"))

        val system = ProductionSources.read("speech/SystemSpeechRecognitionEngine.kt")
        assertTrue(system.contains("override fun onMicInUse(detail: String) {"))
        assertTrue("ERROR_AUDIO during a call is not a broken recognizer",
            system.contains("if (it == RecognitionError.AUDIO_ERROR) MicInUse.captureFailure(appContext) else it"))
    }

    @Test
    fun `a preempted mic does not mark the system engine degraded`() {
        val system = ProductionSources.read("speech/SystemSpeechRecognitionEngine.kt")
        val degrade = system.substringAfter("First-class ROM-level failures poison the engine").substringBefore("markDegraded()")
        assertFalse(degrade.contains("MIC_IN_USE"))
    }

    @Test
    fun `the message exists in every language that has it on iOS, plus Traditional Chinese`() {
        for (dir in listOf("values", "values-zh", "values-zh-rTW", "values-hr")) {
            val xml = java.io.File(ProductionSources.mainRoot()!!.parentFile.parentFile.parentFile.parentFile, "res/$dir/strings.xml").readText()
            assertTrue("$dir voice_mic_in_use", xml.contains("name=\"voice_mic_in_use\""))
            assertTrue("$dir voice_mic_unavailable", xml.contains("name=\"voice_mic_unavailable\""))
        }
    }
}
