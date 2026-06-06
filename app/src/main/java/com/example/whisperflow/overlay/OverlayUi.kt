package com.example.whisperflow.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.Crossfade
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.geometry.CornerRadius
import com.example.whisperflow.network.ToastHelper
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import android.widget.Toast
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.MotionEvent
import com.example.whisperflow.network.SecurityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

enum class OverlayState { IDLE, RECORDING_HOLD, RECORDING_TAP, TRANSCRIBING }

@OptIn(ExperimentalComposeUiApi::class, ExperimentalAnimationApi::class)
@Composable
fun OverlayUi(
    state: OverlayState,
    interactionMode: String,
    initialTargetLanguage: String,
    voiceAmplitude: Float,
    onStateChange: (OverlayState) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onChromeInsetsChange: (Int, Int) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var recordJob by remember { mutableStateOf<Job?>(null) }

    var isDragging by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var isDoubleTapTriggered by remember { mutableStateOf(false) }
    var isLongPressTriggered by remember { mutableStateOf(false) }
    var showLanguagePopup by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (state != OverlayState.IDLE) {
            showLanguagePopup = false
        }
    }
    
    // Coordination tracking (using a ref-like object to avoid recompositions during drag)
    val touchState = remember { object {
        var startX = 0f
        var startY = 0f
        var lastRawX = 0f
        var lastRawY = 0f
    }}

    // Smooth capsule size transition
    val isExpanded = state == OverlayState.RECORDING_TAP
    val needsVisualRoom = showLanguagePopup || state == OverlayState.RECORDING_HOLD || state == OverlayState.RECORDING_TAP
    val outerHorizontalPadding = if (needsVisualRoom) 48.dp else 8.dp
    val outerTopPadding = if (showLanguagePopup) 140.dp else if (needsVisualRoom) 48.dp else 8.dp
    val outerBottomPadding = if (needsVisualRoom) 48.dp else 8.dp
    val density = LocalDensity.current

    LaunchedEffect(needsVisualRoom, showLanguagePopup, density) {
        with(density) {
            onChromeInsetsChange(
                outerHorizontalPadding.roundToPx(),
                outerTopPadding.roundToPx()
            )
        }
    }

    val targetWidth = if (isExpanded) 130.dp else 56.dp
    val targetHeight = 56.dp
    val animatedWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium),
        label = "width"
    )
    val animatedHeight by animateDpAsState(
        targetValue = targetHeight,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium),
        label = "height"
    )

    // Dynamic scale for the button itself (touch feedback)
    val containerScale by animateFloatAsState(
        targetValue = if (isDragging) 0.95f else 1.0f,
        label = "containerScale"
    )

    // Dynamic amplitude animations for multiple rings
    val animatedAmplitude by animateFloatAsState(
        targetValue = if (state == OverlayState.RECORDING_HOLD || state == OverlayState.RECORDING_TAP) voiceAmplitude else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "amplitude"
    )

    Box(
        modifier = Modifier
            .padding(
                start = outerHorizontalPadding,
                end = outerHorizontalPadding,
                top = outerTopPadding,
                bottom = outerBottomPadding
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (showLanguagePopup) {
                    showLanguagePopup = false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = showLanguagePopup,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            val context = LocalContext.current
            val targetLanguage = remember { mutableStateOf(initialTargetLanguage) }

            Box(
                modifier = Modifier
                    .offset(y = (-100).dp)
                    .width(140.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E2129).copy(alpha = 0.95f))
                    .border(1.dp, Color(0xFF5B8DEF).copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val options = listOf("none" to "None", "english" to "English", "spanish" to "Spanish")
                    
                    options.forEach { (key, display) ->
                        val isSelected = targetLanguage.equals(key, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFF5B8DEF) else Color.Transparent)
                                .clickable {
                                    targetLanguage.value = key
                                    scope.launch(Dispatchers.IO) {
                                        SecurityUtils.putString(context, "target_language", key)
                                    }
                                    showLanguagePopup = false
                                    val msg = if (key == "none") "Keep As-Is" else "Auto-Translate to $display"
                                    ToastHelper.showToast(context, "Translation: $msg", Toast.LENGTH_SHORT)
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = display,
                                color = if (targetLanguage.value.equals(key, ignoreCase = true)) Color.White else Color(0xFF9CA3AF),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.SansSerif
                            )
                        }
                    }
                }
            }
        }

        // Ring shape matches the capsule
        val ringShape = if (isExpanded) RoundedCornerShape(28.dp) else CircleShape

        // 1. Reactive Aura (drawn in a single layout pass to prevent overdraw and recompositions)
        if (state == OverlayState.RECORDING_HOLD || state == OverlayState.RECORDING_TAP) {
            Box(
                modifier = Modifier
                    .width(animatedWidth)
                    .height(animatedHeight)
                    .drawBehind {
                        val cornerRadius = if (isExpanded) 28.dp.toPx() else size.height / 2f
                        val colorBlue = Color(0xFF5B8DEF)
                        
                        // Ring A: scale = 1.0 + amplitude * 0.4, alpha = (1.0 - amplitude) * 0.05
                        val scaleA = 1.0f + animatedAmplitude * 0.4f
                        val alphaA = 0.05f * (1.0f - animatedAmplitude)

                        // Ring B: scale = 1.0 + amplitude * 0.3, alpha = 0.1225 * (1.0 - amplitude)
                        val scaleB = 1.0f + animatedAmplitude * 0.3f
                        val alphaB = 0.1225f * (1.0f - animatedAmplitude)

                        // Ring C: scale = 1.0 + amplitude * 0.15, alpha = 0.6 - (amplitude * 0.15)
                        val scaleC = 1.0f + animatedAmplitude * 0.15f
                        val alphaC = 0.6f - (animatedAmplitude * 0.15f)

                        // Draw Ring A
                        if (alphaA > 0f) {
                            scale(scaleA) {
                                drawRoundRect(
                                    color = colorBlue.copy(alpha = alphaA),
                                    cornerRadius = CornerRadius(cornerRadius)
                                )
                            }
                        }

                        // Draw Ring B
                        if (alphaB > 0f) {
                            scale(scaleB) {
                                drawRoundRect(
                                    color = colorBlue.copy(alpha = alphaB),
                                    cornerRadius = CornerRadius(cornerRadius)
                                )
                            }
                        }

                        // Draw Ring C
                        if (alphaC > 0f) {
                            scale(scaleC) {
                                val brush = Brush.radialGradient(
                                    colors = listOf(
                                        colorBlue.copy(alpha = 0.3f * alphaC),
                                        Color.Transparent
                                    ),
                                    center = center,
                                    radius = size.width.coerceAtLeast(size.height) / 2f
                                )
                                drawRoundRect(
                                    brush = brush,
                                    cornerRadius = CornerRadius(cornerRadius)
                                )
                                val strokeWidth = 1.dp.toPx()
                                drawRoundRect(
                                    color = Color.White.copy(alpha = 0.3f * alphaC),
                                    cornerRadius = CornerRadius(cornerRadius),
                                    style = Stroke(width = strokeWidth)
                                )
                            }
                        }
                    }
            )
        } else if (state == OverlayState.IDLE) {
            // Static idle ring draws efficiently once.
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .drawBehind {
                        val strokeWidth = 1.dp.toPx()
                        scale(1.1f) {
                            drawCircle(
                                color = Color(0xFF5B8DEF).copy(alpha = 0.3f * 0.35f),
                                style = Stroke(width = strokeWidth)
                            )
                        }
                    }
            )
        }

        // 2. Main High-Tech Glassmorphic Control Capsule
        val capsuleShape = if (isExpanded) RoundedCornerShape(28.dp) else CircleShape
        val backgroundBrush = when (state) {
            OverlayState.RECORDING_HOLD -> Brush.linearGradient(
                colors = listOf(Color(0xFFE5484D), Color(0xFF8C1D18))
            )
            OverlayState.RECORDING_TAP -> Brush.linearGradient(
                colors = listOf(Color(0xFF1E2129), Color(0xFF15171E))
            )
            OverlayState.TRANSCRIBING -> Brush.linearGradient(
                colors = listOf(Color(0xFF1A1E2E), Color(0xFF111318))
            )
            else -> Brush.linearGradient(
                colors = listOf(Color(0xFF5B8DEF), Color(0xFF3D6BC7))
            )
        }

        val borderGradient = if (state == OverlayState.RECORDING_TAP) {
            Brush.linearGradient(colors = listOf(Color(0xFF5B8DEF).copy(alpha = 0.4f), Color(0xFF5B8DEF).copy(alpha = 0.15f)))
        } else {
            Brush.linearGradient(colors = listOf(Color.White.copy(alpha = 0.3f), Color.White.copy(alpha = 0.05f)))
        }

        Box(
            modifier = Modifier
                .width(animatedWidth)
                .height(animatedHeight)
                .graphicsLayer {
                    scaleX = containerScale
                    scaleY = containerScale
                }
                .shadow(
                    elevation = if (state != OverlayState.IDLE) 8.dp else 2.dp,
                    shape = capsuleShape,
                    spotColor = if (state == OverlayState.RECORDING_HOLD) Color(0xFFE5484D) else Color(0xFF5B8DEF),
                    ambientColor = Color.Black
                )
                .clip(capsuleShape)
                .background(backgroundBrush)
                .border(1.5.dp, borderGradient, capsuleShape)
                .pointerInteropFilter { motionEvent ->
                    // Let TAP mode buttons and spinner handle their own touches
                    if (state == OverlayState.RECORDING_TAP || state == OverlayState.TRANSCRIBING) {
                        return@pointerInteropFilter false
                    }

                    when (motionEvent.action) {
                        MotionEvent.ACTION_DOWN -> {
                            isDragging = false
                            isRecording = false
                            touchState.startX = motionEvent.rawX
                            touchState.startY = motionEvent.rawY
                            touchState.lastRawX = motionEvent.rawX
                            touchState.lastRawY = motionEvent.rawY

                            recordJob?.cancel()
                            longPressJob?.cancel()

                            val now = System.currentTimeMillis()
                            if (interactionMode == "HOLD_TO_TALK" && state == OverlayState.IDLE) {
                                val isDoubleTap = (now - lastTapTime) < 300
                                if (isDoubleTap) {
                                    showLanguagePopup = !showLanguagePopup
                                    isDoubleTapTriggered = true
                                } else {
                                    isDoubleTapTriggered = false
                                    recordJob = scope.launch {
                                        delay(300)
                                        onStateChange(OverlayState.RECORDING_HOLD)
                                        onStartRecording()
                                        isRecording = true
                                    }
                                }
                            } else if (interactionMode == "TAP_TO_TALK" && state == OverlayState.IDLE) {
                                isLongPressTriggered = false
                                longPressJob = scope.launch {
                                    delay(600)
                                    showLanguagePopup = !showLanguagePopup
                                    isLongPressTriggered = true
                                }
                            }
                            lastTapTime = now
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (isRecording) {
                                return@pointerInteropFilter true
                            }

                            val dx = motionEvent.rawX - touchState.lastRawX
                            val dy = motionEvent.rawY - touchState.lastRawY

                            val totalDistX = motionEvent.rawX - touchState.startX
                            val totalDistY = motionEvent.rawY - touchState.startY

                            if (!isDragging && (abs(totalDistX) > 10 || abs(totalDistY) > 10)) {
                                isDragging = true
                                showLanguagePopup = false
                                onDragStart()
                                recordJob?.cancel()
                                longPressJob?.cancel()
                            }

                            if (isDragging) {
                                onDrag(dx, dy)
                            }

                            touchState.lastRawX = motionEvent.rawX
                            touchState.lastRawY = motionEvent.rawY
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            recordJob?.cancel()
                            longPressJob?.cancel()

                            if (interactionMode == "HOLD_TO_TALK") {
                                if (isRecording) {
                                    onStopRecording()
                                } else if (isDoubleTapTriggered) {
                                    isDoubleTapTriggered = false
                                } else if (showLanguagePopup) {
                                    showLanguagePopup = false
                                }
                            } else if (!isDragging && !isRecording && state == OverlayState.IDLE) {
                                if (interactionMode == "TAP_TO_TALK") {
                                    if (isLongPressTriggered) {
                                        isLongPressTriggered = false
                                    } else if (showLanguagePopup) {
                                        showLanguagePopup = false
                                    } else {
                                        onStateChange(OverlayState.RECORDING_TAP)
                                        onStartRecording()
                                    }
                                }
                            }
                            if (isDragging) {
                                onDragEnd()
                            }
                            isRecording = false
                            isDragging = false
                            true
                        }
                        else -> false
                    }
                }
        ) {
            Crossfade(
                targetState = state,
                animationSpec = tween(220),
                modifier = Modifier.fillMaxSize(),
                label = "overlayContent"
            ) { currentState ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    when (currentState) {
                        OverlayState.TRANSCRIBING -> {
                            CircularProgressIndicator(
                                color = Color(0xFF5B8DEF),
                                strokeWidth = 3.dp,
                                modifier = Modifier
                                    .size(28.dp)
                                    .padding(2.dp)
                            )
                        }
                        OverlayState.RECORDING_TAP -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { onCancelRecording() },
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFE5484D).copy(alpha = 0.15f))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Cancel",
                                        tint = Color(0xFFE5484D),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF5B8DEF))
                                )

                                IconButton(
                                    onClick = { onStopRecording() },
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF10B981).copy(alpha = 0.15f))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Done",
                                        tint = Color(0xFF10B981),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                        else -> {
                            // Visual mic icon only — touch handled by parent Box
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Mic",
                                tint = Color.White,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(15.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
