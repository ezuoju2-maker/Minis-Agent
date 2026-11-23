package com.openminis.app.provider

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToolCallDeltaAccumulationTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun stream(sse: String, responses: Boolean = false): List<LLMStreamChunk> {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel.gpt4oMini,
            basePath = server.url("/v1").toString().trimEnd('/'),
            useResponsesAPI = responses,
        )
        return runBlocking {
            provider.streamMessageClamped(
                messages = listOf(LLMMessage(LLMMessage.Role.USER, "hi")),
                systemPrompt = null,
                maxTokens = 256,
                temperature = null,
                imageParts = emptyList(),
                tools = emptyList(),
                thinkingLevel = ThinkingLevel.OFF,
            ).toList()
        }
    }

    private fun sse(vararg events: String, done: Boolean = true): String = buildString {
        for (e in events) { append("data: ").append(e).append("\n\n") }
        if (done) append("data: [DONE]\n\n")
    }

    private fun toolDelta(index: Int, id: String? = null, name: String? = null, args: String? = null): String {
        val tc = JSONObject().put("index", index)
        if (id != null) tc.put("id", id)
        val fn = JSONObject()
        if (name != null) fn.put("name", name)
        if (args != null) fn.put("arguments", args)
        if (fn.length() > 0) tc.put("function", fn)
        return """{"choices":[{"index":0,"delta":{"tool_calls":[$tc]}}]}"""
    }

    private fun toolDeltas(vararg tcs: JSONObject): String =
        """{"choices":[{"index":0,"delta":{"tool_calls":[${tcs.joinToString(",")}]}}]}"""

    private val finishToolCalls = """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""

    private fun completes(chunks: List<LLMStreamChunk>) = chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>()
    private fun starts(chunks: List<LLMStreamChunk>) = chunks.filterIsInstance<LLMStreamChunk.ToolUseStart>()


    @Test
    fun `empty id and name on later deltas keep the values from the first delta`() {
        val chunks = stream(
            sse(
                toolDelta(0, id = "call_abc", name = "shell_execute", args = ""),
                toolDelta(0, id = "", name = "", args = """{"command":"""),
                toolDelta(0, id = "", name = "", args = """"ls -la"}"""),
                finishToolCalls,
            ),
        )
        val done = completes(chunks)
        assertEquals(1, done.size)
        assertEquals("call_abc", done[0].id)
        assertEquals("shell_execute", done[0].name)
        assertEquals("ls -la", done[0].args.getString("command"))
        assertEquals("ToolUseStart fires exactly once per call", 1, starts(chunks).size)
        assertEquals("call_abc", starts(chunks)[0].id)
    }

    @Test
    fun `absent id and name on later deltas keep the values from the first delta`() {
        val chunks = stream(
            sse(
                toolDelta(0, id = "call_x", name = "read_file"),
                toolDelta(0, args = """{"path":"/a.txt"}"""),
                finishToolCalls,
            ),
        )
        val done = completes(chunks).single()
        assertEquals("call_x", done.id)
        assertEquals("read_file", done.name)
        assertEquals("/a.txt", done.args.getString("path"))
    }


    @Test
    fun `parallel calls interleaved by index reassemble separately`() {
        val chunks = stream(
            sse(
                toolDelta(0, id = "call_0", name = "read_file", args = ""),
                toolDelta(1, id = "call_1", name = "shell_execute", args = ""),
                toolDelta(0, id = "", name = "", args = """{"path":"""),
                toolDelta(1, id = "", name = "", args = """{"command":"""),
                toolDelta(0, id = "", name = "", args = """"/a.txt"}"""),
                toolDelta(1, id = "", name = "", args = """"pwd"}"""),
                finishToolCalls,
            ),
        )
        val done = completes(chunks).associateBy { it.id }
        assertEquals(setOf("call_0", "call_1"), done.keys)
        assertEquals("read_file", done.getValue("call_0").name)
        assertEquals("/a.txt", done.getValue("call_0").args.getString("path"))
        assertEquals("shell_execute", done.getValue("call_1").name)
        assertEquals("pwd", done.getValue("call_1").args.getString("command"))
        assertEquals(2, starts(chunks).size)
    }

    @Test
    fun `index wins over array position inside one chunk`() {
        val chunks = stream(
            sse(
                toolDeltas(
                    JSONObject().put("index", 1).put("id", "call_b").put("function", JSONObject().put("name", "beta").put("arguments", """{"b":1}""")),
                    JSONObject().put("index", 0).put("id", "call_a").put("function", JSONObject().put("name", "alpha").put("arguments", """{"a":1}""")),
                ),
                finishToolCalls,
            ),
        )
        val done = completes(chunks).associateBy { it.id }
        assertEquals(1, done.getValue("call_a").args.getInt("a"))
        assertEquals("alpha", done.getValue("call_a").name)
        assertEquals(1, done.getValue("call_b").args.getInt("b"))
        assertEquals("beta", done.getValue("call_b").name)
    }


    @Test
    fun `arguments split across five fragments concatenate into parseable json`() {
        val full = """{"command":"echo hello","timeout":30,"cwd":"/tmp"}"""
        val cut = listOf(0, 7, 15, 28, 41, full.length)
        val frags = (0 until cut.size - 1).map { full.substring(cut[it], cut[it + 1]) }
        assertEquals(5, frags.size)
        val events = mutableListOf(toolDelta(0, id = "call_f", name = "shell_execute", args = frags[0]))
        for (f in frags.drop(1)) events += toolDelta(0, id = "", name = "", args = f)
        events += finishToolCalls
        val chunks = stream(sse(*events.toTypedArray()))
        val done = completes(chunks).single()
        assertEquals("echo hello", done.args.getString("command"))
        assertEquals(30, done.args.getInt("timeout"))
        assertEquals("/tmp", done.args.getString("cwd"))
        val deltas = chunks.filterIsInstance<LLMStreamChunk.ToolInputDelta>().map { it.accumulated }
        assertTrue(deltas.zipWithNext().all { (a, b) -> b.startsWith(a) && b.length >= a.length })
        assertEquals(full, deltas.last())
    }


    @Test
    fun `a stream cut mid-arguments surfaces empty args plus the raw tail, never a fabricated object`() {
        val chunks = stream(
            sse(
                toolDelta(0, id = "call_t", name = "file_write", args = """{"path":"/tmp/x.txt","content":"partial te"""),
                done = false,
            ),
        )
        val done = completes(chunks).single()
        assertEquals("call_t", done.id)
        assertEquals("file_write", done.name)
        assertEquals("the reducer must not invent fields from a cut stream", 0, done.args.length())
        val rawTail = chunks.filterIsInstance<LLMStreamChunk.ToolInputDelta>().last().accumulated
        assertEquals("""{"path":"/tmp/x.txt","content":"partial te""", rawTail)
        assertTrue("the tail is genuinely unparseable", runCatching { JSONObject(rawTail) }.isFailure)
        assertNull(
            "a cut stream must not report a finish reason",
            chunks.filterIsInstance<LLMStreamChunk.Finished>().firstOrNull()?.stopReason,
        )

        val tools = listOf(
            AgentToolDefinition(
                name = "file_write",
                description = "",
                parameters = mapOf(
                    "path" to AgentToolParam("string", ""),
                    "content" to AgentToolParam("string", ""),
                ),
                required = listOf("path", "content"),
            ),
        )
        val tags = ToolJsonRepair.repair("file_write", done.args, rawTail, tools)
        assertTrue("a repair must announce itself: $tags", tags.any { it.startsWith("truncation+") })
        assertEquals("/tmp/x.txt", done.args.getString("path"))
        assertEquals("partial te", done.args.getString("content"))
    }

    @Test
    fun `a clean tool call stream needs no repair`() {
        val chunks = stream(sse(toolDelta(0, id = "call_c", name = "read_file", args = """{"path":"/a"}"""), finishToolCalls))
        val done = completes(chunks).single()
        val tags = ToolJsonRepair.repair(
            "read_file", done.args, """{"path":"/a"}""",
            listOf(AgentToolDefinition("read_file", "", mapOf("path" to AgentToolParam("string", "")), listOf("path"))),
        )
        assertEquals(emptyList<String>(), tags)
        assertEquals("tool_calls", chunks.filterIsInstance<LLMStreamChunk.Finished>().single().stopReason)
    }

    @Test
    fun `start is emitted only once both id and name are known`() {
        val chunks = stream(
            sse(
                toolDelta(0, name = "shell_execute", args = """{"co"""),
                toolDelta(0, id = "call_late", args = """mmand":"ls"}"""),
                finishToolCalls,
            ),
        )
        val s = starts(chunks)
        assertEquals(1, s.size)
        assertEquals("call_late", s[0].id)
        assertEquals("ls", completes(chunks).single().args.getString("command"))
        assertFalse("nothing started under an empty id", s.any { it.id.isEmpty() })
    }


    @Test
    fun `responses function_call arguments accumulate per item_id`() {
        val chunks = stream(
            sse(
                """{"type":"response.output_item.added","item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"read_file"}}""",
                """{"type":"response.output_item.added","item":{"type":"function_call","id":"fc_2","call_id":"call_2","name":"shell_execute"}}""",
                """{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\"path\":"}""",
                """{"type":"response.function_call_arguments.delta","item_id":"fc_2","delta":"{\"command\":"}""",
                """{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"\"/a.txt\"}"}""",
                """{"type":"response.function_call_arguments.delta","item_id":"fc_2","delta":"\"pwd\"}"}""",
                """{"type":"response.output_item.done","item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"read_file","arguments":"{\"path\":\"/a.txt\"}"}}""",
                """{"type":"response.output_item.done","item":{"type":"function_call","id":"fc_2","call_id":"call_2","name":"shell_execute","arguments":"{\"command\":\"pwd\"}"}}""",
                """{"type":"response.completed","response":{"status":"completed","output":[{"type":"function_call"}]}}""",
                done = false,
            ),
            responses = true,
        )
        val done = completes(chunks).associateBy { it.id }
        assertEquals(setOf("call_1|fc_1", "call_2|fc_2"), done.keys)
        assertEquals("/a.txt", done.getValue("call_1|fc_1").args.getString("path"))
        assertEquals("pwd", done.getValue("call_2|fc_2").args.getString("command"))
        assertEquals("tool_use", chunks.filterIsInstance<LLMStreamChunk.Finished>().single().stopReason)
    }

    @Test
    fun `responses stream cut mid-arguments flushes the call with empty args`() {
        val chunks = stream(
            sse(
                """{"type":"response.output_item.added","item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"file_write"}}""",
                """{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\"path\":\"/x\",\"content\":\"partia"}""",
                done = false,
            ),
            responses = true,
        )
        val done = completes(chunks).single()
        assertEquals("call_1|fc_1", done.id)
        assertEquals(0, done.args.length())
        assertEquals(
            """{"path":"/x","content":"partia""",
            chunks.filterIsInstance<LLMStreamChunk.ToolInputDelta>().last().accumulated,
        )
        assertNull(chunks.filterIsInstance<LLMStreamChunk.Finished>().firstOrNull()?.stopReason)
    }
}
