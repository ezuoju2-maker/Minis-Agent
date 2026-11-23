package com.openminis.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class LLMModel(
    val id: String,
    val displayName: String,
    val provider: String,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val limitsReportedByEndpoint: Boolean? = null,
    val supportsReasoning: Boolean? = null,
    val interleavedReasoningField: String? = null,
    val reasoningEffortValues: List<String>? = null,
    val declaresNoEffortTiers: Boolean? = null,
    val inputModalities: List<String>? = null,
    val outputModalities: List<String>? = null,
    val voiceRole: String? = null,
    val serviceTiers: List<String>? = null,
) {
    companion object {
        val claudeFable51 = LLMModel("claude-fable-5-1", "Claude Fable 5.1", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true)
        val claudeFable5 = LLMModel("claude-fable-5", "Claude Fable 5", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true)
        val claudeOpus55 = LLMModel("claude-opus-5-5", "Claude Opus 5.5", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true)
        val claudeOpus48 = LLMModel("claude-opus-4-8", "Claude Opus 4.8", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true)
        val claudeOpus46 = LLMModel("claude-opus-4-6", "Claude Opus 4.6", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true)
        val claudeSonnet55 = LLMModel("claude-sonnet-5-5", "Claude Sonnet 5.5", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true)
        val claudeSonnet5 = LLMModel("claude-sonnet-5", "Claude Sonnet 5", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 64_000, supportsReasoning = true)
        val claudeSonnet46 = LLMModel("claude-sonnet-4-6", "Claude Sonnet 4.6", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 64_000, supportsReasoning = true)
        val claudeHaiku45 = LLMModel("claude-haiku-4-5", "Claude Haiku 4.5", "Anthropic", contextWindow = 200_000, maxOutputTokens = 64_000, supportsReasoning = true)

        val allAnthropic = listOf(claudeFable51, claudeFable5, claudeOpus55, claudeOpus48, claudeOpus46, claudeSonnet55, claudeSonnet5, claudeSonnet46, claudeHaiku45)

        val gemini3Pro = LLMModel("gemini-3-pro-preview", "Gemini 3 Pro (Preview)", "Google")
        val gemini3Flash = LLMModel("gemini-3-flash-preview", "Gemini 3 Flash (Preview)", "Google")
        val gemini25Pro = LLMModel("gemini-2.5-pro", "Gemini 2.5 Pro", "Google")
        val gemini25Flash = LLMModel("gemini-2.5-flash", "Gemini 2.5 Flash", "Google")
        val gemini25FlashLite = LLMModel("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite", "Google")

        val allGemini = listOf(gemini3Pro, gemini3Flash, gemini25Pro, gemini25Flash, gemini25FlashLite)

        val gpt55 = LLMModel("gpt-5.5", "GPT-5.5", "OpenAI", supportsReasoning = true)
        val gpt53Codex = LLMModel("gpt-5.3-codex", "GPT-5.3 Codex", "OpenAI", supportsReasoning = true)
        val gpt52Codex = LLMModel("gpt-5.2-codex", "GPT-5.2 Codex", "OpenAI", supportsReasoning = true)
        val gpt51CodexMax = LLMModel("gpt-5.1-codex-max", "GPT-5.1 Codex Max", "OpenAI", supportsReasoning = true)
        val gpt52 = LLMModel("gpt-5.2", "GPT-5.2", "OpenAI", supportsReasoning = true)
        val gpt4o = LLMModel("gpt-4o", "GPT-4o", "OpenAI")
        val gpt4oMini = LLMModel("gpt-4o-mini", "GPT-4o Mini", "OpenAI")
        val o3 = LLMModel("o3", "o3", "OpenAI", supportsReasoning = true)
        val o4Mini = LLMModel("o4-mini", "o4 Mini", "OpenAI", supportsReasoning = true)
        val codexMini = LLMModel("codex-mini-latest", "Codex Mini", "OpenAI", supportsReasoning = true)

        val allOpenAI = listOf(gpt55, gpt53Codex, gpt52Codex, gpt51CodexMax, gpt52, gpt4o, gpt4oMini, o3, o4Mini, codexMini)

        val orClaudeSonnet4 = LLMModel("anthropic/claude-sonnet-4", "Claude Sonnet 4", "OpenRouter")
        val orGemini25Flash = LLMModel("google/gemini-2.5-flash", "Gemini 2.5 Flash", "OpenRouter")
        val orGpt4o = LLMModel("openai/gpt-4o", "GPT-4o", "OpenRouter")
        val orLlamaMaverick = LLMModel("meta-llama/llama-4-maverick", "Llama 4 Maverick", "OpenRouter")

        val allOpenRouter = listOf(orClaudeSonnet4, orGemini25Flash, orGpt4o, orLlamaMaverick)

        val grok46 = LLMModel("grok-4.6", "Grok 4.6", "xAI", supportsReasoning = true)
        val grok45 = LLMModel("grok-4.5", "Grok 4.5", "xAI", supportsReasoning = true)
        val grok43 = LLMModel("grok-4.3", "Grok 4.3", "xAI", supportsReasoning = true)
        val grok420Reasoning = LLMModel("grok-4.20-0309-reasoning", "Grok 4.20 Reasoning", "xAI", supportsReasoning = true)
        val grok420NonReasoning = LLMModel("grok-4.20-0309-non-reasoning", "Grok 4.20", "xAI")
        val grok420MultiAgent = LLMModel("grok-4.20-multi-agent-0309", "Grok 4.20 Multi-Agent", "xAI", supportsReasoning = true)
        val grokBuild01 = LLMModel("grok-build-0.1", "Grok Build 0.1", "xAI")
        val grok3Mini = LLMModel("grok-3-mini", "Grok 3 Mini", "xAI", supportsReasoning = true)
        val grok3MiniFast = LLMModel("grok-3-mini-fast", "Grok 3 Mini Fast", "xAI", supportsReasoning = true)
        val grokComposer25Fast = LLMModel("grok-composer-2.5-fast", "Grok Composer 2.5 Fast", "xAI")
        val grok4Fast = LLMModel("grok-4-fast", "Grok 4 Fast", "xAI", supportsReasoning = true)
        val grok4FastNonReasoning = LLMModel("grok-4-fast-non-reasoning", "Grok 4 Fast (Non-Reasoning)", "xAI")
        val grokCodeFast1 = LLMModel("grok-code-fast-1", "Grok Code Fast 1", "xAI", supportsReasoning = true)

        val allXAI = listOf(
            grok46,
            grok45,
            grok43,
            grok420Reasoning,
            grok420NonReasoning,
            grok420MultiAgent,
            grokBuild01,
            grok3Mini,
            grok3MiniFast,
            grokComposer25Fast,
            grok4Fast,
            grok4FastNonReasoning,
            grokCodeFast1,
        )

        val kimiK3 = LLMModel("kimi-k3", "Kimi K3", "Kimi")
        val kimiK2 = LLMModel("kimi-k2", "Kimi K2", "Kimi")

        val allKimi = listOf(kimiK3, kimiK2)

        val allModels = allAnthropic + allGemini + allOpenAI + allOpenRouter + allXAI + allKimi

        fun modelDisplayName(fromId: String): String {
            if (fromId.isBlank()) return fromId
            val upperTokens = setOf(
                "gpt", "glm", "oss", "ai", "xl", "xxl", "vl", "llm", "moe", "api",
                "hd", "sd", "rp", "sft", "rl", "dpo", "gguf", "fp16", "bf16", "int4", "int8",
            )
            val brandRewrites = mapOf(
                "openai" to "OpenAI",
                "deepseek" to "DeepSeek",
                "chatgpt" to "ChatGPT",
                "llama" to "Llama",
                "gemma" to "Gemma",
                "phi" to "Phi",
                "mistral" to "Mistral",
                "mixtral" to "Mixtral",
                "qwen" to "Qwen",
                "yi" to "Yi",
            )
            return fromId
                .replace('/', ' ')
                .replace('-', ' ')
                .split(' ')
                .filter { it.isNotEmpty() }
                .joinToString(" ") { token ->
                    val lower = token.lowercase()
                    when {
                        brandRewrites.containsKey(lower) -> brandRewrites[lower]!!
                        upperTokens.contains(lower) -> lower.uppercase()
                        else -> token.replaceFirstChar { it.titlecase() }
                    }
                }
        }
    }

    val isTextOutput: Boolean
        get() {
            val out = outputModalities.normalizeModalities() ?: return true
            return "text" in out
        }

    val contextWindowTokens: Int
        get() {
            contextWindow?.let { if (it > 0) return it }
            val lid = id.lowercase()
            if (lid.contains("claude")) {
                if (lid.contains("haiku")) return 200_000
                if (lid.contains("claude-2") || lid.contains("claude-3")) return 200_000
                return 1_000_000
            }
            if (lid.contains("gemini")) {
                if (lid.contains("1.0")) return 32_000
                return 1_000_000
            }
            if (lid.contains("gpt-3.5")) return 16_000
            if (lid.contains("gpt-4o") || lid.contains("gpt-4-turbo")) return 128_000
            if (lid.contains("gpt-5")) return 400_000
            if (lid.contains("gpt-4")) return 8_000
            if (lid.contains("o3") || lid.contains("o4")) return 200_000
            if (lid.contains("codex")) return 200_000
            if (lid.contains("deepseek")) return 128_000
            if (lid.contains("grok")) {
                if (lid.contains("grok-2") || lid.contains("grok-3")) return 131_072
                return 256_000
            }
            return 128_000
        }

    fun capabilityPromptFragment(): String? {
        val inputs = inputModalities.normalizeModalities() ?: emptyList()
        val hasImage = "image" in inputs
        val hasPdf = "pdf" in inputs
        val hasAudio = "audio" in inputs
        val hasVideo = "video" in inputs
        if (hasImage && hasPdf && hasAudio && hasVideo) return null

        val natives = buildList {
            if (hasImage) add("images")
            if (hasPdf) add("PDFs")
            if (hasAudio) add("audio")
            if (hasVideo) add("video")
        }
        val missing = buildList {
            if (!hasImage) add("images")
            if (!hasPdf) add("PDFs")
            if (!hasAudio) add("audio")
            if (!hasVideo) add("video")
        }

        val sb = StringBuilder()
        if (natives.isNotEmpty()) {
            sb.append("You can natively process ").append(natives.joinToString(", ")).append(". ")
        }
        if (missing.isNotEmpty()) {
            sb.append("You cannot natively process ").append(missing.joinToString(", "))
            sb.append(" — for those formats, call shell_execute with ffmpeg or similar tools to extract text/metadata first.")
        }
        return sb.toString().trim().ifEmpty { null }
    }

    fun agentBehaviorPromptFragment(): String? {
        val idLower = id.lowercase()
        val providerLower = provider.lowercase()

        if (providerLower == "google" || idLower.contains("gemini")) {
            return "When you need to use a tool, invoke it via the function-calling mechanism directly. Do not emit tool invocations as plain text — they will not be executed."
        }

        val isCodex = idLower.contains("codex") ||
            Regex("gpt-5(?:\\.\\d+)?-codex").containsMatchIn(idLower)
        if (isCodex) {
            return "Act autonomously: don't stop at analysis, don't ask for permission on reversible local actions, and never announce \"I will use tool X\" without actually calling X. Keep iterating until the task is fully complete."
        }
        return null
    }
}

