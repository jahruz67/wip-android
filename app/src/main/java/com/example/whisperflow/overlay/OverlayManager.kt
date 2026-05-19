package com.example.whisperflow.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.whisperflow.audio.VoiceRecorder
import com.example.whisperflow.network.GroqApiService
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.abs

class OverlayManager(
    private val context: Context,
    private val onTextTranscribed: (String) -> Unit,
    private val onDismissed: (() -> Unit)? = null
) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var composeView: ComposeView? = null
    private var isShowing = false

    private val voiceRecorder = VoiceRecorder(context)
    private val apiService = GroqApiService.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val overlayState = mutableStateOf(OverlayState.IDLE)
    private val voiceAmplitude = mutableFloatStateOf(0f)
    private var amplitudeJob: Job? = null

    // Bottom-center dismiss target state
    private var dismissComposeView: ComposeView? = null
    private var isNearDismissTarget = false
    private val dismissTargetNearState = mutableStateOf(false)
    private var dismissJob: Job? = null

    private val displayMetrics by lazy { context.resources.displayMetrics }
    private val density by lazy { displayMetrics.density }
    private val safePaddingPx by lazy { (20f * density).toInt() }
    private val dismissSnapDistancePxSquared by lazy {
        val d = 120f * density
        d * d
    }
    private val dismissTargetYOffsetPx by lazy { dismissParams.y.toFloat() + (52f * density) }

    private val dismissParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, // Untouchable visual target
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = 80 // Leaves room for extra visual padding to prevent clipping
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 50 
        y = 500 // Start somewhere in the middle
    }

    private var floatX = 50f
    private var floatY = 500f

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    private fun startAmplitudePolling() {
        amplitudeJob?.cancel()
        amplitudeJob = scope.launch {
            while (isActive) {
                delay(80)
                val maxAmp = voiceRecorder.getMaxAmplitude()
                val normalized = (maxAmp.toFloat() / 8000f).coerceIn(0f, 1f)
                if (abs(voiceAmplitude.floatValue - normalized) >= 0.015f) {
                    voiceAmplitude.floatValue = normalized
                }
            }
        }
    }

    private fun stopAmplitudePolling() {
        amplitudeJob?.cancel()
        amplitudeJob = null
        voiceAmplitude.floatValue = 0f
    }

    private fun showDismissTargetView() {
        if (dismissComposeView != null) return
        dismissComposeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayManager)
            setViewTreeSavedStateRegistryOwner(this@OverlayManager)
            setViewTreeViewModelStoreOwner(this@OverlayManager)
            setContent {
                DismissTargetUi(isNear = dismissTargetNearState.value)
            }
        }
        try {
            windowManager.addView(dismissComposeView, dismissParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateDismissTargetHighlight(near: Boolean) {
        if (isNearDismissTarget == near) return
        isNearDismissTarget = near
        dismissTargetNearState.value = near
    }

    private fun hideDismissTargetView() {
        dismissJob?.cancel()
        dismissJob = null
        dismissComposeView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            dismissComposeView = null
        }
        isNearDismissTarget = false
        dismissTargetNearState.value = false
    }

    private fun startRecording(): Boolean {
        val recordingFile = voiceRecorder.startRecording()
        if (recordingFile == null) {
            overlayState.value = OverlayState.IDLE
            Toast.makeText(context, "Unable to start recording. Please try again.", Toast.LENGTH_SHORT).show()
            return false
        }
        startAmplitudePolling()
        return true
    }

    private fun stopRecordingAndTranscribe() {
        overlayState.value = OverlayState.TRANSCRIBING
        stopAmplitudePolling()
        
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                voiceRecorder.stopRecording()
            }
            if (file == null || !file.exists() || file.length() == 0L) {
                file?.delete()
                overlayState.value = OverlayState.IDLE
                return@launch
            }

            val sharedPrefs = com.example.whisperflow.network.SecurityUtils.getEncryptedSharedPreferences(context)
            val apiKey = sharedPrefs.getString("api_key", "") ?: ""
            val modelName = sharedPrefs.getString("whisper_model", "whisper-large-v3") ?: "whisper-large-v3"

            if (apiKey.isEmpty()) {
                Toast.makeText(context, "Groq API Key is missing! Please configure it in Settings.", Toast.LENGTH_LONG).show()
                file.delete()
                overlayState.value = OverlayState.IDLE
                return@launch
            }

            try {
                val requestFile = file.asRequestBody("audio/*".toMediaTypeOrNull())
                val filePart = MultipartBody.Part.createFormData("file", file.name, requestFile)
                val modelPart = modelName.toRequestBody("text/plain".toMediaTypeOrNull())
                
                val response = withContext(Dispatchers.IO) {
                    apiService.transcribeAudio(
                        authHeader = "Bearer $apiKey",
                        file = filePart,
                        model = modelPart
                    )
                }

                val transcribedText = response.text
                if (transcribedText.isNotEmpty()) {
                    onTextTranscribed(transcribedText)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Transcription failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            } finally {
                file.delete()
                overlayState.value = OverlayState.IDLE
            }
        }
    }

    private fun cancelRecording() {
        voiceRecorder.cancelRecording()
        stopAmplitudePolling()
        overlayState.value = OverlayState.IDLE
    }

    fun showOverlay(interactionMode: String) {
        if (isShowing) return
        overlayState.value = OverlayState.IDLE

        composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayManager)
            setViewTreeSavedStateRegistryOwner(this@OverlayManager)
            setViewTreeViewModelStoreOwner(this@OverlayManager)
            clipChildren = false
            clipToPadding = false
            setContent {
                OverlayUi(
                    state = overlayState.value,
                    interactionMode = interactionMode,
                    voiceAmplitude = voiceAmplitude.floatValue,
                    onStateChange = { newState -> overlayState.value = newState },
                    onStartRecording = {
                        startRecording()
                    },
                    onStopRecording = { stopRecordingAndTranscribe() },
                    onCancelRecording = { cancelRecording() },
                    onDragStart = {
                        dismissJob?.cancel()
                        dismissJob = scope.launch {
                            delay(1000) // Show target if dragging for > 1 second
                            showDismissTargetView()
                        }
                    },
                    onDrag = { dx, dy ->
                        if (dx != 0f || dy != 0f) {
                            val screenWidth = displayMetrics.widthPixels
                            val screenHeight = displayMetrics.heightPixels
                            val viewWidth = this.width
                            val viewHeight = this.height
                            if (viewWidth > 0 && viewHeight > 0) {
                                floatX = (floatX + dx).coerceIn(-safePaddingPx.toFloat(), (screenWidth - viewWidth + safePaddingPx).toFloat())
                                floatY = (floatY + dy).coerceIn(-safePaddingPx.toFloat(), (screenHeight - viewHeight + safePaddingPx).toFloat())
                                
                                val nextX = floatX.toInt()
                                val nextY = floatY.toInt()

                                if (nextX != params.x || nextY != params.y) {
                                    params.x = nextX
                                    params.y = nextY

                                    try {
                                        windowManager.updateViewLayout(this, params)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }

                                if (dismissComposeView != null) {
                                    val overlayCenterX = params.x + viewWidth / 2f
                                    val overlayCenterY = params.y + viewHeight / 2f

                                    val targetCenterX = screenWidth / 2f
                                    val targetCenterY = screenHeight - dismissTargetYOffsetPx

                                    val distX = overlayCenterX - targetCenterX
                                    val distY = overlayCenterY - targetCenterY
                                    val near = (distX * distX + distY * distY) < dismissSnapDistancePxSquared
                                    updateDismissTargetHighlight(near)
                                }
                            }
                        }
                    },
                    onDragEnd = {
                        if (isNearDismissTarget) {
                            hideOverlay()
                            onDismissed?.invoke()
                        }
                        hideDismissTargetView()
                    }
                )
            }
        }

        try {
            windowManager.addView(composeView, params)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            isShowing = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hideOverlay() {
        dismissJob?.cancel()
        hideDismissTargetView()

        if (!isShowing) return
        if (overlayState.value == OverlayState.RECORDING_HOLD || overlayState.value == OverlayState.RECORDING_TAP) {
            cancelRecording()
        }
        composeView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            composeView = null
        }
        isShowing = false
    }

