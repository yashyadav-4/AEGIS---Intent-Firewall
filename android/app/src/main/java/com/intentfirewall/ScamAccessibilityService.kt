package com.intentfirewall

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ScamAccessibilityService : AccessibilityService() {
    private companion object {
        private const val TAG = "SCAM_ScamAccessibilityService"
        private const val THROTTLE_MS = 300L
    }

    private enum class InternalState {
        RUNNING,
        INTERRUPTED,
        DESTROYED,
    }

    private class EventThrottle {
        private val lastByPackage = mutableMapOf<String, Long>()

        fun allow(packageName: String, now: Long): Boolean {
            val last = lastByPackage[packageName] ?: 0L
            if (now - last < THROTTLE_MS) {
                return false
            }
            lastByPackage[packageName] = now
            return true
        }
    }

    private class NoiseFilter {
        private val uiNoise = setOf(
            "ok", "cancel", "back", "more", "reply", "react", "forward", "delete", "read more",
            "delivered", "seen", "typing...", "online", "last seen", "recording", "open", "close",
            "settings", "search",
        )

        private val callStateNoise = setOf(
            "calling", "ringing", "call ended", "declined", "missed call", "connected", "on hold",
        )

        fun shouldDrop(text: String): Boolean {
            val normalized = text.trim()
            if (normalized.length < 4) return true

            val lower = normalized.lowercase()
            if (uiNoise.contains(lower)) return true
            if (callStateNoise.contains(lower)) return true
            if (Regex("^[\\p{So}\\s]+$").matches(normalized)) return true
            if (Regex("^\\d{1,2}:\\d{2}\\s?(AM|PM)?$", RegexOption.IGNORE_CASE).matches(normalized)) return true
            return false
        }
    }

    private class TextExtractor {
        fun extract(event: AccessibilityEvent, root: AccessibilityNodeInfo?): List<String> {
            val out = linkedSetOf<String>()
            event.text.forEach { value ->
                val text = value?.toString()?.trim().orEmpty()
                if (text.isNotBlank()) out.add(text)
            }

            val desc = event.contentDescription?.toString()?.trim().orEmpty()
            if (desc.isNotBlank()) out.add(desc)

            getAllTextFromNode(root).forEach { out.add(it) }

            if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                val notif = event.parcelableData as? Notification
                val extras = notif?.extras
                listOf(
                    extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                    extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty(),
                    extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                ).forEach {
                    if (it.isNotBlank()) out.add(it.trim())
                }
            }

            return out.toList()
        }

        fun getAllTextFromNode(node: AccessibilityNodeInfo?, depth: Int = 0): List<String> {
            if (node == null || depth > 8) return emptyList()
            if (!node.isVisibleToUser) return emptyList()

            val out = linkedSetOf<String>()
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) out.add(text)
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (desc.isNotBlank()) out.add(desc)

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                out.addAll(getAllTextFromNode(child, depth + 1))
                child?.recycle()
            }
            return out.toList()
        }
    }

    private val allowedPackages = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "com.truecaller.android",
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.android.phone",
        "com.google.android.dialer",
        "com.samsung.android.incallui",
    )

    private var internalState = InternalState.RUNNING
    private var eventThrottle: EventThrottle = EventThrottle()
    private var textExtractor: TextExtractor = TextExtractor()
    private var noiseFilter: NoiseFilter = NoiseFilter()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val scamPipeline = ScamPipeline()

    private var foregroundService: ScamForegroundService? = null
    private var isBound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? ScamForegroundService.ServiceBinder
            foregroundService = binder?.getService()
            Log.i(TAG, "Bound to ScamForegroundService at ${System.currentTimeMillis()}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            foregroundService = null
            isBound = false
            Log.w(TAG, "ScamForegroundService disconnected")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            startAnchorService()
            bindAnchorService()

            serviceInfo = AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                packageNames = allowedPackages.toTypedArray()
                notificationTimeout = 300
            }

            eventThrottle = EventThrottle()
            textExtractor = TextExtractor()
            noiseFilter = NoiseFilter()
            val restored = ContextCache.restoreSnapshot(applicationContext)
            Log.i(TAG, "Service connected ts=${System.currentTimeMillis()} restoredPkgs=${restored.size}")

            GeminiAnalysisQueue.initialize(applicationContext)
            scamPipeline.initialize(applicationContext)
            AlertManager.initialize(applicationContext)
            ServiceHealthMonitor.onAccessibilityServiceConnected(applicationContext)
            internalState = InternalState.RUNNING
        } catch (e: Exception) {
            Log.e(TAG, "onServiceConnected failed", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            val pkg = event.packageName?.toString().orEmpty()

            // Step 1: Package gate.
            if (!allowedPackages.contains(pkg)) return

            // Step 2: Event type gate.
            val eventType = event.eventType
            if (eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
                eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            ) {
                return
            }

            // Step 3: Throttle check.
            val now = System.currentTimeMillis()
            if (!eventThrottle.allow(pkg, now)) return

            // Step 4: Text extraction.
            val merged = textExtractor.extract(event, rootInActiveWindow)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()

            if (merged.isEmpty()) return

            // Step 5: Noise filtering.
            val meaningful = merged.filterNot { noiseFilter.shouldDrop(it) }
            if (meaningful.isEmpty()) return

            // Prioritize the latest candidate text; full-screen concatenation dilutes intent signals.
            val current = meaningful.last().take(500)

            // Step 6: Context assembly.
            val localRecent = meaningful.dropLast(1).takeLast(2)
            val historical = (foregroundService?.getRecentContext(pkg).orEmpty() + localRecent)
                .takeLast(3)
            // Step 7: Dispatch through two-tier pipeline.
            serviceScope.launch(Dispatchers.Default) {
                val pipelineResult = scamPipeline.process(current, pkg, historical)
                if (pipelineResult.decision == Decision.ALERT) {
                    AlertManager.showAlert(pipelineResult)
                }
            }
            foregroundService?.reportEvent(pkg, current, now)
            ContextCache.write(applicationContext, pkg, current, now)
            ServiceHealthMonitor.onAccessibilityEventCaptured(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "onAccessibilityEvent failed", e)
        }
    }

    /**
     * Null-safe recursive traversal with max depth and visibility checks.
     */
    fun getAllTextFromNode(node: AccessibilityNodeInfo?): List<String> {
        return textExtractor.getAllTextFromNode(node)
    }

    override fun onInterrupt() {
        try {
            internalState = InternalState.INTERRUPTED
            Log.w(TAG, "Interrupted at ${System.currentTimeMillis()} reason=system_interrupt")
            foregroundService?.reportServiceHealth(HealthStatus.DEGRADED)
        } catch (e: Exception) {
            Log.e(TAG, "onInterrupt failed", e)
        }
    }

    override fun onDestroy() {
        try {
            if (isBound) {
                unbindService(conn)
                isBound = false
            }
            ContextCache.flushSnapshot(applicationContext)
            GeminiAnalysisQueue.shutdown()
            serviceScope.cancel()
            Log.i(TAG, "Destroyed at ${System.currentTimeMillis()}")
            internalState = InternalState.DESTROYED
            ServiceHealthMonitor.onAccessibilityServiceDisconnected(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy failed", e)
        } finally {
            super.onDestroy()
        }
    }

    private fun startAnchorService() {
        val intent = Intent(this, ScamForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun bindAnchorService() {
        val intent = Intent(this, ScamForegroundService::class.java)
        isBound = bindService(
            intent,
            conn,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
        )
    }
}
