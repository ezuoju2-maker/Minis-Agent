package com.openminis.app.provider.voice

import android.util.Log
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType

object VoiceProviderFactory {

    private const val TAG = "VoiceFactory"

    fun make(instance: ProviderInstance, apiKey: String?): VoiceProvider? =
        makeVendor(instance, apiKey)?.also { it.customUserAgent = instance.customUserAgent }

    private fun makeVendor(instance: ProviderInstance, apiKey: String?): VoiceProvider? {
        val custom = instance.customBaseURL
        val normalizedBase = (custom ?: "").lowercase()

        return when (instance.providerType) {
            ProviderType.openAI, ProviderType.openRouter, ProviderType.openAIResponses -> {
                if (instance.providerType == ProviderType.openRouter) {
                    return OpenRouterVoiceProvider(
                        instance.id,
                        custom ?: "https://openrouter.ai/api",
                        apiKey,
                    )
                }
                when {
                    normalizedBase.contains("groq.com") ->
                        GroqVoiceProvider(instance.id, custom ?: "https://api.groq.com/openai", apiKey)
                    normalizedBase.contains("dashscope") ->
                        AlibabaVoiceProvider(instance.id, custom ?: "https://dashscope.aliyuncs.com/compatible-mode", apiKey)
                    ZhipuVoiceProvider.matches(normalizedBase) ->
                        ZhipuVoiceProvider(instance.id, custom ?: ZhipuVoiceProvider.DEFAULT_ORIGIN, apiKey)
                    normalizedBase.contains("minimax") ->
                        MiniMaxVoiceProvider(instance.id, custom ?: "https://api.minimax.io", apiKey)
                    normalizedBase.contains("openspeech.bytedance") || normalizedBase.contains("volcano") -> {
                        Log.i(TAG, "Doubao voice provider: base=$normalizedBase keyLen=${apiKey?.length ?: -1}")
                        DoubaoVoiceProvider(instance.id, apiKey)
                    }
                    normalizedBase.contains("xfyun") -> {
                        val p = splitCompound(apiKey)
                        if (p.size >= 3) XunfeiVoiceProvider(instance.id, p[0], p[1], p[2]) else null
                    }
                    normalizedBase.contains("xiaomimimo") ->
                        MimoVoiceProvider(instance.id, custom ?: "https://api.xiaomimimo.com", apiKey)
                    normalizedBase.contains("elevenlabs") ->
                        ElevenLabsVoiceProvider(instance.id, custom ?: "https://api.elevenlabs.io", apiKey)
                    normalizedBase.contains("tts.speech.microsoft.com") -> {
                        var azureBase = custom ?: "https://eastasia.tts.speech.microsoft.com"
                        if (azureBase.endsWith("/cognitiveservices")) {
                            azureBase = azureBase.removeSuffix("/cognitiveservices")
                        }
                        AzureTTSVoiceProvider(instance.id, azureBase, apiKey)
                    }
                    normalizedBase.contains("deepgram") ->
                        DeepgramVoiceProvider(instance.id, custom ?: "https://api.deepgram.com", apiKey)
                    else ->
                        VoiceProvider(instance.id, custom ?: "https://api.openai.com", apiKey)
                }
            }

            ProviderType.xAI ->
                XAIVoiceProvider(instance.id, custom ?: "https://api.x.ai", apiKey)

            ProviderType.anthropic ->
                if (normalizedBase.contains("minimax")) {
                    MiniMaxVoiceProvider(instance.id, custom ?: "https://api.minimax.io", apiKey)
                } else {
                    null
                }

            ProviderType.gemini ->
                GeminiVoiceProvider(
                    instance.id,
                    custom ?: "https://generativelanguage.googleapis.com/v1beta",
                    apiKey,
                )

            ProviderType.kimiCode -> null
            ProviderType.githubCopilot -> null
            ProviderType.antigravity, ProviderType.unsupported, ProviderType.workBuddy,
            ProviderType.qoder, ProviderType.minimaxCode -> null
        }
    }

    fun supports(instance: ProviderInstance, apiKey: String?): Boolean =
        make(instance, apiKey) != null

    fun splitCompound(value: String?): List<String> {
        if (value.isNullOrEmpty()) return emptyList()
        return value.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
