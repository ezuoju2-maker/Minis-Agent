package com.openminis.app.agent.jobs

import android.content.Context
import com.openminis.app.R
import org.json.JSONArray
import org.json.JSONObject


data class HelperConfig(
    val parentSessionId: String,
    val parentToolUseId: String,
    val jobId: String,
    val maxTurns: Int,
    val title: String,
    val tierUsed: HelperModelTier,
    val agentName: String? = null,
    val agentInstructions: String = "",
)

data class DelegateTaskArgs(
    val title: String,
    val task: String,
    val context: String,
    val tier: HelperModelTier,
    val tierRequested: String,
    val agentName: String?,
    val modelChoice: SubAgentModelChoice,
    val maxTurns: Int,
    val minutes: Int,
    val wait: Boolean,
    val progressLevel: String,
)

enum class HelperWrapUpReason { TURNS, BUDGET }

object HelperRunner {
    const val TOOL_NAME = com.openminis.app.data.model.SubAgentDefinition.TOOL_NAME

    const val LEGACY_TOOL_NAME = "delegate_task"
    const val LEGACY_STATUS_TOOL_NAME = "agent_status"

    fun isSubAgentToolName(name: String?): Boolean =
        name == TOOL_NAME || name == LEGACY_TOOL_NAME || name == LEGACY_STATUS_TOOL_NAME

    const val MAX_TURNS = com.openminis.app.agent.SubAgentBudgetPolicy.DEFAULT_TURNS
    const val MAX_PER_ASSISTANT_TURN = 3
    const val DEFAULT_MINUTES = 10
    const val MAX_MINUTES = 60

    const val WRAP_UP_GRACE_MS = 90_000L
    const val WRAP_UP_TOOL_PATIENCE_MS = 20_000L

    const val PROGRESS_INTERVAL_FREQUENT_MS = 15_000L
    const val PROGRESS_INTERVAL_MODERATE_MS = 60_000L
    const val PROGRESS_LAST_MESSAGE_MAX_CHARS = 800

    const val QUEUED_REENTRY_KEY = "__from_queue"

    const val QUEUED_RESUME_CHILD_KEY = "__resume_child"

    const val STEER_NUDGE_PROMPT =
        "The delegating agent has sent you a course correction. Read it and continue."

    fun shouldContinueForSteer(
        isHelper: Boolean,
        isEmptyTurn: Boolean,
        turn: Int,
        turnCap: Int,
        pendingSteers: Int,
    ): Boolean = isHelper && !isEmptyTurn && pendingSteers > 0 && turn + 1 < turnCap

    const val CLOSING_NUDGE_TEXT =
        "<system-reminder>This note is from the app, not from the delegating agent. Your last reply contained no text, so the delegating agent has received nothing from you. Tools are no longer available. Write your final answer now from what you already have: the deliverable itself, what you finished, what failed (including any tool error above) and what is left. Mark anything you could not verify as unverified.</system-reminder>"

    fun shouldSendClosingRequest(
        isHelper: Boolean,
        alreadySent: Boolean,
        turnHasText: Boolean,
        turn: Int,
        turnCap: Int,
    ): Boolean = isHelper && !alreadySent && !turnHasText && turn + 1 < turnCap

    fun parseArgs(argsJson: String): DelegateTaskArgs {
        val o = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val title = o.optString("tool_title", "").trim()
        val task = o.optString("task", "").trim()
        val tierRaw = o.optString("model_tier", "primary").trim().lowercase()
        val agentName = o.optString("agent", "").trim().ifEmpty { null }
        val modelChoice = SubAgentModelChoice.parse(o.optString("model_choice", "").trim())
        val turnsRaw = if (o.has("max_turns")) o.optDouble("max_turns", MAX_TURNS.toDouble()).toInt() else null
        val minutesRaw = if (o.has("max_minutes")) o.optDouble("max_minutes", DEFAULT_MINUTES.toDouble()).toInt() else DEFAULT_MINUTES
        val level = o.optString("progress_report", "none").trim().lowercase()
        return DelegateTaskArgs(
            title = title.ifEmpty { task.take(40) },
            task = task,
            context = o.optString("context", "").trim(),
            tier = HelperModelTier.parse(tierRaw),
            tierRequested = tierRaw.ifEmpty { "primary" },
            agentName = agentName,
            modelChoice = modelChoice,
            maxTurns = com.openminis.app.agent.SubAgentBudgetPolicy.coerceTurns(turnsRaw),
            minutes = minutesRaw.coerceIn(1, MAX_MINUTES),
            wait = if (o.has("wait")) o.optBoolean("wait", false) else false,
            progressLevel = if (level in setOf("none", "frequent", "moderate")) level else "none",
        )
    }

