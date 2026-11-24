package com.openminis.app.agent.approval

sealed class Submission {
    data class Run(val command: String) : Submission()
    data class Refused(val reason: String) : Submission()
    data class NeedsUser(val pending: Pending) : Submission()
}

data class Pending(
    val id: Long,
    val command: String,
    val risk: CommandRisk,
    val askedAtMs: Long,
)

sealed class Resolution {
    data class Approved(val command: String, val remembered: ApprovalMode) : Resolution()
    data class Denied(val reason: String) : Resolution()
}

class ApprovalCoordinator(
    private val policy: CommandApprovalPolicy = CommandApprovalPolicy(),
    private val timeoutMs: Long = 60_000,
) {

    private var nextId = 1L
    private val open = HashMap<Long, Pending>()

    fun submit(command: String, risk: CommandRisk, nowMs: Long, scope: String = "session"): Submission {
        expireOutstanding(nowMs)
        return when (val d = policy.decide(command, risk, nowMs, scope)) {
            is ApprovalDecision.Execute -> Submission.Run(command)
            is ApprovalDecision.Refuse -> Submission.Refused(d.reason)
            ApprovalDecision.Ask -> {
                val p = Pending(nextId++, command, risk, nowMs)
                open[p.id] = p
                Submission.NeedsUser(p)
            }
        }
    }

    fun resolve(pending: Pending, mode: ApprovalMode, nowMs: Long): Resolution {
        if (open.remove(pending.id) == null) {
            return Resolution.Denied("request already closed")
        }
        return when (mode) {
            ApprovalMode.DENY -> {
                policy.remember(pending.command, ApprovalMode.DENY, nowMs)
                Resolution.Denied("user denied: ${policy.fingerprint(pending.command)}")
            }
            ApprovalMode.ASK -> Resolution.Denied("user dismissed the prompt")
            ApprovalMode.ALLOW_SESSION, ApprovalMode.ALLOW_ALWAYS -> {
                policy.remember(pending.command, mode, nowMs)
                Resolution.Approved(pending.command, mode)
            }
        }
    }

    fun expireOutstanding(nowMs: Long): List<Pending> {
        val expired = open.values.filter { nowMs - it.askedAtMs > timeoutMs }
        for (p in expired) open.remove(p.id)
        return expired
    }

    fun openCount(): Int = open.size

    fun feedback(resolution: Resolution, risk: CommandRisk): String = when (resolution) {
        is Resolution.Approved -> "User approved the command. It ran once and the choice was remembered as ${
            when (resolution.remembered) {
                ApprovalMode.ALLOW_ALWAYS -> "always allow"
                ApprovalMode.ALLOW_SESSION -> "allow for this session"
                else -> "ask again"
            }
        }."
        is Resolution.Denied -> "Blocked by the user: ${resolution.reason}. Do not retry this command. Choose a safer alternative (narrow the path, dry-run first, or inspect before deleting) and explain what you will do instead."
    }
}
