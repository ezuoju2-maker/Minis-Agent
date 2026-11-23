package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.theme.ChatColors
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class PastedText(
    val id: Int,
    val text: String,
) {
    val placeholder: String get() = placeholderFor(id)

    val preview: String
        get() = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty().take(60)

    companion object {
        fun placeholderFor(id: Int): String = "[Pasted#$id]"
    }
}

private val PLACEHOLDER_REGEX =
    Regex("""\[[Pp]asted#(\d+)]""")

fun expandPastePlaceholders(
    text: String,
    buffer: List<PastedText>,
): Pair<String, Set<Int>> {
    if (text.isEmpty() || buffer.isEmpty()) return text to emptySet()
    val byId = buffer.associateBy { it.id }
    val consumed = mutableSetOf<Int>()
    val out = StringBuilder(text.length)
    var cursor = 0
    for (match in PLACEHOLDER_REGEX.findAll(text)) {
        val id = match.groupValues[1].toIntOrNull()
        val entry = id?.let { byId[it] } ?: continue
        out.append(text, cursor, match.range.first)
        out.append(entry.text)
        cursor = match.range.last + 1
        consumed += entry.id
    }
    if (consumed.isEmpty()) return text to emptySet()
    out.append(text, cursor, text.length)
    return out.toString() to consumed
}

sealed class PasteChunk {
    data class Text(val value: String) : PasteChunk()

    data class Pasted(val id: Int) : PasteChunk()
}

fun splitPastePlaceholders(
    text: String,
    buffer: List<PastedText>,
): Pair<List<PasteChunk>, Set<Int>> {
    if (text.isEmpty() || buffer.isEmpty()) {
        return listOf<PasteChunk>(PasteChunk.Text(text)).filterNot {
            it is PasteChunk.Text && it.value.isEmpty()
        } to emptySet()
    }
    val byId = buffer.associateBy { it.id }
    val consumed = mutableSetOf<Int>()
    val chunks = mutableListOf<PasteChunk>()
    var cursor = 0
    for (match in PLACEHOLDER_REGEX.findAll(text)) {
        val id = match.groupValues[1].toIntOrNull()
        if (id == null || !byId.containsKey(id)) continue
        val before = text.substring(cursor, match.range.first)
        if (before.isNotEmpty()) chunks.add(PasteChunk.Text(before))
        chunks.add(PasteChunk.Pasted(id))
        cursor = match.range.last + 1
        consumed += id
    }
    if (consumed.isEmpty()) {
        return listOf<PasteChunk>(PasteChunk.Text(text)).filterNot {
            it is PasteChunk.Text && it.value.isEmpty()
        } to emptySet()
    }
    val tail = text.substring(cursor)
    if (tail.isNotEmpty()) chunks.add(PasteChunk.Text(tail))
    return chunks to consumed
}

object PastedMedia {
    const val PASTED_FILENAME_PREFIX = "Pasted#"

    const val MIME = "text/plain"

    const val MISSING_PLACEHOLDER =
        "[pasted content unavailable — the stored text file is missing]"

    fun fileNameFor(id: Int): String = "$PASTED_FILENAME_PREFIX$id.txt"

    fun isPastedRef(mimeType: String?, originalFileName: String?): Boolean =
        mimeType == MIME &&
            originalFileName != null &&
            originalFileName.startsWith(PASTED_FILENAME_PREFIX)
}

const val PASTE_AS_FILE_THRESHOLD = 15_000

fun isLongPastedText(inserted: String): Boolean {
    val asciiLetters = inserted.count { it in 'A'..'Z' || it in 'a'..'z' }
    val englishDominant = asciiLetters > inserted.length / 2
    return if (englishDominant) {
        inserted.split(' ', '\n', '\t', '\r').count { it.isNotEmpty() } > 1000
    } else {
        inserted.length > 1200
    }
}

fun foldLongPasteIfNeeded(
    old: TextFieldValue,
    new: TextFieldValue,
    stash: (String) -> String,
): TextFieldValue {
    val oldText = old.text
    val newText = new.text
    val delta = newText.length - oldText.length
    if (delta <= 0) return new

    var prefix = 0
    val maxPrefix = minOf(oldText.length, newText.length)
    while (prefix < maxPrefix && oldText[prefix] == newText[prefix]) prefix++

    var suffix = 0
    val maxSuffix = minOf(oldText.length - prefix, newText.length - prefix)
    while (
        suffix < maxSuffix &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
    ) suffix++

    val insertedStart = prefix
    val insertedEnd = newText.length - suffix
    if (insertedEnd <= insertedStart) return new
    val inserted = newText.substring(insertedStart, insertedEnd)
    if (!isLongPastedText(inserted)) return new

    val marker = stash(inserted)
    val folded = newText.substring(0, insertedStart) + marker + newText.substring(insertedEnd)
    val caret = insertedStart + marker.length
    return TextFieldValue(
        text = folded,
        selection = TextRange(caret),
    )
}


@Composable
fun PastedTextChip(
    pasted: PastedText,
    onRemove: () -> Unit,
) {
    var showPreview by remember(pasted.id) { mutableStateOf(false) }
    val chipShape = RoundedCornerShape(8.dp)
    Box(modifier = Modifier.size(width = 72.dp, height = 70.dp)) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(64.dp)
                .clip(chipShape)
                .background(MaterialTheme.colorScheme.surfaceVariant, chipShape)
                .border(1.dp, ChatColors.thumbnailBorder, chipShape)
                .clickable { showPreview = true },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.TextSnippet,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "#${pasted.id}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = "${pasted.text.length}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .border(0.5.dp, ChatColors.thumbnailBorder, CircleShape)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(13.dp),
            )
        }
    }

    if (showPreview) {
        PastedTextPreviewDialog(pasted = pasted, onDismiss = { showPreview = false })
    }
}

@Composable
private fun PastedTextPreviewDialog(
    pasted: PastedText,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "${pasted.placeholder}  ·  ${pasted.text.length}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = pasted.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            }
        }
    }
}