    fun childPrompt(args: DelegateTaskArgs): String =
        if (args.context.isEmpty()) args.task
        else args.task + "\n\n--- Context from the delegating agent ---\n" + args.context

    fun childSessionTitle(context: Context, title: String, subAgentName: String? = null): String {
        val label = context.getString(R.string.helper_label)
        val name = subAgentName?.trim().orEmpty()
        return if (name.isEmpty()) "$label · $title" else "$label · $name · $title"
    }

    fun stripChildSessionTitlePrefix(context: Context, title: String): String {
        var t = title
        for (prefix in listOf("${context.getString(R.string.helper_label)} · ", "Agent · ", "Helper · ", "代理任务 · ", "帮手 · ")) {
            if (t.startsWith(prefix)) t = t.removePrefix(prefix)
        }
        return t
    }


    fun wrapUpPrompt(reason: HelperWrapUpReason): String {
        val why = if (reason == HelperWrapUpReason.TURNS) "You have used all of your tool rounds." else "Your time budget is up."
        return "[$why Tools are no longer available for this turn.]\n" +
            "Write your final answer NOW from what you already have. It must contain the complete deliverable itself — the full report, document, list or answer the task asked for — not a description of what you did, not a pointer to a file, not a request for more time. Mark anything you could not verify as unverified. This message is returned to the parent agent verbatim."
    }

    const val TURN_WARNING_LEAD = 3

    fun warningLead(turnCap: Int): Int = com.openminis.app.agent.SubAgentBudgetPolicy.warningLead(turnCap)

    fun shouldWarn(remaining: Int, turnCap: Int): Boolean =
        com.openminis.app.agent.SubAgentBudgetPolicy.shouldWarn(remaining, turnCap)

    fun turnBudgetWarning(remaining: Int): String =
        "[Budget check: $remaining tool ${if (remaining == 1) "round" else "rounds"} left before tools are withdrawn.] " +
            "Stop investigating now and spend what is left on finishing: if the task asked you to write a file or produce an artifact, do it in the next round, then write your final answer. " +
            "Remember the final message must contain the complete deliverable itself, not a pointer to it."

    fun emptyResultNote(status: String): String =
        "(the agent ended with status $status before writing a final answer; there is no deliverable — re-delegate with a narrower task or a larger budget rather than reading its transcript)"

    fun identitySection(cfg: HelperConfig, browserEnabled: Boolean = true): String =
        "You are running as a helper (sub-agent) inside an app called Minis, on an Android device with a fully functional Linux shell (Ubuntu 24.04, aarch64). This is the calling environment, not your identity — keep your own model identity unchanged.\n" +
            "A parent agent delegated ONE focused task to you: \"${cfg.title}\". You cannot see the parent conversation and it cannot see yours; everything you need is in the task text. Work the task to completion using your tools, then finish with a single clear final answer — that final message is returned verbatim to the parent agent as the result, and it is the ONLY thing the parent receives. So the final message must contain the complete deliverable itself: the full report, document, list, code or answer the task asked for, in full — never a summary of what you did, a pointer to a file you wrote, or \"see above\". If you produced a file, paste its full content into the final message as well. Do not ask the parent or the user questions; make reasonable assumptions and state them. Do not restate the task or add meta-commentary. Do not create scheduled tasks or delegate further.\n" +
            browserSharingSection(browserEnabled) +
            "You have at most ${cfg.maxTurns} tool rounds; on the last one tools are withdrawn and you are asked to write the deliverable from what you have, so start writing before you run out. If the task genuinely exceeds what you can do at your capability level, end your final message with a line `[ESCALATE] <one-line reason>` so the parent can rerun it on a stronger model.\n\n" +
            subAgentInstructionsSection(cfg.agentInstructions)

