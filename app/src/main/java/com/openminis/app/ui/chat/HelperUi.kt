package com.openminis.app.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.R
import com.openminis.app.agent.jobs.AgentCallback
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.HelperRunner
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.DisplayBitmapLimits
import kotlinx.coroutines.delay
import org.json.JSONObject
import com.openminis.app.ui.components.DecorativeSpinner


val HelperAccentLight = Color(0xFF734DED)
val HelperAccentDark = Color(0xFFA385FF)
@Composable
internal fun helperAccent(): Color = if (ChatColors.isDark) HelperAccentDark else HelperAccentLight
val HelperAccentStatic = Color(0xFF8B6CF6)

internal val HelperStatusGreen = Color(0xFF34C759)
internal val HelperStatusYellow = Color(0xFFFFCC00)
internal val HelperStatusRed = Color(0xFFFF3B30)

internal sealed class HelperPhase {
    object Starting : HelperPhase()
    data class Queued(val behind: Int) : HelperPhase()
    object NeverStarted : HelperPhase()
    data class Running(val tool: String?, val activity: String, val clock: String) : HelperPhase()
    data class Finished(
        val status: String, val tier: String?, val model: String?, val elapsedSeconds: Int?,
        val summary: String, val escalation: Boolean, val background: Boolean, val childSessionId: String?,
        val modelGroupUnavailable: Boolean = false,
        val modelOrigin: String? = null,
        val modelGroupName: String? = null,
        val errorKind: HelperRunner.ErrorKind? = null,
        val errorDetail: String? = null,
    ) : HelperPhase()
}

@Composable
private fun helperModelStrategyLabel(f: HelperPhase.Finished): String? {
    val group = f.modelGroupName
    return when (f.modelOrigin) {
        "inherited" -> stringResource(R.string.agent_strategy_inherited)
        "default_group" -> group?.let { stringResource(R.string.agent_strategy_default_group, it) }
        "sub_group" -> group?.let { stringResource(R.string.agent_strategy_sub_group, it) }
        "pinned" -> group?.let { stringResource(R.string.agent_strategy_pinned, it) }
        else -> null
    }
}

internal fun helperToolLabel(name: String): String {
    val spaced = name.replace('_', ' ').replace('-', ' ')
    return spaced.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.US) else it.toString() }
}

internal data class HelperBlockInfo(
    val title: String,
    val phase: HelperPhase,
    val childSessionId: String?,
    val result: String?,
    val runSummary: String?,
    val agent: String? = null,
) {
    val isRunning: Boolean get() = phase !is HelperPhase.Finished && phase !is HelperPhase.NeverStarted
    val finished: HelperPhase.Finished? get() = phase as? HelperPhase.Finished
}

private fun parseJson(content: String): JSONObject? {
    val c = content.trim()
    if (!c.startsWith("{")) return null
    return runCatching { JSONObject(c) }.getOrNull()
}

internal fun parseHelperBlock(block: AssistantBlock): HelperBlockInfo {
    val title = block.toolTitle.ifEmpty { block.toolName }
    val o = parseJson(block.content) ?: return HelperBlockInfo(title, HelperPhase.Starting, null, null, null)
    val childId = o.optString("child_session_id", "").ifEmpty { null }
    val summaryLine = o.optString("summary", "").ifEmpty { null }?.removePrefix("Summary: ")
    var status = o.optString("status", "")
    val liveRunning = block.toolStatus == ToolBlockStatus.RUNNING || block.toolStatus == ToolBlockStatus.PENDING || block.toolStatus == ToolBlockStatus.STREAMING
    if (status == "queued") {
        if (HelperRunner.isControlOnly(block.toolArgs.ifEmpty { null }, block.content.ifEmpty { null })) {
            return HelperBlockInfo(title, HelperPhase.Queued(o.optInt("queued_behind", 0)), childId, null, summaryLine)
        }
        return if (HelperRunner.isQueuedLive(block.id)) {
            HelperBlockInfo(title, HelperPhase.Queued(o.optInt("queued_behind", 0)), childId, null, summaryLine)
        } else {
            HelperBlockInfo(title, HelperPhase.NeverStarted, childId, null, summaryLine)
        }
    }
    if (status == "running") {
        if (liveRunning) {
            val tool = o.optString("tool", "").ifEmpty { null }
            val activity = o.optString("activity", "").ifEmpty { o.optString("tool_status", "") }
            val elapsed = o.optLong("elapsed_s", -1L)
            val clock = if (elapsed >= 0 && o.has("elapsed_s")) HelperRunner.formatClock(elapsed * 1000) else ""
            val last = o.optString("last_message", "").ifEmpty { null }
            return HelperBlockInfo(title, HelperPhase.Running(tool, activity, clock), childId, last, summaryLine)
        }
        status = "interrupted"
    }
    val result = o.optString("result", "").ifEmpty { o.optString("detail", "") }
    val firstLine = result.lineSequence().firstOrNull().orEmpty()
    val summary = if (firstLine.length > 140) firstLine.take(140) + "…" else firstLine
    return HelperBlockInfo(
        title,
        HelperPhase.Finished(
            status = status,
            tier = o.optString("tier_used", "").ifEmpty { null },
            model = o.optString("model_used", "").ifEmpty { null },
            modelOrigin = o.optString("model_origin", "").ifEmpty { null },
            modelGroupName = o.optString("model_group_name", "").ifEmpty { null },
            elapsedSeconds = if (o.has("elapsed_s")) o.optInt("elapsed_s") else null,
            summary = summary,
            escalation = o.optBoolean("escalation_requested", false),
            background = o.has("delivered_as"),
            childSessionId = childId,
            modelGroupUnavailable = o.optBoolean("model_group_unavailable", false),
            errorKind = HelperRunner.ErrorKind.fromWire(o.optString("error_kind", "").ifEmpty { null }),
            errorDetail = o.optString("error_detail", "").ifEmpty { null },
        ),
        childId, result.ifEmpty { null }, summaryLine,
        agent = o.optString("agent", "").trim().ifEmpty { null },
    )
}

