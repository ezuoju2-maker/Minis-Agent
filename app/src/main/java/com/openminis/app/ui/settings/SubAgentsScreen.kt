package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.SubAgentLimits
import com.openminis.app.data.model.SubAgentRoster
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.tools.AgentToolSwitch

internal const val DRAFT_AGENT_ID = "__new__"

@Composable
fun SubAgentsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val repo = remember { (context.applicationContext as MinisApp).providerRepository }
    var generation by remember { mutableStateOf(0) }
    val roster = remember(generation) { repo.subAgents }

    var agentsEnabled by remember { mutableStateOf(AgentToolSwitch.AGENTS.isEnabled(context)) }

    SettingsScaffold(title = stringResource(R.string.sub_agents_title), onBack = onBack) {
        SettingsSection(footer = stringResource(R.string.agent_settings_allow_footer)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        agentsEnabled = !agentsEnabled
                        AgentToolSwitch.AGENTS.setEnabled(context, agentsEnabled)
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.sub_agents_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        SubAgentDefinition.TOOL_NAME,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                SettingsSwitch(
                    checked = agentsEnabled,
                    onCheckedChange = {
                        agentsEnabled = it
                        AgentToolSwitch.AGENTS.setEnabled(context, it)
                    },
                )
            }
        }
        Column(Modifier.alpha(if (agentsEnabled) 1f else 0.5f)) {
        SettingsSection(
            header = stringResource(R.string.sub_agents_list_header),
            footer = stringResource(R.string.sub_agents_list_footer),
        ) {
            roster.forEachIndexed { i, def ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        SubAgentRosterRow(
                            name = def.displayName(context),
                            isBuiltIn = def.isBuiltIn,
                            description = def.displayDescription(context),
                            modelLabel = def.modelGroupId?.let { repo.group(it)?.name }
                                ?: stringResource(R.string.sub_agent_model_auto),
                            onClick = { onOpen(def.id) },
                            showDivider = i < roster.lastIndex,
                        )
                    }
                    if (roster.size > 1 && !def.isBuiltIn) {
                        IconButton(
                            onClick = { move(repo, roster, i, i - 1); generation++ },
                            enabled = i > 1,
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = stringResource(R.string.sub_agents_move_up),
                            )
                        }
                        IconButton(
                            onClick = { move(repo, roster, i, i + 1); generation++ },
                            enabled = i < roster.lastIndex,
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.sub_agents_move_down),
                            )
                        }
                    }
                }
            }
        }
        if (roster.size < SubAgentLimits.MAX_COUNT) {
            SettingsSection {
                SettingsValueRow(
                    title = stringResource(R.string.sub_agents_add),
                    value = "",
                    icon = Icons.Default.Add,
                    onClick = {
                        onOpen(DRAFT_AGENT_ID)
                    },
                    showDivider = false,
                )
            }
        } else {
            SettingsSection {
                Text(
                    stringResource(R.string.sub_agents_limit_reached, SubAgentLimits.MAX_COUNT),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
        }
    }
}

