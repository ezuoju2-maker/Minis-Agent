package com.openminis.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.SystemVoiceEntries
import com.openminis.app.data.model.isVoiceInputCandidate
import com.openminis.app.data.model.isVoiceOutputCandidate
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.model.normalizeModalities

enum class PickerModalityFilter {
    AUDIO_INPUT,
    AUDIO_OUTPUT,
    IMAGE_INPUT;

    fun matches(model: LLMModel): Boolean = when (this) {
        AUDIO_INPUT -> model.isVoiceInputCandidate
        AUDIO_OUTPUT -> model.isVoiceOutputCandidate
        IMAGE_INPUT -> model.hasImageInput
    }

    fun systemEntries(): List<ModelEntry> = when (this) {
        AUDIO_INPUT -> listOf(SystemVoiceEntries.asrOnline, SystemVoiceEntries.asrOffline)
        AUDIO_OUTPUT -> listOf(SystemVoiceEntries.tts)
        IMAGE_INPUT -> emptyList()
    }
}

@OptIn(ExperimentalLayoutApi::class)
fun LazyListScope.modelEntryPickerItems(
    instances: List<ProviderInstance>,
    availableEntries: List<ModelEntry>,
    selectedIds: Set<String>,
    onToggleSelection: (String) -> Unit,
    searchQuery: MutableState<String>,
    collapsedInstanceIds: MutableState<Set<String>>,
    @StringRes emptyTextRes: Int,
    @StringRes emptySearchTextRes: Int,
    @StringRes searchPlaceholderRes: Int,
    @StringRes clearContentDescriptionRes: Int,
    onQuickTest: ((ModelEntry) -> Unit)? = null,
    modalityFilter: PickerModalityFilter? = null,
    excludeIds: Set<String> = emptySet(),
    systemProviderLabel: String = "System",
    sections: ModelSearch.Result<ProviderInstance>? = null,
) {
    val result = sections ?: modelEntryPickerSearch(
        instances, availableEntries, ModelSearch.normalize(searchQuery.value),
        modalityFilter, excludeIds, systemProviderLabel,
    )
    val isSearching = searchQuery.value.isNotBlank()
    val instanceWithEntries = result.sections.map { it.key to it.entries }

    val effectiveCollapsed = if (isSearching) emptySet() else collapsedInstanceIds.value

    item("__search__") {
        OutlinedTextField(
            value = searchQuery.value,
            onValueChange = { searchQuery.value = it },
            placeholder = { Text(stringResource(searchPlaceholderRes)) },
            singleLine = true,
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                if (searchQuery.value.isNotEmpty()) {
                    IconButton(onClick = { searchQuery.value = "" }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(clearContentDescriptionRes),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            },
        )
    }

    instanceWithEntries.forEach { (instance, entries) ->
        val isCollapsed = instance.id in effectiveCollapsed
        val dotColor = providerDotColor(instance.providerType)

        item(key = "header_${instance.id}") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    instance.label.ifEmpty { instance.providerType.displayName },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                            CircleShape,
                        )
                        .clip(CircleShape)
                        .clickable {
                            collapsedInstanceIds.value = if (isCollapsed) {
                                collapsedInstanceIds.value - instance.id
                            } else {
                                collapsedInstanceIds.value + instance.id
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isCollapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 20.dp, end = 16.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            )
        }

        if (isCollapsed) {
            item(key = "collapsed_${instance.id}") {
                val firstEntry = entries.firstOrNull() ?: return@item
                val isSelected = firstEntry.id in selectedIds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainer,
                            RoundedCornerShape(12.dp),
                        )
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onToggleSelection(firstEntry.id) }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SelectionDot(isSelected)
                    Spacer(Modifier.width(10.dp))
                    Box(modifier = Modifier.size(6.dp).background(dotColor, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        firstEntry.model.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        androidx.compose.ui.res.pluralStringResource(
                            R.plurals.model_picker_models_count,
                            entries.size,
                            entries.size,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
        } else {
            itemsIndexed(entries, key = { _, e -> "entry_${instance.id}_${e.id}" }) { index, entry ->
                val cardShape = when {
                    entries.size == 1 -> RoundedCornerShape(12.dp)
                    index == 0 -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                    index == entries.size - 1 -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                    else -> RoundedCornerShape(0.dp)
                }
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainer, cardShape),
                ) {
                        val isSelected = entry.id in selectedIds
                        val rowShape = when {
                            entries.size == 1 -> RoundedCornerShape(12.dp)
                            index == 0 -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                            index == entries.size - 1 -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                            else -> RoundedCornerShape(0.dp)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(rowShape)
                                .clickable { onToggleSelection(entry.id) }
                                .padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SelectionDot(isSelected)
                            Spacer(Modifier.width(10.dp))
                            Box(modifier = Modifier.size(6.dp).background(dotColor, CircleShape))
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    entry.model.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                val badges = modalityBadges(entry.model)
                                FitOrWrapLine(
                                    separator = if (badges.isNotEmpty()) {
                                        { ModelRowSeparatorDot() }
                                    } else {
                                        null
                                    },
                                    lead = {
                                        Text(
                                            entry.model.id,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        )
                                    },
                                    tags = { badges.forEach { badge -> ModalityBadge(badge) } },
                                )
                            }
                            if (onQuickTest != null && !SystemVoiceEntries.isSystemEntryId(entry.id)) {
                                IconButton(
                                    onClick = { onQuickTest(entry) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.Bolt,
                                        contentDescription = stringResource(R.string.quicktest_button),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                        if (index < entries.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 52.dp, end = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            )
                        }
                }
            }
        }
    }

    if (isSearching && result.truncated) {
        item("__search_cap_footer__") {
            Text(
                stringResource(R.string.model_picker_search_capped, result.shown, result.totalMatches),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }

    if (instanceWithEntries.isEmpty()) {
        item("__empty__") {
            Text(
                stringResource(if (isSearching) emptySearchTextRes else emptyTextRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun SelectionDot(isSelected: Boolean) {
    Icon(
        if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
        contentDescription = null,
        tint = if (isSelected) Color(0xFF007AFF)
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.size(20.dp),
    )
}

fun modalityBadges(model: LLMModel): List<String> {
    val ins = model.inputModalities.normalizeModalities() ?: emptyList()
    val outs = model.outputModalities.normalizeModalities() ?: emptyList()
    val badges = mutableListOf<String>()
    if ("image" in ins) badges.add("img")
    if ("audio" in ins) badges.add("audio")
    if ("video" in ins) badges.add("video")
    if ("pdf" in ins) badges.add("pdf")
    if ("image" in outs) badges.add("img-out")
    if ("audio" in outs) badges.add("audio-out")
    if ("video" in outs) badges.add("video-out")
    return badges
}

@Composable
fun ModalityBadge(badge: String) {
    Text(
        badge,
        fontSize = 9.sp,
        lineHeight = 11.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        softWrap = false,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                RoundedCornerShape(3.dp),
            )
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
fun ModelRowSeparatorDot() {
    Text(
        "·",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
    )
}

@Composable
fun ModelActiveBadge() {
    Text(
        stringResource(R.string.model_picker_active_badge),
        fontSize = 9.sp,
        lineHeight = 11.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        softWrap = false,
        color = Color(0xFF34C759),
        modifier = Modifier
            .background(
                Color(0xFF34C759).copy(alpha = 0.1f),
                RoundedCornerShape(50),
            )
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

fun providerDotColor(providerType: ProviderType?): Color = when (providerType) {
    ProviderType.anthropic -> Color(0xFFAB47BC)
    ProviderType.gemini -> Color(0xFF42A5F5)
    ProviderType.openAI -> Color(0xFF4CAF50)
    ProviderType.openRouter -> Color(0xFF00BCD4)
    ProviderType.xAI -> Color(0xFFFF7043)
    ProviderType.kimiCode -> Color(0xFF5C6BC0)
    ProviderType.githubCopilot -> Color(0xFF6E5494)
    ProviderType.openAIResponses -> Color(0xFF4CAF50)
    ProviderType.workBuddy -> Color(0xFF4D6BFE)
    ProviderType.qoder -> Color(0xFF111111)
    ProviderType.minimaxCode -> Color(0xFFE73562)
    ProviderType.antigravity,
    ProviderType.unsupported -> Color(0xFF8E8E93)
    null -> Color(0xFF8E8E93)
}

@androidx.compose.runtime.Composable
fun rememberModelEntryPickerSections(
    instances: List<ProviderInstance>,
    availableEntries: List<ModelEntry>,
    searchQuery: String,
    modalityFilter: PickerModalityFilter? = null,
    excludeIds: Set<String> = emptySet(),
    systemProviderLabel: String = "System",
): ModelSearch.Result<ProviderInstance> {
    val query = rememberDebouncedQuery(searchQuery)
    return androidx.compose.runtime.remember(instances, availableEntries, query, modalityFilter, excludeIds, systemProviderLabel) {
        modelEntryPickerSearch(instances, availableEntries, query, modalityFilter, excludeIds, systemProviderLabel)
    }
}

internal fun modelEntryPickerSearch(
    instances: List<ProviderInstance>,
    availableEntries: List<ModelEntry>,
    query: String,
    modalityFilter: PickerModalityFilter?,
    excludeIds: Set<String>,
    systemProviderLabel: String,
): ModelSearch.Result<ProviderInstance> {
    val systemPair: Pair<ProviderInstance, List<ModelEntry>>? = modalityFilter
        ?.systemEntries()
        ?.filter { it.id !in excludeIds }
        ?.takeIf { it.isNotEmpty() }
        ?.let { SystemVoiceEntries.syntheticInstance(systemProviderLabel) to it }

    val byInstance = availableEntries
        .filter { modalityFilter == null || modalityFilter.matches(it.model) }
        .groupBy { it.providerInstanceId }
    val base = listOfNotNull(systemPair) + instances
        .filter { it.isEnabled && !SystemVoiceEntries.isSystemEntryId(it.id) }
        .mapNotNull { instance ->
            val entries = byInstance[instance.id] ?: return@mapNotNull null
            instance to com.openminis.app.data.repository.ModelEntryRanking.sortedByReleaseRank(entries)
        }
    return ModelSearch.search(base, { it.label.ifEmpty { it.providerType.displayName } }, query)
}
