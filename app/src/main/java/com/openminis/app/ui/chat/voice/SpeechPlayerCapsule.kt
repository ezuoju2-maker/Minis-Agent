package com.openminis.app.ui.chat.voice

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.speech.VoiceOutputState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import com.openminis.app.ui.components.rememberDecorativeTick
import com.openminis.app.ui.components.decorativePhase
import androidx.compose.ui.graphics.graphicsLayer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechPlayerCapsule(
    modifier: Modifier = Modifier,
    bottomObstructionPx: Int = 0,
    additionalObstructionDp: androidx.compose.ui.unit.Dp = 0.dp,
    onEditProvider: ((instanceId: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { VoiceOutputState.init(context) }

    val enabled by VoiceOutputState.isEnabled.collectAsState()
    val muted by VoiceOutputState.isMuted.collectAsState()
    val speed by VoiceOutputState.speed.collectAsState()
    val speaking by VoiceOutputState.isSpeaking.collectAsState()
    val synthesizing by VoiceOutputState.isSynthesizing.collectAsState()
    val modelLabel by VoiceOutputState.activeModelLabel.collectAsState()
    val systemVoiceLabel by VoiceOutputState.systemVoiceLabel.collectAsState()

    val isActive = enabled || speaking || synthesizing
    if (!isActive) return

    var expanded by remember { mutableStateOf(false) }
    var idleToken by remember { mutableIntStateOf(0) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showVolume by remember { mutableStateOf(false) }
    val volume by VoiceOutputState.volume.collectAsState()

    LaunchedEffect(speaking) { if (speaking && expanded) expanded = false }

    LaunchedEffect(expanded, idleToken) {
        if (!expanded) {
            showVolume = false
            return@LaunchedEffect
        }
        delay(10_000)
        expanded = false
    }
    fun bumpIdle() { idleToken++ }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (expanded) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { expanded = false } },
            )
        }
        val liftDp = with(androidx.compose.ui.platform.LocalDensity.current) {
            bottomObstructionPx.toDp()
        }
        val toolbarLift = if (bottomObstructionPx > 0) liftDp + 8.dp else 0.dp
        val fabLift =
            if (additionalObstructionDp > 0.dp) additionalObstructionDp + 8.dp - 12.dp else 0.dp
        val animatedLift by androidx.compose.animation.core.animateDpAsState(
            targetValue = maxOf(toolbarLift, fabLift, 0.dp),
            label = "capsuleLift",
        )
        val density = androidx.compose.ui.platform.LocalDensity.current
        var dragY by remember { mutableStateOf(0f) }
        var isDragging by remember { mutableStateOf(false) }
        var capsuleH by remember { mutableStateOf(0) }

        val edgeMarginPx = with(density) { 16.dp.toPx() }
        val restBottomPx = with(density) { (12.dp + animatedLift).toPx() }
        val maxHPx = with(density) { maxHeight.toPx() }

        fun clampY(py: Float): Float {
            if (capsuleH <= 0 || maxHPx <= 0f) return py
            val restTop = maxHPx - restBottomPx - capsuleH
            val minY = edgeMarginPx - restTop
            val maxY = (maxHPx - edgeMarginPx - capsuleH) - restTop
            return if (minY <= maxY) py.coerceIn(minY, maxY) else 0f
        }

        val savedOffset by VoiceOutputState.dragOffsetDp.collectAsState()
        LaunchedEffect(savedOffset, capsuleH, maxHPx, isDragging) {
            if (isDragging || capsuleH <= 0) return@LaunchedEffect
            val targetY = savedOffset?.let { (_, yDp) ->
                with(density) { yDp.dp.toPx() }
            } ?: 0f
            dragY = clampY(targetY)
        }

        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 12.dp + animatedLift)
                .offset { IntOffset(0, dragY.toInt()) }
                .onGloballyPositioned { capsuleH = it.size.height }
                .pointerInput(expanded) {
                    detectVerticalDragGestures(
                        onDragStart = { isDragging = true },
                        onDragEnd = {
                            isDragging = false
                            bumpIdle()
                            with(density) {
                                VoiceOutputState.setCapsuleDragOffsetDp(
                                    0f,
                                    dragY.toDp().value,
                                )
                            }
                        },
                        onDragCancel = { isDragging = false },
                    ) { change, dragAmountY ->
                        change.consume()
                        dragY = clampY(dragY + dragAmountY)
                    }
                },
        ) {
            AnimatedContent(
                targetState = expanded,
                transitionSpec = {
                    (scaleIn(initialScale = 0.6f) + fadeIn())
                        .togetherWith(scaleOut(targetScale = 0.6f) + fadeOut())
                },
                label = "capsule",
            ) { isExpanded ->
                if (isExpanded) {
                    var capsuleWidthPx by remember { mutableIntStateOf(0) }
                    Column(horizontalAlignment = Alignment.End) {
                        AnimatedVisibility(
                            visible = showVolume && capsuleWidthPx > 0,
                            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
                        ) {
                            VolumeBar(
                                volume = volume,
                                muted = muted,
                                width = with(density) { capsuleWidthPx.toDp() },
                                onMuteToggle = { VoiceOutputState.setMuted(!muted); bumpIdle() },
                                onVolumeChange = { VoiceOutputState.setVolume(it, persist = false); bumpIdle() },
                                onVolumeChangeFinished = { VoiceOutputState.persistVolume() },
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        ExpandedCapsule(
                            muted = muted,
                            synthesizing = synthesizing,
                            speed = speed,
                            volumeOpen = showVolume,
                            modelLabel = modelLabel
                                ?: systemVoiceLabel
                                ?: stringResource(R.string.tts_capsule_system_engine),
                            onSpeakerTap = { showVolume = !showVolume; bumpIdle() },
                            onModelTap = { showModelPicker = true; bumpIdle() },
                            onSpeedTap = { VoiceOutputState.nextSpeed(); bumpIdle() },
                            onClose = { VoiceOutputState.setEnabled(false) },
                            modifier = Modifier.onSizeChanged { capsuleWidthPx = it.width },
                        )
                    }
                } else {
                    CompactSpeaker(
                        muted = muted,
                        synthesizing = synthesizing,
                        speed = speed,
                        onTap = { expanded = true; bumpIdle() },
                        onDoubleTap = { VoiceOutputState.setMuted(!muted) },
                    )
                }
            }
        }
    }

    val reopenPickerOnResume = com.openminis.app.ui.components.rememberReopenOnResume {
        showModelPicker = true
    }
    if (showModelPicker) {
        val repo = (LocalContext.current.applicationContext as? MinisApp)?.providerRepository
        if (repo != null) {
            VoiceOutputPickerSheet(
                providerRepository = repo,
                onDismiss = { showModelPicker = false; bumpIdle() },
                onEditProvider = onEditProvider?.let { open ->
                    { id: String ->
                        showModelPicker = false
                        reopenPickerOnResume()
                        open(id)
                    }
                },
            )
        }
    }
}

