package com.openminis.app.provider

import com.openminis.app.provider.openai.CodexModelsApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexModelsApiTest {

    @Test
    fun `a model absent from the built-in list is parsed`() {
        val models = CodexModelsApi.parseModels(
            """{"models":[{"slug":"gpt-7-nova","display_name":"GPT-7 Nova"}]}"""
        )
        assertEquals(1, models.size)
        assertEquals("gpt-7-nova", models[0].id)
        assertEquals("GPT-7 Nova", models[0].displayName)
    }

    @Test
    fun `hidden models are dropped`() {
        val models = CodexModelsApi.parseModels(
            """{"models":[
                {"slug":"visible-one"},
                {"slug":"hidden-one","visibility":"hidden"},
                {"slug":"hide-one","visibility":"hide"},
                {"slug":"loud-case","visibility":"HIDDEN"}
            ]}"""
        )
        assertEquals(listOf("visible-one"), models.map { it.id })
    }

    @Test
    fun `data alias is accepted and an unknown shape yields empty`() {
        assertEquals(
            listOf("a"),
            CodexModelsApi.parseModels("""{"data":[{"slug":"a"}]}""").map { it.id },
        )
        assertTrue(CodexModelsApi.parseModels("""{"something_else":[]}""").isEmpty())
    }

    @Test
    fun `id falls back for slug and an idless entry is skipped`() {
        val models = CodexModelsApi.parseModels(
            """{"models":[{"id":"from-id"},{"display_name":"nameless"},{"slug":"  "}]}"""
        )
        assertEquals(listOf("from-id"), models.map { it.id })
    }

    @Test
    fun `missing display name falls back to the id`() {
        val models = CodexModelsApi.parseModels("""{"models":[{"slug":"bare-model"}]}""")
        assertEquals("bare-model", models[0].displayName)
    }

    @Test
    fun `context window and modalities are mapped`() {
        val models = CodexModelsApi.parseModels(
            """{"models":[{
                "slug":"m","context_window":400000,"max_output_tokens":100000,
                "input_modalities":["text","image"]
            }]}"""
        )
        assertEquals(400_000, models[0].contextWindow)
        assertEquals(100_000, models[0].maxOutputTokens)
        assertEquals(listOf("text", "image"), models[0].inputModalities)
    }

    @Test
    fun `nonpositive context window is treated as unknown`() {
        val models = CodexModelsApi.parseModels("""{"models":[{"slug":"m","context_window":0}]}""")
        assertNull(models[0].contextWindow)
    }

    @Test
    fun `reasoning is detected from either signal`() {
        val fromDefault = CodexModelsApi.parseModels(
            """{"models":[{"slug":"m","default_reasoning_level":"medium"}]}"""
        )
        assertEquals(true, fromDefault[0].supportsReasoning)

        val fromLevels = CodexModelsApi.parseModels(
            """{"models":[{"slug":"m","default_reasoning_level":"none",
                "supported_reasoning_levels":[{"effort":"none"},{"effort":"high"}]}]}"""
        )
        assertEquals(true, fromLevels[0].supportsReasoning)
    }

    @Test
    fun `silence about reasoning stays unknown but an explicit none is false`() {
        val silent = CodexModelsApi.parseModels("""{"models":[{"slug":"m"}]}""")
        assertNull(silent[0].supportsReasoning)

        val explicit = CodexModelsApi.parseModels(
            """{"models":[{"slug":"m","default_reasoning_level":"none"}]}"""
        )
        assertEquals(false, explicit[0].supportsReasoning)
    }

    @Test
    fun `effort tiers are extracted in order`() {
        val models = CodexModelsApi.parseModels(
            """{"models":[{"slug":"m","supported_reasoning_levels":[
                {"effort":"none"},{"effort":"low"},{"effort":"high"},{"effort":"low"}
            ]}]}"""
        )
        assertEquals(listOf("low", "high"), models[0].reasoningEffortValues)
    }

    @Test
    fun `string reasoning levels are accepted`() {
        val models = CodexModelsApi.parseModels(
            """{"models":[{"slug":"m","supported_reasoning_levels":["low","high"]}]}"""
        )
        assertEquals(listOf("low", "high"), models[0].reasoningEffortValues)
        assertEquals(true, models[0].supportsReasoning)
    }

    @Test
    fun `models are ordered by priority then id`() {
        val models = CodexModelsApi.parseModels(
            """{"models":[
                {"slug":"z-low-pri","priority":10},
                {"slug":"b-top","priority":1},
                {"slug":"a-top","priority":1},
                {"slug":"no-pri"}
            ]}"""
        )
        assertEquals(listOf("a-top", "b-top", "z-low-pri", "no-pri"), models.map { it.id })
    }

    @Test
    fun `a junk element does not discard its siblings`() {
        val models = CodexModelsApi.parseModels(
            """{"models":["not-an-object",{"slug":"survivor"},42]}"""
        )
        assertEquals(listOf("survivor"), models.map { it.id })
    }

    @Test
    fun `authoritative fields beat a disagreeing catalog`() {
        val fresh = CodexModelsApi.parseModels(
            """{"models":[{
                "slug":"gpt-5.6-luna","display_name":"GPT-5.6 Luna",
                "context_window":1000000,"max_output_tokens":128000,
                "input_modalities":["text","image"],
                "supported_reasoning_levels":[{"effort":"high"}]
            }]}"""
        )
        val stale = listOf(
            fresh[0].copy(
                contextWindow = 272_000,
                maxOutputTokens = 64_000,
                supportsReasoning = false,
                inputModalities = listOf("text"),
                reasoningEffortValues = listOf("low"),
            )
        )

        val out = CodexModelsApi.restoreAuthoritative(fresh, stale)

        assertEquals(1_000_000, out[0].contextWindow)
        assertEquals(128_000, out[0].maxOutputTokens)
        assertEquals(true, out[0].supportsReasoning)
        assertEquals(listOf("text", "image"), out[0].inputModalities)
        assertEquals(listOf("high"), out[0].reasoningEffortValues)
    }

    @Test
    fun `a stale backend capacity cannot shrink the catalog value`() {
        val fresh = CodexModelsApi.parseModels(
            """{"models":[{"slug":"gpt-5.6-luna","context_window":272000,"max_output_tokens":64000}]}"""
        )
        val catalog = listOf(fresh[0].copy(contextWindow = 1_050_000, maxOutputTokens = 128_000))

        val out = CodexModelsApi.restoreAuthoritative(fresh, catalog)

        assertEquals(1_050_000, out[0].contextWindow)
        assertEquals(128_000, out[0].maxOutputTokens)
    }

    @Test
    fun `a stale catalog capacity cannot shrink the backend value`() {
        val fresh = CodexModelsApi.parseModels(
            """{"models":[{"slug":"m","context_window":1000000}]}"""
        )
        val catalog = listOf(fresh[0].copy(contextWindow = 272_000))
        assertEquals(1_000_000, CodexModelsApi.restoreAuthoritative(fresh, catalog)[0].contextWindow)
    }

    @Test
    fun `a slug-derived rename of a known model is suppressed`() {
        val fresh = CodexModelsApi.parseModels(
            """{"models":[{"slug":"gpt-6-astra","display_name":"GPT-6-Astra"}]}"""
        )
        val out = CodexModelsApi.restoreAuthoritative(fresh, fresh)
        assertEquals("GPT-6 Astra", out[0].displayName)
    }

    @Test
    fun `a new model keeps the name the backend gave it`() {
        val fresh = CodexModelsApi.parseModels(
            """{"models":[{"slug":"gpt-7-nova","display_name":"GPT-7 Nova"}]}"""
        )
        assertEquals("GPT-7 Nova", CodexModelsApi.restoreAuthoritative(fresh, fresh)[0].displayName)
    }

    @Test
    fun `a substantive rename of a known model is respected`() {
        val fresh = CodexModelsApi.parseModels(
            """{"models":[{"slug":"gpt-6-astra","display_name":"GPT-6 Astra Turbo"}]}"""
        )
        assertEquals(
            "GPT-6 Astra Turbo",
            CodexModelsApi.restoreAuthoritative(fresh, fresh)[0].displayName,
        )
    }

    @Test
    fun `catalog fills the gaps the route left`() {
        val fresh = CodexModelsApi.parseModels("""{"models":[{"slug":"m"}]}""")
        val enriched = listOf(
            fresh[0].copy(
                contextWindow = 200_000,
                maxOutputTokens = 32_000,
                supportsReasoning = true,
                inputModalities = listOf("text", "image"),
                reasoningEffortValues = listOf("low", "high"),
            )
        )

        val out = CodexModelsApi.restoreAuthoritative(fresh, enriched)

        assertEquals(200_000, out[0].contextWindow)
        assertEquals(32_000, out[0].maxOutputTokens)
        assertEquals(true, out[0].supportsReasoning)
        assertEquals(listOf("text", "image"), out[0].inputModalities)
        assertEquals(listOf("low", "high"), out[0].reasoningEffortValues)
    }

    @Test
    fun `output modalities are never invented`() {
        val models = CodexModelsApi.parseModels(
            """{"models":[{"slug":"m","input_modalities":["text"]}]}"""
        )
        assertNull(models[0].outputModalities)
    }

    @Test
    fun `provider is OpenAI`() {
        val models = CodexModelsApi.parseModels("""{"models":[{"slug":"m"}]}""")
        assertEquals("OpenAI", models[0].provider)
        assertFalse(models[0].id.isEmpty())
    }
}

