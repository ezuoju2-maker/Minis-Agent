package com.openminis.app.ui.terminal

import com.openminis.app.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.sandbox.TerminalSession
import com.openminis.app.terminal.MinisOpenUrlBroker
import com.openminis.app.ui.terminal.canvas.TerminalNativeViewCompose
import com.openminis.app.ui.terminal.canvas.TerminalInputView
import com.openminis.app.ui.terminal.canvas.rememberTerminalInputController
import com.openminis.app.ui.terminal.emulator.TerminalEmulator
import kotlinx.coroutines.launch

private val TerminalBg = Color(0xFF000000)
private val TerminalFg = Color(0xFFD4D4D4)
private val TerminalGreen = Color(0xFF34C759)
private val AccessoryBg = Color(0xFF1F1F1F)
private val AccButtonBg = Color(0xFF404040)
private val AccButtonActive = Color(0xFF007AFF)
private val TopButtonBg = Color(0xFF2C2C2E)

@Composable
fun TerminalScreen(
    terminalSession: TerminalSession,
    onBack: () -> Unit,
    initCommand: String? = null,
    sessionId: String? = null,
) {
    val emulator = remember { TerminalEmulator() }
    val context = LocalContext.current
    val inputController = rememberTerminalInputController()
    val scope = rememberCoroutineScope()
    var ctrlActive by remember { mutableStateOf(false) }

    LaunchedEffect(terminalSession) {
        terminalSession.outputBytes.collect { bytes ->
            emulator.feed(bytes)
        }
    }

    DisposableEffect(terminalSession, emulator) {
        emulator.onResponse = { data -> terminalSession.sendRawBytes(data) }
        onDispose { emulator.onResponse = null }
    }

    val clearVersion by terminalSession.clearVersion.collectAsStateEffect()
    LaunchedEffect(clearVersion) {
        if (clearVersion > 0) {
            emulator.feed("\u001Bc".toByteArray())
        }
    }

    LaunchedEffect(Unit) {
        if (!terminalSession.isRunning) terminalSession.start(sessionId = sessionId)
        if (!initCommand.isNullOrBlank()) {
            kotlinx.coroutines.delay(500)
            terminalSession.sendText(initCommand)
        }
    }


    val hasHardwareKeyboard = com.openminis.app.ui.components.rememberHasHardwareKeyboard()
    LaunchedEffect(hasHardwareKeyboard) {
        if (hasHardwareKeyboard) inputController.requestFocus()
    }

    DisposableEffect(Unit) {
        onDispose { terminalSession.stop() }
    }

    DisposableEffect(Unit) {
        MinisOpenUrlBroker.setTerminalVisible(true)
        onDispose { MinisOpenUrlBroker.setTerminalVisible(false) }
    }

    var previewUrl by remember { mutableStateOf<String?>(null) }
    val pendingUrl by MinisOpenUrlBroker.pendingUrl.collectAsStateEffect()
    LaunchedEffect(pendingUrl) {
        val uri = pendingUrl ?: return@LaunchedEffect
        if (MinisOpenUrlBroker.isWebScheme(uri.scheme)) {
            previewUrl = uri.toString()
        }
        MinisOpenUrlBroker.consume()
    }

    val accessoryBarHeightDp = 40.dp

    Box(modifier = Modifier.fillMaxSize().background(TerminalBg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .imePadding()
                .padding(bottom = accessoryBarHeightDp),
        ) {
            Spacer(modifier = Modifier.height(52.dp))
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                TerminalNativeViewCompose(
                    emulator = emulator,
                    onResize = { cols, rows ->
                        emulator.resize(cols, rows)
                        terminalSession.setWindowSize(cols, rows)
                    },
                    onTap = { inputController.requestFocus() },
                )
                TerminalInputView(
                    onInput = { bytes ->
                        emulator.scrollOffset = 0
                        if (ctrlActive && bytes.size == 1) {
                            val ch = bytes[0].toInt().toChar().uppercaseChar()
                            if (ch in 'A'..'Z') {
                                terminalSession.sendRawBytes(byteArrayOf((ch - 'A' + 1).toByte()))
                                ctrlActive = false
                                return@TerminalInputView
                            }
                        }
                        terminalSession.sendRawBytes(bytes)
                    },
                    applicationCursorKeys = emulator.applicationCursorKeys,
                    controller = inputController,
                    modifier = Modifier.size(1.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.systemBars)
                .height(52.dp)
                .background(TerminalBg),
        ) {
            TerminalTopBar(
                onClose = {
                    terminalSession.stop()
                    onBack()
                },
                onClear = {
                    terminalSession.sendRawBytes(byteArrayOf(0x15))
                    terminalSession.clearOutput()
                    emulator.feed("\u001Bc".toByteArray())
                },
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .imePadding()
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            KeyboardAccessoryBar(
                ctrlActive = ctrlActive,
                keyboardVisible = inputController.isFocused,
                onCtrlToggle = { ctrlActive = !ctrlActive },
                onToggleKeyboard = {
                    if (inputController.isFocused) inputController.clearFocus()
                    else inputController.requestFocus()
                },
                onCopyAll = { copyAllToClipboard(context, emulator) },
                onSendRaw = { bytes ->
                    emulator.scrollOffset = 0
                    terminalSession.sendRawBytes(bytes)
                },
                onArrow = { dir ->
                    emulator.scrollOffset = 0
                    val prefix = if (emulator.applicationCursorKeys)
                        byteArrayOf(0x1B, 'O'.code.toByte())
                    else byteArrayOf(0x1B, '['.code.toByte())
                    terminalSession.sendRawBytes(prefix + byteArrayOf(dir.code.toByte()))
                },
            )
        }

        previewUrl?.let { url ->
            com.openminis.app.ui.components.UrlPreviewSheet(
                url = url,
                onDismiss = { previewUrl = null },
            )
        }
    }
}

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateEffect(): androidx.compose.runtime.State<T> {
    val state = remember { androidx.compose.runtime.mutableStateOf(value) }
    LaunchedEffect(this) { collect { state.value = it } }
    return state
}


@Composable
private fun TerminalTopBar(
    onClose: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalBg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularIconButton(
            icon = Icons.Default.Close,
            contentDescription = stringResource(R.string.common_close),
            tint = TerminalFg,
            onClick = onClose,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.terminal_title),
            color = TerminalFg,
            style = TextStyle(
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 16.sp,
            ),
        )
        Spacer(modifier = Modifier.weight(1f))
        CircularIconButton(
            icon = Icons.Default.Brush,
            contentDescription = stringResource(R.string.terminal_clear),
            tint = TerminalGreen,
            onClick = onClear,
        )
    }
}

