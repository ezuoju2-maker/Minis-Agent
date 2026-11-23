package com.openminis.app.harness

/**
 * v2.0 · Event-sourced agent core (借鉴 OpenHands SDK / LangGraph, 译为 Kotlin).
 *
 * Design contract: **the event log is the single source of truth**. The UI, the
 * resumable state, the cost panel, the audit trail and the replay tool are all
 * pure projections of this append-only log. This is what makes a phone Agent
 * survive process death, foreground-service restarts and "resume from step N".
 *
 * No Android, no coroutines, no external JSON: plain Kotlin stdlib only, so the
 * same file compiles in the app, in JVM unit tests and in an offline evaluator.
 */

// ---------------------------------------------------------------- risk & kinds

/** R0 只读自动 · R1 本地可逆自动但可见 · R2 外部可见需确认 · R3 不可逆强确认/禁用 */
enum class Risk { R0, R1, R2, R3 }

enum class EventKind {
    RUN_STARTED, PLAN_UPDATED, STEP_STARTED, MODEL_DELTA, TOOL_REQUESTED,
    APPROVAL_REQUIRED, APPROVED, DENIED, TOOL_RESULT, ARTIFACT, ERROR,
    INTERRUPTED, RESUMED, SUMMARY, RUN_COMPLETED
}

enum class RunStatus { IDLE, PLANNING, RUNNING, WAITING_APPROVAL, INTERRUPTED, COMPLETED, FAILED }

/**
 * An immutable, append-only fact. `payload` is ALWAYS redacted before it is
 * stored (see Observability.redact) — prompts and tool results never land in
 * the exported/telemetry copy by default.
 */
data class AgentEvent(
    val seq: Long,
    val runId: String,
    val ts: Long,
    val kind: EventKind,
    val stepId: String = "",
    val summary: String = "",
    val payload: String = "",
    val risk: Risk = Risk.R0,
    val toolName: String = "",
    val approvedBy: String? = null,
    val errorCode: String? = null,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val costMicros: Long = 0L,
    val latencyMs: Long = 0L,
    val traceId: String = "",
    val untrusted: Boolean = false,
) {
    fun toJson(): String = MiniJson.stringify(toMap())

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "seq" to seq, "runId" to runId, "ts" to ts, "kind" to kind.name,
        "stepId" to stepId, "summary" to summary, "payload" to payload,
        "risk" to risk.name, "toolName" to toolName, "approvedBy" to approvedBy,
        "errorCode" to errorCode, "tokensIn" to tokensIn, "tokensOut" to tokensOut,
        "costMicros" to costMicros, "latencyMs" to latencyMs,
        "traceId" to traceId, "untrusted" to untrusted,
    )

    companion object {
        fun fromJson(line: String): AgentEvent? {
            // [v2.0 audit fix FIX-21] A truncated last line (process killed mid
            // write) must be rejected, not thrown: restore() relies on null to
            // keep the healthy log intact.
            val m = runCatching { MiniJson.parseObject(line) }.getOrNull() ?: return null
            val kind = runCatching { EventKind.valueOf(MiniJson.str(m, "kind")) }.getOrNull() ?: return null
            val risk = runCatching { Risk.valueOf(MiniJson.str(m, "risk", "R0")) }.getOrDefault(Risk.R0)
            return AgentEvent(
                seq = MiniJson.long(m, "seq"), runId = MiniJson.str(m, "runId"),
                ts = MiniJson.long(m, "ts"), kind = kind,
                stepId = MiniJson.str(m, "stepId"), summary = MiniJson.str(m, "summary"),
                payload = MiniJson.str(m, "payload"), risk = risk,
                toolName = MiniJson.str(m, "toolName"),
                approvedBy = m["approvedBy"] as? String,
                errorCode = m["errorCode"] as? String,
                tokensIn = MiniJson.int(m, "tokensIn"), tokensOut = MiniJson.int(m, "tokensOut"),
                costMicros = MiniJson.long(m, "costMicros"),
                latencyMs = MiniJson.long(m, "latencyMs"),
                traceId = MiniJson.str(m, "traceId"),
                untrusted = MiniJson.bool(m, "untrusted"),
            )
        }
    }
}

// ---------------------------------------------------------------- folded state

