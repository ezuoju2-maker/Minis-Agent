package com.openminis.app.provider

import com.openminis.app.data.model.AgentToolDefinition
import org.json.JSONObject

object ToolJsonRepair {

    fun repair(
        toolName: String,
        args: JSONObject,
        rawTail: String?,
        tools: List<AgentToolDefinition>,
    ): List<String> {
        val toolDef = tools.firstOrNull { it.name == toolName }
            ?: return emptyList()

        val repairs = mutableListOf<String>()

        if (args.length() == 0 && !rawTail.isNullOrBlank()) {
            val tail = rawTail.trim()
            val suffixes = listOf("", "\"", "\"}", "\"]}", "}", "}}", "]}", "]}}", "]", "]]")
            for (suffix in suffixes) {
                val candidate = tail + suffix
                val parsed = tryParseObject(candidate) ?: continue
                val keys = parsed.keys().asSequence().toList()
                for (k in keys) args.put(k, parsed.opt(k))
                repairs.add("truncation+" + if (suffix.isEmpty()) "noop" else suffix)
                break
            }
        }

        for (field in toolDef.required) {
            if (!args.has(field)) continue
            val raw = args.opt(field) ?: continue
            if (raw is String) continue
            if (raw === JSONObject.NULL) continue
            val coerced = raw.toString()
            if (coerced.trim().isNotEmpty()) {
                args.put(field, coerced)
                repairs.add("type-coerce:$field")
            }
        }

        val schemaFields = toolDef.parameters.keys
        for (field in toolDef.required) {
            if (args.has(field)) continue
            val keys = args.keys().asSequence().toList()
            val candidate = keys.firstOrNull { key ->
                key !in schemaFields && levenshteinAtMostOne(key, field)
            } ?: continue
            args.put(field, args.opt(candidate))
            args.remove(candidate)
            repairs.add("fuzzy:$candidate->$field")
        }

        return repairs
    }

    private fun tryParseObject(s: String): JSONObject? = try {
        JSONObject(s)
    } catch (_: Throwable) {
        null
    }

    private fun levenshteinAtMostOne(a: String, b: String): Boolean {
        val al = a.lowercase()
        val bl = b.lowercase()
        if (al == bl) return false
        val diff = al.length - bl.length
        if (diff > 1 || diff < -1) return false
        if (al.length == bl.length) {
            var mismatches = 0
            for (i in al.indices) {
                if (al[i] != bl[i]) {
                    mismatches += 1
                    if (mismatches > 1) return false
                }
            }
            return mismatches == 1
        }
        val longer = if (al.length > bl.length) al else bl
        val shorter = if (al.length > bl.length) bl else al
        var i = 0
        var j = 0
        var skipped = false
        while (i < longer.length && j < shorter.length) {
            if (longer[i] == shorter[j]) {
                i += 1; j += 1
            } else if (!skipped) {
                i += 1; skipped = true
            } else {
                return false
            }
        }
        return true
    }
}
