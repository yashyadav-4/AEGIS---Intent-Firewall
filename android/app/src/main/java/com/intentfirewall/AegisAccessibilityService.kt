package com.intentfirewall

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * AegisAccessibilityService acts strictly as an Android 10+ capability gateway.
 * By maintaining an active, user-granted AccessibilityService, Android's Concurrent 
 * Call Recording policy grants `com.intentfirewall` the right to use 
 * MediaRecorder.AudioSource.VOICE_RECOGNITION during an active phone call, thus 
 * bypassing the OEM microphone lock.
 */
class AegisAccessibilityService : AccessibilityService() {
    companion object {
        const val TAG = "AegisAccessibility"
    }

    override fun onServiceConnected() {
        Log.i(TAG, "AEGIS: Accessibility Engine online. Concurrent MIC capture unblocked.")
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !AegisCallState.isCallActive) return

        val text = event.text?.joinToString(" ")?.trim().orEmpty()
        if (text.isBlank()) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                AegisCallState.onNotificationSignal(
                    context = applicationContext,
                    packageName = event.packageName?.toString().orEmpty(),
                    title = "",
                    text = text
                )
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AEGIS: Accessibility Engine interrupted.")
    }
}