class CodexImageModelMergeTest {

    @Test
    fun `image models are the three codex image SKUs`() {
        val ids = com.openminis.app.provider.openai.OpenAIModelsApi.codexImageModels().map { it.id }
        assertEquals(
            listOf("gpt-image-2", "gpt-image-2.5-sunburst", "gpt-image-2.5-flare"),
            ids,
        )
    }

    @Test
    fun `image models declare image output`() {
        for (m in com.openminis.app.provider.openai.OpenAIModelsApi.codexImageModels()) {
            assertEquals("${m.id} must output image", listOf("image"), m.outputModalities)
            assertTrue("${m.id} must accept text", m.inputModalities?.contains("text") == true)
        }
    }

    @Test
    fun `built-in oauth list still ends with the image models`() {
        val all = com.openminis.app.provider.openai.OpenAIModelsApi.fetchModelsOAuth().map { it.id }
        assertEquals(
            listOf("gpt-image-2", "gpt-image-2.5-sunburst", "gpt-image-2.5-flare"),
            all.takeLast(3),
        )
    }

    @Test
    fun `a successful discovery keeps the image models and adds no duplicates`() {
        val image = com.openminis.app.provider.openai.OpenAIModelsApi.codexImageModels()

        fun merge(discovered: List<String>): List<String> {
            val discoveredIds = discovered.toSet()
            return discovered + image.map { it.id }.filter { it !in discoveredIds }
        }

        val chatOnly = merge(listOf("gpt-5.6-terra", "gpt-5.5"))
        assertEquals(listOf("gpt-5.6-terra", "gpt-5.5") + image.map { it.id }, chatOnly)

        assertEquals(image.map { it.id }, merge(emptyList()))

        val withOne = merge(listOf("gpt-5.6-terra", "gpt-image-2"))
        assertEquals(withOne.size, withOne.toSet().size)
        for (m in image) assertTrue("${m.id} must survive", m.id in withOne)
    }
}

