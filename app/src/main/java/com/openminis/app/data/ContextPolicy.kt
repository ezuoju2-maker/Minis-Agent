package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage

data class ContextPolicy(
    val offloadThreshold: Int,
    val offloadTarget: Int,
    val compactThreshold: Int,
    val exhaustedOnly: Boolean,
    val manualCompactAllowed: Boolean,
) {
    enum class CheckResult { OK, NEEDS_COMPACT, EXHAUSTED }

    fun check(estimatedTokens: Int, contextWindow: Int): CheckResult {
        if (compactThreshold > 0 && estimatedTokens >= compactThreshold) {
            return CheckResult.NEEDS_COMPACT
        }
        if (contextWindow > 0 && estimatedTokens >= contextWindow) {
            return if (manualCompactAllowed) CheckResult.NEEDS_COMPACT else CheckResult.EXHAUSTED
        }
        if (exhaustedOnly) {
            val exhaustLine = if (offloadThreshold > 0) offloadThreshold else (contextWindow * 9 / 10)
            if (estimatedTokens >= exhaustLine) return CheckResult.EXHAUSTED
        }
        return CheckResult.OK
    }

    fun shouldOffload(estimatedTokens: Int): Boolean =
        offloadThreshold > 0 && estimatedTokens >= offloadThreshold

    enum class InLoopStep {
        PROCEED,
        COMPACT,
        SEND_WITHIN_WINDOW,
        SEND_UNCALIBRATED_ONCE,
        STOP,
    }

    companion object {
        fun inLoopStep(
            verdict: CheckResult,
            measured: Int,
            rawTokens: Int,
            window: Int,
            canCompact: Boolean,
            ratio: Double,
            uncalibratedSendUsed: Boolean,
        ): InLoopStep = when (verdict) {
            CheckResult.OK -> InLoopStep.PROCEED
            CheckResult.EXHAUSTED -> InLoopStep.STOP
            CheckResult.NEEDS_COMPACT -> when {
                canCompact -> InLoopStep.COMPACT
                window <= 0 || measured < window -> InLoopStep.SEND_WITHIN_WINDOW
                !uncalibratedSendUsed && ratio > 1.0 && rawTokens < window -> InLoopStep.SEND_UNCALIBRATED_ONCE
                else -> InLoopStep.STOP
            }
        }

        fun forUserCap(contextWindow: Int): ContextPolicy = ContextPolicy(
            offloadThreshold = (contextWindow * 0.70).toInt(),
            offloadTarget = (contextWindow * 0.55).toInt(),
            compactThreshold = (contextWindow * 0.85).toInt(),
            exhaustedOnly = false,
            manualCompactAllowed = true,
        )

        fun forContextWindow(contextWindow: Int): ContextPolicy = when {
            contextWindow < 32_000 -> ContextPolicy(
                offloadThreshold = 0,
                offloadTarget = 0,
                compactThreshold = 0,
                exhaustedOnly = true,
                manualCompactAllowed = false,
            )
            contextWindow < 64_000 -> ContextPolicy(
                offloadThreshold = contextWindow - 10_000,
                offloadTarget = contextWindow - 15_000,
                compactThreshold = 0,
                exhaustedOnly = true,
                manualCompactAllowed = true,
            )
            contextWindow < 128_000 -> ContextPolicy(
                offloadThreshold = contextWindow - 20_000,
                offloadTarget = contextWindow - 30_000,
                compactThreshold = contextWindow - 10_000,
                exhaustedOnly = false,
                manualCompactAllowed = true,
            )
            else -> ContextPolicy(
                offloadThreshold = contextWindow - 40_000,
                offloadTarget = contextWindow - 60_000,
                compactThreshold = contextWindow - 20_000,
                exhaustedOnly = false,
                manualCompactAllowed = true,
            )
        }
    }
}

object ContextSizeMeter {

