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
import android.media.MediaRecorder
import com.example.whisperflow.audio.VoiceRecorder
import com.example.whisperflow.network.GroqApiService
import com.example.whisperflow.network.SecurityUtils
import com.example.whisperflow.network.ChatRequest
import com.example.whisperflow.network.ChatMessage
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
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 50 
        y = 500 // Start somewhere in the middle
    }

    private var floatX = 50f
    private var floatY = 500f
    private var pendingX = 50
    private var pendingY = 500
    private var isFrameCallbackScheduled = false

    private val frameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (isShowing) {
                composeView?.let { view ->
                    try {
                        params.x = pendingX
                        params.y = pendingY
                        windowManager.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            isFrameCallbackScheduled = false
        }
    }

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
        val prefs = SecurityUtils.getEncryptedSharedPreferences(context)
        val audioSource = prefs.getInt("audio_source", MediaRecorder.AudioSource.MIC)
        val recordingFile = voiceRecorder.startRecording(audioSource)
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

            val sharedPrefs = SecurityUtils.getEncryptedSharedPreferences(context)
            val apiKey = sharedPrefs.getString("api_key", "") ?: ""
            val modelName = sharedPrefs.getString("whisper_model", "whisper-large-v3") ?: "whisper-large-v3"
            val aiEnhancementModel = sharedPrefs.getString("ai_enhancement_model", "none") ?: "none"
            val targetLanguage = sharedPrefs.getString("target_language", "english") ?: "english"

            if (apiKey.isEmpty()) {
                Toast.makeText(context, "Groq API Key is missing! Please configure it in Settings.", Toast.LENGTH_LONG).show()
                file.delete()
                overlayState.value = OverlayState.IDLE
                return@launch
            }

            var finalText: String
            var detectedLanguage: String?

            try {
                val requestFile = file.asRequestBody("audio/*".toMediaTypeOrNull())
                val filePart = MultipartBody.Part.createFormData("file", file.name, requestFile)
                val modelPart = modelName.toRequestBody("text/plain".toMediaTypeOrNull())
                val responseFormatPart = "verbose_json".toRequestBody("text/plain".toMediaTypeOrNull())

                // Step 1: Transcribe with Whisper (auto-detects language)
                val response = withContext(Dispatchers.IO) {
                    apiService.transcribeAudio(
                        authHeader = "Bearer $apiKey",
                        file = filePart,
                        model = modelPart,
                        responseFormat = responseFormatPart
                    )
                }

                val rawText = response.text
                detectedLanguage = response.language

                if (rawText.isEmpty()) {
                    file.delete()
                    overlayState.value = OverlayState.IDLE
                    return@launch
                }

                // Step 2: Translation logic
                // User picks a target language from settings
                // Whisper detects the spoken language
                // If detected != target → translate, otherwise keep as-is
                val isTargetEnglish = targetLanguage.equals("english", ignoreCase = true)

                if (!isTargetEnglish && detectedLanguage != null) {
                    // Target is non-English (e.g. Spanish). Check if detected is English.
                    val isDetectedEnglish = detectedLanguage.startsWith("en", ignoreCase = true) ||
                                            detectedLanguage.startsWith("english", ignoreCase = true)

                    if (isDetectedEnglish) {
                        // Detected English, user wants Spanish → translate to Spanish
                        val translationRequest = ChatRequest(
                            model = "llama-3.1-8b-instant",
                            messages = listOf(
                                ChatMessage("system", "You are a translator. Translate the following text to $targetLanguage. The original text is in English. Return ONLY the translated text, nothing else."),
                                ChatMessage("user", rawText)
                            ),
                            temperature = 0.1f
                        )

                        val translationResponse = withContext(Dispatchers.IO) {
                            apiService.chatCompletion(
                                authHeader = "Bearer $apiKey",
                                request = translationRequest
                            )
                        }
                        finalText = translationResponse.choices.firstOrNull()?.message?.content ?: rawText
                    } else {
                        // Detected matches target (e.g. Spanish detected, target Spanish) → keep as-is
                        finalText = rawText
                    }
                } else if (isTargetEnglish && detectedLanguage != null) {
                    // Target is English. Check if detected is non-English.
                    val isDetectedEnglish = detectedLanguage.startsWith("en", ignoreCase = true) ||
                                            detectedLanguage.startsWith("english", ignoreCase = true)

                    if (!isDetectedEnglish) {
                        // Detected non-English, user wants English → translate to English
                        val translationRequest = ChatRequest(
                            model = "llama-3.1-8b-instant",
                            messages = listOf(
                                ChatMessage("system", "You are a translator. Translate the following text to English. The original text is in $detectedLanguage. Return ONLY the translated text, nothing else."),
                                ChatMessage("user", rawText)
                            ),
                            temperature = 0.1f
                        )

                        val translationResponse = withContext(Dispatchers.IO) {
                            apiService.chatCompletion(
                                authHeader = "Bearer $apiKey",
                                request = translationRequest
                            )
                        }
                        finalText = translationResponse.choices.firstOrNull()?.message?.content ?: rawText
                    } else {
                        // English detected, target English → keep as-is
                        finalText = rawText
                    }
                } else {
                    // No detected language available or English target with no detected lang
                    finalText = rawText
                }

                // Step 3: Apply AI Enhancement if selected
                if (aiEnhancementModel != "none" && finalText.isNotEmpty()) {
                    val enhancerModel = when (aiEnhancementModel) {
                        "llama-3.2-3b-preview" -> "llama-3.2-3b-preview"
                        "mixtral-8x7b-32768" -> "mixtral-8x7b-32768"
                        "gemma2-9b-it" -> "gemma2-9b-it"
                        else -> "llama-3.1-8b-instant"
                    }

                    val enhanceRequest = ChatRequest(
                        model = enhancerModel,
                        messages = listOf(
                            ChatMessage("system", "You are an AI assistant that corrects spelling and grammar in transcribed text. Improve the transcription by fixing spelling errors, correcting grammar, and improving punctuation. Preserve the user's intent, tone, and spoken style. Do NOT add any preamble, quotes, markdown formatting, explanation, or conversational filler. Return ONLY the final corrected text."),
                            ChatMessage("user", finalText)
                        ),
                        temperature = 0.1f
                    )

                    val enhanceResponse = withContext(Dispatchers.IO) {
                        apiService.chatCompletion(
                            authHeader = "Bearer $apiKey",
                            request = enhanceRequest
                        )
                    }
                    var enhancedText = enhanceResponse.choices.firstOrNull()?.message?.content ?: finalText
                    // Clean up potential LLM quoting/wrapping artifacts
                    if (enhancedText.startsWith("\"") && enhancedText.endsWith("\"")) {
                        enhancedText = enhancedText.removeSurrounding("\"")
                    } else if (enhancedText.startsWith("`") && enhancedText.endsWith("`")) {
                        enhancedText = enhancedText.removeSurrounding("`")
                    }
                    finalText = enhancedText.trim()
                }

                if (finalText.isNotEmpty()) {
                    saveToHistory(finalText)
                    onTextTranscribed(finalText)
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

    private fun saveToHistory(text: String) {
        try {
            val prefs = SecurityUtils.getEncryptedSharedPreferences(context)
            val existing = prefs.getString("transcription_history", "") ?: ""
            val encodedText = java.net.URLEncoder.encode(text, "UTF-8")
            val newEntry = "${System.currentTimeMillis()}:$encodedText"
            val updated = if (existing.isEmpty()) newEntry else "$existing|$newEntry"
            
            // Keep only last 100 entries
            val entries = updated.split("|")
            val trimmed = if (entries.size > 100) entries.takeLast(100).joinToString("|") else updated
            
            prefs.edit().putString("transcription_history", trimmed).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cancelRecording() {
        voiceRecorder.cancelRecording()
        stopAmplitudePolling()
        overlayState.value = OverlayState.IDLE
    }

    private fun adjustOverlayPositionForExpansion() {
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Expanded total layout size: 130.dp (capsule) + 96.dp (ambient rings padding) = 226.dp width
        // Expanded total height: 56.dp (capsule) + 96.dp (ambient rings padding) = 152.dp height
        val expandedWidthPx = (226f * density).toInt()
        val expandedHeightPx = (152f * density).toInt()

        val maxAllowedX = screenWidth - expandedWidthPx + safePaddingPx
        val minAllowedX = -safePaddingPx

        if (floatX > maxAllowedX) {
            floatX = floatX.coerceIn(minAllowedX.toFloat(), maxAllowedX.toFloat())
            pendingX = floatX.toInt()
            params.x = pendingX
        }

        val maxAllowedY = screenHeight - expandedHeightPx + safePaddingPx
        val minAllowedY = -safePaddingPx

        if (floatY > maxAllowedY) {
            floatY = floatY.coerceIn(minAllowedY.toFloat(), maxAllowedY.toFloat())
            pendingY = floatY.toInt()
            params.y = pendingY
        }

        composeView?.let { view ->
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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
                    onStateChange = { newState ->
                        overlayState.value = newState
                        if (newState == OverlayState.RECORDING_TAP) {
                            adjustOverlayPositionForExpansion()
                        }
                    },
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
                                    pendingX = nextX
                                    pendingY = nextY

                                    if (!isFrameCallbackScheduled) {
                                        isFrameCallbackScheduled = true
                                        try {
                                            android.view.Choreographer.getInstance().postFrameCallback(frameCallback)
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            // Fallback in case Choreographer is unavailable or encounters issues
                                            params.x = pendingX
                                            params.y = pendingY
                                            try {
                                                windowManager.updateViewLayout(this, params)
                                            } catch (ex: Exception) {
                                                ex.printStackTrace()
                                            }
                                            isFrameCallbackScheduled = false
                                        }
                                    }
                                }

                                if (dismissComposeView != null) {
                                    val overlayCenterX = pendingX + viewWidth / 2f
                                    val overlayCenterY = pendingY + viewHeight / 2f

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

        if (isFrameCallbackScheduled) {
            try {
                android.view.Choreographer.getInstance().removeFrameCallback(frameCallback)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isFrameCallbackScheduled = false
        }

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