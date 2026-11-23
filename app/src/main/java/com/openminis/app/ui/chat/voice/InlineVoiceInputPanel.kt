package com.openminis.app.ui.chat.voice

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.speech.RecognitionState
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import com.openminis.app.ui.components.rememberDecorativeTick
import com.openminis.app.ui.components.decorativePhase
import androidx.compose.ui.graphics.graphicsLayer


object VoiceModePrefs {

    private const val PREFS = "voice_prefs"
    private const val KEY_EXPANDED = "voice.panel.expanded"

    private var appContext: android.content.Context? = null

    var isVoiceActive by mutableStateOf(false)

    private var expandedState by mutableStateOf(true)

    var expanded: Boolean
        get() = expandedState
        set(value) {
            expandedState = value
            appContext?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                ?.edit()?.putBoolean(KEY_EXPANDED, value)?.apply()
        }

    var enteredFromText = false

    var lastSpokenAssistantKey: Int = 0

    fun init(context: android.content.Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        expandedState = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_EXPANDED, true)
    }
}

private val CompactHeight = 92.dp
private val ExpandedBase = 168.dp
private val CompactMic = 60.dp
private val ExpandedMic = 72.dp

@Composable
internal fun InlineVoiceInputPanel(
    providerRepository: ProviderRepository,
    inputText: String,
    onInputTextChange: (String) -> Unit,
    ensureMicPermission: suspend () -> Boolean,
    modifier: Modifier = Modifier,
    conversationContextProvider: (com.openminis.app.speech.correction.ScreenContextBuilder.Snapshot?) ->
        com.openminis.app.speech.correction.ConversationContext = {
        com.openminis.app.speech.correction.ConversationContext.EMPTY
    },
    captureScreen: () -> com.openminis.app.speech.correction.ScreenContextBuilder.Snapshot? = { null },
    contextUsageHint: com.openminis.app.ui.chat.ContextUsageHint? = null,
    onEditProvider: ((instanceId: String) -> Unit)? = null,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val sttState by SpeechRecognitionManager.state.collectAsState()
    val asrStalled by remember { SpeechRecognitionManager.asrStalled() }.collectAsState()
    val asrToastContext = LocalContext.current
    val systemAsrName = stringResource(R.string.voice_asr_system_recognition)
    val nextModelName = stringResource(R.string.voice_asr_next_model)
    val onSwitchModel: () -> Unit = {
        SpeechRecognitionManager.skipStalledTranscription(systemAsrName, nextModelName)?.let { name ->
            android.widget.Toast.makeText(
                asrToastContext,
                asrToastContext.getString(R.string.voice_asr_retrying_with, name),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }
    val locale by SpeechRecognitionManager.locale.collectAsState()
    val supportedLocales by SpeechRecognitionManager.supportedLocales.collectAsState()
    val levels by SpeechRecognitionManager.audioLevels.collectAsState()

    val panelContext = androidx.compose.ui.platform.LocalContext.current
    VoiceModePrefs.init(panelContext)

    var expanded by remember { mutableStateOf(VoiceModePrefs.expanded) }
    var transcript by remember { mutableStateOf(inputText) }
    var isEditing by remember { mutableStateOf(false) }

    var shownUsageHint by remember {
        mutableStateOf<com.openminis.app.ui.chat.ContextUsageHint?>(null)
    }
    LaunchedEffect(contextUsageHint?.generation) {
        val hint = contextUsageHint ?: return@LaunchedEffect
        shownUsageHint = hint
        kotlinx.coroutines.delay(3_500)
        if (shownUsageHint?.generation == hint.generation) shownUsageHint = null
    }

    var editSnapshot by remember { mutableStateOf<String?>(null) }

    var isCorrecting by remember { mutableStateOf(false) }
    var showCorrectionConsent by remember { mutableStateOf(false) }

    fun leaveEditMode() {
        val before = editSnapshot
        editSnapshot = null
        isEditing = false
        if (before != null) {
            com.openminis.app.speech.correction.VoiceCorrection.captureTranscriptEdit(
                context = panelContext,
                before = before,
                after = transcript,
            )
        }
    }
    var permissionDenied by remember { mutableStateOf(false) }
    var transcribeError by remember { mutableStateOf<String?>(null) }
    var recordingTipIndex by remember { mutableIntStateOf(0) }
    var resultTipIndex by remember { mutableIntStateOf(0) }
    var showModelSelector by remember { mutableStateOf(false) }
    var showLanguageMenu by remember { mutableStateOf(false) }
    var captureBase by remember { mutableStateOf("") }

    val isRecording = sttState == RecognitionState.RECORDING || sttState == RecognitionState.STARTING
    val isTranscribing = sttState == RecognitionState.FINISHING

    val config by providerRepository.config.collectAsState()
    val choice = remember(config) { providerRepository.resolveVoiceInputChoice() }
    val groupName = remember(config) { providerRepository.voiceInputGroupName() }
    val modelLabel = if (choice.isSystem) {
        stringResource(
            if (choice.systemPreferOffline == true) R.string.voice_system_recognition_offline
            else R.string.voice_system_recognition_online,
        )
    } else {
        choice.entry!!.second.model.displayName
    }

    fun persistExpanded(v: Boolean) {
        expanded = v
        VoiceModePrefs.expanded = v
    }

    fun setTranscript(text: String) {
        transcript = text
        VoiceSendGate.transcript = text
        if (inputText != text) onInputTextChange(text)
    }

    fun runCorrection() {
        val text = transcript.trim()
        if (text.isEmpty() || isCorrecting) return
        val engine = com.openminis.app.speech.correction.VoiceCorrection.engine(panelContext)
        if (engine == null) {
            android.widget.Toast.makeText(
                panelContext,
                panelContext.getString(R.string.voice_correction_failed),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
        isCorrecting = true
        val startGeneration = VoiceSendGate.generation
        val screenSnapshot = runCatching { captureScreen() }.getOrNull()
        scope.launch {
            val convoContext = withContext(Dispatchers.Default) {
                runCatching { conversationContextProvider(screenSnapshot) }
                    .getOrDefault(com.openminis.app.speech.correction.ConversationContext.EMPTY)
            }
            val suggestion = engine.correct(text, context = convoContext)
            isCorrecting = false
            if (transcript.trim() != text || VoiceSendGate.generation != startGeneration) {
                com.openminis.app.speech.VoicePipelineLog.event(
                    "correction.stale",
                    "changed" to (transcript.trim() != text),
                    "sent" to (VoiceSendGate.generation != startGeneration),
                )
                return@launch
            }
            when {
                suggestion.hasChange -> {
                    VoiceSendGate.abandonFinishForSend("AI correction")
                    setTranscript(suggestion.corrected)
                    com.openminis.app.speech.correction.VoiceCorrection
                        .captureSuggestionAccepted(
                            panelContext,
                            suggestion.original,
                            suggestion.corrected,
                            suggestion.diffSummary,
                        )
                }
                suggestion.rejectedReason == null ||
                    suggestion.rejectedReason == "empty_input" -> {
                    android.widget.Toast.makeText(
                        panelContext,
                        panelContext.getString(R.string.voice_correction_none_needed),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                else -> {
                    val reason = com.openminis.app.speech.correction.CorrectionFailureReason.res(suggestion)
                    val message = panelContext.getString(R.string.voice_correction_failed) +
                        (reason?.let { "\n" + panelContext.getString(it) } ?: "")
                    android.widget.Toast.makeText(
                        panelContext,
                        message,
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    fun stopCapture() {
        com.openminis.app.speech.VoicePipelineLog.event("panel.stop")
        SpeechRecognitionManager.stopRecording()
    }

    fun onRecognized(text: String, isFinal: Boolean) {
        if (isEditing) {
            if (isFinal) com.openminis.app.speech.VoicePipelineLog.event("panel.drop", "why" to "editing", "chars" to text.length)
            return
        }
        if (!isFinal) return
        if (text.isBlank()) {
            com.openminis.app.speech.VoicePipelineLog.event("panel.drop", "why" to "blank")
            return
        }
        val sep = if (captureBase.isEmpty() || captureBase.endsWith(" ")) "" else " "
        val joined = captureBase + sep + text
        captureBase = joined
        setTranscript(joined)
        com.openminis.app.speech.VoicePipelineLog.event("panel.commit", "chars" to joined.length)
    }

    fun onRecognitionError(error: com.openminis.app.speech.RecognitionError, message: String?) {
        com.openminis.app.speech.VoicePipelineLog.event("panel.error", "kind" to error.name)
        when (error) {
            com.openminis.app.speech.RecognitionError.NO_MATCH -> {}
            com.openminis.app.speech.RecognitionError.PERMISSION_DENIED ->
                permissionDenied = true
            com.openminis.app.speech.RecognitionError.MIC_IN_USE ->
                transcribeError = panelContext.getString(R.string.voice_mic_in_use)
            com.openminis.app.speech.RecognitionError.AUDIO_ERROR ->
                transcribeError = panelContext.getString(R.string.voice_mic_unavailable)
            else -> transcribeError = message ?: error.name
        }
    }

    fun failureSink(captureGeneration: Int): (SpeechRecognitionManager.FailedAudio) -> Unit = { failed ->
        com.openminis.app.speech.VoicePipelineLog.event("panel.failedAudio", "kind" to failed.error.name)
        VoiceSendGate.recordFailure(failed, captureGeneration)
    }

    fun startCapture() {
        val engineId = if (choice.isSystem) "system" else "provider"
        com.openminis.app.speech.VoicePipelineLog.event("panel.start", "engine" to engineId)
        SpeechRecognitionManager.selectEngine(engineId)
        (SpeechRecognitionManager.availableEngines()
            .firstOrNull { it.id == "system" } as? com.openminis.app.speech.SystemSpeechRecognitionEngine)
            ?.preferOffline = (choice.systemPreferOffline == true)
        captureBase = transcript
        transcribeError = null
        SpeechRecognitionManager.startRecording(
            onPartialOrFinal = { text, isFinal -> onRecognized(text, isFinal) },
            onError = { error, message -> onRecognitionError(error, message) },
            onFailedAudio = failureSink(VoiceSendGate.generation),
        )
    }

    fun retryFailedUtterance() {
        val item = VoiceSendGate.takeForRetry() ?: return
        captureBase = transcript
        transcribeError = null
        val started = SpeechRecognitionManager.retryTranscription(
            failed = item.audio,
            onPartialOrFinal = { text, isFinal -> onRecognized(text, isFinal) },
            onError = { error, message -> onRecognitionError(error, message) },
            onFailedAudio = failureSink(VoiceSendGate.generation),
        )
        if (!started) VoiceSendGate.requeue(item)
    }

    fun handleMicTap() {
        when {
            isTranscribing -> {
                VoiceSendGate.abandonFinishForSend("transcription cancelled")
                com.openminis.app.speech.VoicePipelineLog.event("panel.cancel", "src" to "micTap")
                SpeechRecognitionManager.cancelRecording()
            }
            isRecording -> stopCapture()
            else -> scope.launch {
                VoiceSendGate.abandonFinishForSend("mic tapped to record again")
                if (!ensureMicPermission()) {
                    permissionDenied = true
                    return@launch
                }
                permissionDenied = false
                if (isEditing) leaveEditMode()
                startCapture()
            }
        }
    }

    LaunchedEffect(Unit) {
        providerRepository.ensureDefaultVoiceInputGroup()
        providerRepository.ensureDefaultVoiceOutputGroup()
        com.openminis.app.speech.correction.VoiceCorrection.warmUp(panelContext)
        transcript = inputText
        VoiceSendGate.transcript = inputText
        if (VoiceModePrefs.enteredFromText) {
            VoiceModePrefs.enteredFromText = false
            persistExpanded(true)
        }
        SpeechRecognitionManager.refreshSupportedLocales()
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            VoiceSendGate.abandonFinishForSend("voice panel closed")
            if (SpeechRecognitionManager.state.value != RecognitionState.IDLE) {
                com.openminis.app.speech.VoicePipelineLog.event("panel.cancel", "src" to "dispose")
                SpeechRecognitionManager.cancelRecording()
            }
        }
    }

    LaunchedEffect(inputText) {
        if (inputText != transcript) {
            transcript = inputText
            VoiceSendGate.transcript = inputText
            captureBase = inputText
            if (inputText.isEmpty()) {
                VoiceSendGate.bumpGeneration()
                if (SpeechRecognitionManager.state.value != RecognitionState.IDLE) {
                    com.openminis.app.speech.VoicePipelineLog.event("panel.cancel", "src" to "inputCleared", "state" to SpeechRecognitionManager.state.value)
                    SpeechRecognitionManager.cancelRecording()
                }
                leaveEditMode()
                persistExpanded(false)
            } else if (!expanded) {
                persistExpanded(true)
            }
        }
    }
    LaunchedEffect(transcript) {
        if (transcript.isNotEmpty() && !expanded) persistExpanded(true)
    }

    LaunchedEffect(isRecording) {
        recordingTipIndex = 0
        while (isRecording) {
            delay(4000)
            recordingTipIndex += 1
        }
    }
    LaunchedEffect(transcript.isNotEmpty(), isRecording) {
        resultTipIndex = 0
        while (!isRecording && transcript.isNotEmpty()) {
            delay(4000)
            resultTipIndex += 1
        }
    }

    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val maxPanel = screenH * 0.6f

    val engineAvailable by SpeechRecognitionManager.isAvailable.collectAsState()
    if (!engineAvailable) {
        VoiceEngineUnavailableNotice(
            expanded = expanded,
            onToggleExpanded = { persistExpanded(!expanded) },
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (expanded) ExpandedBase else CompactHeight, max = maxPanel)
            .animateContentSize(animationSpec = tween(280))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            CircleIconButton(
                icon = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = stringResource(
                    if (expanded) R.string.voice_panel_collapse else R.string.voice_panel_expand,
                ),
                modifier = Modifier.align(Alignment.TopStart),
            ) { persistExpanded(!expanded) }

            if (showCorrectionConsent) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = {
                        showCorrectionConsent = false
                    },
                    title = { Text(stringResource(R.string.voice_correction_consent_title)) },
                    text = { Text(stringResource(R.string.voice_correction_consent_body)) },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            com.openminis.app.speech.correction.VoiceCorrectionConsent
                                .setEnabled(panelContext, true)
                            com.openminis.app.speech.correction.VoiceCorrectionConsent
                                .setPrompted(panelContext, true)
                            showCorrectionConsent = false
                            runCorrection()
                        }) {
                            Text(stringResource(R.string.voice_correction_consent_enable))
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            com.openminis.app.speech.correction.VoiceCorrectionConsent
                                .setPrompted(panelContext, true)
                            showCorrectionConsent = false
                            runCorrection()
                        }) {
                            Text(stringResource(R.string.voice_correction_consent_not_now))
                        }
                    },
                )
            }

            if (!isEditing && transcript.isNotBlank()) {
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    if (isCorrecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = ChatColors.secondaryText,
                        )
                    } else {
                        CircleIconButton(
                            icon = Icons.Default.AutoAwesome,
                            contentDescription = stringResource(
                                R.string.voice_correction_button_a11y,
                            ),
                        ) {
                            if (!com.openminis.app.speech.correction.VoiceCorrectionConsent
                                    .hasPrompted(panelContext)
                            ) {
                                showCorrectionConsent = true
                            } else {
                                runCorrection()
                            }
                        }
                    }
                }
            } else if (!isEditing) {
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    CircleIconButton(
                        icon = Icons.Default.Language,
                        contentDescription = stringResource(R.string.voice_panel_language),
                    ) { showLanguageMenu = true }
                    DropdownMenu(
                        expanded = showLanguageMenu,
                        onDismissRequest = { showLanguageMenu = false },
                    ) {
                        val options = supportedLocales.ifEmpty { listOf(locale) }
                        options.forEach { opt ->
                            DropdownMenuItem(
                                text = {
                                    val selected = opt.toLanguageTag() == locale.toLanguageTag()
                                    Text(
                                        (if (selected) "✓ " else "") + opt.getDisplayName(opt),
                                    )
                                },
                                onClick = {
                                    SpeechRecognitionManager.selectLocale(opt)
                                    showLanguageMenu = false
                                },
                            )
                        }
                    }
                }
            }

            if (expanded) {
                ExpandedContent(
                    transcript = transcript,
                    isEditing = isEditing,
                    isRecording = isRecording,
                    isTranscribing = isTranscribing,
                    permissionDenied = permissionDenied,
                    transcribeError = transcribeError,
                    recordingTipIndex = recordingTipIndex,
                    resultTipIndex = resultTipIndex,
                    levels = levels,
                    groupName = groupName,
                    modelLabel = modelLabel,
                    contextUsageLine = shownUsageHint,
                    onMicTap = { handleMicTap() },
                    onTranscriptChange = {
                        VoiceSendGate.abandonFinishForSend("text edited")
                        setTranscript(it)
                    },
                    onBeginEdit = {
                        VoiceSendGate.abandonFinishForSend("editing")
                        if (!isEditing) {
                            if (SpeechRecognitionManager.state.value != RecognitionState.IDLE) {
                                com.openminis.app.speech.VoicePipelineLog.event("panel.cancel", "src" to "beginEdit")
                                SpeechRecognitionManager.cancelRecording()
                            }
                            editSnapshot = transcript
                            isEditing = true
                        }
                    },
                    onExitEdit = { leaveEditMode() },
                    onDeleteLast = {
                        VoiceSendGate.abandonFinishForSend("backspace")
                        if (transcript.isNotEmpty()) setTranscript(transcript.dropLast(1))
                    },
                    onOpenSelector = { showModelSelector = true },
                    asrStalled = asrStalled,
                    onSwitchModel = onSwitchModel,
                )
            } else {
                CompactContent(
                    transcript = transcript,
                    isRecording = isRecording,
                    isTranscribing = isTranscribing,
                    levels = levels,
                    onMicTap = { handleMicTap() },
                    asrStalled = asrStalled,
                    onSwitchModel = onSwitchModel,
                )
            }
        }
    }

    val failedPrompt = VoiceSendGate.queue.current
    if (failedPrompt != null && sttState == RecognitionState.IDLE && !isEditing) {
        val failed = failedPrompt.audio
        val reason = if (failed.error == com.openminis.app.speech.RecognitionError.TIMED_OUT) {
            stringResource(R.string.voice_asr_timed_out)
        } else {
            failed.message?.takeIf { it.isNotBlank() } ?: failed.error.name
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.voice_asr_failed_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.voice_asr_failed_message,
                        kotlin.math.round(failed.seconds).toInt(),
                        reason,
                    ),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { retryFailedUtterance() }) {
                    Text(stringResource(R.string.voice_asr_retry))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { VoiceSendGate.discardCurrent() }) {
                    Text(stringResource(R.string.voice_asr_discard))
                }
            },
        )
    }

    val reopenSelectorOnResume = com.openminis.app.ui.components.rememberReopenOnResume {
        showModelSelector = true
    }
    if (showModelSelector) {
        VoiceInputPickerSheet(
            providerRepository = providerRepository,
            onDismiss = { showModelSelector = false },
            onEditProvider = onEditProvider?.let { open ->
                { id: String ->
                    showModelSelector = false
                    reopenSelectorOnResume()
                    open(id)
                }
            },
        )
    }
}


