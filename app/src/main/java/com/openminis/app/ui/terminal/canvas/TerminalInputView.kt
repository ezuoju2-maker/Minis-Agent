package com.openminis.app.ui.terminal.canvas

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun TerminalInputView(
    onInput: (ByteArray) -> Unit,
    applicationCursorKeys: Boolean,
    modifier: Modifier = Modifier,
    controller: TerminalInputController = rememberTerminalInputController(),
) {
    val context = LocalContext.current
    val editText = remember { createHiddenEditText(context) }

    DisposableEffect(editText) {
        controller.editText = editText
        onDispose { controller.editText = null }
    }

    AndroidView(
        factory = { editText.also { it.bind(onInput) { applicationCursorKeys } } },
        update = { it.bind(onInput) { applicationCursorKeys } },
        modifier = modifier,
    )
}

class TerminalInputController {
    internal var editText: TerminalInputEditText? = null

    fun requestFocus() {
        editText?.let { et ->
            et.requestFocus()
            val imm = et.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun clearFocus() {
        editText?.let { et ->
            val imm = et.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(et.windowToken, 0)
            et.clearFocus()
        }
    }

    val isFocused: Boolean get() = editText?.isFocused == true
}

@Composable
fun rememberTerminalInputController(): TerminalInputController =
    remember { TerminalInputController() }

private fun createHiddenEditText(context: Context): TerminalInputEditText =
    TerminalInputEditText(context).apply {
        alpha = 0f
        isCursorVisible = false
        setTextIsSelectable(false)
        setSingleLine()
        isFocusable = true
        isFocusableInTouchMode = true
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        setBackgroundColor(0x00000000)
    }

class TerminalInputEditText @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : EditText(context, attrs) {

    private var onInputBytes: ((ByteArray) -> Unit)? = null
    private var getAppCursorMode: () -> Boolean = { false }

    fun bind(onInput: (ByteArray) -> Unit, appCursorMode: () -> Boolean) {
        this.onInputBytes = onInput
        this.getAppCursorMode = appCursorMode
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE

        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text == null || text.isEmpty()) return true
                send(text.toString().toByteArray(Charsets.UTF_8))
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text == null || text.isEmpty()) return true
                send(text.toString().toByteArray(Charsets.UTF_8))
                return true
            }

            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                event ?: return false
                if (event.action != KeyEvent.ACTION_DOWN) return true
                return handleKeyEvent(event)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { send(byteArrayOf(0x7F)) }
                return true
            }

            override fun finishComposingText(): Boolean = true
        }
    }

    private val consumedKeyDowns = HashSet<Int>()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleKeyEvent(event)) {
            consumedKeyDowns.add(keyCode)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (consumedKeyDowns.remove(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        val appCursor = getAppCursorMode()
        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed
        val shift = event.isShiftPressed

        val bytes: ByteArray? = when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER -> byteArrayOf(0x0D)
            KeyEvent.KEYCODE_DEL -> byteArrayOf(0x7F)
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001B[3~".toByteArray()
            KeyEvent.KEYCODE_TAB -> byteArrayOf(0x09)
            KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(0x1B)
            KeyEvent.KEYCODE_DPAD_UP -> TerminalKeyMapper.arrow('A', appCursor, shift, alt, ctrl)
            KeyEvent.KEYCODE_DPAD_DOWN -> TerminalKeyMapper.arrow('B', appCursor, shift, alt, ctrl)
            KeyEvent.KEYCODE_DPAD_RIGHT -> TerminalKeyMapper.arrow('C', appCursor, shift, alt, ctrl)
            KeyEvent.KEYCODE_DPAD_LEFT -> TerminalKeyMapper.arrow('D', appCursor, shift, alt, ctrl)
            KeyEvent.KEYCODE_MOVE_HOME -> "\u001B[H".toByteArray()
            KeyEvent.KEYCODE_MOVE_END -> "\u001B[F".toByteArray()
            KeyEvent.KEYCODE_PAGE_UP -> "\u001B[5~".toByteArray()
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001B[6~".toByteArray()
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
                TerminalKeyMapper.functionKey(event.keyCode - KeyEvent.KEYCODE_F1 + 1)
            else -> {
                val controlCode = if (ctrl && !event.isMetaPressed) {
                    val base = event.getUnicodeChar(
                        event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv(),
                    )
                    if (base > 0) TerminalKeyMapper.controlCode(base.toChar()) else null
                } else null
                if (controlCode != null) {
                    if (alt) byteArrayOf(0x1B, controlCode) else byteArrayOf(controlCode)
                } else {
                    val unicode = event.unicodeChar
                    if (unicode != 0) {
                        val ch = unicode.toChar()
                        when {
                            alt -> {
                                byteArrayOf(0x1B) + ch.toString().toByteArray(Charsets.UTF_8)
                            }
                            else -> ch.toString().toByteArray(Charsets.UTF_8)
                        }
                    } else null
                }
            }
        }
        return if (bytes != null) { send(bytes); true } else false
    }

    private fun send(bytes: ByteArray) {
        onInputBytes?.invoke(bytes)
    }
}
