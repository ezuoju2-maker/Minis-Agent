package com.openminis.app.provider.thinking

data class ThinkingRule(
    val kind: Kind,
    val scope: Scope,
    val wireFormat: ThinkingWireFormat?,
    val reasoningEcho: ReasoningEchoPolicy? = null,
    val label: String,
    val id: String = "",
) {
    val isEditable: Boolean get() = kind == Kind.CUSTOM

    val stableId: String
        get() {
            if (id.isNotEmpty()) return id
            val sk = if (scope is Scope.ModelPattern) "modelPattern" else "allModels"
            val sp = (scope as? Scope.ModelPattern)?.pattern ?: "*"
            return "builtin:$label:$sk:$sp"
        }

    enum class Kind {
        CUSTOM,

        OFFICIAL_VENDOR,

        PROVIDER_TYPE_DEFAULT,
    }

    sealed interface Scope {
        data object AllModels : Scope

        data class ModelPattern(val pattern: String) : Scope

        fun matches(modelId: String): Boolean = when (this) {
            is AllModels -> true
            is ModelPattern -> glob(
                pattern.lowercase().replace('.', '-'),
                modelId.lowercase().replace('.', '-'),
            )
        }

        companion object {
            fun glob(pattern: String, input: String): Boolean {
                val parts = pattern.split("*")
                if (parts.size == 1) return input == pattern

                var cursor = 0
                for ((i, part) in parts.withIndex()) {
                    if (part.isEmpty()) continue
                    if (i == 0) {
                        if (!input.startsWith(part)) return false
                        cursor = part.length
                        continue
                    }
                    if (i == parts.size - 1 && !pattern.endsWith("*")) {
                        if (!input.endsWith(part)) return false
                        if (input.length - cursor < part.length) return false
                        continue
                    }
                    val found = input.indexOf(part, cursor)
                    if (found < 0) return false
                    cursor = found + part.length
                }
                return true
            }
        }
    }
}

data class ReasoningEchoPolicy(
    val fieldName: String,
    val timing: Timing,
) {
    enum class Timing {
        EVERY_TURN,

        AFTER_TOOL_USE_ONLY,

        NEVER,
    }
}