@Composable
private fun ExpandedContent(
    transcript: String,
    isEditing: Boolean,
    isRecording: Boolean,
    isTranscribing: Boolean,
    permissionDenied: Boolean,
    transcribeError: String?,
    recordingTipIndex: Int,
    resultTipIndex: Int,
    levels: List<Float>,
    groupName: String?,
    modelLabel: String,
    onMicTap: () -> Unit,
    onTranscriptChange: (String) -> Unit,
    onBeginEdit: () -> Unit,
    onExitEdit: () -> Unit,
    onDeleteLast: () -> Unit,
    onOpenSelector: () -> Unit,
    contextUsageLine: com.openminis.app.ui.chat.ContextUsageHint? = null,
    asrStalled: Boolean = false,
    onSwitchModel: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isEditing || transcript.isNotEmpty()) {
            TranscriptArea(
                transcript = transcript,
                isEditing = isEditing,
                onTranscriptChange = onTranscriptChange,
                onBeginEdit = onBeginEdit,
                onClearAll = { onTranscriptChange("") },
            )
            Spacer(Modifier.height(6.dp))
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 36.dp),
        ) {
            val usageLine = contextUsageLine?.takeIf { !isRecording && !isTranscribing }
            if (usageLine != null) {
                Text(
                    text = com.openminis.app.ui.chat.contextUsageAnnotatedText(usageLine),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ChatColors.secondaryText,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = statusLabel(
                        permissionDenied, isEditing, isRecording, isTranscribing,
                        transcript, recordingTipIndex, resultTipIndex,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ChatColors.secondaryText,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            if (isEditing) {
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.voice_panel_exit_edit),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ChatColors.secondaryText,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(ChatColors.secondaryText.copy(alpha = 0.15f))
                        .clickable { onExitEdit() }
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }

        if (transcribeError != null) {
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFFF9500).copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text("⚠︎", fontSize = 10.sp, color = Color(0xFFFF9500))
                Spacer(Modifier.width(5.dp))
                Text(
                    transcribeError,
                    style = MaterialTheme.typography.labelSmall,
                    color = ChatColors.secondaryText,
                    maxLines = 2,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            MicCircleButton(
                diameter = ExpandedMic,
                isRecording = isRecording,
                isTranscribing = isTranscribing,
                levels = levels,
                enabled = true,
                onTap = onMicTap,
            )
            androidx.compose.animation.Crossfade(targetState = asrStalled, label = "asrStallSwap") { stalled ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (stalled) {
                        SwitchModelPill(onClick = onSwitchModel)
                        Spacer(Modifier.width(16.dp))
                    } else if (transcript.isNotEmpty() && !isEditing) {
                        VoiceDeleteButton(onDeleteOne = onDeleteLast)
                        Spacer(Modifier.width(30.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(ChatColors.secondaryText.copy(alpha = 0.1f))
                .clickable { onOpenSelector() }
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            if (groupName != null) {
                Text(
                    groupName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "  ·  ",
                    style = MaterialTheme.typography.labelSmall,
                    color = ChatColors.secondaryText.copy(alpha = 0.5f),
                )
            }
            Text(
                modelLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            Spacer(Modifier.width(3.dp))
            Icon(
                Icons.Default.UnfoldMore,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = ChatColors.secondaryText.copy(alpha = 0.6f),
            )
        }
    }
}


@Composable
private fun CompactContent(
    transcript: String,
    isRecording: Boolean,
    isTranscribing: Boolean,
    levels: List<Float>,
    onMicTap: () -> Unit,
    asrStalled: Boolean = false,
    onSwitchModel: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(CompactHeight - 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (transcript.isNotEmpty()) {
            Text(
                stringResource(R.string.voice_panel_buffered_chars, transcript.length),
                style = MaterialTheme.typography.labelSmall,
                color = ChatColors.secondaryText,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
        }
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            MicCircleButton(
                diameter = CompactMic,
                isRecording = isRecording,
                isTranscribing = isTranscribing,
                levels = levels,
                enabled = true,
                onTap = onMicTap,
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = asrStalled,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SwitchModelPill(onClick = onSwitchModel)
                    Spacer(Modifier.width(16.dp))
                }
            }
        }
    }
}

@Composable
private fun SwitchModelPill(onClick: () -> Unit) {
    val a11y = stringResource(R.string.voice_asr_switch_model_a11y)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .semantics { contentDescription = a11y }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Icon(
            Icons.Default.SkipNext,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(R.string.voice_asr_switch_model),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}


@Composable
private fun TranscriptArea(
    transcript: String,
    isEditing: Boolean,
    onTranscriptChange: (String) -> Unit,
    onBeginEdit: () -> Unit,
    onClearAll: () -> Unit,
) {
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val bandMax = screenH * 0.6f - ExpandedBase
    if (isEditing) {
        VoiceTranscriptEditor(
            value = transcript,
            onValueChange = onTranscriptChange,
            maxHeight = bandMax,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 28.dp, max = bandMax)
                .padding(horizontal = 32.dp),
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = bandMax)
                .padding(horizontal = 32.dp)
                .verticalScroll(rememberScrollState())
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { onBeginEdit() },
                        onLongPress = { onClearAll() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                transcript,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun Modifier.micShadowNoOffset(
    spread: androidx.compose.ui.unit.Dp,
    color: Color = Color.Black,
    maxAlpha: Float = 0.10f,
): Modifier = this.drawBehind {
    val r = size.minDimension / 2f
    val spreadPx = spread.toPx()
    val outer = r + spreadPx
    val edge = r / outer
    drawCircle(
        brush = androidx.compose.ui.graphics.Brush.radialGradient(
            colorStops = arrayOf(
                0f to color.copy(alpha = maxAlpha),
                edge to color.copy(alpha = maxAlpha),
                (edge + (1f - edge) * 0.45f) to color.copy(alpha = maxAlpha * 0.35f),
                1f to Color.Transparent,
            ),
            center = center,
            radius = outer,
        ),
        radius = outer,
        center = center,
    )
}

@Composable
private fun MicCircleButton(
    diameter: androidx.compose.ui.unit.Dp,
    isRecording: Boolean,
    isTranscribing: Boolean,
    levels: List<Float>,
    enabled: Boolean,
    onTap: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(diameter)
            .micShadowNoOffset(10.dp)
            .background(Color.White, CircleShape)
            .clip(CircleShape)
            .clickable(enabled = enabled) { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        if (isTranscribing) {
            TranscribingRing(
                diameter = diameter,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        when {
            isRecording -> InlineMiniWaveform(levels = levels)
            isTranscribing -> Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.voice_panel_cancel_transcription),
                tint = Color.Black,
                modifier = Modifier.size(22.dp),
            )
            else -> Icon(
                Icons.Default.Mic,
                contentDescription = stringResource(R.string.voice_panel_toggle_recording),
                tint = Color.Black,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun TranscribingRing(
    diameter: androidx.compose.ui.unit.Dp,
    color: Color,
) {
    val stroke = 2.5.dp
    val tick = rememberDecorativeTick()
    Canvas(modifier = Modifier.size(diameter).graphicsLayer { rotationZ = decorativePhase(tick.value, 900) * 360f }) {
        val strokePx = stroke.toPx()
        val inset = strokePx / 2f
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 75f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(
                size.width - strokePx,
                size.height - strokePx,
            ),
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun InlineMiniWaveform(levels: List<Float>) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val tail = levels.takeLast(7)
        tail.forEachIndexed { index, level ->
            val animated by animateFloatAsState(
                targetValue = level,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 900f),
                label = "voiceBar$index",
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((5 + animated * 21).dp.coerceAtMost(26.dp))
                    .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(50)),
            )
        }
    }
}

@Composable
private fun VoiceDeleteButton(onDeleteOne: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var repeating by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(47.dp)
            .background(ChatColors.inputIconBg, CircleShape)
            .border(0.5.dp, ChatColors.inputIconBorder, CircleShape)
            .clip(CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onDeleteOne() },
                    onPress = {
                        val job = scope.launch {
                            delay(450)
                            repeating = true
                            while (repeating) {
                                onDeleteOne()
                                delay(120)
                            }
                        }
                        tryAwaitRelease()
                        repeating = false
                        job.cancel()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Backspace,
            contentDescription = stringResource(R.string.voice_panel_delete),
            tint = ChatColors.secondaryText,
            modifier = Modifier.size(21.dp),
        )
    }
}

@Composable
private fun CircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(30.dp)
            .background(ChatColors.inputIconBg, CircleShape)
            .border(0.5.dp, ChatColors.inputIconBorder, CircleShape)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = ChatColors.secondaryText,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun statusLabel(
    permissionDenied: Boolean,
    isEditing: Boolean,
    isRecording: Boolean,
    isTranscribing: Boolean,
    transcript: String,
    recordingTipIndex: Int,
    resultTipIndex: Int,
): String {
    if (permissionDenied) return stringResource(R.string.voice_panel_permission_denied)
    if (isEditing) return stringResource(R.string.voice_panel_editing)
    if (isRecording) {
        val tips = listOf(
            stringResource(R.string.voice_panel_tip_tap_to_transcribe),
            stringResource(R.string.voice_panel_tip_listening),
            stringResource(R.string.voice_panel_tip_hold_delete),
            stringResource(R.string.voice_panel_tip_listening),
        )
        return tips[recordingTipIndex % tips.size]
    }
    if (isTranscribing) return stringResource(R.string.voice_panel_recognizing)
    if (transcript.isNotEmpty()) {
        val tips = listOf(
            stringResource(R.string.voice_panel_tip_double_tap_edit),
            stringResource(R.string.voice_panel_tip_tap_to_continue),
            stringResource(R.string.voice_panel_tip_hold_delete),
        )
        return tips[resultTipIndex % tips.size]
    }
    return stringResource(R.string.voice_panel_tap_to_speak)
}

@Composable
private fun VoiceEngineUnavailableNotice(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (expanded) ExpandedBase else CompactHeight)
            .animateContentSize(animationSpec = tween(280))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            CircleIconButton(
                icon = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = stringResource(
                    if (expanded) R.string.voice_panel_collapse else R.string.voice_panel_expand,
                ),
                modifier = Modifier.align(Alignment.TopStart),
            ) { onToggleExpanded() }

            Text(
                text = stringResource(R.string.voice_panel_no_engine_title),
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 44.dp),
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.voice_panel_no_engine_body),
            style = TextStyle(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            NoticeActionButton(stringResource(R.string.voice_panel_no_engine_open_system)) {
                val opened = runCatching {
                    ctx.startActivity(
                        android.content.Intent("android.settings.VOICE_INPUT_SETTINGS")
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    true
                }.getOrDefault(false)
                if (!opened) {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            NoticeActionButton(stringResource(R.string.voice_panel_no_engine_open_providers)) {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("minis://settings/providers"),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }
}

@Composable
private fun NoticeActionButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(ChatColors.inputIconBg, RoundedCornerShape(14.dp))
            .border(0.5.dp, ChatColors.inputIconBorder, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
