package com.openminis.app.provider.openai

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.normalizeModalities
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.ProviderModelsCache
import com.openminis.app.provider.applyUserAgentOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

object OpenAIModelsApi {
    private const val TAG = "OpenAIModelsApi"
    private val client = OkHttpClient()
    private val cache = ProviderModelsCache("openai")

    fun fetchModelsOAuth(): List<LLMModel> = listOf(
        LLMModel("gpt-6-sol", "GPT-6 Sol", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-6-luna", "GPT-6 Luna", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-6-astra", "GPT-6 Astra", "OpenAI", supportsReasoning = true),
        LLMModel(
            "gpt-6.1-sol", "GPT-6.1 Sol", "OpenAI",
            contextWindow = 272_000,
            maxOutputTokens = 128_000,
            supportsReasoning = true,
        ),
        LLMModel("gpt-5.6-sol", "GPT-5.6 Sol", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.6-terra", "GPT-5.6 Terra", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.6-luna", "GPT-5.6 Luna", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.5", "GPT-5.5", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.4", "GPT-5.4", "OpenAI", supportsReasoning = true),
        LLMModel("gpt-5.4-mini", "GPT-5.4 Mini", "OpenAI", supportsReasoning = true),
    ).let {
        AppLogger.info(TAG, "Codex OAuth model list (${it.size} models): ${it.joinToString { m -> m.id }}")
        ModelsDevApi.enrichModels(it)
    } + codexImageModels()

    fun codexImageModels(): List<LLMModel> = listOf(
        LLMModel(
            id = "gpt-image-2",
            displayName = "GPT Image 2",
            provider = "OpenAI",
            inputModalities = listOf("text", "image"),
            outputModalities = listOf("image"),
        ),
        LLMModel(
            id = "gpt-image-2.5-sunburst",
            displayName = "GPT Image 2.5 Sunburst",
            provider = "OpenAI",
            inputModalities = listOf("text", "image"),
            outputModalities = listOf("image"),
        ),
        LLMModel(
            id = "gpt-image-2.5-flare",
            displayName = "GPT Image 2.5 Flare",
            provider = "OpenAI",
            inputModalities = listOf("text", "image"),
            outputModalities = listOf("image"),
        ),
    )

    private val chatPrefixes = listOf("gpt-", "o1", "o3", "o4-", "codex-", "chatgpt-")

    private val excludeSuffixes = listOf(
        "-instruct", "-realtime", "-audio", "-transcribe", "-tts", "-embedding"
    )

    data class FetchResult(val models: List<LLMModel>, val error: String?)

    suspend fun fetchModels(
        apiKey: String,
        baseURL: String? = null,
        context: Context? = null,
        forceRefresh: Boolean = false,
        customUserAgent: String? = null,
        netTag: String? = null,
    ): List<LLMModel> = fetchModelsDetailed(apiKey, baseURL, context, forceRefresh, customUserAgent, netTag).models

