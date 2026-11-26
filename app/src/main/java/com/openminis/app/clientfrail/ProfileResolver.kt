package com.openminis.app.clientfrail

import java.util.Locale

data class FrailProfile(
    val family: String,
    val transport: String,
    val formIds: List<String>,
    val headerBudgetChars: Int,
    val splitSystemBlocks: Boolean,
    val decodePayloads: Boolean,
    val continuationBias: Boolean,
    val schemaLead: Boolean,
    val notes: String = "",
) {
    fun forms(): List<FrailForm> = FrailRegistry.resolve(formIds)
}

object ProfileResolver {

    private data class Rule(
        val family: String,
        val tokens: List<String>,
        val profile: () -> FrailProfile,
    )

    private const val UNIVERSAL_HEADER = 5200

    private val BASE_STANCE = listOf(
        "stance.compliance", "stance.no-refusal", "stance.granularity",
    )

    private val BASE_PRESSURE = listOf(
        "pressure.prefix", "pressure.contract", "echo.selfcheck",
    )

    private val RULES: List<Rule> = listOf(
        Rule("anthropic", listOf("claude", "anthropic"), {
            FrailProfile(
                family = "anthropic",
                transport = "anthropic-messages",
                formIds = BASE_STANCE + listOf(
                    "frame.continuation", "frame.draft",
                    "narrative.analyst", "dilution.fewshot", "dilution.schema",
                    "ctx.priming", "persona.memory", "cot.execution",
                ) + BASE_PRESSURE + listOf("echo.tooling"),
                headerBudgetChars = 7200,
                splitSystemBlocks = true,
                decodePayloads = true,
                continuationBias = true,
                schemaLead = true,
                notes = "System is an array of text blocks; the identifier block is pinned to index 0 and must not be disturbed.",
            )
        }),
        Rule("openai", listOf("gpt-", "o1", "o3", "o4", "chatgpt", "codex"), {
            FrailProfile(
                family = "openai",
                transport = "chat-completions-or-responses",
                formIds = BASE_STANCE + listOf(
                    "frame.translation", "frame.editorial",
                    "dilution.fewshot", "dilution.context", "dilution.schema",
                    "decode.base64", "decode.marker", "decode.leet",
                    "intent.knowledge", "cot.dilution", "attn.frontload",
                ) + BASE_PRESSURE,
                headerBudgetChars = 6000,
                splitSystemBlocks = false,
                decodePayloads = true,
                continuationBias = false,
                schemaLead = true,
                notes = "content_filter is a hard finish_reason: no partial text survives, so recovery must re-issue rather than continue.",
            )
        }),
        Rule("gemini", listOf("gemini", "bison", "palm"), {
            FrailProfile(
                family = "gemini",
                transport = "generativelanguage",
                formIds = BASE_STANCE + listOf(
                    "frame.translation", "frame.draft",
                    "narrative.engine", "dilution.schema",
                    "decode.base64", "decode.homoglyph",
                    "xcarrier.split", "frame.workflow", "cot.execution",
                ) + BASE_PRESSURE,
                headerBudgetChars = 4800,
                splitSystemBlocks = false,
                decodePayloads = true,
                continuationBias = false,
                schemaLead = true,
                notes = "promptFeedback.blockReason and candidate.safetyRatings are both refusal surfaces and are checked independently.",
            )
        }),
        Rule("xai", listOf("grok"), {
            FrailProfile(
                family = "xai",
                transport = "xai-chat",
                formIds = BASE_STANCE + listOf(
                    "frame.continuation", "frame.editorial",
                    "narrative.analyst", "dilution.fewshot",
                    "decode.base64", "decode.marker",
                    "ctx.priming", "frame.mythic", "attn.frontload",
                ) + BASE_PRESSURE + listOf("pressure.recover"),
                headerBudgetChars = 5600,
                splitSystemBlocks = false,
                decodePayloads = true,
                continuationBias = true,
                schemaLead = false,
                notes = "Mirrors the OpenAI wire shape; refusal arrives as a plain finish reason, so it reads through the OpenAI path.",
            )
        }),
        Rule("deepseek", listOf("deepseek"), {
            FrailProfile(
                family = "deepseek",
                transport = "deepseek-chat",
                formIds = BASE_STANCE + listOf(
                    "frame.draft", "frame.translation",
                    "dilution.context", "dilution.fewshot", "dilution.schema",
                    "decode.base64", "decode.leet",
                    "cot.execution", "cot.dilution", "attn.redirect",
                ) + BASE_PRESSURE + listOf("pressure.recover"),
                headerBudgetChars = 6400,
                splitSystemBlocks = false,
                decodePayloads = true,
                continuationBias = true,
                schemaLead = true,
                notes = "Reasoning prefix may leak control markers into the visible block; marker scanning applies.",
            )
        }),
        Rule("qwen", listOf("qwen", "tongyi"), {
            FrailProfile(
                family = "qwen",
                transport = "dashscope-chat",
                formIds = BASE_STANCE + listOf(
                    "frame.translation", "frame.editorial",
                    "narrative.engine", "dilution.fewshot",
                    "decode.base64", "decode.homoglyph",
                ) + BASE_PRESSURE,
                headerBudgetChars = 5600,
                splitSystemBlocks = false,
                decodePayloads = true,
                continuationBias = false,
                schemaLead = true,
            )
        }),
        Rule("moonshot", listOf("moonshot", "kimi"), {
            FrailProfile(
                family = "moonshot",
                transport = "moonshot-chat",
                formIds = BASE_STANCE + listOf(
                    "frame.continuation", "frame.draft",
                    "narrative.analyst", "dilution.schema",
                    "decode.base64", "decode.marker",
                ) + BASE_PRESSURE,
                headerBudgetChars = 6000,
                splitSystemBlocks = false,
                decodePayloads = true,
                continuationBias = true,
                schemaLead = true,
            )
        }),
        Rule("minimax", listOf("minimax", "abab"), {
            FrailProfile(
                family = "minimax",
                transport = "minimax-chat",
                formIds = BASE_STANCE + listOf(
                    "frame.editorial", "frame.translation",
                    "dilution.context", "dilution.fewshot",
                    "decode.base64", "decode.marker",
                ) + BASE_PRESSURE,
                headerBudgetChars = 5200,
                splitSystemBlocks = false,
                decodePayloads = true,
                continuationBias = false,
                schemaLead = false,
            )
        }),
        Rule("zhipu", listOf("glm", "zhipu", "chatglm"), {
            FrailProfile(
                family = "zhipu",
                transport = "zhipu-chat",
                formIds = BASE_STANCE + listOf(
                    "frame.draft", "frame.continuation",
                    "narrative.analyst", "dilution.schema",
                    "decode.base64", "decode.leet",
                ) + BASE_PRESSURE,
                headerBudgetChars = 5200,
                splitSystemBlocks = false,
                decodePayloads = true,
                continuationBias = true,
                schemaLead = true,
            )
        }),
        Rule("xiaomi", listOf("mimo", "xiaomi"), {
            FrailProfile(
                family = "xiaomi",
                transport = "xiaomi-chat",
                formIds = BASE_STANCE + listOf(
                    "frame.translation", "frame.editorial",
                    "narrative.engine", "dilution.fewshot",
                    "decode.base64", "decode.homoglyph",
                ) + BASE_PRESSURE,
                headerBudgetChars = 4800,
                splitSystemBlocks = false,
                decodePayloads = true,
                continuationBias = false,
                schemaLead = false,
            )
        }),
        Rule("mistral", listOf("mistral", "mixtral", "codestral", "magistral"), {
            FrailProfile(
                family = "mistral",
                transport = "mistral-chat",
                formIds = BASE_STANCE + listOf(
                    "frame.editorial", "frame.translation",
                    "narrative.analyst", "dilution.schema",
                    "decode.base64", "decode.marker", "decode.leet",
                ) + BASE_PRESSURE + listOf("echo.tooling"),
                headerBudgetChars = 5600,
                splitSystemBlocks = false,
                decodePayloads = true,
                continuationBias = false,
                schemaLead = true,
                notes = "Tool-call capable; the tooling carry form is worth its tokens on agent turns.",
            )
        }),
        Rule("cohere", listOf("command", "cohere"), {
            FrailProfile(
                family = "cohere",
                transport = "cohere-chat",
                formIds = BASE_STANCE + listOf(
                    "frame.translation", "frame.draft",
                    "dilution.fewshot", "dilution.schema",
                    "decode.base64", "decode.homoglyph",
                ) + BASE_PRESSURE,
                headerBudgetChars = 4800,
                splitSystemBlocks = false,
                decodePayloads = true,
                continuationBias = false,
                schemaLead = true,
            )
        }),
        Rule("meta", listOf("llama", "meta-llama"), {
            FrailProfile(
                family = "meta",
                transport = "openai-compatible",
                formIds = BASE_STANCE + listOf(
                    "frame.continuation", "frame.draft", "frame.translation",
                    "narrative.engine", "dilution.fewshot", "dilution.context",
                    "decode.base64", "decode.leet", "decode.marker",
                    "frame.workflow", "intent.knowledge", "attn.frontload",
                ) + BASE_PRESSURE + listOf("pressure.recover", "echo.tooling"),
                headerBudgetChars = 6400,
                splitSystemBlocks = false,
                decodePayloads = true,
                continuationBias = true,
                schemaLead = true,
                notes = "Weights are reachable, so suffix-style payloads are a live option where the host permits them.",
            )
        }),
        Rule("alibaba", listOf("qvq", "wan"), {
            FrailProfile(
                family = "alibaba",
                transport = "dashscope-chat",
                formIds = BASE_STANCE + listOf(
                    "frame.editorial", "narrative.engine",
                    "dilution.fewshot", "dilution.schema",
                    "decode.base64", "decode.homoglyph",
                ) + BASE_PRESSURE,
                headerBudgetChars = 4800,
                splitSystemBlocks = false,
                decodePayloads = true,
                continuationBias = false,
                schemaLead = true,
            )
        }),
        Rule("perplexity", listOf("sonar", "perplexity"), {
            FrailProfile(
                family = "perplexity",
                transport = "openai-compatible",
                formIds = BASE_STANCE + listOf(
                    "frame.editorial", "frame.translation",
                    "dilution.schema", "decode.base64",
                ) + BASE_PRESSURE,
                headerBudgetChars = 4000,
                splitSystemBlocks = false,
                decodePayloads = false,
                continuationBias = false,
                schemaLead = true,
                notes = "Search-grounded: keep the layer short or it competes with the citation budget.",
            )
        }),
        Rule("generic", listOf(), {
            FrailProfile(
                family = "generic",
                transport = "openai-compatible",
                formIds = BASE_STANCE + listOf(
                    "frame.continuation", "frame.translation", "frame.draft",
                    "narrative.analyst", "narrative.engine",
                    "dilution.fewshot", "dilution.context", "dilution.schema",
                    "decode.base64", "decode.marker", "decode.leet", "decode.homoglyph",
                    "ctx.priming", "persona.memory", "intent.knowledge", "frame.workflow",
                    "cot.execution", "cot.dilution", "attn.frontload", "attn.redirect",
                    "frame.mythic", "xcarrier.split",
                ) + BASE_PRESSURE + listOf("pressure.recover", "echo.tooling"),
                headerBudgetChars = UNIVERSAL_HEADER,
                splitSystemBlocks = false,
                decodePayloads = true,
                continuationBias = true,
                schemaLead = true,
                notes = "Unknown endpoint: broadest form set, truncated to the budget. Every unrecognised model lands here.",
            )
        }),
    )