@Composable
internal fun HelperControlRow(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Default.Groups,
            contentDescription = null,
            tint = ChatColors.tertiaryText,
            modifier = Modifier.size(11.dp),
        )
        Text(
            text,
            fontSize = 11.sp,
            color = ChatColors.tertiaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun helperControlPayload(block: AssistantBlock): String? {
    if (!HelperRunner.isControlOnly(block.toolArgs.ifEmpty { null }, block.content.ifEmpty { null })) return null
    val args = runCatching { JSONObject(block.toolArgs) }.getOrNull() ?: return null
    args.optString("message").trim().takeIf { it.isNotEmpty() }?.let { return it }
    args.optJSONArray("child_session_ids")?.let { arr ->
        val ids = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotEmpty) }
        if (ids.isNotEmpty()) return ids.joinToString("\n") { it.take(8) }
    }
    args.optString("child_session_id").takeIf { it.isNotEmpty() }?.let { return it.take(8) }
    args.optString("job_id").takeIf { it.isNotEmpty() }?.let { return it.take(8) }
    return null
}

@Composable
internal fun helperControlSummary(block: AssistantBlock): String? {
    val res = androidx.compose.ui.platform.LocalContext.current.resources
    return remember(block.toolArgs, block.content, res) {
        helperControlSummaryText(block) { id, count ->
            if (count == null) res.getString(id) else res.getString(id, count)
        }
    }
}

internal fun helperControlSummaryText(block: AssistantBlock, string: (id: Int, count: Int?) -> String): String? {
    val call = HelperRunner.classifyCall(block.toolArgs.ifEmpty { null }, block.content.ifEmpty { null })
    if (call == HelperRunner.SubAgentCall.DELEGATE) return null
    val count = HelperRunner.controlCount(call, block.content.ifEmpty { null })
    return when (call) {
        HelperRunner.SubAgentCall.STATUS ->
            if (count != null) string(R.string.agent_control_checked_n, count)
            else string(R.string.agent_control_checked, null)
        HelperRunner.SubAgentCall.RESUME ->
            if (count != null) string(R.string.agent_control_resumed_n, count)
            else string(R.string.agent_control_resumed, null)
        HelperRunner.SubAgentCall.STEER -> string(R.string.agent_control_steered, null)
        HelperRunner.SubAgentCall.CANCEL -> string(R.string.agent_control_stopped, null)
        else -> string(R.string.agent_control_generic, null)
    }
}

@Composable
internal fun helperStatusLabel(f: HelperPhase.Finished): String =
    f.errorKind?.let { stringResource(it.res) } ?: helperStatusLabel(f.status)

@Composable
internal fun helperStatusLabel(status: String): String = when (status) {
    "completed" -> stringResource(R.string.helper_status_completed)
    "cancelled" -> stringResource(R.string.helper_status_cancelled)
    "timeout" -> stringResource(R.string.helper_status_timeout)
    "failed" -> stringResource(R.string.helper_status_failed)
    "rejected" -> stringResource(R.string.helper_status_rejected)
    "interrupted" -> stringResource(R.string.helper_status_interrupted)
    HelperRunner.NO_DELIVERABLE -> stringResource(R.string.helper_status_no_deliverable)
    else -> stringResource(R.string.helper_status_unknown)
}

@Composable
internal fun helperStatusColor(status: String): Color = when (status) {
    "completed" -> HelperStatusGreen
    "cancelled", "timeout", "interrupted", HelperRunner.NO_DELIVERABLE -> HelperStatusYellow
    "failed", "rejected" -> HelperStatusRed
    else -> ChatColors.secondaryText
}

internal fun helperStatusGlyph(status: String): ImageVector = when (status) {
    "completed" -> Icons.Default.Check
    "failed", "rejected", "cancelled", "timeout", "interrupted",
    HelperRunner.NO_DELIVERABLE -> Icons.Default.PriorityHigh
    else -> Icons.AutoMirrored.Filled.HelpOutline
}

internal fun helperElapsedLabel(s: Int): String = if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"

internal fun helperToolIcon(name: String?): ImageVector = when (name) {
    null, "", "subagent_task", "delegate_task", "agent_status" -> Icons.Default.Groups
    else -> toolIconFor(name)
}

@Composable
private fun HelperSpinner(size: Int, stroke: Float = 2f) {
    DecorativeSpinner(color = helperAccent(), modifier = Modifier.size(size.dp), strokeWidth = stroke.dp)
}


