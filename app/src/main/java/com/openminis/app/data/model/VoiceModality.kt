package com.openminis.app.data.model

object SystemVoiceIds {
    const val BUILTIN_PROVIDER_ID = "__builtin_system_speech__"
    const val SYSTEM_ASR_ONLINE = "system-asr-online"
    const val SYSTEM_ASR_OFFLINE = "system-asr-offline"
    const val SYSTEM_TTS = "system-tts"
}

object VoiceModality {

    private val asrInferencePatterns = listOf(
        "-asr", "asr-", "_asr", "asr_", "whisper", "transcrib", "speech-to-text",
        "speech2text", "stt-", "-stt", "_stt", "stt_", "voice-input", "voice_input",
        "paraformer", "sensevoice", "fun-asr", "qwen-asr",
    )

    private val ttsInferencePatterns = listOf(
        "tts", "-tts", "_tts", "text-to-speech", "text2speech", "audio-gen",
        "audio-generation", "seed-tts", "voice-output", "voice_output",
    )

    fun inferDedicatedVoiceModality(id: String, displayName: String): Pair<List<String>, List<String>>? {
        val hay = "$id $displayName".lowercase()
        if (asrInferencePatterns.any { hay.contains(it) }) {
            return listOf("audio") to listOf("text")
        }
        if (ttsInferencePatterns.any { hay.contains(it) }) {
            return listOf("text") to listOf("audio")
        }
        return null
    }
}

private val LLMModel.normalizedInputs: List<String>? get() = inputModalities.normalizeModalities()
private val LLMModel.normalizedOutputs: List<String>? get() = outputModalities.normalizeModalities()

val LLMModel.hasAudioInput: Boolean
    get() = effectiveInputModalities?.contains("audio") == true

val LLMModel.hasAudioOutput: Boolean
    get() = effectiveOutputModalities?.contains("audio") == true

val LLMModel.hasImageInput: Boolean
    get() = effectiveInputModalities?.contains("image") == true

val LLMModel.hasVoiceModality: Boolean
    get() = hasAudioInput || hasAudioOutput

object VoiceRole {
    const val TTS = "tts"
    const val STT = "stt"
    const val NONE = "none"

    val OPENROUTER_CHAT_AUDIO_MODELS = setOf("openai/gpt-audio", "openai/gpt-audio-mini")
}

val LLMModel.isVoiceInputCandidate: Boolean
    get() = when (voiceRole) {
        null -> hasAudioInput
        VoiceRole.STT -> true
        else -> id in VoiceRole.OPENROUTER_CHAT_AUDIO_MODELS
    }

val LLMModel.isVoiceOutputCandidate: Boolean
    get() = when (voiceRole) {
        null -> hasAudioOutput
        VoiceRole.TTS -> true
        else -> id in VoiceRole.OPENROUTER_CHAT_AUDIO_MODELS
    }

val LLMModel.isVoiceCandidate: Boolean
    get() = isVoiceInputCandidate || isVoiceOutputCandidate

val LLMModel.isVoiceTemplateSeedShape: Boolean
    get() {
        val ins = normalizedInputs
        val outs = normalizedOutputs
        val asrSeed = ins == listOf("audio") && outs == null
        val ttsSeed = ins == null && outs == listOf("audio")
        return asrSeed || ttsSeed
    }

fun LLMModel.withInferredVoiceModality(): LLMModel {
    if (inputModalities != null || outputModalities != null) return this
    val (ins, outs) = VoiceModality.inferDedicatedVoiceModality(id, displayName) ?: return this
    return copy(inputModalities = ins, outputModalities = outs)
}