data class RunState(
    val runId: String,
    val status: RunStatus = RunStatus.IDLE,
    val currentStepId: String = "",
    val plan: List<String> = emptyList(),
    val completedSteps: Int = 0,
    val pendingApprovalSeq: Long? = null,
    val interruptedAtSeq: Long? = null,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val costMicros: Long = 0L,
    val artifacts: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val lastSeq: Long = 0L,
)

/** UI projection: one row per step in the task timeline (ChatGPT/Cursor 范式). */
data class TimelineItem(
    val seq: Long,
    val stepId: String,
    val what: String,
    val tool: String,
    val status: String,       // planned | running | approved | denied | done | error | interrupted
    val risk: Risk,
    val durationMs: Long,
    val detail: String,
)

// ---------------------------------------------------------------- event store

class EventSourcedRun(
    val runId: String,
    private val clock: () -> Long = { 0L },
    private val sink: (AgentEvent) -> Unit = {},
) {
    private val log = ArrayList<AgentEvent>()
    private var nextSeq = 1L

    val events: List<AgentEvent> get() = log.toList()

    fun append(
        kind: EventKind,
        summary: String = "",
        stepId: String = "",
        payload: String = "",
        risk: Risk = Risk.R0,
        toolName: String = "",
        approvedBy: String? = null,
        errorCode: String? = null,
        tokensIn: Int = 0,
        tokensOut: Int = 0,
        costMicros: Long = 0L,
        latencyMs: Long = 0L,
        traceId: String = "",
        untrusted: Boolean = false,
    ): AgentEvent {
        val e = AgentEvent(
            seq = nextSeq++, runId = runId, ts = clock(), kind = kind,
            stepId = stepId, summary = summary,
            payload = Observability.redact(payload), risk = risk, toolName = toolName,
            approvedBy = approvedBy, errorCode = errorCode,
            tokensIn = tokensIn, tokensOut = tokensOut, costMicros = costMicros,
            latencyMs = latencyMs, traceId = traceId, untrusted = untrusted,
        )
        log.add(e)
        sink(e)
        return e
    }

    /** Fold the log into the current state. Pure: same log → same state. */
    fun state(): RunState = fold(log)

    fun fold(events: List<AgentEvent>): RunState {
        var s = RunState(runId)
        for (e in events) {
            s = when (e.kind) {
                EventKind.RUN_STARTED -> s.copy(status = RunStatus.PLANNING, lastSeq = e.seq)
                EventKind.PLAN_UPDATED -> s.copy(status = RunStatus.RUNNING, plan = s.plan + e.summary, lastSeq = e.seq)
                EventKind.STEP_STARTED -> s.copy(status = RunStatus.RUNNING, currentStepId = e.stepId, lastSeq = e.seq)
                EventKind.APPROVAL_REQUIRED -> s.copy(status = RunStatus.WAITING_APPROVAL, pendingApprovalSeq = e.seq, lastSeq = e.seq)
                EventKind.APPROVED, EventKind.DENIED -> s.copy(status = RunStatus.RUNNING, pendingApprovalSeq = null, lastSeq = e.seq)
                EventKind.TOOL_RESULT -> s.copy(completedSteps = s.completedSteps + 1, lastSeq = e.seq)
                EventKind.ARTIFACT -> s.copy(artifacts = s.artifacts + e.summary, lastSeq = e.seq)
                EventKind.ERROR -> s.copy(errors = s.errors + e.summary, status = RunStatus.FAILED, lastSeq = e.seq)
                EventKind.INTERRUPTED -> s.copy(status = RunStatus.INTERRUPTED, interruptedAtSeq = e.seq, lastSeq = e.seq)
                EventKind.RESUMED -> s.copy(status = RunStatus.RUNNING, lastSeq = e.seq)
                EventKind.RUN_COMPLETED -> s.copy(status = RunStatus.COMPLETED, lastSeq = e.seq)
                else -> s.copy(lastSeq = e.seq)
            }
            if (e.tokensIn > 0 || e.tokensOut > 0 || e.costMicros > 0L) {
                s = s.copy(
                    tokensIn = s.tokensIn + e.tokensIn,
                    tokensOut = s.tokensOut + e.tokensOut,
                    costMicros = s.costMicros + e.costMicros,
                )
            }
        }
        return s
    }

    /** Rebuild UI rows from the log — zero extra storage, deterministic. */
    fun timeline(): List<TimelineItem> {
        val rows = LinkedHashMap<String, TimelineItem>()
        for (e in log) {
            val key = e.stepId.ifEmpty { "run" }
            val prev = rows[key]
            val status = when (e.kind) {
                EventKind.PLAN_UPDATED -> "planned"
                EventKind.STEP_STARTED -> "running"
                EventKind.APPROVAL_REQUIRED -> "approval"
                EventKind.APPROVED -> "approved"
                EventKind.DENIED -> "denied"
                EventKind.TOOL_RESULT -> if (e.errorCode == null) "done" else "error"
                EventKind.ERROR -> "error"
                EventKind.INTERRUPTED -> "interrupted"
                EventKind.RUN_COMPLETED -> "done"
                else -> prev?.status ?: "running"
            }
            rows[key] = TimelineItem(
                seq = e.seq, stepId = key,
                what = prev?.what?.takeIf { it.isNotEmpty() } ?: e.summary,
                tool = e.toolName.ifEmpty { prev?.tool ?: "" },
                status = status, risk = e.risk,
                durationMs = (prev?.durationMs ?: 0L) + e.latencyMs,
                detail = if (prev == null) e.payload else (prev.detail + "\n" + e.payload).trim(),
            )
        }
        return rows.values.toList()
    }

    fun snapshot(): String = log.joinToString("\n") { it.toJson() }

    /** Transactional restore: a corrupt line must never wipe a healthy log. */
    fun restore(jsonl: String): Boolean {
        val parsed = ArrayList<AgentEvent>()
        for (line in jsonl.lineSequence()) {
            if (line.isBlank()) continue
            val e = AgentEvent.fromJson(line) ?: return false
            if (e.runId != runId) return false
            parsed.add(e)
        }
        log.clear()
        log.addAll(parsed)
        nextSeq = (parsed.maxOfOrNull { it.seq } ?: 0L) + 1L
        return true
    }

    /** Integrity check used by the post-change audit (point 4 of the brief). */
    fun selfCheck(): List<String> {
        val issues = ArrayList<String>()
        log.forEachIndexed { i, e ->
            if (e.seq != (i + 1).toLong()) issues.add("seq gap at index $i: ${e.seq}")
            if (e.runId != runId) issues.add("runId mismatch at seq ${e.seq}")
            if (e.payload.contains("Bearer ")) issues.add("unredacted credential at seq ${e.seq}")
        }
        return issues
    }
}

