package com.openminis.app.ui.chat

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R

@Composable
internal fun rememberScreenReaderEnabled(): Boolean {
    val context = LocalContext.current
    val am = remember(context) {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    var enabled by remember { mutableStateOf(am?.isTouchExplorationEnabled == true) }
    DisposableEffect(am) {
        if (am == null) return@DisposableEffect onDispose { }
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on ->
            enabled = on
        }
        am.addTouchExplorationStateChangeListener(listener)
        enabled = am.isTouchExplorationEnabled
        onDispose { am.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

internal fun animationsDisabled(context: Context): Boolean =
    android.provider.Settings.Global.getFloat(
        context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f

@Composable
internal fun composerPlaceholderText(index: Int, soulName: String): String {
    val hints = listOf(
        R.string.chat_input_placeholder_hint_1,
        R.string.chat_input_placeholder_hint_2,
        R.string.chat_input_placeholder_hint_3,
        R.string.chat_input_placeholder_hint_4,
        R.string.chat_input_placeholder_hint_5,
    )
    val safe = index.coerceIn(0, hints.size)
    return if (safe == ComposerPlaceholderRotation.DEFAULT_INDEX) {
        stringResource(R.string.chat_input_placeholder, soulName)
    } else {
        stringResource(hints[safe - 1], soulName)
    }
}
