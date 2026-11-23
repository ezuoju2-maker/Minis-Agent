package com.openminis.app.tools

import org.json.JSONObject

object PluginEcosystem {

    enum class Kind { NATIVE, SKILL, MCP }

    data class Plugin(
        val id: String,
        val name: String,
        val description: String,
        val kind: Kind,
        val skillBody: String = "",
        val configJson: String = "",
        val requiredEnv: List<String> = emptyList(),
    ) {
        val kindLabel: String get() = when (kind) {
            Kind.NATIVE -> "Built-in"
            Kind.SKILL -> "Skill"
            Kind.MCP -> "MCP"
        }
        val needsConfig: Boolean get() = requiredEnv.isNotEmpty()
    }

    fun nativePlugins(): List<Plugin> = listOf(
        Plugin(
            id = "code-reviewer",
            name = "Code Reviewer",
            description = "Structured code review: correctness, edge cases, security smell, and concrete fixes.",
            kind = Kind.NATIVE,
            skillBody = """
# Code Reviewer
When asked to review code, follow this checklist and report findings ordered by severity:
1. Correctness: logic errors, off-by-one, null/undefined, unchecked results.
2. Edge cases: empty input, boundaries, concurrency, large inputs.
3. Maintainability: naming, duplication, dead code, unclear control flow.
4. Security smell: unsanitized input, secrets in source, injection risks (report only — never exploit).
5. Tests: missing cases; suggest a minimal regression test.
For each finding give file, line, severity (P0/P1/P2), why, and a concrete fix. End with a short verdict.
""".trimIndent(),
        ),
        Plugin(
            id = "data-analyst",
            name = "Data Analyst",
            description = "Inspect datasets, clean data, compute statistics, and chart results with Python.",
            kind = Kind.NATIVE,
            skillBody = """
# Data Analyst
For data tasks: 1) inspect shape/columns/types and missing values; 2) state assumptions;
3) clean minimally and document each transform; 4) compute relevant statistics;
5) produce a chart when it aids understanding; 6) summarize findings with caveats.
Prefer pandas + matplotlib (Ubuntu env). Show the commands you ran so results are reproducible.
Never fabricate numbers — compute them from the actual data.
""".trimIndent(),
        ),
        Plugin(
            id = "web-researcher",
            name = "Web Researcher",
            description = "Multi-source web research with citation and cross-verification.",
            kind = Kind.NATIVE,
            skillBody = """
# Web Researcher
For research tasks: 1) break the question into sub-queries; 2) use browser_use to gather sources;
3) cross-verify key claims across at least two independent sources; 4) note dates and recency;
5) present findings with inline citations and a short 'confidence / what is uncertain' section.
Distinguish facts from inference. If sources conflict, say so and explain why.
""".trimIndent(),
        ),
        Plugin(
            id = "test-writer",
            name = "Test Writer",
            description = "Generate focused unit tests including edge cases and regression guards.",
            kind = Kind.NATIVE,
            skillBody = """
# Test Writer
When writing tests: 1) read the implementation and its existing tests first; 2) match the project's
test conventions and naming; 3) cover the happy path, boundaries, and error paths; 4) add one
regression test for the specific bug when fixing one; 5) keep tests deterministic (no wall-clock,
no network) and assert on behaviour, not implementation details. Run the tests and report results.
""".trimIndent(),
        ),
        Plugin(
            id = "release-checklist",
            name = "Release Checklist",
            description = "Pre-delivery audit: build, tests, versioning, and artifact integrity.",
            kind = Kind.NATIVE,
            skillBody = """
# Release Checklist
Before declaring a build done: 1) compile the exact deliverable target; 2) run the full test suite;
3) verify version name/code and app identity; 4) confirm artifact integrity (checksum / open test);
5) remove stale artifacts so only the final one remains; 6) report honestly any known gaps.
Never claim success on a failed test or a crash — diagnose and fix first.
""".trimIndent(),
        ),
    )

    fun catalog(): List<Plugin> = nativePlugins() + McpPluginCatalog.all().map { p ->
        Plugin(
            id = "mcp:" + p.id,
            name = p.name,
            description = p.description,
            kind = Kind.MCP,
            configJson = McpPluginCatalog.importJson(p),
            requiredEnv = p.requiredEnv,
        )
    }

    fun byId(id: String): Plugin? = catalog().firstOrNull { it.id.equals(id, true) }

    fun toSkillMarkdown(plugin: Plugin): String? {
        if (plugin.kind != Kind.NATIVE) return null
        return buildString {
            append("---\n")
            append("name: ${plugin.name}\n")
            append("description: ${plugin.description.replace('\n', ' ')}\n")
            append("version: 1.0.0\n")
            append("---\n\n")
            append(plugin.skillBody)
        }
    }

    fun manifest(maxChars: Int = 3_000): String {
        val sb = StringBuilder()
        sb.append("Plugin ecosystem: native capability packs install as skills (no setup); MCP plugins connect external tools (may need env vars).\n")
        for (p in catalog().take(30)) {
            val line = "- ${p.name} [${p.kindLabel}]: ${p.description.replace(Regex("\\s+"), " ").take(120)}\n"
            if (sb.length + line.length > maxChars) break
            sb.append(line)
        }
        return sb.toString().trimEnd()
    }
}