    fun browserSharingSection(browserEnabled: Boolean): String {
        if (!browserEnabled) return ""
        return "Browser: the browser is shared with the parent and with other agents. " +
            "You may only use your own tabs (up to ${com.openminis.app.browser.BrowserTabPool.AGENT_TAB_QUOTA}); " +
            "list_tabs shows just yours. To open a page, navigate (a tab of yours is picked or created) " +
            "or use new_tab; never guess tab ids you did not receive, and reuse your tabs by navigating " +
            "them instead of opening more.\n"
    }

    fun subAgentInstructionsSection(instructions: String): String {
        val trimmed = instructions.trim()
        if (trimmed.isEmpty()) return ""
        return "\n--- Sub agent instructions (set by the user) ---\n" + trimmed + "\n\n"
    }

    fun subAgentRosterSection(
        roster: List<com.openminis.app.data.model.SubAgentDefinition>,
        groupName: (String) -> String?,
    ): String {
        if (roster.isEmpty()) return ""
        val lines = mutableListOf(
            "Available sub agents (pass the name as ${TOOL_NAME}.agent):",
        )
        for (def in roster) {
            val desc = def.description
                .take(com.openminis.app.data.model.SubAgentLimits.DESCRIPTION_MAX_LENGTH)
                .trim()
            val pinned = def.modelGroupId?.let { groupName(it) }
            val model = if (pinned != null) {
                "fixed — $pinned"
            } else {
                "Auto — you choose with model_choice"
            }
            lines.add("- ${def.name} — $desc Model: $model.")
        }
        lines.add("Prefer a specific sub agent when its description matches; otherwise use the general one.")
        return lines.joinToString("\n") + "\n"
    }

    fun systemPromptBullet(enabled: Boolean): String = if (!enabled) "" else
        "- subagent_task: Delegate a self-contained task to a sub agent that runs its own tool loop in an isolated hidden session, and inspect or stop the ones you started. Full contract in the tool schema. Two things it does not say: the `<agent_callback>` result and progress messages are written by the system, not typed by the user; and while a sub agent runs the user sees it in the tool bar and can watch or stop it.\n"


    fun isQueuedLive(toolUseId: String): Boolean = AgentJobRegistry.isQueued(toolUseId)

    fun resumeNotice(): String =
        "[This run was interrupted and has been resumed. The tool results above are still valid, but all live state is gone: browser tabs are closed, shell processes have ended, and anything unsaved is lost. Files in the workspace are still there. Continue from what the transcript already establishes — reopen pages or re-run commands when you need them, and do not assume anything is still open.]"

    const val NO_DELIVERABLE = "no_deliverable"

    fun resolvedStatus(status: String, result: String): String {
        if (status != "completed") return status
        return if (SubAgentTerminationRules.isFinalAnswer(result)) status else NO_DELIVERABLE
    }

    fun lastToolSnapshot(messages: List<com.openminis.app.ui.chat.ChatMessage>): SubAgentToolSnapshot? {
        for (msg in messages.asReversed()) {
            if (msg.role != "assistant") continue
            val block = msg.toolBlocks.lastOrNull { it.kind == "tool_use" && it.toolStatus != null } ?: continue
            val word = when (block.toolStatus) {
                com.openminis.app.ui.chat.ToolBlockStatus.SUCCESS -> "success"
                com.openminis.app.ui.chat.ToolBlockStatus.FAILED, com.openminis.app.ui.chat.ToolBlockStatus.TIMEOUT -> "failed"
                com.openminis.app.ui.chat.ToolBlockStatus.CANCELLED -> "cancelled"
                else -> "running"
            }
            var name = block.toolName.ifEmpty { "tool" }
            if (block.toolTitle.isNotBlank()) name += " \"" + SubAgentTerminationRules.summarize(block.toolTitle, 80) + "\""
            return SubAgentToolSnapshot(name, word, SubAgentTerminationRules.summarize(block.content))
        }
        return null
    }

