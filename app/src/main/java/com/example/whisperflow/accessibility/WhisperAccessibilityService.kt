package com.example.whisperflow.accessibility

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.whisperflow.overlay.OverlayManager

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

    override fun onServiceConnected() {
        super.onServiceConnected()
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
                    @Suppress("DEPRECATION")
                    currentEditableNode = AccessibilityNodeInfo.obtain(node)
                    isManuallyDismissed = false // Reset manual dismiss on new focus
                    checkOverlayState()
                } else {
                    currentEditableNode = null
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
        val hasEditableFocus = currentEditableNode != null && currentEditableNode?.refresh() == true
        val keyboardVisible = isKeyboardVisible()

        if (!keyboardVisible) {
            isManuallyDismissed = false // Reset manual dismiss when keyboard is closed
        }

        if (hasEditableFocus && keyboardVisible && !isManuallyDismissed) {
            val sharedPrefs = com.example.whisperflow.network.SecurityUtils.getEncryptedSharedPreferences(this)
            val mode = sharedPrefs.getString("interaction_mode", "HOLD_TO_TALK") ?: "HOLD_TO_TALK"
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
        if (::overlayManager.isInitialized) {
            overlayManager.hideOverlay()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::overlayManager.isInitialized) {
            overlayManager.hideOverlay()
        }
        currentEditableNode = null
    }

    private fun injectText(text: String) {
        val node = currentEditableNode ?: return
        if (!node.refresh()) {
            currentEditableNode = null
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
}
