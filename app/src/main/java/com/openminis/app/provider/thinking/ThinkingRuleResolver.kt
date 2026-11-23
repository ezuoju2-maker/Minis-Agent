package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONObject

data class ThinkingResolveContext(
    val modelId: String,
    val instanceId: String? = null,
    val supportsReasoning: Boolean?,
    val declaredEffortValues: List<String>?,
    val declaresNoEffortTiers: Boolean = false,
    val level: ThinkingLevel,
    val maxTokens: Int,
    val isOpenRouter: Boolean,
    val usesUnifiedReasoningEffort: Boolean,
    val isMistral: Boolean,
    val isDashScope: Boolean,
    val isCerebras: Boolean = false,
    val isXAI: Boolean = false,
    val offEffort: String?,
)

data class ThinkingResolveTrace(
    val matchedRuleLabel: String,
    val matchedRuleKind: ThinkingRule.Kind,
    val formatSource: String,
    val emittedKeys: List<String>,
    val clampedFrom: String? = null,
    val clampedTo: String? = null,
) {
    val logLine: String
        get() = buildList {
            add("rule=$matchedRuleLabel")
            add("kind=$matchedRuleKind")
            add("src=$formatSource")
            if (clampedFrom != null && clampedTo != null && clampedFrom != clampedTo) {
                add("clamp=$clampedFrom->$clampedTo")
            }
            add("keys=[${emittedKeys.sorted().joinToString(",")}]")
        }.joinToString(" ")
}

object ThinkingRuleResolver {

    @Volatile
    private var customRulesCache: Map<String, List<ThinkingRule>> = emptyMap()

    @Synchronized
    fun setAllCustomRules(byInstance: Map<String, List<ThinkingRule>>) {
        customRulesCache = byInstance
    }

    @Synchronized
    fun setCustomRules(instanceId: String, rules: List<ThinkingRule>) {
        customRulesCache = customRulesCache.toMutableMap().apply {
            if (rules.isEmpty()) remove(instanceId) else put(instanceId, rules)
        }
    }

    fun customRulesFor(instanceId: String?): List<ThinkingRule> =
        instanceId?.let { customRulesCache[it] } ?: emptyList()

