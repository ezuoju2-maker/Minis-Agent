package com.openminis.app.ui.quota

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.quota.QuotaLevel
import com.openminis.app.quota.QuotaLowRules
import com.openminis.app.quota.QuotaPickerHintRules
import com.openminis.app.quota.QuotaStatus
import com.openminis.app.quota.QuotaStore
import com.openminis.app.quota.QuotaTone
import com.openminis.app.quota.QuotaToneRules
import com.openminis.app.ui.theme.LocalChatPalette


object QuotaTagStyle {
    @Composable
    fun background(level: QuotaLevel): Color {
        val dark = LocalChatPalette.current.isDark
        return when (level) {
            QuotaLevel.normal -> MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.12f else 0.08f)
            QuotaLevel.low -> if (dark) Color(0xFFFFD60A) else Color(0xFFFFCC00)
            QuotaLevel.depleted -> if (dark) Color(0xFFFF453A) else Color(0xFFFF3B30)
        }
    }

    @Composable
    fun foreground(level: QuotaLevel, normal: Color): Color = when (level) {
        QuotaLevel.normal -> normal
        QuotaLevel.low -> Color.Black
        QuotaLevel.depleted -> Color.White
    }
}

@Composable
fun QuotaSectionHeaderBadge(instanceId: String, onTap: (() -> Unit)?) {
    QuotaWatch(instanceId, "pickerAppeared")
    val st by QuotaStore.state.collectAsState()
    val strings = rememberQuotaStrings()
    val locale = currentLocale()
    val context = LocalContext.current
    val head = st.headline(instanceId) ?: return
    val text = QuotaPickerHintRules.headerText(head.first, head.second, locale, strings) ?: return
    val tone = QuotaToneRules.pillTone(head.second)
    val level = st.subjectLevel(instanceId)
    val fg = QuotaTagStyle.foreground(level, tone.color())
    val label = context.getString(R.string.quota_account_quota_a11y, text)
    val hint = context.getString(R.string.quota_shows_details)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .clip(CircleShape)
            .background(QuotaTagStyle.background(level))
            .then(if (onTap != null) Modifier.clickable(role = Role.Button, onClick = onTap) else Modifier)
            .semantics {
                contentDescription = label
                if (onTap != null) onClick(label = hint) { onTap(); true }
            }
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Medium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
        )
        if (onTap != null) {
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = fg, modifier = Modifier.size(10.dp))
        }
    }
}

@Composable
fun ModelQuotaMemberPill(instanceId: String, modelId: String, onTap: () -> Unit) {
    QuotaWatch(instanceId, "groupMemberAppeared")
    val st by QuotaStore.state.collectAsState()
    val strings = rememberQuotaStrings()
    val locale = currentLocale()
    val context = LocalContext.current
    val pill = QuotaPickerHintRules.memberPill(st.modelView(instanceId, modelId), st.pools, locale, strings) ?: return
    val fg = QuotaTagStyle.foreground(pill.level, pill.tone.color())
    val spoken = memberPillSpoken(context, pill)
    val hint = context.getString(R.string.quota_shows_details)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .clip(CircleShape)
            .background(QuotaTagStyle.background(pill.level))
            .clickable(role = Role.Button, onClick = onTap)
            .semantics {
                contentDescription = spoken
                onClick(label = hint) { onTap(); true }
            }
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(
            pill.text,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Medium,
            color = fg,
            maxLines = 1,
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
        )
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = fg, modifier = Modifier.size(8.dp))
    }
}

private fun memberPillSpoken(context: android.content.Context, p: QuotaPickerHintRules.MemberPill): String {
    var value = p.text
    when (p.level) {
        QuotaLevel.normal -> Unit
        QuotaLevel.low -> value += context.getString(R.string.quota_low_suffix)
        QuotaLevel.depleted -> value += ", " + context.getString(R.string.quota_used_up)
    }
    return if (p.isModelSpecific) {
        context.getString(R.string.quota_model_own_a11y, value)
    } else {
        context.getString(R.string.quota_account_quota_a11y, value)
    }
}

@Composable
fun ModelQuotaHintView(instanceId: String, modelId: String) {
    val st by QuotaStore.state.collectAsState()
    val context = LocalContext.current
    val hint = QuotaPickerHintRules.modelRow(st.modelView(instanceId, modelId)) ?: return
    val word = hint.detail ?: statusWord(context, hint.status)
    val color = QuotaToneRules.tone(hint.status, com.openminis.app.quota.QuotaFreshness.fresh, false).color()
    val spoken = context.getString(R.string.quota_model_own_a11y, word)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        Icon(
            if (hint.status == QuotaStatus.rateLimited) Icons.Filled.Timer else Icons.Filled.Error,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(10.dp),
        )
        Text(
            word,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Medium,
            color = color,
            maxLines = 1,
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
        )
    }
}

private fun statusWord(context: android.content.Context, s: QuotaStatus): String = when (s) {
    QuotaStatus.exhausted -> context.getString(R.string.quota_used_up)
    QuotaStatus.rateLimited -> context.getString(R.string.quota_status_rate_limited)
    QuotaStatus.low -> context.getString(R.string.quota_low)
    QuotaStatus.available, QuotaStatus.unknown -> ""
}


@Composable
fun rememberQuotaLow(isAuthed: Boolean, instanceId: String?, modelId: String?): Boolean {
    QuotaWatch(instanceId, "chatNavBar")
    val st by QuotaStore.state.collectAsState()
    if (!isAuthed || instanceId == null || modelId == null) return false
    return QuotaLowRules.isLow(st.modelView(instanceId, modelId), st.pools)
}

fun quotaDotColor(isAuthed: Boolean, quotaLow: Boolean): Color = when {
    !isAuthed -> Color(0xFFFF9500)
    quotaLow -> Color(0xFFFFCC00)
    else -> Color(0xFF34C759)
}

@Composable
fun ModelQuotaStatusDot(
    isAuthed: Boolean,
    activeEntryId: kotlinx.coroutines.flow.StateFlow<String?>,
    providerRepository: com.openminis.app.data.repository.ProviderRepository,
) {
    val entryId by activeEntryId.collectAsState()
    val config by providerRepository.config.collectAsState()
    val entry = entryId?.let { id -> config.modelEntries.find { it.id == id } }
    val low = rememberQuotaLow(isAuthed, entry?.providerInstanceId, entry?.model?.id)
    val lowLabel = if (low) LocalContext.current.getString(R.string.quota_low) else null
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(6.dp)
            .background(quotaDotColor(isAuthed, low), CircleShape)
            .then(if (lowLabel != null) Modifier.semantics { contentDescription = lowLabel } else Modifier),
    )
}
