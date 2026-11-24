package com.openminis.app.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject
import java.util.UUID

@Serializable
enum class ProviderType(val displayName: String) {
    anthropic("Anthropic"),
    gemini("Google Gemini"),
    openAI("OpenAI"),
    openRouter("OpenRouter"),
    xAI("xAI (Grok)"),
    kimiCode("Kimi Code"),

    githubCopilot("GitHub Copilot"),


    openAIResponses("Responses API (v3)"),

    antigravity("Antigravity"),

    workBuddy("WorkBuddy"),

    qoder("Qoder"),

    minimaxCode("MiniMax Code"),

    unsupported("Unsupported");

    val isUsable: Boolean
        get() = when (this) {
            anthropic, gemini, openAI, openRouter, xAI, kimiCode, openAIResponses,
            githubCopilot, workBuddy, qoder, minimaxCode -> true
            antigravity, unsupported -> false
        }

    val builtInModels: List<LLMModel>
        get() = when (this) {
            anthropic -> LLMModel.allAnthropic
            gemini -> LLMModel.allGemini
            openAI -> LLMModel.allOpenAI
            openRouter -> LLMModel.allOpenRouter
            xAI -> LLMModel.allXAI
            kimiCode -> LLMModel.allKimi
            githubCopilot, openAIResponses, antigravity, unsupported,
            workBuddy, qoder, minimaxCode -> emptyList()
        }

    companion object {
        fun decoded(raw: String): ProviderType =
            entries.firstOrNull { it.name == raw } ?: unsupported
    }
}

@Serializable
enum class ProviderCredential {
    apiKey,
    oauth,
}

@Serializable(with = ThinkingLevelSerializer::class)
enum class ThinkingLevel {
    OFF, LOW, MEDIUM, HIGH, XHIGH, MAX, ULTRA;

    val isEnabled: Boolean get() = this != OFF

    val displayName: String
        get() = when (this) {
            OFF -> "Off"
            LOW -> "Low"
            MEDIUM -> "Medium"
            HIGH -> "High"
            XHIGH -> "XHigh"
            MAX -> "Max"
            ULTRA -> "Ultra"
        }

    val rank: Int get() = ordinal

    companion object {
        fun decoded(raw: String): ThinkingLevel = parseOrNull(raw) ?: XHIGH

        fun parseOrNull(raw: String): ThinkingLevel? =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}

object ThinkingLevelSerializer : KSerializer<ThinkingLevel> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.openminis.app.data.model.ThinkingLevel", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ThinkingLevel) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): ThinkingLevel = ThinkingLevel.decoded(decoder.decodeString())
}

@Serializable
enum class RoutingStrategy {
    fallback,
    loadBalance,
}

