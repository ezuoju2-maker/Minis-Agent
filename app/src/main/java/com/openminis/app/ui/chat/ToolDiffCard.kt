package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ToolDiffCard(
    rows: List<DiffRow>,
    modifier: Modifier = Modifier,
    selected: Set<Int>? = null,
    onToggleGroup: ((Int) -> Unit)? = null,
    onApply: (() -> Unit)? = null,
    applyLabel: String = "只保留选中的段",
) {
    if (rows.isEmpty()) return
    val added = Color(0xFF1B7F3B)
    val removed = Color(0xFFB3261E)
    val context = MaterialTheme.colorScheme.onSurfaceVariant
    val groups = DiffPreviewParser.groups(rows)
    val hunkIds = groups.distinct()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (selected != null && onApply != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = com.openminis.app.tools.patch.HunkArgs.describe(selected, hunkIds.size),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onApply,
                    enabled = !com.openminis.app.tools.patch.HunkArgs.isNoop(selected),
                ) {
                    Text(applyLabel, fontSize = 11.sp)
                }
            }
        }
        SelectionContainer {
            Column(modifier = Modifier.fillMaxWidth()) {
                var lastGroup = -1
                for ((index, row) in rows.take(200).withIndex()) {
                    val g = groups.getOrElse(index) { 0 }
                    if (g != lastGroup) {
                        if (lastGroup >= 0) {
                            Text(
                                text = "第 ${g + 1} 段",
                                color = context,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                            )
                        }
                        lastGroup = g
                    }
                    val tint = when (row.marker) {
                        '+' -> added
                        '-' -> removed
                        else -> context
                    }
                    val prefix = "${row.marker}${row.oldNumber ?: ""}/${row.newNumber ?: ""}"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selected != null && onToggleGroup != null) {
                            Checkbox(
                                checked = g in selected,
                                onCheckedChange = { onToggleGroup(g) },
                                modifier = Modifier.padding(end = 2.dp),
                            )
                        }
                        Text(
                            text = "$prefix  ${row.text}",
                            color = tint,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
