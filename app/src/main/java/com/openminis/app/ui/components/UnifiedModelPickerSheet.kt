package com.openminis.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.SystemVoiceEntries
import com.openminis.app.data.model.SystemVoiceIds
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.speech.VoiceOutputState
import com.openminis.app.ui.theme.ChatColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UnifiedModelPickerSheet(
    providerRepository: ProviderRepository,
    title: String,
    modalityFilter: PickerModalityFilter,
    boundGroup: ModelGroup?,
    boundGroupName: String?,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    onEditGroups: (() -> Unit)? = null,
    onEditProvider: ((instanceId: String) -> Unit)? = null,
) {
    val config by providerRepository.config.collectAsState()
    var quickTestEntry by remember { mutableStateOf<ModelEntry?>(null) }
    var searchText by remember { mutableStateOf("") }
    var groupExpanded by remember { mutableStateOf(false) }
    val pickerContext = androidx.compose.ui.platform.LocalContext.current
    var systemVoices by remember {
        mutableStateOf(emptyList<com.openminis.app.speech.SystemTtsVoiceCatalog.SystemVoice>())
    }
    val pickedSystemVoice by com.openminis.app.speech.VoiceOutputState
        .systemVoiceName.collectAsState()
    androidx.compose.runtime.LaunchedEffect(modalityFilter) {
        if (modalityFilter == PickerModalityFilter.AUDIO_OUTPUT) {
            com.openminis.app.speech.VoiceOutputState.init(pickerContext)
            systemVoices = com.openminis.app.speech.SystemTtsVoiceCatalog.voices(pickerContext)
        }
    }
    var collapsedInstanceIds by remember { mutableStateOf(setOf<String>()) }

    fun select(id: String?) {
        onSelect(id)
        onDismiss()
    }

    val query = rememberDebouncedQuery(searchText)
    fun matches(text: String): Boolean = query.isEmpty() || ModelSearch.score(text, query) > 0

    val activeGroupMemberId = remember(config, modalityFilter) {
        when (modalityFilter) {
            PickerModalityFilter.AUDIO_INPUT ->
                providerRepository.activeVoiceGroupMemberId(output = false)
            PickerModalityFilter.AUDIO_OUTPUT ->
                providerRepository.activeVoiceGroupMemberId(output = true)
            else -> null
        }
    }

    val cardColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val cardShape = RoundedCornerShape(14.dp)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 4.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .background(
                            color = ChatColors.secondaryText.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(2.dp),
                        ),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    MinisTextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.model_picker_done))
                    }
                }
            }

            val searchInteraction = remember { MutableInteractionSource() }
            BasicTextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .height(42.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                interactionSource = searchInteraction,
                decorationBox = { innerTextField ->
                    OutlinedTextFieldDefaults.DecorationBox(
                        value = searchText,
                        visualTransformation = VisualTransformation.None,
                        innerTextField = innerTextField,
                        placeholder = { Text(stringResource(R.string.model_picker_search_placeholder)) },
                        label = null,
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingIcon = {
                            if (searchText.isNotEmpty()) {
                                IconButton(onClick = { searchText = "" }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.model_picker_search_clear),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        enabled = true,
                        isError = false,
                        interactionSource = searchInteraction,
                        colors = OutlinedTextFieldDefaults.colors(),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        container = {
                            OutlinedTextFieldDefaults.Container(
                                enabled = true,
                                isError = false,
                                interactionSource = searchInteraction,
                                colors = OutlinedTextFieldDefaults.colors(),
                                shape = RoundedCornerShape(50),
                            )
                        },
                    )
                },
            )

            val unifiedPickerScrollGuard = rememberSheetScrollGuard()

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .nestedScroll(unifiedPickerScrollGuard),
            ) {
                if (boundGroup != null && matches(boundGroupName ?: "")) {
                    item("group_card") {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .background(cardColor, cardShape)
                                .clip(cardShape),
                        ) {
                            val editTrailing: (@Composable () -> Unit)? =
                                if (onEditGroups == null) null else {
                                    {
                                        MinisTextButton(
                                            onClick = onEditGroups,
                                            modifier = Modifier.padding(end = 8.dp),
                                        ) {
                                            Text(stringResource(R.string.model_picker_groups_edit))
                                        }
                                    }
                                }
                            PickerSectionHeader(
                                stringResource(R.string.model_picker_groups_section),
                                trailing = editTrailing,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { select(null) }
                                    .padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val groupSelected =
                                    selectedId == null ||
                                        boundGroup.memberEntryIds.contains(selectedId)
                                Icon(
                                    if (groupSelected) Icons.Default.CheckCircle
                                    else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (groupSelected) Color(0xFF34C759)
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Icon(
                                    Icons.Default.Layers,
                                    contentDescription = null,
                                    tint = Color(0xFF007AFF),
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            boundGroupName ?: "",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                                    RoundedCornerShape(8.dp),
                                                )
                                                .padding(horizontal = 5.dp, vertical = 1.dp),
                                        ) {
                                            Icon(
                                                Icons.Default.ArrowCircleDown,
                                                contentDescription = null,
                                                modifier = Modifier.size(9.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            )
                                            Spacer(Modifier.width(2.dp))
                                            Text(
                                                "FB",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            )
                                        }
                                    }
                                    Text(
                                        stringResource(
                                            R.string.voice_input_picker_group_models,
                                            boundGroup.memberEntryIds.count { id ->
                                                config.modelEntries.any { it.id == id } ||
                                                    SystemVoiceEntries.resolve(id) != null
                                            },
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                            CircleShape,
                                        )
                                        .clip(CircleShape)
                                        .clickable { groupExpanded = !groupExpanded },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        if (groupExpanded) Icons.Default.KeyboardArrowUp
                                        else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (groupExpanded) {
                                val members = boundGroup.memberEntryIds.mapNotNull { id ->
                                    config.modelEntries.find { it.id == id }
                                        ?: SystemVoiceEntries.resolve(id)
                                }
                                members.forEach { entry ->
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 52.dp, end = 16.dp),
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                    )
                                    val inst = config.instances
                                        .find { it.id == entry.providerInstanceId }
                                    val isActive = if (selectedId != null) {
                                        selectedId == entry.id
                                    } else {
                                        entry.id == activeGroupMemberId
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { select(entry.id) }
                                            .padding(
                                                start = 46.dp,
                                                end = 16.dp,
                                                top = 10.dp,
                                                bottom = 10.dp,
                                            ),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        SelectionMark(selected = isActive)
                                        Spacer(Modifier.width(10.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .background(
                                                    providerDotColor(inst?.providerType),
                                                    CircleShape,
                                                ),
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        FitOrWrapLine(
                                            modifier = Modifier.weight(1f),
                                            lead = {
                                                Text(
                                                    entry.model.displayName.ifBlank { entry.model.id },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                )
                                            },
                                            tags = {
                                                if (isActive && selectedId == null) ModelActiveBadge()
                                            },
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                    item("group_footer") {
                        Text(
                            stringResource(R.string.voice_input_picker_group_footer),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                        )
                    }
                }

                val systemEntries = modalityFilter.systemEntries()
                    .filter { matches(it.model.displayName) }
                if (systemEntries.isNotEmpty()) {
                    item("system_card") {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .background(cardColor, cardShape)
                                .clip(cardShape),
                        ) {
                            PickerSectionHeader(stringResource(R.string.voice_input_picker_system))
                            systemEntries.forEachIndexed { i, entry ->
                                if (i > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 52.dp, end = 16.dp),
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (modalityFilter == PickerModalityFilter.AUDIO_OUTPUT) {
                                                VoiceOutputState.setSystemVoice(null, null)
                                            }
                                            select(entry.id)
                                        }
                                        .padding(horizontal = 16.dp, vertical = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val systemInEffect =
                                        (if (selectedId != null) selectedId == entry.id
                                        else entry.id == activeGroupMemberId) &&
                                            (modalityFilter != PickerModalityFilter.AUDIO_OUTPUT ||
                                                pickedSystemVoice == null)
                                    SelectionMark(selected = systemInEffect)
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            entry.model.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            systemEntrySubtitle(entry),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (systemInEffect && selectedId == null) {
                                        Spacer(Modifier.width(6.dp))
                                        ModelActiveBadge()
                                    }
                                }
                            }
                            if (modalityFilter == PickerModalityFilter.AUDIO_OUTPUT) {
                                systemVoices
                                    .filter { matches(it.label) }
                                    .forEach { v ->
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 52.dp, end = 16.dp),
                                            thickness = 0.5.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                        )
                                        val sysId = systemEntries.firstOrNull()?.id
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    VoiceOutputState.setSystemVoice(v.name, v.label)
                                                    select(sysId)
                                                }
                                                .padding(horizontal = 16.dp, vertical = 11.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            SelectionMark(
                                                selected = selectedId == sysId &&
                                                    pickedSystemVoice == v.name,
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    v.label,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                )
                                                Text(
                                                    v.name,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }

                val entriesByInstance = config.instances
                    .filter { it.isEnabled }
                    .mapNotNull { inst ->
                        val sectionEntries = com.openminis.app.data.repository.ModelEntryRanking
                            .sortedByReleaseRank(
                                config.modelEntries.filter { e ->
                                    e.providerInstanceId == inst.id && !e.isHidden &&
                                        modalityFilter.matches(e.model)
                                },
                            )
                        val entries = if (matches(inst.label.ifEmpty { inst.providerType.displayName })) sectionEntries
                            else ModelSearch.rank(sectionEntries, query)
                        if (entries.isEmpty()) null else inst to entries
                    }
                entriesByInstance.forEach { (inst, entries) ->
                    item("p_${inst.id}") {
                        val collapsed = collapsedInstanceIds.contains(inst.id)
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .background(cardColor, cardShape)
                                .clip(cardShape),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val providerName = inst.label.ifEmpty { inst.providerType.displayName }
                                Text(
                                    providerName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (onEditProvider != null) {
                                    val editLabel = stringResource(R.string.model_picker_edit_provider, providerName)
                                    Text(
                                        stringResource(R.string.model_picker_groups_edit),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier
                                            .padding(end = 4.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { onEditProvider(inst.id) }
                                            .semantics { contentDescription = editLabel }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                            CircleShape,
                                        )
                                        .clip(CircleShape)
                                        .clickable {
                                            collapsedInstanceIds =
                                                if (collapsed) collapsedInstanceIds - inst.id
                                                else collapsedInstanceIds + inst.id
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        if (collapsed) Icons.Default.KeyboardArrowDown
                                        else Icons.Default.KeyboardArrowUp,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            )
                            if (!collapsed) {
                                entries.forEachIndexed { i, entry ->
                                    if (i > 0) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 52.dp, end = 16.dp),
                                            thickness = 0.5.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                        )
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { select(entry.id) }
                                            .padding(
                                                start = 16.dp,
                                                end = 8.dp,
                                                top = 11.dp,
                                                bottom = 11.dp,
                                            ),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        SelectionMark(selected = selectedId == entry.id)
                                        Spacer(Modifier.width(10.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .background(
                                                    providerDotColor(inst.providerType),
                                                    CircleShape,
                                                ),
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                entry.model.displayName.ifBlank { entry.model.id },
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
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
                                                        entry.baseModel.id,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                },
                                                tags = { badges.forEach { badge -> ModalityBadge(badge) } },
                                            )
                                        }
                                        IconButton(
                                            onClick = { quickTestEntry = entry },
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
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
                item("bottom_pad") { Spacer(Modifier.padding(12.dp)) }
            }
        }
    }

    quickTestEntry?.let { entry ->
        QuickTestSheet(
            entry = entry,
            providerRepository = providerRepository,
            onDismiss = { quickTestEntry = null },
        )
    }
}

@Composable
private fun PickerSectionHeader(
    label: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
        )
        trailing?.invoke()
    }
}

@Composable
private fun SelectionMark(selected: Boolean) {
    Icon(
        if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
        contentDescription = null,
        tint = if (selected) Color(0xFF007AFF)
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.size(20.dp),
    )
}

@Composable
private fun systemEntrySubtitle(entry: ModelEntry): String = when (entry.model.id) {
    SystemVoiceIds.SYSTEM_TTS -> stringResource(R.string.voice_system_tts_subtitle)
    SystemVoiceIds.SYSTEM_ASR_ONLINE -> stringResource(R.string.voice_system_online_subtitle)
    SystemVoiceIds.SYSTEM_ASR_OFFLINE -> stringResource(R.string.voice_system_offline_subtitle)
    else -> ""
}
