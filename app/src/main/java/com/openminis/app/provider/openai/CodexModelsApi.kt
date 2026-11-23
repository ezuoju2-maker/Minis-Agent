package com.openminis.app.provider.openai

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.normalizeModalities
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.ProviderModelsCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object CodexModelsApi {
    private const val TAG = "CodexModelsApi"

    private const val MODELS_URL = "https://chatgpt.com/backend-api/codex/models"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val cache = ProviderModelsCache("codex-oauth")

    internal val HIDDEN_VISIBILITIES = setOf("hide", "hidden", "internal", "none")

    sealed interface Result {
        data class Success(val models: List<LLMModel>) : Result

        data class AuthFailed(val status: Int) : Result

        data class Failed(val reason: String) : Result
    }

    suspend fun fetchModels(
        accessToken: String,
        accountId: String?,
        clientVersion: String,
        context: Context? = null,
        forceRefresh: Boolean = false,
        netTag: String? = null,
    ): Result = withContext(Dispatchers.IO) {
        val key = cacheKey(accountId, accessToken, clientVersion)
        AppLogger.info(
            TAG,
            "discovery start: clientVersion=$clientVersion hasAccountId=${!accountId.isNullOrBlank()} force=$forceRefresh",
        )
        if (!forceRefresh && context != null) {
            cache.load(context, key)?.let {
                AppLogger.info(TAG, "discovery cache hit (${it.size} models): ${it.joinToString { m -> m.id }}")
                recordUltrafast(context, it)
                return@withContext Result.Success(it)
            }
        }

        val builder = discoveryRequest(accessToken, accountId, clientVersion)

        val (status, body) = try {
            com.openminis.app.network.NetworkHttp.client(netTag, client).newCall(builder.build()).execute().use { resp ->
                resp.code to (resp.body?.string() ?: "")
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "discovery transport error: ${e.javaClass.simpleName}: ${e.message}")
            return@withContext Result.Failed("network: ${e.javaClass.simpleName}")
        }

        if (status == 401 || status == 403) {
            if (context != null) cache.invalidate(context, key)
            AppLogger.warning(TAG, "discovery rejected credential: HTTP $status")
            return@withContext Result.AuthFailed(status)
        }
        if (status !in 200..299) {
            AppLogger.warning(TAG, "discovery HTTP $status: ${body.take(200)}")
            return@withContext Result.Failed("http $status")
        }

        val parsed = try {
            parseModels(body)
        } catch (e: Exception) {
            AppLogger.warning(TAG, "discovery parse error: ${e.javaClass.simpleName}: ${e.message}")
            return@withContext Result.Failed("parse: ${e.javaClass.simpleName}")
        }

        if (parsed.isEmpty()) {
            AppLogger.warning(TAG, "discovery returned no visible models")
            return@withContext Result.Failed("empty")
        }

        val enriched = enrichPreservingFresh(parsed)
        AppLogger.info(
            TAG,
            "discovery OK (${enriched.size} models): ${enriched.joinToString { it.id }}",
        )
        if (context != null) cache.save(context, key, enriched)
        recordUltrafast(context, enriched)
        Result.Success(enriched)
    }

    private fun recordUltrafast(context: Context?, models: List<LLMModel>) {
        val ids = models.filter { m -> m.serviceTiers?.contains("ultrafast") == true }.map { it.id.lowercase() }
        com.openminis.app.data.FastModePrefs.recordCatalogUltrafast(context, ids)
        AppLogger.info(
            TAG,
            "Codex discovery: catalogue declares ultrafast for ${ids.size} model(s)" +
                if (ids.isEmpty()) "" else ": " + ids.joinToString(","),
        )
    }

    internal fun discoveryRequest(accessToken: String, accountId: String?, clientVersion: String): Request.Builder {
        val builder = Request.Builder()
            .url("$MODELS_URL?client_version=$clientVersion")
            .get()
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $accessToken")
            .header("Originator", "codex_cli_rs")
            .header("User-Agent", "codex_cli_rs/$clientVersion (Android; arm64)")
        accountId?.takeIf { it.isNotBlank() }?.let { builder.header("Chatgpt-Account-Id", it) }
        return builder
    }

    fun invalidate(context: Context, accountId: String?, accessToken: String, clientVersion: String) {
        cache.invalidate(context, cacheKey(accountId, accessToken, clientVersion))
    }

    internal fun cacheKey(accountId: String?, accessToken: String, clientVersion: String): String {
        val identity = accountId?.takeIf { it.isNotBlank() } ?: "tok:$accessToken"
        return "$identity|$clientVersion"
    }

    internal fun parseModels(body: String): List<LLMModel> {
        val root = JSONObject(body)
        val array = root.optJSONArray("models") ?: root.optJSONArray("data") ?: return emptyList()

        val ranked = mutableListOf<Pair<Int, LLMModel>>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val model = parseEntry(obj) ?: continue
            ranked.add(obj.optInt("priority", Int.MAX_VALUE) to model)
        }
        return ranked
            .sortedWith(compareBy({ it.first }, { it.second.id }))
            .map { it.second }
    }

    private fun parseEntry(obj: JSONObject): LLMModel? {
        val id = obj.optString("slug").ifBlank { obj.optString("id") }.trim()
        if (id.isEmpty()) return null

        val visibility = obj.optString("visibility").trim().lowercase()
        if (visibility in HIDDEN_VISIBILITIES) return null

        val contextWindow = obj.optInt("context_window", 0).takeIf { it > 0 }
        val maxOutput = obj.optInt("max_output_tokens", 0).takeIf { it > 0 }

        return LLMModel(
            id = id,
            displayName = obj.optString("display_name").trim().ifEmpty { id },
            provider = "OpenAI",
            contextWindow = contextWindow,
            maxOutputTokens = maxOutput,
            supportsReasoning = parseReasoning(obj),
            inputModalities = obj.optJSONArray("input_modalities")
                ?.toStringList()
                .normalizeModalities(),
            outputModalities = null,
            reasoningEffortValues = parseEffortTiers(obj),
            serviceTiers = parseServiceTiers(obj),
        )
    }

    internal fun parseServiceTiers(obj: JSONObject): List<String>? {
        if (!obj.has("service_tiers") && !obj.has("additional_speed_tiers")) return null
        val out = LinkedHashSet<String>()
        obj.optJSONArray("service_tiers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val id = when (val raw = arr.opt(i)) {
                    is JSONObject -> raw.optString("id")
                    is String -> raw
                    else -> ""
                }.trim().lowercase()
                if (id.isNotEmpty()) out.add(id)
            }
        }
        obj.optJSONArray("additional_speed_tiers")?.toStringList()?.forEach { t ->
            val id = t.trim().lowercase()
            if (id.isNotEmpty()) out.add(id)
        }
        return out.toList()
    }

    private fun parseReasoning(obj: JSONObject): Boolean? {
        val default = obj.optString("default_reasoning_level").trim().lowercase()
        if (default.isNotEmpty() && default != "none") return true

        val levels = obj.optJSONArray("supported_reasoning_levels")
        var sawLevels = false
        if (levels != null) {
            for (i in 0 until levels.length()) {
                val effort = when (val raw = levels.opt(i)) {
                    is JSONObject -> raw.optString("effort").trim().lowercase()
                    is String -> raw.trim().lowercase()
                    else -> ""
                }
                if (effort.isEmpty()) continue
                sawLevels = true
                if (effort != "none") return true
            }
        }
        return if (default == "none" || sawLevels) false else null
    }

    private fun parseEffortTiers(obj: JSONObject): List<String>? {
        val levels = obj.optJSONArray("supported_reasoning_levels") ?: return null
        val out = LinkedHashSet<String>()
        for (i in 0 until levels.length()) {
            val effort = when (val raw = levels.opt(i)) {
                is JSONObject -> raw.optString("effort").trim().lowercase()
                is String -> raw.trim().lowercase()
                else -> ""
            }
            if (effort.isNotEmpty() && effort != "none") out.add(effort)
        }
        return out.toList().takeIf { it.isNotEmpty() }
    }

    internal fun enrichPreservingFresh(fresh: List<LLMModel>): List<LLMModel> =
        restoreAuthoritative(fresh, ModelsDevApi.enrichModels(fresh))

    internal fun restoreAuthoritative(
        fresh: List<LLMModel>,
        enriched: List<LLMModel>,
    ): List<LLMModel> {
        return fresh.zip(enriched) { authoritative, filled ->
            filled.copy(
                contextWindow = maxCapacity(authoritative.contextWindow, filled.contextWindow),
                maxOutputTokens = maxCapacity(authoritative.maxOutputTokens, filled.maxOutputTokens),
                supportsReasoning = authoritative.supportsReasoning ?: filled.supportsReasoning,
                inputModalities = authoritative.inputModalities ?: filled.inputModalities,
                reasoningEffortValues = authoritative.reasoningEffortValues
                    ?: filled.reasoningEffortValues,
                serviceTiers = authoritative.serviceTiers ?: filled.serviceTiers,
                displayName = preferredName(authoritative.id, authoritative.displayName),
            )
        }
    }

    private fun maxCapacity(fromRoute: Int?, fromCatalog: Int?): Int? = when {
        fromRoute == null -> fromCatalog
        fromCatalog == null -> fromRoute
        else -> maxOf(fromRoute, fromCatalog)
    }

    private fun preferredName(id: String, fromRoute: String): String {
        val builtIn = builtInNames[id] ?: return fromRoute
        return if (cosmeticKey(fromRoute) == cosmeticKey(builtIn)) builtIn else fromRoute
    }

    private val builtInNames: Map<String, String> by lazy {
        OpenAIModelsApi.fetchModelsOAuth().associate { it.id to it.displayName }
    }

    private fun cosmeticKey(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    private fun JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val s = optString(i, "").trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }
}
