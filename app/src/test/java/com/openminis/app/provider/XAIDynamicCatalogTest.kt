package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.xai.XAIModelsApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class XAIDynamicCatalogTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/v1").toString().trimEnd('/')

    private fun resolve(base: String): List<LLMModel> =
        runBlocking {
            OpenAIModelsApi.fetchModels("test-key", base)
                .ifEmpty { XAIModelsApi.fetchModelsOAuth() }
        }

    @Test
    fun liveCatalogSurfacesAModelMissingFromTheBuiltInList() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"object":"list","data":[
                  {"id":"grok-9.9-unreleased","object":"model","owned_by":"xai"},
                  {"id":"grok-4.6","object":"model","owned_by":"xai"}
                ]}
                """.trimIndent(),
            ),
        )

        val models = resolve(baseUrl())
        val ids = models.map { it.id }

        assertTrue(
            "live catalog must surface an id absent from the built-in list, got $ids",
            ids.contains("grok-9.9-unreleased"),
        )
        assertFalse("live result must not be the built-in fallback", models.isEmpty())
    }

    @Test
    fun requestGoesToTheModelsEndpointOfTheGivenBase() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"object":"list","data":[{"id":"grok-4.6","object":"model"}]}"""),
        )

        resolve(baseUrl())

        val path = server.takeRequest().path
        assertEquals("/v1/models", path)
    }

    @Test
    fun httpFailureFallsBackToTheBuiltInCatalog() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"nope"}"""))

        val models = resolve(baseUrl())

        assertFalse("must not end up with an empty picker", models.isEmpty())
        assertTrue(
            "fallback must be the xAI built-ins, got ${models.map { it.id }}",
            models.any { it.id == "grok-4.6" },
        )
        assertTrue(
            "fallback must not contain OpenAI models",
            models.none { it.id.startsWith("gpt-") },
        )
    }

    @Test
    fun malformedBodyFallsBackRatherThanEmptyingThePicker() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json at all"))

        val models = resolve(baseUrl())

        assertFalse(models.isEmpty())
        assertTrue(models.any { it.provider == "xAI" })
    }

    @Test
    fun builtInCatalogContainsGrok46() {
        assertTrue(
            "grok-4.6 missing from LLMModel.allXAI",
            LLMModel.allXAI.any { it.id == "grok-4.6" },
        )
    }

    @Test
    fun builtInCatalogHasNoDuplicateIds() {
        val ids = LLMModel.allXAI.map { it.id }
        assertEquals("duplicate ids in allXAI: $ids", ids.size, ids.toSet().size)
    }
}
