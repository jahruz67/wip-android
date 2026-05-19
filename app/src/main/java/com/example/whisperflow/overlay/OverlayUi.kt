package com.example.whisperflow.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import android.view.MotionEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class OverlayState { IDLE, RECORDING_HOLD, RECORDING_TAP, TRANSCRIBING }

@OptIn(ExperimentalComposeUiApi::class, ExperimentalAnimationApi::class)
@Composable
fun OverlayUi(
    state: OverlayState,
    interactionMode: String,
    voiceAmplitude: Float,
    onStateChange: (OverlayState) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var recordJob by remember { mutableStateOf<Job?>(null) }

    var isDragging by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    
    // Coordination tracking (using a ref-like object to avoid recompositions during drag)
    val touchState = remember { object {
        var startX = 0f
        var startY = 0f
        var lastRawX = 0f
        var lastRawY = 0f
    }}

    // Smooth capsule size transition
    val isExpanded = state == OverlayState.RECORDING_TAP
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

    // Continuous breath-glow transition for idle state
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val idlePulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idlePulse"
    )

    Box(
        modifier = Modifier.padding(48.dp),
        contentAlignment = Alignment.Center
    ) {

        // Ring shape matches the capsule
        val ringShape = if (isExpanded) RoundedCornerShape(28.dp) else CircleShape

        // 1. Triple-Layered Reactive Aura (shape-matched rings)
        if (state == OverlayState.RECORDING_HOLD || state == OverlayState.RECORDING_TAP) {
            // Layer A: Outer ambient ripple
            Box(
                modifier = Modifier
                    .width(animatedWidth)
                    .height(animatedHeight)
                    .graphicsLayer {
                        scaleX = 1.0f + animatedAmplitude * 0.4f
                        scaleY = 1.0f + animatedAmplitude * 0.4f
                        alpha = (1.0f - animatedAmplitude) * 0.2f
                    }
                    .background(Color(0xFF5B8DEF).copy(alpha = 0.25f), ringShape)
            )

            // Layer B: Mid halo
            Box(
                modifier = Modifier
                    .width(animatedWidth)
                    .height(animatedHeight)
                    .graphicsLayer {
                        scaleX = 1.0f + animatedAmplitude * 0.3f
                        scaleY = 1.0f + animatedAmplitude * 0.3f
                        alpha = (1.0f - animatedAmplitude) * 0.35f
                    }
                    .background(Color(0xFF5B8DEF).copy(alpha = 0.35f), ringShape)
            )

            // Layer C: Tight inner pulse
            Box(
                modifier = Modifier
                    .width(animatedWidth)
                    .height(animatedHeight)
                    .graphicsLayer {
                        scaleX = 1.0f + animatedAmplitude * 0.15f
                        scaleY = 1.0f + animatedAmplitude * 0.15f
                        alpha = 0.6f - (animatedAmplitude * 0.15f)
                    }
                    .border(1.dp, Color.White.copy(alpha = 0.3f), ringShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF5B8DEF).copy(alpha = 0.3f),
                                Color(0x005B8DEF)
                            )
                        ),
                        ringShape
                    )
            )
        } else if (state == OverlayState.IDLE) {
            // Idle breathing ring
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .graphicsLayer {
                        scaleX = 1.1f
                        scaleY = 1.1f
                        alpha = idlePulseAlpha
                    }
                    .border(1.dp, Color(0xFF5B8DEF).copy(alpha = 0.3f), CircleShape)
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
                    elevation = if (state != OverlayState.IDLE) 16.dp else 8.dp,
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
                            touchState.startX = motionEvent.x
                            touchState.startY = motionEvent.y
                            touchState.lastRawX = motionEvent.rawX
                            touchState.lastRawY = motionEvent.rawY

                            recordJob?.cancel()
                            if (interactionMode == "HOLD_TO_TALK" && state == OverlayState.IDLE) {
                                recordJob = scope.launch {
                                    delay(300)
                                    onStateChange(OverlayState.RECORDING_HOLD)
                                    onStartRecording()
                                    isRecording = true
                                }
                            }
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (isRecording) {
                                return@pointerInteropFilter true
                            }

                            val dx = motionEvent.rawX - touchState.lastRawX
                            val dy = motionEvent.rawY - touchState.lastRawY

                            val totalDistX = motionEvent.x - touchState.startX
                            val totalDistY = motionEvent.y - touchState.startY

                            if (!isDragging && (abs(totalDistX) > 20 || abs(totalDistY) > 20)) {
                                isDragging = true
                                onDragStart()
                                recordJob?.cancel()
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
                            if (interactionMode == "HOLD_TO_TALK" && isRecording) {
                                onStopRecording()
                            } else if (!isDragging && !isRecording && state == OverlayState.IDLE) {
                                if (interactionMode == "TAP_TO_TALK") {
                                    onStateChange(OverlayState.RECORDING_TAP)
                                    onStartRecording()
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
            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
                },
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
                label = "overlayContent"
            ) { currentState ->
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
