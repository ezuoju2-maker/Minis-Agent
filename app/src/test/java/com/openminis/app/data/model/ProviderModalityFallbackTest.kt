package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModalityFallbackTest {

    private fun model(
        id: String = "m",
        provider: String,
        inputs: List<String>? = null,
        outputs: List<String>? = null,
    ) = LLMModel(
        id = id,
        displayName = id,
        provider = provider,
        inputModalities = inputs,
        outputModalities = outputs,
    )


    @Test
    fun `an uncatalogued Anthropic model falls back to image and pdf input`() {
        val fable51 = LLMModel.claudeFable51
        assertNull("precondition: no declared modality", fable51.inputModalities)
        assertTrue(fable51.hasImageInput)
        assertTrue("pdf must come through too", fable51.effectiveInputModalities!!.contains("pdf"))
    }

    @Test
    fun `the Anthropic fallback grants vision but NOT audio or video`() {
        val m = model(provider = "Anthropic")
        assertTrue(m.hasImageInput)
        assertFalse(m.hasAudioInput)
        assertFalse(m.hasAudioOutput)
        assertFalse(m.effectiveInputModalities!!.contains("video"))
    }

    @Test
    fun `Google falls back to full multimodal input`() {
        val m = model(provider = "Google")
        val ins = m.effectiveInputModalities!!
        assertTrue(ins.containsAll(listOf("text", "image", "pdf", "audio", "video")))
        assertTrue(m.hasAudioInput)
        assertFalse(m.hasAudioOutput)
    }

    @Test
    fun `OpenAI and OpenRouter fall back to vision, matching iOS`() {
        for (p in listOf("OpenAI", "OpenRouter")) {
            val m = model(provider = p)
            assertTrue("$p should default to vision", m.hasImageInput)
            assertFalse("$p must not default to audio", m.hasAudioInput)
        }
    }


    @Test
    fun `a declared modality list is never overwritten by the fallback`() {
        val m = model(provider = "Anthropic", inputs = listOf("text"))
        assertEquals(listOf("text"), m.effectiveInputModalities)
        assertFalse(m.hasImageInput)
    }

    @Test
    fun `a declared list that EXCEEDS the provider default is preserved`() {
        val m = model(provider = "Anthropic", inputs = listOf("text", "image", "audio"))
        assertTrue(m.hasAudioInput)
        assertEquals(listOf("text", "image", "audio"), m.effectiveInputModalities)
    }

    @Test
    fun `an already-catalogued Anthropic model is unaffected`() {
        val opus5 = model(id = "claude-opus-5", provider = "Anthropic", inputs = listOf("text", "image"))
        assertEquals(listOf("text", "image"), opus5.effectiveInputModalities)
        assertTrue(opus5.hasImageInput)
        assertFalse(opus5.hasAudioInput)
    }


    @Test
    fun `an uncatalogued Grok, Kimi or Copilot model falls back to vision, matching iOS`() {
        for (p in listOf("xAI", "xAI (Grok)", "Kimi", "Kimi Code", "GitHub Copilot")) {
            val m = model(provider = p)
            assertEquals("$p gets the vision default", listOf("text", "image", "pdf"), m.effectiveInputModalities)
            assertTrue("$p must accept images", m.hasImageInput)
            assertFalse("$p must not gain audio", m.hasAudioInput)
            assertEquals(listOf("text"), m.effectiveOutputModalities)
        }
    }

    @Test
    fun `a Grok or Kimi model's own list still wins over the default`() {
        val textOnly = model(provider = "xAI", inputs = listOf("text"))
        assertFalse(textOnly.hasImageInput)
        val declaredVision = model(provider = "Kimi", inputs = listOf("text", "image"))
        assertEquals(listOf("text", "image"), declaredVision.effectiveInputModalities)
    }

    @Test
    fun `unknown and third-party provider names get no fallback at all`() {
        for (p in listOf("Custom", "DeepSeek", "Ollama", "vLLM", "LiteLLM", "test", "")) {
            val m = model(provider = p)
            assertNull("$p must have no synthetic modality", m.effectiveInputModalities)
            assertFalse("$p must not report image input", m.hasImageInput)
        }
    }

    @Test
    fun `provider matching is exact, not fuzzy`() {
        assertNull(model(provider = "Anthropic (proxy)").effectiveInputModalities)
        assertNull(model(provider = "anthropic").effectiveInputModalities)
    }


    private fun entry(
        base: LLMModel,
        overrides: ModelOverrides = ModelOverrides(),
    ) = ModelEntry(providerInstanceId = "inst", baseModel = base, overrides = overrides)

    @Test
    fun `entry model applies the provider fallback when there is no override`() {
        val e = entry(LLMModel.claudeFable51)
        assertTrue(e.overrides.isEmpty)
        val ins = e.model.inputModalities!!
        assertTrue(ins.contains("image"))
        assertTrue(ins.contains("pdf"))
        assertTrue(e.model.hasImageInput)
    }

    @Test
    fun `entry model no longer short-circuits past the fallback on empty overrides`() {
        val base = LLMModel.claudeFable51
        val e = entry(base)
        assertNull("precondition", base.inputModalities)
        assertTrue("must not hand back the raw baseModel", e.model.inputModalities != null)
    }

    @Test
    fun `entry model keeps a declared list untouched`() {
        val base = model(id = "claude-opus-5", provider = "Anthropic", inputs = listOf("text", "image"))
        val e = entry(base)
        assertEquals(listOf("text", "image"), e.model.inputModalities)
        assertTrue(e.model.hasImageInput)
        assertEquals(listOf("text"), e.model.outputModalities)
    }

    @Test
    fun `a fully-declared entry allocates nothing`() {
        val base = model(
            id = "claude-opus-5", provider = "Anthropic",
            inputs = listOf("text", "image"), outputs = listOf("text"),
        )
        val e = entry(base)
        assertSame(base, e.model)
    }

    @Test
    fun `a user override still wins over the provider fallback`() {
        val e = entry(
            LLMModel.claudeFable51,
            ModelOverrides(inputModalities = listOf("text")),
        )
        assertEquals(listOf("text"), e.model.inputModalities)
        assertFalse(e.model.hasImageInput)
    }

    @Test
    fun `a third-party entry is unchanged by the fallback`() {
        val declaredNothing = entry(model(provider = "Custom"))
        assertNull(declaredNothing.model.inputModalities)
        assertFalse(declaredNothing.model.hasImageInput)
        assertSame("no copy should be made", declaredNothing.baseModel, declaredNothing.model)

        val declaredVision = entry(model(provider = "Custom", inputs = listOf("text", "image")))
        assertEquals(listOf("text", "image"), declaredVision.model.inputModalities)
    }

    @Test
    fun `the fallback does not disturb the other override fields`() {
        val base = LLMModel(
            id = "m", displayName = "Base", provider = "Anthropic",
            contextWindow = 111, maxOutputTokens = 222, supportsReasoning = true,
        )
        val e = entry(base, ModelOverrides(displayName = "Renamed"))
        val m = e.model
        assertEquals("Renamed", m.displayName)
        assertEquals(111, m.contextWindow)
        assertEquals(222, m.maxOutputTokens)
        assertEquals(true, m.supportsReasoning)
        assertTrue(m.hasImageInput)
    }

    @Test
    fun `a voice template seed entry keeps its exact shape through entry model`() {
        val seed = model(provider = "elevenlabs", inputs = null, outputs = listOf("audio"))
        val e = entry(seed)
        assertNull(e.model.inputModalities)
        assertTrue(e.model.isVoiceTemplateSeedShape)
    }


    @Test
    fun `the ASR template seed shape still matches under the fallback`() {
        val seed = model(provider = "Anthropic", inputs = listOf("audio"), outputs = null)
        assertTrue(seed.isVoiceTemplateSeedShape)
    }

    @Test
    fun `the TTS template seed shape still matches under the fallback`() {
        val seed = model(provider = "Google", inputs = null, outputs = listOf("audio"))
        assertTrue(seed.isVoiceTemplateSeedShape)
    }

    @Test
    fun `a modality-less model is NOT mistaken for a template seed`() {
        val m = model(provider = "Anthropic")
        assertFalse(m.isVoiceTemplateSeedShape)
        assertTrue("capability side still works", m.hasImageInput)
    }


    private val expectedVisionTypes = setOf(
        ProviderType.anthropic,
        ProviderType.openAI,
        ProviderType.openRouter,
        ProviderType.xAI,
        ProviderType.kimiCode,
        ProviderType.githubCopilot,
    )
    private val expectedMultimodalTypes = setOf(ProviderType.gemini)

    @Test
    fun `every provider type resolves the table under its real display name`() {
        for (type in ProviderType.entries) {
            val m = model(provider = type.displayName)
            val ins = m.effectiveInputModalities
            when (type) {
                in expectedMultimodalTypes -> {
                    assertEquals(
                        "${type.name} (\"${type.displayName}\") must get the full " +
                            "multimodal default — a key mismatch here is the reported bug",
                        listOf("text", "image", "pdf", "audio", "video"),
                        ins,
                    )
                    assertTrue(m.hasAudioInput)
                }
                in expectedVisionTypes -> {
                    assertEquals(
                        "${type.name} (\"${type.displayName}\") must get the vision default",
                        listOf("text", "image", "pdf"),
                        ins,
                    )
                    assertTrue(m.hasImageInput)
                    assertFalse("${type.name} must not gain audio", m.hasAudioInput)
                }
                else -> {
                    assertNull(
                        "${type.name} (\"${type.displayName}\") must have NO synthetic " +
                            "modality — inventing one would make a text-only relay " +
                            "advertise image input",
                        ins,
                    )
                    assertFalse(m.hasImageInput)
                }
            }
        }
    }

    @Test
    fun `the Gemini type resolves, which is the exact reported miss`() {
        assertEquals("Google Gemini", ProviderType.gemini.displayName)
        val gemini = model(provider = ProviderType.gemini.displayName)
        assertTrue("an un-catalogued Gemini model must see images", gemini.hasImageInput)
        assertTrue(gemini.hasAudioInput)
    }

    @Test
    fun `the legacy bare Google key is kept as well, not replaced`() {
        assertEquals(
            listOf("text", "image", "pdf", "audio", "video"),
            model(provider = "Google").effectiveInputModalities,
        )
        assertEquals("Google", LLMModel.gemini25Pro.provider)
        assertTrue("the built-in catalog uses the bare key", LLMModel.gemini25Pro.hasImageInput)
    }

    @Test
    fun `every built-in catalog model resolves through the table it belongs to`() {
        for (m in LLMModel.allAnthropic) {
            assertTrue("${m.id} must see images", m.hasImageInput)
            assertFalse("${m.id} must not claim audio in", m.hasAudioInput)
            assertFalse("${m.id} must not claim audio out", m.hasAudioOutput)
        }
        for (m in LLMModel.allGemini) {
            assertTrue("${m.id} must see images", m.hasImageInput)
            assertTrue("${m.id} must accept audio in", m.hasAudioInput)
            assertFalse("${m.id} must not claim audio out", m.hasAudioOutput)
        }
        for (m in LLMModel.allXAI) {
            assertTrue("${m.id} gets the xAI vision default", m.hasImageInput)
            assertFalse("${m.id} must not claim audio in", m.hasAudioInput)
        }
    }

    @Test
    fun `no table key is unreachable`() {
        val src = com.openminis.app.ProductionSources.read("data/model/LLMModel.kt")
        val table = src.substringAfter("PROVIDER_DEFAULT_INPUT_MODALITIES: Map<String, List<String>> = mapOf(")
            .substringBefore(")\n")
        val keys = Regex("""^\s*"([^"]+)" to """, RegexOption.MULTILINE)
            .findAll(table).map { it.groupValues[1] }.toList()
        assertTrue("expected to parse the table keys, got $keys", keys.size >= 4)

        val displayNames = ProviderType.entries.map { it.displayName }.toSet()
        val catalogProviders = LLMModel.allModels.map { it.provider }.toSet()
        for (key in keys) {
            assertTrue(
                "table key \"$key\" matches no ProviderType.displayName and no " +
                    "provider string in the built-in catalog — it can never fire " +
                    "(this is the \"Google\" vs \"Google Gemini\" failure)",
                key in displayNames || key in catalogProviders,
            )
        }
    }

    @Test
    fun `the input and output tables cover the same providers`() {
        val src = com.openminis.app.ProductionSources.read("data/model/LLMModel.kt")
        fun keysOf(marker: String) = src.substringAfter(marker)
            .substringBefore(")\n")
            .let { body ->
                Regex("""^\s*"([^"]+)" to """, RegexOption.MULTILINE)
                    .findAll(body).map { it.groupValues[1] }.toSet()
            }
        assertEquals(
            keysOf("PROVIDER_DEFAULT_INPUT_MODALITIES: Map<String, List<String>> = mapOf("),
            keysOf("PROVIDER_DEFAULT_OUTPUT_MODALITIES: Map<String, List<String>> = mapOf("),
        )
    }


    @Test
    fun `the request-time image gate reads the effective list`() {
        val src = com.openminis.app.ProductionSources.read("ui/chat/ImageInputPreflight.kt")
        assertTrue(
            "the preflight must import the effective list, not the raw field",
            src.contains("effectiveInputModalities"),
        )
    }

    @Test
    fun `ModelEntry_model is the single place the fallback is applied`() {
        val src = com.openminis.app.ProductionSources.read("data/model/ProviderConfig.kt")
        val prop = src.substringAfter("val model: LLMModel").substringBefore("\n}")
        assertTrue(prop.contains("baseModel.effectiveInputModalities"))
        assertTrue(prop.contains("baseModel.effectiveOutputModalities"))
        assertTrue(
            "the early return must be gated on 'nothing to apply from EITHER source'",
            prop.contains("fallbackPending"),
        )
    }

    @Test
    fun `voice inference still fires for a modality-less dedicated ASR model`() {
        val whisper = model(id = "whisper-large-v3", provider = "OpenAI")
        val inferred = whisper.withInferredVoiceModality()
        assertEquals(listOf("audio"), inferred.inputModalities)
        assertEquals(listOf("text"), inferred.outputModalities)
    }

    @Test
    fun `fetched Grok and Kimi models are labelled as the provider, not as a relay`() {
        val repo = java.io.File("src/main/java/com/openminis/app/data/repository/ProviderRepository.kt").readText()
        assertTrue(repo.contains(".map { it.copy(provider = \"xAI\") }"))
        assertTrue(repo.contains(".map { it.copy(provider = \"Kimi\") }"))
    }

    @Test
    fun `a Copilot model reported as text-only stays text-only`() {
        val textOnly = model(provider = "GitHub Copilot", inputs = listOf("text"))
        assertEquals(listOf("text"), textOnly.effectiveInputModalities)
        val src = com.openminis.app.ProductionSources.read("data/repository/ProviderRepository.kt")
        assertTrue(
            "the Copilot fetch writes text-only for supportsVision=false",
            src.contains("inputModalities = if (m.supportsVision) {\n                                            listOf(\"text\", \"image\")\n                                        } else listOf(\"text\"),"),
        )
    }
}