@Composable
internal fun HelperToolBlock(
    block: AssistantBlock,
    onOpenDetail: (String) -> Unit,
    onStop: (() -> Unit)? = null,
    onStart: (() -> Unit)? = null,
    onResume: (() -> Boolean)? = null,
    modifier: Modifier = Modifier,
) {
    val info = remember(block.content, block.toolStatus, block.toolTitle) { parseHelperBlock(block) }
    var resuming by remember(block.id) { mutableStateOf(false) }
    val accent = helperAccent()
    val action: (@Composable () -> Unit)? = when {
        info.phase is HelperPhase.NeverStarted && onStart != null -> {
            {
                val startLabel = stringResource(R.string.helper_start)
                Box(
                    modifier = Modifier.size(24.dp).clip(CircleShape).background(helperAccent())
                        .clickable(onClickLabel = startLabel) { onStart() }
                        .semantics { contentDescription = startLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        info.finished?.status == "interrupted" && onResume != null -> {
            {
                val resumeLabel = stringResource(R.string.helper_resume)
                Box(
                    modifier = Modifier.size(24.dp).clip(CircleShape)
                        .background(helperAccent().copy(alpha = if (resuming) 0.25f else 1f))
                        .clickable(enabled = !resuming, onClickLabel = resumeLabel) { resuming = onResume() }
                        .semantics { contentDescription = resumeLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        info.isRunning && onStop != null -> {
            {
                val stopLabel = stringResource(R.string.helper_stop)
                Box(
                    modifier = Modifier.size(24.dp).clip(CircleShape).background(HelperStatusRed)
                        .clickable(onClickLabel = stopLabel) { onStop() }
                        .semantics { contentDescription = stopLabel },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(Color.White)) }
            }
        }
        else -> null
    }
    TaskCardFrame(
        accent = accent,
        onClick = { onOpenDetail(block.id) },
        onClickLabel = stringResource(R.string.agent_open_detail),
        modifier = modifier,
        icon = {
            val f = info.finished
            when {
                info.isRunning && info.phase is HelperPhase.Running -> HelperSpinner(13)
                f != null -> Icon(
                    helperStatusGlyph(f.status),
                    contentDescription = null, tint = helperStatusColor(f.status), modifier = Modifier.size(13.dp),
                )
                info.phase is HelperPhase.Queued -> Icon(
                    Icons.Default.Pause, contentDescription = null,
                    tint = accent, modifier = Modifier.size(13.dp),
                )
                else -> Icon(Icons.Default.Groups, contentDescription = null, tint = accent, modifier = Modifier.size(13.dp))
            }
        },
        action = action,
    ) {
        TaskCardTitleRow {
            AgentChip(info.agent ?: stringResource(R.string.helper_label))
            TaskCardTitle(info.title)
        }
        TaskCardStatusRow { HelperSecondLine(info) }
    }
}

@Composable
private fun AgentChip(text: String) {
    TaskCardChip(text, helperAccent())
}

@Composable
private fun TierChip(tier: String, tinted: Boolean = false) {
    val accent = helperAccent()
    Text(
        tier, fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
        color = if (tinted) accent else ChatColors.secondaryText,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (tinted) accent.copy(alpha = 0.15f) else ChatColors.secondaryBg).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun ThinkingLevelPill(level: ThinkingLevel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(ChatColors.secondaryBg)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Icon(
            Icons.Default.Psychology, contentDescription = null,
            tint = ChatColors.secondaryText, modifier = Modifier.size(9.dp),
        )
        Text(
            level.displayName, fontSize = 9.sp, lineHeight = 10.sp,
            fontWeight = FontWeight.Medium, color = ChatColors.secondaryText,
        )
    }
}

@Composable
private fun HelperSecondLine(info: HelperBlockInfo) {
    val accent = helperAccent()
    when (val p = info.phase) {
        is HelperPhase.Starting -> Text(stringResource(R.string.agent_status_starting), fontSize = 11.sp, lineHeight = 12.sp, color = ChatColors.secondaryText, maxLines = 1)
        is HelperPhase.Queued -> Text(
            stringResource(R.string.agent_status_queued), fontSize = 11.sp, lineHeight = 12.sp,
            color = accent, maxLines = 1,
        )
        is HelperPhase.NeverStarted -> Text(
            stringResource(R.string.agent_status_never_started), fontSize = 11.sp, lineHeight = 12.sp,
            color = ChatColors.tertiaryText, maxLines = 1,
        )
        is HelperPhase.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (p.tool != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(helperToolIcon(p.tool), contentDescription = null, tint = accent, modifier = Modifier.size(11.dp))
                    Text(helperToolLabel(p.tool), fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = accent, maxLines = 1)
                }
            }
            Text(
                p.activity.ifEmpty { stringResource(R.string.agent_working) }, fontSize = 11.sp, lineHeight = 12.sp, color = ChatColors.secondaryText,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            if (p.clock.isNotEmpty()) Text(p.clock, fontSize = 11.sp, lineHeight = 12.sp, fontFamily = FontFamily.Monospace, color = ChatColors.tertiaryText, maxLines = 1)
        }
        is HelperPhase.Finished -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(helperStatusLabel(p), fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = helperStatusColor(p.status), maxLines = 1)
            if (p.modelGroupUnavailable) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = stringResource(R.string.agent_pinned_group_unavailable),
                    tint = Color(0xFFFFCC00),
                    modifier = Modifier.size(11.dp),
                )
            }
            p.model?.let {
                Text(
                    it, fontSize = 11.sp, lineHeight = 12.sp, fontFamily = FontFamily.Monospace,
                    color = ChatColors.tertiaryText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            p.elapsedSeconds?.let { Text(helperElapsedLabel(it), fontSize = 11.sp, lineHeight = 12.sp, fontFamily = FontFamily.Monospace, color = ChatColors.tertiaryText, maxLines = 1) }
            if (p.escalation) Icon(Icons.Default.PriorityHigh, contentDescription = null, tint = Color(0xFFFF9500), modifier = Modifier.size(11.dp))
        }
    }
}


@Composable
internal fun HelperThumbContent(block: AssistantBlock) {
    val info = remember(block.content, block.toolStatus, block.toolTitle) { parseHelperBlock(block) }
    val accent = helperAccent()
    Column(modifier = Modifier.fillMaxSize().padding(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(Icons.Default.Groups, contentDescription = null, tint = accent, modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.helper_label), fontSize = 6.sp, lineHeight = 7.sp, fontWeight = FontWeight.Bold, color = accent, maxLines = 1)
        }
        Text(info.title, fontSize = 7.sp, lineHeight = 8.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        when (val p = info.phase) {
            is HelperPhase.Starting -> Text(stringResource(R.string.agent_status_starting), fontSize = 6.sp, lineHeight = 7.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
            is HelperPhase.Queued -> Text(stringResource(R.string.agent_status_queued), fontSize = 6.sp, lineHeight = 7.sp, color = accent, maxLines = 1)
            is HelperPhase.NeverStarted -> Text(stringResource(R.string.agent_status_never_started), fontSize = 6.sp, lineHeight = 7.sp, color = Color.White.copy(alpha = 0.55f), maxLines = 1)
            is HelperPhase.Running -> {
                if (p.tool != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Icon(helperToolIcon(p.tool), contentDescription = null, tint = accent, modifier = Modifier.size(7.dp))
                    Text(helperToolLabel(p.tool), fontSize = 6.sp, lineHeight = 7.sp, fontWeight = FontWeight.SemiBold, color = accent, maxLines = 1)
                }
                Text(
                    listOf(p.activity, p.clock).filter { it.isNotEmpty() }.joinToString(" · "),
                    fontSize = 6.sp, lineHeight = 7.sp, color = Color.White.copy(alpha = 0.7f),
                    maxLines = if (p.tool == null) 2 else 1, overflow = TextOverflow.Ellipsis,
                )
            }
            is HelperPhase.Finished -> {
                val warn = stringResource(R.string.agent_pinned_group_unavailable)
                val line = listOf(
                    helperStatusLabel(p),
                    p.model.orEmpty(),
                    p.elapsedSeconds?.let(::helperElapsedLabel).orEmpty(),
                ).filter { it.isNotEmpty() }.joinToString(" · ")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.semantics {
                        contentDescription = if (p.modelGroupUnavailable) "$line — $warn" else line
                    },
                ) {
                    if (p.modelGroupUnavailable) {
                        Icon(
                            Icons.Default.Warning, contentDescription = null,
                            tint = Color(0xFFFFCC00).copy(alpha = 0.9f),
                            modifier = Modifier.size(6.dp),
                        )
                    }
                    Text(
                        line, fontSize = 6.sp, lineHeight = 7.sp, fontWeight = FontWeight.SemiBold,
                        color = helperStatusColor(p.status), maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
            }
        }
    }
}


@Composable
internal fun AgentDetailCard(
    title: String,
    icon: ImageVector,
    iconTint: Color = ChatColors.secondaryText,
    trailing: (@Composable () -> Unit)? = null,
    body: (@Composable () -> Unit)? = null,
) {
    val isDark = ChatColors.isDark
    val barBg = if (isDark) Color(0xFF212121) else Color(0xFFEBEBEB)
    val cardBg = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF0F0F0)
    val stroke = if (isDark) Color(0xFF404040) else Color(0xFFD1D1D1)
    val shape = RoundedCornerShape(10.dp)
    Column(modifier = Modifier.fillMaxWidth().clip(shape).background(cardBg).border(0.5.dp, stroke, shape)) {
        Row(modifier = Modifier.fillMaxWidth().background(barBg).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = ChatColors.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        if (body != null) {
            HorizontalDivider(color = stroke)
            Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) { body() }
        }
    }
}

@Composable
private fun HelperControlDetailContent(block: AssistantBlock, summary: String) {
    val accent = helperAccent()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp).padding(top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AgentDetailCard(title = summary, icon = Icons.Default.Tune, iconTint = accent)
        helperControlPayload(block)?.let { payload ->
            AgentDetailCard(
                title = stringResource(R.string.agent_detail_sent_to_agent),
                icon = Icons.AutoMirrored.Filled.Send,
                iconTint = accent,
            ) {
                Text(payload, fontSize = 13.sp, color = ChatColors.primaryText)
            }
        }
        block.content.takeIf { it.isNotBlank() }?.let { result ->
            AgentDetailCard(
                title = stringResource(R.string.agent_detail_result),
                icon = Icons.Default.CheckCircle,
            ) {
                Text(
                    result, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    color = ChatColors.secondaryText,
                )
            }
        }
    }
}

@Composable
internal fun HelperDetailContent(block: AssistantBlock) {
    val controlSummary = helperControlSummary(block)
    if (controlSummary != null) {
        HelperControlDetailContent(block, controlSummary)
        return
    }
    val context = LocalContext.current
    val info = remember(block.content, block.toolStatus, block.toolTitle) { parseHelperBlock(block) }
    val accent = helperAccent()
    var latestImagePath by remember(info.childSessionId) { mutableStateOf<String?>(null) }
    var digest by remember(info.childSessionId) { mutableStateOf<SubAgentRunDigest?>(null) }
    var clock by remember(info.childSessionId) { mutableStateOf(SubAgentRunClock()) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(info.childSessionId, info.isRunning) {
        while (true) {
            nowMs = System.currentTimeMillis()
            clock = SubAgentRunClock.read(info.childSessionId, block)
            val cid = info.childSessionId ?: break
            val vm = runCatching {
                HeadlessChatRunner.viewModelFor(context, cid, ChatViewModelStore.PoolKind.CHILD)
            }.getOrNull()
            vm?.awaitSessionLoaded()
            val msgs = vm?.let { v ->
                val canonical = v.messages.value
                val stream = v.streamingById.value
                if (stream.isEmpty()) canonical else mergeStreamingOverlay(canonical, stream)
            }.orEmpty()
            latestImagePath = msgs.asReversed().asSequence()
                .filter { it.role == "assistant" }
                .flatMap { it.toolBlocks.asReversed().asSequence() }
                .mapNotNull { it.imageFilePath?.takeIf { p -> p.isNotEmpty() && java.io.File(p).exists() } }
                .firstOrNull()
            if (vm != null) {
                val stats = runCatching { vm.loadSessionTokenStats() }.getOrNull()
                val read = SubAgentRunDigest.from(
                    msgs,
                    turns = stats?.loopCount ?: 0,
                    tokensIn = stats?.input ?: 0L,
                    tokensOut = stats?.output ?: 0L,
                )
                if (read != digest) digest = read
            }
            if (!info.isRunning) break
            delay(3_000L)
        }
    }
    val taskArgs = remember(block.toolArgs) { SubAgentTaskArgs.parse(block.toolArgs.ifEmpty { null }) }
    val budgetMinutes = remember(block.content, taskArgs) {
        val recorded = runCatching { JSONObject(block.content) }.getOrNull()
            ?.takeIf { it.has("budget_minutes") }?.optInt("budget_minutes")
        SubAgentTimeBudget.minutes(
            recorded = recorded, requested = taskArgs?.maxMinutes, hasArgs = taskArgs != null,
            defaultMinutes = com.openminis.app.agent.jobs.HelperRunner.DEFAULT_MINUTES,
            maxMinutes = com.openminis.app.agent.jobs.HelperRunner.MAX_MINUTES,
        )
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val f = info.finished
        val ranAtThinking = remember(block.content) {
            runCatching { JSONObject(block.content) }.getOrNull()
                ?.optString("thinking_level")?.takeIf { it.isNotEmpty() }
                ?.let { ThinkingLevel.parseOrNull(it) }
                ?.takeIf { it.isEnabled }
        }
        val rows = buildList {
            if (f != null) {
                helperModelStrategyLabel(f)?.let {
                    add(Triple(stringResource(R.string.agent_detail_model_strategy), it, false))
                }
                f.model?.let { add(Triple(stringResource(R.string.agent_detail_model_actual), it, false)) }
                f.modelGroupName?.let {
                    add(Triple(stringResource(R.string.agent_detail_model_group), it, false))
                }
                f.tier?.let {
                    add(Triple(stringResource(R.string.agent_detail_model_tier), it, false))
                }
                if (f.escalation) add(Triple(stringResource(R.string.agent_detail_escalation), stringResource(R.string.agent_detail_escalation_value), false))
                if (f.background) add(Triple(stringResource(R.string.agent_detail_delivery), stringResource(R.string.agent_detail_delivery_value), false))
            }
            clock.startedAtMs?.let {
                add(Triple(stringResource(R.string.subagent_detail_started), subAgentClockLabel(it), true))
            }
            if (!info.isRunning) clock.finishedAtMs?.let {
                add(Triple(stringResource(R.string.subagent_detail_finished), subAgentClockLabel(it), true))
            }
            budgetMinutes?.let {
                add(Triple(stringResource(R.string.subagent_detail_time_budget), stringResource(R.string.subagent_detail_minutes, it), false))
            }
            if (taskArgs != null) {
                if (f?.background != true) {
                    add(Triple(
                        stringResource(R.string.agent_detail_delivery),
                        stringResource(if (taskArgs.wait == true) R.string.subagent_detail_delivery_wait else R.string.subagent_detail_delivery_background),
                        false,
                    ))
                }
                taskArgs.progressReport?.takeIf { it != "none" }?.let { level ->
                    add(Triple(
                        stringResource(R.string.agent_detail_interaction_progress),
                        stringResource(if (level == "frequent") R.string.subagent_detail_progress_frequent else R.string.subagent_detail_progress_moderate),
                        false,
                    ))
                }
            }
            val payload = remember(block.content) {
                runCatching { JSONObject(block.content) }.getOrNull()
            }
            payload?.optString("callback_kind")?.takeIf { it.isNotEmpty() }?.let { kind ->
                val label = when (kind) {
                    "progress" -> stringResource(R.string.agent_detail_interaction_progress)
                    "finished" -> stringResource(R.string.agent_detail_interaction_final)
                    else -> kind
                }
                add(Triple(stringResource(R.string.agent_detail_interaction), label, false))
            }
            payload?.optString("job_id")?.takeIf { it.isNotEmpty() }?.let { job ->
                add(Triple(stringResource(R.string.agent_detail_job), job.take(8), true))
            }
            val chipsCoverIt = digest?.isEmpty == false
            info.runSummary?.split(" · ")?.forEach { part ->
                val t = part.trim()
                when {
                    t.startsWith("tools ") -> if (!chipsCoverIt) add(Triple(stringResource(R.string.agent_detail_tools), t.removePrefix("tools "), true))
                    t.startsWith("turns ") -> if (!chipsCoverIt) add(Triple(stringResource(R.string.agent_detail_turns), t.removePrefix("turns "), true))
                    t.startsWith("tokens ") -> if (!chipsCoverIt) add(Triple(stringResource(R.string.agent_detail_tokens), t.removePrefix("tokens "), true))
                    t.isNotEmpty() -> add(Triple(stringResource(R.string.agent_detail_summary), t, false))
                }
            }
        }
        AgentDetailCard(
            title = info.title, icon = Icons.Default.Groups, iconTint = accent,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (val p = info.phase) {
                        is HelperPhase.Starting -> Text(stringResource(R.string.agent_status_starting), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = accent)
                        is HelperPhase.Queued -> Text(stringResource(R.string.agent_status_queued), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = accent)
                        is HelperPhase.NeverStarted -> Text(stringResource(R.string.agent_status_never_started), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ChatColors.tertiaryText)
                        is HelperPhase.Running -> {
                            HelperSpinner(11, 1.6f)
                            Text(stringResource(R.string.helper_status_running), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = accent)
                            if (p.clock.isNotEmpty()) Text(p.clock, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = ChatColors.tertiaryText)
                        }
                        is HelperPhase.Finished -> {
                            Text(helperStatusLabel(p), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = helperStatusColor(p.status))
                            p.tier?.let { TierChip(it, tinted = true) }
                            if (p.modelGroupUnavailable) {
                                Text(
                                    stringResource(R.string.agent_model_group_unavailable),
                                    fontSize = 10.sp,
                                    color = HelperStatusYellow,
                                    maxLines = 2,
                                )
                            }
                            p.elapsedSeconds?.let { Text(helperElapsedLabel(it), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = ChatColors.tertiaryText) }
                        }
                    }
                }
            },
            body = if (rows.isEmpty()) null else ({
                val modelRowLabel = context.getString(R.string.agent_detail_model_actual)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((label, value, mono) in rows) {
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(label, fontSize = 12.sp, color = ChatColors.secondaryText, maxLines = 1, modifier = Modifier.width(84.dp))
                            Text(value, fontSize = 12.sp, color = ChatColors.primaryText, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
                            if (ranAtThinking != null && label == modelRowLabel) {
                                ThinkingLevelPill(ranAtThinking)
                            }
                        }
                    }
                }
            }),
        )
        val remainingMs = clock.startedAtMs?.takeIf { info.isRunning }?.let { started ->
            budgetMinutes?.let { SubAgentTimeBudget.remainingMs(it, started, nowMs) }
        }
        digest?.let { d ->
            if (!d.isEmpty || remainingMs != null) {
                SubAgentStatChips(d, remainingMs, budgetMinutes = if (info.isRunning) budgetMinutes else null)
            }
        }
        taskArgs?.let { SubAgentTaskCard(it) }
        (info.phase as? HelperPhase.Running)?.let { p ->
            if (p.tool != null || p.activity.isNotEmpty()) {
                AgentDetailCard(title = stringResource(R.string.agent_detail_current_tool), icon = helperToolIcon(p.tool)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (p.tool != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(helperToolIcon(p.tool), contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
                            Text(helperToolLabel(p.tool), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = accent)
                        }
                        if (p.activity.isNotEmpty()) Text(p.activity, fontSize = 13.sp, color = ChatColors.primaryText)
                    }
                }
            }
        }
        digest?.takeIf { it.total > 0 }?.let { SubAgentToolTimelineCard(it) }
        latestImagePath?.let { path ->
            val bmp = remember(path) { DisplayBitmapLimits.decodeFileBounded(path) }
            if (bmp != null) {
                AgentDetailCard(title = stringResource(R.string.agent_detail_latest_screenshot), icon = toolIconFor("read_image")) {
                    Image(
                        bitmap = bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).border(0.5.dp, ChatColors.separator.copy(alpha = 0.5f), RoundedCornerShape(6.dp)),
                    )
                }
            }
        }
        (f?.errorDetail)?.let { detail ->
            AgentDetailCard(
                title = stringResource(R.string.agent_detail_error),
                icon = Icons.Default.Error,
                iconTint = HelperStatusRed,
            ) {
                Text(
                    detail, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    color = ChatColors.primaryText,
                )
            }
        }
        val isFinished = f != null
        if (!isFinished && info.result.isNullOrEmpty()) {
            digest?.latestText?.let { SubAgentLatestOutputCard(it) }
        }
        if (isFinished || !info.result.isNullOrEmpty()) {
            AgentDetailCard(
                title = stringResource(if (isFinished) R.string.agent_detail_result else R.string.agent_detail_last_message),
                icon = if (isFinished) Icons.Default.CheckCircle else Icons.Default.Groups,
            ) {
                val r = info.result
                if (!r.isNullOrEmpty()) StreamingMarkdownText(content = r, isStreaming = false)
                else Text(stringResource(R.string.agent_detail_no_deliverable), fontSize = 13.sp, color = ChatColors.secondaryText)
            }
        }
        val cid = info.childSessionId
        if (info.isRunning && cid != null) {
            SubAgentActionsCard(childSessionId = cid, canSteer = clock.jobIsLive)
        }
    }
}


internal fun AgentCallback.syntheticBlock(): AssistantBlock {
    val finished = kind == AgentCallback.Kind.FINISHED
    val status = if (!finished) "running"
    else HelperRunner.resolvedStatus(if (this.status == "done") "completed" else this.status, body)
    val o = JSONObject().put("status", status).put("job_id", jobId)
        .put("callback_kind", kind.wire)
    childSessionId?.let { o.put("child_session_id", it) }
    tier?.let { o.put("tier_used", it) }
    elapsedSeconds?.let { o.put("elapsed_s", it) }
    tool?.let { o.put("tool", it) }
    activity?.let { o.put("activity", it) }
    summary?.let { o.put("summary", "Summary: $it") }
    o.put(if (finished) "result" else "last_message", body)
    if (finished) o.put("delivered_as", "new turn in this conversation")
    return AssistantBlock(
        id = "callback_$jobId", kind = "tool_use", content = o.toString(), toolName = HelperRunner.TOOL_NAME, toolTitle = title,
        toolStatus = if (!finished) {
            OrphanedRunningToolGate.resolve(
                ToolBlockStatus.RUNNING,
                isLiveRun = false,
                jobIsAlive = com.openminis.app.agent.jobs.AgentJobRegistry.job(jobId)?.isActive == true,
            )
        } else when (this.status) {
            "done" -> ToolBlockStatus.SUCCESS
            "cancelled" -> ToolBlockStatus.CANCELLED
            "timeout" -> ToolBlockStatus.TIMEOUT
            else -> ToolBlockStatus.FAILED
        },
    )
}

@Composable
internal fun AgentCallbackCard(callback: AgentCallback, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val helperAccent = helperAccent()
    val finished = callback.kind == AgentCallback.Kind.FINISHED
    val accent = when (callback.status) {
        "running" -> helperAccent
        "done" -> HelperStatusGreen
        "cancelled", "timeout" -> Color(0xFFFFCC00)
        else -> HelperStatusRed
    }
    val icon = when {
        !finished -> Icons.Default.Sync
        callback.status == "done" -> Icons.Default.CheckCircle
        callback.status == "cancelled" -> Icons.Default.Cancel
        callback.status == "timeout" -> Icons.Default.Schedule
        else -> Icons.Default.Warning
    }
    val kindLabel = stringResource(if (finished) R.string.agent_callback_result else R.string.agent_callback_progress)
    val headline = if (callback.title.isEmpty()) kindLabel else "$kindLabel · ${callback.title}"
    val turnLabel = callback.turn?.takeIf { it > 0 }?.let { stringResource(R.string.agent_callback_turn, it) }
    val subline = buildList {
        add(com.openminis.app.agent.jobs.AgentCallbackLabels.localizedStatus(callback.status))
        callback.elapsed?.takeIf { it.isNotEmpty() }?.let { add(it) }
        if (!finished) {
            callback.tool?.takeIf { it.isNotEmpty() }?.let { add(it) }
            turnLabel?.let { add(it) }
        } else {
            callback.summary?.takeIf { it.isNotEmpty() }?.let { add(it) }
        }
    }.joinToString(" · ")
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(56.dp)
            .clip(shape)
            .background(ChatColors.secondaryBg)
            .border(1.dp, accent.copy(alpha = 0.25f), shape)
            .clickable(onClickLabel = stringResource(R.string.agent_open_detail), onClick = onTap)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(modifier = Modifier.size(30.dp).clip(CircleShape).background(accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                com.openminis.app.ui.chat.search.rememberChatSearchHighlighted(
                    headline, com.openminis.app.ui.chat.search.ChatSearchUnits.TITLE, offset = headline.length - callback.title.length,
                ),
                fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, color = ChatColors.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(subline, fontSize = 12.sp, lineHeight = 15.sp, color = ChatColors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = ChatColors.tertiaryText, modifier = Modifier.size(16.dp))
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentTranscriptScreen(sessionId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm = remember(sessionId) {
        HeadlessChatRunner.viewModelFor(context, sessionId, ChatViewModelStore.PoolKind.CHILD)
    }
    androidx.compose.runtime.DisposableEffect(sessionId) {
        val release = ChatViewModelStore.holdWhileViewed(sessionId)
        onDispose { release() }
    }
    val isStreaming by vm.isStreaming.collectAsState()
    val jobs by AgentJobRegistry.jobs.collectAsState()
    val job = jobs.values.firstOrNull { it.runSessionId == sessionId }
    val sessionTitle by vm.sessionTitle.collectAsState()
    val title = remember(sessionTitle) { HelperRunner.stripChildSessionTitlePrefix(context, sessionTitle) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isStreaming) { while (isStreaming) { delay(1000); now = System.currentTimeMillis() } }
    val accent = helperAccent()
    val startedAt = job?.startedAtMs
    var callbackDetailBlock by remember { mutableStateOf<AssistantBlock?>(null) }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalMarkdownSessionId provides (vm.helperConfig?.parentSessionId ?: sessionId),
        LocalMarkdownBlankTapHandler provides remember(vm) {
            { msgId: String -> vm.toggleUsageCapsule(msgId.substringBefore('#')) }
        },
        LocalUsageTapBandRegistry provides remember(vm) { UsageTapBandRegistry() },
    ) {
    Scaffold(
        containerColor = ChatColors.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ChatColors.background),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.helper_close), tint = ChatColors.primaryText) }
                },
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (isStreaming) HelperSpinner(12, 1.8f)
                            else Icon(Icons.Default.CheckCircle, contentDescription = null, tint = HelperStatusGreen, modifier = Modifier.size(14.dp))
                            Text(
                                HelperRunner.childSessionTitle(context, title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                color = ChatColors.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            job?.tierUsed?.let { TierChip(it) }
                            if (isStreaming && startedAt != null) Text(HelperRunner.formatClock(now - startedAt), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = ChatColors.secondaryText)
                        }
                    }
                },
                actions = {
                    if (isStreaming) {
                        IconButton(onClick = {
                            job?.let { AgentJobRegistry.cancelSiblings(sessionId, "user-stop-transcript") }
                                ?: vm.cancelStream()
                        }) {
                            Icon(Icons.Outlined.StopCircle, contentDescription = stringResource(R.string.helper_stop), tint = HelperStatusRed)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
            HelperTranscript(
                vm,
                accent,
                onOpenDetail = { vm.openToolDetail(it) },
                onOpenCallback = { callbackDetailBlock = it.syntheticBlock() },
            )
        }

        val selectedId by vm.selectedToolDetailId.collectAsState()
        val transcriptMessages by vm.messages.collectAsState()
        val transcriptStreaming by vm.streamingById.collectAsState()
        val transcriptToolBlocks = remember(transcriptMessages, transcriptStreaming) {
            buildFlatChatItems(
                if (transcriptStreaming.isEmpty()) transcriptMessages
                else mergeStreamingOverlay(transcriptMessages, transcriptStreaming)
            ).filterIsInstance<FlatChatItem.AssistantToolUse>().map { it.block }
        }
        LaunchedEffect(selectedId, transcriptToolBlocks) {
            val id = selectedId ?: return@LaunchedEffect
            if (transcriptToolBlocks.none { it.id == id }) vm.closeToolDetail()
        }
        selectedId?.let { id ->
            val idx = transcriptToolBlocks.indexOfFirst { it.id == id }
            if (idx >= 0) {
                ToolDetailSheet(
                    toolBlocks = transcriptToolBlocks,
                    initialIndex = idx,
                    onDismiss = { vm.closeToolDetail() },
                )
            }
        }
        callbackDetailBlock?.let { block ->
            ToolDetailSheet(
                toolBlocks = listOf(block),
                initialIndex = 0,
                onDismiss = { callbackDetailBlock = null },
            )
        }
    }
    }
}

