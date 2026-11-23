package com.openminis.app.data

import com.openminis.app.data.repository.EnvVarRepository

object EnvVarRedactor {
    const val MIN_MATCH_LEN = 5

    const val SYSTEM_REMINDER = "<system-reminder>Privacy mode is ON: one or more environment variable values were detected in this output and have been masked. Prefer running commands that consume secrets via environment variable references (e.g. `curl -H \"Authorization: Bearer \$API_KEY\" ...`) rather than echoing them. If the user needs to inspect raw values, ask them to disable Privacy Mode at Settings → Environment Variables.</system-reminder>"

    @Volatile
    var envVarRepository: EnvVarRepository? = null

    fun mask(value: String): String {
        val n = value.length
        if (n < 8) return "*".repeat(n)
        return value.substring(0, 2) + "*".repeat(n - 4) + value.substring(n - 2, n)
    }

    fun redact(output: String, values: Collection<String>): Pair<String, Int> {
        val candidates = values
            .filter { it.length >= MIN_MATCH_LEN }
            .toSet()
            .sortedByDescending { it.length }
        if (candidates.isEmpty()) return output to 0

        var current = output
        var hits = 0
        for (v in candidates) {
            if (current.contains(v)) {
                current = current.replace(v, mask(v))
                hits++
            }
        }
        return current to hits
    }

    fun redactIfEnabled(output: String): Pair<String, Int> {
        if (!EnvVarPrivacyStore.isEnabled) return output to 0
        val repo = envVarRepository ?: return output to 0
        val values = repo.allValuesForRedaction().filter { it.isNotEmpty() }
        if (values.isEmpty()) return output to 0
        val (masked, hits) = redact(output, values)
        if (hits == 0) return masked to 0
        return (masked + "\n\n" + SYSTEM_REMINDER) to hits
    }
}
