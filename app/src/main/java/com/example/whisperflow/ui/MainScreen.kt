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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import com.example.whisperflow.accessibility.WhisperAccessibilityService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { com.example.whisperflow.network.SecurityUtils.getEncryptedSharedPreferences(context) }

    var apiKey by remember { mutableStateOf(sharedPrefs.getString("api_key", "") ?: "") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var showSavedIcon by remember { mutableStateOf(apiKey.isNotEmpty()) }
    var selectedModel by remember { mutableStateOf(sharedPrefs.getString("whisper_model", "whisper-large-v3") ?: "whisper-large-v3") }
    var interactionMode by remember { mutableStateOf(sharedPrefs.getString("interaction_mode", "HOLD_TO_TALK") ?: "HOLD_TO_TALK") }
    var isDropdownExpanded by remember { mutableStateOf(false) }

    val historyItems = remember { mutableStateListOf("Did you say groq is fast? (10:42 AM)", "Whisper dictation injected directly into text field. (11:15 AM)") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF111318))
    ) {

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                // High-End Custom Header
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
                                    color = Color(0xFFE8EAED)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF2E313B))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Groq",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFF8B8F9A)
                                    )
                                }
                            }
                            Text(
                                text = "Voice Dictation",
                                fontSize = 13.sp,
                                color = Color(0xFF6B7076),
                                fontWeight = FontWeight.Normal
                            )
                        }
                        
                        // Status LED Indicator
                        val isServiceEnabled = isAccessibilityServiceEnabled(context)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(30.dp))
                                .background(Color(0xFF1A1C23))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isServiceEnabled) Color(0xFF10B981) else Color(0xFFEF4444))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isServiceEnabled) "Active" else "Inactive",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isServiceEnabled) Color(0xFF10B981) else Color(0xFFEF4444)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = Color(0xFF2E313B), thickness = 1.dp)
                }
            }
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                // API Configuration Dashboard Card
                item {
                    PremiumGlassCard(
                        borderGlow = if (apiKey.isEmpty()) Color(0x33E5484D) else Color(0xFF2E313B)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = Color(0xFF5B8DEF),
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
                                    placeholder = { Text("gsk_...", color = Color(0xFF4B5563)) },
                                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        val image = if (isPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                            Icon(imageVector = image, contentDescription = null, tint = Color(0xFF9CA3AF))
                                        }
                                    },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF5B8DEF),
                                        unfocusedBorderColor = Color(0xFF2E313B),
                                        focusedLabelColor = Color(0xFF5B8DEF),
                                        unfocusedLabelColor = Color(0xFF8B8F9A),
                                        focusedTextColor = Color(0xFFE8EAED),
                                        unfocusedTextColor = Color(0xFFE8EAED),
                                        focusedContainerColor = Color(0xFF15171E),
                                        unfocusedContainerColor = Color(0xFF15171E)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                
                                val buttonColor = if (showSavedIcon) Color(0xFF46A758) else Color(0xFF5B8DEF)
                                Button(
                                    onClick = { 
                                        sharedPrefs.edit().apply {
                                            putString("api_key", apiKey)
                                            putString("whisper_model", selectedModel)
                                            putString("interaction_mode", interactionMode)
                                            apply()
                                        }
                                        showSavedIcon = true
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = buttonColor
                                    ),
                                    shape = RoundedCornerShape(12.dp),
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
                                            Text("Save", color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(10.dp))
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (showSavedIcon) Icons.Default.CheckCircle else Icons.Default.Info,
                                    contentDescription = null,
                                    tint = if (showSavedIcon) Color(0xFF46A758) else Color(0xFF6B7076),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (showSavedIcon) "Saved" else "Encrypted on-device storage",
                                    fontSize = 12.sp,
                                    color = if (showSavedIcon) Color(0xFF46A758) else Color(0xFF6B7076)
                                )
                            }
                        }
                    }
                }

                // Whisper Model Selection Card
                item {
                    PremiumGlassCard {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = Color(0xFF5B8DEF),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Whisper Model",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFE8EAED)
                                )
                            }

                            ExposedDropdownMenuBox(
                                expanded = isDropdownExpanded,
                                onExpandedChange = { isDropdownExpanded = !isDropdownExpanded }
                            ) {
                                OutlinedTextField(
                                    value = if (selectedModel == "whisper-large-v3") "Whisper Large V3 (Premium)" else "Whisper Large V3 Turbo (Fast)",
                                    onValueChange = {},
                                    readOnly = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isDropdownExpanded) },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF5B8DEF),
                                        unfocusedBorderColor = Color(0xFF2E313B),
                                        focusedTextColor = Color(0xFFE8EAED),
                                        unfocusedTextColor = Color(0xFFE8EAED),
                                        focusedContainerColor = Color(0xFF15171E),
                                        unfocusedContainerColor = Color(0xFF15171E)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth()
                                )
                                
                                ExposedDropdownMenu(
                                    expanded = isDropdownExpanded,
                                    onDismissRequest = { isDropdownExpanded = false },
                                    modifier = Modifier.background(Color(0xFF1A1C23))
                                ) {
                                    DropdownMenuItem(
                                        text = { 
                                            Column {
                                                Text("Whisper Large V3", color = Color(0xFFE8EAED), fontWeight = FontWeight.SemiBold)
                                                Text("Best accuracy, handles accents well", color = Color(0xFF6B7076), fontSize = 11.sp)
                                            }
                                        },
                                        onClick = {
                                            selectedModel = "whisper-large-v3"
                                            isDropdownExpanded = false
                                            sharedPrefs.edit().putString("whisper_model", "whisper-large-v3").apply()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text("Whisper Large V3 Turbo", color = Color(0xFFE8EAED), fontWeight = FontWeight.SemiBold)
                                                Text("Faster, lighter on data", color = Color(0xFF6B7076), fontSize = 11.sp)
                                            }
                                        },
                                        onClick = {
                                            selectedModel = "whisper-large-v3-turbo"
                                            isDropdownExpanded = false
                                            sharedPrefs.edit().putString("whisper_model", "whisper-large-v3-turbo").apply()
                                        }
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(14.dp))
                            
                            // High-end informative card clarifying model speeds
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF15171E))
                                    .border(1.dp, Color(0xFF2E313B), RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = if (selectedModel == "whisper-large-v3") "Accuracy" else "Speed",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF8B8F9A),
                                        letterSpacing = 0.5.sp
                                    )
                                    Text(
                                        text = if (selectedModel == "whisper-large-v3") {
                                            "1.5B parameter model. Handles slang, jargon, and strong accents well."
                                        } else {
                                            "Up to 8x faster. Lower data usage, good for messaging."
                                        },
                                        fontSize = 11.sp,
                                        color = Color(0xFF6B7076),
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // Interaction Mode Cards Selector
                item {
                    PremiumGlassCard {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.TouchApp,
                                    contentDescription = null,
                                    tint = Color(0xFF5B8DEF),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Interaction Mode",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFE8EAED)
                                )
                            }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Hold-to-Talk Tab
                                InteractionModeCard(
                                    title = "Hold to Talk",
                                    description = "Press & hold overlay to speak. Releases automatically.",
                                    icon = Icons.Default.Mic,
                                    isSelected = interactionMode == "HOLD_TO_TALK",
                                    selectedColor = Color(0xFF5B8DEF),
                                    onClick = {
                                        interactionMode = "HOLD_TO_TALK"
                                        sharedPrefs.edit().putString("interaction_mode", "HOLD_TO_TALK").apply()
                                    },
                                    modifier = Modifier.weight(1f)
                                )

                                // Tap-to-Talk Tab
                                InteractionModeCard(
                                    title = "Tap to Talk",
                                    description = "Tap to record. Tap checkmark/cancel controls when finished.",
                                    icon = Icons.Default.SettingsSuggest,
                                    isSelected = interactionMode == "TAP_TO_TALK",
                                    selectedColor = Color(0xFF5B8DEF),
                                    onClick = {
                                        interactionMode = "TAP_TO_TALK"
                                        sharedPrefs.edit().putString("interaction_mode", "TAP_TO_TALK").apply()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Permissions Panel
                item {
                    PremiumGlassCard {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = Color(0xFF5B8DEF),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Permissions",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFE8EAED)
                                )
                            }
                            
                            PermissionPanel(
                                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                                onOpenOverlaySettings = onOpenOverlaySettings
                            )
                        }
                    }
                }

                // History Header
                item {
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
                            TextButton(
                                onClick = { historyItems.clear() },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE5484D))
                            ) {
                                Text("Clear All", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                        }
                    }
                }

                // History Items List
                if (historyItems.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF1A1C23))
                                .border(1.dp, Color(0xFF2E313B), RoundedCornerShape(16.dp))
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
                                    tint = Color(0xFF2E313B),
                                    modifier = Modifier.size(48.dp)
                                )
                                Text(
                                    text = "Ready for Dictation",
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF8B8F9A),
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "Focus a text field, then speak into the overlay to get started.",
                                    color = Color(0xFF4B5563),
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            }
                        }
                    }
                } else {
                    items(historyItems) { item ->
                        PremiumGlassCard(
                            borderGlow = Color(0xFF2E313B)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    // Custom bullet dot indicator
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF5B8DEF))
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = item,
                                        fontSize = 14.sp,
                                        color = Color(0xFFCDD0D5),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                IconButton(
                                    onClick = { historyItems.remove(item) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = Color(0xFFE5484D).copy(alpha = 0.7f),
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

@Composable
fun PremiumGlassCard(
    modifier: Modifier = Modifier,
    borderGlow: Color = Color(0xFF2E313B),
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp),
                spotColor = Color.Black,
                ambientColor = Color.Black
            )
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1A1C23))
            .border(
                border = BorderStroke(1.dp, borderGlow),
                shape = RoundedCornerShape(16.dp)
            )
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
    val borderGlow = if (isSelected) selectedColor else Color(0xFF2E313B)

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) Color(0xFF1E2129) else Color(0xFF15171E))
            .border(1.dp, borderGlow, RoundedCornerShape(14.dp))
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
                
                // Small Radio-like custom indicator
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .border(
                            1.5.dp,
                            if (isSelected) selectedColor else Color(0xFF4B5563),
                            CircleShape
                        )
                        .background(if (isSelected) selectedColor else Color.Transparent)
                )
            }
            
            Column {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) Color(0xFFE8EAED) else Color(0xFF8B8F9A)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 10.sp,
                    color = Color(0xFF6B7280),
                    lineHeight = 13.sp
                )
            }
        }
    }
}

