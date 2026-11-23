package com.openminis.app.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.ui.components.MinisSmallButton
import com.openminis.app.ui.components.SectionDesign
import com.openminis.app.ui.components.SectionTextField

sealed interface GroupChoice {
    data class Existing(val folderId: String) : GroupChoice

    data class Create(val name: String, val description: String?) : GroupChoice

    data object RemoveFromGroup : GroupChoice
}

internal fun groupPickerOrder(folders: List<FolderEntity>, lastActivity: Map<String, Long>): List<FolderEntity> =
    folders.sortedByDescending { f ->
        maxOf(f.updatedAt, f.createdAt, lastActivity[f.id] ?: Long.MIN_VALUE)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPickerSheet(
    folders: List<FolderEntity>,
    memberCounts: Map<String, Int>,
    sessionCount: Int,
    anyFiled: Boolean,
    onChoose: (GroupChoice) -> Unit,
    onDismiss: () -> Unit,
    suggesting: Boolean = false,
    suggestFailed: Boolean = false,
    suggestion: SessionListViewModel.GroupSuggestion? = null,
    onSuggest: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var newName by remember { mutableStateOf("") }
    var newDesc by remember { mutableStateOf("") }

    val createSuggestion = suggestion as? SessionListViewModel.GroupSuggestion.Create
    androidx.compose.runtime.LaunchedEffect(createSuggestion) {
        if (createSuggestion != null) {
            newName = createSuggestion.name
            createSuggestion.description?.let { newDesc = it.take(FolderEntity.DESC_MAX_CHARS) }
        }
    }

    val trimmedName = newName.trim()
    val duplicate = remember(trimmedName, folders) {
        if (trimmedName.isEmpty()) null
        else folders.firstOrNull { it.name.trim().equals(trimmedName, ignoreCase = true) }
    }

    val title = when {
        sessionCount > 1 && anyFiled -> stringResource(R.string.group_picker_title_change_n, sessionCount)
        sessionCount > 1 -> stringResource(R.string.group_picker_title_move_n, sessionCount)
        anyFiled -> stringResource(R.string.group_picker_title_change)
        else -> stringResource(R.string.group_picker_title_move)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )

            SectionTextField(
                value = newName,
                onValueChange = { newName = it },
                placeholder = stringResource(R.string.group_new_name_hint),
                modifier = Modifier.padding(horizontal = 20.dp),
                containerColor = SectionDesign.screenBackgroundColor(),
                contentHorizontalPadding = 16.dp,
            )
            Spacer(Modifier.height(8.dp))
            SectionTextField(
                value = newDesc,
                onValueChange = { newDesc = it.take(FolderEntity.DESC_MAX_CHARS) },
                placeholder = stringResource(R.string.group_desc_hint),
                modifier = Modifier.padding(horizontal = 20.dp),
                containerColor = SectionDesign.screenBackgroundColor(),
                contentHorizontalPadding = 16.dp,
            )

            if (duplicate != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onChoose(GroupChoice.Existing(duplicate.id)) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text(
                            stringResource(R.string.group_duplicate_exists, duplicate.name),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.group_duplicate_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            (suggestion as? SessionListViewModel.GroupSuggestion.Merge)?.let { merge ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onChoose(GroupChoice.Existing(merge.folderId)) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        stringResource(R.string.group_suggest_merge, merge.folderName),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onSuggest != null) {
                    Row(
                        modifier = Modifier
                            .clickable(enabled = !suggesting) { onSuggest() }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (suggesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(
                                if (suggestFailed) R.string.group_suggest_failed
                                else R.string.group_suggest,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                MinisSmallButton(
                    onClick = {
                        onChoose(
                            GroupChoice.Create(
                                name = trimmedName,
                                description = newDesc.trim().ifBlank { null },
                            ),
                        )
                    },
                    enabled = trimmedName.isNotEmpty() && duplicate == null,
                ) { Text(stringResource(R.string.group_create)) }
            }

            if (folders.isNotEmpty() || anyFiled) {
                Text(
                    text = stringResource(R.string.group_section_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    if (anyFiled) {
                        item(key = "no_group") {
                            val label = stringResource(R.string.group_none)
                            val hint = stringResource(R.string.group_none_subtitle)
                            GroupRow(
                                title = label,
                                subtitle = hint,
                                icon = Icons.Default.FolderOff,
                                onClick = { onChoose(GroupChoice.RemoveFromGroup) },
                                modifier = Modifier.semantics(mergeDescendants = true) {
                                    contentDescription = label
                                    stateDescription = hint
                                },
                            )
                        }
                    }
                    items(folders, key = { it.id }) { folder ->
                        val count = memberCounts[folder.id] ?: 0
                        GroupRow(
                            title = folder.name,
                            subtitle = folder.description?.takeIf { it.isNotBlank() }
                                ?: if (count > 0) stringResource(R.string.group_n_chats, count)
                                else stringResource(R.string.group_empty),
                            icon = Icons.Default.Folder,
                            onClick = { onChoose(GroupChoice.Existing(folder.id)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.size(16.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
