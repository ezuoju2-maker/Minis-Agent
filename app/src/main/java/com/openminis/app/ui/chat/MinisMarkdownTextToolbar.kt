package com.openminis.app.ui.chat

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.openminis.app.R

internal class MinisMarkdownTextToolbar(
    private val context: Context,
    private val registry: MessageBoundsRegistry,
    private val onAddToInput: ((String) -> Unit)? = null,
    private val onReadAloud: ((String) -> Unit)? = null,
    private val onReadFromStart: ((String) -> Unit)? = null,
    private val isStreamingNow: () -> Boolean = { false },
    internal val selectionController: SelectionController? = null,
    private val onShareChat: ((String?) -> Unit)? = null,
    private val onSearchInChat: ((String) -> Unit)? = null,
) : TextToolbar {

    internal var state by mutableStateOf(ToolbarState())
        private set

    internal val canAddToInput: Boolean get() = onAddToInput != null

    internal val canShareChat: Boolean get() = onShareChat != null

    internal fun shareChat() {
        val sink = onShareChat ?: return
        sink(
            selectionController?.singleMessageId()
                ?: selectionController?.selection?.value?.start?.shard?.messageId,
        )
    }

    override val status: TextToolbarStatus
        get() = if (state.visible) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        state = ToolbarState(
            visible = true,
            rect = rect,
            onCopyRequested = onCopyRequested,
            originatingMarkdown = registry.markdownAt(rect),
            lastShownAtMs = android.os.SystemClock.uptimeMillis(),
        )
    }

    override fun hide() {
        state = state.copy(visible = false)
    }

    internal fun addSelectionToInput() {
        val sink = onAddToInput ?: return
        withSelection(sink)
    }

    internal fun readAloudSelection() {
        val sink = onReadAloud ?: return
        withSelection(sink)
    }

    internal val canReadAloud: Boolean get() = onReadAloud != null

    internal val canSearchInChat: Boolean get() = onSearchInChat != null

    internal fun searchSelectionInChat() {
        val sink = onSearchInChat ?: return
        withSelection(sink)
    }

    internal fun readFromStart() {
        val sink = onReadFromStart ?: return
        val source = state.originatingMarkdown ?: return
        if (source.isNotBlank()) sink(source)
    }

    internal val canReadFromStart: Boolean
        get() = onReadFromStart != null &&
            state.originatingMarkdown != null &&
            !isStreamingNow()

    private fun withSelection(sink: (String) -> Unit) {
        val onCopy = state.onCopyRequested ?: return
        val cm = clipboard()
        val original = runCatching { cm.primaryClip }.getOrNull()
        try {
            onCopy.invoke()
            val selection = runCatching {
                cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            }.getOrNull()?.trim().orEmpty()
            if (selection.isNotEmpty()) sink(selection)
        } finally {
            if (original != null) runCatching { cm.setPrimaryClip(original) }
        }
    }

    private fun clipboard(): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    internal fun copyMarkdown() {
        val md = state.originatingMarkdown ?: return
        MarkdownClipboard.copyMarkdown(context, md)
        toast(R.string.selection_copied_as_markdown_toast)
    }

    internal fun copyRichText() {
        val md = state.originatingMarkdown ?: return
        MarkdownClipboard.copyRichText(context, md)
        toast(R.string.selection_copied_as_rich_text_toast)
    }

    internal fun copyPlainText() {
        val md = state.originatingMarkdown ?: return
        MarkdownClipboard.copyPlain(context, md)
        toast(R.string.selection_copied_as_plain_text_toast)
    }

    private fun toast(resId: Int) {
        Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
    }

    internal data class ToolbarState(
        val visible: Boolean = false,
        val rect: Rect = Rect.Zero,
        val onCopyRequested: (() -> Unit)? = null,
        val originatingMarkdown: String? = null,
        val lastShownAtMs: Long = 0L,
    )
}

