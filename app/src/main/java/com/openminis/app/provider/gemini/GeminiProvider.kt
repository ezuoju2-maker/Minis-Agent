package com.openminis.app.provider.gemini

import android.util.Base64
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.safeOptString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import com.openminis.app.provider.failOnSilentEmptyCompletion
import com.openminis.app.provider.thinking.ThinkingRuleResolver

class GeminiProvider(
    private val apiKey: String,
    override var model: LLMModel = LLMModel.gemini25Flash,
    private val basePath: String = "https://generativelanguage.googleapis.com/v1beta",
) : LLMProvider, com.openminis.app.network.NetworkTagged {
    override val name = "Google"

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .connectionPool(com.openminis.app.network.NetworkMonitor.sharedLLMConnectionPool)
        .build()

    override var netTag: String? = null

    private val client: OkHttpClient get() = com.openminis.app.network.NetworkHttp.client(netTag, baseClient)

    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse = withContext(Dispatchers.IO) {
        val response = executeWithThinkingRetry("generateContent") { format ->
            buildRequestBody(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel, format)
        }
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw mapHttpError(response.code, responseBody)
        }

        val json = JSONObject(responseBody)
        val text = extractText(json)
        val finishReason = extractFinishReason(json)
        val usage = extractUsage(json)
        val mediaAttachments = extractInlineMedia(json)
        LLMResponse(text, finishReason ?: "end_turn", usage, mediaAttachments)
    }

    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = rawStreamMessage(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
    ).failOnSilentEmptyCompletion(name)

    private fun inlinePart(data: ByteArray, declaredMime: String?): JSONObject {
        val upload = ImageBudget.prepareForUpload(data, declaredMime)
            ?: return JSONObject().put("text", ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER)
        return JSONObject().put("inlineData", JSONObject().apply {
            put("mimeType", upload.mimeType)
            put("data", upload.base64())
        })
    }

    private fun executeWithThinkingRetry(
        method: String,
        bodyFor: (ThinkingRuleResolver.GeminiThinkingFormat?) -> JSONObject,
    ): okhttp3.Response {
        val rawUrl = "$basePath/models/${model.id}:$method"
        val url = rawUrl.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("key", com.openminis.app.provider.WireSanitize.token(apiKey))
            ?.build()
            ?.toString()
            ?: "$rawUrl${if ('?' in method) "&" else "?"}key=${com.openminis.app.provider.WireSanitize.queryEncoded(apiKey)}"
        fun post(body: JSONObject) = client.newCall(
            Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .applyUserAgentOverride(null)
                .build(),
        ).execute()

        val instance = netTag ?: basePath
        val modelId = model.id
        val first = bodyFor(GeminiThinkingRetry.preferred(instance, modelId))
        val response = post(first)
        if (response.code != 400) return response
        val sent = GeminiThinkingRetry.thinkingConfigOf(first) ?: return response
        val errorBody = response.body?.string() ?: ""
        response.close()
        val format = GeminiThinkingRetry.formatFor(errorBody) ?: throw mapHttpError(400, errorBody)
        val second = bodyFor(format)
        val resent = GeminiThinkingRetry.thinkingConfigOf(second)
        if (resent == sent) throw mapHttpError(400, errorBody)
        com.openminis.app.logging.AppLogger.info(
            "Thinking",
            "[GeminiThinkingRetry] model=$modelId 400 on thinkingConfig " +
                "${GeminiThinkingRetry.describe(sent)} -> retrying as $format ${GeminiThinkingRetry.describe(resent)}",
        )
        val retried = post(second)
        if (retried.isSuccessful) GeminiThinkingRetry.remember(instance, modelId, format)
        return retried
    }

    private fun rawStreamMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = callbackFlow {
        val response = executeWithThinkingRetry("streamGenerateContent?alt=sse") { format ->
            buildRequestBody(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel, format)
        }
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            response.close()
            throw mapHttpError(response.code, errorBody)
        }

        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
        try {
            var started = false
            var lastFinishReason: String? = null
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data: ")) continue
                val payload = l.removePrefix("data: ")

                val json = try { JSONObject(payload) } catch (_: Exception) { continue }

                if (!started) {
                    send(LLMStreamChunk.Started)
                    started = true
                }

                val (text, thinking) = extractTextAndThinking(json)
                if (thinking.isNotEmpty()) {
                    send(LLMStreamChunk.ThinkingDelta(thinking))
                }
                if (text.isNotEmpty()) {
                    send(LLMStreamChunk.Text(text))
                }

                val functionCalls = extractFunctionCalls(json)
                for ((fcName, fcArgs, fcSig) in functionCalls) {
                    val toolId = "gemini_${System.nanoTime()}"
                    send(LLMStreamChunk.ToolUseStart(toolId, fcName))
                    send(LLMStreamChunk.ToolCallComplete(toolId, fcName, fcArgs, thoughtSignature = fcSig))
                }

                extractUsage(json)?.let { usage ->
                    send(LLMStreamChunk.Usage(usage))
                }

                extractFinishReason(json)?.let { reason ->
                    lastFinishReason = reason
                }
            }
            send(LLMStreamChunk.Finished(lastFinishReason ?: "end_turn"))
        } catch (e: Exception) {
            cancel("Stream error", mapError(e))
        } finally {
            reader.close()
            response.close()
        }
        channel.close()
        awaitClose()
    }

    private fun buildRequestBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
        thinkingFormat: ThinkingRuleResolver.GeminiThinkingFormat? = null,
    ): JSONObject {
        val body = JSONObject()

        val requiresSig = ThinkingRuleResolver.isGeminiLevelFamily(model.id)
        val unsignedToolCallIds: Set<String> = if (!requiresSig) emptySet() else buildSet {
            for (msg in messages) {
                val toolUses = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
                if (toolUses.isEmpty()) continue
                if (toolUses.any { it.thoughtSignature.isNullOrEmpty() }) {
                    toolUses.forEach { add(it.id) }
                }
            }
        }

        val contents = JSONArray()
        val lastUserIndex = messages.indexOfLast { it.role == LLMMessage.Role.USER }
        for ((index, msg) in messages.withIndex()) {
            val role = if (msg.role == LLMMessage.Role.USER) "user" else "model"
            val content = JSONObject()
            content.put("role", role)

            val parts = JSONArray()

            if (msg.contentParts.isNotEmpty()) {
                val ordered = if (msg.role == LLMMessage.Role.USER && msg.contentParts.any { it is AgentContentPart.ToolResult }) {
                    msg.contentParts.filter { it is AgentContentPart.ToolResult } +
                        msg.contentParts.filter { it !is AgentContentPart.ToolResult }
                } else msg.contentParts
                for (part in ordered) {
                    when (part) {
                        is AgentContentPart.Text -> {
                            if (part.text.isNotEmpty()) {
                                parts.put(JSONObject().put("text", part.text))
                            }
                        }
                        is AgentContentPart.ToolUse -> {
                            if (part.id in unsignedToolCallIds) {
                                parts.put(JSONObject().put("text", narratedToolCall(part.name, part.input)))
                            } else {
                                parts.put(JSONObject().apply {
                                    if (requiresSig && !part.thoughtSignature.isNullOrEmpty()) {
                                        put("thoughtSignature", part.thoughtSignature)
                                    }
                                    put("functionCall", JSONObject().apply {
                                        put("name", part.name)
                                        put("args", part.input)
                                    })
                                })
                            }
                        }
                        is AgentContentPart.ToolResult -> {
                            if (part.id in unsignedToolCallIds) {
                                parts.put(JSONObject().put("text", narratedToolResult(part.content, part.isError)))
                                val trBytes = part.imageData
                                if (trBytes != null && trBytes.isNotEmpty()) {
                                    parts.put(inlinePart(trBytes, part.imageMimeType))
                                }
                            } else {
                                val responseObj = JSONObject()
                                responseObj.put("name", part.name)
                                val responseContent = JSONObject()
                                val safeContent = part.content.ifEmpty { " " }
                                responseContent.put("result", safeContent)
                                if (part.isError) responseContent.put("error", true)
                                responseObj.put("response", responseContent)
                                parts.put(JSONObject().put("functionResponse", responseObj))
                                val trBytes = part.imageData
                                if (trBytes != null && trBytes.isNotEmpty()) {
                                    parts.put(inlinePart(trBytes, part.imageMimeType))
                                }
                            }
                        }
                        is AgentContentPart.ImageData -> {
                            parts.put(inlinePart(part.data, part.mimeType))
                        }
                    }
                }
            } else {
                if (index == lastUserIndex && imageParts.isNotEmpty()) {
                    for (part in imageParts) {
                        parts.put(inlinePart(part.data, part.mimeType))
                    }
                }
                val legacyText = msg.content.ifEmpty { " " }
                parts.put(JSONObject().put("text", legacyText))
            }
            if (parts.length() == 0) {
                parts.put(JSONObject().put("text", "(empty)"))
            }
            content.put("parts", parts)
            contents.put(content)
        }
        body.put("contents", contents)

        val rejectsSystemInstruction = "audio" in model.outputModalities.orEmpty()
        if (systemPrompt != null && !rejectsSystemInstruction) {
            body.put("systemInstruction", JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text", systemPrompt))
            ))
        }

        if (tools.isNotEmpty()) {
            val funcDecls = JSONArray()
            for (tool in tools) {
                funcDecls.put(tool.toGeminiJson())
            }
            body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", funcDecls)))
            body.put(
                "toolConfig",
                JSONObject().put("functionCallingConfig", JSONObject().put("mode", "AUTO")),
            )
        }

        val config = JSONObject()
        config.put("maxOutputTokens", maxTokens)
        if (temperature != null) {
            config.put("temperature", temperature)
        }

        buildThinkingConfig(thinkingLevel, thinkingFormat)?.let { thinkingConfig ->
            config.put("thinkingConfig", thinkingConfig)
        }

        val outputs = model.outputModalities.orEmpty()
        when {
            "audio" in outputs -> config.put("responseModalities", JSONArray().put("AUDIO"))
            "image" in outputs -> config.put(
                "responseModalities",
                JSONArray().put("TEXT").put("IMAGE"),
            )
        }

        body.put("generationConfig", config)

        return body
    }

    private fun buildThinkingConfig(
        level: ThinkingLevel,
        format: ThinkingRuleResolver.GeminiThinkingFormat? = null,
    ): JSONObject? {
        val cfg = ThinkingRuleResolver.geminiThinkingConfig(model.id, level, format)
        com.openminis.app.logging.AppLogger.info(
            "Thinking",
            "[resolve] provider=gemini model=${model.id} level=${level.name} " +
                "keys=[${cfg?.keys()?.asSequence()?.sorted()?.joinToString(",") ?: ""}]",
        )
        return cfg
    }

    private fun extractText(json: JSONObject): String {
        return extractTextAndThinking(json).first
    }

    private fun extractTextAndThinking(json: JSONObject): Pair<String, String> {
        val candidates = json.optJSONArray("candidates") ?: return "" to ""
        val first = candidates.optJSONObject(0) ?: return "" to ""
        val content = first.optJSONObject("content") ?: return "" to ""
        val parts = content.optJSONArray("parts") ?: return "" to ""

        val textBuilder = StringBuilder()
        val thinkingBuilder = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            val text = part.safeOptString("text", "")
            if (text.isEmpty()) continue
            if (part.optBoolean("thought", false)) {
                thinkingBuilder.append(text)
            } else {
                textBuilder.append(text)
            }
        }
        return textBuilder.toString() to thinkingBuilder.toString()
    }

    private fun extractInlineMedia(json: JSONObject): List<LLMMediaAttachment> {
        val candidates = json.optJSONArray("candidates") ?: return emptyList()
        val first = candidates.optJSONObject(0) ?: return emptyList()
        val content = first.optJSONObject("content") ?: return emptyList()
        val parts = content.optJSONArray("parts") ?: return emptyList()

        val out = mutableListOf<LLMMediaAttachment>()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val inline = part.optJSONObject("inlineData") ?: continue
            val mime = inline.safeOptString("mimeType", "")
            val b64 = inline.safeOptString("data", "")
            if (mime.isEmpty() || b64.isEmpty()) continue
            val bytes = try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (e: Throwable) {
                android.util.Log.d("ModelUseImage", "gemini inlineData base64 decode failed: ${e.message}")
                continue
            }
            val type = when {
                mime.startsWith("image/") -> LLMMediaAttachment.MediaType.IMAGE
                mime.startsWith("audio/") -> LLMMediaAttachment.MediaType.AUDIO
                mime.startsWith("video/") -> LLMMediaAttachment.MediaType.VIDEO
                else -> LLMMediaAttachment.MediaType.IMAGE
            }
            android.util.Log.d("ModelUseImage", "gemini inlineData received: mime=$mime bytes=${bytes.size}")
            out.add(LLMMediaAttachment(type, mime, bytes))
        }
        return out
    }

    private fun extractFunctionCalls(json: JSONObject): List<Triple<String, JSONObject, String?>> {
        val candidates = json.optJSONArray("candidates") ?: return emptyList()
        val first = candidates.optJSONObject(0) ?: return emptyList()
        val content = first.optJSONObject("content") ?: return emptyList()
        val parts = content.optJSONArray("parts") ?: return emptyList()

        val calls = mutableListOf<Triple<String, JSONObject, String?>>()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            val fc = part.optJSONObject("functionCall") ?: continue
            val name = fc.safeOptString("name", "")
            val args = fc.optJSONObject("args") ?: JSONObject()
            val sig = part.safeOptString("thoughtSignature", "").ifEmpty { null }
            if (name.isNotEmpty()) calls.add(Triple(name, args, sig))
        }
        return calls
    }

    private fun extractFinishReason(json: JSONObject): String? {
        val candidates = json.optJSONArray("candidates") ?: return null
        val first = candidates.optJSONObject(0) ?: return null
        val reason = first.safeOptString("finishReason", "").ifEmpty { return null }
        return when (reason) {
            "STOP" -> "end_turn"
            "MAX_TOKENS" -> "max_tokens"
            else -> reason.lowercase()
        }
    }

    private fun extractUsage(json: JSONObject): LLMUsage? {
        val usage = json.optJSONObject("usageMetadata") ?: return null
        return parseUsageMetadata(usage)
    }

    companion object {
        const val NARRATED_RESULT_LIMIT = 2000

        internal fun narratedToolCall(name: String, input: JSONObject): String =
            "Earlier in this conversation, the $name tool was run with arguments ${canonicalJson(input)}."

        internal fun narratedToolResult(content: String, isError: Boolean = false): String {
            val lead = if (isError) "It failed" else "It returned"
            if (content.isEmpty()) return if (isError) "It failed with no output." else "It returned no output."
            if (content.length <= NARRATED_RESULT_LIMIT) return "$lead:\n$content"
            return "$lead (showing the first $NARRATED_RESULT_LIMIT of ${content.length} characters):\n" +
                content.take(NARRATED_RESULT_LIMIT)
        }

        internal fun canonicalJson(value: Any?): String = when (value) {
            is JSONObject -> value.keys().asSequence().sorted()
                .joinToString(",", "{", "}") { k -> JSONObject.quote(k) + ":" + canonicalJson(value.opt(k)) }
            is org.json.JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.opt(it)) }
            null, JSONObject.NULL -> "null"
            is String -> JSONObject.quote(value)
            is Number -> runCatching { JSONObject.numberToString(value) }.getOrDefault("null")
            is Boolean -> value.toString()
            else -> JSONObject.quote(value.toString())
        }

        fun parseUsageMetadata(usage: JSONObject): LLMUsage =
            com.openminis.app.usage.ProviderUsageParser.gemini(usage).toLLMUsage()
    }

    private fun mapHttpError(statusCode: Int, body: String): LLMError {
        if (statusCode == 401 || statusCode == 403) return LLMError.InvalidApiKey()
        if (statusCode == 429) return LLMError.RateLimited()
        val message = "Gemini API error $statusCode: ${body.take(200)}"
        val transientCodes = setOf(500, 502, 503, 504, 529)
        if (statusCode in transientCodes) return LLMError.TransientError(message, httpStatus = statusCode)
        return LLMError.ProviderError(message, httpStatus = statusCode)
    }

    private fun mapError(error: Throwable): LLMError {
        if (error is LLMError) return error
        com.openminis.app.provider.TlsFailure.toLLMError(error, basePath)?.let { return it }
        if (error is java.io.IOException) return LLMError.NetworkError(error)
        return LLMError.Unknown(error)
    }
}
