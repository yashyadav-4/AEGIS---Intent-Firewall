package com.intentfirewall

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class OpenChatAccessibilityService : AccessibilityService() {
    private data class NodeTextCandidate(
        val text: String,
        val yBottom: Int,
        val depth: Int,
    )

    companion object {
        private const val DUPLICATE_WINDOW_MS = 20_000L
        private const val DUPLICATE_CACHE_MAX = 500
        private const val MAX_PENDING_EVENTS = 6
        private const val MAX_EVENT_AGE_MS = 8_000L
    }

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
    private val pipeline by lazy { DetectionPipeline(applicationContext) }
    private val processingExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val pendingEvents = AtomicInteger(0)
    private val recentMessageHashes = linkedMapOf<String, Long>()
    private val ignoredWords = setOf(
        "online",
        "typing",
        "last seen",
        "read more",
        "drag handle",
        "no answer",
        "just now",
        "favourites filter",
        "search",
        "attach",
        "camera",
        "video call",
        "voice call",
        "back",
        "chats",
        "updates",
        "calls",
        "settings",
        "more options",
        "delete",
        "undo",
        "star",
        "forward",
        "reply",
        "reactions",
        "more reactions"
    )
    private val ignoredContains = listOf(
        "typing",
        "message",
        "read more",
        "drag handle",
        "favourites filter",
        "swipe down to reveal",
        "additional actions",
        "calling",
        "ringing",
        "no answer",
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
        "view once",
        "more options",
        "reaction",
        "delete",
        "forward",
        "starred"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            ServiceHealthMonitor.ensureStarted(applicationContext)
            ServiceHealthMonitor.onAccessibilityServiceConnected(applicationContext)
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.packageNames = trackedPackages.toTypedArray()
            info.notificationTimeout = 120
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            serviceInfo = info

            Log.i("IntentFirewall", "OpenChatAccessibilityService connected")
        } catch (t: Throwable) {
            Log.e("IntentFirewall", "OpenChatAccessibilityService onServiceConnected failed", t)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            if (!shouldHandleEvent(event)) return

            val packageName = event.packageName?.toString() ?: return
            if (!trackedPackages.contains(packageName)) return

            val extractedText = extractText(event).trim()
            if (extractedText.isBlank()) return
            if (isNoise(extractedText)) return

            val now = System.currentTimeMillis()
            if (shouldSkipDuplicateMessage(packageName, extractedText, now)) {
                return
            }

            if (extractedText == lastEmittedText && now - lastEmittedAt < 1500) {
                return
            }

            val pendingCount = pendingEvents.get()
            if (pendingCount >= MAX_PENDING_EVENTS && !hasMediumRiskSignals(extractedText)) {
                Log.d(
                    "IntentFirewall",
                    "Open-chat dropped low-risk backlog event (pending=$pendingCount): $extractedText"
                )
                return
            }

            lastEmittedText = extractedText
            lastEmittedAt = now
            ServiceHealthMonitor.onAccessibilityEventCaptured(applicationContext)

            pendingEvents.incrementAndGet()
            processingExecutor.execute {
                try {
                    val ageMs = System.currentTimeMillis() - now
                    if (ageMs > MAX_EVENT_AGE_MS && !hasMediumRiskSignals(extractedText)) {
                        Log.d(
                            "IntentFirewall",
                            "Open-chat dropped stale low-risk event age=${ageMs}ms: $extractedText"
                        )
                        return@execute
                    }

                    val app = appNameFor(packageName)
                    pipeline.processMessage(
                        appName = app,
                        packageName = packageName,
                        title = app,
                        text = extractedText,
                        sender = app,
                        appSource = app,
                        captureMethod = "accessibility",
                        eventTimestamp = now,
                    )
                } catch (t: Throwable) {
                    Log.e("IntentFirewall", "Open-chat processing failed", t)
                } finally {
                    pendingEvents.decrementAndGet()
                }
            }

            Log.d("IntentFirewall", "Open-chat captured from $packageName: $extractedText")
        } catch (t: Throwable) {
            Log.e("IntentFirewall", "Accessibility event handling failed", t)
        }
    }

    override fun onInterrupt() {
        // No-op.
    }

    override fun onDestroy() {
        try {
            processingExecutor.shutdownNow()
            ServiceHealthMonitor.onAccessibilityServiceDisconnected(applicationContext)
        } catch (t: Throwable) {
            Log.e("IntentFirewall", "OpenChatAccessibilityService onDestroy failed", t)
        } finally {
            super.onDestroy()
        }
    }

    private fun extractText(event: AccessibilityEvent): String {
        val candidates = mutableListOf<NodeTextCandidate>()

        for (part in event.text) {
            val t = part?.toString()?.trim().orEmpty()
            if (isPotentialMessageText(t)) {
                candidates.add(NodeTextCandidate(t, 0, 0))
            }
        }

        val eventDescription = event.contentDescription?.toString()?.trim().orEmpty()
        if (isPotentialMessageText(eventDescription)) {
            candidates.add(NodeTextCandidate(eventDescription, 0, 0))
        }

        val source = event.source
        if (source != null) {
            collectNodeText(source, candidates, 0)
        }

        return pickBestCandidate(candidates)
            .take(500)
    }

    private fun pickBestCandidate(candidates: List<NodeTextCandidate>): String {
        if (candidates.isEmpty()) return ""

        val screenHeight = resources?.displayMetrics?.heightPixels ?: 0

        val deduped = linkedMapOf<String, NodeTextCandidate>()
        for (candidate in candidates) {
            val normalizedText = candidate.text.replace("\\s+".toRegex(), " ").trim()
            if (!isPotentialMessageText(normalizedText)) continue
            if (isLikelyHeaderName(normalizedText)) continue

            val existing = deduped[normalizedText]
            if (existing == null || candidate.yBottom > existing.yBottom) {
                deduped[normalizedText] = candidate.copy(text = normalizedText)
            }
        }

        val sorted = deduped.values
            .sortedByDescending { scoreCandidate(it, screenHeight) }

        return sorted.firstOrNull()?.text.orEmpty()
    }

    private fun scoreCandidate(candidate: NodeTextCandidate, screenHeight: Int): Int {
        val text = candidate.text
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

        // Prefer latest visible bubbles, usually near bottom of the chat window.
        if (screenHeight > 0 && candidate.yBottom > 0) {
            val yRatio = (candidate.yBottom.toFloat() / screenHeight.toFloat()).coerceIn(0f, 1f)
            score += (yRatio * 120f).toInt()
            if (yRatio >= 0.65f) score += 25
        }

        // Slightly prefer text from deeper nodes, which are often message bubble leaves.
        score += candidate.depth.coerceAtMost(10) * 2

        return score
    }

    private fun collectNodeText(node: AccessibilityNodeInfo, acc: MutableList<NodeTextCandidate>, depth: Int) {
        if (depth > 7) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val yBottom = bounds.bottom

        val value = node.text?.toString()?.trim().orEmpty()
        if (isPotentialMessageText(value) && node.isVisibleToUser && !node.isPassword && !node.isEditable) {
            acc.add(NodeTextCandidate(value, yBottom, depth))
        }

        val description = node.contentDescription?.toString()?.trim().orEmpty()
        if (isPotentialMessageText(description) && node.isVisibleToUser && !node.isEditable) {
            acc.add(NodeTextCandidate(description, yBottom, depth))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodeText(child, acc, depth + 1)
        }
    }

    private fun shouldHandleEvent(event: AccessibilityEvent): Boolean {
        val type = event.eventType
        val packageName = event.packageName?.toString() ?: return false
        if (!trackedPackages.contains(packageName)) return false

        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            return false
        }

        val className = event.className?.toString()?.lowercase().orEmpty()
        if (className.contains("launcher") || className.contains("statusbar")) {
            return false
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
        if (isLikelyUiAction(lower)) return false

        if (lower.matches(Regex("^[0-9: ]{1,8}(am|pm)?$"))) return false
        if (lower.matches(Regex("^[0-9]{1,2} ?(min|mins|minutes|sec|secs|seconds)$"))) return false
        if (lower.endsWith("calling...") || lower.endsWith("calling…")) return false
        if (lower.endsWith("ringing...") || lower.endsWith("ringing…")) return false
        if (lower.matches(Regex("^[\\p{Punct}\\s]+$"))) return false
        if (lower.matches(Regex("^[a-z]{1,12}$")) && lower in setOf("ok", "yes", "no", "hmm")) return true
        if (!lower.matches(Regex(".*[a-zA-Z0-9].*"))) return false

        return true
    }

    private fun isLikelyHeaderName(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 3 || trimmed.length > 28) return false

        val lower = trimmed.lowercase()
        if (listOf("loan", "otp", "verify", "bank", "urgent", "click", "link", "http").any { lower.contains(it) }) {
            return false
        }

        val words = trimmed.split(" ").filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 3) return false

        return words.all { w ->
            w.all { ch -> ch.isLetter() } && w.firstOrNull()?.isUpperCase() == true
        }
    }

    private fun isNoise(text: String): Boolean {
        val normalized = text.lowercase().replace("\\s+".toRegex(), " ").trim()
        if (normalized.length < 2) return true

        return ignoredWords.contains(normalized) || ignoredContains.any { normalized.contains(it) }
    }

    private fun isLikelyUiAction(normalized: String): Boolean {
        if (normalized.length > 28) return false
        val actionTokens = setOf(
            "more options", "delete", "undo", "reply", "forward", "star",
            "reaction", "reactions", "copy", "select", "menu", "search",
            "camera", "attach", "send"
        )
        return actionTokens.any { token -> normalized == token || normalized.startsWith("$token ") }
    }

    private fun shouldSkipDuplicateMessage(packageName: String, text: String, now: Long): Boolean {
        val normalized = text.lowercase().replace("\\s+".toRegex(), " ").trim()
        if (normalized.isBlank()) return true

        val key = "$packageName|$normalized"
        val lastSeen = recentMessageHashes[key]
        if (lastSeen != null && now - lastSeen < DUPLICATE_WINDOW_MS) {
            return true
        }

        recentMessageHashes[key] = now

        val pruneBefore = now - DUPLICATE_WINDOW_MS
        val iterator = recentMessageHashes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value < pruneBefore) {
                iterator.remove()
            }
        }

        while (recentMessageHashes.size > DUPLICATE_CACHE_MAX) {
            val oldest = recentMessageHashes.entries.firstOrNull()?.key ?: break
            recentMessageHashes.remove(oldest)
        }

        return false
    }

    private fun hasMediumRiskSignals(text: String): Boolean {
        val lower = text.lowercase()
        val indicators = listOf(
            "otp", "verify", "bank", "kyc", "blocked", "suspended",
            "urgent", "click", "link", "http", "bit.ly", "tinyurl",
            "refund", "cashback", "lottery", "prize", "upi", "payment"
        )
        return indicators.any { lower.contains(it) }
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
