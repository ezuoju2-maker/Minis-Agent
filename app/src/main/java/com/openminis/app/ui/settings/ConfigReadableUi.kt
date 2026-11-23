package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.config.audit.ConfigAuditEntry
import com.openminis.app.config.audit.ConfigAuditStatus
import com.openminis.app.config.confirm.PendingConfigChangeItem
import com.openminis.app.config.readable.ConfigReadableStrings
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener


@Composable
internal fun rememberReadableLocalizer(): (String) -> String? {
    val context = LocalContext.current
    return remember(context) { { key: String -> ConfigReadableStrings.lookup(context, key) } }
}

@Composable
internal fun ConfigReadableDivider() {
    val onePx = with(LocalDensity.current) { 1f.toDp() }
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 2.dp),
        thickness = onePx,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

internal fun configSummaryLines(items: List<PendingConfigChangeItem>, localize: (String) -> String?): List<String> {
    val bySubject = LinkedHashMap<String, MutableList<String>>()
    for (item in items) {
        if (!item.isApproved) continue
        val r = item.readable ?: continue
        bySubject.getOrPut(r.subject ?: "~global") { ArrayList() }.addAll(r.displayText(localize).split("\n"))
    }
    return bySubject.values.flatten().filter { it.isNotEmpty() }
}

@Composable
internal fun configRevertMessage(entry: ConfigAuditEntry, localize: (String) -> String?): String {
    val readable = entry.readable
    if (readable != null) {
        return stringResource(R.string.config_audit_revert_undoes) + "\n" + readable.displayText(localize)
    }
    return "${entry.key}\n${prettyConfigJSON(entry.newValueJSON)} → ${prettyConfigJSON(entry.oldValueJSON)}"
}

internal fun prettyConfigJSON(json: String): String {
    if (json.isEmpty()) return "—"
    val v = try {
        JSONTokener(json).nextValue()
    } catch (_: Exception) {
        return json
    }
    return when (v) {
        is String -> v
        is JSONObject, is JSONArray -> pretty(v, 0)
        else -> json
    }
}

private fun pretty(v: Any?, depth: Int): String {
    val pad = "  ".repeat(depth + 1)
    val end = "  ".repeat(depth)
    return when (v) {
        is JSONObject -> {
            if (v.length() == 0) return "{}"
            val keys = v.keys().asSequence().toList().sorted()
            keys.joinToString(",\n", "{\n", "\n$end}") { k -> "$pad${JSONObject.quote(k)} : ${pretty(v.opt(k), depth + 1)}" }
        }
        is JSONArray -> {
            if (v.length() == 0) return "[]"
            (0 until v.length()).joinToString(",\n", "[\n", "\n$end]") { i -> pad + pretty(v.opt(i), depth + 1) }
        }
        is String -> JSONObject.quote(v).replace("\\/", "/")
        null, JSONObject.NULL -> "null"
        else -> v.toString()
    }
}

@Composable
internal fun ConfigAuditStatusBadge(status: ConfigAuditStatus) {
    val color = when (status) {
        ConfigAuditStatus.APPLIED -> Color(0xFF388E3C)
        ConfigAuditStatus.REJECTED -> Color(0xFF757575)
        ConfigAuditStatus.TIMEOUT -> Color(0xFFE65100)
        ConfigAuditStatus.REVERTED -> Color(0xFF8E5CD9)
    }
    val label = when (status) {
        ConfigAuditStatus.APPLIED -> stringResource(R.string.logs_config_status_applied)
        ConfigAuditStatus.REJECTED -> stringResource(R.string.logs_config_status_rejected)
        ConfigAuditStatus.TIMEOUT -> stringResource(R.string.logs_config_status_timeout)
        ConfigAuditStatus.REVERTED -> stringResource(R.string.logs_config_status_reverted)
    }
    Box(
        modifier = Modifier
            .background(color, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
            color = Color.White,
        )
    }
}
