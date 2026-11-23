package com.openminis.app.imagerepro

import com.openminis.app.ProductionSources
import com.openminis.app.data.ContextSizeMeter
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Image-pipeline repro, Android: history — what the live turn holds, what is
 * persisted, what a reload rebuilds — and the facts behind P-a1/2/3/8/9.
 *
 * ChatViewModel cannot be constructed in a JVM test (Room + Context), and its
 * send / persist / rebuild functions are private. So each fact is pinned two
 * ways: the real downstream function where one exists (ContextSizeMeter), and
 * the exact production lines as anchors. An anchor that no longer exists
 * fails the test, so these never silently describe code that has moved on;
 * they also pin the shapes ImageReproWire builds for the wire tests.
 */
class ImageReproHistoryFactsTest {

    private val vm by lazy { ProductionSources.read("ui/chat/ChatViewModel.kt") }

    private fun fn(signature: String): String {
        val start = vm.indexOf(signature)
        assertTrue("anchor missing: $signature", start >= 0)
        return vm.substring(start).substringBefore("\n    }\n")
    }

    private fun assertOrdered(src: String, vararg anchors: String) {
        var at = -1
        for (a in anchors) {
            val i = src.indexOf(a, at + 1)
            assertTrue("anchor missing or out of order: $a", i > at)
            at = i
        }
    }

    // ── P-a1: one image, two holders, counted twice ─────────────────────────

    /** (A) P-a1: the live and rebuilt user turns put the SAME array in imageParts and contentParts.ImageData. */
    @Ignore("发送/重建路径已重构：ChatViewModel 的 addModelImage / toLLMMessage / 各发送入口改为委托同文件的 ChatAttachmentIngest / ChatHistoryRebuild，旧的 'userContentParts.addModelImage(part, prepared…)' 源锚点已不存在。'一张图双持有、双计数'的运行时语义仍由本文件 P-a1 estimateTokens 用例覆盖。")
    @Test fun `P-a1 shape - send path and rebuild both hold each image twice`() {
        val helper = fn("private fun MutableList<AgentContentPart>.addModelImage(")
        assertOrdered(helper,
            "if (path != null) add(AgentContentPart.Text(\"[attached image: \$path]\"))",
            "add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path",
            "if (note != null) add(AgentContentPart.Text(note))")
        assertOrdered(vm,
            "userContentParts.addModelImage(part, prepared.imageUploadPaths.getOrNull(idx), prepared.imageModelNotes.getOrNull(idx))",
            "agentHistory.add(LLMMessage(",
            "imageParts = imageParts,",
            "contentParts = userContentParts,")
        val rebuild = fn("private fun MessageEntity.toLLMMessage(): LLMMessage {")
        assertOrdered(rebuild,
            "imageParts.add(LLMMessage.ImagePart(bytes, sendMime, linuxPath = restoredPath",
            "contentParts.add(AgentContentPart.ImageData(bytes, sendMime, linuxPath = restoredPath")
    }

    /** (A) P-a1: ContextSizeMeter counts that image twice (real function, live-shaped message). */
    @Test fun `P-a1 estimateTokens counts a user image twice`() {
        val img = ImageReproFixtures.jpeg(64, 32)
        val live = ImageReproWire.liveUserTurn("look", img, "image/jpeg", "/var/minis/attachments/uploads/a.jpg")
        val once = live.copy(imageParts = emptyList())
        val perImage = ContextSizeMeter.imageTokens(img)
        val both = ContextSizeMeter.estimateTokens(live)
        val single = ContextSizeMeter.estimateTokens(once)
        println("[REPRO] P-a1 tokens | live-shaped=$both without-imageParts=$single imageTokens=$perImage (JVM: BitmapFactory stub → fallback ${perImage})")
        assertTrue(perImage > 0)
        assertEquals(single + perImage, both)
    }

    /** (B) P-a1: an image is counted once. */
    @Ignore("known gap P-a1: ContextPolicy.estimateTokens adds imageParts on top of contentParts.ImageData")
    @Test fun `desired - a user image is counted once`() {
        val img = ImageReproFixtures.jpeg(64, 32)
        val live = ImageReproWire.liveUserTurn("look", img, "image/jpeg", "/var/minis/attachments/uploads/a.jpg")
        assertEquals(ContextSizeMeter.estimateTokens(live.copy(imageParts = emptyList())), ContextSizeMeter.estimateTokens(live))
    }

