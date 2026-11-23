package com.openminis.app.provider

import android.content.Context
import com.openminis.app.auth.BuiltInAccountStore
import com.openminis.app.auth.QoderApi
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [FIX-qoder-chat-protocol] Qoder chat via the cosy-signed `agent_chat_generation`
 * gateway. Qoder has no OpenAI-compatible chat endpoint (the previous
 * `/model/v1/chat/completions` call returned HTTP 404). This provider adapts the
 * app's [LLMProvider] contract to [QoderApi.chatStream], which speaks the
 * community-verified cosy protocol (private encrypted body + SSE envelope).
 *
 * The cosy chat identity is obtained by exchanging the signed-in account's
 * credential for a jobToken (the chat gateway rejects the raw device token).
 * The exchange is handled inside QoderApi.chatStream, so the account the user
 * signed in with works here with no extra PAT setup.
 */
internal class QoderChatProvider(
    private val context: Context,
    override var model: LLMModel,
) : LLMProvider, com.openminis.app.network.NetworkTagged {

    override val name: String = "Qoder"
    override val defaultMaxOutputTokens: Int = 32_000
    override var netTag: String? = null

    private fun toQoderMessages(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        imageParts: List<LLMMessage.ImagePart>,
    ): List<QoderApi.QoderChatMessage> {
        val out = mutableListOf<QoderApi.QoderChatMessage>()
        if (!systemPrompt.isNullOrBlank()) {
            out.add(QoderApi.QoderChatMessage("system", systemPrompt))
        }
        for (m in messages) {
            val role = when (m.role) {
                LLMMessage.Role.USER -> "user"
                LLMMessage.Role.ASSISTANT -> "assistant"
            }
            val imgs = if (m.role == LLMMessage.Role.USER) {
                m.imageParts.mapNotNull { part ->
                    runCatching {
                        val b64 = android.util.Base64.encodeToString(part.data, android.util.Base64.NO_WRAP)
                        "data:${part.mimeType};base64,$b64"
                    }.getOrNull()
                }
            } else emptyList()
            out.add(QoderApi.QoderChatMessage(role, m.content, imgs))
        }
        // If the caller passed images separately (QuickTest vision path) attach
        // them to the last user turn.
        if (imageParts.isNotEmpty()) {
            val extra = imageParts.mapNotNull { part ->
                runCatching {
                    val b64 = android.util.Base64.encodeToString(part.data, android.util.Base64.NO_WRAP)
                    "data:${part.mimeType};base64,$b64"
                }.getOrNull()
            }
            val idx = out.indexOfLast { it.role == "user" }
            if (idx >= 0 && extra.isNotEmpty()) {
                out[idx] = out[idx].copy(images = out[idx].images + extra)
            }
        }
        return out
    }

    private fun accountOrThrow(): com.openminis.app.auth.BuiltInAccount {
        val region = BuiltInAccountStore.activeRegion(context, "qoder")
        return BuiltInAccountStore.load(context, "qoder", region)
            ?: throw LLMError.InvalidApiKey("Qoder 账号未登录")
    }

    private fun upstreamId(): String = QoderApi.upstreamModelId(model.id)

    private fun toolCallsFrom(arr: org.json.JSONArray): List<Triple<String, String, org.json.JSONObject>> {
        val out = mutableListOf<Triple<String, String, org.json.JSONObject>>()
        for (i in 0 until arr.length()) {
            val tc = arr.optJSONObject(i) ?: continue
            val fn = tc.optJSONObject("function") ?: continue
            val args = runCatching { org.json.JSONObject(fn.optString("arguments", "{}")) }
                .getOrDefault(org.json.JSONObject())
            out.add(Triple(tc.optString("id", ""), fn.optString("name", ""), args))
        }
        return out
    }

    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = flow {
        emit(LLMStreamChunk.Started)
        val account = accountOrThrow()
        val qMessages = toQoderMessages(messages, systemPrompt, imageParts)
        try {
            var finished = false
            QoderApi.chatStream(account, upstreamId(), qMessages).collect { d ->
                if (d.content.isNotEmpty()) emit(LLMStreamChunk.Text(d.content))
                if (d.reasoning.isNotEmpty()) emit(LLMStreamChunk.ReasoningContent(d.reasoning))
                d.toolCalls?.let { arr ->
                    toolCallsFrom(arr).forEach { (id, name, args) ->
                        emit(LLMStreamChunk.ToolUseStart(id, name))
                        emit(LLMStreamChunk.ToolInputDelta(id, args.toString()))
                        emit(LLMStreamChunk.ToolCallComplete(id, name, args))
                    }
                }
                if (d.inputTokens > 0 || d.outputTokens > 0) {
                    emit(LLMStreamChunk.Usage(LLMUsage(
                        inputTokens = d.inputTokens,
                        outputTokens = d.outputTokens,
                        cacheReadInputTokens = d.cachedTokens,
                    )))
                }
                // Qoder's SSE envelope doesn't carry a stop reason explicitly;
                // a non-empty content/reasoning frame then stream end = stop.
                if (d.content.isNotEmpty() || d.reasoning.isNotEmpty()) finished = true
            }
            emit(LLMStreamChunk.Finished(if (finished) "stop" else "stop"))
        } catch (e: QoderApi.QoderChatException) {
            throw mapError(e)
        }
    }.failOnSilentEmptyCompletion(name)

    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var usage: LLMUsage? = null
        try {
            streamMessageClamped(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel)
                .collect { chunk ->
                    when (chunk) {
                        is LLMStreamChunk.Text -> text.append(chunk.text)
                        is LLMStreamChunk.ReasoningContent -> reasoning.append(chunk.content)
                        is LLMStreamChunk.ThinkingDelta -> reasoning.append(chunk.text)
                        is LLMStreamChunk.Usage -> usage = chunk.usage
                        else -> Unit
                    }
                }
        } catch (e: LLMError) {
            throw e
        }
        return LLMResponse(text = text.toString(), stopReason = "stop", usage = usage)
    }

    private fun mapError(e: QoderApi.QoderChatException): LLMError {
        val code = e.businessCode
        return when {
            code == "105" -> LLMError.InvalidApiKey(e.message ?: "登录态已过期")
            code == "110" || code == "113" || code == "115" || code == "116" ||
                code == "117" || code == "118" || code == "119" || code == "122" ||
                code == "402" || code == "6004" -> LLMError.ProviderError(e.message ?: "额度不足")
            code == "406" -> LLMError.ProviderError(e.message ?: "内容审核未通过")
            code == "429" -> LLMError.RateLimited()
            code == "500" || code == "10408" || code == "10500" || code == "10605" ||
                (e.message?.contains("HTTP 5") == true) -> LLMError.TransientError(e.message ?: "上游临时错误")
            else -> LLMError.ProviderError(e.message ?: "Qoder 请求失败")
        }
    }
}
