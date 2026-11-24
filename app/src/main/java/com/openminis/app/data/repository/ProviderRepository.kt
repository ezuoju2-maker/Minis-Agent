package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.openminis.app.backup.BackupSecrets
import com.openminis.app.backup.BackupCredentialRestore.Slot as CredentialSlot
import com.openminis.app.data.db.ProviderConfigDao
import com.openminis.app.data.db.ProviderConfigMetaKeys
import com.openminis.app.data.db.ProviderConfigSnapshot
import com.openminis.app.data.db.ProviderThinkingRuleEntity
import com.openminis.app.provider.thinking.ThinkingRule
import com.openminis.app.provider.thinking.ThinkingRuleCoding
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import com.openminis.app.data.db.ProviderDatabase
import com.openminis.app.data.db.compositeEntryKey
import com.openminis.app.data.db.toProviderConfig
import com.openminis.app.data.db.toSnapshot
import com.openminis.app.data.model.ImageEndpointMode
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentRoster
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.SystemVoiceIds
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.VoiceProviderTemplate
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.model.hasVoiceModality
import com.openminis.app.data.model.isVoiceCandidate
import com.openminis.app.data.model.isVoiceInputCandidate
import com.openminis.app.data.model.isVoiceOutputCandidate
import com.openminis.app.data.model.isVoiceTemplateSeedShape
import com.openminis.app.data.model.withInferredVoiceModality
import com.openminis.app.provider.ModelReleaseIndex
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.anthropic.AnthropicModelsApi
import com.openminis.app.provider.gemini.GeminiModelsApi
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.openrouter.OpenRouterModelsApi
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

private const val MODALITY_BIT_TEXT_IN = 1 shl 0
private const val MODALITY_BIT_TEXT_OUT = 1 shl 1
private const val MODALITY_BIT_IMG_IN = 1 shl 2
private const val MODALITY_BIT_PDF_IN = 1 shl 3
private const val MODALITY_BIT_AUD_IN = 1 shl 4
private const val MODALITY_BIT_VID_IN = 1 shl 5
private const val MODALITY_BIT_IMG_OUT = 1 shl 6
private const val MODALITY_BIT_AUD_OUT = 1 shl 7
private const val MODALITY_BIT_VID_OUT = 1 shl 8

class CodexDiscoveryAuthException(val status: Int) :
    Exception("Codex model discovery rejected the credential (HTTP $status)")

class ProviderRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("provider_config", Context.MODE_PRIVATE)

    private val providerDao: ProviderConfigDao =
        ProviderDatabase.getInstance(context).providerConfigDao()

    companion object {
        private const val SUB_AGENT_LOG = "SubAgents"

        private const val MODEL_CACHE_TTL_MS = 24 * 60 * 60 * 1000L

        private const val MODELS_REFRESH_WINDOW_MS = 6 * 60 * 60 * 1000L

        internal const val MODEL_ABSENCE_GRACE_MS = 7L * 24 * 60 * 60 * 1000

        private fun lastFetchKey(instanceId: String) = "modelsLastFetchAt_$instanceId"

        private const val KEY_LAST_USED_ENTRY = "lastUsedModelEntryId"

        fun normalizedShadowKey(baseURL: String?): String {
            var s = baseURL?.trim()?.lowercase() ?: return ""
            while (s.endsWith("/")) s = s.dropLast(1)
            if (s.endsWith("/v1")) s = s.dropLast(3)
            while (s.endsWith("/")) s = s.dropLast(1)
            return s
        }
    }

    private fun markInstanceFetched(instanceId: String) {
        prefs.edit().putLong(lastFetchKey(instanceId), System.currentTimeMillis()).apply()
    }

    private fun isInstanceStale(instanceId: String): Boolean {
        val last = prefs.getLong(lastFetchKey(instanceId), 0L)
        return last == 0L || (System.currentTimeMillis() - last) > MODEL_CACHE_TTL_MS
    }

    fun invalidateModelCache(instanceId: String) {
        prefs.edit().remove(lastFetchKey(instanceId)).apply()
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, "provider_secrets")
    }

    private val _config = MutableStateFlow(ProviderConfig())
    val config: StateFlow<ProviderConfig> = _config.asStateFlow()

    private val _configLoaded = MutableStateFlow(false)
    val configLoaded: StateFlow<Boolean> = _configLoaded.asStateFlow()

    private val loadScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    private val configLoadComplete = kotlinx.coroutines.CompletableDeferred<Unit>()

    suspend fun awaitConfigLoaded() = configLoadComplete.await()

    init {
        loadScope.launch {
            var adoptedByAsyncLoader = false
            try {
                val loaded = loadConfig()
                synchronized(configLock) {
                    if (!_configLoaded.value) {
                        _config.value = loaded
                        _configLoaded.value = true
                        adoptedByAsyncLoader = true
                        android.util.Log.i(
                            "ProviderRepo",
                            "[ProviderStore] async loader adopted ${loaded.instances.size} instances",
                        )
                    }
                }
                if (adoptedByAsyncLoader) loadAllThinkingRulesIntoCache()
            } catch (e: Exception) {
                android.util.Log.e(
                    "ProviderRepo",
                    "[ProviderStore] initial load failed; leaving config unloaded for retry: ${e.message}",
                )
            }
            if (!configLoadComplete.isCompleted) configLoadComplete.complete(Unit)
            // [T-android-provider-voice] Reconcile voice-template seeds after
            try {
                ensureVoiceTemplateModels()
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "[Voice] ensureVoiceTemplateModels failed: ${e.message}")
            }
        }
    }

    private val configLock = Any()

    private fun loadConfig(): ProviderConfig = runBlocking { loadConfigSuspending() }

    private suspend fun loadConfigSuspending(): ProviderConfig {
        val rawJson = prefs.getString("config", null)
        var daoReadFailed = false
        val instanceCount = try {
            providerDao.instanceCount()
        } catch (e: Exception) {
            android.util.Log.w("ProviderRepo", "[ProviderStore] DAO instanceCount failed: ${e.message}")
            daoReadFailed = true
            0
        }
        android.util.Log.i(
            "ProviderRepo",
            "[ProviderStore] load: dbInstances=$instanceCount daoFailed=$daoReadFailed " +
                "mirrorBytes=${rawJson?.length ?: -1}",
        )

        val (dbConfig, dbHashStored) = if (instanceCount > 0) {
            try {
                val snapshot = ProviderConfigSnapshot(
                    instances = providerDao.loadInstances(),
                    entries = providerDao.loadEntries(),
                    groups = providerDao.loadGroups(),
                    loopIds = providerDao.loadAgentLoopIds(),
                    meta = providerDao.loadMeta(),
                )
                val cfg = snapshot.toProviderConfig(json)
                val storedHash = snapshot.meta.firstOrNull {
                    it.key == ProviderConfigMetaKeys.JSON_SYNC_HASH
                }?.value
                cfg to storedHash
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "[ProviderStore] DB load failed, falling back to JSON: ${e.message}")
                daoReadFailed = true
                null to null
            }
        } else {
            null to null
        }

        if (dbConfig != null) {
            val liveHash = rawJson?.let(::hashJsonMirror)
            if (liveHash == dbHashStored) {
                return dbConfig
            }
            android.util.Log.i(
                "ProviderRepo",
                "[ProviderStore] hash mismatch (stored=${dbHashStored?.take(8)} live=${liveHash?.take(8)}) — re-importing JSON mirror",
            )
        }

        if (rawJson != null) {
            val parsed = try {
                json.decodeFromString<ProviderConfig>(rawJson)
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "[ProviderStore] JSON decode failed: ${e.message}")
                null
            }
            if (parsed != null) {
                val mirrored = try {
                    persistToDbAndMirror(parsed)
                } catch (e: Exception) {
                    android.util.Log.w("ProviderRepo", "[ProviderStore] JSON→DB import failed: ${e.message}")
                    parsed
                }
                val legacyLastUsed = prefs.getString(KEY_LAST_USED_ENTRY, null)
                if (legacyLastUsed != null && !legacyLastUsed.contains('/')) {
                    val rewritten = parsed.modelEntries
                        .firstOrNull { it.uuid == legacyLastUsed }
                        ?.let { compositeEntryKey(it.providerInstanceId, it.baseModel.id) }
                    if (rewritten != null) {
                        prefs.edit().putString(KEY_LAST_USED_ENTRY, rewritten).apply()
                        android.util.Log.i(
                            "ProviderRepo",
                            "[ProviderStore] lastUsedEntryId rewritten ${legacyLastUsed.take(8)} → $rewritten",
                        )
                    }
                }
                android.util.Log.i(
                    "ProviderRepo",
                    "[ProviderStore] migrated/synced ${mirrored.instances.size} instances " +
                        "${mirrored.modelEntries.size} entries from JSON → Room",
                )
                return mirrored
            }
        }

        if (dbConfig != null) {
            android.util.Log.w(
                "ProviderRepo",
                "[ProviderStore] mirror unreadable + re-import failed; keeping " +
                    "${dbConfig.instances.size} DB instances as authoritative",
            )
            return dbConfig
        }

        if (daoReadFailed) {
            android.util.Log.e(
                "ProviderRepo",
                "[ProviderStore] REFUSING empty config — DB read failed and JSON mirror " +
                    "unusable; not overwriting a possibly-populated store",
            )
            throw IllegalStateException(
                "Provider store unreadable (DB read failed, JSON mirror unusable) — " +
                    "refusing to load an empty config that would overwrite existing providers",
            )
        }
        return ProviderConfig()
    }

    private fun hashJsonMirror(str: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(str.toByteArray())
        return buildString(digest.size * 2) {
            for (b in digest) {
                val v = b.toInt() and 0xFF
                append(Character.forDigit(v shr 4, 16))
                append(Character.forDigit(v and 0xF, 16))
            }
        }
    }

    private suspend fun persistToDbAndMirror(config: ProviderConfig): ProviderConfig {
        val mirrorStr = json.encodeToString(ProviderConfig.serializer(), config)
        val mirrorHash = hashJsonMirror(mirrorStr)
        val snapshot = config.toSnapshot(json, jsonSyncHash = mirrorHash)
        providerDao.replaceAll(
            instances = snapshot.instances,
            entries = snapshot.entries,
            groups = snapshot.groups,
            loopIds = snapshot.loopIds,
            meta = snapshot.meta,
        )
        val mirrorWritten = prefs.edit().putString("config", mirrorStr).commit()
        if (!mirrorWritten) {
            android.util.Log.w(
                "ProviderRepo",
                "[ProviderStore] mirror commit() returned false — DB updated " +
                    "but JSON write rejected (disk full? prefs corruption?); " +
                    "DB remains authoritative",
            )
        }
        return snapshot.toProviderConfig(json)
    }

    internal fun ensureConfigLoaded() {
        if (_configLoaded.value) return
        synchronized(configLock) {
            if (_configLoaded.value) return
            val loaded = try {
                loadConfig()
            } catch (e: Exception) {
                android.util.Log.e(
                    "ProviderRepo",
                    "[ProviderStore] ensureConfigLoaded failed; staying unloaded for retry: ${e.message}",
                )
                return
            }
            _config.value = loaded
            _configLoaded.value = true
        }
        loadAllThinkingRulesIntoCache()
        if (!configLoadComplete.isCompleted) configLoadComplete.complete(Unit)
    }

    private fun saveConfig(config: ProviderConfig) {
        synchronized(configLock) {
            val canonical = try {
                runBlocking { persistToDbAndMirror(config) }
            } catch (e: Exception) {
                android.util.Log.e(
                    "ProviderRepo",
                    "[ProviderStore] saveConfig persistence failed; emitting in-memory only: ${e.message}",
                    e,
                )
                config
            }
            _config.value = canonical.copy(
                instances = canonical.instances.toMutableList(),
                modelEntries = canonical.modelEntries.toMutableList(),
                modelGroups = canonical.modelGroups.toMutableList(),
                agentLoopModelEntryIds = canonical.agentLoopModelEntryIds.toMutableList(),
                agentLoopGroupIds = canonical.agentLoopGroupIds.toMutableList(),
                revision = ProviderConfig.nextRevision(),
            )
        }
    }

    val instances: List<ProviderInstance>
        get() = _config.value.instances.toList()

    private fun workingCopy(): ProviderConfig {
        val live = _config.value
        return live.copy(
            instances = live.instances.toMutableList(),
            modelEntries = live.modelEntries.toMutableList(),
            modelGroups = live.modelGroups
                .map { g -> g.copy(memberEntryIds = g.memberEntryIds.toMutableList()) }
                .toMutableList(),
            agentLoopModelEntryIds = live.agentLoopModelEntryIds.toMutableList(),
            agentLoopGroupIds = live.agentLoopGroupIds.toMutableList(),
            subAgents = live.subAgents.toMutableList(),
        )
    }

    fun addInstance(instance: ProviderInstance): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.instances.add(instance)
        val shouldSeed = instance.credentialType == ProviderCredential.oauth ||
            !isThirdPartyOpenAICompat(instance)
        if (shouldSeed) {
            val entries = instance.providerType.builtInModels.map { model ->
                ModelEntry(providerInstanceId = instance.id, baseModel = model)
            }
            config.modelEntries.addAll(entries)
        } else {
            android.util.Log.i(
                "ProviderRepo",
                "[ModelList] addInstance: skip built-in seed for third-party OpenAI-compat base " +
                    "(label=${instance.label} base=${instance.effectiveBaseURL}) — " +
                    "models will populate from upstream /v1/models on refresh",
            )
        }
        val voiceSeeds = VoiceProviderTemplate.mockEntries(instance)
            .filter { seed ->
                config.modelEntries.none {
                    it.providerInstanceId == instance.id && it.baseModel.id == seed.baseModel.id
                }
            }
        if (voiceSeeds.isNotEmpty()) {
            config.modelEntries.addAll(voiceSeeds)
            android.util.Log.i(
                "ProviderRepo",
                "[Voice] addInstance seeded ${voiceSeeds.size} voice-template models for ${instance.label}",
            )
        }
        saveConfig(config)
        invalidateModelCache(instance.id)
    }

    fun ensureVoiceTemplateModels() = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        var changed = false
        for (instance in config.instances) {
            val tpl = VoiceProviderTemplate.template(instance.customBaseURL) ?: continue
            val templateById = tpl.mockModels.associateBy { it.id }
            val instanceEntries = config.modelEntries.filter { it.providerInstanceId == instance.id }
            val existingIds = instanceEntries.map { it.baseModel.id }.toSet()

            val staleSeedIds = instanceEntries
                .filter { it.baseModel.isVoiceTemplateSeedShape }
                .map { it.baseModel.id }
                .filter { it !in templateById }
            if (staleSeedIds.isNotEmpty()) {
                config.modelEntries.removeAll {
                    it.providerInstanceId == instance.id && it.baseModel.id in staleSeedIds
                }
                changed = true
                android.util.Log.i("ProviderRepo", "[VoiceTemplateMigrate] ${instance.label}: removed stale voice entries: ${staleSeedIds.sorted()}")
            }

            val toAdd = templateById.keys - existingIds
            if (toAdd.isNotEmpty()) {
                val newEntries = tpl.mockModels
                    .filter { it.id in toAdd }
                    .map { ModelEntry(providerInstanceId = instance.id, baseModel = it) }
                config.modelEntries.addAll(newEntries)
                changed = true
                android.util.Log.i("ProviderRepo", "[VoiceTemplateMigrate] ${instance.label}: added ${newEntries.size} new entries: ${toAdd.sorted()}")
            }

            for (i in config.modelEntries.indices) {
                val e = config.modelEntries[i]
                if (e.providerInstanceId != instance.id) continue
                val tplModel = templateById[e.baseModel.id] ?: continue
                if (e.baseModel.inputModalities == tplModel.inputModalities &&
                    e.baseModel.outputModalities == tplModel.outputModalities
                ) continue
                android.util.Log.i("ProviderRepo", "[VoiceTemplateMigrate] ${instance.label}: healing modality for ${e.baseModel.id}")
                config.modelEntries[i] = e.copy(baseModel = tplModel)
                changed = true
            }
        }
        if (changed) saveConfig(config)
    }

    private fun isThirdPartyOpenAICompat(instance: ProviderInstance): Boolean {
        if (instance.providerType != ProviderType.openAI) return false
        val custom = instance.customBaseURL?.lowercase() ?: return false
        val officialHosts = listOf("api.openai.com", "chatgpt.com")
        return officialHosts.none { custom.contains(it) }
    }

    fun updateInstance(instance: ProviderInstance): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val idx = config.instances.indexOfFirst { it.id == instance.id }
        if (idx >= 0) {
            val prior = config.instances[idx]
            if ((prior.customBaseURL != instance.customBaseURL ||
                    prior.appendV1Suffix != instance.appendV1Suffix) &&
                instance.imageEndpointResolved != null
            ) {
                instance.imageEndpointResolved = null
            }
            config.instances[idx] = instance
            saveConfig(config)
            if (prior.effectiveBaseURL != instance.effectiveBaseURL ||
                prior.credentialType != instance.credentialType ||
                prior.isEnabled != instance.isEnabled ||
                prior.useResponsesAPI != instance.useResponsesAPI
            ) {
                invalidateModelCache(instance.id)
            }
        }
    }

    fun removeInstance(instanceId: String): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        invalidateModelCache(instanceId)
        val config = workingCopy()
        val removedEntryIds = config.modelEntries
            .filter { it.providerInstanceId == instanceId }
            .map { it.id }
            .toSet()
        config.instances.removeAll { it.id == instanceId }
        config.modelEntries.removeAll { it.providerInstanceId == instanceId }

        if (removedEntryIds.isNotEmpty()) {
            for (group in config.modelGroups) {
                group.memberEntryIds.removeAll { it in removedEntryIds }
            }
            config.agentLoopModelEntryIds.removeAll { it in removedEntryIds }
        }
        val emptyGroupIds = config.modelGroups.filter { it.memberEntryIds.isEmpty() }.map { it.id }.toSet()
        if (emptyGroupIds.isNotEmpty()) {
            config.modelGroups.removeAll { it.id in emptyGroupIds }
            if (config.defaultPrimaryGroupId in emptyGroupIds) config.defaultPrimaryGroupId = null
            if (config.defaultSubGroupId in emptyGroupIds) config.defaultSubGroupId = null
        }

        saveConfig(config)
        deleteApiKey(instanceId)
        runCatching {
            runBlocking { providerDao.deleteThinkingRulesForInstance(instanceId) }
            ThinkingRuleResolver.setCustomRules(instanceId, emptyList())
        }
    }

    fun setImageEndpointResolved(instanceId: String, endpoint: ImageEndpointMode): Unit =
        synchronized(configLock) {
            ensureConfigLoaded()
            val config = workingCopy()
            val idx = config.instances.indexOfFirst { it.id == instanceId }
            if (idx < 0) return@synchronized
            if (config.instances[idx].imageEndpointResolved == endpoint) return@synchronized
            config.instances[idx] = config.instances[idx].copy(imageEndpointResolved = endpoint)
            saveConfig(config)
        }

    fun instance(id: String): ProviderInstance? =
        _config.value.instances.find { it.id == id }

    fun enabledInstances(providerType: ProviderType): List<ProviderInstance> =
        _config.value.instances.filter { it.providerType == providerType && it.isEnabled }

    fun entriesFor(instanceId: String): List<ModelEntry> =
        _config.value.modelEntries
            .filter { it.providerInstanceId == instanceId }
            .sortedByReleaseRank()

    fun visibleEntries(instanceId: String): List<ModelEntry> =
        _config.value.modelEntries
            .filter { it.providerInstanceId == instanceId && !it.isHidden }
            .sortedByReleaseRank()

    fun allVisibleEntries(): List<ModelEntry> =
        _config.value.let { config ->
            val enabledIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
            config.modelEntries
                .filter { it.providerInstanceId in enabledIds && !it.isHidden }
                .sortedByReleaseRank()
        }

    private fun List<ModelEntry>.sortedByReleaseRank(): List<ModelEntry> =
        ModelEntryRanking.sortedByReleaseRank(this)


    var lastUsedEntryId: String?
        get() = prefs.getString(KEY_LAST_USED_ENTRY, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_LAST_USED_ENTRY) else putString(KEY_LAST_USED_ENTRY, value)
            }.apply()
        }

    fun lastUsedVisibleEntry(): ModelEntry? {
        val id = lastUsedEntryId ?: return null
        return allVisibleEntries().firstOrNull { it.id == id }
    }

    fun newestProviderNewestTextEntry(): ModelEntry? {
        val config = _config.value
        val enabledProviders = config.instances
            .filter { it.isEnabled }
            .sortedByDescending { it.createdAt }
        for (instance in enabledProviders) {
            val textEntry = config.modelEntries
                .filter { it.providerInstanceId == instance.id && !it.isHidden && it.model.isTextOutput }
                .lastOrNull()
            if (textEntry != null) return textEntry
        }
        return null
    }

    fun enabledMemberEntries(group: ModelGroup): List<ModelEntry> {
        val config = _config.value
        val enabledIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
        return group.memberEntryIds.mapNotNull { entryId ->
            val entry = config.modelEntries.find { it.id == entryId }
            if (entry != null &&
                entry.providerInstanceId in enabledIds &&
                !entry.isUnavailableFromProvider
            ) {
                entry
            } else {
                null
            }
        }
    }

    fun firstEnabledMemberEntry(group: ModelGroup): ModelEntry? =
        enabledMemberEntries(group).firstOrNull()

    fun hasAnyCredential(instance: ProviderInstance): Boolean {
        if (usableApiKey(instance) != null) return true
        if (com.openminis.app.auth.OAuthManager.needsReauth(context, instance.id)) return false
        val mgr = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
        if (mgr != null && mgr.isAuthenticated()) return true
        return com.openminis.app.auth.OAuthManager.hasStoredCredential(context, instance.id)
    }

    fun availableMemberEntries(group: ModelGroup): List<ModelEntry> {
        val config = _config.value
        return group.memberEntryIds.mapNotNull { entryId ->
            val entry = config.modelEntries.find { it.id == entryId } ?: return@mapNotNull null
            if (entry.isHidden) return@mapNotNull null
            val instance = config.instances.find { it.id == entry.providerInstanceId }
                ?: return@mapNotNull null
            if (!instance.isEnabled) return@mapNotNull null
            if (!hasAnyCredential(instance)) return@mapNotNull null
            entry
        }
    }

    fun resolveTitleSubEntry(): ModelEntry? {
        val subGroupId = defaultSubGroupId ?: return null
        val group = group(subGroupId) ?: return null
        return availableMemberEntries(group).firstOrNull()
    }

    fun isEntryProviderEnabled(entryId: String): Boolean {
        val config = _config.value
        val entry = config.modelEntries.find { it.id == entryId } ?: return false
        val instance = config.instances.find { it.id == entry.providerInstanceId } ?: return false
        return instance.isEnabled
    }

    fun replaceEntries(instanceId: String, models: List<LLMModel>) = synchronized(configLock) {
        ensureConfigLoaded()
        android.util.Log.i(
            "ProviderRepo",
            "[Restore][DIAG] replaceEntries ENTER instanceId=$instanceId models=" +
                models.joinToString { m -> "(id=${m.id}, displayName=${m.displayName})" },
        )
        val config = workingCopy()
        val existing = config.modelEntries.filter { it.providerInstanceId == instanceId }
        val existingEntryIds = existing.map { it.id }.toSet()

        android.util.Log.i(
            "ProviderRepo",
            "[Restore][DIAG] replaceEntries existing(before) instanceId=$instanceId count=${existing.size}: " +
                existing.joinToString { e ->
                    "(baseModel.id=${e.baseModel.id}, displayName=${e.baseModel.displayName}, uuid=${e.id}, isCustom=${e.isCustom})"
                },
        )

        val existingByModelId = mutableMapOf<String, ModelEntry>()
        for (entry in existing) {
            val key = entry.baseModel.id
            val current = existingByModelId[key]
            if (current == null || current.isCustom) {
                existingByModelId[key] = entry
            }
        }

        val refreshedModelIds = models.map { it.id }.toSet()
        val instanceBaseURL = config.instances.firstOrNull { it.id == instanceId }?.customBaseURL
        val voiceTemplate = VoiceProviderTemplate.template(instanceBaseURL)
        val templateVoiceModelById = voiceTemplate?.mockModels
            ?.filter { it.hasVoiceModality }
            ?.associateBy { it.id }
            ?: emptyMap()
        val newEntries = models.map { model ->
            val prior = existingByModelId[model.id]
            android.util.Log.i(
                "ProviderRepo",
                "[Restore][DIAG] replaceEntries lookup key=${model.id} hit=${prior != null}" +
                    (if (prior != null) " prior.baseModel.id=${prior.baseModel.id}" else ""),
            )
            var resolved = model.withInferredVoiceModality()
            templateVoiceModelById[model.id]?.let { tplModel ->
                resolved = resolved.copy(
                    inputModalities = tplModel.inputModalities,
                    outputModalities = tplModel.outputModalities,
                )
            }
            val resolvedDisplayName = when {
                model.displayName.isBlank() || model.displayName == model.id ->
                    prior?.baseModel?.displayName
                        ?.takeIf { it.isNotBlank() && it != model.id }
                        ?: model.displayName
                else -> model.displayName
            }
            resolved = resolved.copy(displayName = resolvedDisplayName)
            ModelEntry(
                providerInstanceId = instanceId,
                baseModel = resolved,
                overrides = prior?.overrides ?: ModelOverrides(),
                isCustom = false,
                isHidden = prior?.isHidden ?: false,
                uuid = prior?.id ?: java.util.UUID.randomUUID().toString(),
                userModifiedAt = prior?.userModifiedAt,
                absentSince = null,
            )
        }

        val remainingCustom = existing.filter { it.isCustom && it.baseModel.id !in refreshedModelIds }
        if (remainingCustom.isNotEmpty()) {
            android.util.Log.i(
                "ProviderRepo",
                "[Restore][DIAG] replaceEntries remainingCustom BRANCH HIT instanceId=$instanceId count=${remainingCustom.size}: " +
                    remainingCustom.joinToString { e -> "(baseModel.id=${e.baseModel.id}, uuid=${e.id})" },
            )
        }

        val preservedVoice = existing.filter { e ->
            !e.isCustom &&
                e.baseModel.id !in refreshedModelIds &&
                e.baseModel.id in templateVoiceModelById
        }
        if (preservedVoice.isNotEmpty()) {
            android.util.Log.i(
                "ProviderRepo",
                "[Restore][DIAG] replaceEntries preservedVoice BRANCH HIT instanceId=$instanceId count=${preservedVoice.size}: " +
                    preservedVoice.joinToString { e -> "(baseModel.id=${e.baseModel.id}, uuid=${e.id})" },
            )
        }

        val nowMs = System.currentTimeMillis()
        val absentCatalog = existing.filter { e ->
            !e.isCustom &&
                e.baseModel.id !in refreshedModelIds &&
                e.baseModel.id !in templateVoiceModelById
        }
        val keptAbsent = mutableListOf<ModelEntry>()
        val expiredAbsent = mutableListOf<ModelEntry>()
        for (entry in absentCatalog) {
            val since = entry.absentSince ?: nowMs
            if (nowMs - since > MODEL_ABSENCE_GRACE_MS) {
                expiredAbsent.add(entry)
            } else {
                keptAbsent.add(entry.copy(absentSince = since))
            }
        }
        if (keptAbsent.isNotEmpty()) {
            android.util.Log.i(
                "ProviderRepo",
                "[ModelList] replaceEntries kept ${keptAbsent.size} unlisted catalog entr(ies) within the " +
                    "${MODEL_ABSENCE_GRACE_MS / 3_600_000}h grace window: ${keptAbsent.map { it.baseModel.id }.take(10)}",
            )
        }
        if (expiredAbsent.isNotEmpty()) {
            android.util.Log.i(
                "ProviderRepo",
                "[ModelList] replaceEntries dropped ${expiredAbsent.size} catalog entr(ies) absent beyond the " +
                    "grace window: ${expiredAbsent.map { it.baseModel.id }.take(10)}",
            )
        }

        val entriesToRemove = config.modelEntries.filter { it.providerInstanceId == instanceId }
        android.util.Log.i(
            "ProviderRepo",
            "[Restore][DIAG] replaceEntries removeAll about to remove instanceId=$instanceId count=${entriesToRemove.size} ids=" +
                entriesToRemove.map { it.baseModel.id },
        )
        config.modelEntries.removeAll { it.providerInstanceId == instanceId }
        val remainingAfterRemove = config.modelEntries.count { it.providerInstanceId == instanceId }
        android.util.Log.i(
            "ProviderRepo",
            "[Restore][DIAG] replaceEntries post-removeAll instanceId=$instanceId remaining=$remainingAfterRemove (expected 0)",
        )
        config.modelEntries.addAll(newEntries)
        config.modelEntries.addAll(remainingCustom)
        config.modelEntries.addAll(keptAbsent)
        if (preservedVoice.isNotEmpty()) {
            config.modelEntries.addAll(preservedVoice)
            android.util.Log.i("ProviderRepo", "[ModelList] replaceEntries preserved ${preservedVoice.size} voice-template seed entries: ${preservedVoice.map { it.baseModel.id }.take(10)}")
        }
        val finalForInstance = config.modelEntries.filter { it.providerInstanceId == instanceId }
        android.util.Log.i(
            "ProviderRepo",
            "[Restore][DIAG] replaceEntries FINAL instanceId=$instanceId count=${finalForInstance.size}: " +
                finalForInstance.joinToString { e ->
                    "(baseModel.id=${e.baseModel.id}, displayName=${e.baseModel.displayName}, uuid=${e.id})"
                },
        )

        val survivingEntryIds = config.modelEntries.map { it.id }.toSet()
        val prunedEntryIds = existingEntryIds - survivingEntryIds
        if (prunedEntryIds.isNotEmpty()) {
            val suspiciousShrink = existing.size >= 4 && models.size * 2 < existing.size
            if (suspiciousShrink) {
                android.util.Log.w("ProviderRepo", "[ModelList] replaceEntries SUSPICIOUS SHRINK before=${existing.size} after=${models.size} — group references PRESERVED as stale")
            } else {
                for (i in config.modelGroups.indices) {
                    val before = config.modelGroups[i].memberEntryIds.size
                    config.modelGroups[i].memberEntryIds.removeAll { it in prunedEntryIds }
                    val removed = before - config.modelGroups[i].memberEntryIds.size
                    if (removed > 0) {
                        android.util.Log.i("ProviderRepo", "[ModelList] replaceEntries pruned $removed stale refs from group '${config.modelGroups[i].name}'")
                    }
                }
                val agentBefore = config.agentLoopModelEntryIds.size
                config.agentLoopModelEntryIds.removeAll { it in prunedEntryIds }
                val agentRemoved = agentBefore - config.agentLoopModelEntryIds.size
                if (agentRemoved > 0) {
                    android.util.Log.i("ProviderRepo", "[ModelList] replaceEntries pruned $agentRemoved stale agent-loop entry pins")
                }
            }
        }

        saveConfig(config)
        markInstanceFetched(instanceId)
    }


    fun addEntry(entry: ModelEntry): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        if (!entry.isCustom) {
            val exists = config.modelEntries.any {
                it.providerInstanceId == entry.providerInstanceId && it.baseModel.id == entry.baseModel.id
            }
            if (exists) return@addEntry
        }
        config.modelEntries.add(entry)
        saveConfig(config)
    }

    fun updateEntry(entry: ModelEntry): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val idx = config.modelEntries.indexOfFirst { it.id == entry.id }
        if (idx >= 0) {
            config.modelEntries[idx] = entry.copy(userModifiedAt = System.currentTimeMillis())
            saveConfig(config)
        }
    }

    fun removeEntry(entryId: String): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.modelEntries.removeAll { it.id == entryId }
        config.modelGroups.forEach { group ->
            group.memberEntryIds.removeAll { it == entryId }
        }
        config.agentLoopModelEntryIds.removeAll { it == entryId }
        saveConfig(config)
    }


    // --- Model Group management ---
    fun addGroup(group: ModelGroup): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.modelGroups.add(group)
        saveConfig(config)
    }

    fun updateGroup(group: ModelGroup): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val idx = config.modelGroups.indexOfFirst { it.id == group.id }
        if (idx >= 0) {
            config.modelGroups[idx] = group.copy(
                memberEntryIds = group.memberEntryIds.toMutableList(),
            )
            saveConfig(config)
        }
    }

    fun removeGroup(groupId: String): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.modelGroups.removeAll { it.id == groupId }
        if (config.defaultPrimaryGroupId == groupId) {
            config.defaultPrimaryGroupId = null
        }
        if (config.defaultSubGroupId == groupId) {
            config.defaultSubGroupId = null
        }
        if (config.voiceInputGroupId == groupId) {
            config.voiceInputGroupId = null
        }
        if (config.voiceOutputGroupId == groupId) {
            config.voiceOutputGroupId = null
        }
        if (config.visionGroupId == groupId) {
            config.visionGroupId = null
        }
        config.agentLoopGroupIds.removeAll { it == groupId }
        config.subAgents.forEachIndexed { i, def ->
            if (def.modelGroupId == groupId) {
                config.subAgents[i] = def.copy(modelGroupId = null)
            }
        }
        saveConfig(config)
    }

    fun setAgentLoopEntryIds(ids: List<String>): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.agentLoopModelEntryIds.clear()
        config.agentLoopModelEntryIds.addAll(ids.distinct())
        saveConfig(config)
    }

    fun setAgentLoopGroupIds(ids: List<String>): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.agentLoopGroupIds.clear()
        config.agentLoopGroupIds.addAll(ids.distinct())
        saveConfig(config)
    }


    fun addAgentLoopEntry(entryId: String) {
        val cur = _config.value.agentLoopModelEntryIds.toList()
        if (entryId in cur) return
        setAgentLoopEntryIds(cur + entryId)
    }

    fun removeAgentLoopEntry(entryId: String) {
        val cur = _config.value.agentLoopModelEntryIds.toList()
        if (entryId !in cur) return
        setAgentLoopEntryIds(cur.filterNot { it == entryId })
    }

    fun addAgentLoopGroup(groupId: String) {
        val cur = _config.value.agentLoopGroupIds.toList()
        if (groupId in cur) return
        setAgentLoopGroupIds(cur + groupId)
    }

    fun removeAgentLoopGroup(groupId: String) {
        val cur = _config.value.agentLoopGroupIds.toList()
        if (groupId !in cur) return
        setAgentLoopGroupIds(cur.filterNot { it == groupId })
    }

    fun reorderAgentLoopEntries(newOrder: List<String>) {
        val cur = _config.value.agentLoopModelEntryIds.toSet()
        if (newOrder.toSet() != cur) return
        setAgentLoopEntryIds(newOrder)
    }

    fun reorderAgentLoopGroups(newOrder: List<String>) {
        val cur = _config.value.agentLoopGroupIds.toSet()
        if (newOrder.toSet() != cur) return
        setAgentLoopGroupIds(newOrder)
    }

    fun reorderInstances(newOrder: List<String>): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val current = config.instances.toList()
        if (current.isEmpty()) return

        val byId = current.associateBy { it.id }
        val seen = LinkedHashSet<String>()
        val reordered = ArrayList<ProviderInstance>(current.size)
        for (id in newOrder) {
            val inst = byId[id] ?: continue
            if (!seen.add(id)) continue
            reordered.add(inst)
        }
        for (inst in current) {
            if (seen.add(inst.id)) reordered.add(inst)
        }

        if (reordered.map { it.id } == current.map { it.id }) return

        synchronized(configLock) {
            config.instances.clear()
            config.instances.addAll(reordered)
            saveConfig(config)
        }
    }

    fun reorderModelGroups(newOrder: List<String>): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val current = config.modelGroups.toList()
        if (current.isEmpty()) return

        val byId = current.associateBy { it.id }
        val seen = LinkedHashSet<String>()
        val reordered = ArrayList<ModelGroup>(current.size)
        for (id in newOrder) {
            val group = byId[id] ?: continue
            if (!seen.add(id)) continue
            reordered.add(group)
        }
        for (group in current) {
            if (seen.add(group.id)) reordered.add(group)
        }

        if (reordered.map { it.id } == current.map { it.id }) return

        synchronized(configLock) {
            config.modelGroups.clear()
            config.modelGroups.addAll(reordered)
            saveConfig(config)
        }
    }

    fun resolvedAgentLoopEntries(): List<ModelEntry> {
        val config = _config.value
        val enabledIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<ModelEntry>()

        fun consider(entry: ModelEntry) {
            if (entry.providerInstanceId !in enabledIds) return
            if (!seen.add(entry.id)) return
            out.add(entry)
        }

        for (id in config.agentLoopModelEntryIds) {
            config.modelEntries.find { it.id == id }?.let(::consider)
        }
        for (gid in config.agentLoopGroupIds) {
            val group = config.modelGroups.find { it.id == gid } ?: continue
            for (memberId in group.memberEntryIds) {
                config.modelEntries.find { it.id == memberId }?.let(::consider)
            }
        }
        return out
    }

    fun group(id: String): ModelGroup? =
        _config.value.modelGroups.find { it.id == id }


    val subAgents: List<SubAgentDefinition>
        get() = SubAgentRoster.normalize(_config.value.subAgents) { msg ->
            AppLogger.info(SUB_AGENT_LOG, msg)
        }

    fun subAgent(id: String): SubAgentDefinition? = subAgents.find { it.id == id }

    fun upsertSubAgent(def: SubAgentDefinition): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val stamped = def.copy(updatedAt = System.currentTimeMillis())
        val idx = config.subAgents.indexOfFirst { it.id == stamped.id }
        if (idx >= 0) config.subAgents[idx] = stamped else config.subAgents.add(stamped)
        val normalized = SubAgentRoster.normalize(config.subAgents) { AppLogger.info(SUB_AGENT_LOG, it) }
        config.subAgents.clear()
        config.subAgents.addAll(normalized)
        saveConfig(config)
    }

    fun deleteSubAgent(id: String): Unit = synchronized(configLock) {
        if (id == SubAgentDefinition.BUILT_IN_ID) return
        ensureConfigLoaded()
        val config = workingCopy()
        config.subAgents.removeAll { it.id == id }
        val normalized = SubAgentRoster.normalize(config.subAgents) { AppLogger.info(SUB_AGENT_LOG, it) }
        config.subAgents.clear()
        config.subAgents.addAll(normalized)
        saveConfig(config)
    }

    fun reorderSubAgents(orderedIds: List<String>): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val byId = config.subAgents.associateBy { it.id }
        val reordered = mutableListOf<SubAgentDefinition>()
        orderedIds.forEach { id -> byId[id]?.let { reordered.add(it) } }
        config.subAgents.forEach { if (it.id !in orderedIds) reordered.add(it) }
        reordered.forEachIndexed { i, def -> reordered[i] = def.copy(sortOrder = i) }
        val normalized = SubAgentRoster.normalize(reordered) { AppLogger.info(SUB_AGENT_LOG, it) }
        config.subAgents.clear()
        config.subAgents.addAll(normalized)
        saveConfig(config)
    }

    var defaultPrimaryGroupId: String?
        get() = _config.value.defaultPrimaryGroupId
        set(value) = synchronized(configLock) {
            val config = workingCopy()
            config.defaultPrimaryGroupId = value
            saveConfig(config)
        }

    var defaultSubGroupId: String?
        get() = _config.value.defaultSubGroupId
        set(value) = synchronized(configLock) {
            val config = workingCopy()
            config.defaultSubGroupId = value
            saveConfig(config)
        }


    var voiceInputGroupId: String?
        get() = _config.value.voiceInputGroupId
        set(value) = synchronized(configLock) {
            ensureConfigLoaded()
            val config = workingCopy()
            config.voiceInputGroupId = value
            saveConfig(config)
        }

    var voiceOutputGroupId: String?
        get() = _config.value.voiceOutputGroupId
        set(value) = synchronized(configLock) {
            ensureConfigLoaded()
            val config = workingCopy()
            config.voiceOutputGroupId = value
            saveConfig(config)
        }


    var visionGroupId: String?
        get() = _config.value.visionGroupId
        set(value) = synchronized(configLock) {
            ensureConfigLoaded()
            val config = workingCopy()
            config.visionGroupId = value
            saveConfig(config)
        }

    fun hasVisionGroupConfigured(): Boolean {
        val gid = _config.value.visionGroupId ?: return false
        return _config.value.modelGroups.any { it.id == gid }
    }

    fun visionGroupName(): String? {
        val gid = _config.value.visionGroupId ?: return null
        return _config.value.modelGroups.find { it.id == gid }?.name
    }

    fun resolveVisionCandidates(loadBalanceSeed: Int = 0): List<Pair<ProviderInstance, ModelEntry>> {
        ensureConfigLoaded()
        val config = _config.value

        fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
            if (!inst.isEnabled || !entry.model.hasImageInput) return null
            return inst to entry
        }

        val gid = config.visionGroupId ?: return emptyList()
        val group = config.modelGroups.find { it.id == gid } ?: return emptyList()
        var members = group.memberEntryIds.mapNotNull { providerEntry(it) }
        if (group.strategy == RoutingStrategy.loadBalance && members.size > 1) {
            val offset = kotlin.math.abs(loadBalanceSeed) % members.size
            members = members.drop(offset) + members.take(offset)
        }
        val out = mutableListOf<Pair<ProviderInstance, ModelEntry>>()
        for (m in members) {
            if (out.none { it.second.id == m.second.id }) out.add(m)
        }
        return out
    }


    fun thinkingRules(instanceId: String): List<ThinkingRule> = runBlocking {
        runCatching { providerDao.loadThinkingRules(instanceId).map { ThinkingRuleCoding.toRule(it) } }
            .getOrDefault(emptyList())
    }

    fun thinkingRuleIds(instanceId: String): List<String> = runBlocking {
        runCatching { providerDao.loadThinkingRules(instanceId).map { it.id } }.getOrDefault(emptyList())
    }

    fun firstModelId(instanceId: String): String? {
        ensureConfigLoaded()
        return _config.value.modelEntries.firstOrNull { it.providerInstanceId == instanceId }?.model?.id
    }

    fun loadAllThinkingRulesIntoCache() {
        runCatching {
            val rows = runBlocking { providerDao.loadAllThinkingRules() }
            val byInstance = rows.groupBy { it.providerInstanceId }
                .mapValues { (_, rs) -> rs.sortedBy { it.sortOrder }.map { ThinkingRuleCoding.toRule(it) } }
            ThinkingRuleResolver.setAllCustomRules(byInstance)
        }
    }

    private fun republishThinkingCache(instanceId: String) {
        ThinkingRuleResolver.setCustomRules(instanceId, thinkingRules(instanceId))
    }

    fun saveThinkingRule(instanceId: String, rule: ThinkingRule, id: String? = null): String = runBlocking {
        val existing = providerDao.loadThinkingRules(instanceId).toMutableList()
        val ruleId = id ?: java.util.UUID.randomUUID().toString()
        val idx = existing.indexOfFirst { it.id == ruleId }
        if (idx >= 0) {
            existing[idx] = ThinkingRuleCoding.toEntity(rule, ruleId, instanceId, existing[idx].sortOrder)
        } else {
            existing.add(0, ThinkingRuleCoding.toEntity(rule, ruleId, instanceId, 0))
        }
        val renumbered = existing.mapIndexed { i, e -> e.copy(sortOrder = i) }
        providerDao.replaceThinkingRules(instanceId, renumbered)
        republishThinkingCache(instanceId)
        ruleId
    }

    fun deleteThinkingRule(instanceId: String, id: String) = runBlocking {
        providerDao.deleteThinkingRule(id)
        val survivors = providerDao.loadThinkingRules(instanceId)
            .sortedBy { it.sortOrder }
            .mapIndexed { i, e -> e.copy(sortOrder = i) }
        providerDao.replaceThinkingRules(instanceId, survivors)
        republishThinkingCache(instanceId)
    }

    fun reorderThinkingRules(instanceId: String, orderedIds: List<String>) = runBlocking {
        val byId = providerDao.loadThinkingRules(instanceId).associateBy { it.id }
        val reordered = orderedIds.mapNotNull { byId[it] }
            .mapIndexed { i, e -> e.copy(sortOrder = i) }
        val omitted = byId.values.filter { it.id !in orderedIds }.map { it }
        providerDao.replaceThinkingRules(instanceId, reordered + omitted)
        republishThinkingCache(instanceId)
    }

    fun builtInThinkingRulesForDisplay(instanceId: String): List<ThinkingRule> {
        ensureConfigLoaded()
        val config = _config.value
        val inst = config.instances.find { it.id == instanceId } ?: return emptyList()
        val base = (inst.effectiveBaseURL ?: "").lowercase()
        val ctx = com.openminis.app.provider.thinking.ThinkingResolveContext(
            modelId = "",
            supportsReasoning = null,
            declaredEffortValues = null,
            level = com.openminis.app.data.model.ThinkingLevel.OFF,
            maxTokens = 0,
            isOpenRouter = base.contains("openrouter.ai"),
            usesUnifiedReasoningEffort = base.contains("volces") || base.contains("ark.") || base.contains("venice.ai"),
            isMistral = base.contains("mistral.ai"),
            isDashScope = base.contains("dashscope"),
            offEffort = null,
        )
        val modelIds = config.modelEntries.filter { it.providerInstanceId == instanceId }.map { it.model.id }
        return ThinkingRuleResolver.builtInRules(ctx).filter { rule ->
            when (rule.scope) {
                is ThinkingRule.Scope.AllModels -> true
                is ThinkingRule.Scope.ModelPattern ->
                    modelIds.isEmpty() || modelIds.any { rule.scope.matches(it) }
            }
        }
    }

    fun ensureDefaultVoiceInputGroup(): String? = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.voiceInputGroupId?.let { gid ->
            if (config.modelGroups.any { it.id == gid }) return gid
        }
        val sentinel = SystemVoiceIds.BUILTIN_PROVIDER_ID
        val group = ModelGroup(
            name = "Voice Input",
            memberEntryIds = mutableListOf(
                "$sentinel/${SystemVoiceIds.SYSTEM_ASR_ONLINE}",
                "$sentinel/${SystemVoiceIds.SYSTEM_ASR_OFFLINE}",
            ),
        )
        config.modelGroups.add(group)
        config.voiceInputGroupId = group.id
        saveConfig(config)
        android.util.Log.i("ProviderRepo", "[Voice] auto-created default Voice Input group ${group.id.take(8)} [System ASR online+offline]")
        return group.id
    }

    fun ensureDefaultVoiceOutputGroup(): String? = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.voiceOutputGroupId?.let { gid ->
            if (config.modelGroups.any { it.id == gid }) return gid
        }
        val sentinel = SystemVoiceIds.BUILTIN_PROVIDER_ID
        val group = ModelGroup(
            name = "Voice Output",
            memberEntryIds = mutableListOf("$sentinel/${SystemVoiceIds.SYSTEM_TTS}"),
        )
        config.modelGroups.add(group)
        config.voiceOutputGroupId = group.id
        saveConfig(config)
        android.util.Log.i("ProviderRepo", "[Voice] auto-created default Voice Output group ${group.id.take(8)} [System Voice (Auto)]")
        return group.id
    }

    var voiceInputOverrideEntryId: String?
        get() = prefs.getString("voice.input.overrideEntryId", null)
        set(value) {
            prefs.edit().putString("voice.input.overrideEntryId", value).apply()
            synchronized(configLock) {
                val config = _config.value
                _config.value = config.copy(revision = ProviderConfig.nextRevision())
            }
        }

    data class VoiceInputChoice(
        val systemPreferOffline: Boolean?,
        val entry: Pair<ProviderInstance, ModelEntry>?,
    ) {
        val isSystem: Boolean get() = entry == null
    }

    fun resolveVoiceInputChoice(): VoiceInputChoice {
        ensureConfigLoaded()
        val config = _config.value

        fun systemChoice(memberId: String): VoiceInputChoice = VoiceInputChoice(
            systemPreferOffline = memberId.endsWith("/${SystemVoiceIds.SYSTEM_ASR_OFFLINE}"),
            entry = null,
        )

        fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
            if (!inst.isEnabled || !entry.model.isVoiceInputCandidate) return null
            return inst to entry
        }

        voiceInputOverrideEntryId?.let { override ->
            if (override.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return systemChoice(override)
            providerEntry(override)?.let { return VoiceInputChoice(null, it) }
        }
        val gid = config.voiceInputGroupId
        val group = gid?.let { g -> config.modelGroups.find { it.id == g } }
        if (group != null) {
            for (memberId in group.memberEntryIds) {
                if (memberId.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return systemChoice(memberId)
                providerEntry(memberId)?.let { return VoiceInputChoice(null, it) }
            }
        }
        return VoiceInputChoice(systemPreferOffline = null, entry = null)
    }

    fun voiceInputGroupName(): String? {
        val gid = _config.value.voiceInputGroupId ?: return null
        return _config.value.modelGroups.find { it.id == gid }?.name
    }

    fun resolveVoiceInputEntry(): Pair<ProviderInstance, ModelEntry>? =
        resolveVoiceInputChoice().entry

    fun resolveVoiceInputCandidates(loadBalanceSeed: Int = 0): List<Pair<ProviderInstance, ModelEntry>> {
        ensureConfigLoaded()
        val config = _config.value

        fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
            if (!inst.isEnabled || !entry.model.isVoiceInputCandidate) return null
            return inst to entry
        }

        val out = mutableListOf<Pair<ProviderInstance, ModelEntry>>()
        voiceInputOverrideEntryId?.let { override ->
            if (override.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return emptyList()
            providerEntry(override)?.let { out.add(it) }
        }
        val gid = config.voiceInputGroupId
        val group = gid?.let { g -> config.modelGroups.find { it.id == g } }
        if (group != null) {
            var members = group.memberEntryIds
                .filter { !it.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID) }
                .mapNotNull { providerEntry(it) }
            if (group.strategy == RoutingStrategy.loadBalance && members.size > 1) {
                val offset = kotlin.math.abs(loadBalanceSeed) % members.size
                members = members.drop(offset) + members.take(offset)
            }
            for (m in members) {
                if (out.none { it.second.id == m.second.id }) out.add(m)
            }
        }
        return out
    }


    var voiceOutputOverrideEntryId: String?
        get() = prefs.getString("voice.output.overrideEntryId", null)
        set(value) {
            prefs.edit().putString("voice.output.overrideEntryId", value).apply()
            synchronized(configLock) {
                val config = _config.value
                _config.value = config.copy(revision = ProviderConfig.nextRevision())
            }
        }

    data class VoiceOutputChoice(
        val isSystemEngine: Boolean,
        val entry: Pair<ProviderInstance, ModelEntry>?,
    ) {
        val isSystem: Boolean get() = entry == null
    }

    fun resolveVoiceOutputChoice(): VoiceOutputChoice {
        ensureConfigLoaded()
        val config = _config.value

        fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
            if (!inst.isEnabled || !entry.model.isVoiceOutputCandidate) return null
            return inst to entry
        }

        voiceOutputOverrideEntryId?.let { override ->
            if (override.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) {
                return VoiceOutputChoice(isSystemEngine = true, entry = null)
            }
            providerEntry(override)?.let { return VoiceOutputChoice(false, it) }
        }
        val gid = config.voiceOutputGroupId
        val group = gid?.let { g -> config.modelGroups.find { it.id == g } }
        if (group != null) {
            for (memberId in group.memberEntryIds) {
                if (memberId.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) {
                    return VoiceOutputChoice(isSystemEngine = true, entry = null)
                }
                providerEntry(memberId)?.let { return VoiceOutputChoice(false, it) }
            }
        }
        return VoiceOutputChoice(isSystemEngine = true, entry = null)
    }

    fun activeVoiceGroupMemberId(output: Boolean): String? {
        ensureConfigLoaded()
        val config = _config.value
        val gid = if (output) config.voiceOutputGroupId else config.voiceInputGroupId
        val group = config.modelGroups.find { it.id == gid } ?: return null
        for (memberId in group.memberEntryIds) {
            if (memberId.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return memberId
            val entry = config.modelEntries.find { it.id == memberId } ?: continue
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: continue
            if (!inst.isEnabled) continue
            val usable = if (output) entry.model.isVoiceOutputCandidate else entry.model.isVoiceInputCandidate
            if (usable) return memberId
        }
        return null
    }

    fun voiceOutputGroupName(): String? {
        val gid = _config.value.voiceOutputGroupId ?: return null
        return _config.value.modelGroups.find { it.id == gid }?.name
    }

    fun resolveVoiceOutputEntry(): Pair<ProviderInstance, ModelEntry>? =
        resolveVoiceOutputChoice().entry


    fun hasVoiceModels(instanceId: String): Boolean =
        _config.value.modelEntries.any {
            it.providerInstanceId == instanceId && it.model.isVoiceCandidate
        }

    fun isVoiceShadowDisabled(instanceId: String): Boolean =
        prefs.getBoolean("voiceShadowDisabled.$instanceId", false)

    fun setVoiceShadowDisabled(instanceId: String, disabled: Boolean) {
        prefs.edit().putBoolean("voiceShadowDisabled.$instanceId", disabled).apply()
        synchronized(configLock) {
            _config.value = _config.value.copy(revision = ProviderConfig.nextRevision())
        }
    }

    data class ShadowVoiceProvider(
        val instanceId: String,
        val displayName: String,
        val inputModels: List<ModelEntry>,
        val outputModels: List<ModelEntry>,
    )

    fun shadowVoiceProviders(): List<ShadowVoiceProvider> {
        ensureConfigLoaded()
        val config = _config.value
        val candidates = config.instances.toList().filter { inst ->
            inst.isEnabled && hasVoiceModels(inst.id) && !isVoiceShadowDisabled(inst.id) &&
                com.openminis.app.provider.voice.VoiceProviderFactory.supports(inst, loadApiKey(inst.id))
        }
        val byKey = candidates.groupBy { inst ->
            normalizedShadowKey(inst.customBaseURL).ifEmpty { "id:${inst.id}" }
        }

        fun mostRecentModified(instanceId: String): Long =
            config.modelEntries
                .filter { it.providerInstanceId == instanceId }
                .mapNotNull { it.userModifiedAt }
                .maxOrNull() ?: Long.MIN_VALUE

        return byKey.values.map { insts ->
            val rep = insts.sortedWith(
                compareByDescending<ProviderInstance> { it.isEnabled }
                    .thenByDescending { mostRecentModified(it.id) }
                    .thenBy { it.createdAt }
                    .thenBy { it.id },
            ).first()
            val entries = config.modelEntries.filter { it.providerInstanceId == rep.id }
            ShadowVoiceProvider(
                instanceId = rep.id,
                displayName = rep.label,
                inputModels = entries.filter { it.model.isVoiceInputCandidate },
                outputModels = entries.filter { it.model.isVoiceOutputCandidate },
            )
        }.sortedWith(compareBy({ it.displayName }, { it.instanceId }))
    }

    fun hasFoldedShadowDuplicates(): Boolean {
        val seen = mutableSetOf<String>()
        for (inst in _config.value.instances.toList()) {
            if (!inst.isEnabled || !hasVoiceModels(inst.id)) continue
            val key = normalizedShadowKey(inst.customBaseURL)
            if (key.isEmpty()) continue
            if (!seen.add(key)) return true
        }
        return false
    }


    private val repositoryScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    private val modelReconciler = ModelReconciler(
        scope = repositoryScope,
        lookup = { id -> instance(id) },
        refresh = { inst, force -> refreshModels(inst, forceRefresh = force) },
    )

    fun triggerAsyncModelReconcile(instanceId: String, forceRefresh: Boolean = false): kotlinx.coroutines.Job =
        modelReconciler.trigger(instanceId, forceRefresh)

    enum class ModelRefreshSource { VENDOR, BUILT_IN, MODELS_DEV, KEPT }

    data class ModelRefreshOutcome(
        val source: ModelRefreshSource,
        val total: Int,
        val added: Int,
        val removed: Int,
        val vendorError: String?,
    )

    suspend fun refreshModels(
        instance: ProviderInstance,
        forceRefresh: Boolean = false,
        onVendorError: ((String) -> Unit)? = null,
    ): ModelRefreshOutcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val before = entriesFor(instance.id).map { it.baseModel.id }.toSet()
        var vendorError: String? = null
        val source = refreshModelsInner(instance, forceRefresh) { message ->
            if (vendorError == null) vendorError = message
            onVendorError?.invoke(message)
        }
        if (source != ModelRefreshSource.VENDOR && source != ModelRefreshSource.BUILT_IN) {
            addMissingBuiltInModels(instance)
        }
        val after = entriesFor(instance.id).map { it.baseModel.id }.toSet()
        val outcome = ModelRefreshOutcome(
            source = source,
            total = after.size,
            added = (after - before).size,
            removed = (before - after).size,
            vendorError = if (source == ModelRefreshSource.VENDOR) null else vendorError,
        )
        AppLogger.info("ProviderRepo", "refreshModels outcome id=${instance.id.take(8)} $outcome")
        outcome
    }

    private fun addMissingBuiltInModels(instance: ProviderInstance) = synchronized(configLock) {
        ensureConfigLoaded()
        val seeds = instance.credentialType == ProviderCredential.oauth || !isThirdPartyOpenAICompat(instance)
        if (!seeds) return@synchronized
        val config = workingCopy()
        val have = config.modelEntries.filter { it.providerInstanceId == instance.id }.map { it.baseModel.id }.toSet()
        val missing = instance.providerType.builtInModels.filter { it.id !in have }
        if (missing.isEmpty()) return@synchronized
        config.modelEntries.addAll(missing.map { ModelEntry(providerInstanceId = instance.id, baseModel = it) })
        saveConfig(config)
        android.util.Log.i("ProviderRepo", "[ModelList] ${instance.label}: added built-in ${missing.map { it.id }}")
    }

    private suspend fun refreshModelsInner(
        instance: ProviderInstance,
        forceRefresh: Boolean,
        reportVendorError: (String) -> Unit,
    ): ModelRefreshSource {
        var apiKey = usableApiKey(instance)

        if (instance.providerType == ProviderType.githubCopilot && apiKey == null) {
            apiKey = try {
                com.openminis.app.auth.CopilotOAuthManager(context, instance.id).validAccessToken()
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "Copilot session token unavailable: ${e.message}")
                null
            }
        }

        if (instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth &&
            apiKey != null && instance.providerType != ProviderType.githubCopilot
        ) {
            try {
                val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                val freshToken = manager?.validAccessToken()
                if (freshToken != null && freshToken != apiKey) {
                    saveApiKey(instance.id, freshToken)
                    apiKey = freshToken
                    android.util.Log.i("ProviderRepo", "refreshModels: OAuth token refreshed")
                }
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "OAuth token refresh failed: ${e.message}")
            }
        }

        android.util.Log.i("ProviderRepo", "refreshModels: id=${instance.id} type=${instance.providerType} credential=${instance.credentialType} hasKey=${apiKey != null} keyLen=${apiKey?.length ?: 0} baseURL=${instance.effectiveBaseURL}")

        if (instance.providerType == ProviderType.openAI
            && instance.credentialType == ProviderCredential.oauth
        ) {
            val discovered = discoverCodexModels(instance, apiKey, forceRefresh)
            if (discovered != null) {
                replaceEntries(instance.id, discovered)
                return ModelRefreshSource.VENDOR
            }
            val builtIn = OpenAIModelsApi.fetchModelsOAuth()
            if (builtIn.isNotEmpty()) {
                android.util.Log.i("ProviderRepo", "Codex discovery unavailable — using built-in list (${builtIn.size})")
                replaceEntries(instance.id, builtIn)
                return ModelRefreshSource.BUILT_IN
            }
        }

        val customBase = instance.customBaseURL
        val isThirdParty = customBase != null
            && !customBase.lowercase().let {
                it.contains("api.openai.com") || it.contains("chatgpt.com") || it.contains("openrouter.ai")
            }

        if (apiKey != null) {
            val baseURL = instance.effectiveBaseURL
            val netTag = com.openminis.app.network.NetworkRuntime.providerTag(instance.id)
            val models = try {
                when (instance.providerType) {
                    ProviderType.anthropic -> AnthropicModelsApi.fetchModels(
                        apiKey, baseURL,
                        isOAuth = instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth,
                        customUserAgent = instance.customUserAgent,
                        netTag = netTag,
                    )
                    ProviderType.gemini -> GeminiModelsApi.fetchModels(apiKey, netTag = netTag)
                    ProviderType.openAI, ProviderType.openAIResponses ->
                        OpenAIModelsApi.fetchModelsDetailed(apiKey, baseURL, customUserAgent = instance.customUserAgent, netTag = netTag)
                            .also { r -> r.error?.let(reportVendorError) }
                            .models
                    ProviderType.openRouter -> OpenRouterModelsApi.fetchModels(apiKey, netTag = netTag)
                    ProviderType.xAI -> OpenAIModelsApi.fetchModels(
                        apiKey,
                        baseURL ?: "https://api.x.ai/v1",
                        customUserAgent = instance.customUserAgent,
                        netTag = netTag,
                    )
                        .map { it.copy(provider = "xAI") }
                        .ifEmpty { com.openminis.app.provider.xai.XAIModelsApi.fetchModelsOAuth() }
                    ProviderType.kimiCode -> OpenAIModelsApi.fetchModels(
                        apiKey,
                        baseURL ?: "${com.openminis.app.auth.KimiDeviceFlow.CODING_API_BASE}/v1",
                        customUserAgent = instance.customUserAgent,
                        netTag = netTag,
                    )
                        .map { it.copy(provider = "Kimi") }
                    ProviderType.githubCopilot -> {
                        if (context == null) emptyList() else {
                            com.openminis.app.auth.CopilotOAuthManager(context, instance.id)
                                .fetchModelsDetailed()
                                .map { m ->
                                    LLMModel(
                                        id = m.id,
                                        displayName = m.displayName,
                                        provider = "GitHub Copilot",
                                        contextWindow = m.contextWindow,
                                        maxOutputTokens = m.maxOutputTokens,
                                        supportsReasoning = m.supportsReasoning,
                                        reasoningEffortValues = m.reasoningEffortValues,
                                        inputModalities = if (m.supportsVision) {
                                            listOf("text", "image")
                                        } else listOf("text"),
                                    )
                                }
                        }
                    }
                    ProviderType.workBuddy -> {
                        if (context == null) emptyList() else {
                            val account = com.openminis.app.auth.WorkBuddyOAuthManager(context, instance.id)
                                .currentAccount()
                            if (account == null) emptyList() else {
                                com.openminis.app.auth.WorkBuddyApi.fetchModels(account).map { m ->
                                    LLMModel(
                                        id = m.id,
                                        displayName = m.displayName,
                                        provider = "WorkBuddy",
                                        contextWindow = m.contextWindow,
                                        maxOutputTokens = m.maxOutputTokens,
                                        limitsReportedByEndpoint = true,
                                        supportsReasoning = m.supportsReasoning,
                                        inputModalities = if (m.supportsVision) {
                                            listOf("text", "image")
                                        } else listOf("text"),
                                    )
                                }
                            }
                        }
                    }
                    ProviderType.qoder, ProviderType.minimaxCode -> {
                        val platform = when (instance.providerType) {
                            ProviderType.qoder -> "qoder"
                            else -> "minimax"
                        }
                        if (context == null) emptyList() else {
                            val activeRegion =
                                com.openminis.app.auth.BuiltInAccountStore.activeRegion(context, platform)
                            val acc = com.openminis.app.auth.BuiltInAccountStore.load(context, platform, activeRegion)
                            val api = com.openminis.app.auth.BuiltInAccountStore.api(platform)
                            if (acc == null || api == null) emptyList() else {
                                api.fetchModels(acc).models.map { m ->
                                    LLMModel(
                                        id = m.id,
                                        displayName = m.name,
                                        provider = instance.providerType.displayName,
                                        contextWindow = m.contextWindow,
                                        maxOutputTokens = m.maxOutputTokens,
                                        limitsReportedByEndpoint = true,
                                        supportsReasoning = m.reasoning,
                                        inputModalities = if (m.vision) listOf("text", "image") else listOf("text"),
                                    )
                                }
                            }
                        }
                    }
                    ProviderType.antigravity, ProviderType.unsupported -> emptyList()
                }
            } catch (e: Exception) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                android.util.Log.e("ProviderRepo", "refreshModels fetch error: ${e.message}", e)
                reportVendorError(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
                emptyList()
            }
            android.util.Log.i("ProviderRepo", "refreshModels: got ${models.size} models")
            android.util.Log.i(
                "ProviderRepo",
                "[Restore][DIAG] refreshModels STEP1(vendor-api) id=${instance.id} models=" +
                    models.joinToString { m -> "(id=${m.id}, displayName=${m.displayName})" },
            )

            if (models.isNotEmpty()) {
                replaceEntries(instance.id, models)
                return ModelRefreshSource.VENDOR
            }
            reportVendorError("the provider returned no models")
        } else {
            reportVendorError("no API key for this provider")
        }

        val fallbackBaseURL = modelsDevBaseURL(instance)
        val fallbackModels = ModelsDevApi.fetchModels(fallbackBaseURL, fresh = forceRefresh)
        if (fallbackModels.isNotEmpty()) {
            android.util.Log.i("ProviderRepo", "models.dev fallback returned ${fallbackModels.size} models for ${instance.label}")
            android.util.Log.i(
                "ProviderRepo",
                "[Restore][DIAG] refreshModels STEP3(models.dev-fallback) id=${instance.id} models=" +
                    fallbackModels.joinToString { m -> "(id=${m.id}, displayName=${m.displayName})" },
            )
            replaceEntries(instance.id, fallbackModels)
            return ModelRefreshSource.MODELS_DEV
        }
        if (isThirdParty) {
            android.util.Log.i("ProviderRepo", "Third-party endpoint, no models.dev match — preserving existing models for ${instance.label}")
        }
        return ModelRefreshSource.KEPT
    }

    private suspend fun discoverCodexModels(
        instance: ProviderInstance,
        accessToken: String?,
        forceRefresh: Boolean,
    ): List<LLMModel>? {
        if (accessToken.isNullOrBlank()) return null
        val ctx = context

        val accountId = try {
            com.openminis.app.auth.OpenAIOAuthManager(ctx, instance.id).accountId
        } catch (e: Exception) {
            android.util.Log.w("ProviderRepo", "Codex accountId unavailable: ${e.message}")
            null
        }

        val result = com.openminis.app.provider.openai.CodexModelsApi.fetchModels(
            accessToken = accessToken,
            accountId = accountId,
            clientVersion = com.openminis.app.provider.openai.OpenAIProvider.CODEX_CLIENT_VERSION,
            context = ctx,
            forceRefresh = forceRefresh,
            netTag = com.openminis.app.network.NetworkRuntime.providerTag(instance.id),
        )

        return when (result) {
            is com.openminis.app.provider.openai.CodexModelsApi.Result.Success -> {
                val imageModels = OpenAIModelsApi.codexImageModels()
                val discoveredIds = result.models.map { it.id }.toSet()
                android.util.Log.i(
                    "ProviderRepo",
                    "Codex discovery: ${result.models.size} live models for ${instance.label}",
                )
                result.models + imageModels.filter { it.id !in discoveredIds }
            }
            is com.openminis.app.provider.openai.CodexModelsApi.Result.AuthFailed -> {
                if (forceRefresh) throw CodexDiscoveryAuthException(result.status)
                android.util.Log.w(
                    "ProviderRepo",
                    "Codex discovery auth failure (HTTP ${result.status}) on background refresh — falling back",
                )
                null
            }
            is com.openminis.app.provider.openai.CodexModelsApi.Result.Failed -> {
                android.util.Log.i(
                    "ProviderRepo",
                    "Codex discovery unavailable (${result.reason}) for ${instance.label}",
                )
                null
            }
        }
    }

    private suspend fun autoRefreshModels(instance: ProviderInstance) {
        val existingCountBeforeRefresh = _config.value.modelEntries.count { it.providerInstanceId == instance.id }
        val hasCustom = _config.value.modelEntries.any {
            it.providerInstanceId == instance.id && it.isCustom
        }
        android.util.Log.i(
            "ProviderRepo",
            "[Restore][DIAG] autoRefreshModels ENTER id=${instance.id} label=${instance.label} " +
                "existingEntries=$existingCountBeforeRefresh hasCustom=$hasCustom",
        )
        if (hasCustom) {
            android.util.Log.i("ProviderRepo", "[ModelList] autoRefresh SKIP ${instance.label} — has custom models")
            android.util.Log.i(
                "ProviderRepo",
                "[Restore][DIAG] autoRefreshModels SKIP-branch HIT id=${instance.id} — refreshModels/replaceEntries NOT called",
            )
            return
        }
        refreshModels(instance)
    }

    fun triggerBackgroundRefreshIfStale(scope: kotlinx.coroutines.CoroutineScope) {
        val stale = _config.value.instances.filter { it.isEnabled && isInstanceStale(it.id) }
        if (stale.isEmpty()) return
        android.util.Log.i("ProviderRepo", "[ModelList] SWR refresh — ${stale.size} stale instance(s)")
        for (instance in stale) {
            scope.launch { autoRefreshModels(instance) }
        }
    }

    fun refreshAllModelsIfNeeded(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            val key = "lastModelsRefreshDate"
            val lastMs = prefs.getLong(key, 0L)
            val now = System.currentTimeMillis()
            val elapsed = now - lastMs
            if (lastMs > 0L && elapsed < MODELS_REFRESH_WINDOW_MS) {
                android.util.Log.i(
                    "ProviderRepo",
                    "[ModelList] refreshAllModelsIfNeeded SKIP — refreshed ${elapsed / 60_000}m ago " +
                        "(window ${MODELS_REFRESH_WINDOW_MS / 60_000}m)",
                )
                return@launch
            }

            awaitConfigLoaded()
            val enabled = _config.value.instances.filter { it.isEnabled }
            if (enabled.isEmpty()) {
                android.util.Log.i("ProviderRepo", "[ModelList] refreshAllModelsIfNeeded SKIP — no enabled instances")
                return@launch
            }

            android.util.Log.i("ProviderRepo", "[ModelList] refreshAllModelsIfNeeded FIRE — ${enabled.size} instances")
            android.util.Log.i(
                "ProviderRepo",
                "[Restore][DIAG] refreshAllModelsIfNeeded enabled providers: " +
                    enabled.joinToString { ei -> "(id=${ei.id}, label=${ei.label})" },
            )
            prefs.edit().putLong(key, now).apply()

            for (instance in enabled) {
                scope.launch { autoRefreshModels(instance) }
            }
        }
    }

    private fun modelsDevBaseURL(instance: ProviderInstance): String {
        instance.effectiveBaseURL?.let { return it }
        return when (instance.providerType) {
            ProviderType.anthropic -> "https://api.anthropic.com/v1"
            ProviderType.gemini -> "https://generativelanguage.googleapis.com"
            ProviderType.openAI, ProviderType.openAIResponses -> "https://api.openai.com/v1"
            ProviderType.openRouter -> "https://openrouter.ai/api/v1"
            ProviderType.xAI -> "https://api.x.ai/v1"
            ProviderType.kimiCode -> "${com.openminis.app.auth.KimiDeviceFlow.CODING_API_BASE}/v1"
            ProviderType.githubCopilot -> com.openminis.app.auth.CopilotDeviceFlow.API_BASE
            ProviderType.workBuddy -> "https://copilot.tencent.com/v1"
            ProviderType.qoder -> com.openminis.app.auth.QoderApi.DEFAULT_CHAT_BASE
            ProviderType.minimaxCode -> com.openminis.app.auth.MiniMaxCodeApi.DEFAULT_CHAT_BASE
            ProviderType.antigravity, ProviderType.unsupported -> "https://api.openai.com/v1"
        }
    }

    fun saveApiKey(instanceId: String, key: String) {
        encryptedPrefs.edit().putString("apikey_$instanceId", key).apply()
        com.openminis.app.quota.QuotaStore.credentialsChanged()
        ProviderCredentialEdits.changed(instanceId)
    }

    fun loadApiKey(instanceId: String): String? {
        return encryptedPrefs.getString("apikey_$instanceId", null)
    }

    fun usableApiKey(instance: ProviderInstance): String? =
        loadApiKey(instance.id) ?: if (instance.allowsEmptyAPIKey) "" else null

    suspend fun requestCredential(instance: ProviderInstance): String? {
        var apiKey = usableApiKey(instance) ?: if (hasAnyCredential(instance)) "" else return null
        if (instance.credentialType == ProviderCredential.oauth) {
            try {
                val fresh = com.openminis.app.auth.OAuthManager.forInstance(context, instance)?.validAccessToken()
                if (fresh != null && fresh != apiKey) {
                    saveApiKey(instance.id, fresh)
                    apiKey = fresh
                }
            } catch (e: Exception) {
                AppLogger.warning("ProviderRepo", "requestCredential: OAuth refresh failed for ${instance.id.take(8)}: ${e.message}")
            }
        }
        return apiKey
    }

    fun deleteApiKey(instanceId: String) {
        encryptedPrefs.edit().remove("apikey_$instanceId").apply()
        com.openminis.app.quota.QuotaStore.credentialsChanged()
        ProviderCredentialEdits.changed(instanceId)
    }


    private fun oauthManagerFor(
        instance: ProviderInstance,
    ): com.openminis.app.auth.OAuthManager? = when (instance.providerType) {
        ProviderType.anthropic -> com.openminis.app.auth.ClaudeOAuthManager(context, instance.id)
        ProviderType.openAI -> com.openminis.app.auth.OpenAIOAuthManager(context, instance.id)
        ProviderType.xAI -> com.openminis.app.auth.XAIOAuthManager(context, instance.id)
        ProviderType.gemini -> com.openminis.app.auth.GeminiOAuthManager(context, instance.id)
        ProviderType.kimiCode -> com.openminis.app.auth.KimiOAuthManager(context, instance.id)
        else -> null
    }

    fun exportInstanceJSON(instanceId: String): String? {
        ensureConfigLoaded()
        val instance = instance(instanceId) ?: return null
        val entries = visibleEntries(instanceId) + _config.value.modelEntries.filter {
            it.providerInstanceId == instanceId && it.isHidden
        }

        val obj = JSONObject().apply {
            put("providerType", instance.providerType.name)
            put("label", instance.label)
            put("credentialType", instance.credentialType.name)
            val modelsArr = JSONArray()
            for (entry in entries) {
                modelsArr.put(JSONObject().apply {
                    put("modelId", entry.baseModel.id)
                    put("displayName", entry.baseModel.displayName)
                    put("isHidden", entry.isHidden)
                    if (entry.isCustom) put("isCustom", true)
                    entry.baseModel.contextWindow?.let { put("contextWindow", it) }
                    entry.baseModel.maxOutputTokens?.let { put("maxOutputTokens", it) }
                    if (entry.baseModel.limitsReportedByEndpoint == true) put("limitsReportedByEndpoint", true)
                    entry.baseModel.supportsReasoning?.let { put("supportsReasoning", it) }
                    entry.baseModel.interleavedReasoningField?.let { put("interleavedReasoningField", it) }
                    if (!entry.overrides.isEmpty) {
                        val o = JSONObject()
                        entry.overrides.displayName?.let { o.put("displayName", it) }
                        entry.overrides.maxOutputTokens?.let { o.put("maxOutputTokens", it) }
                        entry.overrides.contextWindow?.let { o.put("contextWindow", it) }
                        entry.overrides.supportsReasoning?.let { o.put("supportsReasoning", it) }
                        entry.overrides.inputModalities?.let {
                            o.put("inputModalities", JSONArray(it))
                        }
                        entry.overrides.outputModalities?.let {
                            o.put("outputModalities", JSONArray(it))
                        }
                        entry.overrides.maxThinkingLevel?.let {
                            o.put("maxThinkingLevel", it.name.lowercase())
                        }
                        entry.overrides.temperature?.let { o.put("temperature", it) }
                        entry.overrides.topP?.let { o.put("topP", it) }
                        entry.overrides.customHeaders?.let { headers ->
                            if (headers.isNotEmpty()) {
                                val h = JSONObject()
                                for ((k, v) in headers) h.put(k, v)
                                o.put("customHeaders", h)
                            }
                        }
                        entry.overrides.extraBodyParams?.let { extra ->
                            runCatching { JSONObject(extra.toString()) }
                                .getOrNull()
                                ?.let { o.put("extraBodyParams", it) }
                        }
                        val bitfield = modalityBitfieldFromLists(
                            entry.overrides.inputModalities,
                            entry.overrides.outputModalities,
                        )
                        if (bitfield != 0) o.put("modalityOverride", bitfield)
                        put("overrides", o)
                    }
                    run {
                        val bf = modalityBitfieldFromLists(
                            entry.baseModel.inputModalities,
                            entry.baseModel.outputModalities,
                        )
                        if (bf != 0) put("modalityOverride", bf)
                    }
                    entry.baseModel.inputModalities?.let {
                        put("inputModalities", JSONArray(it))
                    }
                    entry.baseModel.outputModalities?.let {
                        put("outputModalities", JSONArray(it))
                    }
                })
            }
            put("models", modelsArr)
            loadApiKey(instanceId)?.let { key ->
                put("apiKey", Base64.encodeToString(key.toByteArray(), Base64.NO_WRAP))
            }
            run {
                val mgr = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                val manual = mgr?.loadManualBearerToken()
                if (!manual.isNullOrEmpty()) {
                    put("manualOAuthToken", Base64.encodeToString(manual.toByteArray(), Base64.NO_WRAP))
                }
            }
            run {
                val mgr = oauthManagerFor(instance)
                mgr?.exportStoredTokensJson()?.let { tokenJson ->
                    put("oauthToken", Base64.encodeToString(tokenJson.toByteArray(), Base64.NO_WRAP))
                }
                if (instance.providerType == ProviderType.gemini && mgr != null) {
                    mgr.exportOAuthString("email")?.takeIf { it.isNotEmpty() }?.let {
                        put("oauthEmail", Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP))
                    }
                    mgr.exportOAuthString("gcp_project")?.takeIf { it.isNotEmpty() }?.let {
                        put("oauthGcpProject", Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP))
                    }
                }
            }
            instance.customBaseURL?.let { put("customBaseURL", it) }
            if (!instance.appendV1Suffix) put("appendV1Suffix", false)
            if (instance.useResponsesAPI) put("useResponsesAPI", true)
            instance.customUserAgent?.takeIf { it.isNotBlank() }?.let { put("customUserAgent", it) }
        }
        return obj.toString(2)
    }

    fun collectBackupProviderSecret(
        instance: ProviderInstance,
    ): BackupSecrets.ProviderSecret? {
        fun b64(s: String): String =
            Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)

        val apiKey = loadApiKey(instance.id)?.let(::b64)
        val manualOAuth = com.openminis.app.auth.OAuthManager
            .forInstance(context, instance)?.loadManualBearerToken()
            ?.takeIf { it.isNotEmpty() }?.let(::b64)

        var oauthToken: String? = null
        var oauthEmail: String? = null
        var oauthGcpProject: String? = null
        val mgr = oauthManagerFor(instance)
        if (mgr != null) {
            oauthToken = if (instance.providerType == ProviderType.githubCopilot) {
                (mgr as? com.openminis.app.auth.CopilotOAuthManager)
                    ?.exportCopilotTokensJson()?.let(::b64)
            } else {
                mgr.exportStoredTokensJson()?.let(::b64)
            }
            if (instance.providerType == ProviderType.gemini) {
                oauthEmail = mgr.exportOAuthString("email")
                    ?.takeIf { it.isNotEmpty() }?.let(::b64)
                oauthGcpProject = mgr.exportOAuthString("gcp_project")
                    ?.takeIf { it.isNotEmpty() }?.let(::b64)
            }
        }

        val secret = BackupSecrets.ProviderSecret(
            instanceId = instance.id,
            label = instance.label,
            providerType = instance.providerType.name,
            apiKey = apiKey,
            manualOAuthToken = manualOAuth,
            oauthToken = oauthToken,
            oauthEmail = oauthEmail,
            oauthGcpProject = oauthGcpProject,
        )
        return if (secret.isEmpty) null else secret
    }

    fun restoreBackupProviderSecret(secret: BackupSecrets.ProviderSecret): Boolean {
        fun deb64(s: String?): String? = s?.let {
            runCatching { String(Base64.decode(it, Base64.NO_WRAP)) }.getOrNull()
        }
        val instance = instance(secret.instanceId) ?: return false
        val bearerMgr = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
        val mgr = oauthManagerFor(instance)
        val copilotMgr = (mgr as? com.openminis.app.auth.CopilotOAuthManager)
            ?.takeIf { instance.providerType == ProviderType.githubCopilot }
        val isGemini = instance.providerType == ProviderType.gemini
        val store = object : com.openminis.app.backup.BackupCredentialRestore.Store {
            override fun supports(slot: CredentialSlot) = when (slot) {
                CredentialSlot.API_KEY -> true
                CredentialSlot.MANUAL_OAUTH_TOKEN -> bearerMgr != null
                CredentialSlot.OAUTH_TOKEN -> mgr != null
                CredentialSlot.OAUTH_EMAIL, CredentialSlot.OAUTH_GCP_PROJECT -> mgr != null && isGemini
            }

            override fun read(slot: CredentialSlot): String? = when (slot) {
                CredentialSlot.API_KEY -> loadApiKey(instance.id)
                CredentialSlot.MANUAL_OAUTH_TOKEN -> bearerMgr?.loadManualBearerToken()
                CredentialSlot.OAUTH_TOKEN -> if (copilotMgr != null) copilotMgr.exportCopilotTokensJson() else mgr?.exportStoredTokensJson()
                CredentialSlot.OAUTH_EMAIL -> mgr?.exportOAuthString("email")
                CredentialSlot.OAUTH_GCP_PROJECT -> mgr?.exportOAuthString("gcp_project")
            }

            override fun write(slot: CredentialSlot, value: String) {
                when (slot) {
                    CredentialSlot.API_KEY -> saveApiKey(instance.id, value)
                    CredentialSlot.MANUAL_OAUTH_TOKEN -> bearerMgr?.saveManualBearerToken(value)
                    CredentialSlot.OAUTH_TOKEN -> if (copilotMgr != null) copilotMgr.importCopilotTokensJson(value) else mgr?.importStoredTokensJson(value)
                    CredentialSlot.OAUTH_EMAIL -> mgr?.importOAuthString("email", value)
                    CredentialSlot.OAUTH_GCP_PROJECT -> mgr?.importOAuthString("gcp_project", value)
                }
            }

            override fun oauthNeedsReauth() =
                com.openminis.app.auth.OAuthManager.needsReauth(context, instance.id)
        }
        val backup = buildMap {
            deb64(secret.apiKey)?.let { put(CredentialSlot.API_KEY, it) }
            deb64(secret.manualOAuthToken)?.let { put(CredentialSlot.MANUAL_OAUTH_TOKEN, it) }
            deb64(secret.oauthToken)?.let { put(CredentialSlot.OAUTH_TOKEN, it) }
            deb64(secret.oauthEmail)?.let { put(CredentialSlot.OAUTH_EMAIL, it) }
            deb64(secret.oauthGcpProject)?.let { put(CredentialSlot.OAUTH_GCP_PROJECT, it) }
        }
        return com.openminis.app.backup.BackupCredentialRestore.restore(backup, store)
    }

    data class RestoreMergeOutcome(
        val before: Int,
        val after: Int,
        val groupIdRewrites: Map<String, String>,
        val merge: com.openminis.app.backup.BackupProviderMerge.Result? = null,
    )

    fun mergeBackupProviderConfig(remote: ProviderConfig): RestoreMergeOutcome {
        ensureConfigLoaded()
        return synchronized(configLock) {
            val local = _config.value
            val before = local.instances.size

            android.util.Log.i(
                "ProviderRepo",
                "[Restore][DIAG] remote.instances count=${remote.instances.size}: " +
                    remote.instances.joinToString { ri -> "(id=${ri.id}, label=${ri.label}, type=${ri.providerType})" },
            )
            android.util.Log.i(
                "ProviderRepo",
                "[Restore][DIAG] remote.modelEntries count=${remote.modelEntries.size}",
            )
            for (re in remote.modelEntries) {
                android.util.Log.i(
                    "ProviderRepo",
                    "[Restore][DIAG] remote entry providerInstanceId=${re.providerInstanceId} " +
                        "baseModel.id=${re.baseModel.id} baseModel.displayName=${re.baseModel.displayName} uuid=${re.id}",
                )
            }

            val result = com.openminis.app.backup.BackupProviderMerge.merge(local, remote) {
                AppLogger.info(SUB_AGENT_LOG, it)
            }
            val merged = result.config
            val orderedInstances = merged.instances
            val mergedEntries = merged.modelEntries
            val orderedGroups = merged.modelGroups
            saveConfig(merged)
            val after = orderedInstances.size
            android.util.Log.i(
                "ProviderRepo",
                "[Restore] provider merge: instances $before→$after " +
                    "entries=${mergedEntries.size} groups=${orderedGroups.size}",
            )
            android.util.Log.i(
                "ProviderRepo",
                "[Restore][DIAG] orderedInstances count=${orderedInstances.size}: " +
                    orderedInstances.joinToString { oi -> "(id=${oi.id}, label=${oi.label}, type=${oi.providerType})" },
            )
            android.util.Log.i(
                "ProviderRepo",
                "[Restore][DIAG] mergedEntries count=${mergedEntries.size}",
            )
            for (me in mergedEntries) {
                android.util.Log.i(
                    "ProviderRepo",
                    "[Restore][DIAG] merged entry providerInstanceId=${me.providerInstanceId} " +
                        "baseModel.id=${me.baseModel.id} baseModel.displayName=${me.baseModel.displayName} uuid=${me.id}",
                )
            }
            if (result.groupIdRewrites.isNotEmpty()) {
                AppLogger.info("ProviderRepo", "[Restore] same-name groups merged: ${result.groupIdRewrites}")
            }
            android.util.Log.i(
                "ProviderRepo",
                "[Restore] entries added=${result.entriesAdded} parameters replaced=${result.entriesUpdated}",
            )
            RestoreMergeOutcome(before, after, result.groupIdRewrites, result)
        }
    }

    fun restoreBackupSubAgents(incoming: List<SubAgentDefinition>): Pair<Int, Int> = synchronized(configLock) {
        if (incoming.isEmpty()) return@synchronized 0 to 0
        ensureConfigLoaded()
        val config = workingCopy()
        val merged = SubAgentRoster.mergeBackup(config.subAgents, incoming) { AppLogger.info(SUB_AGENT_LOG, it) }
        if (merged.written > 0) {
            config.subAgents.clear()
            config.subAgents.addAll(merged.roster)
            saveConfig(config)
        }
        merged.written to merged.skipped
    }

    fun restoreBackupThinkingRules(
        rules: List<com.openminis.app.backup.BackupThinkingRuleRecord>,
    ): Pair<Int, Int> = runBlocking {
        if (rules.isEmpty()) return@runBlocking 0 to 0
        val existing = providerDao.loadAllThinkingRules().map { it.id }.toSet()
        var written = 0
        var skipped = 0
        for (r in rules) {
            if (r.id in existing) { skipped++; continue }
            providerDao.upsertThinkingRule(
                com.openminis.app.data.db.ProviderThinkingRuleEntity(
                    id = r.id,
                    providerInstanceId = r.instanceId,
                    label = r.label,
                    scopeKind = r.scopeKind,
                    scopePattern = r.scopePattern,
                    wireFormatJson = r.wireFormatJson.takeIf { it.isNotBlank() && it != "{}" },
                    reasoningEchoJson = null,
                    sortOrder = r.sortOrder,
                )
            )
            written++
        }
        loadAllThinkingRulesIntoCache()
        written to skipped
    }

    fun importInstanceJSON(jsonStr: String): String? {
        ensureConfigLoaded()
        val dict = try { JSONObject(jsonStr) } catch (_: Exception) { return null }
        val providerTypeRaw = dict.optString("providerType", "").ifEmpty { return null }
        val providerType = try { ProviderType.valueOf(providerTypeRaw) } catch (_: Exception) { return null }
        val label = dict.optString("label", "").ifEmpty { return null }

        val credentialType = try {
            ProviderCredential.valueOf(dict.optString("credentialType", "apiKey"))
        } catch (_: Exception) { ProviderCredential.apiKey }

        val existingLabels = _config.value.instances.map { it.label }.toSet()
        var resolvedLabel = label
        if (resolvedLabel in existingLabels) {
            var suffix = 2
            while ("$label ($suffix)" in existingLabels) suffix++
            resolvedLabel = "$label ($suffix)"
        }

        val customBaseURL = dict.optString("customBaseURL", "").ifEmpty { null }
        val appendV1 = dict.optBoolean("appendV1Suffix", true)
        val useResponsesAPI = dict.optBoolean("useResponsesAPI", false)
        val customUserAgent = dict.optString("customUserAgent", "").ifEmpty { null }

        val instance = ProviderInstance(
            id = java.util.UUID.randomUUID().toString(),
            label = resolvedLabel,
            providerType = providerType,
            credentialType = credentialType,
            customBaseURL = customBaseURL,
            appendV1Suffix = appendV1,
            useResponsesAPI = useResponsesAPI,
            customUserAgent = customUserAgent,
        )
        addInstance(instance)

        val keyValue = dict.optString("apiKey", "").ifEmpty { null }
        if (keyValue != null) {
            val apiKey = try {
                String(Base64.decode(keyValue, Base64.NO_WRAP))
            } catch (_: Exception) {
                keyValue
            }
            saveApiKey(instance.id, apiKey)
        }

        val manualTokenValue = dict.optString("manualOAuthToken", "").ifEmpty { null }
        if (manualTokenValue != null) {
            val manualToken = try {
                String(Base64.decode(manualTokenValue, Base64.NO_WRAP))
            } catch (_: Exception) {
                manualTokenValue
            }
            val mgr = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
            mgr?.saveManualBearerToken(manualToken)
        }

        val oauthTokenValue = dict.optString("oauthToken", "").ifEmpty { null }
        if (oauthTokenValue != null) {
            val tokenJson = try {
                String(Base64.decode(oauthTokenValue, Base64.NO_WRAP))
            } catch (_: Exception) {
                oauthTokenValue
            }
            oauthManagerFor(instance)?.importStoredTokensJson(tokenJson)
        }
        if (instance.providerType == ProviderType.gemini) {
            val mgr = oauthManagerFor(instance)
            dict.optString("oauthEmail", "").ifEmpty { null }?.let { b64 ->
                val email = try { String(Base64.decode(b64, Base64.NO_WRAP)) } catch (_: Exception) { b64 }
                mgr?.importOAuthString("email", email)
            }
            dict.optString("oauthGcpProject", "").ifEmpty { null }?.let { b64 ->
                val project = try { String(Base64.decode(b64, Base64.NO_WRAP)) } catch (_: Exception) { b64 }
                mgr?.importOAuthString("gcp_project", project)
            }
        }

        val models = dict.optJSONArray("models")
        if (models != null && models.length() > 0) {
            val entries = mutableListOf<ModelEntry>()
            for (i in 0 until models.length()) {
                val m = models.getJSONObject(i)
                val modelId = m.optString("modelId", "")
                if (modelId.isEmpty()) continue
                val displayName = m.optString("displayName", modelId)
                val isCustom = m.optBoolean("isCustom", false)
                val isHidden = m.optBoolean("isHidden", false)
                val contextWindow = if (m.has("contextWindow")) m.optInt("contextWindow").takeIf { it > 0 } else null
                val maxOutputTokens = if (m.has("maxOutputTokens")) m.optInt("maxOutputTokens").takeIf { it > 0 } else null
                val supportsReasoning = if (m.has("supportsReasoning")) m.optBoolean("supportsReasoning") else null
                val interleavedReasoningField = m.optString("interleavedReasoningField", "").ifEmpty { null }
                val (baseIn, baseOut) = readModalitiesWithBitfieldFallback(m)
                val model = LLMModel(
                    id = modelId,
                    displayName = displayName,
                    provider = providerType.displayName,
                    contextWindow = contextWindow,
                    maxOutputTokens = maxOutputTokens,
                    limitsReportedByEndpoint = if (m.optBoolean("limitsReportedByEndpoint", false)) true else null,
                    supportsReasoning = supportsReasoning,
                    interleavedReasoningField = interleavedReasoningField,
                    inputModalities = baseIn,
                    outputModalities = baseOut,
                )
                val overridesObj = m.optJSONObject("overrides")
                val overrides = if (overridesObj != null) {
                    val (ovIn, ovOut) = readModalitiesWithBitfieldFallback(overridesObj)
                    ModelOverrides(
                        displayName = overridesObj.optString("displayName", "").ifEmpty { null },
                        maxOutputTokens = if (overridesObj.has("maxOutputTokens")) overridesObj.optInt("maxOutputTokens").takeIf { it > 0 } else null,
                        contextWindow = if (overridesObj.has("contextWindow")) overridesObj.optInt("contextWindow").takeIf { it > 0 } else null,
                        supportsReasoning = if (overridesObj.has("supportsReasoning")) overridesObj.optBoolean("supportsReasoning") else null,
                        inputModalities = ovIn,
                        outputModalities = ovOut,
                        maxThinkingLevel = thinkingLevelFromWire(
                            overridesObj.optString("maxThinkingLevel", ""),
                        ),
                        temperature = if (overridesObj.has("temperature")) {
                            overridesObj.optDouble("temperature").takeIf { !it.isNaN() }
                        } else null,
                        topP = if (overridesObj.has("topP")) {
                            overridesObj.optDouble("topP").takeIf { !it.isNaN() }
                        } else null,
                        customHeaders = overridesObj.optJSONObject("customHeaders")?.let { h ->
                            buildMap {
                                for (k in h.keys()) put(k, h.optString(k, ""))
                            }.takeIf { it.isNotEmpty() }
                        },
                        extraBodyParams = overridesObj.optJSONObject("extraBodyParams")?.let { ex ->
                            runCatching {
                                kotlinx.serialization.json.Json
                                    .parseToJsonElement(ex.toString())
                                    as? kotlinx.serialization.json.JsonObject
                            }.getOrNull()
                        },
                    )
                } else {
                    ModelOverrides()
                }
                entries.add(ModelEntry(
                    providerInstanceId = instance.id,
                    baseModel = model,
                    overrides = overrides,
                    isCustom = isCustom,
                    isHidden = isHidden,
                ))
            }
            synchronized(configLock) {
                val cfg = workingCopy()
                cfg.modelEntries.removeAll { it.providerInstanceId == instance.id }
                cfg.modelEntries.addAll(entries)
                saveConfig(cfg)
            }
        }

        return resolvedLabel
    }


    private fun modalityBitfieldFromLists(
        inputs: List<String>?,
        outputs: List<String>?,
    ): Int {
        var bits = 0
        inputs?.forEach { raw ->
            when (raw.lowercase()) {
                "text" -> bits = bits or MODALITY_BIT_TEXT_IN
                "image" -> bits = bits or MODALITY_BIT_IMG_IN
                "pdf" -> bits = bits or MODALITY_BIT_PDF_IN
                "audio" -> bits = bits or MODALITY_BIT_AUD_IN
                "video" -> bits = bits or MODALITY_BIT_VID_IN
            }
        }
        outputs?.forEach { raw ->
            when (raw.lowercase()) {
                "text" -> bits = bits or MODALITY_BIT_TEXT_OUT
                "image" -> bits = bits or MODALITY_BIT_IMG_OUT
                "audio" -> bits = bits or MODALITY_BIT_AUD_OUT
                "video" -> bits = bits or MODALITY_BIT_VID_OUT
            }
        }
        return bits
    }

    private fun modalityListsFromBitfield(bits: Int): Pair<List<String>?, List<String>?> {
        if (bits == 0) return null to null
        val inputs = buildList {
            if (bits and MODALITY_BIT_TEXT_IN != 0) add("text")
            if (bits and MODALITY_BIT_IMG_IN != 0) add("image")
            if (bits and MODALITY_BIT_PDF_IN != 0) add("pdf")
            if (bits and MODALITY_BIT_AUD_IN != 0) add("audio")
            if (bits and MODALITY_BIT_VID_IN != 0) add("video")
        }
        val outputs = buildList {
            if (bits and MODALITY_BIT_TEXT_OUT != 0) add("text")
            if (bits and MODALITY_BIT_IMG_OUT != 0) add("image")
            if (bits and MODALITY_BIT_AUD_OUT != 0) add("audio")
            if (bits and MODALITY_BIT_VID_OUT != 0) add("video")
        }
        return inputs.ifEmpty { null } to outputs.ifEmpty { null }
    }

    private fun readModalitiesWithBitfieldFallback(
        obj: JSONObject,
    ): Pair<List<String>?, List<String>?> {
        val nativeIn = obj.optJSONArray("inputModalities")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }.takeIf { it.isNotEmpty() }
        }
        val nativeOut = obj.optJSONArray("outputModalities")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }.takeIf { it.isNotEmpty() }
        }
        if (nativeIn != null || nativeOut != null) return nativeIn to nativeOut
        if (!obj.has("modalityOverride")) return null to null
        val bits = obj.optInt("modalityOverride", 0)
        return modalityListsFromBitfield(bits)
    }

    private fun thinkingLevelFromWire(raw: String): ThinkingLevel? {
        if (raw.isEmpty()) return null
        return ThinkingLevel.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}

object ProviderCredentialEdits {
    private val _ids = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 64)
    val ids: kotlinx.coroutines.flow.SharedFlow<String> = _ids

    fun changed(instanceId: String) {
        _ids.tryEmit(instanceId)
    }
}