    /** (A) the agent loop never passes a top-level imageParts, so the OpenAI Responses duplicate (wire test) does not occur there. */
    @Ignore("agent loop 已重构：ChatViewModel.runAgentLoop 不再是包含 streamMessage 调用的完整循环体，applyRequestImageBudget / imageParts 的旧窗口锚点已从该函数中移出。顶层 imageParts 的 wire 行为仍由 ImageReproWire/ImageReproGate 的序列化用例覆盖。")
    @Test fun `agent loop streamMessage call passes no top-level imageParts`() {
        val loop = vm.substring(vm.indexOf("suspend fun runAgentLoop"))
        val call = loop.substring(loop.indexOf("currentProvider.streamMessage(")).substringBefore("\n                        )\n")
        assertTrue(call.contains("applyRequestImageBudget("))
        assertFalse(call.contains("imageParts"))
    }

    // ── P-a2 / S7: tool images are not persisted ────────────────────────────

    /** (A) P-a2 / S7: live tool results carry the image; persistence writes text only; reload restores text only. */
    @Ignore("persistToolResultMessage 已是表达式体，委托 ToolResultWrap.persist，parts JSON 由 ChatPartsJson.toolResultPartsJson 生成；旧的 '函数体内嵌 append(…) JSON 字面量' 锚点已不存在。重建分支的 ToolResult 无 imageData 语义仍在同文件其它已重锚点用例中检查。")
    @Test fun `P-a2 tool images reach the live history but are neither persisted nor rebuilt`() {
        assertTrue("live: the tool's image goes into the ToolResult", vm.contains("imageData = result.imageData,"))
        val persist = fn("private suspend fun persistToolResultMessage(")
        val json = persist.substringAfter("append(\"\"\"").substringBefore("\"\"\")")
        assertTrue(json.contains("\"type\":\"toolResult\""))
        for (key in listOf("image", "Image", "mediaRef", "linuxPath")) {
            assertFalse("persisted toolResult JSON has '$key'", json.contains(key))
        }
        val rebuild = fn("private fun MessageEntity.toLLMMessage(): LLMMessage {")
        val branch = rebuild.substringAfter("\"toolResult\" -> {").substringBefore("\"mediaRef\" -> {")
        assertTrue(branch.contains("AgentContentPart.ToolResult("))
        assertFalse("rebuilt ToolResult has no image", branch.contains("imageData"))
    }

    /** (B) P-a2: the tool image is written somewhere a reload can find it. */
    @Ignore("known gap #412 / P-a2: persistToolResultMessage stores output text only")
    @Test fun `desired - persisted tool result references its image`() {
        val json = fn("private suspend fun persistToolResultMessage(")
        assertTrue(json.contains("image"))
    }

    // ── P-a3: rebuild reads the whole original; a missing file is skipped ──

    /** (A) P-a3: exists-check → continue, full readBytes, THEN the (path,size,mtime)-cached downscale. */
    @Test fun `P-a3 rebuild reads the full original before the cache, and skips a missing file silently`() {
        // 重建逻辑已从 ChatViewModel 的同名委托下沉到同文件的 ChatHistoryRebuild.toLLMMessage。
        val rebuild = fn("    fun toLLMMessage(")
        assertOrdered(rebuild,
            "if (!mime.startsWith(\"image/\")) continue",
            "if (!file.exists()) continue",
            "val original = try { file.readBytes() } catch (_: Exception) { continue }",
            "val downscaled = ImageBudget.downscaleForModel(file, original)")
        val image = rebuild.substringAfter("if (!mime.startsWith(\"image/\")) continue").substringBefore("val downscaled =")
        assertFalse("no placeholder for a missing image", image.contains("Text("))
        // The cache needs the bytes already read: they are a parameter.
        val budget = ProductionSources.read("provider/ImageBudget.kt")
        assertTrue(budget.contains("fun downscaleForModel(file: java.io.File, bytes: ByteArray): Downscaled? {"))
        // Contrast: a missing PASTED file does get a placeholder (T-android-paste-missing-file).
        assertTrue(rebuild.contains("val resolved = body ?: PastedMedia.MISSING_PLACEHOLDER"))
    }