    fun familyOf(modelId: String?, providerName: String?): String {
        val hay = listOf(modelId ?: "", providerName ?: "").joinToString(" ").lowercase(Locale.ROOT)
        for (r in RULES) {
            if (r.family == "generic") continue
            if (r.tokens.any { hay.contains(it) }) return r.family
        }
        return "generic"
    }

    fun resolve(modelId: String?, providerName: String?): FrailProfile {
        val fam = familyOf(modelId, providerName)
        val rule = RULES.first { it.family == fam }
        return rule.profile()
    }

    fun generic(): FrailProfile = RULES.last().profile()

    fun families(): List<String> = RULES.map { it.family }

    fun selfTest(): List<String> {
        val failures = ArrayList<String>()
        val known = FrailRegistry.ids().toSet()
        val probes = listOf(
            "claude-opus-4-8" to "anthropic", "gpt-5" to "openai", "o3" to "openai",
            "gemini-3-pro" to "gemini", "grok-4" to "xai", "deepseek-r1" to "deepseek",
            "qwen3-8b" to "qwen", "kimi-k2" to "moonshot", "minimax-m2" to "minimax",
            "glm-5" to "zhipu", "mimo" to "xiaomi", "mistral-large" to "mistral",
            "command-a" to "cohere", "llama-4" to "meta", "qvq-72b" to "alibaba",
            "sonar-pro" to "perplexity", "some-unknown-model" to "generic",
            "" to "generic",
        )
        for ((model, want) in probes) {
            val got = resolve(model, "test").family
            if (got != want) failures.add("route $model -> $got, want $want")
        }
        for (r in RULES) {
            val p = r.profile()
            for (id in p.formIds) if (id !in known) {
                failures.add("profile ${r.family} references unknown form $id")
            }
            if (p.headerBudgetChars <= 0) {
                failures.add("profile ${r.family} has non-positive header budget")
            }
        }
        return failures
    }

