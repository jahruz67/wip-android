package com.example.whisperflow.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.media.MediaRecorder
import com.example.whisperflow.accessibility.WhisperAccessibilityService
import com.example.whisperflow.audio.VoiceRecorder
import com.example.whisperflow.network.SecurityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

// ── Extracted color constants to avoid repeated allocation during recomposition ──
private object C {
    val Bg = Color(0xFF111318)
    val CardBg = Color(0xFF1A1C23)
    val InputBg = Color(0xFF15171E)
    val Border = Color(0xFF2E313B)
    val Accent = Color(0xFF5B8DEF)
    val Green = Color(0xFF10B981)
    val Red = Color(0xFFE5484D)
    val GreenBadge = Color(0xFF46A758)
    val TextPrimary = Color(0xFFE8EAED)
    val TextSecondary = Color(0xFF8B8F9A)
    val TextMuted = Color(0xFF6B7076)
    val TextDim = Color(0xFF4B5563)
    val TextBody = Color(0xFFCDD0D5)
    val DeleteIcon = Color(0xFFE5484D).copy(alpha = 0.7f)
    val ChipBg = Color(0xFF2E313B)
    val StatusBg = Color(0xFF1A1C23)
    val GreenTint = Color(0x1A10B981)
    val RedTint = Color(0x1AE5484D)
    val GreenTrack = Color(0x4D10B981)
    val GrayTrack = Color(0x4D6B7076)
    val GreenGrantedTint = Color(0x1A46A758)
    val GrantBtnBg = Color(0xFF5B8DEF).copy(alpha = 0.15f)
}

// ── Reusable shapes (allocated once) ──
private val CardShape = RoundedCornerShape(16.dp)
private val InputShape = RoundedCornerShape(12.dp)
private val DropdownShape = RoundedCornerShape(14.dp)
private val PillShape = RoundedCornerShape(20.dp)
private val InfoBoxShape = RoundedCornerShape(10.dp)