    fun terminationOf(
        childSessionId: String,
        jobId: String,
        parentSessionId: String?,
        loopStatus: String,
        budgetExpired: Boolean,
        errorText: String?,
        history: List<com.openminis.app.data.model.LLMMessage>,
        resultText: String,
        lastTool: SubAgentToolSnapshot?,
        closingAttempted: Boolean,
        delivery: SubAgentTermination.Delivery,
        nowMs: Long = System.currentTimeMillis(),
    ): SubAgentTermination {
        val hasFinal = SubAgentTerminationRules.isFinalAnswer(resultText) &&
            SubAgentTerminationRules.endsWithFinalTurn(SubAgentTerminationRules.shapesOf(history))
        val loop = if (loopStatus == "cancelled" || loopStatus == "timeout") loopStatus else "completed"
        val (reason, detail) = SubAgentTerminationRules.classify(
            SubAgentRunEnd(
                loopStatus = loop, budgetExpired = budgetExpired, errorText = errorText,
                hasFinalAnswer = hasFinal, lastToolStatus = lastTool?.status,
            ),
        )
        return SubAgentTermination(
            sessionId = childSessionId, jobId = jobId, parentSessionId = parentSessionId,
            reason = reason, detail = detail,
            lastTool = lastTool?.name, lastToolStatus = lastTool?.status, lastToolSummary = lastTool?.summary,
            partialOutputAvailable = reason != SubAgentTermination.Reason.COMPLETED && resultText.isNotBlank(),
            closingAttempted = closingAttempted,
            delivery = delivery,
            startedAtMs = nowMs,
            endedAtMs = nowMs,
        )
    }

    fun reportedStatus(loopStatus: String, result: String, termination: SubAgentTermination?): String {
        val s = resolvedStatus(loopStatus, result)
        return if (s == "completed" && termination != null && termination.reason != SubAgentTermination.Reason.COMPLETED) NO_DELIVERABLE else s
    }

    enum class ErrorKind(val res: Int) {
        CONTEXT_LIMIT(R.string.agent_error_context_limit),
        RATE_LIMITED(R.string.agent_error_rate_limited),
        QUOTA_EXHAUSTED(R.string.agent_error_quota_exhausted),
        PROVIDER_OVERLOADED(R.string.agent_error_provider_overloaded),
        TIMED_OUT(R.string.agent_error_timed_out),
        AUTH_FAILED(R.string.agent_error_auth_failed),
        NETWORK_ERROR(R.string.agent_error_network),
        CANCELLED(R.string.agent_error_cancelled),
        ;

        val wire: String get() = name.lowercase()

        companion object {
            fun fromWire(v: String?): ErrorKind? =
                v?.let { w -> entries.firstOrNull { it.wire == w } }
        }
    }

    fun errorKind(raw: String?): ErrorKind? {
        val s = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (s.contains("context") && (s.contains("length") || s.contains("window") || s.contains("too long"))) {
            return ErrorKind.CONTEXT_LIMIT
        }
        if (s.contains("rate limit") || s.contains("429") || s.contains("too many requests")) {
            return ErrorKind.RATE_LIMITED
        }
        if (s.contains("quota") || s.contains("insufficient") || s.contains("credit") || s.contains("billing")) {
            return ErrorKind.QUOTA_EXHAUSTED
        }
        if (s.contains("overload") || s.contains("529") || s.contains("503") || s.contains("unavailable")) {
            return ErrorKind.PROVIDER_OVERLOADED
        }
        if (s.contains("timed out") || s.contains("timeout")) return ErrorKind.TIMED_OUT
        if (s.contains("unauthor") || s.contains("401") || s.contains("403") ||
            s.contains("api key") || s.contains("credential")
        ) {
            return ErrorKind.AUTH_FAILED
        }
        if (s.contains("offline") || s.contains("network") || s.contains("connection") ||
            s.contains("host") || s.contains("internet")
        ) {
            return ErrorKind.NETWORK_ERROR
        }
        if (s.contains("cancel")) return ErrorKind.CANCELLED
        return null
    }