@Composable
private fun CompactSpeaker(
    muted: Boolean,
    synthesizing: Boolean,
    speed: Float,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
) {
    Box {
        Box(
            Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), CircleShape)
                .border(0.5.dp, Color.Gray.copy(alpha = 0.25f), CircleShape)
                .pointerInput(muted) {
                    detectTapGestures(
                        onDoubleTap = { onDoubleTap() },
                        onTap = { onTap() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            SpeakerGlyph(muted = muted, synthesizing = synthesizing, ring = 40.dp)
        }
        if (speed > 1.0f) {
            Text(
                VoiceOutputState.speedLabel(speed),
                fontSize = 9.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 6.dp, y = 4.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun ExpandedCapsule(
    muted: Boolean,
    synthesizing: Boolean,
    speed: Float,
    volumeOpen: Boolean,
    modelLabel: String,
    onSpeakerTap: () -> Unit,
    onModelTap: () -> Unit,
    onSpeedTap: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val volumeLabel = stringResource(R.string.read_aloud_volume)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(40.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), RoundedCornerShape(50))
            .border(0.5.dp, Color.Gray.copy(alpha = 0.25f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp),
    ) {
        Box(
            Modifier
                .size(28.dp)
                .background(
                    if (volumeOpen) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                    CircleShape,
                )
                .clickable(onClick = onSpeakerTap)
                .semantics { contentDescription = volumeLabel },
            contentAlignment = Alignment.Center,
        ) {
            SpeakerGlyph(muted = muted, synthesizing = synthesizing, ring = 28.dp)
        }
        Spacer(Modifier.width(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.widthIn(max = 130.dp).clickable(onClick = onModelTap),
        ) {
            Icon(
                Icons.Default.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                modelLabel,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(3.dp))
            Icon(
                Icons.Default.UnfoldMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(12.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(width = 46.dp, height = 22.dp)
                .background(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                    RoundedCornerShape(50),
                )
                .clickable(onClick = onSpeedTap),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                VoiceOutputState.speedLabel(speed),
                maxLines = 1,
                color = if (speed > 1.0f) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 11.sp,
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                        includeFontPadding = false,
                    ),
                ),
            )
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            Icons.Default.Close,
            contentDescription = stringResource(R.string.standard_sheet_close),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp).clickable(onClick = onClose),
        )
    }
}

@Composable
private fun VolumeBar(
    volume: Float,
    muted: Boolean,
    width: androidx.compose.ui.unit.Dp,
    onMuteToggle: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onVolumeChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val percent = (volume * 100).roundToInt()
    val tint = MaterialTheme.colorScheme.primary
    val label = stringResource(R.string.read_aloud_volume)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .width(width)
            .height(40.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), RoundedCornerShape(50))
            .border(0.5.dp, Color.Gray.copy(alpha = 0.25f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp),
    ) {
        Box(
            Modifier.size(28.dp).clickable(onClick = onMuteToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when {
                    muted -> Icons.AutoMirrored.Filled.VolumeOff
                    percent == 0 -> Icons.AutoMirrored.Filled.VolumeMute
                    percent < 50 -> Icons.AutoMirrored.Filled.VolumeDown
                    else -> Icons.AutoMirrored.Filled.VolumeUp
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Slider(
            value = volume,
            onValueChange = onVolumeChange,
            onValueChangeFinished = onVolumeChangeFinished,
            valueRange = 0f..VoiceOutputState.MAX_VOLUME,
            colors = SliderDefaults.colors(
                thumbColor = tint,
                activeTrackColor = tint,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
            ),
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .padding(horizontal = 6.dp)
                .semantics { contentDescription = label },
        )
        Text(
            "$percent%",
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.End,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun SpeakerGlyph(muted: Boolean, synthesizing: Boolean, ring: androidx.compose.ui.unit.Dp) {
    Box(contentAlignment = Alignment.Center) {
        Icon(
            if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = null,
            tint = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        if (synthesizing && !muted) {
            val tick = rememberDecorativeTick()
            val arcColor = MaterialTheme.colorScheme.primary
            Canvas(Modifier.size(ring).graphicsLayer { rotationZ = decorativePhase(tick.value, 900) * 360f }) {
                drawArc(
                    color = arcColor,
                    startAngle = 0f,
                    sweepAngle = 75f,
                    useCenter = false,
                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
                    topLeft = Offset(0.75.dp.toPx(), 0.75.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(
                        size.width - 1.5.dp.toPx(),
                        size.height - 1.5.dp.toPx(),
                    ),
                )
            }
        }
    }
}