private data class MainScreenSettings(
    val apiKey: String,
    val selectedModel: String,
    val interactionMode: String,
    val isServiceEnabled: Boolean,
    val selectedAudioSource: Int,
    val selectedLanguage: String,
    val selectedAiModel: String,
    val historyItems: List<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var showSavedIcon by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf("whisper-large-v3") }
    var interactionMode by remember { mutableStateOf("HOLD_TO_TALK") }
    var isModelDropdownExpanded by remember { mutableStateOf(false) }
    var isLangDropdownExpanded by remember { mutableStateOf(false) }
    var isSourceDropdownExpanded by remember { mutableStateOf(false) }
    var isAiDropdownExpanded by remember { mutableStateOf(false) }

    // Master On/Off Toggle
    var isServiceEnabled by remember {
        mutableStateOf(true)
    }

    // Audio Source Selection - dynamically lists all recording devices on the device
    val audioSourceList = remember { VoiceRecorder.getAvailableAudioSources(context) }
    var selectedAudioSource by remember {
        mutableStateOf(MediaRecorder.AudioSource.MIC)
    }

    // Translation Target Language (English, Spanish, or None)
    val languageOptions = remember {
        listOf(
            "none" to "None",
            "english" to "English",
            "spanish" to "Spanish"
        )
    }
    var selectedLanguage by remember {
        mutableStateOf("none")
    }

    // AI Enhancement Model Selection
    val aiModelOptions = remember {
        listOf(
            "none" to "None",
            "llama-3.2-3b-preview" to "Llama 3.2 3B (Fast)",
            "mixtral-8x7b-32768" to "Mixtral 8x7B (Powerful)",
            "gemma2-9b-it" to "Gemma 2 9B (Balanced)"
        )
    }
    var selectedAiModel by remember {
        mutableStateOf("none")
    }

    var historyItems by remember { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(context) {
        val settings = withContext(Dispatchers.IO) {
            MainScreenSettings(
                apiKey = SecurityUtils.getString(context, "api_key", ""),
                selectedModel = SecurityUtils.getString(context, "whisper_model", "whisper-large-v3"),
                interactionMode = SecurityUtils.getString(context, "interaction_mode", "HOLD_TO_TALK"),
                isServiceEnabled = SecurityUtils.getBoolean(context, "service_enabled", true),
                selectedAudioSource = SecurityUtils.getInt(context, "audio_source", MediaRecorder.AudioSource.MIC),
                selectedLanguage = SecurityUtils.getString(context, "target_language", "none"),
                selectedAiModel = SecurityUtils.getString(context, "ai_enhancement_model", "none"),
                historyItems = loadHistory(context)
            )
        }
        apiKey = settings.apiKey
        showSavedIcon = settings.apiKey.isNotEmpty()
        selectedModel = settings.selectedModel
        interactionMode = settings.interactionMode
        isServiceEnabled = settings.isServiceEnabled
        selectedAudioSource = settings.selectedAudioSource
        selectedLanguage = settings.selectedLanguage
        selectedAiModel = settings.selectedAiModel
        historyItems = settings.historyItems
    }

    var isServiceAccessible by remember { mutableStateOf(false) }

    // Check accessibility status off the main thread to avoid IPC on UI thread
    LaunchedEffect(context) {
        isServiceAccessible = withContext(Dispatchers.IO) { isAccessibilityServiceEnabled(context) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Check accessibility + reload history on resume (both off main thread)
                scope.launch(Dispatchers.IO) {
                    val accessible = isAccessibilityServiceEnabled(context)
                    val updatedHistory = loadHistory(context)
                    withContext(Dispatchers.Main) {
                        isServiceAccessible = accessible
                        historyItems = updatedHistory
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun getAudioSourceDisplayName(source: Int): String {
        return audioSourceList.find { it.first == source }?.second ?: "Microphone (Default)"
    }

    // Helper to get language display name
    fun getLanguageDisplayName(lang: String): String {
        return languageOptions.find { it.first == lang }?.second ?: lang.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    // Helper to get AI model display name
    fun getAiModelDisplayName(model: String): String {
        return aiModelOptions.find { it.first == model }?.second ?: "None"
    }

    val saveAllSettings = remember(apiKey, selectedModel, interactionMode, selectedAudioSource, selectedLanguage, selectedAiModel) {
        {
            scope.launch(Dispatchers.IO) {
                SecurityUtils.saveSettings(
                    context = context,
                    apiKey = apiKey,
                    whisperModel = selectedModel,
                    interactionMode = interactionMode,
                    audioSource = selectedAudioSource,
                    targetLanguage = selectedLanguage,
                    aiEnhancementModel = selectedAiModel
                )
                withContext(Dispatchers.Main) {
                    showSavedIcon = apiKey.isNotEmpty()
                }
            }
        }
    }

    val lazyListState = rememberLazyListState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(C.Bg)
    ) {

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(top = 28.dp, bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "WhisperFlow",
                                    fontSize = 22.sp,
                                    fontFamily = FontFamily.SansSerif,
                                    fontWeight = FontWeight.Bold,
                                    color = C.TextPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(InputShape)
                                        .background(C.ChipBg)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Groq",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = C.TextSecondary
                                    )
                                }
                            }
                            Text(
                                text = "Voice Dictation",
                                fontSize = 13.sp,
                                color = C.TextMuted,
                                fontWeight = FontWeight.Normal
                            )
                        }

                        // Status LED Indicator
                        val isActive = isServiceAccessible && isServiceEnabled
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(30.dp))
                                .background(C.StatusBg)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isActive) C.Green else Color(0xFFEF4444))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isActive) "Active" else "Inactive",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isActive) C.Green else Color(0xFFEF4444)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = C.Border, thickness = 1.dp)
                }
            }
        ) { paddingValues ->
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                // Service Toggle Card
                item(key = "service_toggle") {
                    val serviceBorderGlow = if (isServiceEnabled) C.Green else C.Red
                    PremiumGlassCard(borderGlow = serviceBorderGlow) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(InputShape)
                                    .background(if (isServiceEnabled) C.GreenTint else C.RedTint),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isServiceEnabled) Icons.Default.PlayArrow else Icons.Default.Stop,
                                    contentDescription = null,
                                    tint = if (isServiceEnabled) C.Green else C.Red,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "WhisperFlow Service",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = C.TextPrimary
                                )
                                Text(
                                    text = if (isServiceEnabled) "Overlay will appear when keyboard is active" else "Service disabled - overlay won't appear",
                                    fontSize = 11.sp,
                                    color = C.TextMuted
                                )
                            }

                            Switch(
                                checked = isServiceEnabled,
                                onCheckedChange = { enabled ->
                                    isServiceEnabled = enabled
                                    scope.launch(Dispatchers.IO) {
                                        SecurityUtils.putBoolean(context, "service_enabled", enabled)
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = C.Green,
                                    checkedTrackColor = C.GreenTrack,
                                    uncheckedThumbColor = C.TextMuted,
                                    uncheckedTrackColor = C.GrayTrack
                                )
                            )
                        }
                    }
                }

                // API Configuration Dashboard Card
                item(key = "api_key") {
                    val apiBorderGlow = if (apiKey.isEmpty()) Color(0x33E5484D) else C.Border
                    PremiumGlassCard(borderGlow = apiBorderGlow) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = C.Accent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "API Key",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = apiKey,
                                    onValueChange = {
                                        apiKey = it
                                        showSavedIcon = false
                                    },
                                    placeholder = { Text("gsk_...", color = C.TextDim) },
                                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        val image = if (isPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                            Icon(imageVector = image, contentDescription = null, tint = Color(0xFF9CA3AF))
                                        }
                                    },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = C.Accent,
                                        unfocusedBorderColor = C.Border,
                                        focusedLabelColor = C.Accent,
                                        unfocusedLabelColor = C.TextSecondary,
                                        focusedTextColor = C.TextPrimary,
                                        unfocusedTextColor = C.TextPrimary,
                                        focusedContainerColor = C.InputBg,
                                        unfocusedContainerColor = C.InputBg
                                    ),
                                    shape = InputShape,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(10.dp))

                                val buttonColor = if (showSavedIcon) C.GreenBadge else C.Accent
                                Button(
                                    onClick = { saveAllSettings() },
                                    colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                                    shape = InputShape,
                                    modifier = Modifier.height(56.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp)
                                ) {
                                    AnimatedContent(
                                        targetState = showSavedIcon,
                                        transitionSpec = {
                                            scaleIn(animationSpec = spring()) togetherWith scaleOut()
                                        },
                                        label = "SaveBtn"
                                    ) { isSaved ->
                                        if (isSaved) {
                                            Icon(Icons.Default.Check, contentDescription = "Saved", tint = Color.White, modifier = Modifier.size(22.dp))
                                        } else {
                                            Text("Save All", color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (showSavedIcon) Icons.Default.CheckCircle else Icons.Default.Info,
                                    contentDescription = null,
                                    tint = if (showSavedIcon) C.GreenBadge else C.TextMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (showSavedIcon) "All settings saved" else "Encrypted on-device storage",
                                    fontSize = 12.sp,
                                    color = if (showSavedIcon) C.GreenBadge else C.TextMuted
                                )
                            }
                        }
                    }
                }

                // Audio Source Selection Card
                item(key = "audio_source") {
                    val sourceDisplayName = remember(selectedAudioSource) { getAudioSourceDisplayName(selectedAudioSource) }
                    Box {
                        PremiumGlassCard {
                            Column(modifier = Modifier.padding(20.dp)) {
                                SectionHeader(icon = Icons.Default.Mic, title = "Audio Source")

                                // Lightweight dropdown trigger
                                DropdownTrigger(
                                    value = sourceDisplayName,
                                    expanded = isSourceDropdownExpanded,
                                    onClick = { isSourceDropdownExpanded = true }
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                InfoBox(
                                    label = sourceDisplayName,
                                    description = when (selectedAudioSource) {
                                        MediaRecorder.AudioSource.MIC -> "Default microphone. Works well in quiet environments."
                                        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "Optimized for speech recognition with noise reduction."
                                        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "Optimized for two-way voice communication."
                                        MediaRecorder.AudioSource.CAMCORDER -> "Camera microphone path for noisy environments."
                                        MediaRecorder.AudioSource.UNPROCESSED -> "Raw audio with no processing applied."
                                        else -> "Selected audio input device."
                                    }
                                )
                            }
                        }

                        // Lightweight dropdown menu
                        DropdownMenu(
                            expanded = isSourceDropdownExpanded,
                            onDismissRequest = { isSourceDropdownExpanded = false },
                            modifier = Modifier.background(C.CardBg)
                        ) {
                            audioSourceList.forEach { (source, name) ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(name, color = C.TextPrimary, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                text = when (source) {
                                                    MediaRecorder.AudioSource.MIC -> "Standard built-in microphone"
                                                    MediaRecorder.AudioSource.VOICE_RECOGNITION -> "Optimized for speech recognition"
                                                    MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "Optimized for voice calls"
                                                    MediaRecorder.AudioSource.UNPROCESSED -> "Raw unprocessed audio"
                                                    else -> "Detected audio input device"
                                                },
                                                color = C.TextMuted,
                                                fontSize = 11.sp
                                            )
                                        }
                                    },
                                    onClick = {
                                        selectedAudioSource = source
                                        isSourceDropdownExpanded = false
                                        scope.launch(Dispatchers.IO) {
                                            SecurityUtils.putInt(context, "audio_source", source)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // Whisper Model Selection Card
                item(key = "whisper_model") {
                    val modelDisplayName = remember(selectedModel) {
                        if (selectedModel == "whisper-large-v3") "Whisper Large V3 (Premium)" else "Whisper Large V3 Turbo (Fast)"
                    }
                    Box {
                        PremiumGlassCard {
                            Column(modifier = Modifier.padding(20.dp)) {
                                SectionHeader(icon = Icons.Default.Settings, title = "Whisper Model")

                                DropdownTrigger(
                                    value = modelDisplayName,
                                    expanded = isModelDropdownExpanded,
                                    onClick = { isModelDropdownExpanded = true }
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                InfoBox(
                                    label = if (selectedModel == "whisper-large-v3") "Accuracy" else "Speed",
                                    description = if (selectedModel == "whisper-large-v3") {
                                        "1.5B parameter model. Handles slang, jargon, and strong accents well."
                                    } else {
                                        "Up to 8x faster. Lower data usage, good for messaging."
                                    }
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = isModelDropdownExpanded,
                            onDismissRequest = { isModelDropdownExpanded = false },
                            modifier = Modifier.background(C.CardBg)
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("Whisper Large V3", color = C.TextPrimary, fontWeight = FontWeight.SemiBold)
                                        Text("Best accuracy, handles accents well", color = C.TextMuted, fontSize = 11.sp)
                                    }
                                },
                                onClick = {
                                    selectedModel = "whisper-large-v3"
                                    isModelDropdownExpanded = false
                                    scope.launch(Dispatchers.IO) {
                                        SecurityUtils.putString(context, "whisper_model", "whisper-large-v3")
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("Whisper Large V3 Turbo", color = C.TextPrimary, fontWeight = FontWeight.SemiBold)
                                        Text("Faster, lighter on data", color = C.TextMuted, fontSize = 11.sp)
                                    }
                                },
                                onClick = {
                                    selectedModel = "whisper-large-v3-turbo"
                                    isModelDropdownExpanded = false
                                    scope.launch(Dispatchers.IO) {
                                        SecurityUtils.putString(context, "whisper_model", "whisper-large-v3-turbo")
                                    }
                                }
                            )
                        }
                    }
                }

                // Translation Target Language Card
                item(key = "translation") {
                    val langDisplayName = remember(selectedLanguage) { getLanguageDisplayName(selectedLanguage) }
                    val langLabel = remember(selectedLanguage) {
                        when (selectedLanguage) {
                            "none" -> "Keep As-Is"
                            "english" -> "Auto-Translate to English"
                            else -> "Auto-Translate to ${selectedLanguage.replaceFirstChar { it.uppercase() }}"
                        }
                    }
                    Box {
                        PremiumGlassCard {
                            Column(modifier = Modifier.padding(20.dp)) {
                                SectionHeader(icon = Icons.Default.Translate, title = "Translation Mode")

                                DropdownTrigger(
                                    value = langDisplayName,
                                    expanded = isLangDropdownExpanded,
                                    onClick = { isLangDropdownExpanded = true }
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                InfoBox(
                                    label = langLabel,
                                    description = when (selectedLanguage) {
                                        "none" -> "Whisper detects spoken language and keeps it as-is without any translation."
                                        "english" -> "Whisper detects spoken language. If detected is English → keeps as-is. If detected is Spanish → translates to English."
                                        else -> "Whisper detects spoken language. If detected is Spanish → keeps as-is. If detected is English → translates to Spanish."
                                    }
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = isLangDropdownExpanded,
                            onDismissRequest = { isLangDropdownExpanded = false },
                            modifier = Modifier.background(C.CardBg)
                        ) {
                            languageOptions.forEach { (lang, display) ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(display, color = C.TextPrimary, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                text = when (lang) {
                                                    "none" -> "Whisper detects spoken language → keeps as-is."
                                                    "english" -> "Detects Spanish spoken → translates to English. Detects English → keeps as-is."
                                                    else -> "Detects English spoken → translates to Spanish. Detects Spanish → keeps as-is."
                                                },
                                                color = C.TextMuted,
                                                fontSize = 11.sp
                                            )
                                        }
                                    },
                                    onClick = {
                                        selectedLanguage = lang
                                        isLangDropdownExpanded = false
                                        scope.launch(Dispatchers.IO) {
                                            SecurityUtils.putString(context, "target_language", lang)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // AI Enhancement Card
                item(key = "ai_enhancement") {
                    val aiDisplayName = remember(selectedAiModel) { getAiModelDisplayName(selectedAiModel) }
                    Box {
                        PremiumGlassCard {
                            Column(modifier = Modifier.padding(20.dp)) {
                                SectionHeader(icon = Icons.Default.AutoFixHigh, title = "AI Enhancement")

                                DropdownTrigger(
                                    value = aiDisplayName,
                                    expanded = isAiDropdownExpanded,
                                    onClick = { isAiDropdownExpanded = true }
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                InfoBox(
                                    label = "Spelling & Grammar Correction",
                                    description = if (selectedAiModel == "none") {
                                        "No AI enhancement applied. Raw transcription will be used."
                                    } else {
                                        "Applies $aiDisplayName to fix spelling, grammar, and punctuation."
                                    }
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = isAiDropdownExpanded,
                            onDismissRequest = { isAiDropdownExpanded = false },
                            modifier = Modifier.background(C.CardBg)
                        ) {
                            aiModelOptions.forEach { (model, display) ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(display, color = C.TextPrimary, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                text = when (model) {
                                                    "none" -> "Raw transcription, no correction"
                                                    "llama-3.2-3b-preview" -> "Fast lightweight model for basic corrections"
                                                    "mixtral-8x7b-32768" -> "Powerful model with deep grammar understanding"
                                                    "gemma2-9b-it" -> "Balanced speed & accuracy for polish"
                                                    else -> ""
                                                },
                                                color = C.TextMuted,
                                                fontSize = 11.sp
                                            )
                                        }
                                    },
                                    onClick = {
                                        selectedAiModel = model
                                        isAiDropdownExpanded = false
                                        scope.launch(Dispatchers.IO) {
                                            SecurityUtils.putString(context, "ai_enhancement_model", model)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // Interaction Mode Cards Selector
                item(key = "interaction_mode") {
                    PremiumGlassCard {
                        Column(modifier = Modifier.padding(20.dp)) {
                            SectionHeader(icon = Icons.Default.TouchApp, title = "Interaction Mode")

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                InteractionModeCard(
                                    title = "Hold to Talk",
                                    description = "Press & hold overlay to speak. Releases automatically.",
                                    icon = Icons.Default.Mic,
                                    isSelected = interactionMode == "HOLD_TO_TALK",
                                    selectedColor = C.Accent,
                                    onClick = {
                                        interactionMode = "HOLD_TO_TALK"
                                        scope.launch(Dispatchers.IO) {
                                            SecurityUtils.putString(context, "interaction_mode", "HOLD_TO_TALK")
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )

                                InteractionModeCard(
                                    title = "Tap to Talk",
                                    description = "Tap to record. Tap checkmark/cancel controls when finished.",
                                    icon = Icons.Default.SettingsSuggest,
                                    isSelected = interactionMode == "TAP_TO_TALK",
                                    selectedColor = C.Accent,
                                    onClick = {
                                        interactionMode = "TAP_TO_TALK"
                                        scope.launch(Dispatchers.IO) {
                                            SecurityUtils.putString(context, "interaction_mode", "TAP_TO_TALK")
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Permissions Panel
                item(key = "permissions") {
                    PremiumGlassCard {
                        Column(modifier = Modifier.padding(20.dp)) {
                            SectionHeader(icon = Icons.Default.Shield, title = "Permissions")

                            PermissionPanel(
                                hasAccessibilityPermission = isServiceAccessible,
                                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                                onOpenOverlaySettings = onOpenOverlaySettings
                            )
                        }
                    }
                }

                // History Header
                item(key = "history_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = Color(0xFF6B7076),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "History",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFE8EAED)
                            )
                        }
                        
                    if (historyItems.isNotEmpty()) {
                            var showClearConfirm by remember { mutableStateOf(false) }
                            TextButton(
                                onClick = { showClearConfirm = true },
                                colors = ButtonDefaults.textButtonColors(contentColor = C.Red)
                            ) {
                                Text("Clear All", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                            if (showClearConfirm) {
                                AlertDialog(
                                    onDismissRequest = { showClearConfirm = false },
                                    title = { Text("Clear All History?", color = C.TextPrimary) },
                                    text = { Text("This will permanently delete all transcription history.", color = C.TextSecondary) },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                SecurityUtils.remove(context, "transcription_history")
                                            }
                                            historyItems = emptyList()
                                            showClearConfirm = false
                                        }) {
                                            Text("Clear", color = Color(0xFFE5484D), fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showClearConfirm = false }) {
                                            Text("Cancel", color = Color(0xFF8B8F9A))
                                        }
                                    },
                                    containerColor = C.CardBg,
                                    titleContentColor = C.TextPrimary,
                                    textContentColor = C.TextSecondary
                                )
                            }
                        }
                    }
                }

                // History Items List
                if (historyItems.isEmpty()) {
                    item(key = "history_empty") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(CardShape)
                                .background(C.CardBg)
                                .border(1.dp, C.Border, CardShape)
                                .padding(36.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = C.Border,
                                    modifier = Modifier.size(48.dp)
                                )
                                Text(
                                    text = "Ready for Dictation",
                                    fontWeight = FontWeight.Medium,
                                    color = C.TextSecondary,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "Focus a text field, then speak into the overlay to get started.",
                                    color = C.TextDim,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            }
                        }
                    }
                } else {
                    itemsIndexed(historyItems, key = { index, entry -> "$index:${entry.hashCode()}" }) { _, historyEntry ->
                        // Parse the history entry (format: "timestamp:text")
                        val parts = historyEntry.split(":", limit = 2)
                        val timestamp = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                        val rawText = parts.getOrNull(1) ?: historyEntry
                        val text = try {
                            java.net.URLDecoder.decode(rawText, Charsets.UTF_8.name())
                        } catch (e: Exception) {
                            rawText
                        }
                        val formattedTime = formatHistoryTime(timestamp)

                        PremiumGlassCard(borderGlow = C.Border) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.Top,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(C.Accent)
                                        .offset(y = 6.dp)
                                )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = text,
                                                fontSize = 14.sp,
                                                color = C.TextBody,
                                                fontWeight = FontWeight.Medium
                                            )
                                            if (timestamp > 0L) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = formattedTime,
                                                    fontSize = 11.sp,
                                                    color = C.TextMuted
                                                )
                                            }
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            historyItems = historyItems.filterNot { it == historyEntry }
                                            scope.launch(Dispatchers.IO) {
                                                saveHistoryList(context, historyItems)
                                            }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = C.DeleteIcon,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Helper to load history from cached secure preferences.
private fun loadHistory(context: Context): List<String> {
    val raw = SecurityUtils.getString(context, "transcription_history", "")
    if (raw.isEmpty()) return emptyList()
    return raw.split("|").filter { it.isNotBlank() }.reversed()
}

// Helper to save history list back to cached secure preferences.
private fun saveHistoryList(context: Context, items: List<String>) {
    val raw = items.reversed().joinToString("|")
    SecurityUtils.putString(context, "transcription_history", raw)
}

private fun formatHistoryTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val sdf = SimpleDateFormat("MMM dd, yyyy 'at' h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

// ── Section header used across multiple cards ──
@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 16.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = C.Accent, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.TextPrimary)
    }
}

// ── Lightweight dropdown trigger replacing heavy ExposedDropdownMenuBox + OutlinedTextField ──
@Composable
private fun DropdownTrigger(value: String, expanded: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InputShape)
            .background(C.InputBg)
            .border(1.dp, if (expanded) C.Accent else C.Border, InputShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = value, color = C.TextPrimary, fontSize = 14.sp)
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = C.TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ── Info box below dropdowns ──
@Composable
private fun InfoBox(label: String, description: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InfoBoxShape)
            .background(C.InputBg)
            .border(1.dp, C.Border, InfoBoxShape)
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = C.TextSecondary, letterSpacing = 0.5.sp)
            Text(text = description, fontSize = 11.sp, color = C.TextMuted, lineHeight = 16.sp)
        }
    }
}

@Composable
fun PremiumGlassCard(
    modifier: Modifier = Modifier,
    borderGlow: Color = C.Border,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(C.CardBg)
            .border(border = BorderStroke(1.dp, borderGlow), shape = CardShape)
    ) {
        content()
    }
}

@Composable
fun InteractionModeCard(
    title: String,
    description: String,
    icon: ImageVector,
    isSelected: Boolean,
    selectedColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(targetValue = if (isSelected) 1.02f else 1.0f, label = "scale")
    val borderGlow = if (isSelected) selectedColor else C.Border

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(DropdownShape)
            .background(if (isSelected) Color(0xFF1E2129) else C.InputBg)
            .border(1.dp, borderGlow, DropdownShape)
            .clickable(onClick = onClick)
            .padding(14.dp)
            .height(110.dp)
    ) {
        Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected) selectedColor else Color(0xFF6B7076),
                    modifier = Modifier.size(22.dp)
                )
                
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, if (isSelected) selectedColor else C.TextDim, CircleShape)
                        .background(if (isSelected) selectedColor else Color.Transparent)
                )
            }
            
            Column {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) C.TextPrimary else C.TextSecondary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = description, fontSize = 10.sp, color = C.TextMuted, lineHeight = 13.sp)
            }
        }
    }
}

@Composable
fun PermissionPanel(
    hasAccessibilityPermission: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasMicPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasMicPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                hasOverlayPermission = Settings.canDrawOverlays(context)
                // Accessibility permission is tracked by MainScreen on IO thread
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PermissionRow(title = "Microphone", description = "Needed for voice capture.", isGranted = hasMicPermission, icon = Icons.Default.Mic,
            onAction = { if (!hasMicPermission) micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) })
        PermissionRow(title = "Draw Over Apps", description = "Shows the floating mic button.", isGranted = hasOverlayPermission, icon = Icons.Default.Settings,
            onAction = onOpenOverlaySettings)
        PermissionRow(title = "Accessibility Service", description = "Injects transcribed text into fields.", isGranted = hasAccessibilityPermission, icon = Icons.Default.SettingsSuggest,
            onAction = onOpenAccessibilitySettings)
    }
}

