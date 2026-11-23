package com.openminis.app.data.model

object SystemVoiceEntries {

    val asrOnline = ModelEntry(
        providerInstanceId = SystemVoiceIds.BUILTIN_PROVIDER_ID,
        baseModel = LLMModel(
            id = SystemVoiceIds.SYSTEM_ASR_ONLINE,
            displayName = "System Recognition (Online)",
            provider = "system",
            inputModalities = listOf("audio"),
        ),
        isHidden = true,
        uuid = "${SystemVoiceIds.BUILTIN_PROVIDER_ID}/${SystemVoiceIds.SYSTEM_ASR_ONLINE}",
    )

    val asrOffline = ModelEntry(
        providerInstanceId = SystemVoiceIds.BUILTIN_PROVIDER_ID,
        baseModel = LLMModel(
            id = SystemVoiceIds.SYSTEM_ASR_OFFLINE,
            displayName = "System Recognition (Offline)",
            provider = "system",
            inputModalities = listOf("audio"),
        ),
        isHidden = true,
        uuid = "${SystemVoiceIds.BUILTIN_PROVIDER_ID}/${SystemVoiceIds.SYSTEM_ASR_OFFLINE}",
    )

    val tts = ModelEntry(
        providerInstanceId = SystemVoiceIds.BUILTIN_PROVIDER_ID,
        baseModel = LLMModel(
            id = SystemVoiceIds.SYSTEM_TTS,
            displayName = "System Voice (Auto)",
            provider = "system",
            outputModalities = listOf("audio"),
        ),
        isHidden = true,
        uuid = "${SystemVoiceIds.BUILTIN_PROVIDER_ID}/${SystemVoiceIds.SYSTEM_TTS}",
    )

    val all: List<ModelEntry> = listOf(asrOnline, asrOffline, tts)

    fun resolve(memberId: String): ModelEntry? = all.find { it.id == memberId }

    fun isSystemEntryId(id: String): Boolean =
        id.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)

    fun syntheticInstance(label: String = "System") = ProviderInstance(
        id = SystemVoiceIds.BUILTIN_PROVIDER_ID,
        label = label,
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        isEnabled = true,
    )
}
