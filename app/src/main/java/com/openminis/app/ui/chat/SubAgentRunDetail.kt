package com.openminis.app.ui.chat

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.AgentJobState
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.DecorativeSpinner
import com.openminis.app.ui.theme.ChatColors
import org.json.JSONObject
import java.util.Locale



internal data class SubAgentRunDigest(
    val calls: List<Call> = emptyList(),
    val turns: Int = 0,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val latestText: String? = null,
) {
    enum class CallState { RUNNING, OK, FAILED, CANCELLED }

    data class Call(
        val id: String,
        val tool: String,
        val title: String,
        val state: CallState,
        val durationMs: Long?,
    )

    val total: Int get() = calls.size
    val okCount: Int get() = calls.count { it.state == CallState.OK }
    val failedCount: Int get() = calls.count { it.state == CallState.FAILED }
    val hasTokens: Boolean get() = tokensIn > 0 || tokensOut > 0
    val isEmpty: Boolean get() = calls.isEmpty() && turns == 0 && !hasTokens && latestText == null

    val byTool: List<Pair<String, Int>>
        get() {
            val counts = LinkedHashMap<String, Int>()
            for (c in calls) counts[c.tool] = (counts[c.tool] ?: 0) + 1
            return counts.entries.map { it.key to it.value }.sortedByDescending { it.second }
        }

    fun recent(limit: Int): List<Call> = calls.takeLast(maxOf(0, limit)).asReversed()

    companion object {
        fun compact(n: Long): String = when {
            n < 1_000 -> "$n"
            n < 10_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
            n < 1_000_000 -> "${Math.round(n / 1_000.0)}k"
            else -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        }

        fun durationLabel(ms: Long): String {
            if (ms < 0) return "0s"
            if (ms < 10_000) return String.format(Locale.US, "%.1fs", ms / 1000.0)
            val s = Math.round(ms / 1000.0)
            if (s < 60) return "${s}s"
            if (s < 3600) return "${s / 60}m ${String.format(Locale.US, "%02d", s % 60)}s"
            return "${s / 3600}h ${(s % 3600) / 60}m"
        }

        fun from(
            messages: List<ChatMessage>,
            turns: Int = 0,
            tokensIn: Long = 0,
            tokensOut: Long = 0,
        ): SubAgentRunDigest {
            val calls = mutableListOf<Call>()
            var latest: String? = null
            for (m in messages) {
                if (m.role != "assistant") continue
                var sawTextBlock = false
                for (b in m.toolBlocks) {
                    if (b.kind == "text") {
                        sawTextBlock = true
                        b.content.trim().takeIf { it.isNotEmpty() }?.let { latest = it }
                        continue
                    }
                    if (b.kind != "tool_use") continue
                    val status = b.toolStatus ?: continue
                    val state = when (status) {
                        ToolBlockStatus.STREAMING, ToolBlockStatus.PENDING, ToolBlockStatus.RUNNING -> CallState.RUNNING
                        ToolBlockStatus.SUCCESS -> CallState.OK
                        ToolBlockStatus.FAILED, ToolBlockStatus.TIMEOUT -> CallState.FAILED
                        ToolBlockStatus.CANCELLED -> CallState.CANCELLED
                    }
                    calls += Call(
                        id = b.id,
                        tool = b.toolName,
                        title = callTitle(b),
                        state = state,
                        durationMs = if (state == CallState.RUNNING || b.durationMs <= 0) null else b.durationMs,
                    )
                }
                if (!sawTextBlock) m.content.trim().takeIf { it.isNotEmpty() }?.let { latest = it }
            }
            return SubAgentRunDigest(calls, turns, tokensIn, tokensOut, latest)
        }

        fun callTitle(b: AssistantBlock): String {
            b.toolTitle.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            val o = runCatching { JSONObject(b.toolArgs) }.getOrNull() ?: return ""
            for (key in listOf("command", "path", "file_path", "url", "query", "pattern")) {
                val v = o.optString(key, "").lineSequence().firstOrNull()?.trim().orEmpty()
                if (v.isNotEmpty()) return v
            }
            return ""
        }
    }
}

