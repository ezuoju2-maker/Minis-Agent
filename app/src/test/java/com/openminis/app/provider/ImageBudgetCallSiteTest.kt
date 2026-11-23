package com.openminis.app.provider

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageBudgetCallSiteTest {

    private val mb = 1024 * 1024

    private fun img(rawBytes: Int, path: String? = null) =
        ImageBudget.BudgetImage(ByteArray(rawBytes), path, "image/jpeg")


    @Test
    fun `every request that leaves runAgentLoop passes through applyRequestImageBudget`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val loopStart = vm.indexOf("suspend fun runAgentLoop")
        assertTrue("runAgentLoop not found", loopStart > 0)
        val loop = vm.substring(loopStart)
        val streamCalls = Regex("currentProvider\\.streamMessage\\(").findAll(loop).count()
        assertEquals("expected a single streamMessage call site in runAgentLoop", 1, streamCalls)
        val callIdx = loop.indexOf("currentProvider.streamMessage(")
        val window = loop.substring(callIdx, callIdx + 700)
        assertTrue("outgoing history must be budgeted", window.contains("currentProvider.streamMessage(\n                            requestMessages,"))
        val before = loop.substring(0, callIdx)
        val bind = before.lastIndexOf("val requestContext = RequestContextBuilder.build(requestContextInput())")
        assertTrue("bound in the same block, just before the call", bind >= 0 && callIdx - bind < 3_000)
        val between = before.substring(bind)
        assertTrue(between.contains("var requestMessages = requestContext.messages"))
        assertEquals(
            "requestMessages is assigned from the builder, then only from the reminder",
            listOf("requestContext.messages", "withReminder"),
            Regex("""requestMessages = (\S+)""").findAll(between).map { it.groupValues[1] }.toList(),
        )
        assertTrue(between.contains("QueuedCallbackReminder.inject(requestMessages, it.text)"))
        val build = vm.substringAfter("    fun build(input: Input, onOutbound").substringBefore("\n    }\n")
        assertTrue(build.contains("val outbound = outbound(input, compactionTrace, orphanTrace)"))
        assertTrue(build.contains("val budgeted = applyRequestBudget(outbound, input.resolveAttachmentsRoot)"))
        assertTrue("byte audit sits outermost", build.contains("val messages = auditOutgoingPayload(budgeted, input.contextWindowTokens, findings)"))
        assertTrue(loop.contains("while (!collectDone)"))
        assertTrue(loop.contains("retryAttempt = 0"))
        assertTrue(loop.contains("currentProvider = next"))
        assertTrue(vm.contains("private fun applyRequestImageBudget(messages: List<LLMMessage>): List<LLMMessage>"))
        assertTrue(vm.contains("RequestContextBuilder.applyRequestBudget(messages, ::requestBudgetSpillRoot, ::onRequestBudgetPlan)"))
        assertTrue(vm.contains("val applied = ImageBudget.applyRequestBudget(messages, resolveAttachmentsRoot)"))
        assertTrue(ProductionSources.read("provider/ImageBudget.kt").contains("ImageBudget.elidedImagePlaceholder(path)"))
    }


    @Test
    fun `history 6 plus this turn 2 over budget - the eldest history images degrade first`() {
        val history = (0 until 6).map { img(4 * mb, "/var/minis/offloads/tools/h$it.jpg") }
        val current = (0 until 2).map { img(4 * mb, "/var/minis/attachments/uploads/c$it.jpg") }
        val all = history + current
        val plan = ImageBudget.planRequestBudget(all)

        assertEquals(3, plan.droppedCount)
        val droppedIdx = all.indices.filter { ImageBudget.ImagePartId.of(all[it].data) in plan.droppedIds }
        assertEquals("the three ELDEST history images are the ones elided", listOf(0, 1, 2), droppedIdx)
        for (c in current) {
            assertFalse("this turn's attachment must never be elided ahead of history",
                ImageBudget.ImagePartId.of(c.data) in plan.droppedIds)
        }
        for (i in droppedIdx) {
            val id = ImageBudget.ImagePartId.of(all[i].data)
            assertEquals(all[i].linuxPath, plan.droppedPaths[id])
            assertTrue(ImageBudget.elidedImagePlaceholder(plan.droppedPaths[id]).contains(all[i].linuxPath!!))
        }
    }


    @Test
    fun `the same image instance appearing twice is one identity - never half kept`() {
        val shared = img(4 * mb, "/var/minis/attachments/uploads/same.jpg")
        val small = ImageBudget.planRequestBudget(listOf(shared, img(1 * mb), shared))
        assertEquals(0, small.droppedCount)

        val filler = (0 until 5).map { img(4 * mb) }
        val plan = ImageBudget.planRequestBudget(listOf(shared) + filler + listOf(shared))
        val sharedId = ImageBudget.ImagePartId.of(shared.data)
        assertEquals(sharedId, ImageBudget.ImagePartId.of(shared.data))
        assertTrue(plan.droppedIds.size == plan.droppedCount)
        assertEquals("one id per instance, not per occurrence",
            plan.droppedIds.size, plan.droppedIds.toSet().size)
    }


    @Test
    fun `budget is judged on base64 size not decoded bytes`() {
        val raw = (3.3 * mb).toInt()
        val five = (0 until 6).map { img(raw) }
        val rawTotal = five.sumOf { it.data.size.toLong() }
        assertTrue("fixture sanity: raw total under the cap", rawTotal < ImageBudget.MAX_REQUEST_BYTES)
        val wireTotal = five.sumOf { ImageBudget.estimatedBase64Length(it.data.size) }
        assertTrue("fixture sanity: wire total over the cap", wireTotal > ImageBudget.MAX_REQUEST_BYTES)
        assertTrue(ImageBudget.estimatedBase64Length(raw) < ImageBudget.MAX_PER_IMAGE_BYTES)

        val plan = ImageBudget.planRequestBudget(five)
        assertEquals("one image must be elided on the wire measurement", 1, plan.droppedCount)
        assertTrue(plan.keptBytes <= ImageBudget.MAX_REQUEST_BYTES)
        val one = ImageBudget.planRequestBudget(listOf(img(1 * mb)))
        assertEquals(0, one.droppedCount)
    }


    private fun provider(model: LLMModel) = OpenAIProvider(
        apiKey = "test-key", model = model, basePath = "https://example.invalid/v1",
    )

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun textParts(body: JSONObject): List<String> {
        val out = mutableListOf<String>()
        for (m in body.getJSONArray("messages").objects()) {
            val c = m.opt("content")
            when (c) {
                is String -> out.add(c)
                is JSONArray -> for (p in c.objects()) if (p.optString("type") == "text") out.add(p.getString("text"))
            }
        }
        return out
    }

    @Test
    fun `an image part never reaches the request as a text part`() {
        val pixels = ByteArray(1 * mb) { (it % 251).toByte() }
        val history = listOf(
            LLMMessage(
                role = LLMMessage.Role.USER, content = "",
                contentParts = listOf(
                    AgentContentPart.Text("look"),
                    AgentContentPart.ImageData(pixels, "image/jpeg", linuxPath = "/var/minis/attachments/uploads/a.jpg"),
                ),
            ),
        )
        val vision = LLMModel("v", "V", "test", inputModalities = listOf("text", "image"))
        val body = provider(vision).buildRequestBody(history, null, 256, false, null, emptyList())
        val userContent = body.getJSONArray("messages").objects().last().getJSONArray("content").objects()
        assertTrue(userContent.any { it.optString("type") == "image_url" })
        for (t in textParts(body)) {
            assertTrue("a text part must never carry the image payload (len=${t.length})", t.length < 4096)
            assertFalse(t.contains("base64,"))
        }

        val text = LLMModel("t", "T", "test", inputModalities = listOf("text"))
        val body2 = provider(text).buildRequestBody(history, null, 256, false, null, emptyList())
        val content2 = body2.getJSONArray("messages").objects().last().getJSONArray("content").objects()
        assertFalse(content2.any { it.optString("type") == "image_url" })
        for (t in textParts(body2)) assertTrue(t.length < 4096)
        assertTrue(content2.any { it.optString("type") == "text" && it.getString("text").contains("does not support vision") })

        val ph = ImageBudget.elidedImagePlaceholder("/var/minis/attachments/uploads/a.jpg")
        assertTrue(ph.length < 512)
        assertTrue(ph.contains("read_image"))
    }
}