    enum class SubAgentCall { DELEGATE, STATUS, RESUME, STEER, CANCEL }

    fun classifyCall(argsJson: String?, resultJson: String?): SubAgentCall {
        val action = argsJson?.let {
            runCatching { JSONObject(it).optString("action", "").trim().lowercase() }.getOrNull()
        }?.takeIf { it.isNotEmpty() }
        if (action != null) {
            return when (action) {
                "status" -> SubAgentCall.STATUS
                "resume" -> SubAgentCall.RESUME
                "steer" -> SubAgentCall.STEER
                "cancel" -> SubAgentCall.CANCEL
                else -> SubAgentCall.DELEGATE
            }
        }

        val o = resultJson?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return SubAgentCall.DELEGATE

        if (o.has("child_session_id") && o.optString("child_session_id").isNotEmpty()) {
            return SubAgentCall.DELEGATE
        }
        if (o.has("agents")) return SubAgentCall.STATUS
        if (o.has("child_session_ids")) return SubAgentCall.RESUME
        if (o.optString("status") == "queued" && o.has("job_id")) return SubAgentCall.STEER
        val reason = o.optString("reason")
        if (reason == "already_finished" || reason == "child_not_running") return SubAgentCall.STEER
        return SubAgentCall.DELEGATE
    }

    fun isControlOnly(argsJson: String?, resultJson: String?): Boolean =
        classifyCall(argsJson, resultJson) != SubAgentCall.DELEGATE

    fun controlCount(call: SubAgentCall, resultJson: String?): Int? {
        val o = resultJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return when (call) {
            SubAgentCall.STATUS -> o.optJSONArray("agents")?.length()
            SubAgentCall.RESUME -> o.optJSONArray("child_session_ids")?.length()
            else -> null
        }
    }

    fun queuedJson(queuedBehind: Int, detail: String): String =
        JSONObject()
            .put("ok", true)
            .put("status", "queued")
            .put("queued_behind", maxOf(0, queuedBehind))
            .put("detail", detail)
            .toString()

    fun rejectionJson(reason: String, detail: String): String =
        JSONObject().put("ok", false).put("status", "rejected").put("reason", reason).put("detail", detail).toString()

    fun progressJson(childSessionId: String, title: String, tool: String, activity: String, elapsedMs: Long, background: Boolean = false): String =
        JSONObject()
            .put("status", "running")
            .put("child_session_id", childSessionId)
            .put("title", title)
            .put("tool", tool)
            .put("activity", activity.take(80))
            .put("elapsed_s", elapsedMs / 1000)
            .put("background", background)
            .toString()