@Composable
fun DismissTargetUi(isNear: Boolean) {
    val scale by animateFloatAsState(targetValue = if (isNear) 1.15f else 1.0f, label = "scale")
    
    Box(
        modifier = Modifier
            .width(192.dp)
            .height(104.dp)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        // Broad background visual gravity field
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(
                    elevation = if (isNear) 24.dp else 8.dp,
                    shape = RoundedCornerShape(36.dp),
                    spotColor = Color(0xFFEF4444),
                    ambientColor = Color.Black
                )
                .clip(RoundedCornerShape(36.dp))
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1E1015).copy(alpha = 0.95f),
                            Color(0xFF0F080B).copy(alpha = 0.95f)
                        )
                    )
                )
                .border(
                    width = 1.5.dp,
                    color = if (isNear) Color(0xFFEF4444) else Color(0xFFEF4444).copy(alpha = 0.3f),
                    shape = RoundedCornerShape(36.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                // Glowing crimson dot/icon
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isNear) Color(0xFFEF4444) else Color(0xFFEF4444).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss Icon",
                        tint = if (isNear) Color.White else Color(0xFFEF4444),
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(10.dp))
                
                Text(
                    text = if (isNear) "Release to Close" else "Drag here to close",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isNear) Color.White else Color(0xFF9CA3AF),
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}
}