@Composable
fun SubAgentDetailScreen(agentId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { (context.applicationContext as MinisApp).providerRepository }
    val isDraft = agentId == DRAFT_AGENT_ID
    val original = remember(agentId) {
        if (isDraft) {
            SubAgentDefinition(name = "", description = "", sortOrder = repo.subAgents.size)
        } else {
            repo.subAgent(agentId)
        }
    }
    if (original == null) {
        SettingsScaffold(title = stringResource(R.string.sub_agents_title), onBack = onBack) {}
        return
    }

    var name by remember(agentId) { mutableStateOf(original.name) }
    var description by remember(agentId) { mutableStateOf(original.description) }
    var instructions by remember(agentId) { mutableStateOf(original.instructions) }
    var groupId by remember(agentId) { mutableStateOf(original.modelGroupId) }
    var thinking by remember(agentId) { mutableStateOf(original.thinkingLevelOverride) }
    var error by remember(agentId) { mutableStateOf<String?>(null) }

    val groups = remember { repo.config.value.modelGroups.toList() }
    val nameTaken = stringResource(R.string.sub_agent_name_taken)
    val nameRequired = stringResource(R.string.sub_agent_name_required)

    fun persist() {
        if (original.isBuiltIn) {
            repo.upsertSubAgent(
                original.copy(
                    instructions = instructions,
                    modelGroupId = groupId,
                    thinkingLevelOverride = thinking,
                ),
            )
            error = null
            return
        }
        val trimmed = name.trim()
        if (trimmed.isEmpty()) { error = nameRequired; return }
        val clash = repo.subAgents.any {
            it.id != original.id && SubAgentRoster.nameKey(it.name) == SubAgentRoster.nameKey(trimmed)
        }
        if (clash) { error = nameTaken; return }
        error = null
        repo.upsertSubAgent(
            original.copy(
                name = trimmed,
                description = description.trim(),
                instructions = instructions,
                modelGroupId = groupId,
                thinkingLevelOverride = thinking,
            ),
        )
    }

    SettingsScaffold(
        title = original.displayName(context).ifEmpty { stringResource(R.string.sub_agents_title) },
        onBack = { persist(); onBack() },
    ) {
        if (original.isBuiltIn) {
            SettingsSection(footer = stringResource(R.string.sub_agent_builtin_note)) {
                SettingsValueRow(
                    title = stringResource(R.string.sub_agent_name),
                    value = original.displayName(context),
                    showDivider = false,
                )
            }
        } else {
            SettingsSection(footer = error) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(SubAgentLimits.NAME_MAX_LENGTH) },
                        label = { Text(stringResource(R.string.sub_agent_name)) },
                        singleLine = true,
                        isError = error != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FieldCounter(name.length, SubAgentLimits.NAME_MAX_LENGTH)
                }
            }
            SettingsSection(footer = stringResource(R.string.sub_agent_description_footer)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it.take(SubAgentLimits.DESCRIPTION_MAX_LENGTH) },
                        label = { Text(stringResource(R.string.sub_agent_description)) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FieldCounter(description.length, SubAgentLimits.DESCRIPTION_MAX_LENGTH)
                }
            }
        }

        SettingsSection(
            footer = stringResource(
                if (original.isBuiltIn) R.string.sub_agent_instructions_footer_builtin
                else R.string.sub_agent_instructions_footer,
            ),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it.take(SubAgentLimits.INSTRUCTIONS_MAX_LENGTH) },
                    label = { Text(stringResource(R.string.sub_agent_instructions)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                FieldCounter(instructions.length, SubAgentLimits.INSTRUCTIONS_MAX_LENGTH)
            }
        }

        SettingsSection(
            header = stringResource(R.string.sub_agent_model),
            footer = stringResource(R.string.sub_agent_model_footer),
        ) {
            SettingsChoiceRow(
                title = stringResource(R.string.sub_agent_model_auto),
                selected = groupId == null,
                onSelect = { groupId = null; persist() },
            )
            groups.forEachIndexed { i, g ->
                SettingsChoiceRow(
                    title = g.name,
                    selected = groupId == g.id,
                    onSelect = { groupId = g.id; persist() },
                    showDivider = i < groups.lastIndex,
                )
            }
        }

        SettingsSection(
            header = stringResource(R.string.sub_agent_reasoning),
            footer = stringResource(
                if (thinking == null) R.string.sub_agent_reasoning_footer_off
                else R.string.sub_agent_reasoning_footer_on,
            ),
        ) {
            val on = thinking != null
            SettingsSwitchRow(
                title = stringResource(R.string.sub_agent_reasoning_override),
                checked = on,
                onCheckedChange = { enabled ->
                    thinking = if (enabled) (thinking ?: ThinkingLevel.MEDIUM) else null
                    persist()
                },
                icon = Icons.Default.Psychology,
                iconColor = Color(0xFFAF52DE),
                showDivider = on,
            )
            if (on) {
                val entries = remember { repo.config.value.modelEntries.toList() }
                val ceiling = remember(groupId, entries) {
                    val members = groupId?.let { gid -> groups.find { it.id == gid } }?.memberEntryIds
                    if (members == null) {
                        ThinkingLevel.ULTRA
                    } else {
                        members.mapNotNull { entryId ->
                            entries.find { it.id == entryId }?.effectiveMaxThinkingLevel
                        }.filter { it != ThinkingLevel.OFF }.maxByOrNull { it.rank }
                            ?: ThinkingLevel.XHIGH
                    }
                }
                val labelFor: @Composable (ThinkingLevel) -> String = { level ->
                    when (level) {
                        ThinkingLevel.LOW -> stringResource(R.string.model_group_detail_thinking_low)
                        ThinkingLevel.MEDIUM -> stringResource(R.string.model_group_detail_thinking_medium)
                        ThinkingLevel.HIGH -> stringResource(R.string.model_group_detail_thinking_high)
                        ThinkingLevel.XHIGH -> stringResource(R.string.model_group_detail_thinking_xhigh)
                        ThinkingLevel.MAX -> stringResource(R.string.model_group_detail_thinking_max)
                        ThinkingLevel.ULTRA -> stringResource(R.string.model_group_detail_thinking_ultra)
                        ThinkingLevel.OFF -> stringResource(R.string.model_group_detail_thinking_low)
                    }
                }
                val cases = ThinkingLevel.entries
                    .filter { it != ThinkingLevel.OFF && it.rank <= ceiling.rank }
                    .map { it to labelFor(it) }
                SettingsCardBlock {
                    Text(
                        text = stringResource(R.string.model_group_detail_intensity),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        cases.forEachIndexed { idx, (level, label) ->
                            SegmentedButton(
                                selected = thinking == level,
                                onClick = { thinking = level; persist() },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = idx,
                                    count = cases.size,
                                ),
                                icon = {},
                            ) {
                                Text(
                                    label,
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!original.isBuiltIn && !isDraft) {
            SettingsSection {
                SettingsValueRow(
                    title = stringResource(R.string.sub_agent_delete),
                    value = "",
                    valueColor = MaterialTheme.colorScheme.error,
                    onClick = { repo.deleteSubAgent(agentId); onBack() },
                    showDivider = false,
                )
            }
        }
    }
}

@Composable
private fun FieldCounter(count: Int, limit: Int) {
    Text(
        "$count/$limit",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
    )
}

private fun move(
    repo: ProviderRepository,
    roster: List<SubAgentDefinition>,
    from: Int,
    to: Int,
) {
    if (from !in roster.indices || to !in roster.indices) return
    if (from == 0 || to == 0) return
    val ids = roster.map { it.id }.toMutableList()
    ids.add(to, ids.removeAt(from))
    repo.reorderSubAgents(ids)
}

@Composable
private fun SubAgentRosterRow(
    name: String,
    isBuiltIn: Boolean,
    description: String,
    modelLabel: String,
    onClick: () -> Unit,
    showDivider: Boolean,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Groups,
                contentDescription = null,
                tint = com.openminis.app.ui.chat.HelperAccentStatic,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isBuiltIn) {
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.sub_agent_builtin_badge),
                    fontSize = 10.sp,
                    lineHeight = 11.sp,
                    style = LocalTextStyle.current.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                            RoundedCornerShape(50),
                        )
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        if (description.isNotEmpty()) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 32.dp, top = 2.dp),
            )
        }
        Text(
            modelLabel,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
            modifier = Modifier.padding(start = 32.dp, top = 2.dp),
        )
    }
    if (showDivider) {
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(start = 48.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
}
