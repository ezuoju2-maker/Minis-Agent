package com.openminis.app.ui.chat

import com.openminis.app.R

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.Color


internal val stepTimestampFormatter: java.text.SimpleDateFormat =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

internal fun formatStepTimestamp(epochMs: Long): String =
    stepTimestampFormatter.format(java.util.Date(epochMs))

internal fun formatStepDuration(seconds: Long, stillRunning: Boolean): String {
    val safe = seconds.coerceAtLeast(0L)
    val base = when {
        safe < 60L -> "${safe}s"
        safe < 3600L -> {
            val m = safe / 60L
            val s = safe % 60L
            if (s == 0L) "${m}m" else "${m}m${s}s"
        }
        else -> {
            val h = safe / 3600L
            val m = (safe % 3600L) / 60L
            if (m == 0L) "${h}h" else "${h}h${m}m"
        }
    }
    return if (stillRunning) "$base…" else base
}

internal fun toolAccentColor(toolName: String): Color = when (toolName) {
    "shell_execute" -> Color(0xFF34C759)
    "file_read" -> Color(0xFF32ADE6)
    "file_write" -> Color(0xFF007AFF)
    "file_edit" -> Color(0xFFFF9500)
    "browser_use" -> Color(0xFF007AFF)
    "read_image" -> Color(0xFFAF52DE)
    "memory_write", "memory_get" -> Color(0xFFFF2D55)
    "web_search" -> Color(0xFF32ADE6)
    "subagent_task", "delegate_task", "agent_status" -> HelperAccentStatic
    else -> Color(0xFF8E8E93)
}

internal fun toolIconFor(toolName: String) = when (toolName) {
    "shell_execute" -> Icons.Default.Terminal
    "file_read" -> Icons.Default.Description
    "file_write" -> Icons.AutoMirrored.Filled.NoteAdd
    "file_edit" -> Icons.Default.EditNote
    "browser_use" -> Icons.Default.Language
    "read_image" -> Icons.Default.Image
    "memory_write", "memory_get" -> Icons.Default.Psychology
    "web_search" -> Icons.Default.Search
    "subagent_task", "delegate_task", "agent_status" -> Icons.Default.Groups
    else -> Icons.Default.Build
}

@androidx.annotation.DrawableRes
internal fun toolIconResFor(toolName: String?): Int = when (toolName) {
    "shell_execute" -> R.drawable.ic_tool_terminal
    "file_read" -> R.drawable.ic_tool_description
    "file_write" -> R.drawable.ic_tool_note_add
    "file_edit" -> R.drawable.ic_tool_edit_note
    "browser_use" -> R.drawable.ic_tool_globe
    "read_image" -> R.drawable.ic_tool_image
    "memory_write", "memory_get" -> R.drawable.ic_tool_psychology
    "web_search" -> R.drawable.ic_tool_search
    "subagent_task", "delegate_task", "agent_status" -> R.drawable.ic_tool_groups
    else -> R.drawable.ic_tool_build
}

@androidx.annotation.ColorInt
internal fun toolAccentColorInt(toolName: String?): Int = when (toolName) {
    "shell_execute" -> 0xFF34C759.toInt()
    "file_read" -> 0xFF32ADE6.toInt()
    "file_write" -> 0xFF007AFF.toInt()
    "file_edit" -> 0xFFFF9500.toInt()
    "browser_use" -> 0xFF007AFF.toInt()
    "read_image" -> 0xFFAF52DE.toInt()
    "memory_write", "memory_get" -> 0xFFFF2D55.toInt()
    "web_search" -> 0xFF32ADE6.toInt()
    "subagent_task", "delegate_task", "agent_status" -> 0xFFAF52DE.toInt()
    else -> 0xFF8E8E93.toInt()
}

internal fun friendlyToolTitleFor(toolName: String?): String = when (toolName) {
    null, "" -> "DeepSeek"
    "shell_execute" -> "Execute Shell"
    "file_read" -> "Read File"
    "file_write" -> "Write File"
    "file_edit" -> "Edit File"
    "browser_use" -> "Browse Web"
    "read_image" -> "Read Image"
    "memory_write" -> "Write Memory"
    "memory_get" -> "Read Memory"
    "web_search" -> "Search Web"
    else -> toolName
        .split('_')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
}

internal fun toolDisplayName(toolName: String): String = when (toolName) {
    "shell_execute" -> "terminal"
    "file_read" -> "file reader"
    "file_write" -> "file writer"
    "file_edit" -> "file editor"
    "browser_use" -> "browser"
    "read_image" -> "image viewer"
    "memory_write" -> "memory"
    "memory_get" -> "memory"
    "web_search" -> "search"
    "subagent_task", "delegate_task", "agent_status" -> "agent"
    else -> toolName
}

internal fun toolTitleLabel(toolName: String): String = when (toolName) {
    "shell_execute" -> "DeepSeek is using Shell"
    "file_read" -> "DeepSeek is reading File"
    "file_write" -> "DeepSeek is using Editor"
    "file_edit" -> "DeepSeek is editing File"
    "browser_use" -> "DeepSeek is using Browser"
    "read_image" -> "DeepSeek is reading Image"
    "memory_write", "memory_get" -> "DeepSeek is using Memory"
    "web_search" -> "DeepSeek is using Search"
    "subagent_task", "delegate_task", "agent_status" -> "DeepSeek is using an Agent"
    else -> "DeepSeek is using ${toolDisplayName(toolName)}"
}

internal fun formatToolDuration(ms: Long): String {
    val seconds = ms / 1000.0
    return when {
        seconds < 1 -> String.format("%.1fs", seconds)
        seconds < 60 -> String.format("%.0fs", seconds)
        else -> {
            val m = (seconds / 60).toInt()
            val s = (seconds % 60).toInt()
            "${m}m ${s}s"
        }
    }
}