@Composable
fun PermissionPanel(
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasMicPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasAccessibilityPermission by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasMicPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                hasOverlayPermission = Settings.canDrawOverlays(context)
                hasAccessibilityPermission = isAccessibilityServiceEnabled(context)
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
        // Permission 1: Microphone
        PermissionRow(
            title = "Microphone",
            description = "Needed for voice capture.",
            isGranted = hasMicPermission,
            icon = Icons.Default.Mic,
            onAction = {
                if (!hasMicPermission) micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        )

        // Permission 2: Overlay
        PermissionRow(
            title = "Draw Over Apps",
            description = "Shows the floating mic button.",
            isGranted = hasOverlayPermission,
            icon = Icons.Default.Settings,
            onAction = onOpenOverlaySettings
        )

        // Permission 3: Accessibility
        PermissionRow(
            title = "Accessibility Service",
            description = "Injects transcribed text into fields.",
            isGranted = hasAccessibilityPermission,
            icon = Icons.Default.SettingsSuggest,
            onAction = onOpenAccessibilitySettings
        )
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
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF15171E))
            .border(1.dp, Color(0xFF2E313B), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon Circle
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (isGranted) Color(0x1A46A758) else Color(0x1AE5484D)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isGranted) Color(0xFF46A758) else Color(0xFFE5484D),
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Info
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE8EAED)
                )
                Spacer(modifier = Modifier.width(6.dp))
                
                // Status dot
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (isGranted) Color(0xFF46A758) else Color(0xFFF59E0B))
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                fontSize = 10.sp,
                color = Color(0xFF6B7280),
                lineHeight = 13.sp
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Action / Status Badge
        if (isGranted) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x1A46A758))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "Granted",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF46A758)
                )
            }
        } else {
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF5B8DEF).copy(alpha = 0.15f),
                    contentColor = Color(0xFF5B8DEF)
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Text(
                    text = "Grant",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
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
