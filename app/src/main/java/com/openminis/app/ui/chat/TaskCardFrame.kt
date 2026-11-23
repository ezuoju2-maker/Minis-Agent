package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.util.expandToggleClickable

internal object TaskCardMetrics {
    val corner = 12.dp
    val outerVertical = 4.dp
    val paddingHorizontal = 14.dp
    val paddingVertical = 12.dp
    val iconDisc = 34.dp
    val iconGlyph = 13.dp
    val iconGap = 12.dp
    val belowGap = 10.dp
    val rowSpacing = 5.dp
    val chipGap = 6.dp
    val titleSize = 13.sp
    val titleLineHeight = 14.sp
    val subtitleSize = 11.sp
    val subtitleLineHeight = 12.sp
    val chipSize = 10.sp
    val chipLineHeight = 11.sp

    val actionDisc = 24.dp
    val actionGlyph = 14.dp
    val chevron = 16.dp
}

@Composable
internal fun TaskCardFrame(
    accent: Color,
    onClick: () -> Unit,
    onClickLabel: String?,
    modifier: Modifier = Modifier,
    expandsInPlace: Boolean = false,
    icon: @Composable () -> Unit,
    action: (@Composable () -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(TaskCardMetrics.corner)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = TaskCardMetrics.outerVertical)
            .clip(shape)
            .background(accent.copy(alpha = 0.07f))
            .border(0.8.dp, accent.copy(alpha = 0.35f), shape)
            .then(
                if (expandsInPlace) {
                    Modifier.expandToggleClickable(onClickLabel = onClickLabel, pressedShape = RectangleShape, onClick = onClick)
                } else {
                    Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick)
                },
            )
            .padding(horizontal = TaskCardMetrics.paddingHorizontal, vertical = TaskCardMetrics.paddingVertical),
    ) {
        TaskCardHeadRow(accent, icon, action, content)
        if (below != null) {
            Column(
                modifier = Modifier.padding(start = TaskCardMetrics.iconDisc + TaskCardMetrics.iconGap, top = TaskCardMetrics.belowGap),
                content = below,
            )
        }
    }
}

@Composable
private fun TaskCardHeadRow(
    accent: Color,
    icon: @Composable () -> Unit,
    action: (@Composable () -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(TaskCardMetrics.iconDisc).clip(CircleShape).background(accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.width(TaskCardMetrics.iconGap))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(TaskCardMetrics.rowSpacing),
            content = content,
        )
        Spacer(Modifier.width(4.dp))
        if (action != null) {
            action()
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = ChatColors.tertiaryText,
            modifier = Modifier.size(TaskCardMetrics.chevron),
        )
    }
}

@Composable
private fun cjkLineHeight(fontSize: TextUnit, lineHeight: TextUnit, weight: FontWeight): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val base = LocalTextStyle.current
    return remember(fontSize, lineHeight, weight, density, base) {
        val style = base.merge(TextStyle(fontSize = fontSize, lineHeight = lineHeight, fontWeight = weight))
        with(density) { measurer.measure("\u56FDAg", style, maxLines = 1).size.height.toDp() }
    }
}

@Composable
internal fun TaskCardTitleRow(content: @Composable RowScope.() -> Unit) {
    val title = cjkLineHeight(TaskCardMetrics.titleSize, TaskCardMetrics.titleLineHeight, FontWeight.Medium)
    val chip = cjkLineHeight(TaskCardMetrics.chipSize, TaskCardMetrics.chipLineHeight, FontWeight.SemiBold) + 2.dp
    Row(
        modifier = Modifier.heightIn(min = maxOf(title, chip)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TaskCardMetrics.chipGap),
        content = content,
    )
}

@Composable
internal fun TaskCardStatusRow(content: @Composable RowScope.() -> Unit) {
    val h = taskCardStatusLineHeight()
    Row(
        modifier = Modifier.heightIn(min = h),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
internal fun taskCardStatusLineHeight(): Dp =
    cjkLineHeight(TaskCardMetrics.subtitleSize, TaskCardMetrics.subtitleLineHeight, FontWeight.SemiBold)

@Composable
internal fun TaskCardChip(text: String, accent: Color) {
    Text(
        text,
        fontSize = TaskCardMetrics.chipSize,
        lineHeight = TaskCardMetrics.chipLineHeight,
        fontWeight = FontWeight.SemiBold,
        color = accent,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
internal fun TaskCardTitle(text: String) {
    Text(
        text,
        fontSize = TaskCardMetrics.titleSize,
        lineHeight = TaskCardMetrics.titleLineHeight,
        fontWeight = FontWeight.Medium,
        color = ChatColors.primaryText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun TaskCardActionDisc(
    label: String,
    background: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
    glyph: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.size(TaskCardMetrics.actionDisc).clip(CircleShape).background(background)
            .clickable(enabled = enabled, onClickLabel = label) { onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { glyph() }
}

@Composable
internal fun TaskCardActionGlyph(icon: ImageVector, tint: Color = Color.White) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(TaskCardMetrics.actionGlyph))
}
