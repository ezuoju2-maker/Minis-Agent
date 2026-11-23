package com.openminis.app.data.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModalityInferenceGuardTest {

    private fun model(id: String, displayName: String = id) =
        LLMModel(id = id, displayName = displayName, provider = "dashscope")
            .withInferredVoiceModality()

    private fun assertIsAsr(id: String, displayName: String = id) {
        val m = model(id, displayName)
        assertTrue("$id must be recognised as audio-IN (ASR)", m.hasAudioInput)
        assertFalse("$id must NOT be treated as TTS (audio-OUT)", m.hasAudioOutput)
    }


    @Test
    fun `qwen3-asr-flash is an ASR model`() = assertIsAsr("qwen3-asr-flash")

    @Test
    fun `fun-asr-flash is an ASR model`() = assertIsAsr("fun-asr-flash")

    @Test
    fun `paraformer-realtime-v2 is an ASR model and never a TTS one`() {
        assertIsAsr("paraformer-realtime-v2")
    }

    @Test
    fun `SenseVoiceSmall is an ASR model`() {
        assertIsAsr("SenseVoiceSmall")
    }

    @Test
    fun `Qwen3-ASR-1_7B is an ASR model`() = assertIsAsr("Qwen3-ASR-1.7B")

    @Test
    fun `the inferred ASR shape is exactly audio-in text-out`() {
        val m = model("paraformer-realtime-v2")
        assertEquals(listOf("audio"), m.inputModalities)
        assertEquals(listOf("text"), m.outputModalities)
        assertFalse("an inferred model must not look like a template seed", m.isVoiceTemplateSeedShape)
    }


    @Test
    fun `a real TTS model is still inferred as audio-out`() {
        val tts = model("cosyvoice-tts-v2")
        assertTrue(tts.hasAudioOutput)
        assertFalse(tts.hasAudioInput)
    }

    @Test
    fun `an ordinary chat model gains no voice modality`() {
        val chat = model("gpt-5.6-terra")
        assertFalse(chat.hasAudioInput)
        assertFalse(chat.hasAudioOutput)
    }


    @Test
    fun `a manual audio_input override is normalized and actually takes effect`() {
        val entry = ModelEntry(
            providerInstanceId = "inst-A",
            baseModel = LLMModel(id = "some-asr-box", displayName = "Some ASR Box", provider = "custom"),
            overrides = ModelOverrides(inputModalities = listOf("audio_input")),
        )
        assertTrue("a hand-set audio_input override must report audio IN", entry.model.hasAudioInput)
        assertEquals(listOf("audio"), entry.model.inputModalities)
    }

    @Test
    fun `an audio_output override normalizes too`() {
        val entry = ModelEntry(
            providerInstanceId = "inst-A",
            baseModel = LLMModel(id = "some-tts-box", displayName = "Some TTS Box", provider = "custom"),
            overrides = ModelOverrides(outputModalities = listOf("audio_output")),
        )
        assertTrue(entry.model.hasAudioOutput)
        assertEquals(listOf("audio"), entry.model.outputModalities)
    }

    @Test
    fun `an already-bare override is unchanged`() {
        val entry = ModelEntry(
            providerInstanceId = "inst-A",
            baseModel = LLMModel(id = "m", displayName = "m", provider = "custom"),
            overrides = ModelOverrides(inputModalities = listOf("audio", "text")),
        )
        assertEquals(listOf("audio", "text"), entry.model.inputModalities)
        assertTrue(entry.model.hasAudioInput)
    }


    @Test
    fun `the four vendor-family patterns are present in the shipping source`() {
        val src = File("src/main/java/com/openminis/app/data/model/VoiceModality.kt")
        assertTrue("missing ${src.absolutePath}", src.exists())
        val code = src.readText()
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        for (p in listOf("paraformer", "sensevoice", "fun-asr", "qwen-asr")) {
            assertTrue("asrInferencePatterns must contain \"$p\"", code.contains("\"$p\""))
        }
    }
}
