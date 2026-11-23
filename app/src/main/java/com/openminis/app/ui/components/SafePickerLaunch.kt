package com.openminis.app.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.StringRes
import com.openminis.app.R
import com.openminis.app.logging.AppLogger

fun <I> ActivityResultLauncher<I>.safeLaunchPicker(
    input: I,
    context: Context,
    tag: String,
    @StringRes message: Int = R.string.picker_unavailable,
): Boolean = launchPickerSafely(
    launch = { launch(input) },
    log = { AppLogger.warning(tag, it) },
    notify = { Toast.makeText(context, context.getString(message), Toast.LENGTH_LONG).show() },
)

internal fun launchPickerSafely(
    launch: () -> Unit,
    log: (String) -> Unit,
    notify: () -> Unit,
): Boolean {
    try {
        launch()
        return true
    } catch (e: ActivityNotFoundException) {
        log("picker unavailable: ${e.message}")
        notify()
        return false
    }
}