@Serializable
enum class ImageEndpointMode {
    auto,
    @SerialName("images_generations") imagesGenerations,
    @SerialName("chat_completions") chatCompletions,
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
enum class FallbackStrategy {
    @kotlinx.serialization.json.JsonNames("limited") default,
    always,
}

@Serializable
data class ModelGroup(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val memberEntryIds: MutableList<String> = mutableListOf(),
    var strategy: RoutingStrategy = RoutingStrategy.fallback,
    var fallbackStrategy: FallbackStrategy = FallbackStrategy.default,
    var defaultThinkingLevel: ThinkingLevel? = null,
    var contextLimitTokens: Int? = null,
    var lastContextLimitTokens: Int? = null,
)

@Serializable
data class ProviderInstance(
    val id: String,
    var label: String,
    val providerType: ProviderType,
    val credentialType: ProviderCredential,
    var isEnabled: Boolean = true,
    @Serializable(with = com.openminis.app.backup.Iso8601MillisSerializer::class)
    val createdAt: Long = System.currentTimeMillis(),
    var customBaseURL: String? = null,
    var appendV1Suffix: Boolean = true,
    var customUserAgent: String? = null,
    var useResponsesAPI: Boolean = false,
    var imageEndpointMode: ImageEndpointMode = ImageEndpointMode.auto,
    var imageEndpointResolved: ImageEndpointMode? = null,
    var azureMode: Boolean = false,

) {
    val effectiveBaseURL: String?
        get() {
            val base = customBaseURL?.trimEnd('/') ?: return null
            return if (appendV1Suffix && !base.endsWith("/v1")) "$base/v1" else base
        }

    val allowsEmptyAPIKey: Boolean
        get() = credentialType == ProviderCredential.apiKey &&
            !customBaseURL.isNullOrBlank() &&
            (providerType == ProviderType.openAI || providerType == ProviderType.anthropic)

    val supportsImageEndpointSetting: Boolean
        get() = providerType == ProviderType.openAI ||
            providerType == ProviderType.openRouter ||
            providerType == ProviderType.xAI

    val supportsAzureMode: Boolean
        get() = providerType == ProviderType.openAI && credentialType == ProviderCredential.apiKey


    val supportsCustomThinkingRules: Boolean
        get() = when (providerType) {
            ProviderType.anthropic, ProviderType.gemini -> false
            else -> {
                val codexOAuth = credentialType == ProviderCredential.oauth &&
                    customBaseURL.isNullOrBlank()
                !useResponsesAPI && !codexOAuth
            }
        }
}

@Serializable
data class ModelOverrides(
    val displayName: String? = null,
    val maxOutputTokens: Int? = null,
    val contextWindow: Int? = null,
    val supportsReasoning: Boolean? = null,
    val inputModalities: List<String>? = null,
    val outputModalities: List<String>? = null,
    val maxThinkingLevel: ThinkingLevel? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val customHeaders: Map<String, String>? = null,
    val extraBodyParams: JsonObject? = null,
) {
    val isEmpty: Boolean
        get() = displayName == null
            && maxOutputTokens == null
            && contextWindow == null
            && supportsReasoning == null
            && inputModalities == null
            && outputModalities == null
            && maxThinkingLevel == null
            && temperature == null
            && topP == null
            && customHeaders == null
            && extraBodyParams == null
}

@Serializable
data class ModelEntry(
    val providerInstanceId: String,
    @SerialName("model")
    val baseModel: LLMModel,
    val overrides: ModelOverrides = ModelOverrides(),
    val isCustom: Boolean = false,
    val isHidden: Boolean = false,
    val uuid: String = UUID.randomUUID().toString(),
    @Serializable(with = com.openminis.app.backup.Iso8601MillisNullableSerializer::class)
    val userModifiedAt: Long? = null,
    @Serializable(with = com.openminis.app.backup.Iso8601MillisNullableSerializer::class)
    val absentSince: Long? = null,
) {
    val id: String get() = uuid

    val isUnavailableFromProvider: Boolean get() = absentSince != null

    val model: LLMModel
        get() {
            val effIn = baseModel.effectiveInputModalities
            val effOut = baseModel.effectiveOutputModalities
            val fallbackPending =
                (baseModel.inputModalities == null && effIn != null) ||
                    (baseModel.outputModalities == null && effOut != null)
            if (overrides.isEmpty && !fallbackPending) return baseModel
            return baseModel.copy(
                displayName = overrides.displayName ?: baseModel.displayName,
                maxOutputTokens = overrides.maxOutputTokens ?: baseModel.maxOutputTokens,
                contextWindow = overrides.contextWindow ?: baseModel.contextWindow,
                supportsReasoning = overrides.supportsReasoning ?: baseModel.supportsReasoning,
                inputModalities = overrides.inputModalities
                    ?.let { it.normalizeModalities() ?: emptyList() } ?: effIn,
                outputModalities = overrides.outputModalities
                    ?.let { it.normalizeModalities() ?: emptyList() } ?: effOut,
            )
        }

    val isUserModified: Boolean
        get() = isCustom || isHidden || !overrides.isEmpty
}

@Serializable
data class ProviderConfig(
    val instances: MutableList<ProviderInstance> = mutableListOf(),
    val modelEntries: MutableList<ModelEntry> = mutableListOf(),
    val modelGroups: MutableList<ModelGroup> = mutableListOf(),
    var defaultPrimaryGroupId: String? = null,
    var defaultSubGroupId: String? = null,
    // [T-android-provider-voice] Voice Input / Voice Output group bindings —
    var voiceInputGroupId: String? = null,
    var voiceOutputGroupId: String? = null,
    var visionGroupId: String? = null,
    val subAgents: MutableList<SubAgentDefinition> = mutableListOf(),
    val agentLoopModelEntryIds: MutableList<String> = mutableListOf(),
    val agentLoopGroupIds: MutableList<String> = mutableListOf(),
    @kotlinx.serialization.Transient var revision: Long = nextRevision(),
) {
    companion object {
        private val revisionSeq = java.util.concurrent.atomic.AtomicLong(1L)

        fun nextRevision(): Long = revisionSeq.getAndIncrement()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProviderConfig) return false
        return revision == other.revision
    }

    override fun hashCode(): Int = revision.hashCode()
}
