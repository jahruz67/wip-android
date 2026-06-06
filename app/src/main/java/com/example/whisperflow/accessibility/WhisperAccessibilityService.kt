package com.example.whisperflow.accessibility

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.whisperflow.network.SecurityUtils
import com.example.whisperflow.overlay.OverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Accessibility service used solely to:
 *  1. Detect when an editable text field is focused and the soft keyboard is visible,
 *     so we can show the WhisperFlow voice input overlay.
 *  2. Inject the transcribed text into the currently focused editable field
 *     (with user consent — the user actively presses/holds the mic to record).
 *
 * This service does NOT bypass privacy controls, change system settings,
 * read screen content beyond the focused editable node, or perform any
 * action without an explicit user gesture.
 */
@SuppressLint("AccessibilityPolicy")
class WhisperAccessibilityService : AccessibilityService() {

    private lateinit var overlayManager: OverlayManager
    private var currentEditableNode: AccessibilityNodeInfo? = null
    private var isManuallyDismissed = false
    private val handler = Handler(Looper.getMainLooper())
    private val checkOverlayRunnable = Runnable { checkOverlayStateActual() }
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var checkOverlayJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceScope.launch(Dispatchers.IO) {
            SecurityUtils.getEncryptedSharedPreferences(applicationContext)
        }
        overlayManager = OverlayManager(
            context = this,
            onTextTranscribed = { transcribedText ->
                injectText(transcribedText)
            },
            onDismissed = {
                isManuallyDismissed = true
            }
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val node = event.source
                if (node != null && node.isEditable) {
                    recycleCurrentEditableNode()
                    @Suppress("DEPRECATION")
                    currentEditableNode = AccessibilityNodeInfo.obtain(node)
                    node.recycle()
                    isManuallyDismissed = false // Reset manual dismiss on new focus
                    checkOverlayState()
                } else {
                    node?.recycle()
                    recycleCurrentEditableNode()
                    handler.removeCallbacks(checkOverlayRunnable)
                    overlayManager.hideOverlay()
                }
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                checkOverlayState()
            }
            else -> {}
        }
    }

    private fun checkOverlayState() {
        handler.removeCallbacks(checkOverlayRunnable)
        handler.postDelayed(checkOverlayRunnable, 80)
    }

    private fun checkOverlayStateActual() {
        checkOverlayJob?.cancel()
        checkOverlayJob = serviceScope.launch {
            val sharedPrefsValues = withContext(Dispatchers.IO) {
                val serviceEnabled = SecurityUtils.getBoolean(this@WhisperAccessibilityService, "service_enabled", true)
                val mode = SecurityUtils.getString(this@WhisperAccessibilityService, "interaction_mode", "HOLD_TO_TALK")
                serviceEnabled to mode
            }
            applyOverlayState(sharedPrefsValues.first, sharedPrefsValues.second)
        }
    }

    private fun applyOverlayState(serviceEnabled: Boolean, mode: String) {
        val hasEditableFocus = currentEditableNode != null && currentEditableNode?.refresh() == true
        val keyboardVisible = isKeyboardVisible()

        if (!keyboardVisible) {
            isManuallyDismissed = false // Reset manual dismiss when keyboard is closed
        }

        if (hasEditableFocus && keyboardVisible && !isManuallyDismissed && serviceEnabled) {
            overlayManager.showOverlay(mode)
        } else {
            overlayManager.hideOverlay()
        }
    }

    private fun isKeyboardVisible(): Boolean {
        val windows = windows ?: return false
        for (window in windows) {
            if (window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                return true
            }
        }
        return false
    }

    override fun onInterrupt() {
        handler.removeCallbacks(checkOverlayRunnable)
        checkOverlayJob?.cancel()
        if (::overlayManager.isInitialized) {
            overlayManager.hideOverlay()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(checkOverlayRunnable)
        checkOverlayJob?.cancel()
        if (::overlayManager.isInitialized) {
            overlayManager.destroy()
        }
        recycleCurrentEditableNode()
        serviceScope.cancel()
    }

    private fun injectText(text: String) {
        val node = currentEditableNode ?: return
        if (!node.refresh()) {
            recycleCurrentEditableNode()
            return
        }
        
        val currentTextStr = node.text?.toString() ?: ""
        
        // Detect if the current text is actually a placeholder/hint (e.g., "Search here")
        val isHint = node.isShowingHintText || currentTextStr == node.hintText?.toString()
        
        val currentText = if (isHint) "" else currentTextStr
        val newText = if (currentText.isEmpty()) text else "$currentText $text"

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun recycleCurrentEditableNode() {
        currentEditableNode?.recycle()
        currentEditableNode = null
    }
}
