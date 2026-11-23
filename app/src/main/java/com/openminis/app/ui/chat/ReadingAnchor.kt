package com.openminis.app.ui.chat

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal object ReadingAnchorMath {
    fun offsetAfterViewportResize(offset: Int, oldHeight: Int, newHeight: Int): Int =
        offset + (oldHeight - newHeight)

    fun shouldKeepOnResize(oldHeight: Int, newHeight: Int, readerAway: Boolean): Boolean =
        readerAway && oldHeight > 0 && newHeight > 0 && oldHeight != newHeight

    fun shouldHoldGrowth(reported: Int, natural: Int, isAnchor: Boolean, readerAway: Boolean): Boolean =
        readerAway && isAnchor && reported >= 0 && natural != reported
}

internal class ReadingAnchor(
    private val listState: LazyListState,
    private val toggleAnchor: ChatToggleAnchor,
) {
    var readerAway: () -> Boolean = { false }
    var keepOnResize: () -> Boolean = { false }
    var scope: CoroutineScope? = null

    private fun away(): Boolean = Snapshot.withoutReadObservation {
        readerAway() && !listState.isScrollInProgress && !toggleAnchor.isBusy
    }

    private fun anchorKey(): Any? = Snapshot.withoutReadObservation {
        listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key
    }


    private var lastViewportHeight = -1

    val viewportModifier: Modifier = Modifier.layout { measurable, constraints ->
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else -1
        val old = lastViewportHeight
        lastViewportHeight = height
        val keep = Snapshot.withoutReadObservation { keepOnResize() && !toggleAnchor.isBusy }
        if (ReadingAnchorMath.shouldKeepOnResize(old, height, keep)) {
            Snapshot.withoutReadObservation {
                listState.requestScrollToItem(
                    listState.firstVisibleItemIndex,
                    ReadingAnchorMath.offsetAfterViewportResize(listState.firstVisibleItemScrollOffset, old, height),
                )
            }
        }
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }


    inner class RowHold(private val key: Any) {
        private var reported = -1
        private var natural = -1
        private var pending = false
        private val release = mutableIntStateOf(0)

        val modifier: Modifier = Modifier.layout { measurable, constraints ->
            release.intValue
            val placeable = measurable.measure(constraints)
            natural = placeable.height
            val height = when {
                pending -> reported
                ReadingAnchorMath.shouldHoldGrowth(reported, natural, anchorKey() == key, away()) -> {
                    hold()
                    reported
                }
                else -> natural.also { reported = it }
            }
            layout(placeable.width, height) { placeable.place(0, 0) }
        }

        private fun hold() {
            val s = scope ?: return
            pending = true
            s.launch {
                withFrameNanos {
                    val from = reported
                    val to = natural
                    if (anchorKey() == key && away()) {
                        val d = ToggleAnchorMath.keepTopScroll(from, to)
                        if (d != 0f) listState.dispatchRawDelta(d)
                    }
                    reported = to
                    pending = false
                    release.intValue++
                }
            }
        }
    }
}

internal object ImeDictation {
    fun recordingActive(context: Context): Boolean = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        am?.activeRecordingConfigurations?.isNotEmpty() == true
    }.getOrDefault(false)
}