    fun resultJson(
        status: String, result: String, modelLabel: String, tierUsed: HelperModelTier, tierRequested: String,
        turns: Int, elapsedMs: Long, childSessionId: String, jobId: String,
        summary: String = "", deliveredAs: String? = null,
        agentName: String? = null,
        modelOrigin: HelperModelOrigin? = null,
        modelGroupName: String? = null,
        modelGroupUnavailable: Boolean = false,
        wasResumed: Boolean = false,
        errorText: String? = null,
        resolvedEntryId: String? = null,
        resolvedProviderLabel: String? = null,
        resolvedProviderType: String? = null,
        resolvedModelId: String? = null,
        resolvedModelName: String? = null,
        thinkingLevel: String? = null,
        termination: SubAgentTermination? = null,
    ): String = JSONObject()
        .put("ok", status == "completed")
        .put("status", status)
        .put(
            "result",
            if (termination != null && termination.isTerminal) SubAgentTerminationRules.helperResultText(result, termination)
            else result.ifEmpty { emptyResultNote(status) },
        )
        .put("model_used", modelLabel)
        .put("tier_used", tierUsed.wire)
        .put("tier_requested", tierRequested)
        .also { j ->
            fun put(k: String, v: String?) { if (!v.isNullOrEmpty()) j.put(k, v) }
            put("model_resolved_entry_id", resolvedEntryId)
            put("model_resolved_provider", resolvedProviderLabel)
            put("model_resolved_provider_type", resolvedProviderType)
            put("model_resolved_id", resolvedModelId)
            put("model_resolved_name", resolvedModelName)
            put("thinking_level", thinkingLevel)
        }
        .put("escalation_requested", result.contains("[ESCALATE]"))
        .put("turns", turns)
        .put("elapsed_s", elapsedMs / 1000)
        .put("child_session_id", childSessionId)
        .put("job_id", jobId)
        .put("summary", summary)
        .also { if (deliveredAs != null) it.put("delivered_as", deliveredAs) }
        .also { if (!agentName.isNullOrEmpty()) it.put("agent", agentName) }
        .also { j -> modelOrigin?.let { j.put("model_origin", it.wire) } }
        .also { j -> modelGroupName?.let { if (it.isNotBlank()) j.put("model_group_name", it) } }
        .also { if (modelGroupUnavailable) it.put("model_group_unavailable", true) }
        .also { j ->
            val detail = errorText?.trim()?.takeIf { it.isNotEmpty() }
            if (detail != null) {
                j.put("error_detail", detail)
                errorKind(detail)?.let { j.put("error_kind", it.wire) }
            }
        }
        .also {
            if (wasResumed) {
                it.put("resumed", true)
                it.put("resumed_note", RESUMED_NOTE)
            }
        }
        .also { j -> termination?.putPayload(j) }
        .toString()

    const val RESUMED_NOTE =
        "This run was interrupted and resumed; its tool context was lost at that point, and the " +
            "elapsed/turn counts cover only the resumed part. Verify anything that depended on a " +
            "page or process staying open."

    fun JSONObject.putModelIdentity(
        modelLabel: String?,
        tierUsed: HelperModelTier?,
        origin: HelperModelOrigin?,
        modelGroupName: String? = null,
        modelGroupUnavailable: Boolean = false,
        resolvedEntryId: String? = null,
        resolvedProviderLabel: String? = null,
        resolvedProviderType: String? = null,
        resolvedModelId: String? = null,
        resolvedModelName: String? = null,
    ): JSONObject {
        fun putIfPresent(k: String, v: String?) { if (!v.isNullOrEmpty()) this.put(k, v) }
        putIfPresent("model_used", modelLabel)
        putIfPresent("tier_used", tierUsed?.wire)
        putIfPresent("model_origin", origin?.wire)
        putIfPresent("model_resolved_entry_id", resolvedEntryId)
        putIfPresent("model_resolved_provider", resolvedProviderLabel)
        putIfPresent("model_resolved_provider_type", resolvedProviderType)
        putIfPresent("model_resolved_id", resolvedModelId)
        putIfPresent("model_resolved_name", resolvedModelName)
        putIfPresent("model_group_name", modelGroupName)
        if (modelGroupUnavailable) put("model_group_unavailable", true)
        return this
    }