fun String.normalizeModalityName(): String =
    lowercase().removeSuffix("_input").removeSuffix("_output")

fun List<String>?.normalizeModalities(): List<String>? =
    this?.map { it.normalizeModalityName() }?.distinct()?.takeIf { it.isNotEmpty() }

private val VISION_INPUT = listOf("text", "image", "pdf")
private val FULL_MULTIMODAL_INPUT = listOf("text", "image", "pdf", "audio", "video")

private val PROVIDER_DEFAULT_INPUT_MODALITIES: Map<String, List<String>> = mapOf(
    "Anthropic" to VISION_INPUT,
    "OpenAI" to VISION_INPUT,
    "OpenRouter" to VISION_INPUT,
    "Google" to FULL_MULTIMODAL_INPUT,
    "Google Gemini" to FULL_MULTIMODAL_INPUT,
    "xAI" to VISION_INPUT,
    "xAI (Grok)" to VISION_INPUT,
    "Kimi" to VISION_INPUT,
    "Kimi Code" to VISION_INPUT,
    "GitHub Copilot" to VISION_INPUT,
)

private val PROVIDER_DEFAULT_OUTPUT_MODALITIES: Map<String, List<String>> = mapOf(
    "Anthropic" to listOf("text"),
    "OpenAI" to listOf("text"),
    "OpenRouter" to listOf("text"),
    "Google" to listOf("text"),
    "Google Gemini" to listOf("text"),
    "xAI" to listOf("text"),
    "xAI (Grok)" to listOf("text"),
    "Kimi" to listOf("text"),
    "Kimi Code" to listOf("text"),
    "GitHub Copilot" to listOf("text"),
)

val LLMModel.effectiveInputModalities: List<String>?
    get() = inputModalities.normalizeModalities()
        ?: PROVIDER_DEFAULT_INPUT_MODALITIES[provider]

val LLMModel.effectiveOutputModalities: List<String>?
    get() = outputModalities.normalizeModalities()
        ?: PROVIDER_DEFAULT_OUTPUT_MODALITIES[provider]
