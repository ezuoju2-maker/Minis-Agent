package com.openminis.app.agent.approval

enum class CommandRisk { SAFE, WARN, BLOCK }

enum class ApprovalMode { ASK, ALLOW_SESSION, ALLOW_ALWAYS, DENY }

sealed class ApprovalDecision {
    object Execute : ApprovalDecision()
    object Ask : ApprovalDecision()
    data class Refuse(val reason: String) : ApprovalDecision()
}

class CommandApprovalPolicy(
    private val sessionTtlMs: Long = 6L * 60 * 60 * 1000,
) {

    private data class Remembered(val mode: ApprovalMode, val atMs: Long, val scope: String)

    private val remembered = HashMap<String, Remembered>()

    fun fingerprint(command: String): String {
        val sb = StringBuilder(command.length)
        var quote: Char? = null
        for (i in command.indices) {
            val c = command[i]
            when {
                quote != null && c == quote -> quote = null
                quote != null -> sb.append(c)
                c == '\'' || c == '"' -> quote = c
                else -> sb.append(c)
            }
        }
        val collapsed = sb.toString().replace(Regex("\\s+"), " ").trim()
        return collapsed.replace(Regex("\\d+"), "0")
    }

    fun decide(command: String, risk: CommandRisk, nowMs: Long, scope: String = "session"): ApprovalDecision {
        val key = fingerprint(command)
        expire(nowMs)
        val mem = remembered[key]
        when (risk) {
            CommandRisk.BLOCK -> return ApprovalDecision.Refuse("blocked by safety policy: $key")
            CommandRisk.SAFE -> return ApprovalDecision.Execute
            CommandRisk.WARN -> {
                if (mem == null) return ApprovalDecision.Ask
                return when (mem.mode) {
                    ApprovalMode.ALLOW_SESSION, ApprovalMode.ALLOW_ALWAYS -> ApprovalDecision.Execute
                    ApprovalMode.DENY -> ApprovalDecision.Refuse("denied by user: $key")
                    ApprovalMode.ASK -> ApprovalDecision.Ask
                }
            }
        }
    }

    fun remember(command: String, mode: ApprovalMode, nowMs: Long, scope: String = "session"): String {
        val key = fingerprint(command)
        remembered[key] = Remembered(mode, nowMs, scope)
        return key
    }

    fun modeOf(command: String, nowMs: Long): ApprovalMode {
        expire(nowMs)
        return remembered[fingerprint(command)]?.mode ?: ApprovalMode.ASK
    }

    fun revoke(command: String): Boolean = remembered.remove(fingerprint(command)) != null

    fun clearScope(scope: String) {
        remembered.entries.removeAll { it.value.scope == scope }
    }

    fun clear() = remembered.clear()

    val size: Int get() = remembered.size

    private fun expire(nowMs: Long) {
        val it = remembered.entries.iterator()
        while (it.hasNext()) {
            val e = it.next().value
            if (e.mode != ApprovalMode.ALLOW_ALWAYS && nowMs - e.atMs > sessionTtlMs) it.remove()
        }
    }
}