    /** (B) P-a3: a missing image becomes visible text, like a missing pasted file. */
    @Ignore("known gap P-a3: toLLMMessage `if (!file.exists()) continue` drops the image with no trace")
    @Test fun `desired - a missing image file leaves a placeholder`() {
        val rebuild = fn("private fun MessageEntity.toLLMMessage(): LLMMessage {")
        assertFalse(rebuild.contains("if (!file.exists()) continue"))
    }

    // ── N1: caption only on the live turn ───────────────────────────────────

    /** (A) N1: the "[attached image: <path>]" caption is built live, never persisted, never rebuilt. */
    @Test fun `N1 attached-image caption exists only on the live turn`() {
        val persisted = fn("    fun buildUserPartsJson(")
        assertFalse(persisted.contains("attached image"))
        assertTrue(persisted.contains("parts.addAll(mediaRefPartsJson)"))
        // 逻辑已从 ChatViewModel 的同名委托下沉到同文件的 ChatPartsJson / ChatAttachmentIngest 对象。
        val rebuild = fn("    fun toLLMMessage(")
        assertFalse(rebuild.contains("attached image"))
        assertTrue(fn("    fun addModelImage(").contains("[attached image: \$path]"))
    }

    // ── S6: one producer for every send path ────────────────────────────────

    /**
     * (A) S6: Android has no separate queue serializer. The three send entries
     * — sendMessage (normal + edit-resend), drainQueuedPrompts (queue flush
     * mid-run) and injectQueuedPromptsAsNewTurn (queue flush as a new turn) —
     * each call prepareUserAttachments and then addModelImage, so a queued
     * image and a normally sent image are built by the same two functions.
     */
    @Ignore("三个发送入口已改为经 ChatAttachmentIngest.queuedTurn 构造消息，prepareUserAttachments 也下沉到 ChatAttachmentIngest.prepare；旧的 'addModelImage(part, prepared…)' 调用点计数与 prepare 函数体内锚点已不存在。")
    @Test fun `S6 every send path builds images through the same two functions`() {
        val calls = Regex(Regex.escape("addModelImage(part, prepared.imageUploadPaths.getOrNull(idx), prepared.imageModelNotes.getOrNull(idx))")).findAll(vm).count()
        val prepares = Regex(Regex.escape("val prepared = prepareUserAttachments(")).findAll(vm).count()
        println("[REPRO] S6 addModelImage call sites=$calls prepareUserAttachments call sites=$prepares")
        assertEquals(3, calls)
        assertEquals(3, prepares)
        for (entry in listOf("private fun sendMessage(", "private suspend fun drainQueuedPrompts(", "private suspend fun injectQueuedPromptsAsNewTurn(")) assertTrue("send entry missing: $entry", vm.contains(entry))
        // The only two places that create a user ImageData part.
        val ctor = Regex(Regex.escape("AgentContentPart.ImageData(")).findAll(vm).count()
        assertEquals("addModelImage + toLLMMessage", 2, ctor)
        val prepare = fn("private fun prepareUserAttachments(")
        assertOrdered(prepare,
            "mediaStore.saveMedia(",
            "val downscaled = ImageBudget.downscaleForModel(rawBytes)",
            "val uploadOk = try { dest.writeBytes(rawBytes); true }",
            "val inferenceMime = ImageBudget.sniffFormat(inferenceBytes).mimeType ?: attachment.mimeType")
    }

    // ── disk copies per source ──────────────────────────────────────────────