@Composable
private fun CircularIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(TopButtonBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}


private fun copyAllToClipboard(context: Context, emulator: TerminalEmulator) {
    val text = emulator.copyableText()
    if (text.isEmpty()) return
    runCatching {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("Minis Shell", text))
    }.onSuccess {
        Toast.makeText(context, R.string.terminal_copied_all, Toast.LENGTH_SHORT).show()
    }.onFailure {
        com.openminis.app.logging.AppLogger.error("TerminalScreen", "copy all (${text.length} chars) failed: $it")
        Toast.makeText(
            context,
            context.getString(R.string.image_copy_failed_toast, it.message ?: it.javaClass.simpleName),
            Toast.LENGTH_SHORT,
        ).show()
    }
}


@Composable
private fun KeyboardAccessoryBar(
    ctrlActive: Boolean,
    keyboardVisible: Boolean,
    onCtrlToggle: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onCopyAll: () -> Unit,
    onSendRaw: (ByteArray) -> Unit,
    onArrow: (Char) -> Unit,
) {
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AccessoryBg),
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        QuickCommandButton(
            label = stringResource(if (keyboardVisible) R.string.terminal_hide_keyboard else R.string.terminal_show_keyboard),
            icon = if (keyboardVisible) Icons.Default.KeyboardHide else Icons.Outlined.Keyboard,
            onClick = onToggleKeyboard,
        )
        QuickCommandButton(
            label = stringResource(R.string.terminal_copy_all),
            icon = Icons.Outlined.ContentCopy,
            onClick = onCopyAll,
        )
        QuickCommandButton("Esc", iconText = "⎋") { onSendRaw(byteArrayOf(0x1B)) }
        QuickCommandButton("Tab", icon = Icons.AutoMirrored.Filled.KeyboardTab) { onSendRaw(byteArrayOf(0x09)) }
        QuickCommandButton("⏎", iconText = "⏎") { onSendRaw(byteArrayOf(0x0D)) }
        QuickCommandButton("Ctrl", iconText = "^", isActive = ctrlActive, onClick = onCtrlToggle)
        QuickCommandButton("\u2191", icon = Icons.Default.KeyboardArrowUp) { onArrow('A') }
        QuickCommandButton("\u2193", icon = Icons.Default.KeyboardArrowDown) { onArrow('B') }
        QuickCommandButton("\u2190", icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft) { onArrow('D') }
        QuickCommandButton("\u2192", icon = Icons.AutoMirrored.Filled.KeyboardArrowRight) { onArrow('C') }
        QuickCommandButton("C-c", icon = Icons.Outlined.Cancel) { onSendRaw(byteArrayOf(0x03)) }
        QuickCommandButton("C-d", icon = Icons.Default.Eject) { onSendRaw(byteArrayOf(0x04)) }
        QuickCommandButton("C-z", icon = Icons.Outlined.PauseCircle) { onSendRaw(byteArrayOf(0x1A)) }
        }
    }
}

@Composable
private fun QuickCommandButton(
    label: String,
    icon: ImageVector? = null,
    iconText: String? = null,
    isActive: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = if (isActive) AccButtonActive else AccButtonBg
    val fg = if (isActive) Color.White else TerminalGreen
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            icon != null -> Icon(icon, null, tint = fg, modifier = Modifier.size(12.dp))
            iconText != null -> Text(iconText, color = fg, style = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp))
        }
        Text(label, color = fg, style = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp), maxLines = 1)
    }
}
