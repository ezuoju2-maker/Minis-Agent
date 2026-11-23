package com.openminis.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.lazy.LazyListState
import kotlin.math.abs

fun Modifier.minisTextKitSelectionGesture(
    controller: SelectionController,
    listState: LazyListState,
    rootCoordinates: () -> LayoutCoordinates?,
    reverseLayout: Boolean = false,
    onLongPressEngaged: () -> Unit = {},
): Modifier = composed {
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
    pointerInput(controller, listState, reverseLayout) {
    val longPressTimeoutMs = android.view.ViewConfiguration.getLongPressTimeout().toLong()
    val touchSlopPx = viewConfiguration.touchSlop
    val doubleTapTimeoutMs = android.view.ViewConfiguration.getDoubleTapTimeout().toLong()
    var lastMouseClickUptimeMs = 0L
    var lastMouseClickPoint = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val downLocal = down.position
        var lastWindowPoint = localToWindow(downLocal, rootCoordinates)

        if (down.type == PointerType.Mouse) {
            val handled = handleMouseGesture(
                controller = controller,
                down = down,
                downLocal = downLocal,
                downWindowPoint = lastWindowPoint,
                rootCoordinates = rootCoordinates,
                touchSlopPx = touchSlopPx,
                doubleTapTimeoutMs = doubleTapTimeoutMs,
                lastClickUptimeMs = lastMouseClickUptimeMs,
                lastClickPoint = lastMouseClickPoint,
            )
            lastMouseClickUptimeMs = handled.clickUptimeMs
            lastMouseClickPoint = handled.clickPoint
            return@awaitEachGesture
        }


        val abortReason = withTimeoutOrNull(longPressTimeoutMs) {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val change = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull "lost"
                val pos = change.position
                lastWindowPoint = localToWindow(pos, rootCoordinates)
                if (!change.pressed) {
                    if (controller.selection.value != null &&
                        controller.hitTestStrict(lastWindowPoint) != null
                    ) {
                        controller.clearSelection()
                    }
                    return@withTimeoutOrNull "tap"
                }
                if ((pos - downLocal).getDistance() >= touchSlopPx) {
                    return@withTimeoutOrNull "scroll"
                }
            }
            @Suppress("UNREACHABLE_CODE") "unreached"
        }
        if (abortReason != null) return@awaitEachGesture

        val hit = controller.hitTestStrict(lastWindowPoint) ?: return@awaitEachGesture
        controller.selectionFromMouse.value = false
        controller.beginSelectionWord(hit)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        onLongPressEngaged()
        down.consume()

        var dragStarted = false
        try {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val change = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed) return@awaitEachGesture
                val movedPx = (change.position - downLocal).getDistance()
                if (!dragStarted && movedPx < touchSlopPx) {
                    change.consume()
                    continue
                }
                dragStarted = true
                lastWindowPoint = localToWindow(change.position, rootCoordinates)
                controller.dragIntent.value = SelectionController.DragIntent(
                    point = lastWindowPoint,
                    handle = SelectionController.Handle.End,
                )
                change.consume()
            }
        } finally {
            controller.dragIntent.value = null
        }
    }
    }
}

private data class MouseGestureOutcome(
    val clickUptimeMs: Long,
    val clickPoint: Offset,
)