@Composable
internal fun MinisMarkdownTextToolbarHost(toolbar: MinisMarkdownTextToolbar) {
    val state = toolbar.state
    if (!state.visible) return
    val anchor = remember(state.rect) { state.rect.toIntRectRounded() }
    val positionProvider = remember(anchor) { FloatingToolbarPositionProvider(anchor) }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = { toolbar.hide() },
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        val barColor = MaterialTheme.colorScheme.surfaceContainerHigh
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = barColor,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            border = androidx.compose.foundation.BorderStroke(
                0.5.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            ),
        ) {
            val toolbarScroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(barColor)
                    .height(40.dp)
                    .horizontalScroll(toolbarScroll),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarButton(label = stringResource(R.string.selection_copy)) {
                    state.onCopyRequested?.invoke()
                    toolbar.hide()
                }
                if (toolbar.canAddToInput) {
                    ToolbarDivider()
                    ToolbarButton(
                        label = stringResource(R.string.selection_add_to_chat_input),
                    ) {
                        toolbar.addSelectionToInput()
                        toolbar.hide()
                    }
                }
                if (toolbar.canSearchInChat) {
                    ToolbarDivider()
                    ToolbarButton(label = stringResource(R.string.chat_search_in_chat)) {
                        toolbar.searchSelectionInChat()
                        toolbar.hide()
                    }
                }
                if (toolbar.canReadAloud) {
                    ToolbarDivider()
                    ToolbarButton(
                        label = stringResource(R.string.selection_read_aloud),
                    ) {
                        toolbar.readAloudSelection()
                        toolbar.hide()
                    }
                }
                if (toolbar.canReadFromStart) {
                    ToolbarDivider()
                    ToolbarButton(
                        label = stringResource(R.string.selection_read_from_start),
                    ) {
                        toolbar.readFromStart()
                        toolbar.hide()
                    }
                }
                if (state.originatingMarkdown != null) {
                    ToolbarDivider()
                    ToolbarButton(label = stringResource(R.string.selection_copy_full_markdown)) {
                        toolbar.copyMarkdown()
                        toolbar.hide()
                    }
                    ToolbarDivider()
                    ToolbarButton(label = stringResource(R.string.selection_copy_full_rich_text)) {
                        toolbar.copyRichText()
                        toolbar.hide()
                    }
                    ToolbarDivider()
                    ToolbarButton(label = stringResource(R.string.selection_copy_full_plain_text)) {
                        toolbar.copyPlainText()
                        toolbar.hide()
                    }
                }
                val tableActions = toolbar.selectionController?.selectionTableActions()
                if (tableActions != null) {
                    ToolbarDivider()
                    ToolbarButton(label = stringResource(R.string.markdown_table_copy_table)) {
                        tableActions.copyTableMarkdown()
                        toolbar.hide()
                    }
                    ToolbarDivider()
                    ToolbarButton(label = stringResource(R.string.markdown_table_copy_table_image)) {
                        tableActions.copyTableImage()
                        toolbar.hide()
                    }
                }
                if (toolbar.canShareChat) {
                    ToolbarDivider()
                    ToolbarButton(label = stringResource(R.string.chat_share_share_chat)) {
                        toolbar.hide()
                        toolbar.shareChat()
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarButton(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun ToolbarDivider() {
    Box(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .width(0.5.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    )
}

private fun Rect.toIntRectRounded(): IntRect = IntRect(
    left = left.toInt(),
    top = top.toInt(),
    right = right.toInt(),
    bottom = bottom.toInt(),
)

private class FloatingToolbarPositionProvider(
    private val anchor: IntRect,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val gap = 8
        val centerX = anchor.left + anchor.width / 2
        val x = (centerX - popupContentSize.width / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val aboveY = anchor.top - popupContentSize.height - gap
        val y = if (aboveY >= 0) {
            aboveY
        } else {
            (anchor.bottom + gap)
                .coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0))
        }
        return IntOffset(x, y)
    }
}
