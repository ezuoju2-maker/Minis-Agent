package com.openminis.app.ui.components

import android.content.Context
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.view.InputDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

object HardwareKeyboard {

    fun qualifies(
        keyboardType: Int,
        isVirtual: Boolean,
        isExternal: Boolean,
        sourcesIncludeKeyboard: Boolean,
    ): Boolean =
        sourcesIncludeKeyboard &&
            keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
            !isVirtual &&
            isExternal

    fun isAttached(context: Context, configuration: Configuration): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            return configuration.keyboard == Configuration.KEYBOARD_QWERTY &&
                configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
        }
        if (configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_YES) return false
        val ids = InputDevice.getDeviceIds()
        for (id in ids) {
            val d = InputDevice.getDevice(id) ?: continue
            val hasKeyboardSource = (d.sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
            if (qualifies(d.keyboardType, d.isVirtual, d.isExternal, hasKeyboardSource)) return true
        }
        return false
    }
}

@Composable
fun rememberHasHardwareKeyboard(): Boolean {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var attached by remember { mutableStateOf(HardwareKeyboard.isAttached(context, configuration)) }
    androidx.compose.runtime.LaunchedEffect(configuration) {
        attached = HardwareKeyboard.isAttached(context, configuration)
    }
    DisposableEffect(context) {
        val im = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { attached = HardwareKeyboard.isAttached(context, configuration) }
            override fun onInputDeviceRemoved(deviceId: Int) { attached = HardwareKeyboard.isAttached(context, configuration) }
            override fun onInputDeviceChanged(deviceId: Int) { attached = HardwareKeyboard.isAttached(context, configuration) }
        }
        im?.registerInputDeviceListener(listener, null)
        onDispose { im?.unregisterInputDeviceListener(listener) }
    }
    return attached
}
