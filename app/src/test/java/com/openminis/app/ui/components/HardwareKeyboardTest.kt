package com.openminis.app.ui.components

import android.view.InputDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareKeyboardTest {

    private val ALPHA = InputDevice.KEYBOARD_TYPE_ALPHABETIC
    private val NONE = InputDevice.KEYBOARD_TYPE_NONE
    private val NON_ALPHA = InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC


    @Test
    fun `a scrcpy uhid keyboard does not count as a hardware keyboard`() {
        assertFalse(
            HardwareKeyboard.qualifies(
                keyboardType = ALPHA, isVirtual = false, isExternal = false, sourcesIncludeKeyboard = true,
            ),
        )
    }


    @Test
    fun `a real Bluetooth or USB keyboard still counts`() {
        assertTrue(
            HardwareKeyboard.qualifies(
                keyboardType = ALPHA, isVirtual = false, isExternal = true, sourcesIncludeKeyboard = true,
            ),
        )
    }


    @Test
    fun `the framework's built-in virtual keyboard is excluded`() {
        assertFalse(HardwareKeyboard.qualifies(ALPHA, isVirtual = true, isExternal = false, sourcesIncludeKeyboard = true))
        assertFalse(HardwareKeyboard.qualifies(ALPHA, isVirtual = true, isExternal = true, sourcesIncludeKeyboard = true))
    }

    @Test
    fun `built-in button devices are excluded`() {
        assertFalse(HardwareKeyboard.qualifies(ALPHA, isVirtual = false, isExternal = false, sourcesIncludeKeyboard = true))
        assertFalse(HardwareKeyboard.qualifies(NON_ALPHA, isVirtual = false, isExternal = false, sourcesIncludeKeyboard = true))
    }

    @Test
    fun `an external device without an alphabetic layout is excluded`() {
        assertFalse(HardwareKeyboard.qualifies(NON_ALPHA, isVirtual = false, isExternal = true, sourcesIncludeKeyboard = true))
        assertFalse(HardwareKeyboard.qualifies(NONE, isVirtual = false, isExternal = true, sourcesIncludeKeyboard = true))
    }

    @Test
    fun `a device with no keyboard source is excluded even if otherwise plausible`() {
        assertFalse(HardwareKeyboard.qualifies(ALPHA, isVirtual = false, isExternal = true, sourcesIncludeKeyboard = false))
    }
}
