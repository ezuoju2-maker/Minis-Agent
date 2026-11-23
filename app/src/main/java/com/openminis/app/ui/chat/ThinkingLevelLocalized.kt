package com.openminis.app.ui.chat

import android.content.Context
import com.openminis.app.R
import com.openminis.app.data.model.ThinkingLevel

fun ThinkingLevel.localizedName(context: Context): String =
    context.getString(
        when (this) {
            ThinkingLevel.OFF -> R.string.thinking_level_off
            ThinkingLevel.LOW -> R.string.thinking_level_low
            ThinkingLevel.MEDIUM -> R.string.thinking_level_medium
            ThinkingLevel.HIGH -> R.string.thinking_level_high
            ThinkingLevel.XHIGH -> R.string.thinking_level_xhigh
            ThinkingLevel.MAX -> R.string.thinking_level_max
            ThinkingLevel.ULTRA -> R.string.thinking_level_ultra
        },
    )
