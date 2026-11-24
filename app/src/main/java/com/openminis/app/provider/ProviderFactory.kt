package com.openminis.app.provider

import android.content.Context
import com.openminis.app.auth.OpenAIOAuthManager
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider

object ProviderFactory {
    fun oneOffSessionId(): String = java.util.UUID.randomUUID().toString()

    fun create(
        instance: ProviderInstance,
        apiKey: String,
        model: LLMModel,
        context: Context? = null,
        sessionId: String?,
        overrides: com.openminis.app.data.model.ModelOverrides? = null,
    ): LLMProvider {
        // T174: route through ProviderInstance.effectiveBaseURL instead of
        val basePath = instance.effectiveBaseURL
        val provider: LLMProvider = when (instance.providerType) {
            ProviderType.anthropic -> {
                val isOAuth = instance.credentialType == ProviderCredential.oauth
                val anthropic = if (basePath != null) AnthropicProvider(apiKey, model, basePath, isOAuth = isOAuth, customUserAgent = instance.customUserAgent)
                else AnthropicProvider(apiKey, model, isOAuth = isOAuth)
                if (isOAuth) anthropic.quotaInstanceId = instance.id
                anthropic
            }
            ProviderType.gemini -> {
                if (basePath != null) GeminiProvider(apiKey, model, basePath)
                else GeminiProvider(apiKey, model)
            }
            ProviderType.openAI, ProviderType.openAIResponses -> {
                val manualBearer = if (context != null &&
                    instance.credentialType == ProviderCredential.oauth) {
                    com.openminis.app.auth.OAuthManager.forInstance(context, instance)?.loadManualBearerToken()
                } else null

                if (instance.credentialType == ProviderCredential.oauth && manualBearer.isNullOrEmpty()
                    && basePath == null && context != null) {
                    val oauthManager = OpenAIOAuthManager(context, instance.id)
                    OpenAIProvider(
                        oauthTokenProvider = { oauthManager.validAccessToken() ?: throw com.openminis.app.data.model.LLMError.InvalidApiKey() },
                        model = model,
                        codexAccountId = oauthManager.accountId,
                    ).also {
                        it.quotaInstanceId = instance.id
                    }
                } else {
                    val base = basePath ?: "https://api.openai.com/v1"
                    val effectiveKey = if (!manualBearer.isNullOrEmpty()) manualBearer else apiKey
                    OpenAIProvider(
                        apiKey = effectiveKey,
                        model = model,
                        basePath = base,
                        useResponsesAPI = instance.useResponsesAPI ||
                            instance.providerType == ProviderType.openAIResponses,
                        customUserAgent = instance.customUserAgent,
                        isAzure = instance.azureMode,
                        azureBase = instance.customBaseURL,
                    )
                }
            }
            ProviderType.openRouter -> {
                OpenAIProvider(
                    apiKey = apiKey,
                    model = model,
                    basePath = "https://openrouter.ai/api/v1",
                    extraHeaders = mapOf(
                        "HTTP-Referer" to "https://github.com/OpenMinis/OpenMinis",
                        "X-Title" to "Minis App",
                    ),
                )
            }
            ProviderType.xAI -> {
                val base = basePath ?: "https://api.x.ai/v1"
                val manualBearer = if (context != null &&
                    instance.credentialType == ProviderCredential.oauth) {
                    com.openminis.app.auth.OAuthManager.forInstance(context, instance)?.loadManualBearerToken()
                } else null
                if (instance.credentialType == ProviderCredential.oauth && manualBearer.isNullOrEmpty()
                    && context != null) {
                    val oauthManager = com.openminis.app.auth.XAIOAuthManager(context, instance.id)
                    OpenAIProvider.oauthOpenAICompat(
                        oauthTokenProvider = {
                            oauthManager.validAccessToken()
                                ?: throw com.openminis.app.data.model.LLMError.InvalidApiKey()
                        },
                        model = model,
                        basePath = base,
                    ).also { it.supportsPriorityProcessing = true }
                } else {
                    val effectiveKey = if (!manualBearer.isNullOrEmpty()) manualBearer else apiKey
                    OpenAIProvider(
                        apiKey = effectiveKey,
                        model = model,
                        basePath = base,
                    ).also { it.supportsPriorityProcessing = true }
                }
            }
            ProviderType.kimiCode -> {
                val base = basePath ?: "${com.openminis.app.auth.KimiDeviceFlow.CODING_API_BASE}/v1"
                val manualBearer = if (context != null &&
                    instance.credentialType == ProviderCredential.oauth) {
                    com.openminis.app.auth.OAuthManager.forInstance(context, instance)?.loadManualBearerToken()
                } else null
                if (instance.credentialType == ProviderCredential.oauth && manualBearer.isNullOrEmpty()
                    && context != null) {
                    val oauthManager = com.openminis.app.auth.KimiOAuthManager(context, instance.id)
                    OpenAIProvider.oauthOpenAICompat(
                        oauthTokenProvider = {
                            oauthManager.validAccessToken()
                                ?: throw com.openminis.app.data.model.LLMError.InvalidApiKey()
                        },
                        model = model,
                        basePath = base,
                    )
                } else {
                    val effectiveKey = if (!manualBearer.isNullOrEmpty()) manualBearer else apiKey
                    OpenAIProvider(
                        apiKey = effectiveKey,
                        model = model,
                        basePath = base,
                    )
                }
            }
            ProviderType.githubCopilot -> {
                if (context == null) throw com.openminis.app.data.model.LLMError.InvalidApiKey()
                val oauthManager = com.openminis.app.auth.CopilotOAuthManager(context, instance.id)
                OpenAIProvider.oauthOpenAICompat(
                    oauthTokenProvider = {
                        oauthManager.validAccessToken()
                            ?: throw com.openminis.app.data.model.LLMError.InvalidApiKey()
                    },
                    model = model,
                    basePath = basePath ?: com.openminis.app.auth.CopilotDeviceFlow.API_BASE,
                    extraHeaders = com.openminis.app.auth.CopilotDeviceFlow.staticChatHeaders(),
                ).also { p ->
                    p.perRequestHeaders = { body ->
                        com.openminis.app.auth.CopilotDeviceFlow.perRequestHeaders(body)
                    }
                }
            }
            ProviderType.workBuddy -> {
                if (context == null) throw com.openminis.app.data.model.LLMError.InvalidApiKey()
                val oauthManager = com.openminis.app.auth.WorkBuddyOAuthManager(context, instance.id)
                val account = oauthManager.currentAccount()
                    ?: throw com.openminis.app.data.model.LLMError.InvalidApiKey()
                val backend = account.backend.ifBlank { com.openminis.app.auth.WorkBuddyApi.DOMESTIC_BACKEND }

                val staticHeaders = LinkedHashMap<String, String>().apply {
                    putAll(com.openminis.app.auth.WorkBuddyApi.authHeaders("", account.domain, account.enterpriseId, account.uid))
                    remove("Authorization")
                }
                OpenAIProvider.oauthOpenAICompat(
                    oauthTokenProvider = {
                        oauthManager.validAccessToken()
                            ?: throw com.openminis.app.data.model.LLMError.InvalidApiKey()
                    },
                    model = model,
                    basePath = backend,
                    extraHeaders = staticHeaders,
                ).also { p ->
                    p.perRequestHeaders = {
                        com.openminis.app.auth.WorkBuddyApi.chatExtraHeaders(account.conversationId)
                    }
                    p.absoluteEndpointOverride = "/v2/chat/completions"
                }
            }
            ProviderType.qoder -> {
                if (context == null) throw com.openminis.app.data.model.LLMError.InvalidApiKey()
                QoderChatProvider(context, model)
            }

            ProviderType.minimaxCode -> {
                if (context == null) throw com.openminis.app.data.model.LLMError.InvalidApiKey()
                val mmRegion = com.openminis.app.auth.BuiltInAccountStore.activeRegion(context, "minimax")
                val token = runCatching {
                    kotlinx.coroutines.runBlocking {
                        com.openminis.app.auth.BuiltInAccountStore.validToken(context, "minimax")
                    }
                }.getOrNull()
                    ?: apiKey.takeIf { it.isNotBlank() }
                    ?: throw com.openminis.app.data.model.LLMError.InvalidApiKey()
                AnthropicProvider(
                    apiKey = token,
                    model = model,
                    basePath = com.openminis.app.auth.MiniMaxCodeApi.messagesBase(mmRegion),
                    isOAuth = false,
                )
            }

            ProviderType.antigravity, ProviderType.unsupported -> {
                throw com.openminis.app.data.model.LLMError.InvalidApiKey()
            }
        }
        (provider as? OpenAIProvider)?.thinkingRuleInstanceId = instance.id
        (provider as? com.openminis.app.network.NetworkTagged)?.netTag =
            com.openminis.app.network.NetworkRuntime.providerTag(instance.id)
        if (context != null) {
            (provider as? OpenAIProvider)?.responseIdleTimeoutSec =
                ResponseIdleTimeoutSetting.seconds(context, instance.id)
        }
        (provider as? OpenAIProvider)?.sessionId = sessionId
        (provider as? OpenAIProvider)?.modelOverrides = overrides?.takeIf { !it.isEmpty }
        return provider
    }
}