@Composable
internal fun HelperTranscript(
    vm: ChatViewModel,
    accent: Color,
    onOpenDetail: (String) -> Unit = {},
    onOpenCallback: (AgentCallback) -> Unit = {},
) {
    val messages by vm.messages.collectAsState()
    val streaming by vm.streamingById.collectAsState()
    val revealedUsage by vm.revealedUsageIds.collectAsState()
    val liveMessageTracker = remember(vm) { LiveMessageTracker() }
    val items = remember(messages, streaming) {
        val merged = if (streaming.isEmpty()) messages else mergeStreamingOverlay(messages, streaming)
        buildFlatChatItems(merged, liveMessageIds = liveMessageTracker.update(merged, streaming.keys))
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        items(items.asReversed(), key = { it.key }, contentType = { it.contentType }) { item ->
            when (item) {
                is FlatChatItem.UserBubble -> UserMessageBubble(message = item.message, precededByUser = item.precededByUser, onRetry = null, onEdit = null, onDeleteFromHere = null)
                is FlatChatItem.AgentCallbackCard -> AgentCallbackCard(item.callback, onTap = { onOpenCallback(item.callback) })
                is FlatChatItem.ScheduledTaskCardItem -> ScheduledTaskCard(marker = item.marker)
                is FlatChatItem.AssistantHeader -> AssistantHeader()
                is FlatChatItem.AssistantText -> StreamingMarkdownText(content = item.block.content, isStreaming = item.isStreaming)
                is FlatChatItem.AssistantMarkdownBlock -> UsageTapBandRowScope(
                    messageId = item.messageId,
                    inFinalTextBlock = item.inFinalTextBlock,
                    isLastRow = item.isLastBlockOfMessage,
                ) {
                    StreamingMarkdownText(content = item.rawText, isStreaming = item.isStreaming)
                }
                is FlatChatItem.AssistantThinking -> ThinkingBlock(
                    item.block,
                    isStreaming = item.isLastBlockOverall && item.messageIsStreaming,
                    isLast = item.isLast,
                )
                is FlatChatItem.AssistantToolUse ->
                    if (HelperRunner.isSubAgentToolName(item.block.toolName)) HelperToolBlock(item.block, onOpenDetail = { onOpenDetail(item.block.id) })
                    else ToolCallPill(item.block, allToolBlocks = item.allToolBlocks, onOpenDetail = onOpenDetail)
                is FlatChatItem.AssistantToolGroup -> Unit
                is FlatChatItem.AssistantInfo -> FallbackInfoBlock(item.block)
                is FlatChatItem.AssistantTyping -> TypingIndicator()
                is FlatChatItem.AssistantError -> InlineErrorBanner(item.error)
                is FlatChatItem.AssistantUsage -> {
                    val msgId = item.messageId.substringBefore('#')
                    val shown = msgId in revealedUsage
                    UsageCapsuleRow(
                        shown = shown,
                        onToggle = { vm.toggleUsageCapsule(msgId) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 12.dp),
                    ) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = shown,
                            enter = androidx.compose.animation.fadeIn(),
                            exit = androidx.compose.animation.fadeOut(),
                        ) {
                            UsageCapsule(item.usage, item.completedAt)
                        }
                    }
                }
                is FlatChatItem.AssistantLegacyContent -> UsageTapBandRowScope(messageId = item.messageId, inFinalTextBlock = true, isLastRow = true) {
                    StreamingMarkdownText(content = item.content, isStreaming = item.isStreaming)
                }
            }
        }
    }
}