internal data class SubAgentTaskArgs(
    val task: String,
    val context: String = "",
    val maxMinutes: Int? = null,
    val wait: Boolean? = null,
    val progressReport: String? = null,
    val agent: String? = null,
) {
    companion object {
        fun parse(json: String?): SubAgentTaskArgs? {
            if (json.isNullOrBlank()) return null
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            fun text(key: String) = o.optString(key, "").trim()
            val task = text("task")
            if (task.isEmpty()) return null
            val progress = text("progress_report").lowercase(Locale.US)
            return SubAgentTaskArgs(
                task = task,
                context = text("context"),
                maxMinutes = if (o.has("max_minutes")) o.optDouble("max_minutes").takeIf { !it.isNaN() }?.toInt() else null,
                wait = if (o.has("wait")) o.optBoolean("wait") else null,
                progressReport = progress.takeIf { it in setOf("none", "moderate", "frequent") },
                agent = text("agent").ifEmpty { null },
            )
        }
    }
}

internal object SubAgentTimeBudget {
    fun minutes(recorded: Int?, requested: Int?, hasArgs: Boolean, defaultMinutes: Int, maxMinutes: Int): Int? {
        if (recorded != null && recorded > 0) return recorded
        if (!hasArgs) return null
        return (requested ?: defaultMinutes).coerceIn(1, maxMinutes)
    }

    fun remainingMs(budgetMinutes: Int, startedAtMs: Long, nowMs: Long): Long =
        budgetMinutes * 60_000L - (nowMs - startedAtMs)

    fun remainingLabel(ms: Long): Pair<Int, Int>? {
        if (ms < 1_000) return null
        val s = (ms / 1000).toInt()
        return (s / 60) to (s % 60)
    }
}

internal object SubAgentTextPreview {
    const val COLLAPSED_LINES = 4

    fun isLong(text: String, lines: Int = COLLAPSED_LINES, characters: Int = 220): Boolean =
        text.length > characters || text.lines().size > lines
}

internal fun subAgentClockLabel(epochMs: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date(epochMs))


internal data class SubAgentRunClock(
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    val jobIsLive: Boolean = false,
) {
    companion object {
        fun read(childSessionId: String?, block: AssistantBlock): SubAgentRunClock {
            val job = childSessionId?.let { AgentJobRegistry.jobForSession(it) }
            return SubAgentRunClock(
                startedAtMs = job?.startedAtMs ?: block.startTimeMs.takeIf { it > 0 },
                finishedAtMs = job?.finishedAtMs,
                jobIsLive = job != null && (job.state == AgentJobState.RUNNING || job.state == AgentJobState.PENDING),
            )
        }
    }
}

internal object SubAgentUserSteer {
    fun send(text: String, childSessionId: String): Boolean {
        val message = text.trim()
        if (message.isEmpty()) return false
        val job = AgentJobRegistry.jobForSession(childSessionId) ?: return false
        if (job.state != AgentJobState.RUNNING && job.state != AgentJobState.PENDING) return false
        val delivered = AgentJobRegistry.steer(job.id, "(sent directly by the user) $message")
        AppLogger.info(
            "SubAgentDetail",
            "[subagent_task] user steer ${if (delivered) "queued" else "refused"} job=${job.id.take(8)} " +
                "child=${childSessionId.take(8)} chars=${message.length}",
        )
        return delivered
    }
}

