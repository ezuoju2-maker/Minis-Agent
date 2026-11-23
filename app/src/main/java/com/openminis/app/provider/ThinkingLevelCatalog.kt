package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ThinkingLevel

object ThinkingLevelCatalog {
    private data class Rule(val match: (String) -> Boolean, val max: ThinkingLevel)

    private val rules: List<Rule> = listOf(
        Rule({ it.startsWith("gpt-6-astra") }, ThinkingLevel.MAX),
        Rule({ it.startsWith("gpt-6-sol") || it.startsWith("gpt-6-luna") }, ThinkingLevel.MAX),
        Rule({ it.startsWith("gpt-6.1-sol") || it.startsWith("gpt-6-1-sol") }, ThinkingLevel.MAX),
        Rule({ it.startsWith("gpt-5.6-sol") || it.startsWith("gpt-5.6-terra") }, ThinkingLevel.MAX),
        Rule({ it.startsWith("gpt-5.6-luna") }, ThinkingLevel.MAX),
        Rule({ it.startsWith("gpt-5.5") }, ThinkingLevel.XHIGH),
        Rule({ it.contains("mimo") || it.contains("agnes") }, ThinkingLevel.HIGH),
        Rule({ it.contains("seed-") || it.contains("bytedance-seed") }, ThinkingLevel.HIGH),
        Rule({ it.startsWith("deepseek-flash") || it.contains("deepseek-v4") }, ThinkingLevel.MAX),
        Rule({ normalizedHasPrefix(it, "claude-opus-4") }, ThinkingLevel.MAX),
        Rule({ normalizedHasPrefix(it, "claude-opus-5") }, ThinkingLevel.MAX),
        Rule({ normalizedHasPrefix(it, "claude-sonnet-5-5") }, ThinkingLevel.MAX),
    )

    private fun normalizedHasPrefix(id: String, prefix: String): Boolean =
        id.replace('.', '-').startsWith(prefix)

    fun declaredMaxLevel(modelId: String): ThinkingLevel? {
        val lid = modelId.lowercase()
        return rules.firstOrNull { it.match(lid) }?.max
    }
}

val LLMModel.catalogMaxThinkingLevel: ThinkingLevel
    get() {
        if (supportsReasoning == false) return ThinkingLevel.OFF
        val ruleTop = ThinkingLevelCatalog.declaredMaxLevel(id)
        selectableThinkingLevels.lastOrNull()?.let { declaredTop ->
            return if (ruleTop != null && ruleTop.rank > declaredTop.rank) ruleTop else declaredTop
        }
        return ruleTop ?: ThinkingLevel.XHIGH
    }

val LLMModel.selectableThinkingLevels: List<ThinkingLevel>
    get() {
        val declared = reasoningEffortValues
        if (declared.isNullOrEmpty()) return emptyList()
        val mapping = listOf(
            "low" to ThinkingLevel.LOW,
            "medium" to ThinkingLevel.MEDIUM,
            "high" to ThinkingLevel.HIGH,
            "xhigh" to ThinkingLevel.XHIGH,
            "max" to ThinkingLevel.MAX,
        )
        val set = declared.map { it.lowercase() }.toSet()
        return mapping.filter { set.contains(it.first) }.map { it.second }
    }

val ModelEntry.effectiveMaxThinkingLevel: ThinkingLevel
    get() = overrides.maxThinkingLevel ?: model.catalogMaxThinkingLevel
