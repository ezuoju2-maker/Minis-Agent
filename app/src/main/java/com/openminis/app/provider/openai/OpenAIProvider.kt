package com.openminis.app.provider.openai

import android.util.Base64
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.WireToolIdMap
import com.openminis.app.data.model.isKimiNativeToolId
import com.openminis.app.data.model.logWireToolIds
import com.openminis.app.data.model.wireToolIdWithSuffix
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.provider.thinking.ThinkingResolveContext
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.provider.safeOptString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import com.openminis.app.provider.failOnSilentEmptyCompletion

class OpenAIProvider private constructor(
    private val apiKey: String?,
    private val oauthTokenProvider: (suspend () -> String)?,
    override var model: LLMModel = LLMModel.gpt4oMini,
    private val basePath: String = "https://api.openai.com/v1",
    private val extraHeaders: Map<String, String> = emptyMap(),
    var codexAccountId: String? = null,
    private val useResponsesAPI: Boolean = false,
    private val forceChatCompletions: Boolean = false,
    private val customUserAgent: String? = null,
    private val isAzure: Boolean = false,
    private val azureBase: String? = null,
) : LLMProvider, com.openminis.app.network.NetworkTagged {
    override val name = "OpenAI"

    var quotaInstanceId: String? = null

    var thinkingRuleInstanceId: String? = null

    var responseIdleTimeoutSec: Int = com.openminis.app.provider.ResponseIdleTimeoutSetting.DEFAULT_SECONDS

    var modelOverrides: com.openminis.app.data.model.ModelOverrides? = null

    var supportsPriorityProcessing: Boolean = false

    internal fun resolvedServiceTier(): String? =
        if (supportsPriorityProcessing && com.openminis.app.data.FastModePrefs.isEnabled()) {
            "priority"
        } else {
            null
        }

    constructor(
        apiKey: String,
        model: LLMModel = LLMModel.gpt4oMini,
        basePath: String = "https://api.openai.com/v1",
        extraHeaders: Map<String, String> = emptyMap(),
        useResponsesAPI: Boolean = false,
        customUserAgent: String? = null,
        isAzure: Boolean = false,
        azureBase: String? = null,
    ) : this(
        apiKey = apiKey,
        oauthTokenProvider = null,
        model = model,
        basePath = basePath,
        extraHeaders = extraHeaders,
        useResponsesAPI = useResponsesAPI,
        customUserAgent = customUserAgent,
        isAzure = isAzure,
        azureBase = azureBase,
    )

    constructor(
        oauthTokenProvider: suspend () -> String,
        model: LLMModel = LLMModel.codexMini,
        codexAccountId: String? = null,
    ) : this(apiKey = null, oauthTokenProvider = oauthTokenProvider, model = model, codexAccountId = codexAccountId)

    companion object {
        const val CHAT_COMPLETIONS_DEFAULT_MAX_TOKENS = 16_384
        const val RESPONSES_DEFAULT_MAX_TOKENS = 32_768

        internal fun defaultMaxOutputTokensFor(chatCompletions: Boolean): Int =
            if (chatCompletions) CHAT_COMPLETIONS_DEFAULT_MAX_TOKENS else RESPONSES_DEFAULT_MAX_TOKENS

        private val CODEX_IMAGE_25_MODEL_IDS = setOf(
            "gpt-image-2.5-sunburst",
            "gpt-image-2.5-flare",
        )

        private val CODEX_IMAGE_MODEL_IDS = setOf("gpt-image-2") + CODEX_IMAGE_25_MODEL_IDS

        internal const val CODEX_CLIENT_VERSION = "0.159.0"

        internal const val CODEX_ROUTING_HINT_HEADER = "x-codex-routing-hint"

        internal fun codexConversationHeaders(promptCacheKey: String?): Map<String, String> {
            val key = promptCacheKey?.trim().orEmpty()
            if (key.isEmpty()) return emptyMap()
            return linkedMapOf("Session_id" to key, "Conversation_id" to key)
        }

        internal fun codexRoutingHint(model: String?, serviceTier: String?): String? {
            val m = model?.trim().orEmpty()
            if (m.isEmpty()) return null
            val tier = serviceTier?.trim().orEmpty()
            return if (tier.isEmpty()) "model=$m" else "model=$m;tier=$tier"
        }

        private const val STREAM_TTFB_TIMEOUT_MS = 120_000L

        private const val STREAM_UPLOAD_CAP_MS = 120_000L

        fun oauthOpenAICompat(
            oauthTokenProvider: suspend () -> String,
            model: LLMModel,
            basePath: String,
            extraHeaders: Map<String, String> = emptyMap(),
        ): OpenAIProvider = OpenAIProvider(
            apiKey = null,
            oauthTokenProvider = oauthTokenProvider,
            model = model,
            basePath = basePath,
            forceChatCompletions = true,
            extraHeaders = extraHeaders,
        )
    }

    private val isOAuth: Boolean get() = oauthTokenProvider != null


    var imageExtraBody: Map<String, Any?> = emptyMap()

    var imageExtraHeaders: Map<String, String> = emptyMap()

    var imagePathOverride: String? = null


    var chatExtraBody: Map<String, Any?> = emptyMap()

    var chatExtraHeaders: Map<String, String> = emptyMap()


    var perRequestHeaders: ((org.json.JSONObject) -> Map<String, String>)? = null

    @Volatile
    var sessionId: String? = null

    var absoluteEndpointOverride: String? = null

    fun hostRootURL(path: String): String? {
        val base = basePath.toHttpUrlOrNull() ?: return null
        val qIdx = path.indexOf('?')
        val pathPart = if (qIdx >= 0) path.substring(0, qIdx) else path
        val queryPart = if (qIdx >= 0) path.substring(qIdx + 1) else null
        val builder = base.newBuilder()
            .encodedPath(pathPart)
            .fragment(null)
        builder.encodedQuery(queryPart)
        return builder.build().toString()
    }

    private fun endpointURL(defaultPath: String): String {
        val abs = absoluteEndpointOverride
        if (abs != null && abs.startsWith("/")) {
            hostRootURL(abs)?.let { return it }
        }
        return "$basePath$defaultPath"
    }


    private fun Request.Builder.applyKeyAuth(token: String): Request.Builder =
        if (token.isEmpty()) this
        else if (isAzure) header("api-key", token)
        else header("Authorization", "Bearer $token")

    private fun azureUrl(path: String): String? {
        val raw = azureBase?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val qIdx = raw.indexOf('?')
        val query = if (qIdx >= 0) raw.substring(qIdx) else ""
        var p = (if (qIdx >= 0) raw.substring(0, qIdx) else raw).trimEnd('/')
        if (p.endsWith("/v1")) p = p.dropLast(3).trimEnd('/')
        if (p.endsWith("/openai")) p = p.dropLast("/openai".length).trimEnd('/')
        val endpointPath = path.removePrefix("/")
        return "$p/openai/deployments/${model.id}/$endpointPath$query"
    }

    private val usesChatCompletionsAPI: Boolean get() = forceChatCompletions || (!isOAuth && !useResponsesAPI)

    override fun echoesReasoningContent(thinkingLevel: ThinkingLevel): Boolean {
        if (!usesChatCompletionsAPI) return false
        val level = clampThinkingLevel(thinkingLevel)
        val modelAlwaysReasons = model.supportsReasoning == true
        val modelMayReason = model.supportsReasoning ?: true
        return (level.isEnabled || modelAlwaysReasons) && modelMayReason && !(isMistral || isCerebras)
    }

    override val defaultMaxOutputTokens: Int
        get() = defaultMaxOutputTokensFor(usesChatCompletionsAPI)

    override val streamTextIsMonolithic: Boolean get() = usesChatCompletionsAPI

    private val isCodexImageModel: Boolean get() = isOAuth && model.id in CODEX_IMAGE_MODEL_IDS

    private suspend fun getToken(): String {
        oauthTokenProvider?.let { return it() }
        return apiKey ?: throw LLMError.InvalidApiKey()
    }

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .connectionPool(com.openminis.app.network.NetworkMonitor.sharedLLMConnectionPool)
        .eventListenerFactory { OkHttpNetTraceListener() }
        .build()

    override var netTag: String? = null

    private val client: OkHttpClient get() = com.openminis.app.network.NetworkHttp.client(netTag, baseClient)

    private val isOpenRouter: Boolean = basePath.contains("openrouter.ai")

    private val keepsKimiToolIds: Boolean
        get() = basePath.lowercase().contains("moonshot") || model.id.lowercase().contains("kimi")

    private val needsOpenRouterAnthropicCacheControl: Boolean
        get() = isOpenRouter && model.id.lowercase().startsWith("anthropic/")

    private val isDashScope: Boolean = basePath.contains("dashscope")

    private val isMistral: Boolean = basePath.lowercase().contains("mistral.ai")

    private val isCerebras: Boolean = basePath.lowercase().contains("cerebras.ai")

    private val isXAI: Boolean = basePath.lowercase().let {
        it.contains("api.x.ai") || it.contains("//x.ai")
    }

    private val usesUnifiedReasoningEffort: Boolean =
        isAzure || basePath.lowercase().let {
            it.contains("volces") || it.contains("ark.") || it.contains("api.venice.ai")
        }

    private fun explicitOffEffort(): String? {
        if (isAzure) return null
        val base = basePath.lowercase()
        if (base.startsWith("https://api.openai.com")) return "none"
        val lid = model.id.lowercase()
        if (base.contains("volces") || base.contains("ark.") ||
            lid.contains("seed-") || lid.contains("doubao")
        ) {
            return "minimal"
        }
        return null
    }

    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse = withContext(Dispatchers.IO) {
        val textBuf = StringBuilder()
        var stopReason: String? = null
        var usage: LLMUsage? = null
        val media = mutableListOf<LLMMediaAttachment>()
        streamMessage(
            messages = messages,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
            temperature = temperature,
            imageParts = imageParts,
            tools = tools,
            thinkingLevel = thinkingLevel,
        ).collect { chunk ->
            when (chunk) {
                is LLMStreamChunk.Text -> textBuf.append(chunk.text)
                is LLMStreamChunk.Usage -> usage = chunk.usage
                is LLMStreamChunk.Finished -> stopReason = chunk.stopReason
                is LLMStreamChunk.MediaAttachment -> media.add(chunk.attachment)
                else -> Unit
            }
        }
        LLMResponse(textBuf.toString(), stopReason, usage, media)
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

    private fun rawStreamMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = callbackFlow {
        val body = if (isCodexImageModel) {
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[ModelUseRoute] gpt-image-2 → route=codex-backend " +
                    "url=chatgpt.com/backend-api/codex/responses isOAuth=$isOAuth " +
                    "hasAccountId=${codexAccountId != null}",
            )
            buildCodexImageBody(messages)
        } else if (usesChatCompletionsAPI) {
            buildRequestBody(messages, systemPrompt, maxTokens, stream = true, temperature = temperature, imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel)
        } else {
            buildResponsesAPIBody(messages, systemPrompt, maxTokens, stream = true, imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel)
        }
        val requestedServiceTier = body.optString("service_tier", "").takeIf { it.isNotEmpty() }
        val memBefore = com.openminis.app.diagnostics.MemorySnapshot.capture()
        val serStartNs = System.nanoTime()
        val bodyStr = try {
            body.toString()
        } catch (t: Throwable) {
            com.openminis.app.diagnostics.LargeAllocProbe.report(
                "openai.body.toString", -1, "model=${model.id} messages=${messages.size}",
                memBefore, serStartNs, failure = t,
            )
            throw t
        }
        if (bodyStr.length >= com.openminis.app.diagnostics.LargeAllocProbe.NOTABLE_BYTES) {
            com.openminis.app.diagnostics.LargeAllocProbe.report(
                "openai.body.toString", bodyStr.length.toLong(),
                "model=${model.id} messages=${messages.size}",
                memBefore, serStartNs, failure = null,
            )
        }
        val request = buildRequest(
            bodyStr,
            codexRoutingHint(body.optString("model"), body.optString("service_tier")),
            promptCacheKey = body.optString("prompt_cache_key"),
        )
        val headerMap = mutableMapOf<String, String>()
        for (name in request.headers.names()) {
            headerMap[name] = request.headers[name] ?: ""
        }
        val startTime = System.currentTimeMillis()

        run {
            val authPresent = request.headers["Authorization"] != null
            val msgsLen = body.optJSONArray("messages")?.length()
                ?: body.optJSONArray("input")?.length() ?: 0
            val toolsLen = body.optJSONArray("tools")?.length() ?: 0
            val temp = if (body.has("temperature")) body.optDouble("temperature") else null
            val maxTok = body.optInt("max_completion_tokens", body.optInt("max_tokens", -1))
            val hasSystem = body.has("instructions") ||
                (body.optJSONArray("messages")?.let { arr ->
                    var found = false
                    for (i in 0 until arr.length()) {
                        if (arr.optJSONObject(i)?.optString("role") == "system") { found = true; break }
                    }
                    found
                } ?: false)
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[T321] → REQ url=${request.url} model=${model.id} stream=${body.optBoolean("stream", false)} " +
                    "headerKeys=${request.headers.names()} authPresent=$authPresent " +
                    "messages=$msgsLen tools=$toolsLen temp=$temp maxTokens=$maxTok hasSystem=$hasSystem " +
                    "useResponsesAPI=${!usesChatCompletionsAPI} bodyLen=${bodyStr.length}"
            )
        }

        val watchState = CallWatchState()
        val ttfbBudgetMs = if (usesChatCompletionsAPI) responseIdleTimeoutSec * 1000L else STREAM_TTFB_TIMEOUT_MS
        val raisedReadSec = if (usesChatCompletionsAPI) {
            com.openminis.app.provider.ResponseIdleTimeoutSetting.readTimeoutSeconds(responseIdleTimeoutSec)
        } else null
        val callClient = if (raisedReadSec != null) {
            client.newBuilder().readTimeout(raisedReadSec.toLong(), TimeUnit.SECONDS).build()
        } else client
        val call = callClient.newCall(request.newBuilder().tag(CallWatchState::class.java, watchState).build())
        val ttfbTimedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val headersArrived = java.util.concurrent.atomic.AtomicBoolean(false)
        val callStartNanos = System.nanoTime()
        val ttfbWatchdog = launch {
            val pollMs = 250L
            var timedOutPhase: String? = null
            while (!headersArrived.get()) {
                val uploadDoneAt = watchState.uploadDoneAtNanos.get()
                val nowNanos = System.nanoTime()
                if (uploadDoneAt == 0L) {
                    val elapsedMs = (nowNanos - callStartNanos) / 1_000_000L
                    if (elapsedMs >= STREAM_UPLOAD_CAP_MS) { timedOutPhase = "upload"; break }
                } else {
                    val sinceUploadMs = (nowNanos - uploadDoneAt) / 1_000_000L
                    if (sinceUploadMs >= ttfbBudgetMs) { timedOutPhase = "ttfb"; break }
                }
                delay(pollMs)
            }
            if (timedOutPhase != null && !headersArrived.get()) {
                ttfbTimedOut.set(true)
                val budgetS = if (timedOutPhase == "ttfb") ttfbBudgetMs / 1000 else STREAM_UPLOAD_CAP_MS / 1000
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[T-android-ttfb-upload-split] no response headers ($timedOutPhase phase, ${budgetS}s) — cancelling call + evicting connection (stale pooled connection?)",
                )
                call.cancel()
                try {
                    watchState.connection.get()?.socket()?.close()
                } catch (_: Throwable) {
                }
            }
        }
        val response = try {
            call.execute()
        } catch (e: IOException) {
            if (ttfbTimedOut.get()) {
                throw LLMError.TransientError(
                    "no response from server (${ttfbBudgetMs / 1000}s TTFB) — check network/proxy",
                )
            }
            throw e
        } finally {
            headersArrived.set(true)
            ttfbWatchdog.cancel()
        }
        if (isOAuth && !forceChatCompletions) {
            com.openminis.app.quota.QuotaObservationSink.recordCodexResponse(quotaInstanceId, response.headers)
        }
        run {
            val rh = response.headers
            val ct = rh["content-type"] ?: ""
            val rid = rh["x-request-id"] ?: rh["openai-request-id"] ?: ""
            val openAiHdrs = rh.names().filter { it.lowercase().startsWith("openai-") }
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[T321] ← RSP status=${response.code} content-type=$ct x-request-id=$rid " +
                    "headerKeys=${rh.names()} openAiHeaders=${openAiHdrs.associateWith { rh[it] ?: "" }}"
            )
        }
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            com.openminis.app.logging.AppLogger.error(
                "OpenAIProvider",
                "[T321] ← HTTP ${response.code} error body: $errorBody"
            )
            response.close()
            if (com.openminis.app.BuildConfig.DEBUG) {
                com.openminis.app.debug.LLMRequestLog.add(
                    com.openminis.app.debug.LLMRequestLog.Entry(
                        provider = "openai",
                        requestURL = request.url.toString(),
                        requestHeaders = headerMap,
                        requestBody = bodyStr,
                        durationMs = System.currentTimeMillis() - startTime,
                        responseStatusCode = response.code,
                        responseBody = errorBody.take(2000),
                    )
                )
            }
            throw mapHttpError(response.code, errorBody)
        }
        if (com.openminis.app.BuildConfig.DEBUG) {
            com.openminis.app.debug.LLMRequestLog.add(
                com.openminis.app.debug.LLMRequestLog.Entry(
                    provider = "openai",
                    requestURL = request.url.toString(),
                    requestHeaders = headerMap,
                    requestBody = bodyStr,
                    durationMs = System.currentTimeMillis() - startTime,
                    responseStatusCode = response.code,
                )
            )
        }

        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))

        if (isCodexImageModel) {
            try {
                handleCodexImageStream(reader) { chunk -> trySend(chunk) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cancel("Image stream error", mapError(e))
            } finally {
                reader.close()
                response.close()
            }
            channel.close()
            awaitClose {
                try { call.cancel() } catch (_: Exception) {}
                try { response.close() } catch (_: Exception) {}
            }
            return@callbackFlow
        }

        data class ToolCallAccumulator(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), var started: Boolean = false)
        val toolCallAccumulators = mutableMapOf<Int, ToolCallAccumulator>()
        data class ResponsesToolCallAccumulator(var callId: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), var started: Boolean = false)
        val responsesToolCalls = mutableMapOf<String, ResponsesToolCallAccumulator>()
        var sawReasoningDelta = false
        val reasoningAccum = StringBuilder()
        var sawReasoningField = false
        val thinkParser = ThinkPrefixStreamParser()

        var sseEventCount = 0
        var contentLen = 0
        var sawNonBlankText = false
        var reasoningLen = 0
        var encryptedReasoningChunks = 0
        var toolCallEventCount = 0
        var sawFinishReason = false
        var sawUsageBlock = false
        var finishReason: String? = null
        var sentFinished = false

        try {
            send(LLMStreamChunk.Started)
            var line: String?

            val isResponsesAPI = !usesChatCompletionsAPI

            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data:")) continue
                val payload = l.removePrefix("data:").let {
                    if (it.startsWith(" ")) it.removePrefix(" ") else it
                }
                if (payload == "[DONE]") {
                    thinkParser.finishTurn().let { fin ->
                        if (fin.thinking.isNotEmpty()) {
                            reasoningAccum.append(fin.thinking)
                            send(LLMStreamChunk.ThinkingDelta(fin.thinking))
                        }
                        if (fin.visible.isNotEmpty()) send(LLMStreamChunk.Text(fin.visible))
                    }
                    if (sawReasoningField || reasoningAccum.isNotEmpty()) {
                        send(LLMStreamChunk.ReasoningContent(reasoningAccum.toString()))
                    }
                    send(LLMStreamChunk.Finished(finishReason))
                    sentFinished = true
                    break
                }

                val event = try { JSONObject(payload) } catch (e: Exception) {
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "[T321] SSE JSON parse failed: ${e.message} payload=${payload.take(300)}"
                    )
                    continue
                }
                if (com.openminis.app.BuildConfig.DEBUG) {
                    android.util.Log.d("ToolChain[Provider]", "RAW SSE: $payload")
                }
                sseEventCount++

                run {
                    val ev = event
                    val delta = ev.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                    val type = ev.optString("type", "")
                    if (delta != null) {
                        val cLen = delta.optString("content", "").length
                        val rcLen = delta.optString("reasoning_content", "").length
                        val rLen = delta.optString("reasoning", "").length
                            .coerceAtLeast(delta.optString("reasoning_text", "").length)
                        val tcLen = delta.optJSONArray("tool_calls")?.length() ?: 0
                        val role = delta.optString("role", "")
                        if (cLen + rcLen + rLen + tcLen > 0 || delta.has("role")) {
                            com.openminis.app.logging.AppLogger.trace("OpenAIProvider") {
                                "[T321] SSE delta: contentLen=$cLen rcLen=$rcLen rLen=$rLen toolCalls=$tcLen role='$role'"
                            }
                        }
                        contentLen += cLen
                        reasoningLen += rcLen + rLen
                        if (tcLen > 0) toolCallEventCount += tcLen
                    } else if (type.isNotEmpty()) {
                        val dLen = ev.optString("delta", "").length
                        if (type.contains("delta") || type == "response.completed" || type == "response.output_item.added" || type == "response.output_item.done") {
                            com.openminis.app.logging.AppLogger.trace("OpenAIProvider") {
                                "[T321] SSE responses type=$type deltaLen=$dLen"
                            }
                        }
                        if (type == "response.output_text.delta") {
                            contentLen += dLen
                            if (!sawNonBlankText && ev.optString("delta", "").isNotBlank()) {
                                sawNonBlankText = true
                            }
                        }
                        if (type.startsWith("response.reasoning_")) reasoningLen += dLen
                    }
                }

                if (isResponsesAPI) {
                    val type = event.optString("type", "")
                    when {
                        type == "response.reasoning_text.delta" ||
                            type == "response.reasoning_summary_text.delta" -> {
                            val delta = event.optString("delta", "")
                            if (delta.isNotEmpty()) {
                                if (!sawReasoningDelta) {
                                    com.openminis.app.logging.AppLogger.info(
                                        "OpenAIProvider",
                                        "Responses API: first reasoning delta arrived (type=$type) — streaming Thinking content"
                                    )
                                    sawReasoningDelta = true
                                }
                                send(LLMStreamChunk.ThinkingDelta(delta))
                            }
                        }
                        type == "response.output_text.delta" -> {
                            val delta = event.optString("delta", "")
                            if (delta.isNotEmpty()) send(LLMStreamChunk.Text(delta))
                        }
                        type == "response.output_item.added" -> {
                            val item = event.optJSONObject("item") ?: continue
                            val itemType = item.optString("type", "")
                            if (itemType == "function_call") {
                                val itemId = item.optString("id", "")
                                val callId = item.optString("call_id", "")
                                val name = item.optString("name", "")
                                if (itemId.isNotEmpty() && callId.isNotEmpty() && name.isNotEmpty()) {
                                    responsesToolCalls[itemId] = ResponsesToolCallAccumulator(callId = callId, name = name)
                                    val combined = combineResponsesAPIIds(callId, itemId)
                                    android.util.Log.d("ToolChain[Provider]", "→ ToolUseStart (Responses) id=$combined name=$name")
                                    send(LLMStreamChunk.ToolUseStart(combined, name))
                                    responsesToolCalls[itemId]?.started = true
                                }
                            }
                        }
                        type == "response.function_call_arguments.delta" -> {
                            val itemId = event.optString("item_id", "")
                            val delta = event.optString("delta", "")
                            val acc = responsesToolCalls[itemId]
                            if (acc != null && delta.isNotEmpty()) {
                                acc.args.append(delta)
                                val combined = combineResponsesAPIIds(acc.callId, itemId)
                                send(LLMStreamChunk.ToolInputDelta(combined, acc.args.toString()))
                            } else if (acc == null) {
                                com.openminis.app.logging.AppLogger.warning(
                                    "OpenAIProvider",
                                    "Responses API: function_call_arguments.delta for unknown item_id=$itemId — dropping"
                                )
                            }
                        }
                        type == "response.output_item.done" -> {
                            val item = event.optJSONObject("item") ?: continue
                            val itemType = item.optString("type", "")
                            if (itemType == "function_call") {
                                val itemId = item.optString("id", "")
                                val acc = responsesToolCalls.remove(itemId) ?: continue
                                val argsStr = item.optString("arguments", acc.args.toString())
                                val args = try { JSONObject(argsStr) } catch (_: Exception) { JSONObject() }
                                val combined = combineResponsesAPIIds(acc.callId, itemId)
                                android.util.Log.d("ToolChain[Provider]", "→ ToolCallComplete (Responses) id=$combined name=${acc.name} args=${args.toString().take(300)}")
                                send(LLMStreamChunk.ToolCallComplete(combined, acc.name, args))
                            }
                        }
                        type == "response.failed" -> {
                            val resp = event.optJSONObject("response")
                            val err = resp?.optJSONObject("error")
                            val code = err?.optString("code")?.takeIf { it.isNotEmpty() } ?: "unknown"
                            val message = err?.optString("message")?.takeIf { it.isNotEmpty() }
                                ?: "response.failed with no error detail"
                            com.openminis.app.logging.AppLogger.error(
                                "OpenAIProvider",
                                "Responses API response.failed — code=$code message=$message"
                            )
                            if (code == "server_error" || code == "rate_limit_exceeded") {
                                throw LLMError.TransientError("[$code] $message")
                            }
                            val status = if (code == "context_length_exceeded") 400 else null
                            throw LLMError.ProviderError("[$code] $message", httpStatus = status)
                        }
                        type == "response.incomplete" -> {
                            val reason = event.optJSONObject("response")
                                ?.optJSONObject("incomplete_details")
                                ?.optString("reason")?.takeIf { it.isNotEmpty() }
                                ?: "unknown"

                            if (reason == com.openminis.app.provider.StopReasons.CONTENT_FILTER) {
                                com.openminis.app.logging.AppLogger.warning(
                                    "OpenAIProvider",
                                    "[OpenAIStream] response.incomplete reason=content_filter — " +
                                        "OpenAI safety system blocked this response (contentLen=$contentLen)"
                                )
                                finishReason = com.openminis.app.provider.StopReasons.CONTENT_FILTER
                                sawFinishReason = true
                                break
                            }

                            val hasUsableOutput = sawNonBlankText
                            if (hasUsableOutput) {
                                com.openminis.app.logging.AppLogger.warning(
                                    "OpenAIProvider",
                                    "Responses API response.incomplete — reason=$reason; " +
                                        "keeping ${contentLen}ch of partial output (finish_reason=length)"
                                )
                                finishReason = "length"
                                sawFinishReason = true
                                break
                            }

                            com.openminis.app.logging.AppLogger.error(
                                "OpenAIProvider",
                                "Responses API response.incomplete — reason=$reason (no usable output)"
                            )
                            throw LLMError.ProviderError(
                                "Response ended incomplete (reason: $reason)" +
                                    if (reason == "max_output_tokens") {
                                        " — the model used its entire output budget before producing a reply" +
                                            " (often the thinking phase on a reasoning model). Try turning off or" +
                                            " lowering Deep Thinking, shortening the request, or raising the model's" +
                                            " Max Output Tokens."
                                    } else ""
                            )
                        }
                        type == "response.completed" -> {
                            val resp = event.optJSONObject("response")
                            val status = resp?.optString("status", "")
                            val sawToolCalls = responsesToolCalls.isNotEmpty() ||
                                (resp?.optJSONArray("output")?.let { out ->
                                    var found = false
                                    for (i in 0 until out.length()) {
                                        if (out.optJSONObject(i)?.optString("type") == "function_call") { found = true; break }
                                    }
                                    found
                                } ?: false)
                            finishReason = when {
                                sawToolCalls -> "tool_use"
                                status == "completed" -> "stop"
                                else -> status
                            }
                            if (!sawFinishReason) {
                                sawFinishReason = true
                                val echoedTier = resp?.optString("service_tier", "")?.takeIf { it.isNotEmpty() }
                                val serviceTier = echoedTier ?: "n/a"
                                com.openminis.app.logging.AppLogger.info(
                                    "OpenAIProvider",
                                    "[T321] Responses finish_reason=$finishReason status=$status service_tier=$serviceTier requested_tier=${requestedServiceTier ?: "none"} contentLen=$contentLen reasoningLen=$reasoningLen toolCallAccumulators=${responsesToolCalls.size}"
                                )
                                if (requestedServiceTier != null && echoedTier != requestedServiceTier) {
                                    com.openminis.app.logging.AppLogger.info(
                                        "OpenAIProvider",
                                        "[SpeedTier] requested service_tier=$requestedServiceTier, server echoed service_tier=${echoedTier ?: "n/a"} (not verifiable for ChatGPT auth) model=${model.id}",
                                    )
                                }
                            }
                            resp?.optJSONObject("usage")?.let { usage ->
                                sawUsageBlock = true
                                com.openminis.app.logging.AppLogger.info(
                                    "OpenAIProvider",
                                    "[T321] Responses usage block: $usage"
                                )
                                send(LLMStreamChunk.Usage(parseResponsesAPIUsage(usage)))
                            }
                        }
                        type == "response.output_text.done" -> {
                        }
                    }
                } else {
                    val inlineError = event.optJSONObject("error")
                    if (inlineError != null) {
                        val code = inlineError.optInt("code", 0)
                        val msg = inlineError.optString("message", "Unknown SSE error")
                        val err = mapHttpError(code, event.toString())
                        throw err
                    }
                    val choices = event.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val choice = choices.getJSONObject(0)
                        val delta = choice.optJSONObject("delta")

                        delta?.let { d ->
                            val hasRcKey = d.has("reasoning_content")
                            val hasReasoningKey = d.has("reasoning")
                            val hasReasoningTextKey = d.has("reasoning_text")
                            if (hasRcKey || hasReasoningKey || hasReasoningTextKey) {
                                sawReasoningField = true
                            }
                            val rc = d.safeOptString("reasoning_content", "")
                                .ifEmpty { d.safeOptString("reasoning", "") }
                                .ifEmpty { d.safeOptString("reasoning_text", "") }
                            if (rc.isNotEmpty()) {
                                reasoningAccum.append(rc)
                                if (!sawReasoningDelta) {
                                    sawReasoningDelta = true
                                    com.openminis.app.logging.AppLogger.info(
                                        "OpenAIProvider",
                                        "Chat Completions: first reasoning_content delta arrived on ${model.id} — streaming Thinking content"
                                    )
                                }
                                send(LLMStreamChunk.ThinkingDelta(rc))
                            }
                            ReasoningDetails.parse(d)?.let { rd ->
                                sawReasoningField = true
                                encryptedReasoningChunks += rd.encryptedCount
                                if (rd.text.isNotEmpty()) {
                                    reasoningAccum.append(rd.text)
                                    reasoningLen += rd.text.length
                                    if (!sawReasoningDelta) {
                                        sawReasoningDelta = true
                                        com.openminis.app.logging.AppLogger.info(
                                            "OpenAIProvider",
                                            "Chat Completions: first reasoning_details delta arrived on ${model.id} — streaming Thinking content"
                                        )
                                    }
                                    send(LLMStreamChunk.ThinkingDelta(rd.text))
                                }
                            }
                        }

                        delta?.safeOptString("content", "")?.let { text ->
                            if (text.isNotEmpty()) {
                                val out = thinkParser.feed(text)
                                if (out.thinking.isNotEmpty()) {
                                    reasoningAccum.append(out.thinking)
                                    send(LLMStreamChunk.ThinkingDelta(out.thinking))
                                }
                                if (out.visible.isNotEmpty()) send(LLMStreamChunk.Text(out.visible))
                            }
                        }

                        val toolCalls = delta?.optJSONArray("tool_calls")
                        if (toolCalls != null) {
                            for (i in 0 until toolCalls.length()) {
                                val tc = toolCalls.getJSONObject(i)
                                val idx = tc.optInt("index", 0)
                                val acc = toolCallAccumulators.getOrPut(idx) { ToolCallAccumulator() }

                                tc.safeOptString("id", "").let { if (it.isNotEmpty()) acc.id = it }
                                tc.optJSONObject("function")?.let { fn ->
                                    fn.safeOptString("name", "").let { if (it.isNotEmpty()) acc.name = it }
                                    fn.safeOptString("arguments", "").let { if (it.isNotEmpty()) acc.args.append(it) }
                                }

                                if (!acc.started && acc.id.isNotEmpty() && acc.name.isNotEmpty()) {
                                    acc.started = true
                                    android.util.Log.d("ToolChain[Provider]", "→ ToolUseStart id=${acc.id} name=${acc.name}")
                                    send(LLMStreamChunk.ToolUseStart(acc.id, acc.name))
                                }
                                if (acc.id.isNotEmpty() && acc.args.isNotEmpty()) {
                                    if (com.openminis.app.logging.AppLogger.traceEnabled) {
                                        android.util.Log.d("ToolChain[Provider]", "→ ToolInputDelta id=${acc.id} accumulated=${acc.args.length}chars")
                                    }
                                    send(LLMStreamChunk.ToolInputDelta(acc.id, acc.args.toString()))
                                }
                            }
                        }

                        choice.safeOptString("finish_reason", "").let {
                            if (it.isNotEmpty()) {
                                finishReason = it
                                if (it == com.openminis.app.provider.StopReasons.CONTENT_FILTER && !sawFinishReason) {
                                    com.openminis.app.logging.AppLogger.warning(
                                        "OpenAIProvider",
                                        "[OpenAIStream] finish_reason=content_filter — OpenAI safety system blocked this response",
                                    )
                                }
                                if (!sawFinishReason) {
                                    sawFinishReason = true
                                    com.openminis.app.logging.AppLogger.info(
                                        "OpenAIProvider",
                                        "[T321] finish_reason=$it contentLen=$contentLen reasoningLen=$reasoningLen encryptedReasoningChunks=$encryptedReasoningChunks toolCallEvents=$toolCallEventCount accumulators=${toolCallAccumulators.size}"
                                    )
                                }
                            }
                        }
                    }

                    event.optJSONObject("usage")?.let { usage ->
                        sawUsageBlock = true
                        com.openminis.app.logging.AppLogger.info(
                            "OpenAIProvider",
                            "[T321] usage block: $usage"
                        )
                        send(LLMStreamChunk.Usage(parseChatCompletionsUsage(usage)))
                    }
                }
            }

            thinkParser.finishTurn().let { fin ->
                if (fin.thinking.isNotEmpty()) {
                    reasoningAccum.append(fin.thinking)
                    send(LLMStreamChunk.ThinkingDelta(fin.thinking))
                }
                if (fin.visible.isNotEmpty()) send(LLMStreamChunk.Text(fin.visible))
            }

            for ((_, acc) in toolCallAccumulators) {
                if (acc.id.isNotEmpty() && acc.name.isNotEmpty()) {
                    val args = try { JSONObject(acc.args.toString()) } catch (_: Exception) { JSONObject() }
                    android.util.Log.d("ToolChain[Provider]", "→ ToolCallComplete id=${acc.id} name=${acc.name} args=${args.toString().take(300)}")
                    send(LLMStreamChunk.ToolCallComplete(acc.id, acc.name, args))
                }
            }
            for ((itemId, acc) in responsesToolCalls) {
                if (acc.callId.isNotEmpty() && acc.name.isNotEmpty()) {
                    val args = try { JSONObject(acc.args.toString()) } catch (_: Exception) { JSONObject() }
                    val combined = combineResponsesAPIIds(acc.callId, itemId)
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "Stream ended mid-tool-call id=$combined name=${acc.name} argsLen=${acc.args.length} — flushing as ToolCallComplete (T248)",
                    )
                    send(LLMStreamChunk.ToolCallComplete(combined, acc.name, args))
                }
            }
            responsesToolCalls.clear()

            if (sawFinishReason && !sentFinished) {
                thinkParser.finishTurn().let { fin ->
                    if (fin.thinking.isNotEmpty()) {
                        reasoningAccum.append(fin.thinking)
                        send(LLMStreamChunk.ThinkingDelta(fin.thinking))
                    }
                    if (fin.visible.isNotEmpty()) send(LLMStreamChunk.Text(fin.visible))
                }
                if (sawReasoningField || reasoningAccum.isNotEmpty()) {
                    send(LLMStreamChunk.ReasoningContent(reasoningAccum.toString()))
                }
                send(LLMStreamChunk.Finished(finishReason))
                sentFinished = true
                com.openminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[T321] stream ended without [DONE] — emitted Finished(finishReason=$finishReason) from tail"
                )
            }

            if (!sawFinishReason) {
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[T321] stream ended WITHOUT finish_reason: events=$sseEventCount " +
                        "contentLen=$contentLen reasoningLen=$reasoningLen " +
                        "encryptedReasoningChunks=$encryptedReasoningChunks " +
                        "toolCallEvents=$toolCallEventCount sawUsage=$sawUsageBlock model=${model.id}"
                )
                if (sseEventCount == 0 && contentLen == 0 && reasoningLen == 0 &&
                    toolCallEventCount == 0 && !sawUsageBlock
                ) {
                    throw LLMError.TransientError(
                        "stream closed without producing any data (model=${model.id})"
                    )
                }
            } else {
                com.openminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[T321] stream complete: events=$sseEventCount contentLen=$contentLen " +
                        "reasoningLen=$reasoningLen toolCallEvents=$toolCallEventCount sawUsage=$sawUsageBlock"
                )
            }
        } catch (e: Exception) {
            val frames = e.stackTrace.take(3).joinToString(" | ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
            com.openminis.app.logging.AppLogger.error(
                "OpenAIProvider",
                "[T321] stream parse exception: ${e.javaClass.simpleName}: ${e.message} @ $frames " +
                    "(events=$sseEventCount contentLen=$contentLen reasoningLen=$reasoningLen)"
            )
            cancel("Stream error", mapError(e))
        } finally {
            reader.close()
            response.close()
        }
        channel.close()
        awaitClose {
            try { call.cancel() } catch (_: Exception) {}
            try { response.close() } catch (_: Exception) {}
        }
    }


    class RawPassthroughResult(
        val data: ByteArray,
        val status: Int,
        val contentType: String?,
        val url: String,
    )

    suspend fun rawPassthroughRequest(
        endpoint: String?,
        method: String,
        headers: Map<String, String>,
        bodyObject: JSONObject?,
    ): RawPassthroughResult = withContext(Dispatchers.IO) {
        val url: String = when {
            endpoint != null && endpoint.startsWith("/") ->
                hostRootURL(endpoint)
                    ?: throw LLMError.ProviderError("Invalid passthrough endpoint: $endpoint")
            endpoint != null -> "$basePath/${endpoint.trimStart('/')}"
            else -> endpointURL("/chat/completions")
        }

        val verb = method.uppercase()
        val builder = Request.Builder().url(url)
        if (verb != "GET" && bodyObject != null) {
            val jsonMediaType = "application/json".toMediaType()
            val bodyBytes = bodyObject.toString().toByteArray(Charsets.UTF_8)
            val requestBody = object : okhttp3.RequestBody() {
                override fun contentType() = jsonMediaType
                override fun contentLength() = bodyBytes.size.toLong()
                override fun writeTo(sink: okio.BufferedSink) { sink.write(bodyBytes) }
            }
            builder.method(verb, requestBody)
        } else {
            builder.method(verb, null)
        }
        val token = getToken()
        builder.applyKeyAuth(token)
        builder.header("Content-Type", "application/json")
        builder.applyProviderHeaders(url, bodyObject?.toString())
        builder.applyProviderUserAgent()
        for ((k, v) in headers) builder.header(k, v)

        com.openminis.app.logging.AppLogger.info(
            "OpenAIProvider",
            "[ModelUseRoute] route=raw-passthrough method=$verb url=$url " +
                "bodyKeys=[${bodyObject?.keys()?.asSequence()?.sorted()?.joinToString(",") ?: ""}] " +
                "headerOverrides=[${headers.keys.sorted().joinToString(",")}]",
        )

        val response = client.newCall(builder.build()).execute()
        response.use { resp ->
            RawPassthroughResult(
                data = resp.body?.bytes() ?: ByteArray(0),
                status = resp.code,
                contentType = resp.header("Content-Type"),
                url = url,
            )
        }
    }

    suspend fun generateImage(
        prompt: String,
        n: Int = 1,
        size: String? = null,
        quality: String? = null,
    ): LLMResponse = withContext(Dispatchers.IO) {
        val token = getToken()
        val imagePath = imagePathOverride?.takeIf { it.isNotBlank() } ?: "/images/generations"
        val abs = absoluteEndpointOverride
        val url = when {
            abs != null && abs.startsWith("/") -> hostRootURL(abs) ?: "$basePath$imagePath"
            isAzure -> azureUrl(imagePath) ?: "$basePath$imagePath"
            else -> "$basePath$imagePath"
        }

        val userSetResponseFormat = imageExtraBody.containsKey("response_format")
        var triedWithoutFormat = userSetResponseFormat
        while (true) {
            val body = JSONObject()
                .put("model", model.id)
                .put("prompt", prompt)
                .put("n", n)
            if (size != null) body.put("size", size)
            if (quality != null) body.put("quality", quality)
            if (!triedWithoutFormat) body.put("response_format", "b64_json")
            for ((k, v) in imageExtraBody) body.put(k, v ?: JSONObject.NULL)
            body.put("model", model.id)

            val bodyStr = body.toString()
            val jsonMediaType = "application/json".toMediaType()
            val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
            val requestBody = object : okhttp3.RequestBody() {
                override fun contentType() = jsonMediaType
                override fun contentLength() = bodyBytes.size.toLong()
                override fun writeTo(sink: okio.BufferedSink) { sink.write(bodyBytes) }
            }
            val builder = Request.Builder()
                .url(url)
                .post(requestBody)
                .applyKeyAuth(token)
                .header("Content-Type", "application/json")
            builder.applyProviderHeaders(url, bodyStr)
            for ((key, value) in imageExtraHeaders) {
                builder.header(key, value)
            }
            builder.applyProviderUserAgent()
            val request = builder.build()

            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[ModelUseRoute] → images/generations url=$url model=${model.id} n=$n " +
                    "size=$size quality=$quality respFormat=${if (triedWithoutFormat) "<none>" else "b64_json"}",
            )

            val response = client.newCall(request).execute()
            val statusCode = response.code
            val responseBody = response.body?.string() ?: ""
            response.close()

            if (!triedWithoutFormat && statusCode == 400 &&
                (responseBody.lowercase().contains("response_format") || responseBody.contains("b64_json"))
            ) {
                com.openminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[ModelUseRoute] images/generations rejected b64_json — retrying without response_format",
                )
                triedWithoutFormat = true
                continue
            }

            if (statusCode !in 200..299) {
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[ModelUseRoute] images/generations HTTP $statusCode body=${responseBody.take(300)}",
                )
                throw mapHttpError(statusCode, responseBody)
            }

            val json = try {
                JSONObject(responseBody)
            } catch (e: Exception) {
                throw LLMError.ProviderError("images/generations returned non-JSON body: ${e.message}")
            }
            return@withContext parseImageGenerationsResult(json)
        }
        @Suppress("UNREACHABLE_CODE")
        throw LLMError.ProviderError("images/generations: unreachable")
    }

    suspend fun editImage(
        prompt: String,
        images: List<LLMMessage.ImagePart>,
        n: Int = 1,
        size: String? = null,
        quality: String? = null,
    ): LLMResponse = withContext(Dispatchers.IO) {
        if (images.isEmpty()) {
            throw LLMError.ProviderError("images/edits requires at least one input image")
        }
        val token = getToken()
        val imagePath = imagePathOverride?.takeIf { it.isNotBlank() } ?: "/images/edits"
        val abs = absoluteEndpointOverride
        val url = when {
            abs != null && abs.startsWith("/") -> hostRootURL(abs) ?: "$basePath$imagePath"
            isAzure -> azureUrl(imagePath) ?: "$basePath$imagePath"
            else -> "$basePath$imagePath"
        }

        val userSetResponseFormat = imageExtraBody.containsKey("response_format")
        var triedWithoutFormat = userSetResponseFormat
        while (true) {
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            multipart.addFormDataPart("model", model.id)
            multipart.addFormDataPart("prompt", prompt)
            multipart.addFormDataPart("n", n.toString())
            if (size != null) multipart.addFormDataPart("size", size)
            if (quality != null) multipart.addFormDataPart("quality", quality)
            if (!triedWithoutFormat) multipart.addFormDataPart("response_format", "b64_json")
            for ((k, v) in imageExtraBody) {
                if (k == "model") continue
                multipart.addFormDataPart(k, v?.toString() ?: "")
            }

            for ((idx, img) in images.withIndex()) {
                val ext = img.mimeType.substringAfterLast('/', "").ifEmpty { "png" }
                val fieldName = if (idx == 0) "image" else "image[]"
                multipart.addFormDataPart(
                    fieldName,
                    "image$idx.$ext",
                    img.data.toRequestBody(img.mimeType.toMediaType()),
                )
            }

            val builder = Request.Builder()
                .url(url)
                .post(multipart.build())
                .applyKeyAuth(token)
            builder.applyProviderHeaders(url, null)
            for ((key, value) in imageExtraHeaders) {
                builder.header(key, value)
            }
            builder.applyProviderUserAgent()
            val request = builder.build()

            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[ModelUseRoute] → images/edits url=$url model=${model.id} n=$n " +
                    "size=$size quality=$quality images=${images.size} " +
                    "respFormat=${if (triedWithoutFormat) "<none>" else "b64_json"}",
            )

            val response = client.newCall(request).execute()
            val statusCode = response.code
            val responseBody = response.body?.string() ?: ""
            response.close()

            if (!triedWithoutFormat && statusCode == 400 &&
                (responseBody.lowercase().contains("response_format") || responseBody.contains("b64_json"))
            ) {
                com.openminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[ModelUseRoute] images/edits rejected b64_json — retrying without response_format",
                )
                triedWithoutFormat = true
                continue
            }

            if (statusCode !in 200..299) {
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[ModelUseRoute] images/edits HTTP $statusCode body=${responseBody.take(300)}",
                )
                throw mapHttpError(statusCode, responseBody)
            }

            val json = try {
                JSONObject(responseBody)
            } catch (e: Exception) {
                throw LLMError.ProviderError("images/edits returned non-JSON body: ${e.message}")
            }
            return@withContext parseImageGenerationsResult(json)
        }
        @Suppress("UNREACHABLE_CODE")
        throw LLMError.ProviderError("images/edits: unreachable")
    }

    private fun parseImageGenerationsResult(json: JSONObject): LLMResponse {
        val dataArray = json.optJSONArray("data")
        if (dataArray == null) {
            if (json.has("choices")) {
                throw LLMError.ProviderError(
                    "[404] /images/generations not supported (got chat completions response)",
                )
            }
            return LLMResponse("", "end_turn", null, emptyList())
        }

        val attachments = mutableListOf<LLMMediaAttachment>()
        val revisedPrompts = mutableListOf<String>()
        for (i in 0 until dataArray.length()) {
            val item = dataArray.optJSONObject(i) ?: continue
            val hintMime = item.safeOptString("mime_type", "").ifEmpty { null }
            val b64 = item.safeOptString("b64_json", "")
            if (b64.isNotEmpty()) {
                val bytes = try {
                    Base64.decode(b64, Base64.DEFAULT)
                } catch (e: IllegalArgumentException) {
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "[ModelUseRoute] images/generations b64 decode failed: ${e.message}",
                    )
                    continue
                }
                val mime = hintMime ?: detectImageMime(bytes)
                attachments.add(LLMMediaAttachment(LLMMediaAttachment.MediaType.IMAGE, mime, bytes))
            } else {
                val urlStr = item.safeOptString("url", "")
                if (urlStr.isNotEmpty()) {
                    try {
                        val dlReq = Request.Builder().url(urlStr).get().build()
                        val dlResp = client.newCall(dlReq).execute()
                        val dlBytes = dlResp.body?.bytes()
                        val ctMime = dlResp.header("Content-Type")
                        dlResp.close()
                        if (dlBytes != null && dlBytes.isNotEmpty()) {
                            val mime = hintMime ?: ctMime ?: detectImageMime(dlBytes)
                            attachments.add(LLMMediaAttachment(LLMMediaAttachment.MediaType.IMAGE, mime, dlBytes))
                        }
                    } catch (e: Exception) {
                        com.openminis.app.logging.AppLogger.warning(
                            "OpenAIProvider",
                            "[ModelUseRoute] failed to download image from $urlStr: ${e.message}",
                        )
                    }
                }
            }
            val revised = item.safeOptString("revised_prompt", "")
            if (revised.isNotEmpty()) revisedPrompts.add(revised)
        }

        val text = revisedPrompts.joinToString("\n")
        return LLMResponse(text, "end_turn", null, attachments)
    }

    internal fun buildRequestBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        stream: Boolean,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject {
        val supportsImages = model.hasImageInput
        val body = JSONObject()
        body.put("model", model.id)
        if (isOpenRouter) {
            body.put("max_tokens", maxTokens)
        } else {
            body.put("max_completion_tokens", maxTokens)
        }
        body.put("stream", stream)

        resolvedServiceTier()?.let { body.put("service_tier", it) }

        if (temperature != null) {
            body.put("temperature", temperature)
        }

        if (stream && !isOpenRouter) {
            body.put("stream_options", JSONObject().put("include_usage", true))
        }

        if (!isMistral) {
            injectThinkingParams(body, thinkingLevel, maxTokens)
        }

        if (needsOpenRouterAnthropicCacheControl) {
            body.put("cache_control", JSONObject().put("type", "ephemeral"))
        }

        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                toolsArray.put(tool.toOpenAIJson())
            }
            body.put("tools", toolsArray)
            body.put("tool_choice", "auto")
        }

        val wireIds = if (keepsKimiToolIds) {
            WireToolIdMap(keepAsIs = ::isKimiNativeToolId)
        } else {
            WireToolIdMap()
        }
        val messagesArray = JSONArray()
        if (systemPrompt != null) {
            messagesArray.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }

        val modelAlwaysReasons = model.supportsReasoning == true
        val modelMayReason = model.supportsReasoning ?: true
        val forbidReasoningField = isMistral || isCerebras
        val includeReasoning =
            (thinkingLevel.isEnabled || modelAlwaysReasons) && modelMayReason && !forbidReasoningField
        val echoReasoning = includeReasoning
        val placeholderAllowed = includeReasoning

        val lastUserIndex = messages.indexOfLast { it.role == LLMMessage.Role.USER }
        for ((index, msg) in messages.withIndex()) {
            if (msg.contentParts.isNotEmpty()) {
                when {
                    msg.role == LLMMessage.Role.ASSISTANT -> {
                        val obj = JSONObject()
                        obj.put("role", "assistant")
                        if (echoReasoning) {
                            val rc = msg.reasoningContent
                            if (rc != null) {
                                obj.put("reasoning_content", rc)
                            } else if (placeholderAllowed) {
                                obj.put("reasoning_content", "")
                            }
                        }
                        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                        val toolUseParts = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
                        if (textParts.isNotEmpty() || toolUseParts.isNotEmpty()) {
                            val visible = textParts.joinToString("") { it.text }
                            val content = com.openminis.app.provider.wire.AssistantWire.contentFor(
                                visible, toolUseParts.isNotEmpty(),
                            )
                            if (content != null) obj.put("content", content)
                        }
                        if (toolUseParts.isNotEmpty()) {
                            val toolCallsArr = JSONArray()
                            for (tu in toolUseParts) {
                                toolCallsArr.put(JSONObject().apply {
                                    put("id", wireIds.idFor(tu.id))
                                    put("type", "function")
                                    put("function", JSONObject().apply {
                                        put("name", tu.name)
                                        put("arguments", tu.input.toString())
                                    })
                                })
                            }
                            obj.put("tool_calls", toolCallsArr)
                        }
                        messagesArray.put(obj)
                    }
                    msg.role == LLMMessage.Role.USER -> {
                        val toolResults = msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
                        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                        val imageParts = msg.contentParts.filterIsInstance<AgentContentPart.ImageData>()

                        val imageCarriers = mutableListOf<JSONObject>()
                        for (tr in toolResults) {
                            messagesArray.put(JSONObject().apply {
                                put("role", "tool")
                                put("tool_call_id", wireIds.idFor(tr.id))
                                put("content", tr.content)
                            })
                            val trBytes = tr.imageData
                            if (trBytes != null && trBytes.isNotEmpty() && supportsImages) {
                                val upload = com.openminis.app.provider.ImageBudget
                                    .prepareForUpload(trBytes, tr.imageMimeType)
                                imageCarriers.add(JSONObject().apply {
                                    put("role", "user")
                                    put("content", JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("type", "text")
                                            put(
                                                "text",
                                                "[Image returned by ${tr.name} " +
                                                    "(tool_call_id: ${wireIds.idFor(tr.id)})]",
                                            )
                                        })
                                        put(
                                            if (upload != null) JSONObject().apply {
                                                put("type", "image_url")
                                                put("image_url", JSONObject().apply {
                                                    put("url", upload.dataUrl())
                                                })
                                            } else JSONObject().apply {
                                                put("type", "text")
                                                put("text", com.openminis.app.provider.ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER)
                                            },
                                        )
                                    })
                                })
                            }
                        }
                        for (carrier in imageCarriers) messagesArray.put(carrier)
                        val hasImages = imageParts.isNotEmpty()
                        if (hasImages || textParts.isNotEmpty()) {
                            if (hasImages) {
                                val contentArray = JSONArray()
                                for (part in msg.contentParts) {
                                    when (part) {
                                        is AgentContentPart.Text -> {
                                            if (part.text.isNotEmpty()) {
                                                contentArray.put(JSONObject().apply {
                                                    put("type", "text")
                                                    put("text", part.text)
                                                })
                                            }
                                        }
                                        is AgentContentPart.ImageData -> {
                                            if (supportsImages) {
                                                val upload = com.openminis.app.provider.ImageBudget.prepareForUpload(part.data, part.mimeType)
                                                contentArray.put(
                                                    if (upload != null) JSONObject().apply {
                                                        put("type", "image_url")
                                                        put("image_url", JSONObject().apply {
                                                            put("url", upload.dataUrl())
                                                        })
                                                    } else JSONObject().apply {
                                                        put("type", "text")
                                                        put("text", com.openminis.app.provider.ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER)
                                                    },
                                                )
                                            } else {
                                                contentArray.put(JSONObject().apply {
                                                    put("type", "text")
                                                    put("text", part.noVisionPlaceholder
                                                        ?: "[Image attached but this model does not support vision input]")
                                                })
                                            }
                                        }
                                        else -> Unit
                                    }
                                }
                                messagesArray.put(JSONObject().apply {
                                    put("role", "user")
                                    put("content", contentArray)
                                })
                            } else {
                                messagesArray.put(JSONObject().apply {
                                    put("role", "user")
                                    put("content", textParts.joinToString("") { it.text })
                                })
                            }
                        }
                    }
                }
            } else {
                val obj = JSONObject()
                obj.put("role", msg.role.value)

                if (echoReasoning && msg.role == LLMMessage.Role.ASSISTANT) {
                    val rc = msg.reasoningContent
                    if (rc != null) {
                        obj.put("reasoning_content", rc)
                    } else if (placeholderAllowed) {
                        obj.put("reasoning_content", "")
                    }
                }

                val attachTopLevelImages =
                    index == lastUserIndex && msg.role == LLMMessage.Role.USER && imageParts.isNotEmpty()
                if (attachTopLevelImages || msg.audioParts.isNotEmpty()) {
                    val contentArray = JSONArray()
                    if (attachTopLevelImages) {
                        for (part in imageParts) {
                            if (supportsImages) {
                                val upload = com.openminis.app.provider.ImageBudget.prepareForUpload(part.data, part.mimeType)
                                contentArray.put(
                                    if (upload != null) JSONObject().apply {
                                        put("type", "image_url")
                                        put("image_url", JSONObject().put("url", upload.dataUrl()))
                                    } else JSONObject().apply {
                                        put("type", "text")
                                        put("text", com.openminis.app.provider.ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER)
                                    },
                                )
                                com.openminis.app.tools.VisionGroupResolver
                                    .visionImagePathNote(part.linuxPath)
                                    ?.let { note ->
                                        contentArray.put(JSONObject().apply {
                                            put("type", "text")
                                            put("text", note)
                                        })
                                    }
                            } else {
                                contentArray.put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", part.noVisionPlaceholder
                                        ?: "[Image attached but this model does not support vision input]")
                                })
                            }
                        }
                    }
                    for (audio in msg.audioParts) {
                        contentArray.put(JSONObject().apply {
                            put("type", "input_audio")
                            put("input_audio", JSONObject().apply {
                                put("data", audio.base64Data)
                                put("format", audio.format)
                            })
                        })
                    }
                    if (attachTopLevelImages || msg.content.isNotEmpty()) {
                        contentArray.put(JSONObject().apply {
                            put("type", "text")
                            put("text", msg.content)
                        })
                    }
                    obj.put("content", contentArray)
                } else {
                    obj.put("content", msg.content)
                }

                messagesArray.put(obj)
            }
        }
        globallyDedupeToolCallIds(messagesArray)
        logWireToolIds("chat", wireIds)
        val paired = ChatCompletionsToolPairing.sanitize(messagesArray)
        if (paired.changed) {
            android.util.Log.w(
                "OpenAIProvider",
                "[sanitize-chat] repaired outgoing tool pairing: " +
                    "movedReplies=${paired.movedReplies} " +
                    "droppedDuplicateReplies=${paired.droppedDuplicateReplies} " +
                    "droppedOrphanReplies=${paired.droppedOrphanReplies} " +
                    "placeholderCalls=${paired.placeholderCalls} messages=${messagesArray.length()}",
            )
        }
        body.put("messages", paired.messages)

        mergeModelOverridesBody(body, temperature)
        mergeChatExtraBody(body)

        return body
    }

    private fun mergeModelOverridesBody(body: JSONObject, explicitTemperature: Double?) {
        val o = modelOverrides ?: return
        if (isOAuth && !forceChatCompletions) return

        if (explicitTemperature == null) {
            o.temperature?.let { body.put("temperature", it) }
        }
        o.topP?.let { body.put("top_p", it) }

        o.extraBodyParams?.let { extra ->
            runCatching { JSONObject(extra.toString()) }.getOrNull()?.let { parsed ->
                for (k in parsed.keys()) body.put(k, parsed.get(k))
            }
        }
        body.put("model", model.id)
    }

    private fun mergeChatExtraBody(body: JSONObject) {
        if (chatExtraBody.isEmpty()) return
        if (isOAuth && !forceChatCompletions) return
        for ((k, v) in chatExtraBody) body.put(k, v ?: JSONObject.NULL)
        body.put("model", model.id)
    }

    private fun globallyDedupeToolCallIds(messagesArray: JSONArray) {
        val seen = HashMap<String, Int>()
        val renameForPendingResults = HashMap<String, String>()
        var renamedCount = 0
        val n = messagesArray.length()
        for (i in 0 until n) {
            val msg = messagesArray.optJSONObject(i) ?: continue
            val role = msg.optString("role")
            when (role) {
                "assistant" -> {
                    val toolCalls = msg.optJSONArray("tool_calls") ?: continue
                    for (j in 0 until toolCalls.length()) {
                        val call = toolCalls.optJSONObject(j) ?: continue
                        val rawId = call.optString("id", "")
                        if (rawId.isEmpty()) continue
                        val used = seen[rawId] ?: 0
                        val renamedId: String
                        if (used == 0) {
                            renamedId = rawId
                            seen[rawId] = 1
                        } else {
                            val next = used + 1
                            renamedId = wireToolIdWithSuffix(rawId, next)
                            seen[rawId] = next
                            renamedCount += 1
                            call.put("id", renamedId)
                        }
                        renameForPendingResults[rawId] = renamedId
                    }
                }
                "tool" -> {
                    val rawId = msg.optString("tool_call_id", "")
                    if (rawId.isEmpty()) continue
                    val renamedId = renameForPendingResults[rawId] ?: continue
                    if (renamedId != rawId) {
                        msg.put("tool_call_id", renamedId)
                    }
                }
            }
        }
        if (renamedCount > 0) {
            android.util.Log.w("OpenAIProvider", "[dedupe-tool-call-id] renamed $renamedCount duplicate tool_call_id(s) across messages — likely DB-loaded history or cross-provider switch")
        }
    }

    internal suspend fun buildRequest(bodyStr: String, routingHint: String? = null, promptCacheKey: String? = null): Request {
        val token = getToken()

        if (isOAuth && !forceChatCompletions) {
            val jsonMediaType = "application/json".toMediaType()
            val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
            val requestBody = object : okhttp3.RequestBody() {
                override fun contentType() = jsonMediaType
                override fun contentLength() = bodyBytes.size.toLong()
                override fun writeTo(sink: okio.BufferedSink) { sink.write(bodyBytes) }
            }
            val builder = Request.Builder()
                .url("https://chatgpt.com/backend-api/codex/responses")
                .post(requestBody)
                .header("Authorization", "Bearer $token")
                .header("Version", CODEX_CLIENT_VERSION)
                .header("Openai-Beta", "responses=experimental")
                .header("User-Agent", "codex_cli_rs/$CODEX_CLIENT_VERSION (Android; arm64)")
                .header("Originator", "codex_cli_rs")
            codexAccountId?.let { builder.header("Chatgpt-Account-Id", it) }
            routingHint?.let { builder.header(CODEX_ROUTING_HINT_HEADER, it) }
            for ((name, value) in codexConversationHeaders(promptCacheKey)) builder.header(name, value)
            builder.applyUserAgentOverride(customUserAgent, defaultUserAgent = null)
            return builder.build()
        }

        val endpointPath = if (useResponsesAPI) "/responses" else "/chat/completions"
        val requestUrl = when {
            absoluteEndpointOverride?.startsWith("/") == true -> endpointURL(endpointPath)
            isAzure -> azureUrl(endpointPath) ?: "$basePath$endpointPath"
            else -> "$basePath$endpointPath"
        }
        val jsonMediaType = "application/json".toMediaType()
        val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
        val requestBody = object : okhttp3.RequestBody() {
            override fun contentType() = jsonMediaType
            override fun contentLength() = bodyBytes.size.toLong()
            override fun writeTo(sink: okio.BufferedSink) { sink.write(bodyBytes) }
        }
        val builder = Request.Builder()
            .url(requestUrl)
            .post(requestBody)
            .applyKeyAuth(token)
            .header("Content-Type", "application/json")
        builder.applyProviderHeaders(requestUrl, bodyStr)
        for ((key, value) in chatExtraHeaders) {
            builder.header(key, value)
        }
        builder.applyProviderUserAgent()
        return builder.build()
    }

    private fun Request.Builder.applyProviderHeaders(requestUrl: String, jsonBody: String?): Request.Builder {
        for ((key, value) in extraHeaders) header(key, value)
        if (jsonBody != null) {
            perRequestHeaders?.let { derive ->
                val parsed = runCatching { org.json.JSONObject(jsonBody) }.getOrNull()
                if (parsed != null) for ((key, value) in derive(parsed)) header(key, value)
            }
        }
        for ((key, value) in com.openminis.app.provider.OpenCodeSessionHeader.headersFor(requestUrl, sessionId)) {
            header(key, value)
        }
        modelOverrides?.customHeaders?.let { headers -> for ((key, value) in headers) header(key, value) }
        return this
    }

    private fun Request.Builder.applyProviderUserAgent(): Request.Builder = applyUserAgentOverride(
        customUserAgent,
        defaultUserAgent = if (extraHeaders.keys.any { it.equals("User-Agent", true) }) {
            null
        } else {
            com.openminis.app.provider.MinisUserAgent.DEFAULT
        },
    )

    private fun parseChatCompletionsUsage(usage: JSONObject): LLMUsage =
        com.openminis.app.usage.ProviderUsageParser.openAIChat(usage).toLLMUsage()

    private fun clampEffortForModel(effort: String): String {
        val lid = model.id.lowercase()
        return if (effort == "xhigh" && (lid.contains("mimo") || lid.contains("agnes"))) "high" else effort
    }

    private fun injectThinkingParams(body: JSONObject, level: ThinkingLevel, maxTokens: Int) {
        val ctx = ThinkingResolveContext(
            modelId = model.id,
            instanceId = thinkingRuleInstanceId,
            supportsReasoning = model.supportsReasoning,
            declaredEffortValues = model.reasoningEffortValues,
            declaresNoEffortTiers = model.declaresNoEffortTiers == true,
            level = level,
            maxTokens = maxTokens,
            isOpenRouter = isOpenRouter,
            usesUnifiedReasoningEffort = usesUnifiedReasoningEffort,
            isMistral = isMistral,
            isDashScope = isDashScope,
            isCerebras = isCerebras,
            isXAI = isXAI,
            offEffort = explicitOffEffort(),
        )
        val trace = ThinkingRuleResolver.apply(body, ctx)
        com.openminis.app.logging.AppLogger.info(
            "Thinking",
            "[resolve] model=${model.id} level=${level.name} ${trace.logLine}",
        )
    }

    internal fun clampEffort(effort: String, values: List<String>?): String {
        if (values.isNullOrEmpty()) return effort
        if (values.contains(effort)) return effort
        val ladder = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
        val want = ladder.indexOf(effort)
        if (want < 0) return effort
        val declared = values.mapNotNull { v ->
            val i = ladder.indexOf(v)
            if (i >= 0) i to v else null
        }.sortedBy { it.first }
        if (declared.isEmpty()) return effort
        return declared.lastOrNull { it.first <= want }?.second ?: declared.first().second
    }


    private fun buildCodexImageBody(messages: List<LLMMessage>): JSONObject {
        val lastUser = messages.lastOrNull { it.role == LLMMessage.Role.USER }
        val prompt = lastUser?.let { m ->
            m.content.takeIf { it.isNotBlank() }
                ?: m.contentParts.filterIsInstance<AgentContentPart.Text>()
                    .joinToString(" ") { it.text }.trim()
        }.orEmpty()
        return JSONObject().apply {
            put("model", "gpt-5.5")
            put("instructions", "You are a helpful assistant. Use tools when available.")
            put("input", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", "Use the image generation tool to create: $prompt")
            }))
            put("store", false)
            val imageTool = JSONObject().put("type", "image_generation")
            if (model.id in CODEX_IMAGE_25_MODEL_IDS) imageTool.put("model", model.id)
            put("tools", JSONArray().put(imageTool))
            put("reasoning", JSONObject().put("effort", "low"))
            put("include", JSONArray())
            put("tool_choice", "auto")
            put("parallel_tool_calls", true)
            put("stream", true)
        }
    }

    private suspend fun handleCodexImageStream(
        reader: BufferedReader,
        emit: (LLMStreamChunk) -> Unit,
    ) {
        var b64Result: String? = null
        var revisedPrompt: String? = null
        var imageCallFailed = false
        var refusalText: String? = null

        fun scanItem(item: JSONObject) {
            when (item.optString("type")) {
                "image_generation_call" -> {
                    if (item.optString("status") == "failed") imageCallFailed = true
                    item.optString("result").takeIf { it.isNotEmpty() }?.let { b64Result = it }
                    item.optString("revised_prompt").takeIf { it.isNotEmpty() }?.let { revisedPrompt = it }
                }
                "message", "output_text" -> {
                    val content = item.optJSONArray("content")
                    if (content != null) {
                        for (i in 0 until content.length()) {
                            val c = content.optJSONObject(i) ?: continue
                            if (c.optString("type").contains("text")) {
                                c.optString("text").takeIf { it.isNotEmpty() }?.let { refusalText = it }
                            }
                        }
                    } else {
                        item.optString("text").takeIf { it.isNotEmpty() }?.let { refusalText = it }
                    }
                }
            }
        }

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line ?: continue
            if (!l.startsWith("data:")) continue
            val payload = l.removePrefix("data:").let { if (it.startsWith(" ")) it.removePrefix(" ") else it }
            if (payload == "[DONE]") break

            val event = try { JSONObject(payload) } catch (e: Exception) { continue }
            when (event.optString("type")) {
                "response.output_item.done" -> {
                    event.optJSONObject("item")?.let { scanItem(it) }
                }
                "response.output_text.done", "response.output_text.delta" -> {
                    event.optString("text").takeIf { it.isNotEmpty() }?.let { refusalText = it }
                        ?: event.optString("delta").takeIf { it.isNotEmpty() }
                            ?.let { refusalText = (refusalText ?: "") + it }
                }
                "response.completed" -> {
                    if (b64Result == null) {
                        val output = event.optJSONObject("response")?.optJSONArray("output")
                        if (output != null) {
                            for (i in 0 until output.length()) {
                                output.optJSONObject(i)?.let { scanItem(it) }
                            }
                        }
                    }
                }
                "response.failed", "error" -> {
                    val msg = event.optJSONObject("response")?.optJSONObject("error")?.optString("message")
                        ?.takeIf { it.isNotEmpty() }
                        ?: event.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
                        ?: "Codex image generation failed"
                    throw LLMError.ProviderError(msg)
                }
            }
        }

        val b64 = b64Result
        if (b64 != null) {
            val bytes = try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (e: Exception) {
                throw LLMError.ProviderError("Failed to decode generated image: ${e.message}")
            }
            if (bytes.isNotEmpty()) {
                emit(
                    LLMStreamChunk.MediaAttachment(
                        LLMMediaAttachment(
                            type = LLMMediaAttachment.MediaType.IMAGE,
                            mimeType = detectImageMime(bytes),
                            data = bytes,
                        ),
                    ),
                )
                emit(LLMStreamChunk.Finished("end_turn"))
                return
            }
        }

        if (imageCallFailed || refusalText != null) {
            val reason = refusalText?.trim()
            throw LLMError.ProviderError(
                "Image generation was rejected by the safety system" +
                    (reason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."),
            )
        }

        throw LLMError.ProviderError("No image data in Codex response")
    }

    private fun detectImageMime(data: ByteArray): String {
        if (data.size < 4) return "image/png"
        val b = data.map { it.toInt() and 0xFF }
        return when {
            b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47 -> "image/png"
            b[0] == 0xFF && b[1] == 0xD8 -> "image/jpeg"
            b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46 -> "image/webp"
            b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46 -> "image/gif"
            else -> "image/png"
        }
    }


    private fun responsesImageBlock(
        data: ByteArray,
        mimeType: String,
        supportsImages: Boolean,
        noVisionPlaceholder: String?,
    ): JSONObject = if (supportsImages) {
        val upload = com.openminis.app.provider.ImageBudget.prepareForUpload(data, mimeType)
        if (upload != null) JSONObject().apply {
            put("type", "input_image")
            put("image_url", upload.dataUrl())
        } else JSONObject().apply {
            put("type", "input_text")
            put("text", com.openminis.app.provider.ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER)
        }
    } else {
        JSONObject().apply {
            put("type", "input_text")
            put(
                "text",
                noVisionPlaceholder
                    ?: "[Image attached but this model does not support vision input]",
            )
        }
    }

    internal fun buildResponsesAPIBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        stream: Boolean,
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject {
        val supportsImages = model.hasImageInput
        val body = JSONObject()
        body.put("model", model.id)
        body.put("stream", stream)
        resolvedServiceTier()?.let { body.put("service_tier", it) }
        body.put("store", false)
        body.put("parallel_tool_calls", true)
        body.put("prompt_cache_key", derivePromptCacheKey(messages))
        if (isOAuth) {
            body.put("include", JSONArray().put("reasoning.encrypted_content"))
        }
        val effort = if (thinkingLevel.isEnabled) {
            mapThinkingLevelToResponsesEffort(thinkingLevel)?.let { clampEffortForModel(it) }
        } else null
        when {
            isMistral -> {}
            effort != null -> body.put(
                "reasoning",
                JSONObject().put("effort", effort).put("summary", "auto"),
            )
            isOAuth -> body.put(
                "reasoning",
                JSONObject().put("effort", "low").put("summary", "auto"),
            )
            !thinkingLevel.isEnabled && model.supportsReasoning == true &&
                !model.id.lowercase().let { it.contains("mimo") || it.contains("agnes") } -> {
                explicitOffEffort()?.let { offEffort ->
                    val safe = ThinkingRuleResolver.clampOffEffort(
                        offEffort,
                        model.reasoningEffortValues,
                    )
                    body.put("reasoning", JSONObject().put("effort", safe))
                }
            }
        }

        if (maxTokens > 0 && !isOAuth) {
            body.put("max_output_tokens", maxTokens)
        }

        if (!body.has("service_tier") && model.id.contains("gpt", ignoreCase = true)) {
            com.openminis.app.data.SpeedTiers
                .effective(com.openminis.app.data.FastModePrefs.tier(), model)
                .wireValue
                ?.let { body.put("service_tier", it) }
        }
        com.openminis.app.data.SpeedTiers
            .ultrafastUnsupportedNote(com.openminis.app.data.FastModePrefs.tier(), model)
            ?.let { com.openminis.app.logging.AppLogger.info("OpenAIProvider", "[SpeedTier] $it") }

        if (systemPrompt != null) {
            body.put("instructions", systemPrompt)
        }

        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                toolsArray.put(tool.toResponsesAPIJson())
            }
            body.put("tools", toolsArray)
            body.put("tool_choice", "auto")
        }

        val input = JSONArray()
        val lastUserIdx = messages.indexOfLast { it.role == LLMMessage.Role.USER }
        for ((msgIndex, msg) in messages.withIndex()) {
            val attachTopLevelImages =
                msgIndex == lastUserIdx && msg.role == LLMMessage.Role.USER && imageParts.isNotEmpty()
            if (msg.contentParts.isNotEmpty()) {
                when (msg.role) {
                    LLMMessage.Role.ASSISTANT -> {
                        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                        if (textParts.isNotEmpty()) {
                            val text = textParts.joinToString("") { it.text }
                            if (text.isNotEmpty()) {
                                input.put(JSONObject().apply {
                                    put("role", "assistant")
                                    put("content", text)
                                })
                            }
                        }
                        for (tu in msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()) {
                            val (callId, fcId) = splitResponsesAPIIds(tu.id)
                            val safeCallId = capResponsesId(callId)
                            val safeFcId = fcId?.let { capResponsesId(it) }
                                ?: "fc_syn_${safeCallId.takeLast(24)}"
                            input.put(JSONObject().apply {
                                put("type", "function_call")
                                put("id", safeFcId)
                                put("call_id", safeCallId)
                                put("name", tu.name)
                                put("arguments", tu.input.toString())
                            })
                        }
                    }
                    LLMMessage.Role.USER -> {
                        val imageCarriers = mutableListOf<JSONObject>()
                        for (tr in msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>()) {
                            val (callId, _) = splitResponsesAPIIds(tr.id)
                            input.put(JSONObject().apply {
                                put("type", "function_call_output")
                                put("call_id", capResponsesId(callId))
                                put("output", tr.content)
                            })
                            val trBytes = tr.imageData
                            if (trBytes != null && trBytes.isNotEmpty() && supportsImages) {
                                imageCarriers.add(JSONObject().apply {
                                    put("role", "user")
                                    put("content", JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("type", "input_text")
                                            put("text", "[Image returned by ${tr.name}]")
                                        })
                                        put(
                                            responsesImageBlock(
                                                trBytes,
                                                tr.imageMimeType ?: "image/jpeg",
                                                supportsImages,
                                                null,
                                            ),
                                        )
                                    })
                                })
                            }
                        }
                        for (carrier in imageCarriers) input.put(carrier)
                        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                        val imgParts = msg.contentParts.filterIsInstance<AgentContentPart.ImageData>()
                        if (imgParts.isNotEmpty()) {
                            val contentArray = JSONArray()
                            for (part in msg.contentParts) {
                                when (part) {
                                    is AgentContentPart.Text -> {
                                        if (part.text.isNotEmpty()) {
                                            contentArray.put(JSONObject().apply {
                                                put("type", "input_text")
                                                put("text", part.text)
                                            })
                                        }
                                    }
                                    is AgentContentPart.ImageData -> {
                                        if (supportsImages) {
                                            val upload = com.openminis.app.provider.ImageBudget.prepareForUpload(part.data, part.mimeType)
                                            contentArray.put(
                                                if (upload != null) JSONObject().apply {
                                                    put("type", "input_image")
                                                    put("image_url", upload.dataUrl())
                                                } else JSONObject().apply {
                                                    put("type", "input_text")
                                                    put("text", com.openminis.app.provider.ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER)
                                                },
                                            )
                                        } else {
                                            contentArray.put(JSONObject().apply {
                                                put("type", "input_text")
                                                put("text", part.noVisionPlaceholder
                                                    ?: "[Image attached but this model does not support vision input]")
                                            })
                                        }
                                    }
                                    is AgentContentPart.ToolUse,
                                    is AgentContentPart.ToolResult -> Unit
                                    else -> com.openminis.app.logging.AppLogger.error(
                                        "OpenAIProvider",
                                        "[responses] DROPPED unconvertible content part " +
                                            "${part.javaClass.simpleName} on role=user — it will NOT " +
                                            "reach the model. Add an encoding branch for it.",
                                    )
                                }
                            }
                            if (attachTopLevelImages) {
                                for (p in imageParts) {
                                    contentArray.put(
                                        responsesImageBlock(p.data, p.mimeType, supportsImages, p.noVisionPlaceholder),
                                    )
                                }
                            }
                            input.put(JSONObject().apply {
                                put("role", "user")
                                put("content", contentArray)
                            })
                        } else if (attachTopLevelImages) {
                            val contentArray = JSONArray()
                            val text = textParts.joinToString("") { it.text }
                            if (text.isNotEmpty()) {
                                contentArray.put(JSONObject().apply {
                                    put("type", "input_text")
                                    put("text", text)
                                })
                            }
                            for (p in imageParts) {
                                contentArray.put(
                                    responsesImageBlock(p.data, p.mimeType, supportsImages, p.noVisionPlaceholder),
                                )
                            }
                            input.put(JSONObject().apply {
                                put("role", "user")
                                put("content", contentArray)
                            })
                        } else if (textParts.isNotEmpty()) {
                            input.put(JSONObject().apply {
                                put("role", "user")
                                put("content", textParts.joinToString("") { it.text })
                            })
                        }
                    }
                    else -> {
                        input.put(JSONObject().apply {
                            put("role", msg.role.value)
                            put("content", msg.content)
                        })
                    }
                }
            } else if (msg.audioParts.isNotEmpty()) {
                val contentArray = JSONArray()
                for (audio in msg.audioParts) {
                    contentArray.put(JSONObject().apply {
                        put("type", "input_audio")
                        put("input_audio", JSONObject().apply {
                            put("data", audio.base64Data)
                            put("format", audio.format)
                        })
                    })
                }
                if (msg.content.isNotEmpty()) {
                    contentArray.put(JSONObject().apply {
                        put("type", "input_text")
                        put("text", msg.content)
                    })
                }
                if (attachTopLevelImages) {
                    for (p in imageParts) {
                        contentArray.put(
                            responsesImageBlock(p.data, p.mimeType, supportsImages, p.noVisionPlaceholder),
                        )
                    }
                }
                input.put(JSONObject().apply {
                    put("role", msg.role.value)
                    put("content", contentArray)
                })
            } else if (attachTopLevelImages) {
                val contentArray = JSONArray()
                if (msg.content.isNotEmpty()) {
                    contentArray.put(JSONObject().apply {
                        put("type", "input_text")
                        put("text", msg.content)
                    })
                }
                for (p in imageParts) {
                    contentArray.put(
                        responsesImageBlock(p.data, p.mimeType, supportsImages, p.noVisionPlaceholder),
                    )
                }
                input.put(JSONObject().apply {
                    put("role", msg.role.value)
                    put("content", contentArray)
                })
            } else {
                input.put(JSONObject().apply {
                    put("role", msg.role.value)
                    put("content", msg.content)
                })
            }
        }
        val paired = ResponsesToolPairing.sanitize(input)
        if (paired.changed) {
            android.util.Log.w(
                "OpenAIProvider",
                "[sanitize-responses] repaired outgoing tool pairing: " +
                    "droppedOrphanOutputs=${paired.droppedOrphanOutputs} " +
                    "droppedDuplicateOutputs=${paired.droppedDuplicateOutputs} " +
                    "placeholderCalls=${paired.placeholderCalls} items=${input.length()}",
            )
        }
        body.put("input", paired.items)

        mergeModelOverridesBody(body, explicitTemperature = null)

        mergeChatExtraBody(body)

        return body
    }

    private fun combineResponsesAPIIds(callId: String, fcId: String): String =
        if (fcId.isEmpty()) callId else "$callId|$fcId"

    private fun splitResponsesAPIIds(combined: String): Pair<String, String?> {
        val sep = combined.indexOf('|')
        return if (sep < 0) combined to null
        else combined.substring(0, sep) to combined.substring(sep + 1)
    }

    private fun capResponsesId(id: String): String =
        if (id.length <= 64) id else id.substring(0, 64)

    private fun derivePromptCacheKey(messages: List<LLMMessage>): String {
        for (msg in messages) {
            if (msg.role != LLMMessage.Role.USER) continue
            val text = msg.contentParts
                .filterIsInstance<AgentContentPart.Text>()
                .joinToString("") { it.text }
                .ifEmpty { msg.content }
            if (text.isNotEmpty()) {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.toByteArray(Charsets.UTF_8))
                val hex = digest.joinToString("") { "%02x".format(it) }
                return "minis-${hex.take(32)}"
            }
        }
        return "minis-${java.util.UUID.randomUUID().toString().lowercase()}"
    }

    private fun mapThinkingLevelToResponsesEffort(level: ThinkingLevel): String? = when (level) {
        ThinkingLevel.OFF -> null
        ThinkingLevel.LOW -> "low"
        ThinkingLevel.MEDIUM -> "medium"
        ThinkingLevel.HIGH -> "high"
        ThinkingLevel.XHIGH -> "xhigh"
        ThinkingLevel.MAX, ThinkingLevel.ULTRA -> "max"
    }

    private fun AgentToolDefinition.toResponsesAPIJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toJson())
        }
        val params = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("type", "function")
            put("name", name)
            put("description", description)
            put("parameters", params)
        }
    }

    private fun parseResponsesAPIUsage(usage: JSONObject): LLMUsage =
        com.openminis.app.usage.ProviderUsageParser.openAIResponses(usage).toLLMUsage()

    private fun mapHttpError(statusCode: Int, body: String): LLMError {
        if (statusCode == 401 || statusCode == 403) return LLMError.InvalidApiKey()
        if (statusCode == 429) return LLMError.RateLimited()

        val message = try {
            val json = JSONObject(body)
            val error = json.optJSONObject("error")
            val errorMessage = error?.safeOptString("message", "") ?: body
            "[$statusCode] $errorMessage"
        } catch (_: Exception) {
            "HTTP $statusCode: ${body.take(500)}"
        }

        val transientCodes = setOf(500, 502, 503, 504, 529)
        if (statusCode in transientCodes) {
            if (statusCode == 503 && (body.contains("no_available_providers") || body.contains("model_not_found"))) {
                return LLMError.ProviderError(message, httpStatus = statusCode)
            }
            return LLMError.TransientError(message, httpStatus = statusCode)
        }
        return LLMError.ProviderError(message, httpStatus = statusCode)
    }

    private fun mapError(error: Throwable): LLMError {
        if (error is LLMError) return error
        com.openminis.app.provider.TlsFailure.toLLMError(error, basePath)?.let { return it }
        if (error is java.io.IOException) return LLMError.NetworkError(error)
        return LLMError.Unknown(error)
    }
}

