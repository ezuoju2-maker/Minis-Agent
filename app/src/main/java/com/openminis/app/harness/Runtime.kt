package com.openminis.app.harness

/**
 * v2.0 · Deterministic execution runtime.
 *
 * Brings in the engineering contracts that make long agent tasks survivable:
 *  - typed tool schemas, idempotency keys and bounded exponential backoff
 *    (OpenHands / MCP);
 *  - loop detection (Devin's "infinite HTML parsing" failure mode);
 *  - a single shared step/token/cost/wall-clock governor with an explicit
 *    wrap-up verdict (DeadlineBudget generalised);
 *  - model routing with a circuit breaker, a user-editable fallback chain,
 *    privacy mode and a live cost counter (LiteLLM / Claude gateway / OPPO).
 *
 * No I/O is performed here: the host injects `executor` and `clock`, which
 * makes the whole runtime unit-testable and replayable on a plain JVM.
 */

data class ToolResult(
    val content: String,
    val isError: Boolean = false,
    val retryable: Boolean = false,
    val errorCode: String? = null,
    val durationMs: Long = 0L,
)

class ToolRuntime(
    private val clock: () -> Long = { 0L },
    private val executor: (ToolDescriptor, String) -> ToolResult = { _, _ -> ToolResult("") },
) {
    private val idempotency = LinkedHashMap<String, ToolResult>()
    private val recentCalls = ArrayList<String>()
    private val loopThreshold = 2

    /** Minimal JSON-Schema validation: required keys + primitive types. */
    fun validate(schemaJson: String, argsJson: String): String? {
        val schema = MiniJson.parseObject(schemaJson) ?: return "invalid tool schema"
        val args = MiniJson.parseObject(argsJson) ?: return "arguments must be a JSON object"
        @Suppress("UNCHECKED_CAST")
        val required = schema["required"] as? List<*> ?: emptyList<Any>()
        for (k in required) {
            val key = k.toString()
            if (!args.containsKey(key)) return "missing required argument: $key"
        }
        @Suppress("UNCHECKED_CAST")
        val props = schema["properties"] as? Map<String, Any?> ?: emptyMap()
        for ((key, spec) in props) {
            val value = args[key] ?: continue
            @Suppress("UNCHECKED_CAST")
            val type = (spec as? Map<String, Any?>)?.get("type")?.toString() ?: continue
            val ok = when (type) {
                "string" -> value is String
                "number" -> value is Number
                "integer" -> value is Number
                "boolean" -> value is Boolean
                "array" -> value is List<*>
                "object" -> value is Map<*, *>
                else -> true
            }
            if (!ok) return "argument '$key' must be $type"
        }
        return null
    }

    /** Stable fingerprint of (tool, normalised args) for loop detection. */
    private fun fingerprint(tool: ToolDescriptor, argsJson: String): String =
        tool.name + "|" + (MiniJson.parseObject(argsJson)?.toSortedMap()?.toString() ?: argsJson.trim())

    fun detectLoop(tool: ToolDescriptor, argsJson: String): Boolean {
        val fp = fingerprint(tool, argsJson)
        // Sliding window: two identical calls with identical results in a row.
        val count = recentCalls.count { it == fp }
        recentCalls.add(fp)
        if (recentCalls.size > 24) recentCalls.removeAt(0)
        return count + 1 >= loopThreshold
    }

    /**
     * Execute once. Returns a *classified* result; network 5xx/429 is
     * `retryable`, schema errors are not (the model must correct its arguments).
     * Non-idempotent tools are never replayed without an explicit key.
     */
    fun execute(
        tool: ToolDescriptor, argsJson: String, idempotencyKey: String? = null,
    ): ToolResult {
        val invalid = validate(tool.schemaJson, argsJson)
        if (invalid != null) return ToolResult("schema error: $invalid", isError = true, errorCode = "SCHEMA")
        // [v2.0 audit fix] Replay a cached idempotent result BEFORE loop
        // detection: a legitimate retry of the same key is not a loop.
        if (idempotencyKey != null && tool.idempotent) {
            idempotency[idempotencyKey]?.let { return it }
        }
        if (detectLoop(tool, argsJson)) {
            return ToolResult("loop detected: identical tool call repeated", isError = true, errorCode = "LOOP")
        }
        val started = clock()
        val result = try {
            executor(tool, argsJson)
        } catch (e: Exception) {
            ToolResult("exception: ${e.message}", isError = true, retryable = true, errorCode = "EXCEPTION")
        }
        val withDuration = result.copy(durationMs = result.durationMs.takeIf { it > 0 } ?: (clock() - started))
        if (idempotencyKey != null && tool.idempotent && !withDuration.isError) {
            idempotency[idempotencyKey] = withDuration
        }
        return withDuration
    }

    /** Jitter-free, bounded exponential backoff. Host performs the actual delay. */
    fun backoffDelays(maxAttempts: Int = 3, baseMs: Long = 500L, capMs: Long = 8_000L): List<Long> {
        require(maxAttempts >= 1)
        val out = ArrayList<Long>()
        var d = baseMs
        repeat(maxAttempts - 1) { out.add(d); d = (d * 2).coerceAtMost(capMs) }
        return out
    }

    data class Trimmed(val text: String, val originalLines: Int, val keptLines: Int, val omittedLines: Int)

    private val IMPORTANT = listOf("error", "fail", "exception", "timeout", "denied", "panic", "traceback")

    /** Structure-aware trim: keep head (context), tail (verdict) and error lines. */
    fun trim(text: String, maxLines: Int = 240, maxChars: Int = 24_000): Trimmed {
        val lines = if (text.isEmpty()) emptyList() else text.lineSequence().toList()
        val lineCap = maxLines.coerceAtLeast(1)
        val charCap = maxChars.coerceAtLeast(1)
        if (lines.size <= lineCap && text.length <= charCap) return Trimmed(text, lines.size, lines.size, 0)
        val headN = minOf(lines.size, maxOf(1, lineCap / 4))
        val tailN = minOf(lines.size - headN, maxOf(1, lineCap / 2))
        val middle = if (lines.size > headN + tailN) lines.subList(headN, lines.size - tailN) else emptyList()
        val important = middle.filter { l -> IMPORTANT.any { l.lowercase().contains(it) } }
            .take(maxOf(0, lineCap - headN - tailN))
        val kept = ArrayList<String>()
        kept.addAll(lines.take(headN))
        if (middle.size > important.size) kept.add("… [${middle.size - important.size} lines omitted] …")
        kept.addAll(important)
        if (tailN > 0) kept.addAll(lines.takeLast(tailN))
        var out = kept.joinToString("\n")
        if (out.length > charCap) {
            val prefix = "… [${out.length - charCap} chars omitted] …\n"
            out = if (prefix.length >= charCap) out.takeLast(charCap)
            else prefix + out.takeLast(charCap - prefix.length)
        }
        val keptCount = headN + important.size + tailN
        return Trimmed(out, lines.size, keptCount, (lines.size - keptCount).coerceAtLeast(0))
    }
}

