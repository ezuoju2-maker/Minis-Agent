package com.openminis.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

object SectionDesign {
    val ScreenHorizontalPadding = 16.dp

    val CardShape = RoundedCornerShape(12.dp)

    val FirstSectionTopGap = 16.dp

    val SectionTopGap = 24.dp

    val HeaderToCardGap = 8.dp

    val CardToFooterGap = 8.dp

    val InterRowGap = 8.dp

    val RowMinHeight = 44.dp

    val TextFieldMinHeight = 56.dp

    val RowHorizontalPadding = 16.dp

    val RowVerticalPadding = 10.dp

    val CardInnerVerticalPadding = 6.dp

    val DividerThickness = 0.5.dp

    val DividerStartInset = 16.dp

    @Composable
    @ReadOnlyComposable
    fun cardColor(): Color = MaterialTheme.colorScheme.surfaceContainerLow

    @Composable
    @ReadOnlyComposable
    fun screenBackgroundColor(): Color = MaterialTheme.colorScheme.background

    @Composable
    @ReadOnlyComposable
    fun dividerColor(): Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)

    @Composable
    @ReadOnlyComposable
    fun footerColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(
            start = SectionDesign.ScreenHorizontalPadding,
            end = SectionDesign.ScreenHorizontalPadding,
            bottom = SectionDesign.HeaderToCardGap,
        ),
    )
}

@Composable
fun SectionFooter(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = SectionDesign.footerColor(),
        modifier = modifier.padding(
            start = SectionDesign.ScreenHorizontalPadding,
            end = SectionDesign.ScreenHorizontalPadding,
            top = SectionDesign.CardToFooterGap,
        ),
    )
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = SectionDesign.cardColor(),
        shape = SectionDesign.CardShape,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SectionDesign.ScreenHorizontalPadding),
    ) {
        Column(
            modifier = Modifier.padding(vertical = SectionDesign.CardInnerVerticalPadding),
            content = content,
        )
    }
}

@Composable
fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = SectionDesign.DividerStartInset),
        thickness = SectionDesign.DividerThickness,
        color = SectionDesign.dividerColor(),
    )
}

@Composable
fun RowLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = 6.dp),
    )
}
