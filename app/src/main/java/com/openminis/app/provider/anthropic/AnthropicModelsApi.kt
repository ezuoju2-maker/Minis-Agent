package com.openminis.app.provider.anthropic

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.applyUserAgentOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

object AnthropicModelsApi {
    private val client = OkHttpClient()

    suspend fun fetchModels(
        apiKey: String,
        baseURL: String? = null,
        isOAuth: Boolean = false,
        context: Context? = null,
        forceRefresh: Boolean = false,
        customUserAgent: String? = null,
        netTag: String? = null,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        val cacheKey = (baseURL ?: "") + "|" + apiKey + "|" + isOAuth
        if (context != null && !forceRefresh) {
            AnthropicModelsCache.load(context, cacheKey)?.let { return@withContext it }
        }

        val isCustomEndpoint = baseURL != null

        val candidateBases = mutableListOf<String?>(baseURL)
        if (baseURL != null) {
            var current: String = baseURL
            for (i in 0 until 3) {
                val parent = parentPath(current) ?: break
                if (parent == current) break
                candidateBases.add(parent)
                current = parent
            }
        }

        var lastFailureCode: Int = -1
        var lastFailureBody: String = ""
        for ((idx, candidate) in candidateBases.withIndex()) {
            val url = buildURL(candidate, forceV1Discovery = idx > 0)
            val requestBuilder = Request.Builder()
                .url(url)
                .header("anthropic-version", "2023-06-01")
                .applyUserAgentOverride(customUserAgent)

            if (isOAuth) {
                requestBuilder.header("Authorization", "Bearer ${com.openminis.app.provider.WireSanitize.token(apiKey)}")
                requestBuilder.header("anthropic-beta", "oauth-2025-04-20")
            } else if (candidate != null) {
                requestBuilder.header("Authorization", "Bearer ${com.openminis.app.provider.WireSanitize.token(apiKey)}")
            } else {
                requestBuilder.header("x-api-key", com.openminis.app.provider.WireSanitize.token(apiKey))
            }

            val request = requestBuilder.build()
            android.util.Log.d("AnthropicModels", "Fetching models (level=$idx): ${request.url} isOAuth=$isOAuth headers=${request.headers}")

            val response: Response = try {
                com.openminis.app.network.NetworkHttp.client(netTag, client).newCall(request).execute()
            } catch (e: Exception) {
                android.util.Log.e("AnthropicModels", "Fetch error (level=$idx): ${e.message}")
                continue
            }
            val body = response.body?.string() ?: run {
                android.util.Log.w("AnthropicModels", "Empty response body (level=$idx)")
                ""
            }

            if (!response.isSuccessful) {
                android.util.Log.e("AnthropicModels", "Fetch failed (level=$idx): ${response.code} ${body.take(200)}")
                if (context != null && (response.code == 401 || response.code == 403)) {
                    AnthropicModelsCache.invalidate(context, cacheKey)
                }
                lastFailureCode = response.code
                lastFailureBody = body
                continue
            }

            if (idx > 0) {
                android.util.Log.i("AnthropicModels", "Parent-path fallback succeeded at level=$idx base=$candidate")
            }
            android.util.Log.d("AnthropicModels", "Response ${response.code}, body length: ${body.length}")
            return@withContext parseAndCache(body, isCustomEndpoint, context, cacheKey)
        }

        android.util.Log.e("AnthropicModels", "All ${candidateBases.size} fallback levels failed; lastCode=$lastFailureCode")
        return@withContext if (isCustomEndpoint) emptyList() else LLMModel.allAnthropic
    }

    private suspend fun parseAndCache(
        body: String,
        isCustomEndpoint: Boolean,
        context: Context?,
        cacheKey: String,
    ): List<LLMModel> {
        val fallback: List<LLMModel> = if (isCustomEndpoint) emptyList() else LLMModel.allAnthropic
        val models = try {
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return fallback
            val parsed = mutableListOf<LLMModel>()
            for (i in 0 until data.length()) {
                val obj = data.getJSONObject(i)
                val id = obj.getString("id")
                val displayName = obj.optString("display_name", "").ifBlank { LLMModel.modelDisplayName(id) }
                val reasoning = if (AnthropicProvider.supportsThinking(id)) true else null
                val (reportedContext, reportedOutput) = OpenAIModelsApi.reportedLimits(obj)
                parsed.add(
                    LLMModel(
                        id, displayName, "Anthropic",
                        contextWindow = reportedContext,
                        maxOutputTokens = reportedOutput,
                        limitsReportedByEndpoint = if (reportedContext != null || reportedOutput != null) true else null,
                        supportsReasoning = reasoning,
                    ),
                )
            }
            android.util.Log.i("AnthropicModels", "Parsed ${parsed.size} models: ${parsed.map { it.id }.joinToString()}")
            if (parsed.isEmpty()) return fallback
            ModelsDevApi.enrichModels(parsed)
        } catch (_: Exception) {
            return fallback
        }

        if (context != null) {
            AnthropicModelsCache.save(context, cacheKey, models)
        }
        return models
    }

    private fun buildURL(baseURL: String?, forceV1Discovery: Boolean = false): String {
        if (baseURL == null) return "https://api.anthropic.com/v1/models?limit=512"
        val base = baseURL.trimEnd('/')
        val endpoint = when {
            base.endsWith("/v1") -> "$base/models"
            forceV1Discovery -> "$base/v1/models"
            else -> "$base/models"
        }
        return "$endpoint?limit=512"
    }

    private fun parentPath(base: String): String? {
        val httpUrl = base.toHttpUrlOrNull() ?: return null
        val segments = httpUrl.pathSegments.toMutableList()
        while (segments.isNotEmpty() && segments.last().isEmpty()) {
            segments.removeAt(segments.size - 1)
        }
        if (segments.isEmpty()) return null
        segments.removeAt(segments.size - 1)
        val builder = httpUrl.newBuilder()
        for (i in httpUrl.pathSegments.indices.reversed()) {
            builder.removePathSegment(i)
        }
        for (seg in segments) {
            builder.addPathSegment(seg)
        }
        return builder.build().toString().trimEnd('/')
    }
}
