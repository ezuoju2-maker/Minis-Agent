package com.openminis.app.provider

import android.content.Context
import android.util.Log
import com.openminis.app.data.model.LLMModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object ModelsDevApi {
    private const val TAG = "ModelsDevApi"
    private const val SOURCE_URL = "https://models.dev/api.json"
    private const val CACHE_TTL_MS = 48 * 3600 * 1000L

    private val providerKeyMap = mapOf(
        "Anthropic" to listOf("anthropic"),
        "Google" to listOf("google", "google-vertex"),
        "OpenAI" to listOf("openai"),
        "OpenRouter" to listOf("openrouter"),
        "Antigravity" to emptyList(),
    )

    private var cachedRegistry: Map<String, ProviderEntry>? = null
    private var cacheTimestamp: Long = 0L

    @Volatile private var fetchedAt: Long = 0L

    private const val MANUAL_REFRESH_FRESH_MS = 10 * 60 * 1000L

    private var cachedAggregateIndex: Map<String, ModelDevEntry>? = null
    private var aggregateIndexBuiltFrom: Long = -1L
    private var aggregateIndexRegistry: Map<String, ProviderEntry>? = null

    private var cachedStage2Index: Map<String, ModelDevEntry>? = null
    private var stage2IndexBuiltFrom: Long = -1L
    private var stage2IndexRegistry: Map<String, ProviderEntry>? = null
    private val isRefreshing = AtomicBoolean(false)
    private var appContext: Context? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        appContext = context.applicationContext
    }


    fun fetchModels(forBaseURL: String, fresh: Boolean = false): List<LLMModel> {
        if (fresh && System.currentTimeMillis() - fetchedAt > MANUAL_REFRESH_FRESH_MS) {
            refreshFromNetwork()
        }
        val registry = loadRegistry() ?: return emptyList()

        val candidates = normalizedCandidates(forBaseURL)
        for ((_, provider) in registry) {
            val api = provider.api ?: continue
            if (api.isEmpty()) continue
            val normalizedAPI = stripTrailingSlash(api)
            for (candidate in candidates) {
                if (candidate == normalizedAPI) {
                    val models = buildModels(provider)
                    Log.d(TAG, "Exact match ${provider.id} (api=$api) — ${models.size} models")
                    return models
                }
            }
        }

        val inputHost = extractHost(forBaseURL)
        if (inputHost != null) {
            for ((_, provider) in registry) {
                val api = provider.api ?: continue
                val providerHost = extractHost(api) ?: continue
                if (inputHost == providerHost) {
                    val models = buildModels(provider)
                    Log.d(TAG, "Host match ${provider.id} (host=$providerHost) — ${models.size} models")
                    return models
                }
            }
        }

        Log.d(TAG, "No models.dev match for base URL: $forBaseURL")
        return emptyList()
    }


    fun enrichModel(model: LLMModel): LLMModel {
        val registry = loadRegistry() ?: return model
        val devModel = resolveDevEntry(registry, model.provider, model.id) ?: return model
        return applyDevData(model, devModel)
    }

    fun enrichModels(models: List<LLMModel>): List<LLMModel> {
        val registry = loadRegistry() ?: return models
        return models.map { model ->
            resolveDevEntry(registry, model.provider, model.id)
                ?.let { applyDevData(model, it) } ?: model
        }
    }


    private const val MIN_ALIAS_SEGMENTS = 2

    internal fun aliasCandidates(modelId: String): List<String> {
        val out = mutableListOf(modelId)
        var current = modelId
        while (true) {
            val cut = current.lastIndexOfAny(charArrayOf('-', ':'))
            if (cut <= 0) break
            current = current.substring(0, cut)
            val segments = current.split('-', ':').count { it.isNotEmpty() }
            if (segments < MIN_ALIAS_SEGMENTS) break
            if (current != modelId) out.add(current)
        }
        return out
    }

    internal fun resolveDevEntry(
        registry: Map<String, ProviderEntry>,
        provider: String,
        modelId: String,
    ): ModelDevEntry? {
        val keys = providerKeyMap[provider] ?: emptyList()
        val wanted = normalizedModelKey(modelId)

        for (key in keys) {
            val prov = registry[key] ?: continue
            prov.models[modelId]?.let { return it }
            for (id in prov.models.keys.sorted()) {
                if (normalizedModelKey(id) == wanted) prov.models[id]?.let { return it }
            }
        }
        val index = stage2Index(registry)
        index[wanted]?.let { return it }
        prefixMatch(wanted, index)?.let { return it }

        for (candidate in aliasCandidates(modelId)) {
            for (key in keys) {
                registry[key]?.models?.get(candidate)?.let { return it }
            }
            val candidates = registry.keys.sorted().mapNotNull { registry[it]?.models?.get(candidate) }
            val best = candidates.firstOrNull { !it.reasoningEffortValues.isNullOrEmpty() }
                ?: candidates.firstOrNull()
            if (best != null) return best
        }

        if (!isUnrecognizedProvider(provider)) return null
        val aggIndex = aggregateIndex(registry)
        for (candidate in normalizedPrefixes(wanted) + aliasCandidates(modelId)) {
            aggIndex[candidate]?.let {
                Log.i(TAG, "aggregate fallback: $modelId -> prefix $candidate")
                return it
            }
        }
        return null
    }


    internal fun normalizedModelKey(id: String): String =
        id.substringAfterLast('/').lowercase().replace('.', '-').replace('_', '-')

    internal fun buildStage2Index(registry: Map<String, ProviderEntry>): Map<String, ModelDevEntry> {
        val grouped = linkedMapOf<String, MutableList<ModelDevEntry>>()
        for (provKey in registry.keys.sorted()) {
            val prov = registry[provKey] ?: continue
            for (id in prov.models.keys.sorted()) {
                val entry = prov.models[id] ?: continue
                grouped.getOrPut(normalizedModelKey(id)) { mutableListOf() }.add(entry)
            }
        }
        return grouped.mapValues { (_, candidates) ->
            val declaring = candidates.filter { it.reasoningEffortValues != null }
            if (declaring.isEmpty()) return@mapValues candidates.first()
            val counts = declaring.groupingBy { it.reasoningEffortValues!! }.eachCount()
            var winner: List<String>? = null
            var winnerCount = 0
            for (c in declaring) {
                val n = counts[c.reasoningEffortValues!!] ?: 0
                if (n > winnerCount) {
                    winnerCount = n
                    winner = c.reasoningEffortValues
                }
            }
            declaring.firstOrNull { it.reasoningEffortValues == winner } ?: declaring.first()
        }
    }

    private fun stage2Index(registry: Map<String, ProviderEntry>): Map<String, ModelDevEntry> {
        val cached = cachedStage2Index
        if (cached != null && stage2IndexBuiltFrom == cacheTimestamp && stage2IndexRegistry === registry) return cached
        val built = buildStage2Index(registry)
        cachedStage2Index = built
        stage2IndexBuiltFrom = cacheTimestamp
        stage2IndexRegistry = registry
        Log.i(TAG, "stage-2 index built: ${built.size} normalized keys")
        return built
    }

    private const val MIN_PREFIX_SEGMENTS = 2

    internal fun prefixMatch(wanted: String, index: Map<String, ModelDevEntry>): ModelDevEntry? {
        val segments = wanted.split('-').toMutableList()
        if (segments.size <= MIN_PREFIX_SEGMENTS) return null
        while (segments.size > MIN_PREFIX_SEGMENTS) {
            segments.removeAt(segments.size - 1)
            index[segments.joinToString("-")]?.let { return it }
        }
        return null
    }

    internal fun normalizedPrefixes(wanted: String): List<String> {
        val segments = wanted.split('-').toMutableList()
        val out = mutableListOf<String>()
        while (segments.size >= MIN_PREFIX_SEGMENTS) {
            out.add(segments.joinToString("-"))
            segments.removeAt(segments.size - 1)
        }
        return out
    }


    internal fun isUnrecognizedProvider(providerName: String): Boolean =
        providerKeyMap[providerName].isNullOrEmpty()

    private fun aggregateIndex(registry: Map<String, ProviderEntry>): Map<String, ModelDevEntry> {
        val cached = cachedAggregateIndex
        if (cached != null && aggregateIndexBuiltFrom == cacheTimestamp && aggregateIndexRegistry === registry) return cached
        val built = buildAggregateIndex(registry)
        cachedAggregateIndex = built
        aggregateIndexBuiltFrom = cacheTimestamp
        aggregateIndexRegistry = registry
        Log.i(TAG, "aggregate index built: ${built.size} prefixes")
        return built
    }

    internal fun buildAggregateIndex(
        registry: Map<String, ProviderEntry>,
    ): Map<String, ModelDevEntry> {
        val grouped = mutableMapOf<String, MutableList<ModelDevEntry>>()
        for (provKey in registry.keys.sorted()) {
            val prov = registry[provKey] ?: continue
            for (modelId in prov.models.keys.sorted()) {
                val entry = prov.models[modelId] ?: continue
                for (prefix in (aliasCandidates(modelId) + normalizedPrefixes(normalizedModelKey(modelId))).distinct()) {
                    grouped.getOrPut(prefix) { mutableListOf() }.add(entry)
                }
            }
        }
        return grouped.mapValues { (prefix, records) -> aggregate(records, prefix) }
    }

    internal fun aggregate(records: List<ModelDevEntry>, id: String): ModelDevEntry {
        var maxContext: Int? = null
        var maxOutput: Int? = null
        var anyReasoning = false
        var sawReasoning = false
        val unionInput = linkedSetOf<String>()
        val unionOutput = linkedSetOf<String>()
        var interleaved: String? = null
        val effortUnion = mutableListOf<String>()
        var sawEffort = false
        var anyDeclaresNoEffort = false

        for (r in records) {
            r.contextWindow?.let { maxContext = maxOf(maxContext ?: it, it) }
            r.maxOutputTokens?.let { maxOutput = maxOf(maxOutput ?: it, it) }
            r.reasoning?.let {
                sawReasoning = true
                anyReasoning = anyReasoning || it
            }
            r.inputModalities?.let { unionInput.addAll(it) }
            r.outputModalities?.let { unionOutput.addAll(it) }
            if (interleaved == null) interleaved = r.interleavedField
            r.reasoningEffortValues?.let { values ->
                sawEffort = true
                for (v in values) if (v !in effortUnion) effortUnion.add(v)
            }
            if (r.declaresNoEffortTiers) anyDeclaresNoEffort = true
        }

        return ModelDevEntry(
            id = id,
            name = null,
            family = null,
            contextWindow = maxContext,
            maxOutputTokens = maxOutput,
            reasoning = if (sawReasoning) anyReasoning else null,
            interleavedField = interleaved,
            inputModalities = unionInput.takeIf { it.isNotEmpty() }?.sorted(),
            outputModalities = unionOutput.takeIf { it.isNotEmpty() }?.sorted(),
            reasoningEffortValues = if (sawEffort) effortUnion else null,
            declaresNoEffortTiers = anyDeclaresNoEffort && !sawEffort,
            releaseDate = null,
            outputCost = null,
        )
    }


    internal fun applyDevData(model: LLMModel, devModel: ModelDevEntry): LLMModel {
        val keepReported = model.limitsReportedByEndpoint == true
        return model.copy(
            contextWindow = if (keepReported) model.contextWindow ?: devModel.contextWindow
            else devModel.contextWindow ?: model.contextWindow,
            maxOutputTokens = if (keepReported) model.maxOutputTokens ?: devModel.maxOutputTokens
            else devModel.maxOutputTokens ?: model.maxOutputTokens,
            supportsReasoning = devModel.reasoning ?: model.supportsReasoning,
            interleavedReasoningField = devModel.interleavedField ?: model.interleavedReasoningField,
            inputModalities = devModel.inputModalities ?: model.inputModalities,
            outputModalities = devModel.outputModalities ?: model.outputModalities,
            reasoningEffortValues = devModel.reasoningEffortValues ?: model.reasoningEffortValues,
            declaresNoEffortTiers = if (devModel.declaresNoEffortTiers) true else model.declaresNoEffortTiers,
        )
    }


    private fun buildModels(provider: ProviderEntry): List<LLMModel> {
        return provider.models.values.mapNotNull { model ->
            val family = model.family?.lowercase() ?: ""
            if (family.contains("embedding") || family.contains("moderation")) return@mapNotNull null
            LLMModel(
                id = model.id,
                displayName = model.name ?: model.id,
                provider = provider.name ?: provider.id,
                contextWindow = model.contextWindow,
                maxOutputTokens = model.maxOutputTokens,
                supportsReasoning = model.reasoning,
                interleavedReasoningField = model.interleavedField,
                inputModalities = model.inputModalities,
                outputModalities = model.outputModalities,
                reasoningEffortValues = model.reasoningEffortValues,
                declaresNoEffortTiers = if (model.declaresNoEffortTiers) true else null,
            )
        }
    }


    private fun normalizedCandidates(url: String): List<String> {
        val stripped = stripTrailingSlash(url)
        val results = mutableListOf(stripped)
        if (stripped.endsWith("/v1")) {
            results.add(stripped.dropLast(3))
        } else {
            results.add("$stripped/v1")
        }
        return results
    }

    private fun stripTrailingSlash(s: String): String {
        var r = s
        while (r.endsWith("/")) r = r.dropLast(1)
        return r
    }

    private fun extractHost(urlString: String): String? {
        return try {
            URL(stripTrailingSlash(urlString)).host?.lowercase()
        } catch (_: Exception) {
            null
        }
    }


    fun registrySnapshot(): Map<String, ProviderEntry> = loadRegistry() ?: emptyMap()

    @Synchronized
    private fun loadRegistry(): Map<String, ProviderEntry>? {
        val cached = cachedRegistry
        if (cached != null && System.currentTimeMillis() - cacheTimestamp < CACHE_TTL_MS) {
            return cached
        }

        if (cached != null) {
            scheduleBackgroundRefresh()
            return cached
        }

        val diskResult = loadDiskCache()
        if (diskResult != null) {
            val (parsed, diskDate) = diskResult
            cachedRegistry = parsed
            cacheTimestamp = diskDate
            fetchedAt = diskDate
            if (System.currentTimeMillis() - diskDate >= CACHE_TTL_MS) {
                scheduleBackgroundRefresh()
            }
            return parsed
        }

        val bundled = loadBundledRegistry()
        if (bundled != null) {
            cachedRegistry = bundled
            cacheTimestamp = System.currentTimeMillis()
            scheduleBackgroundRefresh()
            return bundled
        }

        return null
    }

    private fun scheduleBackgroundRefresh() {
        if (!isRefreshing.compareAndSet(false, true)) return
        Thread {
            try {
                refreshFromNetwork()
            } finally {
                isRefreshing.set(false)
            }
        }.apply {
            name = com.openminis.app.diagnostics.ThreadNames.of("modelsdev")
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun refreshFromNetwork() {
        try {
            val request = Request.Builder().url(SOURCE_URL).build()
            val response = com.openminis.app.network.NetworkHttp.client(com.openminis.app.network.NetworkHttp.DEFAULT_TAG, client).newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "models.dev HTTP error: ${response.code}")
                response.close()
                return
            }
            val body = response.body?.string() ?: return
            response.close()

            val parsed = parseRegistry(body)
            if (parsed != null) {
                synchronized(this) {
                    cachedRegistry = parsed
                    cacheTimestamp = System.currentTimeMillis()
                    fetchedAt = cacheTimestamp
                }
                saveDiskCache(body)
                Log.d(TAG, "Background-refreshed models.dev registry: ${parsed.size} providers")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch models.dev: ${e.message}")
        }
    }


    internal fun parseRegistry(jsonStr: String): Map<String, ProviderEntry>? {
        return try {
            val json = JSONObject(jsonStr)
            val result = mutableMapOf<String, ProviderEntry>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val provObj = json.optJSONObject(key) ?: continue
                val entry = parseProviderEntry(key, provObj) ?: continue
                result[key] = entry
            }
            if (result.isEmpty()) null else result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse models.dev JSON: ${e.message}")
            null
        }
    }

    private fun parseProviderEntry(id: String, obj: JSONObject): ProviderEntry? {
        val name = obj.optString("name", "").ifEmpty { null }
        val api = obj.optString("api", "").ifEmpty { null }
        val modelsObj = obj.optJSONObject("models") ?: return ProviderEntry(id, name, api, emptyMap())

        val models = mutableMapOf<String, ModelDevEntry>()
        val modelKeys = modelsObj.keys()
        while (modelKeys.hasNext()) {
            val modelKey = modelKeys.next()
            val modelObj = modelsObj.optJSONObject(modelKey) ?: continue
            models[modelKey] = parseModelDevEntry(modelKey, modelObj)
        }
        return ProviderEntry(id, name, api, models)
    }

    private fun parseModelDevEntry(id: String, obj: JSONObject): ModelDevEntry {
        val name = obj.optString("name", "").ifEmpty { null }
        val family = obj.optString("family", "").ifEmpty { null }

        val limitObj = obj.optJSONObject("limit")
        val contextWindow = limitObj?.optInt("context", 0)?.takeIf { it > 0 }
        val maxOutputTokens = limitObj?.optInt("output", 0)?.takeIf { it > 0 }

        val reasoning = if (obj.has("reasoning")) obj.optBoolean("reasoning") else null

        var interleavedField: String? = null
        if (obj.has("interleaved")) {
            val interleaved = obj.opt("interleaved")
            when (interleaved) {
                is JSONObject -> interleavedField = interleaved.optString("field", "").ifEmpty { null }
                is Boolean -> if (interleaved) interleavedField = "reasoning_content"
                true -> interleavedField = "reasoning_content"
            }
        }

        val modalitiesObj = obj.optJSONObject("modalities")
        fun parseArray(key: String): List<String>? {
            val arr = modalitiesObj?.optJSONArray(key) ?: return null
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                arr.optString(i, "").takeIf { it.isNotEmpty() }?.let(out::add)
            }
            return out.takeIf { it.isNotEmpty() }
        }
        val inputModalities = parseArray("input")
        val outputModalities = parseArray("output")

        var reasoningEffortValues: List<String>? = null
        val reasoningOptions = obj.optJSONArray("reasoning_options")
        reasoningOptions?.let { arr ->
            for (i in 0 until arr.length()) {
                val opt = arr.optJSONObject(i) ?: continue
                if (opt.optString("type") != "effort") continue
                val vals = opt.optJSONArray("values") ?: continue
                val out = mutableListOf<String>()
                for (j in 0 until vals.length()) {
                    vals.optString(j, "").takeIf { it.isNotEmpty() }?.let { out.add(it.lowercase()) }
                }
                reasoningEffortValues = out.takeIf { it.isNotEmpty() }
                break
            }
        }
        val declaresNoEffortTiers = reasoningOptions != null && reasoningEffortValues == null

        return ModelDevEntry(
            id = id,
            name = name,
            family = family,
            contextWindow = contextWindow,
            maxOutputTokens = maxOutputTokens,
            reasoning = reasoning,
            interleavedField = interleavedField,
            inputModalities = inputModalities,
            outputModalities = outputModalities,
            reasoningEffortValues = reasoningEffortValues,
            declaresNoEffortTiers = declaresNoEffortTiers,
            releaseDate = obj.optString("release_date", "").ifEmpty { null },
            outputCost = obj.optJSONObject("cost")
                ?.optDouble("output", Double.NaN)
                ?.takeIf { !it.isNaN() },
        )
    }


    private fun loadBundledRegistry(): Map<String, ProviderEntry>? {
        val ctx = appContext ?: return null
        return try {
            val jsonStr = ctx.assets.open("models-dev-api.json").bufferedReader().readText()
            val parsed = parseRegistry(jsonStr)
            Log.d(TAG, "Loaded bundled models.dev registry: ${parsed?.size ?: 0} providers")
            parsed
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bundled models-dev-api.json: ${e.message}")
            null
        }
    }


    private fun getCacheFile(): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.cacheDir, "models-dev-cache")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "api.json")
    }

    private fun loadDiskCache(): Pair<Map<String, ProviderEntry>, Long>? {
        val file = getCacheFile() ?: return null
        if (!file.exists()) return null
        return try {
            val jsonStr = file.readText()
            val parsed = parseRegistry(jsonStr) ?: return null
            Pair(parsed, file.lastModified())
        } catch (_: Exception) {
            null
        }
    }

    private fun saveDiskCache(jsonStr: String) {
        val file = getCacheFile() ?: return
        try {
            file.writeText(jsonStr)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save disk cache: ${e.message}")
        }
    }


    data class ProviderEntry(
        val id: String,
        val name: String?,
        val api: String?,
        val models: Map<String, ModelDevEntry>,
    )

    data class ModelDevEntry(
        val id: String,
        val name: String?,
        val family: String?,
        val contextWindow: Int?,
        val maxOutputTokens: Int?,
        val reasoning: Boolean?,
        val interleavedField: String?,
        val inputModalities: List<String>?,
        val outputModalities: List<String>?,
        val reasoningEffortValues: List<String>?,
        val declaresNoEffortTiers: Boolean = false,
        val releaseDate: String?,
        val outputCost: Double?,
    )
}