    suspend fun fetchModelsDetailed(
        apiKey: String,
        baseURL: String? = null,
        context: Context? = null,
        forceRefresh: Boolean = false,
        customUserAgent: String? = null,
        netTag: String? = null,
    ): FetchResult = withContext(Dispatchers.IO) {
        val isCustomBase = baseURL != null && !isOfficialOpenAI(baseURL)
        val fallback = if (isCustomBase) emptyList() else LLMModel.allOpenAI
        fun fail(why: String) = FetchResult(fallback, why)

        val cacheKey = (baseURL ?: "") + "|" + apiKey
        if (context != null && !forceRefresh) {
            cache.load(context, cacheKey)?.let { return@withContext FetchResult(it, null) }
        }

        val url = buildURL(baseURL)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${com.openminis.app.provider.WireSanitize.token(apiKey)}")
            .applyUserAgentOverride(customUserAgent)
            .build()

        val (code, body) = com.openminis.app.network.NetworkHttp.client(netTag, client).newCall(request).execute().use { it.code to (it.body?.string() ?: "") }

        if (code !in 200..299) {
            if (context != null && (code == 401 || code == 403)) {
                cache.invalidate(context, cacheKey)
            }
            return@withContext fail("HTTP $code: ${errorSnippet(body)}")
        }

        val data = modelArray(body)
            ?: return@withContext fail("the response is not a model list: ${errorSnippet(body)}")
        val models = try {
            val parsed = mutableListOf<LLMModel>()
            for (i in 0 until data.length()) {
                val raw = data.opt(i)
                val obj = raw as? JSONObject ?: JSONObject().put("id", raw?.toString() ?: "")
                val id = listOf("id", "model", "model_id", "name")
                    .firstNotNullOfOrNull { k -> obj.optString(k, "").trim().takeIf { it.isNotEmpty() } }
                    ?: continue

                if (!isCustomBase) {
                    if (!chatPrefixes.any { id.startsWith(it) }) continue
                    if (excludeSuffixes.any { id.contains(it) }) continue
                    if (id.contains(":ft-")) continue
                }

                val displayName = (if (obj.optString("name", "") == id) "" else obj.optString("name", "")).ifBlank { LLMModel.modelDisplayName(id) }
                val arch = obj.optJSONObject("architecture")
                val inputModalities = arch?.optJSONArray("input_modalities")?.toStringList().normalizeModalities()
                val outputModalities = arch?.optJSONArray("output_modalities")?.toStringList().normalizeModalities()

                val idLower = id.lowercase()
                val knownReasoning = idLower.startsWith("gpt-5") ||
                    idLower.startsWith("gpt-6") ||
                    idLower.startsWith("o1") ||
                    idLower.startsWith("o3") ||
                    idLower.startsWith("o4") ||
                    idLower.contains("codex")

                val (reportedContext, reportedOutput) = reportedLimits(obj)
                parsed.add(
                    LLMModel(
                        id = id,
                        displayName = displayName,
                        provider = if (isCustomBase) "Custom" else "OpenAI",
                        contextWindow = reportedContext,
                        maxOutputTokens = reportedOutput,
                        limitsReportedByEndpoint = if (reportedContext != null || reportedOutput != null) true else null,
                        inputModalities = inputModalities,
                        outputModalities = outputModalities,
                        supportsReasoning = if (knownReasoning) true else null,
                    )
                )
            }
            if (parsed.isEmpty()) return@withContext fail("the provider listed no models")
            ModelsDevApi.enrichModels(parsed)
        } catch (e: Exception) {
            return@withContext fail("could not read the model list: ${e.message ?: e.javaClass.simpleName}")
        }

        if (context != null) cache.save(context, cacheKey, models)
        FetchResult(models, null)
    }

    internal fun reportedLimits(obj: JSONObject): Pair<Int?, Int?> {
        val limit = obj.optJSONObject("limit")
        val top = obj.optJSONObject("top_provider")
        val context = listOf("context_length", "context_window", "contextWindow", "max_context_length")
            .firstNotNullOfOrNull { positiveInt(obj.opt(it)) }
            ?: positiveInt(limit?.opt("context"))
            ?: positiveInt(top?.opt("context_length"))
        val output = listOf("max_output_tokens", "maxOutputTokens", "max_completion_tokens")
            .firstNotNullOfOrNull { positiveInt(obj.opt(it)) }
            ?: positiveInt(limit?.opt("output"))
            ?: positiveInt(top?.opt("max_completion_tokens"))
        return context to output
    }

    private fun positiveInt(raw: Any?): Int? {
        val value: Long? = when (raw) {
            is Boolean -> null
            is Number -> raw.toDouble().takeIf { it.isFinite() && it >= 1 && it < Int.MAX_VALUE.toDouble() }?.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        }
        return value?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
    }

    internal fun modelArray(body: String): JSONArray? {
        val text = body.trim()
        if (text.startsWith("[")) return runCatching { JSONArray(text) }.getOrNull()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val keys = listOf("data", "models", "items", "results", "result")
        for (k in keys) json.optJSONArray(k)?.let { return it }
        for (k in keys) {
            val inner = json.optJSONObject(k) ?: continue
            for (k2 in keys) inner.optJSONArray(k2)?.let { return it }
        }
        return null
    }

    internal fun errorSnippet(body: String): String {
        val json = runCatching { JSONObject(body.trim()) }.getOrNull()
        val message = json?.optJSONObject("error")?.optString("message", "")?.takeIf { it.isNotBlank() }
            ?: json?.optString("message", "")?.takeIf { it.isNotBlank() }
            ?: json?.optString("error", "")?.takeIf { it.isNotBlank() && !it.startsWith("{") }
        return (message ?: body).replace(Regex("\\s+"), " ").trim().take(200).ifEmpty { "(empty body)" }
    }

    private fun JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val s = optString(i, "")
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun isOfficialOpenAI(baseURL: String): Boolean {
        val lower = baseURL.lowercase()
        return lower.contains("api.openai.com") || lower.contains("chatgpt.com")
    }

    private fun buildURL(baseURL: String?): String {
        if (baseURL == null) return "https://api.openai.com/v1/models"
        val base = baseURL.trimEnd('/')
        return "$base/models"
    }
}