    fun builtInRules(ctx: ThinkingResolveContext): List<ThinkingRule> = buildList {
        if (ctx.isMistral) {
            add(
                ThinkingRule(
                    kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                    scope = ThinkingRule.Scope.AllModels,
                    wireFormat = ThinkingWireFormat.OmitEverything,
                    reasoningEcho = ReasoningEchoPolicy("reasoning_content", ReasoningEchoPolicy.Timing.NEVER),
                    label = "mistral-official",
                ),
            )
        }

        if (ctx.isCerebras) {
            add(
                ThinkingRule(
                    kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                    scope = ThinkingRule.Scope.AllModels,
                    wireFormat = ThinkingWireFormat.ReasoningEffort(ctx.offEffort),
                    reasoningEcho = ReasoningEchoPolicy("reasoning_content", ReasoningEchoPolicy.Timing.NEVER),
                    label = "cerebras-official",
                ),
            )
        }

        if (ctx.isOpenRouter) {
            add(
                ThinkingRule(
                    kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                    scope = ThinkingRule.Scope.AllModels,
                    wireFormat = ThinkingWireFormat.ReasoningEffortNested(offValue = null),
                    label = "openrouter",
                ),
            )
        }


        add(
            ThinkingRule(
                kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                scope = ThinkingRule.Scope.ModelPattern("o*"),
                wireFormat = ThinkingWireFormat.ReasoningEffort(ctx.offEffort),
                label = "openai-native",
            ),
        )
        add(
            ThinkingRule(
                kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                scope = ThinkingRule.Scope.ModelPattern("gpt-5*"),
                wireFormat = ThinkingWireFormat.ReasoningEffort(ctx.offEffort),
                label = "openai-native",
            ),
        )
        add(
            ThinkingRule(
                kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                scope = ThinkingRule.Scope.ModelPattern("gpt-6*"),
                wireFormat = ThinkingWireFormat.ReasoningEffort(ctx.offEffort),
                label = "openai-native",
            ),
        )

        if (ctx.isDashScope) {
            add(
                ThinkingRule(
                    kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                    scope = ThinkingRule.Scope.AllModels,
                    wireFormat = ThinkingWireFormat.QwenDual,
                    label = "qwen-dashscope",
                ),
            )
        }
        add(
            ThinkingRule(
                kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                scope = ThinkingRule.Scope.ModelPattern("*qwen*"),
                wireFormat = ThinkingWireFormat.QwenDual,
                label = "qwen-dashscope",
            ),
        )

        if (ctx.usesUnifiedReasoningEffort) {
            add(
                ThinkingRule(
                    kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                    scope = ThinkingRule.Scope.AllModels,
                    wireFormat = ThinkingWireFormat.ReasoningEffort(ctx.offEffort),
                    label = "unified-gateway(ark|azure|venice)",
                ),
            )
        }

        add(
            ThinkingRule(
                kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                scope = ThinkingRule.Scope.ModelPattern("*deepseek-v4*"),
                wireFormat = ThinkingWireFormat.DeepSeekSibling,
                reasoningEcho = ReasoningEchoPolicy("reasoning_content", ReasoningEchoPolicy.Timing.AFTER_TOOL_USE_ONLY),
                label = "deepseek-v4-official",
            ),
        )

        add(
            ThinkingRule(
                kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                scope = ThinkingRule.Scope.ModelPattern("deepseek-flash*"),
                wireFormat = ThinkingWireFormat.DeepSeekSibling,
                reasoningEcho = ReasoningEchoPolicy("reasoning_content", ReasoningEchoPolicy.Timing.AFTER_TOOL_USE_ONLY),
                label = "deepseek-flash-official",
            ),
        )

        add(
            ThinkingRule(
                kind = ThinkingRule.Kind.PROVIDER_TYPE_DEFAULT,
                scope = ThinkingRule.Scope.AllModels,
                wireFormat = ThinkingWireFormat.ReasoningEffort(ctx.offEffort),
                label = "openai-compatible-default",
            ),
        )
    }

    fun apply(body: JSONObject, ctx: ThinkingResolveContext): ThinkingResolveTrace {
        val before = body.keys().asSequence().toSet()

        val rules = customRulesFor(ctx.instanceId) + builtInRules(ctx)
        val winner = rules.firstOrNull { it.scope.matches(ctx.modelId) }
            ?: return ThinkingResolveTrace(
                matchedRuleLabel = "none",
                matchedRuleKind = ThinkingRule.Kind.PROVIDER_TYPE_DEFAULT,
                formatSource = "no-match",
                emittedKeys = emptyList(),
            )

        var formatSource = "rule"
        val format = winner.wireFormat ?: run {
            formatSource = "providerTypeDefault"
            ThinkingWireFormat.ReasoningEffort(ctx.offEffort)
        }

        val clamp = emit(format, ctx, body)

        val emitted = body.keys().asSequence().toSet() - before
        return ThinkingResolveTrace(
            matchedRuleLabel = winner.label,
            matchedRuleKind = winner.kind,
            formatSource = formatSource,
            emittedKeys = emitted.toList(),
            clampedFrom = clamp.first,
            clampedTo = clamp.second,
        )
    }

    private val RESERVED_ROOT_KEYS = setOf(
        "messages", "model", "stream", "stream_options", "tools", "tool_choice",
        "n", "functions", "function_call", "response_format",
    )

    private fun setValueAtPath(body: JSONObject, path: String, value: Any): Boolean {
        val parts = path.split(".").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        if (parts.first().lowercase() in RESERVED_ROOT_KEYS) return false
        var cursor = body
        for (i in 0 until parts.size - 1) {
            val seg = parts[i]
            val next = cursor.optJSONObject(seg) ?: JSONObject().also { cursor.put(seg, it) }
            cursor = next
        }
        cursor.put(parts.last(), value)
        return true
    }

