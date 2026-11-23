package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import com.openminis.app.R
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsValueRow
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.openminis.app.ui.components.MinisTextButton

@Composable
fun SessionMemorySheet(
    memoryRepository: MemoryRepository,
    toolRecords: List<MemoryToolRecord>,
    onDismiss: () -> Unit,
    onRevokeRecord: (MemoryToolRecord) -> MemoryRepository.EntryMutationResult,
    onSaveRecord: (MemoryToolRecord, String) -> MemoryRepository.EntryMutationResult,
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf<MemorySheetMode>(MemorySheetMode.List) }
    val autoItems = remember(memoryRepository, context) { buildAutoInjectedItems(context, memoryRepository) }

    var isEditing by remember(mode) { mutableStateOf(false) }
    var editedContent by remember(mode) { mutableStateOf("") }
    var savedToastVisible by remember { mutableStateOf(false) }

    var showRevokeConfirm by remember { mutableStateOf(false) }
    var mutationResult by remember { mutableStateOf<MemoryRepository.EntryMutationResult?>(null) }

    LaunchedEffect(savedToastVisible) {
        if (savedToastVisible) {
            delay(1500)
            savedToastVisible = false
        }
    }

    LaunchedEffect(mode, isEditing) {
        if (isEditing) {
            editedContent = when (val m = mode) {
                is MemorySheetMode.AutoFile -> m.content
                is MemorySheetMode.Write -> m.record.writtenContent ?: ""
                else -> ""
            }
        }
    }

    val title = when (val m = mode) {
        MemorySheetMode.List -> stringResource(R.string.session_memory_title)
        is MemorySheetMode.AutoFile -> m.name
        is MemorySheetMode.Write -> m.record.title
        is MemorySheetMode.Get -> m.record.title
    }

    StandardChatSheet(
        title = title,
        onDismiss = onDismiss,
        leadingAction = if (mode != MemorySheetMode.List) {
            {
                IconButton(onClick = {
                    if (isEditing) {
                        isEditing = false
                    } else {
                        mode = MemorySheetMode.List
                    }
                }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.memory_action_back),
                    )
                }
            }
        } else null,
    ) {
        when (val m = mode) {
            MemorySheetMode.List -> ListBody(
                memoryRepository = memoryRepository,
                autoItems = autoItems,
                toolRecords = toolRecords,
                onAutoItemClick = { item ->
                    mode = MemorySheetMode.AutoFile(
                        name = item.fileName,
                        content = item.content,
                        editable = true,
                    )
                },
                onWriteClick = { rec -> mode = MemorySheetMode.Write(rec) },
                onGetClick = { rec -> mode = MemorySheetMode.Get(rec) },
            )

            is MemorySheetMode.AutoFile -> Column(modifier = Modifier.fillMaxSize()) {
                DetailToolbar(
                    showEdit = m.editable && !isEditing,
                    showSave = m.editable && isEditing,
                    showRevoke = false,
                    onEdit = { isEditing = true; editedContent = m.content },
                    onSave = {
                        try {
                            memoryRepository.saveFile(m.name, editedContent)
                            if (m.name == "SOUL.md") {
                                com.openminis.app.agent.SoulStore.refreshCache(context)
                            }
                            mode = MemorySheetMode.AutoFile(m.name, editedContent, m.editable)
                            isEditing = false
                            savedToastVisible = true
                        } catch (_: Exception) {  }
                    },
                    onRevoke = {},
                )
                MemoryFileViewerBody(
                    initialContent = m.content,
                    isEditing = isEditing,
                    editedContent = editedContent,
                    onEditedContentChange = { editedContent = it },
                    showSavedToast = savedToastVisible,
                )
            }

            is MemorySheetMode.Write -> Column(modifier = Modifier.fillMaxSize()) {
                val canEditOrRevoke = m.record.writtenContent != null
                DetailToolbar(
                    showEdit = canEditOrRevoke && !isEditing,
                    showSave = canEditOrRevoke && isEditing,
                    showRevoke = canEditOrRevoke && !isEditing,
                    onEdit = {
                        isEditing = true
                        editedContent = m.record.writtenContent ?: ""
                    },
                    onSave = {
                        val result = onSaveRecord(m.record, editedContent)
                        if (result is MemoryRepository.EntryMutationResult.Success) {
                            mode = MemorySheetMode.Write(
                                m.record.copy(writtenContent = editedContent)
                            )
                            isEditing = false
                            savedToastVisible = true
                        } else {
                            mutationResult = result
                        }
                    },
                    onRevoke = { showRevokeConfirm = true },
                )
                MemoryWriteDetailBody(
                    record = m.record,
                    isEditing = isEditing,
                    editedContent = editedContent,
                    onEditedContentChange = { editedContent = it },
                    showSavedToast = savedToastVisible,
                )
            }

            is MemorySheetMode.Get -> MemoryGetDetailBody(record = m.record)
        }
    }

    if (showRevokeConfirm && mode is MemorySheetMode.Write) {
        val writeMode = mode as MemorySheetMode.Write
        RevokeConfirmDialog(
            onConfirm = {
                showRevokeConfirm = false
                val result = onRevokeRecord(writeMode.record)
                mutationResult = result
                if (result is MemoryRepository.EntryMutationResult.Success) {
                    mode = MemorySheetMode.List
                }
            },
            onDismiss = { showRevokeConfirm = false },
        )
    }

    val resultSnapshot = mutationResult
    if (resultSnapshot != null) {
        MutationResultDialog(
            result = resultSnapshot,
            onDismiss = { mutationResult = null },
        )
    }
}