// ---------------------------------------------------------------- observability

/**
 * OpenTelemetry-style span semantics, local-first. Only metadata leaves the
 * device; prompts and tool results are redacted and opt-in for upload.
 */
object Observability {
    private val SECRET = Regex(
        "(?i)(bearer\\s+[a-z0-9._\\-]{8,}|sk-[a-z0-9]{16,}|api[_-]?key\\s*[:=]\\s*\\S+|" +
            "password\\s*[:=]\\s*\\S+|1[3-9]\\d{9}|\\d{6}|[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,})"
    )

    fun redact(text: String): String = SECRET.replace(text) { m ->
        val v = m.value
        "***" + if (v.length > 12) v.takeLast(4) else ""
    }

    fun span(
        runId: String, step: Int, name: String, model: String = "",
        tokensIn: Int = 0, tokensOut: Int = 0, latencyMs: Long = 0L,
        tool: String = "", risk: Risk = Risk.R0, approved: Boolean? = null,
        errorCode: String? = null,
    ): String = MiniJson.obj(
        "trace" to runId, "step" to step, "span" to name, "model" to model,
        "gen_ai.usage.prompt_tokens" to tokensIn,
        "gen_ai.usage.completion_tokens" to tokensOut,
        "latency_ms" to latencyMs, "tool" to tool, "risk" to risk.name,
        "approval" to approved, "error" to errorCode,
    )

    /** Export only metadata by default; payload export requires explicit consent. */
    fun export(events: List<AgentEvent>, includePayload: Boolean = false): String =
        events.joinToString("\n") { e ->
            MiniJson.obj(
                "seq" to e.seq, "ts" to e.ts, "kind" to e.kind.name,
                "step" to e.stepId, "tool" to e.toolName, "risk" to e.risk.name,
                "tokens_in" to e.tokensIn, "tokens_out" to e.tokensOut,
                "cost_micros" to e.costMicros, "latency_ms" to e.latencyMs,
                "error" to e.errorCode, "approved_by" to e.approvedBy,
                "payload" to if (includePayload) e.payload else "",
            )
        }
}