class CodexCacheKeyTest {

    private val v = "0.153.3"

    @Test
    fun `different accounts never share a key`() {
        assertNotEquals(
            CodexModelsApi.cacheKey("account-A", "token-A", v),
            CodexModelsApi.cacheKey("account-B", "token-B", v),
        )
    }

    @Test
    fun `a token refresh keeps the same key when the account is known`() {
        assertEquals(
            CodexModelsApi.cacheKey("account-A", "old-token", v),
            CodexModelsApi.cacheKey("account-A", "new-token", v),
        )
    }

    @Test
    fun `without an account id the token still isolates`() {
        assertNotEquals(
            CodexModelsApi.cacheKey(null, "token-A", v),
            CodexModelsApi.cacheKey(null, "token-B", v),
        )
        assertNotEquals(
            CodexModelsApi.cacheKey(null, "token-A", v),
            CodexModelsApi.cacheKey(null, "token-A", "0.154.0"),
        )
    }

    @Test
    fun `a blank account id does not become a shared partition`() {
        assertNotEquals(
            CodexModelsApi.cacheKey("  ", "token-A", v),
            CodexModelsApi.cacheKey("", "token-B", v),
        )
    }

    @Test
    fun `client version participates in the key`() {
        assertNotEquals(
            CodexModelsApi.cacheKey("account-A", "token-A", "0.153.3"),
            CodexModelsApi.cacheKey("account-A", "token-A", "0.154.0"),
        )
    }
}