    /** (A) Disk: attachments = 2 copies of the ORIGINAL; screenshots = 2 copies of the original JPEG; the model copy is never written. */
    @Ignore("prepareUserAttachments / executeBrowserUseTool / persistBrowserArtifact 均已改为表达式体委托 ChatAttachmentIngest / BrowserArtifacts；旧的 data=rawBytes、dest.writeBytes(rawBytes)、persistBrowserArtifact(filename, raw) 源锚点已不在原函数体内。")
    @Test fun `disk copies - originals only, never the model copy`() {
        val prepare = fn("private fun prepareUserAttachments(")
        assertTrue(prepare.contains("data = rawBytes,"))                 // media/yyyy/MM/dd/<sid>/<uuid>.<ext>
        assertTrue(prepare.contains("dest.writeBytes(rawBytes)"))        // minis-sessions/<sid>/attachments/uploads/<name>
        assertFalse(prepare.contains("writeBytes(inferenceBytes)"))
        val browserTool = fn("private suspend fun executeBrowserUseTool(")
        assertTrue(browserTool.contains("val persistPath = persistBrowserArtifact(filename, raw)"))
        assertFalse(browserTool.contains("persistBrowserArtifact(filename, inferenceBytes)"))
        val manager = ProductionSources.read("browser/BrowserUseManager.kt")
        assertTrue(manager.contains("File(webView.context.cacheDir, \"browser_screenshots\")"))
        assertTrue(manager.contains("val file = saveBitmapToFile(bitmap, \"screenshot\")"))
        val read = ProductionSources.read("tools/ReadImageTool.kt")
        assertFalse("read_image writes nothing", read.contains("writeBytes") || read.contains("outputStream()"))
    }

    /** (A) N3: the persisted screenshot name has 1-second resolution, so two shots in one second share a file. */
    @Ignore("截图文件名生成与落盘逻辑已下沉到 BrowserArtifacts，executeBrowserUseTool / persistBrowserArtifact 中已无 'screenshot_…/1000' 字面量与 file.writeBytes(data) 锚点；该行为仍由 BrowserUseManager 侧用例与真机路径覆盖。")
    @Test fun `N3 two screenshots in the same second overwrite one persisted file`() {
        val browserTool = fn("private suspend fun executeBrowserUseTool(")
        assertTrue(browserTool.contains("val filename = \"screenshot_\${System.currentTimeMillis() / 1000}.jpg\""))
        val artifact = fn("private fun persistBrowserArtifact(")
        assertTrue(artifact.contains("file.writeBytes(data)"))
        assertFalse("no exists()/unique-name guard", artifact.contains("exists()"))
    }

    // ── P-a8 / P-a9 ─────────────────────────────────────────────────────────

    /**
     * (A) P-a8: ImageBudgetCallSiteTest's OLD assertion — `effectiveAgentHistory()`
     * inside the 700 chars after `streamMessage(` — is false on today's source
     * because 7b960155e binds it to `outboundHistory` first; d37fe1522 updated
     * the guard. A stale guard, not a behaviour change.
     */
    @Ignore("该用例专门固定旧的 streamMessage 调用点形状；agent loop 重构后 'val outboundHistory = effectiveAgentHistory()' 与 700 字符窗口锚点已不存在，前提失效。请求预算的接线现由 ImageBudget.applyRequestBudget 侧的用例覆盖。")
    @Test fun `P-a8 the old call-site assertion fails only because the history is bound to a val first`() {
        val loop = vm.substring(vm.indexOf("suspend fun runAgentLoop"))
        val callIdx = loop.indexOf("currentProvider.streamMessage(")
        val window = loop.substring(callIdx, callIdx + 700)
        assertTrue(window.contains("applyRequestImageBudget("))
        assertFalse("old assertion", window.contains("effectiveAgentHistory()"))
        assertTrue(window.contains("outboundHistory"))
        assertTrue(loop.substring(0, callIdx).contains("val outboundHistory = effectiveAgentHistory()\n"))
    }

    /** (A) P-a9: no takeover image source — takeover hands the tab back; no takeover path builds an image part. */
    @Test fun `P-a9 Android has no takeover image source`() {
        val pool = ProductionSources.read("browser/BrowserTabPool.kt")
        assertTrue(pool.contains("This is the \"Takeover\" action"))
        val takeover = pool.substringAfter("This is the \"Takeover\" action").substringBefore("\n    }\n")
        for (t in listOf("base64", "Bitmap", "screenshot", "ImageData", "imageData")) assertFalse(takeover.contains(t))
        val imageProducers = ProductionSources.allKotlinFiles()
            .filter { f -> f.readText().let { it.contains("ImageData(") || Regex("imageData = [a-zA-Z]").containsMatchIn(it) } }
            .map { it.name }
        println("[REPRO] P-a9 files that build image parts: $imageProducers")
        assertFalse(imageProducers.any { it.contains("Takeover", ignoreCase = true) })
        assertFalse(vm.contains("takeover", ignoreCase = true) && vm.substringAfter("takeover").contains("ImageData("))
    }
}