internal fun stopSubAgentByUser(context: Context, childSessionId: String, reason: String) {
    val job = AgentJobRegistry.jobForSession(childSessionId)
    if (job != null && job.isActive) {
        AgentJobRegistry.cancelSiblings(childSessionId, reason)
    } else {
        runCatching {
            HeadlessChatRunner.viewModelFor(context, childSessionId, ChatViewModelStore.PoolKind.CHILD).cancelStream()
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubAgentStatChips(digest: SubAgentRunDigest, remainingMs: Long?, budgetMinutes: Int?) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (digest.total > 0) {
            StatChip(Icons.Default.Build, pluralStringResource(R.plurals.subagent_detail_tool_calls, digest.total, digest.total))
        }
        if (digest.okCount > 0) {
            StatChip(
                Icons.Default.CheckCircle, "${digest.okCount}", tint = HelperStatusGreen,
                label = stringResource(R.string.subagent_detail_succeeded, digest.okCount),
            )
        }
        if (digest.failedCount > 0) {
            StatChip(
                Icons.Default.Cancel, "${digest.failedCount}", tint = HelperStatusRed,
                label = stringResource(R.string.subagent_detail_failed, digest.failedCount),
            )
        }
        if (digest.turns > 0) {
            StatChip(Icons.Default.Loop, pluralStringResource(R.plurals.subagent_detail_turns, digest.turns, digest.turns))
        }
        if (digest.hasTokens) {
            val tin = SubAgentRunDigest.compact(digest.tokensIn)
            val tout = SubAgentRunDigest.compact(digest.tokensOut)
            StatChip(Icons.Default.SwapVert, "$tin / $tout", label = stringResource(R.string.subagent_detail_tokens, tin, tout))
        }
        val left = remainingMs?.let { SubAgentTimeBudget.remainingLabel(it) }
        if (left != null) {
            StatChip(
                Icons.Default.Timer,
                if (left.first > 0) stringResource(R.string.subagent_detail_minutes_left, left.first)
                else stringResource(R.string.subagent_detail_seconds_left, left.second),
                tint = if (remainingMs < 60_000L) HelperStatusYellow else null,
            )
        } else if (budgetMinutes != null) {
            StatChip(Icons.Default.Timer, stringResource(R.string.subagent_detail_minutes_budget, budgetMinutes))
        }
    }
}

private fun chipTextStyle(size: Int, mono: Boolean) = TextStyle(
    fontSize = size.sp,
    lineHeight = (size + 2).sp,
    fontWeight = FontWeight.Medium,
    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both),
)

private val STAT_CHIP_HEIGHT = 20.dp

private val TOOL_CHIP_HEIGHT = 17.dp

@Composable
private fun StatChip(icon: ImageVector, text: String, tint: Color? = null, label: String? = null) {
    val bg = if (ChatColors.isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .height(STAT_CHIP_HEIGHT)
            .padding(horizontal = 7.dp)
            .clearAndSetSemantics { contentDescription = label ?: text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint ?: ChatColors.secondaryText, modifier = Modifier.size(10.dp))
        Text(text, style = chipTextStyle(11, mono = true), color = ChatColors.primaryText, maxLines = 1)
    }
}


