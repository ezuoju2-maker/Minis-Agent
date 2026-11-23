package com.openminis.app.auth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CopilotModelFilterTest {

    private fun parse(body: String) = CopilotDeviceFlow.parseModelsDetailed(JSONObject(body))


    @Test
    fun `gpt-6-astra passes the filter with its full real-world payload`() {
        val parsed = parse(
            """
            {"data":[{
              "id":"gpt-6-astra","name":"GPT-6 Astra","vendor":"OpenAI","version":"gpt-6-astra",
              "model_picker_enabled":true,"model_picker_category":"versatile",
              "preview":true,"is_chat_default":false,"is_chat_fallback":false,
              "billing":{"is_premium":true,"multiplier":1.0,"restricted_to":["pro","enterprise"]},
              "policy":{"state":"enabled","terms":"Subject to the GitHub Terms"},
              "capabilities":{"family":"gpt-6","object":"model_capabilities","type":"chat",
                "tokenizer":"o200k_base",
                "limits":{"max_context_window_tokens":400000,"max_output_tokens":128000,
                  "max_prompt_tokens":272000,"vision":{"max_prompt_image_size":3145728}},
                "supports":{"streaming":true,"tool_calls":true,"parallel_tool_calls":true,
                  "vision":true,"structured_outputs":true,"adaptive_thinking":true,
                  "reasoning_effort":["low","medium","high","xhigh","max"]}}
            }]}
            """.trimIndent(),
        )
        assertEquals(listOf("gpt-6-astra"), parsed.models.map { it.id })
        assertTrue("nothing may be hidden by policy here", parsed.hiddenByPolicy.isEmpty())
        assertTrue("nothing may be dropped by type here", parsed.droppedByType.isEmpty())
        val m = parsed.models.single()
        assertEquals(400_000, m.contextWindow)
        assertEquals(128_000, m.maxOutputTokens)
        assertTrue(m.supportsVision)
        assertEquals(true, m.supportsReasoning)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), m.reasoningEffortValues)
    }


    @Test
    fun `no field other than the two gates can drop a model`() {
        val decoys = listOf(
            """"policy":{"state":"unconfigured"}""",
            """"policy":{"state":"disabled"}""",
            """"billing":{"is_premium":true,"multiplier":50.0}""",
            """"preview":true""",
            """"is_chat_default":false""",
            """"is_chat_fallback":false""",
            """"model_picker_category":"lightweight"""",
            """"vendor":"Some-New-Vendor"""",
            """"version":"0.0.1-alpha"""",
            """"capabilities":{"type":"chat","supports":{"streaming":false}}""",
            """"capabilities":{"type":"chat","supports":{"tool_calls":false}}""",
            """"capabilities":{"type":"chat","family":"an-unknown-family"}""",
            """"deprecated":true""",
            """"enabled":false""",
        )
        for (decoy in decoys) {
            val parsed = parse("""{"data":[{"id":"m","name":"M",$decoy}]}""")
            assertEquals(
                "a model must survive $decoy",
                listOf("m"),
                parsed.models.map { it.id },
            )
            assertTrue("$decoy must not report a policy drop", parsed.hiddenByPolicy.isEmpty())
            assertTrue("$decoy must not report a type drop", parsed.droppedByType.isEmpty())
        }
    }

    @Test
    fun `policy state is never an entitlement gate`() {
        val parsed = parse(
            """
            {"data":[
              {"id":"premium","model_picker_enabled":true,
               "policy":{"state":"unconfigured"},"capabilities":{"type":"chat"}},
              {"id":"plain","model_picker_enabled":true,"capabilities":{"type":"chat"}}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("premium", "plain"), parsed.models.map { it.id })
    }


    @Test
    fun `gate 1 fires only on an explicit false`() {
        assertTrue(parse("""{"data":[{"id":"m","model_picker_enabled":false}]}""").models.isEmpty())
        assertEquals(1, parse("""{"data":[{"id":"m","model_picker_enabled":true}]}""").models.size)
        assertEquals(1, parse("""{"data":[{"id":"m"}]}""").models.size)
    }

    @Test
    fun `gate 2 fires only on a present non-chat type`() {
        assertTrue(parse("""{"data":[{"id":"e","capabilities":{"type":"embeddings"}}]}""").models.isEmpty())
        assertEquals(1, parse("""{"data":[{"id":"c","capabilities":{"type":"chat"}}]}""").models.size)
        assertEquals(1, parse("""{"data":[{"id":"auto"}]}""").models.size)
        assertEquals(1, parse("""{"data":[{"id":"blank","capabilities":{"type":""}}]}""").models.size)
    }

    @Test
    fun `the two gates are reported separately and never overlap`() {
        val parsed = parse(
            """
            {"data":[
              {"id":"keep","capabilities":{"type":"chat"}},
              {"id":"policy-off","model_picker_enabled":false,"capabilities":{"type":"chat"}},
              {"id":"embed","capabilities":{"type":"embeddings"}}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("keep"), parsed.models.map { it.id })
        assertEquals(listOf("policy-off"), parsed.hiddenByPolicy)
        assertEquals(listOf("embed:embeddings"), parsed.droppedByType)
        assertEquals("totalReturned must count the raw payload", 3, parsed.totalReturned)
    }

    @Test
    fun `a model failing both gates is attributed to the picker flag`() {
        val parsed = parse(
            """{"data":[{"id":"both","model_picker_enabled":false,
                        "capabilities":{"type":"embeddings"}}]}""",
        )
        assertEquals(listOf("both"), parsed.hiddenByPolicy)
        assertTrue(parsed.droppedByType.isEmpty())
    }


    @Test
    fun `rows with no usable id are skipped silently`() {
        val parsed = parse("""{"data":[{"name":"nameless"},{"id":""},{"id":"real"}]}""")
        assertEquals(listOf("real"), parsed.models.map { it.id })
        assertTrue(parsed.hiddenByPolicy.isEmpty())
        assertTrue(parsed.droppedByType.isEmpty())
        assertEquals(3, parsed.totalReturned)
    }

    @Test
    fun `malformed payloads yield an empty result rather than throwing`() {
        for (body in listOf("""{}""", """{"data":[]}""", """{"data":"nope"}""", """{"data":[1,2,3]}""")) {
            val parsed = parse(body)
            assertTrue("$body should parse to nothing", parsed.models.isEmpty())
        }
    }

    @Test
    fun `parseModelIds applies exactly the same filter`() {
        val body = """
            {"data":[
              {"id":"keep","name":"Keep","policy":{"state":"unconfigured"}},
              {"id":"hidden","model_picker_enabled":false},
              {"id":"embed","capabilities":{"type":"embeddings"}}
            ]}
        """.trimIndent()
        assertEquals(
            listOf("keep" to "Keep"),
            CopilotDeviceFlow.parseModelIds(JSONObject(body)),
        )
    }


    @Test
    fun `the filter loop still has exactly two drop gates`() {
        val src = com.openminis.app.ProductionSources.read("auth/CopilotDeviceFlow.kt")
        val loop = src.substringAfter("fun parseModelsDetailed(")
            .substringBefore("private fun JSONArray")
        assertTrue("gate 1 must read model_picker_enabled defaulting to true",
            loop.contains("""!m.optBoolean("model_picker_enabled", true)"""))
        assertTrue("gate 2 must only fire on a PRESENT non-chat type",
            loop.contains("""type.isNotEmpty() && type != "chat""""))
        // Five `continue`s exactly: non-object row, empty id, gate 1, gate 2,
        // duplicate id. Only two of them are policy decisions; the other three
        // are shape guards, and none of the five may grow a sixth sibling.
        val continues = Regex("\\bcontinue\\b").findAll(loop).count()
        assertEquals(
            "a new `continue` in the filter loop is a new way to lose a model",
            5,
            continues,
        )
        assertFalse(
            "policy state must not become a gate",
            loop.contains("\"policy\""),
        )
    }
}
