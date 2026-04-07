package com.intentfirewall

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

class OpenChatAccessibilityService : AccessibilityService() {
    private val trackedPackages = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging"
    )

    private var lastEmittedText: String = ""
    private var lastEmittedAt: Long = 0L
    private var globalWarmupUntil: Long = 0L
    private val pipeline by lazy { DetectionPipeline(applicationContext) }
    private val packageWarmupUntil = mutableMapOf<String, Long>()
    private val alertedHashes = linkedMapOf<String, Long>()
    private val ignoredWords = setOf(
        "online",
        "typing",
        "last seen",
        "search",
        "attach",
        "camera",
        "video call",
        "voice call",
        "back",
        "chats",
        "updates",
        "calls",
        "settings"
    )
    private val ignoredContains = listOf(
        "typing",
        "message",
        "attach",
        "search",
        "video call",
        "voice call",
        "today",
        "yesterday",
        "online",
        "last seen",
        "enlarge photo",
        "tap to",
        "view once"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        ServiceHealthMonitor.ensureStarted(applicationContext)
        ServiceHealthMonitor.onAccessibilityServiceConnected(applicationContext)
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED or
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.packageNames = trackedPackages.toTypedArray()
        info.notificationTimeout = 120
        info.flags = info.flags or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        serviceInfo = info
        globalWarmupUntil = System.currentTimeMillis() + 5000L

        Log.i("IntentFirewall", "OpenChatAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!shouldHandleEvent(event)) return

        val packageName = event.packageName?.toString() ?: return
        if (!trackedPackages.contains(packageName)) return

        val extractedText = extractText(event).trim()
        if (extractedText.isBlank()) return
        if (isNoise(extractedText)) return

        val now = System.currentTimeMillis()
        if (extractedText == lastEmittedText && now - lastEmittedAt < 1500) {
            return
        }

        lastEmittedText = extractedText
        lastEmittedAt = now
        ServiceHealthMonitor.onAccessibilityEventCaptured(applicationContext)

        pipeline.processMessage(
            appName = appNameFor(packageName),
            packageName = packageName,
            title = "Open chat",
            text = extractedText,
            sender = "Open chat",
            appSource = appNameFor(packageName),
            captureMethod = "accessibility",
            eventTimestamp = now,
        )

        Log.d("IntentFirewall", "Open-chat captured from $packageName: $extractedText")
    }

    override fun onInterrupt() {
        // No-op.
    }

    override fun onDestroy() {
        ServiceHealthMonitor.onAccessibilityServiceDisconnected(applicationContext)
        super.onDestroy()
    }

    private fun extractText(event: AccessibilityEvent): String {
        val textParts = linkedSetOf<String>()

        for (part in event.text) {
            val t = part?.toString()?.trim().orEmpty()
            if (isPotentialMessageText(t)) {
                textParts.add(t)
            }
        }

        val eventDescription = event.contentDescription?.toString()?.trim().orEmpty()
        if (isPotentialMessageText(eventDescription)) {
            textParts.add(eventDescription)
        }

        val source = event.source
        if (source != null) {
            collectNodeText(source, textParts, 0)
            source.recycle()
        }

        return textParts.asSequence()
            .map { it.replace("\\s+".toRegex(), " ").trim() }
            .filter { isPotentialMessageText(it) }
            .distinct()
            .toList()
            .let { candidates -> pickBestCandidate(candidates) }
            .take(500)
    }

    private fun pickBestCandidate(candidates: List<String>): String {
        if (candidates.isEmpty()) return ""

        val sorted = candidates
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .sortedByDescending { scoreCandidate(it) }

        return sorted.firstOrNull().orEmpty()
    }

    private fun scoreCandidate(text: String): Int {
        val normalized = text.lowercase()
        var score = text.length.coerceAtMost(140)

        if (normalized.contains("http") || normalized.contains("otp") || normalized.contains("verify")) {
            score += 30
        }
        if (normalized.matches(Regex(".*[a-zA-Z].*")) && normalized.matches(Regex(".*[0-9].*"))) {
            score += 12
        }
        if (ignoredContains.any { normalized.contains(it) }) {
            score -= 60
        }
        if (normalized == "whatsapp" || normalized == "telegram") {
            score -= 100
        }

        return score
    }

    private fun collectNodeText(node: AccessibilityNodeInfo, acc: MutableSet<String>, depth: Int) {
        if (depth > 5) return

        val value = node.text?.toString()?.trim().orEmpty()
        if (isPotentialMessageText(value) && node.isVisibleToUser && !node.isPassword && !node.isEditable) {
            acc.add(value)
        }

        val description = node.contentDescription?.toString()?.trim().orEmpty()
        if (isPotentialMessageText(description) && node.isVisibleToUser && !node.isEditable) {
            acc.add(description)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodeText(child, acc, depth + 1)
            child.recycle()
        }
    }

    private fun shouldHandleEvent(event: AccessibilityEvent): Boolean {
        val type = event.eventType
        val packageName = event.packageName?.toString() ?: return false
        if (!trackedPackages.contains(packageName)) return false

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            packageWarmupUntil[packageName] = System.currentTimeMillis() + 3500L
            return false
        }

        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) {
            return false
        }

        val className = event.className?.toString()?.lowercase().orEmpty()
        if (className.contains("launcher") || className.contains("statusbar")) {
            return false
        }

        return true
    }

    private fun shouldRaiseLiveAlert(
        packageName: String,
        text: String,
        flagged: Boolean,
        strongRegexMatch: Boolean,
    ): Boolean {
        if (!flagged) return false

        val now = System.currentTimeMillis()
        if (now < globalWarmupUntil) return false
        if (now < (packageWarmupUntil[packageName] ?: 0L)) return false
        if (text.length < 10) return false

        val lower = text.lowercase()
        val hasRiskToken = listOf(
            "otp", "verify", "bank", "account", "blocked", "suspended",
            "urgent", "click", "http", "bit.ly", "upi", "payment"
        ).any { lower.contains(it) }

        if (!strongRegexMatch && !hasRiskToken) return false

        val hash = "$packageName|${lower.replace("\\s+".toRegex(), " ").trim()}"
        val last = alertedHashes[hash] ?: 0L
        if (now - last < 180_000L) return false

        alertedHashes[hash] = now
        if (alertedHashes.size > 300) {
            val oldest = alertedHashes.entries.minByOrNull { it.value }?.key
            if (oldest != null) {
                alertedHashes.remove(oldest)
            }
        }

        return true
    }

    private fun isPotentialMessageText(text: String): Boolean {
        val normalized = text.trim().replace("\\s+".toRegex(), " ")
        if (normalized.length < 2) return false
        if (normalized.length > 420) return false

        val lower = normalized.lowercase()
        if (ignoredWords.contains(lower)) return false
        if (ignoredContains.any { lower.contains(it) }) return false

        if (lower.matches(Regex("^[0-9: ]{1,8}(am|pm)?$"))) return false
        if (lower.matches(Regex("^[\\p{Punct}\\s]+$"))) return false
        if (lower.matches(Regex("^[a-z]{1,12}$")) && lower in setOf("ok", "yes", "no", "hmm")) return true
        if (!lower.matches(Regex(".*[a-zA-Z0-9].*"))) return false

        return true
    }

    private fun isNoise(text: String): Boolean {
        val normalized = text.lowercase()
        if (normalized.length < 2) return true

        return ignoredWords.contains(normalized)
    }

    private fun appNameFor(packageName: String): String {
        return when (packageName) {
            "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
            "org.telegram.messenger" -> "Telegram"
            "com.android.mms", "com.google.android.apps.messaging", "com.samsung.android.messaging" -> "SMS"
            else -> "Unknown"
        }
    }
}