private class CallWatchState {
    val uploadDoneAtNanos = java.util.concurrent.atomic.AtomicLong(0L)
    val connection = java.util.concurrent.atomic.AtomicReference<okhttp3.Connection?>(null)
}

private class OkHttpNetTraceListener : EventListener() {
    private val tag = "OkHttpNetTrace"
    private val t0 = System.nanoTime()
    private fun ms(): Long = (System.nanoTime() - t0) / 1_000_000L
    private fun callTag(call: Call): String {
        val id = System.identityHashCode(call).toString(16)
        return "call#$id"
    }

    override fun callStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms callStart url=${call.request().url}"
        )
    }

    override fun proxySelectStart(call: Call, url: HttpUrl) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms proxySelectStart host=${url.host}"
        )
    }

    override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms proxySelectEnd host=${url.host} chain=${proxies.joinToString(",") { it.toString() }}"
        )
    }

    override fun dnsStart(call: Call, domainName: String) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms dnsStart host=$domainName"
        )
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms dnsEnd host=$domainName resolved=${inetAddressList.size} addrs=${inetAddressList.take(3).joinToString(",") { it.hostAddress ?: "?" }}"
        )
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms connectStart target=$inetSocketAddress proxy=$proxy"
        )
    }

    override fun secureConnectStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms tlsStart"
        )
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms tlsEnd version=${handshake?.tlsVersion} cipher=${handshake?.cipherSuite}"
        )
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms connectEnd target=$inetSocketAddress proxy=$proxy proto=$protocol"
        )
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        com.openminis.app.logging.AppLogger.warning(
            tag,
            "[${callTag(call)}] +${ms()}ms connectFailed target=$inetSocketAddress proxy=$proxy proto=$protocol err=${ioe.javaClass.simpleName}:${ioe.message}"
        )
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        val conn = System.identityHashCode(connection).toString(16)
        call.request().tag(CallWatchState::class.java)?.connection?.set(connection)
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms connectionAcquired conn#$conn route=${connection.route()} proto=${connection.protocol()}"
        )
    }

    override fun connectionReleased(call: Call, connection: Connection) {
        val conn = System.identityHashCode(connection).toString(16)
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms connectionReleased conn#$conn"
        )
    }

    override fun requestHeadersStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms requestHeadersStart"
        )
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms requestHeadersEnd"
        )
    }

    override fun requestBodyStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms requestBodyStart"
        )
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        call.request().tag(CallWatchState::class.java)?.uploadDoneAtNanos?.set(System.nanoTime())
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms requestBodyEnd bytes=$byteCount"
        )
    }

    override fun responseHeadersStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms responseHeadersStart (server first byte)"
        )
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms responseHeadersEnd status=${response.code} proto=${response.protocol}"
        )
    }

    override fun responseBodyStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms responseBodyStart"
        )
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms responseBodyEnd bytes=$byteCount"
        )
    }

    override fun callEnd(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms callEnd"
        )
    }

    override fun callFailed(call: Call, ioe: IOException) {
        com.openminis.app.logging.AppLogger.warning(
            tag,
            "[${callTag(call)}] +${ms()}ms callFailed err=${ioe.javaClass.simpleName}:${ioe.message}"
        )
    }

    override fun canceled(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms canceled"
        )
    }
}