// ---------------------------------------------------------------- budgets

class BudgetGovernor(
    private val maxSteps: Int = 200,
    private val maxWallMs: Long = 30 * 60 * 1000L,
    private val maxTokens: Int = 400_000,
    private val maxCostMicros: Long = 5_000_000L,
    private val clock: () -> Long = { 0L },
) {
    data class Verdict(val proceed: Boolean, val reason: String, val urgency: String)

    private val startedAt = clock()
    private var steps = 0
    private var tokens = 0
    private var cost = 0L
    private val actionHashes = ArrayList<String>()

    fun record(tokensDelta: Int = 0, costDelta: Long = 0L, action: String = "") {
        steps++
        tokens += tokensDelta
        cost += costDelta
        if (action.isNotEmpty()) {
            actionHashes.add(action.hashCode().toString())
            if (actionHashes.size > 32) actionHashes.removeAt(0)
        }
    }

    fun beforeStep(): Verdict {
        val elapsed = clock() - startedAt
        if (elapsed > maxWallMs) return Verdict(false, "wall-clock budget exceeded", "CRITICAL")
        if (steps >= maxSteps) return Verdict(false, "step budget exceeded ($maxSteps)", "CRITICAL")
        if (tokens >= maxTokens) return Verdict(false, "token budget exceeded", "CRITICAL")
        if (cost >= maxCostMicros) return Verdict(false, "cost budget exceeded", "CRITICAL")
        val recent = actionHashes.takeLast(3)
        if (recent.size == 3 && recent[0] == recent[1] && recent[1] == recent[2]) {
            return Verdict(false, "repeated identical action (loop)", "CRITICAL")
        }
        val left = maxWallMs - elapsed
        return when {
            left < maxWallMs / 10 || steps > maxSteps * 9 / 10 -> Verdict(true, "wrap up", "CRITICAL")
            left < maxWallMs / 3 -> Verdict(true, "prepare summary", "WRAP_UP")
            else -> Verdict(true, "", "NORMAL")
        }
    }

    fun usage(): Map<String, Any?> = mapOf(
        "steps" to steps, "maxSteps" to maxSteps, "tokens" to tokens,
        "costMicros" to cost, "elapsedMs" to (clock() - startedAt),
    )
}

