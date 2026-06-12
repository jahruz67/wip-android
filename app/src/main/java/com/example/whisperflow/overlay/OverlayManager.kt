package com.example.whisperflow.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
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
import com.example.whisperflow.network.ToastHelper
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.abs

private data class TranscriptionSettings(
    val apiKey: String,
    val modelName: String,
    val aiEnhancementModel: String,
    val targetLanguage: String
)

class OverlayManager(
    private val context: Context,
    private val onTextTranscribed: (String) -> Unit,
    private val onDismissed: (() -> Unit)? = null
) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    companion object {
        private const val TAG = "OverlayManager"
        private const val MAX_HISTORY_CHARS = 50_000
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var composeView: ComposeView? = null
    private var isShowing = false

    private val voiceRecorder = VoiceRecorder(context)
    private val apiService = GroqApiService.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val overlayState = mutableStateOf(OverlayState.IDLE)
    private val voiceAmplitude = mutableFloatStateOf(0f)
    private var amplitudeJob: Job? = null
    private var startRecordingJob: Job? = null

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
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = 80
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
        y = 500
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
                        Log.e(TAG, "Failed to update overlay layout in frame callback", e)
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
                delay(120)
                val maxAmp = withContext(Dispatchers.IO) { voiceRecorder.getMaxAmplitude() }
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
            Log.e(TAG, "Failed to add dismiss target view", e)
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
                Log.e(TAG, "Failed to remove dismiss target view", e)
            }
            dismissComposeView = null
        }
        isNearDismissTarget = false
        dismissTargetNearState.value = false
    }

    private fun startRecording() {
        startRecordingJob?.cancel()
        startRecordingJob = scope.launch {
            val recordingFile = withContext(Dispatchers.IO) {
                val audioSource = SecurityUtils.getInt(context, "audio_source", MediaRecorder.AudioSource.MIC)
                voiceRecorder.startRecording(audioSource)
            }
            if (recordingFile == null) {
                overlayState.value = OverlayState.IDLE
                ToastHelper.showToast(context, "Unable to start recording. Please try again.", Toast.LENGTH_SHORT)
                return@launch
            }
            startAmplitudePolling()
        }
    }

    private fun stopRecordingAndTranscribe() {
        overlayState.value = OverlayState.TRANSCRIBING
        stopAmplitudePolling()

        scope.launch {
            val pendingStart = startRecordingJob
            pendingStart?.join()
            startRecordingJob = null

            val file = withContext(Dispatchers.IO) {
                voiceRecorder.stopRecording()
            }
            if (file == null || !file.exists() || file.length() == 0L) {
                file?.delete()
                overlayState.value = OverlayState.IDLE
                return@launch
            }

            val settings = withContext(Dispatchers.IO) {
                TranscriptionSettings(
                    apiKey = SecurityUtils.getString(context, "api_key", ""),
                    modelName = SecurityUtils.getString(context, "whisper_model", "whisper-large-v3"),
                    aiEnhancementModel = SecurityUtils.getString(context, "ai_enhancement_model", "none"),
                    targetLanguage = SecurityUtils.getString(context, "target_language", "none")
                )
            }

            if (settings.apiKey.isEmpty()) {
                ToastHelper.showToast(context, "Groq API Key is missing! Please configure it in Settings.", Toast.LENGTH_LONG)
                withContext(Dispatchers.IO) { file.delete() }
                overlayState.value = OverlayState.IDLE
                return@launch
            }

            var finalText: String
            var detectedLanguage: String?

            try {
                val requestFile = file.asRequestBody("audio/*".toMediaTypeOrNull())
                val filePart = MultipartBody.Part.createFormData("file", file.name, requestFile)
                val responseFormatPart = "verbose_json".toRequestBody("text/plain".toMediaTypeOrNull())

                val response = withContext(Dispatchers.IO) {
                    apiService.transcribeAudio(
                        authHeader = "Bearer ${settings.apiKey}",
                        file = filePart,
                        model = settings.modelName.toRequestBody("text/plain".toMediaTypeOrNull()),
                        responseFormat = responseFormatPart
                    )
                }

                val rawText = response.text
                detectedLanguage = response.language

                if (rawText.isEmpty()) {
                    withContext(Dispatchers.IO) { file.delete() }
                    overlayState.value = OverlayState.IDLE
                    return@launch
                }

                val targetLang = settings.targetLanguage.lowercase()

                if (targetLang == "none") {
                    finalText = rawText
                } else {
                    val isTargetEnglish = targetLang == "english"

                    if (!isTargetEnglish && detectedLanguage != null) {
                        val isDetectedEnglish = detectedLanguage.startsWith("en", ignoreCase = true) ||
                                                detectedLanguage.startsWith("english", ignoreCase = true)

                        if (isDetectedEnglish) {
                            val translationRequest = ChatRequest(
                                model = "llama-3.1-8b-instant",
                                messages = listOf(
                                    ChatMessage("system", "You are a translator. Translate the following text to ${settings.targetLanguage}. The original text is in English. Return ONLY the translated text, nothing else."),
                                    ChatMessage("user", rawText)
                                ),
                                temperature = 0.1f
                            )

                            val translationResponse = withContext(Dispatchers.IO) {
                                apiService.chatCompletion(
                                    authHeader = "Bearer ${settings.apiKey}",
                                    request = translationRequest
                                )
                            }
                            finalText = translationResponse.choices.firstOrNull()?.message?.content ?: rawText
                        } else {
                            finalText = rawText
                        }
                    } else if (isTargetEnglish && detectedLanguage != null) {
                        val isDetectedEnglish = detectedLanguage.startsWith("en", ignoreCase = true) ||
                                                detectedLanguage.startsWith("english", ignoreCase = true)

                        if (!isDetectedEnglish) {
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
                                    authHeader = "Bearer ${settings.apiKey}",
                                    request = translationRequest
                                )
                            }
                            finalText = translationResponse.choices.firstOrNull()?.message?.content ?: rawText
                        } else {
                            finalText = rawText
                        }
                    } else {
                        finalText = rawText
                    }
                }

                if (settings.aiEnhancementModel != "none" && finalText.isNotEmpty()) {
                    val enhancerModel = when (settings.aiEnhancementModel) {
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
                            authHeader = "Bearer ${settings.apiKey}",
                            request = enhanceRequest
                        )
                    }
                    var enhancedText = enhanceResponse.choices.firstOrNull()?.message?.content ?: finalText
                    if (enhancedText.startsWith("\"") && enhancedText.endsWith("\"")) {
                        enhancedText = enhancedText.removeSurrounding("\"")
                    } else if (enhancedText.startsWith("`") && enhancedText.endsWith("`")) {
                        enhancedText = enhancedText.removeSurrounding("`")
                    }
                    finalText = enhancedText.trim()
                }

                if (finalText.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        saveToHistory(finalText)
                    }
                    onTextTranscribed(finalText)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                val errorMsg = e.message ?: e.localizedMessage ?: "Unknown error"
                ToastHelper.showToast(context, "Transcription failed: $errorMsg", Toast.LENGTH_LONG)
            } finally {
                withContext(Dispatchers.IO) { file.delete() }
                overlayState.value = OverlayState.IDLE
            }
        }
    }

    private fun saveToHistory(text: String) {
        try {
            val existing = SecurityUtils.getString(context, "transcription_history", "")
            val encodedText = java.net.URLEncoder.encode(text, "UTF-8")
            val newEntry = "${System.currentTimeMillis()}:$encodedText"
            val updated = if (existing.isEmpty()) newEntry else "$existing|$newEntry"

            // Keep only last 100 entries
            val entries = updated.split("|")
            val trimmed = if (entries.size > 100) entries.takeLast(100).joinToString("|") else updated

            // Enforce a hard character limit to prevent SharedPreferences size issues
            // (EncryptedSharedPreferences has overhead; 50KB of raw text is a safe ceiling)
            val finalHistory = if (trimmed.length > MAX_HISTORY_CHARS) {
                val idx = trimmed.length - MAX_HISTORY_CHARS
                val pipeIdx = trimmed.indexOf('|', idx)
                if (pipeIdx >= 0) trimmed.substring(pipeIdx + 1) else trimmed.takeLast(MAX_HISTORY_CHARS)
            } else {
                trimmed
            }

            SecurityUtils.putString(context, "transcription_history", finalHistory)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save history entry", e)
        }
    }

    private fun cancelRecording() {
        startRecordingJob?.cancel()
        startRecordingJob = null
        scope.launch(Dispatchers.IO) {
            voiceRecorder.cancelRecording()
        }
        stopAmplitudePolling()
        overlayState.value = OverlayState.IDLE
    }

    private fun adjustOverlayPositionForExpansion() {
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

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
                Log.e(TAG, "Failed to update overlay position for expansion", e)
            }
        }
    }

    private fun cleanupStaleAudioFiles() {
        try {
            context.cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("whisper_dictation_") && file.name.endsWith(".m4a")) {
                    // Delete files older than 1 hour (orphaned from crashed/killed sessions)
                    if (System.currentTimeMillis() - file.lastModified() > 3_600_000) {
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean stale audio files", e)
        }
    }

    fun showOverlay(interactionMode: String) {
        if (isShowing) return
        overlayState.value = OverlayState.IDLE

        // Best-effort cleanup of any orphaned audio files from previous sessions
        cleanupStaleAudioFiles()

        val initialTargetLanguage = SecurityUtils.getString(context, "target_language", "none")

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
                    initialTargetLanguage = initialTargetLanguage,
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
                            delay(1000)
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
                                            Log.e(TAG, "Failed to post frame callback", e)
                                            params.x = pendingX
                                            params.y = pendingY
                                            try {
                                                windowManager.updateViewLayout(this, params)
                                            } catch (ex: Exception) {
                                                Log.e(TAG, "Failed fallback layout update", ex)
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
            Log.e(TAG, "Failed to add overlay view", e)
            try {
                composeView?.disposeComposition()
            } catch (ex: Exception) { /* already failing */ }
            composeView = null
            isShowing = false
        }
    }

    fun hideOverlay() {
        dismissJob?.cancel()
        hideDismissTargetView()

        // Cancel any in-flight recording start before tearing down the overlay
        startRecordingJob?.cancel()
        startRecordingJob = null

        if (isFrameCallbackScheduled) {
            try {
                android.view.Choreographer.getInstance().removeFrameCallback(frameCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove frame callback", e)
            }
            isFrameCallbackScheduled = false
        }

        if (!isShowing) return
        if (overlayState.value == OverlayState.RECORDING_HOLD || overlayState.value == OverlayState.RECORDING_TAP) {
            cancelRecording()
        }
        composeView?.let {
            try {
                it.disposeComposition()
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove overlay view", e)
            }
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            composeView = null
        }
        isShowing = false
    }

    fun destroy() {
        hideOverlay()
        scope.cancel()
        store.clear()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
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