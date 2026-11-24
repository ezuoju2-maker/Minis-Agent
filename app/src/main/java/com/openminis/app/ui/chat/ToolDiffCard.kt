package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.chat.DiffPreviewParser

@Composable
fun ToolDiffCard(rows: List<DiffRow>, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return
    val added = Color(0xFF1B7F3B)
    val removed = Color(0xFFB3261E)
    val context = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        SelectionContainer {
            Column(modifier = Modifier.fillMaxWidth()) {
                for (row in rows.take(200)) {
                    val tint = when (row.marker) {
                        '+' -> added
                        '-' -> removed
                        else -> context
                    }
                    val prefix = "${row.marker}${row.oldNumber ?: ""}/${row.newNumber ?: ""}"
                    Text(
                        text = "$prefix  ${row.text}",
                        color = tint,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
