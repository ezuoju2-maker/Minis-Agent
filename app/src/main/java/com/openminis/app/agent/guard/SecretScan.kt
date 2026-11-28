package com.openminis.app.agent.guard

object SecretScan {

    data class Hit(val kind: String, val preview: String)

    private data class Pattern(val kind: String, val regex: Regex)

    private val patterns = listOf(
        Pattern("Anthropic key", Regex("""\bsk-ant-[A-Za-z0-9_-]{20,}""")),
        Pattern("OpenAI-style key", Regex("""\bsk-[A-Za-z0-9]{20,}""")),
        Pattern("GitHub token", Regex("""\bgh[pousr]_[A-Za-z0-9]{30,}""")),
        Pattern("GitHub fine-grained token", Regex("""\bgithub_pat_[A-Za-z0-9_]{40,}""")),
        Pattern("AWS access key", Regex("""\b(?:AKIA|ASIA)[0-9A-Z]{16}""")),
        Pattern("Google API key", Regex("""\bAIza[0-9A-Za-z_-]{30,}""")),
        Pattern("Slack token", Regex("""\bxox[abprs]-[A-Za-z0-9-]{10,}""")),
        Pattern("Stripe key", Regex("""\b(?:sk|rk)_live_[A-Za-z0-9]{15,}""")),
        Pattern("private key block", Regex("""-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----""")),
    )

    private val fakeMarkers = listOf(
        "test", "example", "sample", "dummy", "placeholder", "your", "xxxx", "0000", "fake", "redacted",
    )

    fun scan(text: String): List<Hit> {
        if (text.isEmpty()) return emptyList()
        val hits = ArrayList<Hit>()
        for (p in patterns) {
            for (m in p.regex.findAll(text)) {
                val token = m.value
                if (isPlaceholder(token)) continue
                hits.add(Hit(p.kind, preview(token)))
                if (hits.size >= 4) return hits
            }
        }
        return hits
    }

    fun first(text: String): Hit? = scan(text).firstOrNull()

    fun isPlaceholder(token: String): Boolean {
        val lower = token.lowercase()
        return fakeMarkers.any { lower.contains(it) }
    }

    fun preview(token: String): String = when {
        token.length <= 10 -> token.take(4) + "…"
        else -> token.take(6) + "…" + token.takeLast(4)
    }

    fun guidance(hit: Hit): String =
        "Refused: this would write what looks like a live credential ($hit.kind, $hit.preview) into the workspace. " +
            "Files you write are read back into context, so the key would be sent to the model provider. " +
            "Store it as an environment variable (export NAME=… in the terminal, or Settings → Environment) " +
            "and reference ${'$'}NAME from code, or ask the user to paste it themselves. If this is a fixture or " +
            "documentation example, mark it clearly as fake (e.g. sk-test-…) and retry."
}