private val MOUSE_NO_CLICK = MouseGestureOutcome(0L, Offset.Zero)

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleMouseGesture(
    controller: SelectionController,
    down: androidx.compose.ui.input.pointer.PointerInputChange,
    downLocal: Offset,
    downWindowPoint: Offset,
    rootCoordinates: () -> LayoutCoordinates?,
    touchSlopPx: Float,
    doubleTapTimeoutMs: Long,
    lastClickUptimeMs: Long,
    lastClickPoint: Offset,
): MouseGestureOutcome {
    controller.dismissContextMenu()

    if (currentEvent.buttons.isSecondaryPressed) {
        val hit = controller.hitTestStrict(downWindowPoint) ?: return MOUSE_NO_CLICK
        if (!controller.selectionContains(hit)) {
            controller.selectionFromMouse.value = true
            controller.beginSelectionWord(hit)
        }
        controller.requestContextMenu(downWindowPoint)
        down.consume()
        consumeUntilRelease(down.id)
        return MOUSE_NO_CLICK
    }

    val isDoubleClick = lastClickUptimeMs != 0L &&
        (down.uptimeMillis - lastClickUptimeMs) <= doubleTapTimeoutMs &&
        (downLocal - lastClickPoint).getDistance() <= touchSlopPx * 2
    if (isDoubleClick) {
        val hit = controller.hitTestStrict(downWindowPoint)
        if (hit != null) {
            controller.selectionFromMouse.value = true
            controller.beginSelectionWord(hit)
            down.consume()
            consumeUntilRelease(down.id)
            return MOUSE_NO_CLICK
        }
    }

    var dragging = false
    while (true) {
        val ev = awaitPointerEvent(PointerEventPass.Initial)
        val change = ev.changes.firstOrNull { it.id == down.id }
            ?: return MOUSE_NO_CLICK
        val windowPoint = localToWindow(change.position, rootCoordinates)

        if (!change.pressed) {
            if (dragging) {
                controller.dragIntent.value = null
                return MOUSE_NO_CLICK
            }
            if (controller.selection.value != null &&
                controller.hitTestStrict(windowPoint) != null
            ) {
                controller.clearSelection()
            }
            return MouseGestureOutcome(change.uptimeMillis, change.position)
        }

        if (!dragging) {
            if ((change.position - downLocal).getDistance() < touchSlopPx) continue
            val anchor = controller.hitTestStrict(downWindowPoint)
                ?: return MOUSE_NO_CLICK
            controller.selectionFromMouse.value = true
            controller.beginSelection(anchor)
            dragging = true
            down.consume()
        }

        controller.dragIntent.value = SelectionController.DragIntent(
            point = windowPoint,
            handle = SelectionController.Handle.End,
        )
        change.consume()
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.consumeUntilRelease(
    pointerId: androidx.compose.ui.input.pointer.PointerId,
) {
    while (true) {
        val ev = awaitPointerEvent(PointerEventPass.Initial)
        val change = ev.changes.firstOrNull { it.id == pointerId } ?: return
        change.consume()
        if (!change.pressed) return
    }
}

private const val HANDLE_TOUCH_SLOP_PX = 96f

private const val EDGE_AUTO_SCROLL_ZONE_PX = 80f

private const val EDGE_AUTO_SCROLL_MAX_PX = 14f

private fun edgeAutoScrollNudge(
    localY: Float,
    viewportH: Int,
    listState: LazyListState,
    reverseLayout: Boolean,
) {
    val rawMagnitude = when {
        localY < EDGE_AUTO_SCROLL_ZONE_PX ->
            -((EDGE_AUTO_SCROLL_ZONE_PX - localY) / EDGE_AUTO_SCROLL_ZONE_PX) * EDGE_AUTO_SCROLL_MAX_PX
        localY > viewportH - EDGE_AUTO_SCROLL_ZONE_PX ->
            ((localY - (viewportH - EDGE_AUTO_SCROLL_ZONE_PX)) / EDGE_AUTO_SCROLL_ZONE_PX) * EDGE_AUTO_SCROLL_MAX_PX
        else -> 0f
    }
    if (rawMagnitude == 0f) return
    val signed = if (reverseLayout) -rawMagnitude else rawMagnitude
    listState.dispatchRawDelta(signed)
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleDragLoop(
    controller: SelectionController,
    listState: LazyListState,
    rootCoordinates: () -> LayoutCoordinates?,
    grabbed: SelectionController.Handle,
    pointerId: androidx.compose.ui.input.pointer.PointerId,
    reverseLayout: Boolean,
) {
    try {
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            val change = ev.changes.firstOrNull { it.id == pointerId } ?: return
            if (!change.pressed) return
            val windowPoint = localToWindow(change.position, rootCoordinates)
            controller.dragIntent.value = SelectionController.DragIntent(
                point = windowPoint,
                handle = grabbed,
            )
            change.consume()
        }
    } finally {
        controller.dragIntent.value = null
    }
}

@Composable
fun SelectionDragTracker(
    controller: SelectionController,
    listState: LazyListState,
    listRootCoordinates: () -> LayoutCoordinates?,
    reverseLayout: Boolean = false,
) {
    LaunchedEffect(controller, listState, reverseLayout) {
        androidx.compose.runtime.snapshotFlow { controller.dragIntent.value }
            .collect { intent ->
                if (intent == null) return@collect
                val target = controller.hitTest(intent.point) ?: return@collect
                when (intent.handle) {
                    SelectionController.Handle.Start -> controller.replaceStart(target)
                    SelectionController.Handle.End -> controller.replaceEnd(target)
                }
            }
    }

    LaunchedEffect(controller, listState, reverseLayout) {
        androidx.compose.runtime.snapshotFlow { controller.dragIntent.value != null }
            .collect { dragActive ->
                if (!dragActive) return@collect
                while (controller.dragIntent.value != null) {
                    val intent = controller.dragIntent.value
                    val rootCoords = listRootCoordinates()
                    if (intent != null && rootCoords != null && rootCoords.isAttached) {
                        val origin = rootCoords.positionInWindow()
                        val localY = intent.point.y - origin.y
                        val viewportH = rootCoords.size.height
                        edgeAutoScrollNudge(localY, viewportH, listState, reverseLayout)
                    }
                    kotlinx.coroutines.delay(16)
                }
            }
    }
}

private fun localToWindow(
    local: Offset,
    rootCoordinates: () -> LayoutCoordinates?,
): Offset {
    val coords = rootCoordinates() ?: return local
    if (!coords.isAttached) return local
    val origin = coords.positionInWindow()
    return Offset(local.x + origin.x, local.y + origin.y)
}

private val HANDLE_HIT_SIZE_DP = 56.dp

private val HANDLE_DOT_SIZE_DP = 14.dp

@Composable
fun MinisSelectionHandlesHost(
    controller: SelectionController,
    listState: LazyListState,
    reverseLayout: Boolean = false,
) {
    val sel by controller.selection
    if (sel == null) return
    if (controller.selectionFromMouse.value) return
    val tick = remember { androidx.compose.runtime.mutableStateOf(0) }
    LaunchedEffect(controller) {
        while (true) {
            androidx.compose.runtime.withFrameNanos {  }
            tick.value = tick.value + 1
        }
    }
    @Suppress("UNUSED_VARIABLE") val t = tick.value
    val dragging = controller.dragIntent.value != null
    val frozenStart = remember { androidx.compose.runtime.mutableStateOf<Offset?>(null) }
    val frozenEnd = remember { androidx.compose.runtime.mutableStateOf<Offset?>(null) }
    val liveStart = controller.handleAnchor(SelectionController.Handle.Start)
    val liveEnd = controller.handleAnchor(SelectionController.Handle.End)
    if (dragging) {
        if (frozenStart.value == null && liveStart != null) frozenStart.value = liveStart
        if (frozenEnd.value == null && liveEnd != null) frozenEnd.value = liveEnd
    } else {
        frozenStart.value = null
        frozenEnd.value = null
    }
    val startAnchor = frozenStart.value ?: liveStart
    val endAnchor = frozenEnd.value ?: liveEnd
    if (startAnchor != null) {
        SelectionHandleDot(
            anchorWindow = startAnchor,
            isStart = true,
            controller = controller,
            listState = listState,
            reverseLayout = reverseLayout,
        )
    }
    if (endAnchor != null) {
        SelectionHandleDot(
            anchorWindow = endAnchor,
            isStart = false,
            controller = controller,
            listState = listState,
            reverseLayout = reverseLayout,
        )
    }
}

@Composable
private fun SelectionHandleDot(
    anchorWindow: Offset,
    isStart: Boolean,
    controller: SelectionController,
    listState: LazyListState,
    reverseLayout: Boolean,
) {
    val color = MaterialTheme.colorScheme.primary
    val density = androidx.compose.ui.platform.LocalDensity.current
    val dotPx = with(density) { (HANDLE_DOT_SIZE_DP / 2).toPx() }
    val hitPx = with(density) { HANDLE_HIT_SIZE_DP.toPx() }
    val provider = remember(anchorWindow.x, anchorWindow.y, isStart, hitPx) {
        HandlePositionProvider(anchorWindow, isStart = isStart, hitSizePx = hitPx)
    }
    val handleKind = if (isStart) SelectionController.Handle.Start else SelectionController.Handle.End
    var boxWindowOrigin by remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    Popup(
        popupPositionProvider = provider,
        properties = PopupProperties(focusable = false, clippingEnabled = false),
    ) {
        Box(
            modifier = Modifier
                .size(HANDLE_HIT_SIZE_DP)
                .pointerInput(controller, handleKind) {
                    var dragTotal = Offset.Zero
                    var dragStartAnchor = anchorWindow
                    detectDragGestures(
                        onDragStart = {
                            dragStartAnchor = controller.handleAnchor(handleKind) ?: anchorWindow
                            dragTotal = Offset.Zero
                            controller.dragIntent.value = SelectionController.DragIntent(
                                point = dragStartAnchor,
                                handle = handleKind,
                            )
                        },
                        onDrag = { _, delta ->
                            dragTotal += delta
                            val windowPos = dragStartAnchor + dragTotal
                            controller.dragIntent.value = SelectionController.DragIntent(
                                point = windowPos,
                                handle = handleKind,
                            )
                        },
                        onDragEnd = { controller.dragIntent.value = null },
                        onDragCancel = { controller.dragIntent.value = null },
                    )
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            val live = controller.dragIntent.value
            val draggingThis = live != null && live.handle == handleKind
            val dragOffsetWindow = if (draggingThis) {
                live!!.point - anchorWindow
            } else Offset.Zero
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .size(HANDLE_DOT_SIZE_DP)
                    .offset {
                        IntOffset(
                            x = dragOffsetWindow.x.toInt(),
                            y = dragOffsetWindow.y.toInt(),
                        )
                    },
            ) {
                drawCircle(color = color, radius = dotPx)
            }
        }
    }
}

private class HandlePositionProvider(
    private val anchorWindow: Offset,
    private val isStart: Boolean,
    private val hitSizePx: Float,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val halfBox = popupContentSize.width / 2
        val x = (anchorWindow.x - halfBox).toInt()
        val y = (anchorWindow.y).toInt()
        return IntOffset(
            x = x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y = y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}

data class SelectionToolbarActions(
    val resolveSelectionMarkdown: () -> String?,
    val onAddToInput: ((String) -> Unit)? = null,
    val onReadAloud: ((String) -> Unit)? = null,
    val onReadFromStart: ((String) -> Unit)? = null,
    val onShareChat: ((String?) -> Unit)? = null,
)

@Composable
fun MinisSelectionToolbarHost(
    controller: SelectionController,
    actions: SelectionToolbarActions? = null,
    contentViewportBounds: () -> androidx.compose.ui.geometry.Rect? = { null },
) {
    val sel by controller.selection
    if (sel == null) return
    val tick = remember { androidx.compose.runtime.mutableStateOf(0) }
    LaunchedEffect(controller) {
        while (true) {
            androidx.compose.runtime.withFrameNanos {  }
            tick.value = tick.value + 1
        }
    }
    @Suppress("UNUSED_VARIABLE") val t = tick.value
    val activeDragHandle = controller.dragIntent.value?.handle
    val lastDraggedHandle = remember { androidx.compose.runtime.mutableStateOf<SelectionController.Handle?>(null) }
    LaunchedEffect(controller) {
        androidx.compose.runtime.snapshotFlow { controller.dragIntent.value?.handle }
            .collect { h -> if (h != null) lastDraggedHandle.value = h }
    }
    LaunchedEffect(controller) {
        androidx.compose.runtime.snapshotFlow { controller.selection.value }
            .collect { sel ->
                if (sel != null && sel.start == sel.end) lastDraggedHandle.value = null
            }
    }
    val anchorHandle = activeDragHandle ?: lastDraggedHandle.value
    val menuPoint = controller.contextMenuRequest.value
    val rect = if (menuPoint != null) {
        androidx.compose.ui.geometry.Rect(
            left = menuPoint.x,
            top = menuPoint.y,
            right = menuPoint.x,
            bottom = menuPoint.y,
        )
    } else when (anchorHandle) {
        SelectionController.Handle.Start -> controller.draggedHandleLineRect(SelectionController.Handle.Start)
        SelectionController.Handle.End -> controller.draggedHandleLineRect(SelectionController.Handle.End)
        else -> null
    } ?: controller.visibleSelectionEndLineRect()
        ?: controller.visibleSelectionWindowRect()
    if (menuPoint == null && rect != null && rect.width <= 0f && rect.height <= 0f) return
    val anchor = remember(rect) {
        if (rect != null) IntRect(
            left = rect.left.toInt(),
            top = rect.top.toInt(),
            right = rect.right.toInt(),
            bottom = rect.bottom.toInt(),
        ) else IntRect(0, 0, 0, 0)
    }
    val viewportBounds = contentViewportBounds()
    val centerX = menuPoint?.x ?: when (anchorHandle) {
        SelectionController.Handle.Start ->
            controller.handleAnchor(SelectionController.Handle.Start)?.x
        SelectionController.Handle.End ->
            controller.handleAnchor(SelectionController.Handle.End)?.x
        else -> null
    } ?: controller.handlesCenterX()
    val positionProvider = remember(anchor, viewportBounds, centerX, menuPoint != null) {
        FloatingSelectionToolbarPositionProvider(
            anchor = anchor,
            viewport = viewportBounds,
            preferredCenterX = centerX,
            atCursor = menuPoint != null,
        )
    }
    val clipboard = LocalClipboardManager.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val context = androidx.compose.ui.platform.LocalContext.current
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = {  },
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        val barColor = MaterialTheme.colorScheme.surfaceContainerHigh
        val ctxForWidth = androidx.compose.ui.platform.LocalContext.current
        val density = androidx.compose.ui.platform.LocalDensity.current
        val maxBarWidth = with(density) {
            ctxForWidth.resources.displayMetrics.widthPixels.toFloat().toDp() - 16.dp
        }
        Surface(
            modifier = Modifier.widthIn(max = maxBarWidth),
            shape = RoundedCornerShape(10.dp),
            color = barColor,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            border = androidx.compose.foundation.BorderStroke(
                0.5.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            ),
        ) {
            val toolbarScroll = androidx.compose.foundation.rememberScrollState()
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(barColor)
                    .height(40.dp)
                    .horizontalScroll(toolbarScroll),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                fun toast(msg: String) {
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
                fun preview(text: String): String =
                    if (text.length > 40) text.take(37) + "…" else text

                val labelCopy = androidx.compose.ui.res.stringResource(com.openminis.app.R.string.selection_copy)
                val labelAddToInput = androidx.compose.ui.res.stringResource(com.openminis.app.R.string.selection_add_to_chat_input)
                val labelCopyFullText = androidx.compose.ui.res.stringResource(com.openminis.app.R.string.selection_copy_full_text)
                val labelCopyAsMarkdown = androidx.compose.ui.res.stringResource(com.openminis.app.R.string.selection_copy_as_markdown)
                val labelCopyAsRichText = androidx.compose.ui.res.stringResource(com.openminis.app.R.string.selection_copy_as_rich_text)
                val labelCopyAsPlainText = androidx.compose.ui.res.stringResource(com.openminis.app.R.string.selection_copy_as_plain_text)
                val toastCopiedAsPlainText = androidx.compose.ui.res.stringResource(com.openminis.app.R.string.selection_copied_as_plain_text_toast)
                val labelReadSelection = androidx.compose.ui.res.stringResource(com.openminis.app.R.string.selection_read_aloud)
                val labelReadFromStart = androidx.compose.ui.res.stringResource(com.openminis.app.R.string.selection_read_from_start)
                val labelReadAloud = androidx.compose.ui.res.stringResource(com.openminis.app.R.string.read_aloud_group)
                val toastCopiedAsMarkdown = androidx.compose.ui.res.stringResource(com.openminis.app.R.string.selection_copied_as_markdown_toast)
                val toastCopiedAsRichText = androidx.compose.ui.res.stringResource(com.openminis.app.R.string.selection_copied_as_rich_text_toast)
                val tableActions = controller.selectionTableActions()
                val labelCopyTable = androidx.compose.ui.res.stringResource(
                    com.openminis.app.R.string.markdown_table_copy_table)
                val labelCopyTableImage = androidx.compose.ui.res.stringResource(
                    com.openminis.app.R.string.markdown_table_copy_table_image)
                val labelShareChat = androidx.compose.ui.res.stringResource(
                    com.openminis.app.R.string.chat_share_share_chat)

                val items = buildList {
                    add(SelectionAction(labelCopy) {
                        val text = controller.selectedPlainText()
                        if (text.isNotEmpty()) {
                            clipboard.setText(AnnotatedString(text))
                            haptics.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                            )
                            toast(context.getString(
                                com.openminis.app.R.string.selection_copied_toast, preview(text)))
                        }
                        controller.clearSelection()
                    })
                    if (actions?.onAddToInput != null) {
                        add(SelectionAction(labelAddToInput) {
                            val text = controller.selectedPlainText()
                            if (text.isNotEmpty()) actions.onAddToInput.invoke(text)
                            controller.clearSelection()
                        })
                    }
                    add(
                        SelectionAction(
                            label = labelCopyFullText,
                            children = listOf(
                                SelectionAction(labelCopyAsMarkdown) {
                                    val source = actions?.resolveSelectionMarkdown?.invoke()
                                        ?: controller.selectedPlainText()
                                    if (source.isNotEmpty()) {
                                        MarkdownClipboard.copyMarkdown(context, source)
                                        toast(toastCopiedAsMarkdown)
                                    }
                                    controller.clearSelection()
                                },
                                SelectionAction(labelCopyAsRichText) {
                                    val source = actions?.resolveSelectionMarkdown?.invoke()
                                        ?: controller.selectedPlainText()
                                    if (source.isNotEmpty()) {
                                        MarkdownClipboard.copyRichText(context, source)
                                        toast(toastCopiedAsRichText)
                                    }
                                    controller.clearSelection()
                                },
                                SelectionAction(labelCopyAsPlainText) {
                                    val source = actions?.resolveSelectionMarkdown?.invoke()
                                        ?: controller.selectedPlainText()
                                    if (source.isNotEmpty()) {
                                        MarkdownClipboard.copyPlain(context, source)
                                        toast(toastCopiedAsPlainText)
                                    }
                                    controller.clearSelection()
                                },
                            ),
                        ),
                    )
                    run {
                        val readChildren = buildList {
                            if (actions?.onReadAloud != null) {
                                add(SelectionAction(labelReadSelection) {
                                    val text = controller.selectedPlainText()
                                    if (text.isNotEmpty()) actions.onReadAloud.invoke(text)
                                    controller.clearSelection()
                                })
                            }
                            if (actions?.onReadFromStart != null) {
                                add(SelectionAction(labelReadFromStart) {
                                    val source = actions.resolveSelectionMarkdown()
                                        ?: controller.selectedPlainText()
                                    if (source.isNotEmpty()) actions.onReadFromStart.invoke(source)
                                    controller.clearSelection()
                                })
                            }
                        }
                        when (readChildren.size) {
                            0 -> Unit
                            1 -> add(readChildren.single())
                            else -> add(
                                SelectionAction(labelReadAloud, children = readChildren),
                            )
                        }
                    }
                    if (tableActions != null) {
                        add(SelectionAction(labelCopyTable) {
                            tableActions.copyTableMarkdown()
                            controller.clearSelection()
                        })
                        add(SelectionAction(labelCopyTableImage) {
                            tableActions.copyTableImage()
                            controller.clearSelection()
                        })
                    }
                    if (actions?.onShareChat != null) {
                        add(SelectionAction(labelShareChat) {
                            val messageId = controller.singleMessageId()
                                ?: controller.selection.value?.start?.shard?.messageId
                            controller.clearSelection()
                            actions.onShareChat.invoke(messageId)
                        })
                    }
                }

                val toolbarMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
                val buttonStyle = MaterialTheme.typography.labelLarge
                val buttonPadPx = with(density) { (14.dp * 2 + 1.dp).toPx() }
                fun buttonWidth(label: String): Float =
                    toolbarMeasurer.measure(label, buttonStyle, maxLines = 1, softWrap = false)
                        .size.width + buttonPadPx
                val inlineCount = inlineSelectionActionCount(
                    widths = items.map { buttonWidth(if (it.children.isEmpty()) it.label else it.label + "  ›") },
                    overflowWidth = buttonWidth("⋯"),
                    maxWidth = with(density) { maxBarWidth.toPx() },
                    cap = MAX_INLINE_SELECTION_ACTIONS,
                )
                val inlineItems = items.take(inlineCount)
                val overflowItems = items.drop(inlineCount)

                inlineItems.forEachIndexed { index, item ->
                    if (index > 0) MinisToolbarDivider()
                    if (item.children.isEmpty()) {
                        MinisToolbarButton(label = item.label, onClick = item.onClick)
                    } else {
                        var childOpen by remember(items.size, item.label) {
                            mutableStateOf(false)
                        }
                        var childAnchor by remember { mutableStateOf(Offset.Zero) }
                        MinisToolbarButton(
                            label = item.label + "  ›",
                            modifier = Modifier.onGloballyPositioned { coords ->
                                val pos = coords.positionOnScreen()
                                childAnchor = Offset(
                                    pos.x + coords.size.width / 2f,
                                    pos.y + coords.size.height,
                                )
                            },
                        ) { childOpen = true }
                        if (childOpen) {
                            val childProvider = remember(childAnchor) {
                                SelectionOverflowPositionProvider(anchorWindow = childAnchor)
                            }
                            Popup(
                                popupPositionProvider = childProvider,
                                onDismissRequest = { childOpen = false },
                                properties = PopupProperties(focusable = false),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    tonalElevation = 3.dp,
                                    shadowElevation = 8.dp,
                                    border = BorderStroke(
                                        0.5.dp,
                                        MaterialTheme.colorScheme.outlineVariant
                                            .copy(alpha = 0.6f),
                                    ),
                                ) {
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        for (child in item.children) {
                                            Text(
                                                child.label,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier
                                                    .clickable {
                                                        childOpen = false
                                                        child.onClick()
                                                    }
                                                    .padding(
                                                        horizontal = 16.dp,
                                                        vertical = 10.dp,
                                                    ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (overflowItems.isNotEmpty()) {
                    MinisToolbarDivider()
                    var overflowOpen by remember(items.size) { mutableStateOf(false) }
                    var overflowAnchor by remember { mutableStateOf(Offset.Zero) }
                    MinisToolbarButton(
                        label = "⋯",
                        modifier = Modifier.onGloballyPositioned { coords ->
                            val pos = coords.positionOnScreen()
                            overflowAnchor = Offset(
                                pos.x + coords.size.width / 2f,
                                pos.y + coords.size.height,
                            )
                        },
                    ) { overflowOpen = true }
                    if (overflowOpen) {
                        val overflowProvider = remember(overflowAnchor) {
                            SelectionOverflowPositionProvider(anchorWindow = overflowAnchor)
                        }
                        Popup(
                            popupPositionProvider = overflowProvider,
                            onDismissRequest = { overflowOpen = false },
                            properties = PopupProperties(
                                focusable = true,
                                dismissOnBackPress = true,
                                dismissOnClickOutside = true,
                            ),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                tonalElevation = 3.dp,
                                shadowElevation = 8.dp,
                                border = androidx.compose.foundation.BorderStroke(
                                    0.5.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                ),
                            ) {
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    var expandedParent by remember(overflowOpen) {
                                        mutableStateOf<String?>(null)
                                    }
                                    for (item in overflowItems) {
                                        val isParent = item.children.isNotEmpty()
                                        val isExpanded = isParent && expandedParent == item.label
                                        Text(
                                            if (isParent) {
                                                item.label + if (isExpanded) "  ⌄" else "  ›"
                                            } else {
                                                item.label
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier
                                                .clickable {
                                                    if (isParent) {
                                                        expandedParent =
                                                            if (isExpanded) null else item.label
                                                    } else {
                                                        overflowOpen = false
                                                        item.onClick()
                                                    }
                                                }
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                        )
                                        if (isExpanded) {
                                            for (child in item.children) {
                                                Text(
                                                    child.label,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    modifier = Modifier
                                                        .clickable {
                                                            overflowOpen = false
                                                            child.onClick()
                                                        }
                                                        .padding(
                                                            start = 32.dp,
                                                            end = 16.dp,
                                                            top = 10.dp,
                                                            bottom = 10.dp,
                                                        ),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private class SelectionAction(
    val label: String,
    val children: List<SelectionAction> = emptyList(),
    val onClick: () -> Unit = {},
)

internal const val MAX_INLINE_SELECTION_ACTIONS = 4

internal fun inlineSelectionActionCount(
    widths: List<Float>,
    overflowWidth: Float,
    maxWidth: Float,
    cap: Int,
): Int {
    val limit = minOf(cap, widths.size)
    for (n in limit downTo 1) {
        val needed = widths.take(n).sum() + if (n < widths.size) overflowWidth else 0f
        if (needed <= maxWidth) return n
    }
    return minOf(1, widths.size)
}

@Composable
private fun MinisToolbarDivider() {
    Box(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .width(0.5.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    )
}

@Composable
private fun MinisToolbarButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

private class SelectionOverflowPositionProvider(
    private val anchorWindow: Offset,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val gap = 4
        val x = (anchorWindow.x - popupContentSize.width / 2f).toInt()
        val below = (anchorWindow.y + gap).toInt()
        val y = if (below + popupContentSize.height > windowSize.height) {
            (anchorWindow.y - popupContentSize.height - gap).toInt()
        } else {
            below
        }
        return IntOffset(
            x = x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y = y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}

private class FloatingSelectionToolbarPositionProvider(
    private val anchor: IntRect,
    private val viewport: androidx.compose.ui.geometry.Rect?,
    private val preferredCenterX: Float?,
    private val atCursor: Boolean = false,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val viewTop = viewport?.top?.toInt() ?: 0
        val viewBottom = viewport?.bottom?.toInt() ?: windowSize.height
        val viewLeft = viewport?.left?.toInt() ?: 0
        val viewRight = viewport?.right?.toInt() ?: windowSize.width
        val maxY = (viewBottom - popupContentSize.height).coerceAtLeast(viewTop)
        val maxX = (viewRight - popupContentSize.width).coerceAtLeast(viewLeft)

        if (!atCursor &&
            anchor.left == 0 && anchor.top == 0 && anchor.right == 0 && anchor.bottom == 0
        ) {
            val centerX = (viewLeft + viewRight) / 2
            val x = (centerX - popupContentSize.width / 2).coerceIn(viewLeft, maxX)
            val y = (viewTop + 12).coerceIn(viewTop, maxY)
            return IntOffset(x, y)
        }
        val gap = if (atCursor) 8 else 48
        val centerX = preferredCenterX?.toInt() ?: (anchor.left + anchor.width / 2)
        val x = (centerX - popupContentSize.width / 2).coerceIn(viewLeft, maxX)
        val aboveY = anchor.top - popupContentSize.height - gap
        val belowY = anchor.bottom + gap
        val y = if (atCursor) {
            if (belowY + popupContentSize.height <= viewBottom) belowY else aboveY
        } else {
            if (aboveY >= viewTop) aboveY else belowY
        }
        return IntOffset(x, y.coerceIn(viewTop, maxY))
    }
}