    private fun emit(
        format: ThinkingWireFormat,
        ctx: ThinkingResolveContext,
        body: JSONObject,
    ): Pair<String?, String?> {
        val lid = ctx.modelId.lowercase()

        val strictEffortEnum = lid.contains("mimo") || lid.contains("agnes")
        val offEffort = if (strictEffortEnum) null else ctx.offEffort

        return when (format) {
            is ThinkingWireFormat.OmitEverything -> null to null

            is ThinkingWireFormat.ReasoningEffortNested -> {
                if (!ctx.level.isEnabled) return null to null
                val effort = wireEffort(ctx.level)
                body.put("reasoning", JSONObject().put("effort", effort))
                effort to effort
            }

            is ThinkingWireFormat.ReasoningEffort -> {
                val isOpenAINative = lid.startsWith("o") ||
                    lid.startsWith("gpt-5") ||
                    lid.startsWith("gpt-6")
                val declaresEffort = !ctx.declaredEffortValues.isNullOrEmpty()
                if (ctx.isXAI && !ctx.usesUnifiedReasoningEffort &&
                    ctx.declaresNoEffortTiers && !declaresEffort
                ) {
                    return null to null
                }
                if (!ctx.level.isEnabled) {
                    if (offEffort == null) return null to null
                    val isSelfReasoningFamily = listOf("deepseek", "glm", "kimi", "minimax")
                        .any { lid.contains(it) }
                    when {
                        isOpenAINative -> {
                            body.put("reasoning_effort", offEffort)
                            return offEffort to offEffort
                        }
                        isSelfReasoningFamily -> {
                            if (ctx.usesUnifiedReasoningEffort ||
                                ctx.declaredEffortValues?.contains(offEffort) == true
                            ) {
                                body.put("reasoning_effort", offEffort)
                                return offEffort to offEffort
                            }
                            return null to null
                        }
                        ctx.supportsReasoning != false -> {
                            if (ctx.declaredEffortValues?.contains(offEffort) != false) {
                                body.put("reasoning_effort", offEffort)
                                return offEffort to offEffort
                            }
                            return null to null
                        }
                        else -> return null to null
                    }
                }
                if (isOpenAINative) {
                    val effort = clampEffortForModel(wireEffort(ctx.level), lid)
                    body.put("reasoning_effort", effort)
                    return effort to effort
                }
                if (!ctx.usesUnifiedReasoningEffort && !declaresEffort &&
                    listOf("deepseek", "glm", "kimi", "minimax").any { lid.contains(it) }
                ) {
                    return null to null
                }
                if (ctx.supportsReasoning == false) return null to null
                val requested = clampEffortForModel(wireEffort(ctx.level), lid)
                val clamped = clampEffort(requested, ctx.declaredEffortValues)
                body.put("reasoning_effort", clamped)
                requested to clamped
            }

            is ThinkingWireFormat.DeepSeekSibling -> {
                if (ctx.level.isEnabled) {
                    val requested = wireEffort(ctx.level)
                    val clamped = clampEffort(requested, ctx.declaredEffortValues)
                    body.put("thinking", JSONObject().put("type", "enabled"))
                    body.put("reasoning_effort", clamped)
                    requested to clamped
                } else {
                    body.put("thinking", JSONObject().put("type", "disabled"))
                    null to null
                }
            }

            is ThinkingWireFormat.QwenDual -> {
                if (!ctx.level.isEnabled) return null to null

                val enabled = true
                var budget = when (ctx.level) {
                    ThinkingLevel.LOW -> 4096
                    ThinkingLevel.MEDIUM -> 16384
                    ThinkingLevel.HIGH -> 32768
                    ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA -> 65536
                    ThinkingLevel.OFF -> 0
                }
                if (budget > 0 && ctx.maxTokens > 0) {
                    if (ctx.maxTokens < 2) {
                        budget = 0
                    } else {
                        val margin = maxOf(2048, ctx.maxTokens / 8)
                        val ceiling = maxOf(1, minOf(ctx.maxTokens - margin, ctx.maxTokens - 1))
                        if (budget >= ceiling) budget = ceiling
                    }
                }
                body.put("enable_thinking", enabled)
                if (budget > 0) body.put("thinking_budget", budget)
                body.put(
                    "extra_body",
                    JSONObject().apply {
                        put("enable_thinking", enabled)
                        if (budget > 0) put("thinking_budget", budget)
                    },
                )
                null to null
            }

            is ThinkingWireFormat.CustomPath -> {
                if (ctx.level.isEnabled) {
                    val v = format.values[ctx.level] ?: format.values[ThinkingLevel.HIGH]
                        ?: return null to null
                    if (!setValueAtPath(body, format.path, v)) return null to null
                    v to v
                } else {
                    val off = format.offValue ?: return null to null
                    if (!setValueAtPath(body, format.path, off)) return null to null
                    off to off
                }
            }

            is ThinkingWireFormat.BooleanToggle -> {
                if (!setValueAtPath(body, format.path, ctx.level.isEnabled)) return null to null
                val s = ctx.level.isEnabled.toString()
                s to s
            }

            is ThinkingWireFormat.ExtraBodyToggle -> {
                if (!setValueAtPath(body, format.path, ctx.level.isEnabled)) return null to null
                val s = ctx.level.isEnabled.toString()
                s to s
            }

            else -> {
                error("ThinkingWireFormat $format is not emitted on the OpenAI path in Phase 1")
            }
        }
    }


