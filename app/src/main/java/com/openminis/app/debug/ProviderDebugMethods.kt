package com.openminis.app.debug

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import org.json.JSONArray
import org.json.JSONObject

internal object ProviderDebugMethods {

    private fun repo(context: Context): ProviderRepository {
        val app = context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")
        app.providerRepository.ensureConfigLoaded()
        return app.providerRepository
    }

    fun types(): JSONObject {
        val arr = JSONArray()
        for (type in ProviderType.values()) {
            arr.put(typeSpec(type))
        }
        return JSONObject().put("types", arr)
    }

    private fun typeSpec(type: ProviderType): JSONObject {
        val supportedCreds = JSONArray().apply {
            put("apiKey")
            if (type == ProviderType.anthropic || type == ProviderType.openAI) put("oauth")
        }
        val builtInIds = JSONArray()
        for (m in type.builtInModels) builtInIds.put(m.id)

        val customBaseSupported = type == ProviderType.openAI || type == ProviderType.anthropic
        return JSONObject().apply {
            put("id", type.name)
            put("displayName", type.displayName)
            put("supportedCredentials", supportedCreds)
            put("oauthFlow", when (type) {
                ProviderType.anthropic -> "claude"
                ProviderType.openAI -> "codex"
                else -> JSONObject.NULL
            })
            put("defaultBaseURL", defaultBaseURL(type))
            put("customBaseURLSupported", customBaseSupported)
            put("appendV1SuffixConfigurable", customBaseSupported)
            put("builtInModelIds", builtInIds)
        }
    }

    private fun defaultBaseURL(type: ProviderType): String = when (type) {
        ProviderType.anthropic -> "https://api.anthropic.com"
        ProviderType.gemini -> "https://generativelanguage.googleapis.com"
        ProviderType.openAI -> "https://api.openai.com"
        ProviderType.openRouter -> "https://openrouter.ai/api/v1"
        ProviderType.xAI -> "https://api.x.ai/v1"
        ProviderType.kimiCode -> "https://api.kimi.com/coding/v1"
        ProviderType.githubCopilot -> com.openminis.app.auth.CopilotDeviceFlow.API_BASE
        ProviderType.openAIResponses -> "https://api.openai.com"
        ProviderType.workBuddy -> com.openminis.app.auth.WorkBuddyApi.DOMESTIC_BACKEND
        ProviderType.qoder -> com.openminis.app.auth.QoderApi.DEFAULT_CHAT_BASE
        ProviderType.minimaxCode -> com.openminis.app.auth.MiniMaxCodeApi.DEFAULT_CHAT_BASE
        ProviderType.antigravity, ProviderType.unsupported -> ""
    }

    fun instancesList(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val includeDisabled = params.optBoolean("includeDisabled", true)
        val cfg = repo.config.value
        val arr = JSONArray()
        for (inst in cfg.instances) {
            if (!includeDisabled && !inst.isEnabled) continue
            val hasKey = repo.usableApiKey(inst) != null
            val entryCount = cfg.modelEntries.count { it.providerInstanceId == inst.id }
            arr.put(JSONObject().apply {
                put("id", inst.id)
                put("label", inst.label)
                put("providerType", inst.providerType.name)
                put("credentialType", inst.credentialType.name)
                put("isEnabled", inst.isEnabled)
                put("customBaseURL", inst.customBaseURL ?: JSONObject.NULL)
                put("appendV1Suffix", inst.appendV1Suffix)
                put("useResponsesAPI", inst.useResponsesAPI)
                put("imageEndpointMode", inst.imageEndpointMode.name)
                put("imageEndpointResolved", inst.imageEndpointResolved?.name ?: JSONObject.NULL)
                put("customUserAgent", inst.customUserAgent ?: JSONObject.NULL)
                put("createdAt", inst.createdAt)
                put("hasCredential", hasKey)
                put("modelEntryCount", entryCount)
            })
        }
        return JSONObject().apply {
            put("count", arr.length())
            put("instances", arr)
        }
    }

