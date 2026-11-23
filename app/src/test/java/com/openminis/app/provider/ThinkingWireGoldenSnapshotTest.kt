package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ThinkingWireGoldenSnapshotTest {

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

    private fun model(
        id: String,
        supportsReasoning: Boolean? = true,
        reasoningEffortValues: List<String>? = null,
    ) = LLMModel(
        id = id,
        displayName = id,
        provider = "Golden",
        supportsReasoning = supportsReasoning,
        reasoningEffortValues = reasoningEffortValues,
    )

    private fun canonical(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> v.keys().asSequence().sorted()
            .joinToString(",", "{", "}") { "$it:${canonical(v.get(it))}" }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
        is String -> "\"$v\""
        else -> v.toString()
    }

    private val watched = listOf(
        "reasoning_effort", "reasoning", "thinking",
        "enable_thinking", "thinking_budget", "extra_body",
    )

    private fun emit(m: LLMModel, level: ThinkingLevel, basePath: String, maxTokens: Int): String {
        val ok = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        repeat(4) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(ok))
        }
        val provider = OpenAIProvider(apiKey = "test-key", model = m, basePath = basePath)
        runCatching {
            runBlocking {
                provider.sendMessageClamped(
                    messages = listOf(LLMMessage(LLMMessage.Role.USER, "q")),
                    systemPrompt = null,
                    maxTokens = maxTokens,
                    temperature = null,
                    imageParts = emptyList(),
                    tools = emptyList(),
                    thinkingLevel = level,
                )
            }
        }
        val body = JSONObject(server.takeRequest().body.readUtf8())
        val present = watched.filter { body.has(it) }.sorted()
        return present.joinToString(",", "{", "}") { "$it:${canonical(body.get(it))}" }
    }

    private data class Case(
        val label: String,
        val model: LLMModel,
        val path: String,
        val maxTokens: Int = 8192,
    )

    private fun matrix(): List<Case> = listOf(
        Case("openai-gpt5", model("gpt-5.3", reasoningEffortValues = listOf("none", "low", "medium", "high", "xhigh")), "/v1"),
        Case("openai-o3", model("o3-mini"), "/v1"),
        Case("openai-gpt4o-nonreasoning", model("gpt-4o", supportsReasoning = false), "/v1"),
        Case("openrouter", model("anthropic/claude-sonnet-4-6", reasoningEffortValues = listOf("low", "medium", "high")), "/openrouter.ai/api/v1"),
        Case("qwen", model("qwen3-32b"), "/v1", maxTokens = 16384),
        Case("qwen-tiny-max", model("qwen3-32b"), "/v1", maxTokens = 1),
        Case("deepseek-v4", model("deepseek-v4-pro", reasoningEffortValues = listOf("high", "max")), "/v1"),
        Case("deepseek-v4-unified", model("deepseek-v4-pro", reasoningEffortValues = listOf("high", "max")), "/ark.volces.com/api/v3"),
        Case("glm-declared", model("glm-5.2", reasoningEffortValues = listOf("high", "max")), "/v1"),
        Case("glm-undeclared", model("glm-4.5-air", supportsReasoning = null), "/v1"),
        Case("mimo", model("mimo-v2.5"), "/v1"),
        Case("agnes", model("agnes-1"), "/v1"),
        Case("seed", model("seed-1.6"), "/ark.volces.com/api/v3"),
        Case("generic-unknown", model("some-relay-model"), "/v1"),
        Case("sparse-high-max", model("vendor-x", reasoningEffortValues = listOf("high", "max")), "/v1"),
        Case("mistral", model("mistral-large-latest", reasoningEffortValues = listOf("low", "high")), "/mistral.ai/v1"),
        Case("venice-deepseek", model("deepseek-v4-flash", reasoningEffortValues = listOf("low", "high", "max")), "/api.venice.ai/api/v1"),

        Case("qwen-on-unified", model("qwen3-32b"), "/ark.volces.com/api/v3"),
        Case("gpt5-on-dashscope", model("gpt-5.3", reasoningEffortValues = listOf("low", "high")), "/dashscope.aliyuncs.com/compatible-mode/v1"),
        Case("mimo-on-unified", model("mimo-v2.5"), "/ark.volces.com/api/v3"),
        Case("qwen-on-openrouter", model("qwen3-32b"), "/openrouter.ai/api/v1"),
        Case("deepseek-v4-on-openrouter", model("deepseek-v4-pro", reasoningEffortValues = listOf("high", "max")), "/openrouter.ai/api/v1"),
    )

    private val levels = listOf(
        ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.MEDIUM,
        ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA,
    )

    private fun render(): String = buildString {
        for (c in matrix()) {
            for (lv in levels) {
                append("${c.label}/${lv.name} -> ${emit(c.model, lv, server.url(c.path).toString().trimEnd('/'), c.maxTokens)}")
                append('\n')
            }
        }
    }.trimEnd('\n')

    @Test
    fun `golden snapshot of every branch`() {
        assertEquals(
            """
            THINKING WIRE FORMAT CHANGED.

            This is the byte-for-byte oracle for the Thinking Rules refactor. If you are
            mid-refactor and see this fail, the new rule engine emits a different request
            than the old if-return chain for at least one (model, level) pair.

            Do not "fix" this by pasting the new output in. Diff the two blocks, identify
            which branch moved, and either restore parity or justify the change explicitly.
            """.trimIndent(),
            EXPECTED,
            render(),
        )
    }

    companion object {
        private val EXPECTED = """
openai-gpt5/OFF -> {}
openai-gpt5/LOW -> {reasoning_effort:"low"}
openai-gpt5/MEDIUM -> {reasoning_effort:"medium"}
openai-gpt5/HIGH -> {reasoning_effort:"high"}
openai-gpt5/XHIGH -> {reasoning_effort:"xhigh"}
openai-gpt5/MAX -> {reasoning_effort:"xhigh"}
openai-gpt5/ULTRA -> {reasoning_effort:"xhigh"}
openai-o3/OFF -> {}
openai-o3/LOW -> {reasoning_effort:"low"}
openai-o3/MEDIUM -> {reasoning_effort:"medium"}
openai-o3/HIGH -> {reasoning_effort:"high"}
openai-o3/XHIGH -> {reasoning_effort:"xhigh"}
openai-o3/MAX -> {reasoning_effort:"xhigh"}
openai-o3/ULTRA -> {reasoning_effort:"xhigh"}
openai-gpt4o-nonreasoning/OFF -> {}
openai-gpt4o-nonreasoning/LOW -> {}
openai-gpt4o-nonreasoning/MEDIUM -> {}
openai-gpt4o-nonreasoning/HIGH -> {}
openai-gpt4o-nonreasoning/XHIGH -> {}
openai-gpt4o-nonreasoning/MAX -> {}
openai-gpt4o-nonreasoning/ULTRA -> {}
openrouter/OFF -> {}
openrouter/LOW -> {reasoning:{effort:"low"}}
openrouter/MEDIUM -> {reasoning:{effort:"medium"}}
openrouter/HIGH -> {reasoning:{effort:"high"}}
openrouter/XHIGH -> {reasoning:{effort:"high"}}
openrouter/MAX -> {reasoning:{effort:"high"}}
openrouter/ULTRA -> {reasoning:{effort:"high"}}
qwen/OFF -> {}
qwen/LOW -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:4096},thinking_budget:4096}
qwen/MEDIUM -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:14336},thinking_budget:14336}
qwen/HIGH -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:14336},thinking_budget:14336}
qwen/XHIGH -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:14336},thinking_budget:14336}
qwen/MAX -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:14336},thinking_budget:14336}
qwen/ULTRA -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:14336},thinking_budget:14336}
qwen-tiny-max/OFF -> {}
qwen-tiny-max/LOW -> {enable_thinking:true,extra_body:{enable_thinking:true}}
qwen-tiny-max/MEDIUM -> {enable_thinking:true,extra_body:{enable_thinking:true}}
qwen-tiny-max/HIGH -> {enable_thinking:true,extra_body:{enable_thinking:true}}
qwen-tiny-max/XHIGH -> {enable_thinking:true,extra_body:{enable_thinking:true}}
qwen-tiny-max/MAX -> {enable_thinking:true,extra_body:{enable_thinking:true}}
qwen-tiny-max/ULTRA -> {enable_thinking:true,extra_body:{enable_thinking:true}}
deepseek-v4/OFF -> {thinking:{type:"disabled"}}
deepseek-v4/LOW -> {reasoning_effort:"high",thinking:{type:"enabled"}}
deepseek-v4/MEDIUM -> {reasoning_effort:"high",thinking:{type:"enabled"}}
deepseek-v4/HIGH -> {reasoning_effort:"high",thinking:{type:"enabled"}}
deepseek-v4/XHIGH -> {reasoning_effort:"high",thinking:{type:"enabled"}}
deepseek-v4/MAX -> {reasoning_effort:"max",thinking:{type:"enabled"}}
deepseek-v4/ULTRA -> {reasoning_effort:"max",thinking:{type:"enabled"}}
deepseek-v4-unified/OFF -> {reasoning_effort:"minimal"}
deepseek-v4-unified/LOW -> {reasoning_effort:"high"}
deepseek-v4-unified/MEDIUM -> {reasoning_effort:"high"}
deepseek-v4-unified/HIGH -> {reasoning_effort:"high"}
deepseek-v4-unified/XHIGH -> {reasoning_effort:"high"}
deepseek-v4-unified/MAX -> {reasoning_effort:"max"}
deepseek-v4-unified/ULTRA -> {reasoning_effort:"max"}
glm-declared/OFF -> {}
glm-declared/LOW -> {reasoning_effort:"high"}
glm-declared/MEDIUM -> {reasoning_effort:"high"}
glm-declared/HIGH -> {reasoning_effort:"high"}
glm-declared/XHIGH -> {reasoning_effort:"high"}
glm-declared/MAX -> {reasoning_effort:"max"}
glm-declared/ULTRA -> {reasoning_effort:"max"}
glm-undeclared/OFF -> {}
glm-undeclared/LOW -> {}
glm-undeclared/MEDIUM -> {}
glm-undeclared/HIGH -> {}
glm-undeclared/XHIGH -> {}
glm-undeclared/MAX -> {}
glm-undeclared/ULTRA -> {}
mimo/OFF -> {}
mimo/LOW -> {reasoning_effort:"low"}
mimo/MEDIUM -> {reasoning_effort:"medium"}
mimo/HIGH -> {reasoning_effort:"high"}
mimo/XHIGH -> {reasoning_effort:"high"}
mimo/MAX -> {reasoning_effort:"high"}
mimo/ULTRA -> {reasoning_effort:"high"}
agnes/OFF -> {}
agnes/LOW -> {reasoning_effort:"low"}
agnes/MEDIUM -> {reasoning_effort:"medium"}
agnes/HIGH -> {reasoning_effort:"high"}
agnes/XHIGH -> {reasoning_effort:"high"}
agnes/MAX -> {reasoning_effort:"high"}
agnes/ULTRA -> {reasoning_effort:"high"}
seed/OFF -> {reasoning_effort:"minimal"}
seed/LOW -> {reasoning_effort:"low"}
seed/MEDIUM -> {reasoning_effort:"medium"}
seed/HIGH -> {reasoning_effort:"high"}
seed/XHIGH -> {reasoning_effort:"high"}
seed/MAX -> {reasoning_effort:"high"}
seed/ULTRA -> {reasoning_effort:"high"}
generic-unknown/OFF -> {}
generic-unknown/LOW -> {reasoning_effort:"low"}
generic-unknown/MEDIUM -> {reasoning_effort:"medium"}
generic-unknown/HIGH -> {reasoning_effort:"high"}
generic-unknown/XHIGH -> {reasoning_effort:"xhigh"}
generic-unknown/MAX -> {reasoning_effort:"xhigh"}
generic-unknown/ULTRA -> {reasoning_effort:"xhigh"}
sparse-high-max/OFF -> {}
sparse-high-max/LOW -> {reasoning_effort:"high"}
sparse-high-max/MEDIUM -> {reasoning_effort:"high"}
sparse-high-max/HIGH -> {reasoning_effort:"high"}
sparse-high-max/XHIGH -> {reasoning_effort:"high"}
sparse-high-max/MAX -> {reasoning_effort:"max"}
sparse-high-max/ULTRA -> {reasoning_effort:"max"}
mistral/OFF -> {}
mistral/LOW -> {}
mistral/MEDIUM -> {}
mistral/HIGH -> {}
mistral/XHIGH -> {}
mistral/MAX -> {}
mistral/ULTRA -> {}
venice-deepseek/OFF -> {}
venice-deepseek/LOW -> {reasoning_effort:"low"}
venice-deepseek/MEDIUM -> {reasoning_effort:"low"}
venice-deepseek/HIGH -> {reasoning_effort:"high"}
venice-deepseek/XHIGH -> {reasoning_effort:"high"}
venice-deepseek/MAX -> {reasoning_effort:"max"}
venice-deepseek/ULTRA -> {reasoning_effort:"max"}
qwen-on-unified/OFF -> {}
qwen-on-unified/LOW -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:4096},thinking_budget:4096}
qwen-on-unified/MEDIUM -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:6144},thinking_budget:6144}
qwen-on-unified/HIGH -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:6144},thinking_budget:6144}
qwen-on-unified/XHIGH -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:6144},thinking_budget:6144}
qwen-on-unified/MAX -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:6144},thinking_budget:6144}
qwen-on-unified/ULTRA -> {enable_thinking:true,extra_body:{enable_thinking:true,thinking_budget:6144},thinking_budget:6144}
gpt5-on-dashscope/OFF -> {}
gpt5-on-dashscope/LOW -> {reasoning_effort:"low"}
gpt5-on-dashscope/MEDIUM -> {reasoning_effort:"medium"}
gpt5-on-dashscope/HIGH -> {reasoning_effort:"high"}
gpt5-on-dashscope/XHIGH -> {reasoning_effort:"high"}
gpt5-on-dashscope/MAX -> {reasoning_effort:"high"}
gpt5-on-dashscope/ULTRA -> {reasoning_effort:"high"}
mimo-on-unified/OFF -> {}
mimo-on-unified/LOW -> {reasoning_effort:"low"}
mimo-on-unified/MEDIUM -> {reasoning_effort:"medium"}
mimo-on-unified/HIGH -> {reasoning_effort:"high"}
mimo-on-unified/XHIGH -> {reasoning_effort:"high"}
mimo-on-unified/MAX -> {reasoning_effort:"high"}
mimo-on-unified/ULTRA -> {reasoning_effort:"high"}
qwen-on-openrouter/OFF -> {}
qwen-on-openrouter/LOW -> {reasoning:{effort:"low"}}
qwen-on-openrouter/MEDIUM -> {reasoning:{effort:"medium"}}
qwen-on-openrouter/HIGH -> {reasoning:{effort:"high"}}
qwen-on-openrouter/XHIGH -> {reasoning:{effort:"xhigh"}}
qwen-on-openrouter/MAX -> {reasoning:{effort:"xhigh"}}
qwen-on-openrouter/ULTRA -> {reasoning:{effort:"xhigh"}}
deepseek-v4-on-openrouter/OFF -> {}
deepseek-v4-on-openrouter/LOW -> {reasoning:{effort:"low"}}
deepseek-v4-on-openrouter/MEDIUM -> {reasoning:{effort:"medium"}}
deepseek-v4-on-openrouter/HIGH -> {reasoning:{effort:"high"}}
deepseek-v4-on-openrouter/XHIGH -> {reasoning:{effort:"xhigh"}}
deepseek-v4-on-openrouter/MAX -> {reasoning:{effort:"max"}}
deepseek-v4-on-openrouter/ULTRA -> {reasoning:{effort:"max"}}
        """.trimIndent()
    }
}