// ---------------------------------------------------------------- model routing

data class ModelEndpoint(
    val id: String,
    val label: String,
    val tier: Int,                 // 0 = strongest / primary, 1..n = fallbacks
    val inputMicrosPer1k: Long,
    val outputMicrosPer1k: Long,
    val isLocal: Boolean = false,
    val simplifiedToolset: Boolean = false,
)

class ModelRouter(
    endpoints: List<ModelEndpoint>,
    private val clock: () -> Long = { 0L },
    private val failureThreshold: Int = 3,
    private val cooldownMs: Long = 60_000L,
) {
    data class RouteDecision(val endpoint: ModelEndpoint?, val reason: String, val degraded: Boolean)

    private val sorted = endpoints.sortedBy { it.tier }
    private val failures = HashMap<String, Int>()
    private val openedAt = HashMap<String, Long>()
    private val lastLatency = HashMap<String, Long>()
    var privacyMode: Boolean = false
        private set
    var totalCostMicros: Long = 0L
        private set

    fun setPrivacyMode(on: Boolean) { privacyMode = on }

    private fun isOpen(e: ModelEndpoint): Boolean {
        val at = openedAt[e.id] ?: return false
        if (clock() - at >= cooldownMs) {
            openedAt.remove(e.id); failures[e.id] = 0; return false
        }
        return true
    }

    fun route(complexity: Int = 1): RouteDecision {
        val candidates = sorted.filter { !privacyMode || it.isLocal }
        if (candidates.isEmpty()) return RouteDecision(null, "privacy mode: no local model configured", true)
        val usable = candidates.filter { !isOpen(it) }
        if (usable.isEmpty()) return RouteDecision(null, "all providers circuit-open", true)
        // A weak/small model must not be handed the full toolset on hard tasks.
        val best = usable.first()
        val degraded = best.tier > 0 || (best.simplifiedToolset && complexity >= 3)
        return RouteDecision(
            best,
            if (degraded) "degraded: simplified toolset + read-only tools" else "primary",
            degraded,
        )
    }

    fun recordOutcome(id: String, success: Boolean, latencyMs: Long = 0L) {
        lastLatency[id] = latencyMs
        if (success) {
            failures[id] = 0
            openedAt.remove(id)
        } else {
            val n = (failures[id] ?: 0) + 1
            failures[id] = n
            if (n >= failureThreshold) openedAt[id] = clock()
        }
    }

    fun charge(endpoint: ModelEndpoint, tokensIn: Int, tokensOut: Int): Long {
        val c = endpoint.inputMicrosPer1k * tokensIn / 1000 +
            endpoint.outputMicrosPer1k * tokensOut / 1000
        totalCostMicros += c
        return c
    }

    fun health(): Map<String, Any?> = sorted.associate {
        it.id to mapOf(
            "failures" to (failures[it.id] ?: 0),
            "circuitOpen" to isOpen(it),
            "lastLatencyMs" to (lastLatency[it.id] ?: 0L),
        )
    }
}
