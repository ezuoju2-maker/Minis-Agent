package com.openminis.app.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.pointer.pointerInput

internal object BlankTapGeometry {
    const val TEXT_CLEARANCE_DP = 24f

    const val LINE_VERTICAL_CLEARANCE_DP = 8f

    const val INTERACTIVE_CLEARANCE_DP = 20f

    enum class Kind {
        BLANK,
        TEXT,
        NEAR_INTERACTIVE,
        SUPPRESSED,
        OUTSIDE_BAND,
    }

    sealed class Band {
        object Anywhere : Band()
        object None : Band()
        data class Range(val top: Float, val bottom: Float) : Band()

        fun contains(y: Float): Boolean = when (this) {
            Anywhere -> true
            None -> false
            is Range -> y >= top && y <= bottom
        }
    }

    fun decide(
        tap: Offset,
        lines: List<Rect>,
        interactive: List<Rect>,
        density: Float,
        selectionActive: Boolean,
        longPress: Boolean,
        band: Band = Band.Anywhere,
    ): Kind {
        if (selectionActive || longPress) return Kind.SUPPRESSED
        val keepOut = INTERACTIVE_CLEARANCE_DP * density
        for (r in interactive) {
            if (r.contains(tap)) return Kind.TEXT
        }
        for (r in interactive) {
            if (r.inflate(keepOut).contains(tap)) return Kind.NEAR_INTERACTIVE
        }
        val margin = TEXT_CLEARANCE_DP * density
        val reach = LINE_VERTICAL_CLEARANCE_DP * density
        for (line in lines) {
            if (line.right <= line.left) continue
            val inBand = tap.y >= line.top - reach && tap.y <= line.bottom + reach
            if (!inBand) continue
            if (tap.x > line.left - margin && tap.x < line.right + margin) return Kind.TEXT
        }
        return if (band.contains(tap.y)) Kind.BLANK else Kind.OUTSIDE_BAND
    }
}

@androidx.compose.runtime.Composable
internal fun UsageCapsuleRow(
    shown: Boolean,
    onToggle: () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    content: @androidx.compose.runtime.Composable () -> Unit,
) {
    val base = androidx.compose.ui.platform.LocalViewConfiguration.current
    val exact = androidx.compose.runtime.remember(base) {
        object : androidx.compose.ui.platform.ViewConfiguration by base {
            override val minimumTouchTargetSize = androidx.compose.ui.unit.DpSize.Zero
        }
    }
    val currentToggle by androidx.compose.runtime.rememberUpdatedState(onToggle)
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalViewConfiguration provides exact,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = modifier.then(
                if (shown) {
                    androidx.compose.ui.Modifier.pointerInput(Unit) {
                        detectTapGestures { currentToggle() }
                    }
                } else {
                    androidx.compose.ui.Modifier
                },
            ),
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalViewConfiguration provides base,
                content = content,
            )
        }
    }
}