    fun backgroundStartJson(
        jobId: String, childSessionId: String, modelLabel: String, tierUsed: HelperModelTier,
        minutes: Int, converted: Boolean,
        resolution: HelperModelResolution? = null,
    ): String = JSONObject()
        .put("ok", true)
        .put("status", "running")
        .put("job_id", jobId)
        .put("child_session_id", childSessionId)
        .putModelIdentity(
            modelLabel = modelLabel,
            tierUsed = tierUsed,
            origin = resolution?.origin,
            modelGroupName = resolution?.modelGroupName,
            modelGroupUnavailable = resolution?.modelGroupUnavailable ?: false,
            resolvedEntryId = resolution?.resolvedEntryId,
            resolvedProviderLabel = resolution?.resolvedProviderLabel,
            resolvedProviderType = resolution?.resolvedProviderType,
            resolvedModelId = resolution?.resolvedModelId,
            resolvedModelName = resolution?.resolvedModelName,
        )
        .put("budget_minutes", minutes)
        .put("converted_from_wait", converted)
        .put(
            "note",
            if (converted) "The user sent a new message while you were waiting, so this delegation was moved to the background. Answer the user now; the agent's result will arrive as a NEW MESSAGE in this conversation (prefixed [Background task finished …]) when it is done. Use agent_status if you need its current state."
            else "The agent is running in the background. Its result will arrive as a NEW MESSAGE in this conversation (prefixed [Background task finished …]) when it is done — end this turn when you have nothing else to do. Use agent_status to check on it or cancel it; do not poll in a loop.",
        )
        .toString()

    fun isRunningPayload(content: String): Boolean =
        content.trimStart().startsWith("{") && runCatching { JSONObject(content).optString("status") == "running" }.getOrDefault(false)

    fun childSessionIdFrom(blockContent: String): String? =
        runCatching { JSONObject(blockContent).optString("child_session_id", "").ifEmpty { null } }.getOrNull()

    fun statusWord(state: AgentJobState): String = when (state) {
        AgentJobState.DONE -> "completed"
        else -> state.wire
    }


    fun runSummaryLine(toolNames: List<String>, turns: Int, inputTokens: Long, outputTokens: Long, cacheRead: Long): String {
        val counts = LinkedHashMap<String, Int>()
        for (n in toolNames) counts[n] = (counts[n] ?: 0) + 1
        val tools = if (counts.isEmpty()) "none yet" else counts.entries.joinToString(", ") { "${it.key}×${it.value}" }
        fun k(n: Long) = if (n >= 1000) "%.1fk".format(n / 1000.0) else n.toString()
        var tokens = "in ${k(inputTokens)} / out ${k(outputTokens)}"
        if (cacheRead > 0) tokens += " (cache read ${k(cacheRead)})"
        return "Summary: tools $tools · turns $turns · tokens $tokens"
    }

    data class ChildRunFacts(val toolNames: List<String>, val turns: Int, val input: Long, val output: Long, val cacheRead: Long, val lastText: String)

    fun childRunFacts(rows: List<com.openminis.app.data.db.MessageEntity>): ChildRunFacts {
        val tools = mutableListOf<String>()
        var turns = 0; var input = 0L; var output = 0L; var cache = 0L
        val assistantTexts = mutableListOf<String>()
        for (r in rows) {
            if (r.role != "assistant") continue
            turns++
            runCatching {
                val arr = JSONArray(r.partsJson)
                val sb = StringBuilder()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    when (o.optString("type")) {
                        "toolUse" -> o.optJSONObject("value")?.optString("name")?.takeIf { it.isNotEmpty() }?.let { tools += it }
                        "text" -> { val t = o.optString("value", ""); if (t.isNotBlank()) { if (sb.isNotEmpty()) sb.append('\n'); sb.append(t) } }
                    }
                }
                assistantTexts += sb.toString().trim()
            }
            r.tokenUsage?.let { tu ->
                runCatching {
                    val u = JSONObject(tu)
                    input += u.optLong("inputTokens", 0); output += u.optLong("outputTokens", 0); cache += u.optLong("cacheReadTokens", 0)
                }
            }
        }
        val last = assistantTexts.lastOrNull().orEmpty()
        val lastText = if (last.isNotEmpty()) last else assistantTexts.dropLast(1).lastOrNull { it.isNotEmpty() }
            ?.let { SubAgentTerminationRules.NO_FINAL_ANSWER_LABEL + "\n" + it } ?: ""
        return ChildRunFacts(tools, turns, input, output, cache, lastText)
    }

    fun formatClock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}
