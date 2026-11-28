package com.openminis.app.provider.gemini

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.ProviderModelsCache
import com.openminis.app.provider.applyUserAgentOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

object GeminiModelsApi {
    private val client = OkHttpClient()
    private val cache = ProviderModelsCache("gemini")

    suspend fun fetchModels(
        apiKey: String,
        isOAuth: Boolean = false,
        cloudCodeFallback: Boolean = false,
        context: Context? = null,
        forceRefresh: Boolean = false,
        netTag: String? = null,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        if (cloudCodeFallback) return@withContext LLMModel.allGemini

        val cacheKey = (if (isOAuth) "oauth|" else "key|") + apiKey
        if (context != null && !forceRefresh) {
            cache.load(context, cacheKey)?.let { return@withContext it }
        }

        val builder = Request.Builder()
        if (isOAuth) {
            builder.url("https://generativelanguage.googleapis.com/v1beta/models")
            builder.header("Authorization", "Bearer ${com.openminis.app.provider.WireSanitize.token(apiKey)}")
        } else {
            builder.url("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
        }

        builder.applyUserAgentOverride(null)
        val response = com.openminis.app.network.NetworkHttp.client(netTag, client).newCall(builder.build()).execute()
        val body = response.body?.string() ?: return@withContext LLMModel.allGemini

        if (!response.isSuccessful) {
            if (isOAuth && response.code == 403) return@withContext LLMModel.allGemini
            if (context != null && (response.code == 401 || response.code == 403)) {
                cache.invalidate(context, cacheKey)
            }
            return@withContext LLMModel.allGemini
        }

        val models = try {
            val json = JSONObject(body)
            val arr = json.optJSONArray("models") ?: return@withContext LLMModel.allGemini
            val result = mutableListOf<LLMModel>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.getString("name").removePrefix("models/")
                val displayName = obj.optString("displayName", "").ifBlank { LLMModel.modelDisplayName(name) }
                val supportsGen = obj.optJSONArray("supportedGenerationMethods")
                    ?.let { methods ->
                        (0 until methods.length()).any {
                            methods.getString(it).contains("generateContent")
                        }
                    } == true
                if (supportsGen) {
                    result.add(LLMModel(name, displayName, "Google"))
                }
            }
            if (result.isEmpty()) return@withContext LLMModel.allGemini
            ModelsDevApi.enrichModels(result)
        } catch (_: Exception) {
            return@withContext LLMModel.allGemini
        }

        if (context != null) cache.save(context, cacheKey, models)
        models
    }
}
