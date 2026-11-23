package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel

sealed interface ThinkingWireFormat {

    data object OmitEverything : ThinkingWireFormat

    data class ReasoningEffort(val offValue: String?) : ThinkingWireFormat

    data class ReasoningEffortNested(val offValue: String?) : ThinkingWireFormat

    data object DeepSeekSibling : ThinkingWireFormat

    data object QwenDual : ThinkingWireFormat


    data class AnthropicThinking(val style: AnthropicThinkingStyle) : ThinkingWireFormat

    data class GeminiBudget(val floor: Int, val canDisable: Boolean) : ThinkingWireFormat

    data object GeminiThinkingLevel : ThinkingWireFormat

    data class BooleanToggle(val path: String) : ThinkingWireFormat

    data class ExtraBodyToggle(val path: String) : ThinkingWireFormat

    data class CustomPath(
        val path: String,
        val values: Map<ThinkingLevel, String>,
        val offValue: String?,
    ) : ThinkingWireFormat
}

enum class AnthropicThinkingStyle {
    ADAPTIVE,

    BUDGET_TOKENS,
}
