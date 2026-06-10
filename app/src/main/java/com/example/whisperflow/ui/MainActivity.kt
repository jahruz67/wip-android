package com.example.whisperflow.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.whisperflow.network.SecurityUtils

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Warm the value cache asynchronously to prevent main thread keystore initialization delays.
        // Uses lifecycleScope so the coroutine is cancelled if the activity is destroyed.
        lifecycleScope.launch(Dispatchers.IO) {
            SecurityUtils.getEncryptedSharedPreferences(applicationContext)
        }

        setContent {
            // Elegant Cyber-Dark Color Scheme
            val customColorScheme = darkColorScheme(
                background = Color(0xFF111318),
                surface = Color(0xFF1A1C23),
                primary = Color(0xFF5B8DEF),
                secondary = Color(0xFF5B8DEF),
                tertiary = Color(0xFFF59E0B),
                onBackground = Color(0xFFE8EAED),
                onSurface = Color(0xFFE8EAED),
                surfaceVariant = Color(0xFF22252E),
                onSurfaceVariant = Color(0xFF8B8F9A),
                outline = Color(0xFF2E313B),
                error = Color(0xFFE5484D)
            )

            MaterialTheme(
                colorScheme = customColorScheme
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onOpenAccessibilitySettings = { openAccessibilitySettings() },
                        onOpenOverlaySettings = { openOverlaySettings() }
                    )
                }
            }
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun openOverlaySettings() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }
}
