package com.openminis.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal object EditorCaret {

    fun imeOverlap(editorBottomInWindow: Float, windowHeight: Int, imeBottom: Int, editorHeight: Int, minVisible: Int): Int {
        if (imeBottom <= 0 || windowHeight <= 0 || editorHeight <= 0) return 0
        val keyboardTop = windowHeight - imeBottom
        val covered = (editorBottomInWindow - keyboardTop).toInt()
        return covered.coerceIn(0, (editorHeight - minVisible).coerceAtLeast(0))
    }

    fun revealRect(cursor: Rect, margin: Float): Rect =
        Rect(cursor.left, (cursor.top - margin).coerceAtLeast(0f), cursor.right, cursor.bottom + margin)

    fun clamp(selection: TextRange, length: Int): TextRange =
        TextRange(selection.start.coerceIn(0, length), selection.end.coerceIn(0, length))
}

@Composable
fun DocumentEditorField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LocalTextStyle.current,
    placeholder: String? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    outlined: Boolean = false,
    ownScroll: Boolean = true,
    minHeight: Dp = 0.dp,
) {
    var held by remember { mutableStateOf(TextFieldValue(value)) }
    val field = if (held.text == value) held else held.copy(text = value, selection = EditorCaret.clamp(held.selection, value.length))

    val density = LocalDensity.current
    val requester = remember { BringIntoViewRequester() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var focused by remember { mutableStateOf(false) }
    val imeBottom = WindowInsets.ime.getBottom(density)

    var editorBottom by remember { mutableFloatStateOf(0f) }
    var editorHeight by remember { mutableIntStateOf(0) }
    val windowHeight = LocalView.current.rootView.height
    val overlap = if (ownScroll) {
        EditorCaret.imeOverlap(editorBottom, windowHeight, imeBottom, editorHeight, with(density) { MIN_VISIBLE.roundToPx() })
    } else {
        0
    }
    val viewport = editorHeight - overlap

    val margin = with(density) { CARET_MARGIN.toPx() }
    LaunchedEffect(focused, field.selection, imeBottom, viewport, layout?.size) {
        if (!focused) return@LaunchedEffect
        val result = layout ?: return@LaunchedEffect
        val caret = field.selection.end.coerceIn(0, result.layoutInput.text.length)
        requester.bringIntoView(EditorCaret.revealRect(result.getCursorRect(caret), margin))
    }

    val border = if (outlined) {
        Modifier.border(
            width = if (focused) 2.dp else 1.dp,
            color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            shape = MaterialTheme.shapes.extraSmall,
        )
    } else {
        Modifier
    }
    val mergedStyle = LocalTextStyle.current.merge(textStyle)
    val textColor = if (mergedStyle.color.isSpecified()) mergedStyle.color else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .onGloballyPositioned {
                editorHeight = it.size.height
                editorBottom = it.positionInWindow().y + it.size.height
            },
    ) {
        val scroller = if (ownScroll) {
            Modifier
                .fillMaxSize()
                .padding(bottom = with(density) { overlap.toDp() })
                .then(border)
                .verticalScroll(rememberScrollState())
        } else {
            Modifier.fillMaxWidth().then(border)
        }
        Box(modifier = scroller) {
            val least = if (ownScroll) with(density) { viewport.coerceAtLeast(0).toDp() } else minHeight
            BasicTextField(
                value = field,
                onValueChange = {
                    held = it
                    if (it.text != value) onValueChange(it.text)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = least)
                    .padding(contentPadding)
                    .bringIntoViewRequester(requester)
                    .onFocusChanged { focused = it.isFocused },
                textStyle = mergedStyle.copy(color = textColor),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                onTextLayout = { layout = it },
            )
            if (value.isEmpty() && placeholder != null) {
                Text(
                    placeholder,
                    style = mergedStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(contentPadding),
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.Color.isSpecified(): Boolean = this != androidx.compose.ui.graphics.Color.Unspecified

private val CARET_MARGIN = 24.dp

private val MIN_VISIBLE = 96.dp