    data class GeminiVersion(val major: Int, val minor: Int?)

    private val geminiVersionPattern = Regex("""gemini-(\d+)(?:[.-](\d+))?""")

    fun geminiVersion(modelId: String): GeminiVersion? {
        val m = geminiVersionPattern.find(modelId.lowercase()) ?: return null
        val major = m.groupValues[1].toIntOrNull() ?: return null
        return GeminiVersion(major, m.groupValues[2].toIntOrNull())
    }

    fun isGeminiLevelFamily(modelId: String): Boolean = (geminiVersion(modelId)?.major ?: 0) >= 3

    fun geminiAcceptsMinimalLevel(modelId: String): Boolean {
        val v = geminiVersion(modelId) ?: return false
        return v.major == 3 && (v.minor == null || v.minor < 7) && modelId.lowercase().contains("flash")
    }

    enum class GeminiThinkingFormat { LEVEL, BUDGET, LEVEL_NO_MINIMAL }

    fun geminiThinkingConfig(
        modelId: String,
        level: ThinkingLevel,
        format: GeminiThinkingFormat? = null,
    ): JSONObject? {
        val lowerId = modelId.lowercase()
        val noThinkingSuffixes = listOf("-tts", "-image", "-embedding", "-vision")
        if (noThinkingSuffixes.any { lowerId.endsWith(it) || lowerId.contains("$it-") }) {
            return null
        }

        val is25Pro = modelId.contains("gemini-2.5-pro")
        val is25Flash = modelId.contains("gemini-2.5-flash") && !modelId.contains("lite")
        val is25FlashLite = modelId.contains("gemini-2.5-flash-lite")

        if (is25FlashLite) return null

        return when (format) {
            GeminiThinkingFormat.LEVEL -> geminiLevelConfig(modelId, level, geminiAcceptsMinimalLevel(modelId))
            GeminiThinkingFormat.LEVEL_NO_MINIMAL -> geminiLevelConfig(modelId, level, allowMinimal = false)
            GeminiThinkingFormat.BUDGET -> when {
                is25Pro -> gemini25ProBudget(level)
                is25Flash -> gemini25FlashBudget(level)
                else -> geminiFallbackBudget(level)
            }
            null -> when {
                isGeminiLevelFamily(modelId) ->
                    geminiLevelConfig(modelId, level, geminiAcceptsMinimalLevel(modelId))
                is25Pro -> gemini25ProBudget(level)
                is25Flash -> gemini25FlashBudget(level)
                else -> null
            }
        }
    }

    private fun geminiLevelConfig(modelId: String, level: ThinkingLevel, allowMinimal: Boolean): JSONObject =
        JSONObject().apply {
            if (level == ThinkingLevel.OFF) {
                put("thinkingLevel", if (allowMinimal) "minimal" else "low")
                return@apply
            }
            val v = geminiVersion(modelId)
            val lowHighOnly = v?.major == 3 && v.minor == null && modelId.lowercase().contains("-pro")
            put(
                "thinkingLevel",
                when (level) {
                    ThinkingLevel.LOW -> "low"
                    ThinkingLevel.MEDIUM -> if (lowHighOnly) "high" else "medium"
                    ThinkingLevel.HIGH, ThinkingLevel.XHIGH,
                    ThinkingLevel.MAX, ThinkingLevel.ULTRA,
                    -> "high"
                    ThinkingLevel.OFF -> "low"
                },
            )
            put("includeThoughts", true)
        }