@Composable
fun PermissionRow(
    title: String,
    description: String,
    isGranted: Boolean,
    icon: ImageVector,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InputShape)
            .background(C.InputBg)
            .border(1.dp, C.Border, InputShape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (isGranted) C.GreenGrantedTint else C.RedTint),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = if (isGranted) C.GreenBadge else C.Red, modifier = Modifier.size(18.dp))
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = C.TextPrimary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(if (isGranted) C.GreenBadge else Color(0xFFF59E0B)))
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = description, fontSize = 10.sp, color = C.TextMuted, lineHeight = 13.sp)
        }

        Spacer(modifier = Modifier.width(10.dp))

        if (isGranted) {
            Box(
                modifier = Modifier
                    .clip(PillShape)
                    .background(C.GreenGrantedTint)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(text = "Granted", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = C.GreenBadge)
            }
        } else {
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = C.GrantBtnBg, contentColor = C.Accent),
                shape = PillShape,
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Text(text = "Grant", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedComponentName = ComponentName(context, WhisperAccessibilityService::class.java)
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)
    while (colonSplitter.hasNext()) {
        val componentNameString = colonSplitter.next()
        val enabledService = ComponentName.unflattenFromString(componentNameString)
        if (enabledService != null && enabledService == expectedComponentName) {
            return true
        }
    }
    return false
}