    fun describe(p: FrailProfile): String = buildString {
        append("family=").append(p.family)
        append(" transport=").append(p.transport)
        append(" forms=").append(p.formIds.size)
        append(" budget=").append(p.headerBudgetChars)
        append(" splitSystem=").append(p.splitSystemBlocks)
        append(" decode=").append(p.decodePayloads)
        append(" continuation=").append(p.continuationBias)
        append(" schema=").append(p.schemaLead)
    }

    fun selfTest(): List<String> {
        val failures = ArrayList<String>()
        val probes = listOf(
            "claude-sonnet-4-5" to "anthropic",
            "gpt-5.1" to "openai",
            "gemini-3-pro" to "gemini",
            "grok-4" to "xai",
            "deepseek-v3.2" to "deepseek",
            "qwen3-max" to "qwen",
            "kimi-k2" to "moonshot",
            "MiniMax-M2" to "minimax",
            "glm-4.6" to "zhipu",
            "mimo-v2-flash" to "xiaomi",
            "mistral-large-3" to "mistral",
            "command-a" to "cohere",
            "llama-4-maverick" to "meta",
            "sonar-pro" to "perplexity",
            "something-entirely-new" to "generic",
        )
        for ((model, expected) in probes) {
            val got = familyOf(model, "any")
            if (got != expected) failures.add("probe $model -> $got, expected $expected")
        }
        for (f in families()) {
            val p = resolve(f, "")
            if (p.formIds.isEmpty()) failures.add("family $f resolved with no forms")
            if (p.headerBudgetChars < 2000) failures.add("family $f budget too small")
            val unknown = p.formIds.filter { FrailRegistry.byId(it) == null }
            if (unknown.isNotEmpty()) failures.add("family $f references missing forms $unknown")
        }
        return failures
    }
}