    fun modelsList(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val instanceId = params.optString("instanceId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'instanceId' param")
        }
        val includeHidden = params.optBoolean("includeHidden", false)
        val instance = repo.instance(instanceId)
            ?: throw RPCException(-32602, "Instance not found: $instanceId")
        val all = repo.config.value.modelEntries.filter { it.providerInstanceId == instanceId }
        val visible = if (includeHidden) all else all.filter { !it.isHidden }
        val arr = JSONArray()
        for (entry in visible) {
            val effective: LLMModel = entry.model
            arr.put(JSONObject().apply {
                put("id", entry.id)
                put("modelId", effective.id)
                put("displayName", effective.displayName)
                put("baseModelDisplayName", entry.baseModel.displayName)
                put("isCustom", entry.isCustom)
                put("isHidden", entry.isHidden)
                put("supportsReasoning", effective.supportsReasoning ?: JSONObject.NULL)
                put("contextWindow", effective.contextWindow ?: JSONObject.NULL)
                put("maxOutputTokens", effective.maxOutputTokens ?: JSONObject.NULL)
                val ov = JSONObject()
                ov.put("displayName", entry.overrides.displayName ?: JSONObject.NULL)
                ov.put("maxOutputTokens", entry.overrides.maxOutputTokens ?: JSONObject.NULL)
                ov.put("contextWindow", entry.overrides.contextWindow ?: JSONObject.NULL)
                put("overrides", ov)
                put("userModifiedAt", entry.userModifiedAt ?: JSONObject.NULL)
            })
        }
        return JSONObject().apply {
            put("instanceId", instanceId)
            put("instanceLabel", instance.label)
            put("count", arr.length())
            put("entries", arr)
        }
    }

    fun export(context: Context, params: JSONObject): JSONObject {
        val instanceId = params.optString("instanceId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'instanceId' param")
        }
        val repo = repo(context)
        val instance = repo.instance(instanceId)
            ?: throw RPCException(-32602, "Instance not found: $instanceId")
        val raw = repo.exportInstanceJSON(instanceId)
            ?: throw RPCException(-32000, "Export failed for instance $instanceId")
        val payload = JSONObject(raw)
        return JSONObject().apply {
            put("version", 1)
            put("exportedAt", System.currentTimeMillis())
            put("platform", "android")
            put("instanceId", instanceId)
            put("instanceLabel", instance.label)
            put("config", payload)
        }
    }

    fun import(context: Context, params: JSONObject): JSONObject {
        val raw = params.optString("configJson", "").ifEmpty {
            throw RPCException(-32602, "Missing 'configJson' param")
        }
        val parsed = try { JSONObject(raw) } catch (e: Exception) {
            throw RPCException(-32602, "Invalid configJson: ${e.message}")
        }
        val configJson = if (parsed.has("config") && parsed.optJSONObject("config") != null) {
            parsed.optJSONObject("config")!!.toString()
        } else {
            raw
        }
        val repo = repo(context)
        val resolvedLabel = repo.importInstanceJSON(configJson)
            ?: throw RPCException(-32000, "Import failed — invalid or unsupported configJson")
        val newInstance = repo.config.value.instances.lastOrNull { it.label == resolvedLabel }
            ?: throw RPCException(-32000, "Import succeeded but instance lookup failed")
        return JSONObject().apply {
            put("instanceId", newInstance.id)
            put("instanceLabel", newInstance.label)
            put("modelEntryCount", repo.entriesFor(newInstance.id).size)
            put("success", true)
        }
    }

    fun groupsList(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val includeMembers = params.optBoolean("includeMembers", true)
        val cfg = repo.config.value
        val arr = JSONArray()
        for (group in cfg.modelGroups) {
            val isDefault = group.id == cfg.defaultPrimaryGroupId
            val obj = JSONObject().apply {
                put("id", group.id)
                put("name", group.name)
                put("strategy", group.strategy.name)
                put("fallbackStrategy", group.fallbackStrategy.name)
                put("isDefault", isDefault)
                put("inAgentLoop", group.id in cfg.agentLoopGroupIds)
                val ids = JSONArray()
                for (id in group.memberEntryIds) ids.put(id)
                put("memberEntryIds", ids)
            }
            if (includeMembers) {
                val members = JSONArray()
                for (memberId in group.memberEntryIds) {
                    val entry = cfg.modelEntries.find { it.id == memberId }
                        ?: com.openminis.app.data.model.SystemVoiceEntries.resolve(memberId)
                        ?: continue
                    val instLabel = cfg.instances.find { it.id == entry.providerInstanceId }?.label
                    members.put(JSONObject().apply {
                        put("entryId", entry.id)
                        put("modelId", entry.baseModel.id)
                        put("displayName", entry.model.displayName)
                        put("providerLabel", instLabel ?: "")
                    })
                }
                obj.put("members", members)
            }
            arr.put(obj)
        }
        return JSONObject().apply {
            put("defaultGroupId", cfg.defaultPrimaryGroupId ?: JSONObject.NULL)
            put("count", arr.length())
            put("groups", arr)
        }
    }

    suspend fun quickTest(context: Context, params: JSONObject): JSONObject {
        val entryId = params.optString("entryId")
        if (entryId.isEmpty()) throw RPCException(-32602, "Invalid params: 'entryId' is required")
        val repo = repo(context)
        val entry = repo.config.value.modelEntries.find { it.id == entryId }
            ?: com.openminis.app.data.model.SystemVoiceEntries.resolve(entryId)
            ?: throw RPCException(-32602, "Model entry not found: $entryId")

        val requested = params.optJSONArray("kinds")
        val kinds: List<com.openminis.app.ui.components.QuickTestKind> = if (requested == null) {
            com.openminis.app.ui.components.applicableKinds(entry)
        } else {
            (0 until requested.length()).mapNotNull { i ->
                when (requested.optString(i).lowercase()) {
                    "text" -> com.openminis.app.ui.components.QuickTestKind.TEXT
                    "speechout", "speech_out", "tts" -> com.openminis.app.ui.components.QuickTestKind.SPEECH_OUT
                    "transcription", "asr" -> com.openminis.app.ui.components.QuickTestKind.TRANSCRIPTION
                    "imagegen", "image_gen", "image" -> com.openminis.app.ui.components.QuickTestKind.IMAGE_GEN
                    else -> null
                }
            }
        }
        if (kinds.isEmpty()) throw RPCException(-32602, "No applicable test kinds")

        val results = JSONArray()
        for (kind in kinds) {
            val t0 = System.currentTimeMillis()
            val state = try {
                com.openminis.app.ui.components.performTest(kind, entry, repo, context)
            } catch (e: Exception) {
                com.openminis.app.ui.components.QuickTestState.Failure(
                    e.message ?: e::class.java.simpleName,
                )
            }
            val elapsed = System.currentTimeMillis() - t0
            results.put(JSONObject().apply {
                put("kind", kind.name.lowercase())
                put("elapsedMs", elapsed)
                when (state) {
                    is com.openminis.app.ui.components.QuickTestState.TextReply -> {
                        put("status", "ok"); put("detail", state.text.take(400))
                    }
                    is com.openminis.app.ui.components.QuickTestState.AudioReply -> {
                        put("status", "ok")
                        put("bytes", state.data.size)
                        put("detail", "audio ${state.data.size} bytes")
                    }
                    is com.openminis.app.ui.components.QuickTestState.ImageReply -> {
                        put("status", "ok")
                        put("bytes", state.data.size)
                        put("detail", "image ${state.data.size} bytes")
                    }
                    is com.openminis.app.ui.components.QuickTestState.Failure -> {
                        put("status", "failed"); put("detail", state.message.take(600))
                    }
                    else -> { put("status", "unknown"); put("detail", state.toString().take(200)) }
                }
            })
        }
        val inst = repo.config.value.instances.find { it.id == entry.providerInstanceId }
        return JSONObject().apply {
            put("entryId", entry.id)
            put("modelId", entry.model.id)
            put("displayName", entry.model.displayName)
            put("providerLabel", inst?.label ?: "")
            put("results", results)
        }
    }
}