@Composable
internal fun SubAgentCollapsibleText(text: String, monospaced: Boolean = false) {
    val long = remember(text) { SubAgentTextPreview.isLong(text) }
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectionContainer {
            Text(
                text, fontSize = 13.sp, color = ChatColors.primaryText,
                fontFamily = if (monospaced) FontFamily.Monospace else FontFamily.Default,
                maxLines = if (long && !expanded) SubAgentTextPreview.COLLAPSED_LINES else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (long) {
            Text(
                stringResource(if (expanded) R.string.subagent_detail_show_less else R.string.subagent_detail_show_more),
                fontSize = 12.sp, fontWeight = FontWeight.Medium, color = helperAccent(),
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }
    }
}


@Composable
internal fun SubAgentTaskCard(args: SubAgentTaskArgs) {
    AgentDetailCard(title = stringResource(R.string.subagent_detail_task), icon = Icons.AutoMirrored.Filled.Notes) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SubAgentCollapsibleText(args.task)
            if (args.context.isNotEmpty()) {
                HorizontalDivider(color = ChatColors.separator.copy(alpha = 0.5f))
                Text(
                    stringResource(R.string.subagent_detail_context), fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, color = ChatColors.secondaryText,
                )
                SubAgentCollapsibleText(args.context)
            }
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubAgentToolTimelineCard(digest: SubAgentRunDigest, limit: Int = 8) {
    AgentDetailCard(
        title = stringResource(R.string.subagent_detail_recent_tool_calls),
        icon = Icons.AutoMirrored.Filled.List,
        trailing = if (digest.total > limit) ({
            Text(
                stringResource(R.string.subagent_detail_latest_of, limit, digest.total),
                fontSize = 10.sp, color = ChatColors.tertiaryText,
            )
        }) else null,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val split = digest.byTool
            if (split.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    for ((tool, count) in split) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .border(0.5.dp, ChatColors.separator, RoundedCornerShape(50))
                                .height(TOOL_CHIP_HEIGHT)
                                .padding(horizontal = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(helperToolIcon(tool), contentDescription = null, tint = ChatColors.secondaryText, modifier = Modifier.size(9.dp))
                            Text("${helperToolLabel(tool)} ×$count", style = chipTextStyle(10, mono = false), color = ChatColors.secondaryText, maxLines = 1)
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (call in digest.recent(limit)) TimelineRow(call)
            }
        }
    }
}

@Composable
private fun TimelineRow(call: SubAgentRunDigest.Call) {
    val accent = helperAccent()
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.size(width = 14.dp, height = 16.dp), contentAlignment = Alignment.Center) {
            when (call.state) {
                SubAgentRunDigest.CallState.RUNNING ->
                    DecorativeSpinner(color = accent, modifier = Modifier.size(11.dp), strokeWidth = 1.6.dp)
                SubAgentRunDigest.CallState.OK ->
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = HelperStatusGreen, modifier = Modifier.size(12.dp))
                SubAgentRunDigest.CallState.FAILED ->
                    Icon(Icons.Default.Cancel, contentDescription = null, tint = HelperStatusRed, modifier = Modifier.size(12.dp))
                SubAgentRunDigest.CallState.CANCELLED ->
                    Icon(Icons.Default.RemoveCircle, contentDescription = null, tint = ChatColors.tertiaryText, modifier = Modifier.size(12.dp))
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val nameColor = if (call.state == SubAgentRunDigest.CallState.RUNNING) accent else ChatColors.primaryText
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(helperToolIcon(call.tool), contentDescription = null, tint = nameColor, modifier = Modifier.size(11.dp))
                Text(helperToolLabel(call.tool), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = nameColor, maxLines = 1)
            }
            if (call.title.isNotEmpty()) {
                Text(call.title, fontSize = 12.sp, color = ChatColors.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        call.durationMs?.let {
            Text(SubAgentRunDigest.durationLabel(it), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = ChatColors.tertiaryText)
        }
    }
}


@Composable
internal fun SubAgentLatestOutputCard(text: String) {
    AgentDetailCard(title = stringResource(R.string.subagent_detail_latest_output), icon = Icons.Default.ChatBubbleOutline) {
        SubAgentCollapsibleText(text)
    }
}


@Composable
internal fun SubAgentActionsCard(childSessionId: String, canSteer: Boolean) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val accent = helperAccent()
    var draft by rememberSaveable(childSessionId) { mutableStateOf("") }
    var sentCount by remember(childSessionId) { mutableIntStateOf(0) }
    var confirmStop by remember { mutableStateOf(false) }
    val isBlank = draft.isBlank()

    AgentDetailCard(title = stringResource(R.string.subagent_detail_actions), icon = Icons.Default.Tune) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (canSteer) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text(stringResource(R.string.subagent_detail_steer_hint), fontSize = 13.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = ChatColors.primaryText),
                        minLines = 1, maxLines = 4,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accent,
                            unfocusedBorderColor = ChatColors.separator,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    val sendLabel = stringResource(R.string.subagent_detail_send)
                    IconButton(
                        enabled = !isBlank,
                        onClick = {
                            if (SubAgentUserSteer.send(draft, childSessionId)) {
                                draft = ""
                                sentCount++
                                focus.clearFocus()
                            }
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send, contentDescription = sendLabel,
                            tint = if (isBlank) ChatColors.tertiaryText else accent,
                        )
                    }
                }
                Text(
                    stringResource(if (sentCount > 0) R.string.subagent_detail_steer_sent else R.string.subagent_detail_steer_note),
                    fontSize = 11.sp, color = ChatColors.secondaryText,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(HelperStatusRed.copy(alpha = 0.12f))
                    .clickable { confirmStop = true }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.StopCircle, contentDescription = null, tint = HelperStatusRed, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.helper_stop), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = HelperStatusRed)
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.subagent_detail_stop_confirm_title)) },
            text = { Text(stringResource(R.string.subagent_detail_stop_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    stopSubAgentByUser(context, childSessionId, "user-stop-detail")
                }) { Text(stringResource(R.string.helper_stop), color = HelperStatusRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}
