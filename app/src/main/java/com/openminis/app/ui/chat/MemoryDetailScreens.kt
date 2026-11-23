package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.i18n.uppercaseForDisplay


@Composable
fun MemoryFileViewerBody(
    initialContent: String,
    isEditing: Boolean,
    editedContent: String,
    onEditedContentChange: (String) -> Unit,
    showSavedToast: Boolean,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (isEditing) {
            com.openminis.app.ui.components.DocumentEditorField(
                value = editedContent,
                onValueChange = onEditedContentChange,
                modifier = Modifier.fillMaxSize(),
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = ChatColors.primaryText,
                ),
            )
        } else {
            SelectionContainer(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = initialContent.ifEmpty { "(empty)" },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ChatColors.primaryText,
                    lineHeight = 18.sp,
                )
            }
        }

        if (showSavedToast) SavedToast(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun SavedToast(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(bottom = 12.dp)
            .background(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = RoundedCornerShape(50),
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = stringResource(R.string.memory_save_toast),
            color = MaterialTheme.colorScheme.inverseOnSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun MemoryWriteDetailBody(
    record: MemoryToolRecord,
    isEditing: Boolean,
    editedContent: String,
    onEditedContentChange: (String) -> Unit,
    showSavedToast: Boolean,
) {
    val written = record.writtenContent ?: ""

    Box(modifier = Modifier.fillMaxSize()) {
        if (isEditing) {
            com.openminis.app.ui.components.DocumentEditorField(
                value = editedContent,
                onValueChange = onEditedContentChange,
                modifier = Modifier.fillMaxSize(),
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = ChatColors.primaryText,
                ),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (written.isNotEmpty()) {
                    SectionHeader(text = stringResource(R.string.memory_section_written_content))
                    SelectionContainer {
                        Text(
                            text = written,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = ChatColors.primaryText,
                            lineHeight = 18.sp,
                        )
                    }
                }

                SectionHeader(text = stringResource(R.string.memory_section_tool_result))
                SelectionContainer {
                    Text(
                        text = record.output,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = ChatColors.secondaryText,
                        lineHeight = 18.sp,
                    )
                }
            }
        }

        if (showSavedToast) SavedToast(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
fun MemoryGetDetailBody(record: MemoryToolRecord) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val keywords = record.keywords
        if (!keywords.isNullOrEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = ChatColors.secondaryText,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = keywords,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ChatColors.primaryText,
                )
            }
        }
        SelectionContainer {
            Text(
                text = record.output,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = ChatColors.primaryText,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercaseForDisplay(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
        color = ChatColors.secondaryText,
    )
}

@Composable
fun RevokeConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_revoke_dialog_title)) },
        text = { Text(stringResource(R.string.memory_revoke_dialog_message)) },
        confirmButton = {
            MinisTextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.memory_action_revoke),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            MinisTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
fun MutationResultDialog(
    result: MemoryRepository.EntryMutationResult,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val msg = when (result) {
        is MemoryRepository.EntryMutationResult.Success ->
            ctx.getString(R.string.memory_revoke_result_removed, result.dateStr)
        is MemoryRepository.EntryMutationResult.NotFound ->
            ctx.getString(R.string.memory_revoke_result_not_found)
        is MemoryRepository.EntryMutationResult.IOError ->
            ctx.getString(R.string.memory_revoke_result_io_error, result.message)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(msg) },
        confirmButton = {
            MinisTextButton(onClick = onDismiss) { Text("OK") }
        },
    )
}
