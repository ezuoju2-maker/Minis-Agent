package com.openminis.app.provider.voice

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.VoiceRole
import com.openminis.app.data.model.hasAudioInput
import com.openminis.app.data.model.hasAudioOutput
import com.openminis.app.data.model.isVoiceInputCandidate
import com.openminis.app.data.model.isVoiceOutputCandidate
import com.openminis.app.provider.openrouter.OpenRouterModelsApi
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenRouterVoiceRoutingTest {

    private fun provider() =
        OpenRouterVoiceProvider("test-instance", "https://openrouter.ai/api", "sk-test")

    private fun model(id: String) = LLMModel(id = id, displayName = id, provider = "OpenRouter")


    private val speechIds = listOf(
        "deepgram/flux-tts:free", "fish-audio/s1", "fish-audio/s2-pro",
        "fish-audio/s2.1-pro-free:free", "fish-audio/s2.1-pro", "microsoft/mai-voice-2-flash",
        "qwen/qwen-audio-3.0-tts-flash", "qwen/qwen-audio-3.0-tts-plus", "deepgram/aura-2",
        "minimax/speech-2.8-hd", "minimax/speech-2.8-turbo", "microsoft/mai-voice-2",
        "x-ai/grok-voice-tts-1.0", "google/gemini-3.1-flash-tts-preview",
        "canopylabs/orpheus-3b-0.1-ft", "sesame/csm-1b", "hexgrad/kokoro-82m",
        "mistralai/voxtral-mini-tts-2603",
    )

    private val transcriptionIds = listOf(
        "assemblyai/universal-3-5-pro", "meta/muse-voice-transcribe-1.0",
        "microsoft/mai-transcribe-2", "nvidia/nemotron-3.5-asr-streaming-multilingual-0.6b",
        "mistralai/voxtral-small-24b-2507-stt", "mistralai/voxtral-mini-3b-2507",
        "qwen/qwen3-asr-1.7b", "qwen/qwen3-asr-0.6b", "openai/gpt-transcribe",
        "fish-audio/transcribe-1", "x-ai/grok-stt-1.0", "deepgram/nova-3",
        "microsoft/mai-transcribe-1.5", "nvidia/parakeet-tdt-0.6b-v3",
        "mistralai/voxtral-mini-transcribe", "qwen/qwen3-asr-flash-2026-02-10",
        "google/chirp-3", "openai/gpt-4o-mini-transcribe", "openai/whisper-large-v3",
        "openai/whisper-large-v3-turbo", "openai/whisper-1", "openai/gpt-4o-transcribe",
    )

    private fun chat(id: String, ins: List<String>, outs: List<String>) = LLMModel(
        id = id, displayName = id, provider = "OpenRouter",
        inputModalities = ins, outputModalities = outs,
    )

    private val museSpark = chat("meta/muse-spark-1.3", listOf("text", "image", "video", "file", "audio"), listOf("text"))
    private val lyria = chat("google/lyria-3-pro-preview", listOf("text", "image"), listOf("text", "audio"))
    private val gptAudio = chat("openai/gpt-audio", listOf("text", "audio"), listOf("text", "audio"))
    private val gptAudioMini = chat("openai/gpt-audio-mini", listOf("text", "audio"), listOf("text", "audio"))
    private val gemini = chat("google/gemini-3.6-flash", listOf("text", "image", "video", "file", "audio"), listOf("text"))

    private val catalog: List<LLMModel> by lazy {
        OpenRouterModelsApi.mergeVoiceCatalogs(
            listOf(museSpark, lyria, gptAudio, gptAudioMini, gemini),
            speechIds.map { chat(it, listOf("text"), listOf("speech")) },
            transcriptionIds.map { chat(it, listOf("audio"), listOf("transcription")) },
        )
    }

    private fun tagged(id: String) = catalog.first { it.id == id }


    @Test
    fun `the merged catalog tags each model by its source list`() {
        assertEquals(5 + 18 + 22, catalog.size)
        for (id in speechIds) {
            val m = tagged(id)
            assertEquals(id, VoiceRole.TTS, m.voiceRole)
            assertEquals(id, listOf("text"), m.inputModalities)
            assertEquals(id, listOf("audio"), m.outputModalities)
        }
        for (id in transcriptionIds) {
            val m = tagged(id)
            assertEquals(id, VoiceRole.STT, m.voiceRole)
            assertEquals(id, listOf("audio"), m.inputModalities)
            assertEquals(id, listOf("text"), m.outputModalities)
        }
        for (m in listOf(museSpark, lyria, gptAudio, gptAudioMini, gemini)) {
            assertEquals(m.id, VoiceRole.NONE, tagged(m.id).voiceRole)
            assertEquals(m.id, m.inputModalities, tagged(m.id).inputModalities)
        }
    }

    @Test
    fun `a filter list wins an id collision`() {
        val merged = OpenRouterModelsApi.mergeVoiceCatalogs(
            listOf(chat("x/dual", listOf("text"), listOf("text"))),
            listOf(chat("x/dual", listOf("text"), listOf("speech"))),
            emptyList(),
        )
        assertEquals(1, merged.size)
        assertEquals(VoiceRole.TTS, merged.single().voiceRole)
    }


    @Test
    fun `speech models are TTS candidates and only that`() {
        for (id in speechIds) {
            assertTrue(id, tagged(id).isVoiceOutputCandidate)
            assertFalse(id, tagged(id).isVoiceInputCandidate)
        }
    }

    @Test
    fun `transcription models are ASR candidates and only that`() {
        for (id in transcriptionIds) {
            assertTrue(id, tagged(id).isVoiceInputCandidate)
            assertFalse(id, tagged(id).isVoiceOutputCandidate)
        }
    }

    @Test
    fun `Muse Spark and Lyria are no longer voice candidates`() {
        assertFalse(tagged(museSpark.id).isVoiceInputCandidate)
        assertFalse(tagged(museSpark.id).isVoiceOutputCandidate)
        assertFalse(tagged(lyria.id).isVoiceOutputCandidate)
        assertFalse(tagged(lyria.id).isVoiceInputCandidate)
        assertFalse(tagged(gemini.id).isVoiceInputCandidate)
    }

    @Test
    fun `the global audio flags are untouched`() {
        assertTrue(tagged(museSpark.id).hasAudioInput)
        assertTrue(tagged(lyria.id).hasAudioOutput)
    }

    @Test
    fun `the chat-audio allowlist serves both directions`() {
        for (m in listOf(gptAudio, gptAudioMini)) {
            assertTrue(m.id, tagged(m.id).isVoiceInputCandidate)
            assertTrue(m.id, tagged(m.id).isVoiceOutputCandidate)
        }
    }

    @Test
    fun `an untagged custom entry keeps the modality rule`() {
        val custom = chat("fish-audio/s9-custom", listOf("text"), listOf("audio"))
        assertNull(custom.voiceRole)
        assertTrue(custom.isVoiceOutputCandidate)
        assertEquals(OpenRouterVoiceProvider.TtsRoute.SPEECH_ENDPOINT,
            OpenRouterVoiceProvider.ttsRoute(custom.id, custom))
    }


    @Test
    fun `speech models route to the speech endpoint`() {
        for (id in speechIds) {
            assertEquals(id, OpenRouterVoiceProvider.TtsRoute.SPEECH_ENDPOINT,
                OpenRouterVoiceProvider.ttsRoute(id, tagged(id)))
        }
    }

    @Test
    fun `all 22 transcription models route to the transcriptions endpoint`() {
        for (id in transcriptionIds) {
            assertFalse(id, provider().usesChatBasedASR(tagged(id)))
        }
    }

    @Test
    fun `10 of them carry no name hint, so only the tag routes them`() {
        val nameless = transcriptionIds.filterNot { OpenRouterVoiceProvider.isDedicatedTranscriptionModel(it) }
        assertEquals(10, nameless.size)
        for (id in nameless) {
            assertTrue(id, provider().usesChatBasedASR(model(id)))
            assertFalse(id, provider().usesChatBasedASR(tagged(id)))
        }
    }

    @Test
    fun `chat-audio models keep the chat routes`() {
        for (m in listOf(gptAudio, gptAudioMini)) {
            assertEquals(OpenRouterVoiceProvider.TtsRoute.CHAT_AUDIO,
                OpenRouterVoiceProvider.ttsRoute(m.id, tagged(m.id)))
            assertTrue(provider().usesChatBasedASR(tagged(m.id)))
        }
    }

    @Test
    fun `a tagged chat model is refused for TTS instead of guessed`() {
        assertEquals(OpenRouterVoiceProvider.TtsRoute.UNSUPPORTED,
            OpenRouterVoiceProvider.ttsRoute(lyria.id, tagged(lyria.id)))
        assertEquals(OpenRouterVoiceProvider.TtsRoute.UNSUPPORTED,
            OpenRouterVoiceProvider.ttsRoute(museSpark.id, tagged(museSpark.id)))
        try {
            runBlocking {
                provider().synthesize(VoiceOutputRequest(input = "hi", model = lyria.id, resolvedModel = tagged(lyria.id)))
            }
            fail("expected Unsupported")
        } catch (e: VoiceProviderException.Unsupported) {
            assertTrue(e.message!!.contains("does not support speech synthesis"))
        }
    }

    @Test
    fun `the speech request passes the voice through and carries attribution`() {
        val p = provider()
        fun body(voice: String?): JSONObject {
            val req = p.buildVoiceOutputRequest(
                VoiceOutputRequest(input = "hi", model = "fish-audio/s1", voice = voice),
            )
            assertTrue(req.url.toString().endsWith("/api/v1/audio/speech"))
            assertEquals("https://github.com/OpenMinis/OpenMinis", req.header("HTTP-Referer"))
            assertEquals("Minis App", req.header("X-Title"))
            assertEquals("Bearer sk-test", req.header("Authorization"))
            val buf = Buffer()
            req.body!!.writeTo(buf)
            return JSONObject(buf.readUtf8())
        }
        assertEquals("fish-voice-7f3a", body("fish-voice-7f3a").getString("voice"))
        assertFalse(body(null).has("voice"))
        assertFalse(body("").has("voice"))
        assertFalse(body("fish-audio/s1").has("voice"))
        assertEquals("fish-audio/s1", body(null).getString("model"))
    }


    @Test
    fun `401 with a credential message is an auth error`() {
        val p = provider()
        assertTrue(p.isAuthFailure(401, """{"error":{"message":"User not found.","code":401}}""".toByteArray()))
        assertTrue(p.isAuthFailure(401, """{"error":{"message":"No auth credentials found","code":401}}""".toByteArray()))
        assertTrue(p.isAuthFailure(403, "Invalid API key".toByteArray()))
    }

    @Test
    fun `any other 401 keeps OpenRouter's own message`() {
        val p = provider()
        val body = """{"error":{"message":"meta/muse-spark-1.3 is not a transcription model","code":401}}""".toByteArray()
        assertFalse(p.isAuthFailure(401, body))
        assertFalse(p.isAuthFailure(401, null))
        assertFalse(p.isAuthFailure(500, "User not found".toByteArray()))
        assertTrue(
            VoiceProviderException.Http(401, body).message!!.contains("is not a transcription model"),
        )
        assertTrue(VoiceProvider("i", "https://x", null).isAuthFailure(401, body))
    }


    @Test
    fun `whisper stays on the REST transcriptions endpoint`() {
        assertFalse(provider().usesChatBasedASR(model("openai/whisper-1")))
    }

    @Test
    fun `gpt-4o transcribe variants stay on REST`() {
        assertFalse(provider().usesChatBasedASR(model("openai/gpt-4o-transcribe")))
        assertFalse(provider().usesChatBasedASR(model("openai/gpt-4o-mini-transcribe")))
    }

    @Test
    fun `deepgram is matched vendor-qualified, so amazon nova is NOT misrouted`() {
        assertFalse(provider().usesChatBasedASR(model("deepgram/nova-3")))
        assertTrue(provider().usesChatBasedASR(model("amazon/nova-2-lite-v1")))
    }


    @Test
    fun `audio-capable chat models route to chat completions`() {
        assertTrue(provider().usesChatBasedASR(model("google/gemini-3.6-flash")))
        assertTrue(provider().usesChatBasedASR(model("openai/gpt-audio-mini")))
    }

    @Test
    fun `the rule is inverted versus the base class, covering future audio chat models`() {
        val future = model("somevendor/brand-new-speech-chat")
        assertTrue(provider().usesChatBasedASR(future))
        assertFalse(VoiceProvider("i", "https://x", null).usesChatBasedASR(future))
    }


    @Test
    fun `PCM16 is wrapped in a RIFF WAV header with the declared sample rate`() {
        val pcm = ByteArray(480) { 0 }
        val wav = VoiceProvider.wrapPcm16InWav(pcm, 24000)

        assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12)))
        assertEquals(44 + pcm.size, wav.size)

        fun le32(at: Int) = (wav[at].toInt() and 0xFF) or
            ((wav[at + 1].toInt() and 0xFF) shl 8) or
            ((wav[at + 2].toInt() and 0xFF) shl 16) or
            ((wav[at + 3].toInt() and 0xFF) shl 24)

        assertEquals(24000, le32(24))
        assertEquals(24000 * 2, le32(28))
        assertEquals(pcm.size, le32(40))
    }
}