    fun estimateTokens(text: String): Int {
        var letters = 0
        var digits = 0
        var asciiOther = 0
        var nonAscii = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when {
                cp >= 128 -> nonAscii++
                cp in 65..90 || cp in 97..122 -> letters++
                cp in 48..57 -> digits++
                else -> asciiOther++
            }
            i += Character.charCount(cp)
        }
        val tokens = letters / 4.5 + digits / 2.0 + asciiOther * 0.35 + nonAscii
        return kotlin.math.ceil(tokens).toInt()
    }


    const val PER_MESSAGE_OVERHEAD = 8
    const val PER_TOOL_USE_OVERHEAD = 22
    const val PER_TOOL_RESULT_OVERHEAD = 6
    const val REASONING_FIELD_OVERHEAD = 6

    fun wireEscapeTokens(text: String): Int {
        var escaped = 0
        for (c in text) if (c == '"' || c == '\\' || c == '\n' || c == '\r' || c == '\t') escaped++
        return kotlin.math.ceil(escaped * 0.35).toInt()
    }

    fun estimateWireTokens(text: String): Int =
        if (text.isEmpty()) 0 else estimateTokens(text) + wireEscapeTokens(text)

    fun estimateTokens(part: AgentContentPart): Int = when (part) {
        is AgentContentPart.Text -> estimateWireTokens(part.text)
        is AgentContentPart.ToolUse ->
            estimateTokens(part.name) + estimateTokens(part.id) + estimateWireTokens(part.input.toString()) +
                PER_TOOL_USE_OVERHEAD
        is AgentContentPart.ToolResult ->
            estimateWireTokens(part.content) + estimateTokens(part.id) + PER_TOOL_RESULT_OVERHEAD +
                (part.imageData?.let { imageTokens(it) } ?: 0)
        is AgentContentPart.ImageData -> imageTokens(part.data)
    }

    fun estimateTokens(message: LLMMessage, includeReasoning: Boolean = false): Int {
        val body = if (message.contentParts.isNotEmpty()) {
            message.contentParts.sumOf { estimateTokens(it) }
        } else {
            estimateWireTokens(message.content)
        }
        val reasoning = if (includeReasoning && message.role == LLMMessage.Role.ASSISTANT) {
            message.reasoningContent?.let { estimateWireTokens(it) + REASONING_FIELD_OVERHEAD } ?: 0
        } else {
            0
        }
        return PER_MESSAGE_OVERHEAD + body + reasoning + message.imageParts.sumOf { imageTokens(it.data) }
    }

    fun estimateTokens(messages: List<LLMMessage>, includeReasoning: Boolean = false): Int =
        messages.sumOf { estimateCached(it, includeReasoning) }

    private fun estimateCached(message: LLMMessage, includeReasoning: Boolean = false): Int {
        val id = message.dbMessageId ?: return estimateTokens(message, includeReasoning)
        val shape = buildString {
            append(id).append('|').append(message.content.length).append('|').append(message.contentParts.size)
            if (includeReasoning) append("|rc").append(message.reasoningContent?.length ?: -1)
            for (part in message.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> append("|t").append(part.text.length)
                    is AgentContentPart.ToolUse -> append("|u").append(part.input.length()).append(':').append(part.input.toString().length)
                        .append(if (part.isOffloadedArgument) "o" else "")
                    is AgentContentPart.ToolResult -> append("|r").append(part.content.length).append('/').append(part.imageData?.size ?: 0)
                    is AgentContentPart.ImageData -> append("|i").append(part.data.size)
                }
            }
            for (img in message.imageParts) append("|p").append(img.data.size)
        }
        synchronized(messageEstimateCache) { messageEstimateCache[shape]?.let { return it } }
        val tokens = estimateTokens(message, includeReasoning)
        synchronized(messageEstimateCache) { messageEstimateCache[shape] = tokens }
        return tokens
    }

    private val messageEstimateCache = object : LinkedHashMap<String, Int>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 20_000
    }

    fun estimateFixedTokens(systemPrompt: String?, tools: List<AgentToolDefinition>): Int {
        val key = "${systemPrompt?.length}:${systemPrompt?.hashCode()}:${tools.hashCode()}"
        synchronized(fixedEstimateCache) { fixedEstimateCache[key]?.let { return it } }
        var total = systemPrompt?.let { estimateTokens(it) } ?: 0
        for (tool in tools) {
            total += estimateTokens(tool.name) + estimateTokens(tool.description) + 10
            for ((name, param) in tool.parameters) {
                total += estimateTokens(name) + estimateTokens(param.description) + 4
                param.enumValues?.forEach { total += estimateTokens(it) + 1 }
                param.schema?.let { total += estimateTokens(it.estimationText) + 8 }
            }
        }
        synchronized(fixedEstimateCache) { fixedEstimateCache[key] = total }
        return total
    }

    private val fixedEstimateCache = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 128
    }

    const val CALIBRATION_MIN = 0.8
    const val CALIBRATION_MAX = 8.0

    fun calibrationRatio(reported: Int, estimated: Int): Double? {
        if (reported <= 0 || estimated <= 0) return null
        return (reported.toDouble() / estimated).coerceIn(CALIBRATION_MIN, CALIBRATION_MAX)
    }

    fun calibrated(estimated: Int, ratio: Double): Int = kotlin.math.ceil(estimated * ratio).toInt()

    const val UNCALIBRATED_MODEL_MARGIN = 1.2

    fun ratioSource(modelId: String?, known: Map<String, Double>, lastLearned: Double?): String = when {
        modelId != null && known.containsKey(modelId) -> "own"
        lastLearned == null -> "default"
        else -> "borrowed"
    }

    const val CALIBRATION_FALL_RATE = 0.3

    fun smoothed(previous: Double?, sample: Double): Double {
        if (previous == null || sample >= previous) return sample
        return previous + CALIBRATION_FALL_RATE * (sample - previous)
    }

    fun ratioFor(modelId: String?, known: Map<String, Double>, lastLearned: Double?): Double {
        if (modelId != null) known[modelId]?.let { return it }
        val last = lastLearned ?: return 1.0
        return (maxOf(last, 1.0) * UNCALIBRATED_MODEL_MARGIN).coerceAtMost(CALIBRATION_MAX)
    }

    fun requestedTokens(overflowMessage: String): Int? {
        val cleaned = overflowMessage.replace(Regex("(?<=\\d),(?=\\d{3})"), "")
        return Regex("\\d{4,}").findAll(cleaned).mapNotNull { it.value.toIntOrNull() }.filter { it >= 1000 }.maxOrNull()
    }

    fun ratioAfterOverflow(current: Double, estimated: Int, requested: Int?, window: Int): Double {
        if (estimated <= 0) return current
        val plausible = requested?.takeIf { window <= 0 || (it >= window * 0.9 && it <= window * 4.0) }
        val target = plausible?.toDouble() ?: (maxOf(window, 1) * 1.02)
        return maxOf(current, target / estimated).coerceAtMost(CALIBRATION_MAX)
    }


    data class CalibrationSample(val reported: Int, val estimated: Int, val fixedTokens: Int, val modelId: String?)

    data class CalibrationState(
        val ratios: Map<String, Double> = emptyMap(),
        val lastLearned: Double? = null,
        val fixedTokens: Int = 0,
        val samples: Int = 0,
    ) {
        fun carryingOver(learned: CalibrationState): CalibrationState = copy(
            ratios = ratios + learned.ratios,
            lastLearned = learned.lastLearned ?: lastLearned,
            fixedTokens = if (learned.fixedTokens > 0) learned.fixedTokens else fixedTokens,
        )
    }

    const val ESTIMATOR_VERSION = 3

    fun calibrationSample(usageJson: String): CalibrationSample? {
        val o = runCatching { org.json.JSONObject(usageJson) }.getOrNull() ?: return null
        if (o.optInt("estimatorVersion", 1) != ESTIMATOR_VERSION) return null
        val reported = o.optInt("latestContextTokens", 0)
        val estimated = o.optInt("estimatedRequestTokens", 0)
        if (reported <= 0 || estimated <= 0) return null
        val model = o.optString("calibrationModelId", "").takeIf { it.isNotEmpty() }
        return CalibrationSample(reported, estimated, o.optInt("estimatedFixedTokens", 0), model)
    }

    fun replayCalibration(samples: List<CalibrationSample>): CalibrationState {
        val ratios = mutableMapOf<String, Double>()
        var last: Double? = null
        var fixed = 0
        var count = 0
        for (s in samples) {
            val sample = calibrationRatio(s.reported, s.estimated) ?: continue
            count++
            last = if (s.modelId != null) smoothed(ratios[s.modelId], sample).also { ratios[s.modelId] = it } else sample
            fixed = s.fixedTokens
        }
        return CalibrationState(ratios, last, fixed, count)
    }

    fun warmUpDrop(sizes: List<Int>, startsTurn: List<Boolean>, restTokens: Int, budget: Int): Int {
        require(sizes.size == startsTurn.size)
        var total = sizes.sum()
        var drop = 0
        while (drop < sizes.size && total + restTokens >= budget) {
            total -= sizes[drop++]
            while (drop < sizes.size && !startsTurn[drop]) total -= sizes[drop++]
        }
        return drop
    }

    private val imageTokenCache = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 256
    }

    fun imageTokens(data: ByteArray): Int {
        val head = data.copyOfRange(0, minOf(64, data.size)).contentHashCode()
        val tail = data.copyOfRange(maxOf(0, data.size - 64), data.size).contentHashCode()
        val key = "${data.size}-$head-$tail"
        synchronized(imageTokenCache) { imageTokenCache[key]?.let { return it } }
        val tokens = BPETokenizer.countImageTokens(data)
        synchronized(imageTokenCache) { imageTokenCache[key] = tokens }
        return tokens
    }
}