@Composable
private fun DetailToolbar(
    showEdit: Boolean,
    showSave: Boolean,
    showRevoke: Boolean,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onRevoke: () -> Unit,
) {
    if (!showEdit && !showSave && !showRevoke) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showEdit) {
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.memory_action_edit),
                )
            }
        }
        if (showSave) {
            MinisTextButton(onClick = onSave) {
                Text(stringResource(R.string.memory_action_save))
            }
        }
        if (showRevoke) {
            IconButton(onClick = onRevoke) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    contentDescription = stringResource(R.string.memory_action_revoke),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ListBody(
    memoryRepository: MemoryRepository,
    autoItems: List<AutoItem>,
    toolRecords: List<MemoryToolRecord>,
    onAutoItemClick: (AutoItem) -> Unit,
    onWriteClick: (MemoryToolRecord) -> Unit,
    onGetClick: (MemoryToolRecord) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            SettingsSection(
                header = stringResource(R.string.memory_section_auto_injected),
                footer = stringResource(R.string.memory_section_auto_injected_footer),
            ) {
                autoItems.forEachIndexed { index, item ->
                    SettingsValueRow(
                        title = item.name,
                        value = item.detail,
                        onClick = { onAutoItemClick(item) },
                        showDivider = index < autoItems.size - 1,
                    )
                }
            }
        }

        if (toolRecords.isNotEmpty()) {
            item {
                SettingsSection(
                    header = stringResource(R.string.memory_section_tool_activity),
                    footer = stringResource(R.string.memory_section_tool_activity_footer),
                ) {
                    toolRecords.forEachIndexed { index, record ->
                        MemoryToolRow(
                            record = record,
                            onClick = {
                                if (record.isWrite) onWriteClick(record) else onGetClick(record)
                            },
                            showDivider = index < toolRecords.size - 1,
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }

    @Suppress("UNUSED_EXPRESSION") memoryRepository
}

@Composable
private fun MemoryToolRow(
    record: MemoryToolRecord,
    onClick: () -> Unit,
    showDivider: Boolean,
) {
    val opLabel = if (record.isWrite) "memory_write" else "memory_get"
    val opColor = MaterialTheme.colorScheme.primary

    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    record.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .background(
                            color = opColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        opLabel,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        color = opColor,
                    )
                }
                if (record.preview.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        record.preview,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp)
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}

data class MemoryToolRecord(
    val title: String,
    val isWrite: Boolean,
    val preview: String,
    val output: String,
    val writtenContent: String? = null,
    val keywords: String? = null,
)

private sealed class MemorySheetMode {
    data object List : MemorySheetMode()
    data class AutoFile(val name: String, val content: String, val editable: Boolean) : MemorySheetMode()
    data class Write(val record: MemoryToolRecord) : MemorySheetMode()
    data class Get(val record: MemoryToolRecord) : MemorySheetMode()
}

internal data class AutoItem(
    val name: String,
    val detail: String,
    val fileName: String,
    val content: String,
)

private fun buildAutoInjectedItems(context: Context, memoryRepository: MemoryRepository): List<AutoItem> {
    val items = mutableListOf<AutoItem>()

    val soulContent = memoryRepository.readFile("SOUL.md")
    if (soulContent.isNotBlank()) {
        val lineCount = soulContent.lines().size
        items.add(AutoItem(
            name = "SOUL.md",
            detail = "$lineCount lines (full)",
            fileName = "SOUL.md",
            content = soulContent,
        ))
    } else {
        items.add(AutoItem(
            name = "SOUL.md",
            detail = context.getString(R.string.memory_file_empty),
            fileName = "SOUL.md",
            content = "",
        ))
    }

    val globalContent = memoryRepository.loadGlobalMd()
    if (globalContent.isNotBlank()) {
        val lineCount = globalContent.lines().size
        items.add(AutoItem(
            name = "GLOBAL.md",
            detail = "$lineCount lines (full)",
            fileName = "GLOBAL.md",
            content = globalContent,
        ))
    } else {
        items.add(AutoItem(
            name = "GLOBAL.md",
            detail = context.getString(R.string.memory_file_empty),
            fileName = "GLOBAL.md",
            content = "",
        ))
    }

    val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val today = dateFmt.format(Date())
    val yesterday = dateFmt.format(Date(Date().time - 86400_000L))

    for (dateStr in listOf(today, yesterday)) {
        val fileName = "$dateStr.md"
        val label = if (dateStr == today) context.getString(R.string.time_today) else context.getString(R.string.time_yesterday)
        val content = memoryRepository.readFile(fileName)
        if (content.isNotBlank()) {
            val lineCount = content.lines().size
            val injected = minOf(lineCount, 200)
            val detail = if (lineCount > 200) "$injected/$lineCount lines injected" else "$lineCount lines (full)"
            items.add(AutoItem(
                name = "$label — $fileName",
                detail = detail,
                fileName = fileName,
                content = content,
            ))
        }
    }

    return items
}