    private fun gemini25ProBudget(level: ThinkingLevel): JSONObject = JSONObject().apply {
        put(
            "thinkingBudget",
            when (level) {
                ThinkingLevel.OFF -> 128
                ThinkingLevel.LOW -> 2048
                ThinkingLevel.MEDIUM -> 8192
                ThinkingLevel.HIGH -> 16384
                ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA -> 32768
            },
        )
        if (level.isEnabled) put("includeThoughts", true)
    }

    private fun gemini25FlashBudget(level: ThinkingLevel): JSONObject = JSONObject().apply {
        put(
            "thinkingBudget",
            when (level) {
                ThinkingLevel.OFF -> 0
                ThinkingLevel.LOW -> 1024
                ThinkingLevel.MEDIUM -> 4096
                ThinkingLevel.HIGH -> 8192
                ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA -> 16384
            },
        )
        if (level.isEnabled) put("includeThoughts", true)
    }

    private fun geminiFallbackBudget(level: ThinkingLevel): JSONObject = JSONObject().apply {
        put(
            "thinkingBudget",
            when (level) {
                ThinkingLevel.OFF -> 128
                ThinkingLevel.LOW -> 1024
                ThinkingLevel.MEDIUM -> 4096
                ThinkingLevel.HIGH -> 8192
                ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA -> 16384
            },
        )
        if (level.isEnabled) put("includeThoughts", true)
    }

    fun anthropicThinkingShape(
        modelId: String,
        supportsReasoning: Boolean?,
        level: ThinkingLevel,
        maxTokens: Int,
    ): Map<String, Any> {
        val adaptive = com.openminis.app.provider.anthropic.AnthropicProvider
            .modelUsesAdaptiveThinking(modelId)
        if (level.isEnabled && supportsReasoning != false) {
            if (adaptive) {
                return mapOf(
                    "effort" to com.openminis.app.provider.anthropic.AnthropicProvider
                        .thinkingEffort(level),
                )
            }
            val budget = com.openminis.app.provider.anthropic.AnthropicProvider
                .thinkingBudget(maxTokens, level)
            return if (budget > 0) mapOf("budget_tokens" to budget) else emptyMap()
        }
        return if (
            com.openminis.app.provider.anthropic.AnthropicProvider.offThinking(modelId) ==
            com.openminis.app.provider.anthropic.AnthropicProvider.OffThinking.OMIT
        ) {
            emptyMap()
        } else {
            mapOf("disabled" to true)
        }
    }

    fun wireEffort(level: ThinkingLevel): String = when (level) {
        ThinkingLevel.OFF, ThinkingLevel.LOW -> "low"
        ThinkingLevel.MEDIUM -> "medium"
        ThinkingLevel.HIGH -> "high"
        ThinkingLevel.XHIGH -> "xhigh"
        ThinkingLevel.MAX, ThinkingLevel.ULTRA -> "max"
    }

    fun clampEffortForModel(effort: String, lid: String): String =
        if (effort == "xhigh" && (lid.contains("mimo") || lid.contains("agnes"))) "high" else effort

    fun clampOffEffort(effort: String, values: List<String>?): String {
        if (values.isNullOrEmpty()) return effort
        if (values.contains(effort)) return effort
        val ladder = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
        return values.mapNotNull { v -> ladder.indexOf(v).takeIf { it >= 0 }?.to(v) }
            .minByOrNull { it.first }
            ?.second
            ?: effort
    }


    fun clampEffort(effort: String, values: List<String>?): String {
        if (values.isNullOrEmpty()) return effort
        if (values.contains(effort)) return effort
        val ladder = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
        val want = ladder.indexOf(effort)
        if (want < 0) return effort
        val declared = values.mapNotNull { v ->
            val i = ladder.indexOf(v)
            if (i >= 0) i to v else null
        }.sortedBy { it.first }
        if (declared.isEmpty()) return effort
        return declared.lastOrNull { it.first <= want }?.second ?: declared.first().second
    }
}
