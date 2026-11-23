package com.openminis.app.harness

/**
 * v2.0 · AgentKernel — the "Harness" that the closed-source survey identified
 * as the real moat. It wires together, in one deterministic state machine:
 *
 *   plan → step → tool proposal → risk gate → human approval → execution
 *        → event log → budget/loop guard → model routing/cost → memory
 *        → interrupt / resume / replay / audit
 *
 * Every side effect is injected (clock, executor, endpoints, tool catalog), so
 * the exact same class runs on Android, on the JVM in CI, and in an offline
 * τ-bench style simulator. No Android imports, no coroutines, no singletons
 * that outlive the run.
 */

enum class OutcomeKind { EXECUTED, NEED_APPROVAL, BLOCKED, STOPPED }

data class KernelOutcome(
    val kind: OutcomeKind,
    val result: ToolResult? = null,
    val request: ApprovalGate.Request? = null,
    val reason: String = "",
)

class AgentKernel(
    val runId: String = "run-" + System.nanoTime(),
    clock: () -> Long = { 0L },
    tools: List<ToolDescriptor> = defaultTools(),
    executor: (ToolDescriptor, String) -> ToolResult = defaultExecutor(),
    endpoints: List<ModelEndpoint> = defaultEndpoints(),
) {
    private val now = clock
    val events = EventSourcedRun(runId, now)
    val approvals = ApprovalGate(now)
    val memory = MemoryCore(now)
    val runtime = ToolRuntime(now, executor)
    val router = ModelRouter(endpoints, now)
    val governor = BudgetGovernor(clock = now)
    private val catalog = tools.associateBy { it.name }

    var mode: AgentMode = AgentMode.ACT
    private var currentStep = "run"

    // ---------------------------------------------------------------- lifecycle

    fun start(goal: String): AgentEvent =
        events.append(EventKind.RUN_STARTED, summary = goal, stepId = "run")

    fun submitPlan(steps: List<String>): List<AgentEvent> = steps.mapIndexed { i, s ->
        events.append(EventKind.PLAN_UPDATED, summary = s, stepId = "step-${i + 1}")
    }

    fun beginStep(stepId: String, what: String): AgentEvent {
        currentStep = stepId
        return events.append(EventKind.STEP_STARTED, summary = what, stepId = stepId)
    }

    // ---------------------------------------------------------------- tool call

    fun proposeTool(
        toolName: String,
        argsJson: String = "{}",
        intent: String = "",
        untrustedContext: Boolean = false,
    ): KernelOutcome {
        val verdict = governor.beforeStep()
        if (!verdict.proceed) {
            events.append(EventKind.ERROR, summary = verdict.reason, errorCode = "BUDGET", stepId = currentStep)
            events.append(EventKind.INTERRUPTED, summary = verdict.reason, stepId = currentStep)
            return KernelOutcome(OutcomeKind.STOPPED, reason = verdict.reason)
        }
        val tool = catalog[toolName]
            ?: return KernelOutcome(OutcomeKind.BLOCKED, reason = "unknown tool: $toolName (deny by default)")

        val decision = RiskPolicy.decide(tool, mode, approvals.sessionGranted, untrustedContext)
        events.append(
            EventKind.TOOL_REQUESTED, summary = intent.ifEmpty { tool.description },
            stepId = currentStep, payload = InjectionGuard.redact(argsJson),
            risk = tool.risk, toolName = tool.name, untrusted = untrustedContext,
        )

        return when (decision) {
            Decision.BLOCK -> {
                events.append(EventKind.DENIED, summary = "policy refused ${tool.name}", stepId = currentStep,
                    toolName = tool.name, risk = tool.risk)
                events.append(EventKind.ERROR, summary = "blocked by policy", errorCode = "POLICY", stepId = currentStep)
                KernelOutcome(OutcomeKind.BLOCKED, reason = "blocked by zero-trust policy")
            }
            Decision.CONFIRM_ONCE, Decision.CONFIRM_STRONG -> {
                events.append(EventKind.APPROVAL_REQUIRED, summary = intent, stepId = currentStep,
                    toolName = tool.name, risk = tool.risk)
                val req = approvals.request(runId, tool, decision, intent, argsJson)
                KernelOutcome(OutcomeKind.NEED_APPROVAL, request = req, reason = decision.name)
            }
            Decision.AUTO -> perform(tool, argsJson, "policy-auto")
        }
    }

    fun approve(requestId: Long, choice: ApprovalGate.Choice, actor: String = "user"): KernelOutcome {
        val req = approvals.pending().firstOrNull { it.id == requestId }
            ?: return KernelOutcome(OutcomeKind.BLOCKED, reason = "unknown or expired approval request")
        val rec = approvals.decide(requestId, choice, actor)
            ?: return KernelOutcome(OutcomeKind.BLOCKED, reason = "approval already decided")
        val approved = choice == ApprovalGate.Choice.APPROVE_ONCE || choice == ApprovalGate.Choice.APPROVE_SESSION
        events.append(
            if (approved) EventKind.APPROVED else EventKind.DENIED,
            summary = "${rec.toolName} ${choice.name} by $actor", stepId = currentStep,
            toolName = rec.toolName, risk = rec.risk, approvedBy = actor,
        )
        if (!approved) return KernelOutcome(OutcomeKind.BLOCKED, reason = "user ${choice.name}")
        val tool = catalog[rec.toolName]
            ?: return KernelOutcome(OutcomeKind.BLOCKED, reason = "tool vanished from catalog")
        return perform(tool, req.scope, "approval:$actor")
    }

    private fun perform(tool: ToolDescriptor, argsJson: String, via: String): KernelOutcome {
        val route = router.route(complexity = if (tool.risk >= Risk.R2) 3 else 1)
        val endpoint = route.endpoint
        if (endpoint == null) {
            events.append(EventKind.ERROR, summary = route.reason, errorCode = "NO_MODEL", stepId = currentStep)
            return KernelOutcome(OutcomeKind.STOPPED, reason = route.reason)
        }
        val result = runtime.execute(
            tool, argsJson,
            idempotencyKey = if (tool.idempotent) "$currentStep|${tool.name}" else null,
        )
        val trimmed = runtime.trim(result.content)
        val tokensIn = (argsJson.length + tool.description.length) / 4
        val tokensOut = trimmed.text.length / 4
        val cost = router.charge(endpoint, tokensIn, tokensOut)
        router.recordOutcome(endpoint.id, !result.isError, result.durationMs)
        governor.record(tokensDelta = tokensIn + tokensOut, costDelta = cost,
            action = tool.name + argsJson)

        events.append(
            EventKind.TOOL_RESULT,
            summary = "${tool.name}: " + if (result.isError) "failed" else "ok",
            stepId = currentStep, payload = trimmed.text, toolName = tool.name,
            risk = tool.risk, errorCode = result.errorCode,
            tokensIn = tokensIn, tokensOut = tokensOut, costMicros = cost,
            latencyMs = result.durationMs, traceId = via,
        )
        if (result.isError) {
            events.append(EventKind.ERROR, summary = result.content.take(200),
                errorCode = result.errorCode, stepId = currentStep)
        }
        if (route.degraded) {
            events.append(EventKind.SUMMARY, summary = "degraded model: simplified/read-only toolset",
                stepId = currentStep)
        }
        return KernelOutcome(OutcomeKind.EXECUTED, result = result.copy(content = trimmed.text))
    }

    // ---------------------------------------------------------------- control

    fun interrupt(reason: String): AgentEvent =
        events.append(EventKind.INTERRUPTED, summary = reason, stepId = currentStep)

    /** Resume from the last event; ALL session grants are dropped on restart. */
    fun resume(): Boolean {
        val st = events.state()
        if (st.status == RunStatus.COMPLETED) return false
        approvals.invalidateSessionGrants()
        events.append(EventKind.RESUMED, summary = "resumed from seq ${st.lastSeq}", stepId = currentStep)
        return true
    }

    fun artifact(name: String, content: String): AgentEvent =
        events.append(EventKind.ARTIFACT, summary = name, payload = InjectionGuard.redact(content), stepId = currentStep)

    fun complete(summary: String): List<AgentEvent> {
        memory.addEpisode(events.events.firstOrNull()?.summary ?: "", summary,
            events.state().completedSteps, events.state().costMicros, now())
        return listOf(
            events.append(EventKind.SUMMARY, summary = memory.compact(events.events), stepId = "run"),
            events.append(EventKind.RUN_COMPLETED, summary = summary, stepId = "run"),
        )
    }

    /** Rename avoids the JVM signature clash with the generated `mode` setter. */
    fun switchMode(newMode: AgentMode) {
        mode = newMode
        router.setPrivacyMode(newMode == AgentMode.PRIVACY)
    }

    // ---------------------------------------------------------------- projection & audit

    fun timeline(): List<TimelineItem> = events.timeline()
    fun export(): String = events.snapshot()
    fun restore(jsonl: String): Boolean = events.restore(jsonl)

    /** Post-change audit (point 4 of the brief): invariants over the whole log. */
    fun selfCheck(): List<String> {
        val issues = ArrayList(events.selfCheck())
        var autoR3 = 0
        var executedPayment = 0
        var approvalsWithoutActor = 0
        for (e in events.events) {
            if (e.risk == Risk.R3 && e.kind == EventKind.TOOL_RESULT) {
                val prevApproval = events.events.takeWhile { it.seq < e.seq }
                    .lastOrNull { it.kind == EventKind.APPROVED && it.toolName == e.toolName }
                if (prevApproval == null) autoR3++
            }
            if (e.kind == EventKind.TOOL_RESULT && RiskPolicy.isDenied(
                    catalog[e.toolName] ?: ToolDescriptor(e.toolName, "", Risk.R3, category = "payment"))) {
                executedPayment++
            }
            if (e.kind == EventKind.APPROVED && e.approvedBy.isNullOrEmpty()) approvalsWithoutActor++
        }
        if (autoR3 > 0) issues.add("$autoR3 R3 tool results without a matching approval")
        if (executedPayment > 0) issues.add("$executedPayment denied-category tools were executed")
        if (approvalsWithoutActor > 0) issues.add("$approvalsWithoutActor approvals without an actor")
        val st = events.state()
        if (st.status == RunStatus.COMPLETED && events.events.lastOrNull()?.kind != EventKind.RUN_COMPLETED) {
            issues.add("COMPLETED state without a terminal event")
        }
        if (st.costMicros > 5_000_000L) issues.add("cost budget exceeded in event log")
        return issues
    }

    companion object {
        fun defaultTools(): List<ToolDescriptor> = listOf(
            ToolDescriptor("read_screen", "读取当前界面结构（只读）", Risk.R0),
            ToolDescriptor("web_search", "联网搜索", Risk.R0, requiresNetwork = true, privacyModeAllowed = false),
            ToolDescriptor("open_app", "打开应用", Risk.R1,
                schemaJson = """{"required":["package"],"properties":{"package":{"type":"string"}}}"""),
            ToolDescriptor("write_file", "写入应用私有目录文件", Risk.R2, idempotent = true,
                schemaJson = """{"required":["path","text"],"properties":{"path":{"type":"string"},"text":{"type":"string"}}}"""),
            ToolDescriptor("send_message", "发送消息草稿", Risk.R2,
                schemaJson = """{"required":["to","text"],"properties":{"to":{"type":"string"},"text":{"type":"string"}}}"""),
            ToolDescriptor("delete_file", "删除文件（不可逆）", Risk.R3),
            ToolDescriptor("payment", "支付", Risk.R3, category = "payment"),
        )

        fun defaultExecutor(): (ToolDescriptor, String) -> ToolResult = { tool, args ->
            ToolResult("ok: ${tool.name} $args", false, false, null, 1L)
        }

        fun defaultEndpoints(): List<ModelEndpoint> = listOf(
            ModelEndpoint("cloud-primary", "主模型", 0, 3_000L, 15_000L),
            ModelEndpoint("cloud-backup", "备用供应商", 1, 1_000L, 4_000L, simplifiedToolset = true),
            ModelEndpoint("on-device-1b", "端侧 1B（离线兜底）", 2, 0L, 0L, isLocal = true, simplifiedToolset = true),
        )
    }
}
