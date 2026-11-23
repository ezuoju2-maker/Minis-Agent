package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsDevAggregateFallbackTest {

    private fun entry(
        id: String,
        context: Int? = null,
        maxOut: Int? = null,
        reasoning: Boolean? = null,
        input: List<String>? = null,
        output: List<String>? = null,
        efforts: List<String>? = null,
        interleaved: String? = null,
    ) = ModelsDevApi.ModelDevEntry(
        id = id,
        name = null,
        family = null,
        contextWindow = context,
        maxOutputTokens = maxOut,
        reasoning = reasoning,
        interleavedField = interleaved,
        inputModalities = input,
        outputModalities = output,
        reasoningEffortValues = efforts,
        releaseDate = null,
        outputCost = null,
    )

    private fun registryOf(providerKey: String, vararg models: ModelsDevApi.ModelDevEntry) =
        mapOf(
            providerKey to ModelsDevApi.ProviderEntry(
                id = providerKey,
                name = providerKey,
                api = null,
                models = models.associateBy { it.id },
            ),
        )


    @Test
    fun `numeric attributes aggregate to the maximum`() {
        val agg = ModelsDevApi.aggregate(
            listOf(
                entry("glm-5.3-flash", context = 128_000, maxOut = 4_096),
                entry("glm-5.3-flash-lite", context = 1_000_000, maxOut = 8_192),
                entry("glm-5.3-flash-air", context = 64_000, maxOut = 2_048),
            ),
            id = "glm-5.3-flash",
        )
        assertEquals(1_000_000, agg.contextWindow)
        assertEquals(8_192, agg.maxOutputTokens)
    }

    @Test
    fun `a field no record declares stays null rather than zero`() {
        val agg = ModelsDevApi.aggregate(
            listOf(entry("a-b", reasoning = true), entry("a-b-c", reasoning = false)),
            id = "a-b",
        )
        assertNull("no record declared a context window", agg.contextWindow)
        assertNull(agg.maxOutputTokens)
    }


    @Test
    fun `boolean attributes aggregate to the logical OR`() {
        val agg = ModelsDevApi.aggregate(
            listOf(
                entry("x-y", reasoning = false),
                entry("x-y-pro", reasoning = true),
                entry("x-y-lite", reasoning = false),
            ),
            id = "x-y",
        )
        assertEquals(true, agg.reasoning)
    }

    @Test
    fun `all-false stays false and silence stays null`() {
        assertEquals(
            false,
            ModelsDevApi.aggregate(
                listOf(entry("p-q", reasoning = false), entry("p-q-r", reasoning = false)),
                id = "p-q",
            ).reasoning,
        )
        assertNull(
            ModelsDevApi.aggregate(listOf(entry("p-q"), entry("p-q-r")), id = "p-q").reasoning,
        )
    }

    @Test
    fun `set fields take the union`() {
        val agg = ModelsDevApi.aggregate(
            listOf(
                entry("m-n", input = listOf("text"), efforts = listOf("low")),
                entry("m-n-vision", input = listOf("text", "image"), efforts = listOf("high")),
            ),
            id = "m-n",
        )
        assertEquals(listOf("image", "text"), agg.inputModalities)
        assertTrue(agg.reasoningEffortValues!!.containsAll(listOf("low", "high")))
    }


    @Test
    fun `known providers are never treated as unrecognized`() {
        assertFalse(ModelsDevApi.isUnrecognizedProvider("OpenAI"))
        assertFalse(ModelsDevApi.isUnrecognizedProvider("Anthropic"))
        assertFalse(ModelsDevApi.isUnrecognizedProvider("Google"))
        assertFalse(ModelsDevApi.isUnrecognizedProvider("OpenRouter"))
    }


    @Test
    fun `unrecognized third-party providers trigger the fallback`() {
        assertTrue(ModelsDevApi.isUnrecognizedProvider("Custom"))
        assertTrue(ModelsDevApi.isUnrecognizedProvider("some-unknown-relay"))
        assertTrue(ModelsDevApi.isUnrecognizedProvider("Antigravity"))
    }

    @Test
    fun `the index registers a record under its own prefixes`() {
        val index = ModelsDevApi.buildAggregateIndex(
            registryOf("zhipuai", entry("glm-5.3-flash-air", context = 200_000)),
        )
        assertTrue("own id is a key: ${index.keys}", index.containsKey("glm-5.3-flash-air"))
        assertTrue(index.containsKey("glm-5.3-flash"))
        assertTrue(index.containsKey("glm-5.3"))
        assertFalse("must not register a single-segment head", index.containsKey("glm"))
    }

    @Test
    fun `the reported relay id resolves through the family`() {
        val index = ModelsDevApi.buildAggregateIndex(
            registryOf(
                "zhipuai",
                entry("glm-5.3-flash", context = 128_000, reasoning = false),
                entry("glm-5.3-flash-pro", context = 1_000_000, reasoning = true),
            ),
        )
        val agg = ModelsDevApi.aliasCandidates("glm-5.3-flash-cpa")
            .firstNotNullOfOrNull { index[it] }
        assertEquals(1_000_000, agg!!.contextWindow)
        assertEquals(true, agg.reasoning)
    }


    @Test
    fun `a refreshed snapshot yields a larger value and leaves the old index alone`() {
        val oldIndex = ModelsDevApi.buildAggregateIndex(
            registryOf("zhipuai", entry("glm-5.3-flash", context = 128_000)),
        )
        assertEquals(128_000, oldIndex["glm-5.3-flash"]!!.contextWindow)

        val newIndex = ModelsDevApi.buildAggregateIndex(
            registryOf("zhipuai", entry("glm-5.3-flash", context = 1_000_000)),
        )
        assertEquals(1_000_000, newIndex["glm-5.3-flash"]!!.contextWindow)

        assertEquals(128_000, oldIndex["glm-5.3-flash"]!!.contextWindow)
    }
}
