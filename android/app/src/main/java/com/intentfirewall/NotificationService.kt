package com.intentfirewall

import android.app.Notification
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationService : NotificationListenerService() {
    private data class PendingHiddenEvent(
        val scheduledAt: Long,
        val runnable: Runnable,
    )

    private val trackedApps = buildSet {
        add("com.whatsapp")
        add("com.whatsapp.w4b")
        add("org.telegram.messenger")
        add("com.google.android.gm")
        add("com.microsoft.office.outlook")
        add("com.android.mms")
        add("com.google.android.apps.messaging")
        add("com.samsung.android.messaging")
        add("com.truecaller")
        if (BuildConfig.DEBUG) {
            // ADB `cmd notification post` originates from this package.
            add("com.android.shell")
        }
    }

    private val pipeline by lazy { DetectionPipeline(applicationContext) }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val pendingHiddenEvents = mutableMapOf<String, PendingHiddenEvent>()

    private val redactionMarkers = listOf(
        "sensitive notification content hidden",
        "notification content hidden",
        "content hidden",
        "new message",
        "messages"
    )

    companion object {
        private const val UPGRADE_WINDOW_MS = 900L
    }

    override fun onCreate() {
        super.onCreate()
        ServiceHealthMonitor.ensureStarted(applicationContext)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        ServiceHealthMonitor.onNotificationListenerConnected(applicationContext)
        Log.i("IntentFirewall|NotifService", "NotificationListener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        ServiceHealthMonitor.onNotificationListenerDisconnected(applicationContext)
        Log.w("IntentFirewall|NotifService", "NotificationListener disconnected; requesting rebind")
        requestRebind(ComponentName(applicationContext, NotificationService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        try {
            val packageName = sbn.packageName ?: return
            if (!trackedApps.contains(packageName)) return

            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val extractedText = extractBestText(sbn).trim()
            val looksRedacted = isLikelyRedacted(extractedText)
            val eventTimestamp = if (sbn.postTime > 0L) sbn.postTime else System.currentTimeMillis()
            val notificationKey = buildNotificationKey(sbn)

            ServiceHealthMonitor.onNotificationEventCaptured(applicationContext)

            if (extractedText.isBlank() || looksRedacted) {
                val appName = getAppName(packageName)
                val fallbackCategory = if (looksRedacted) "HIDDEN_BY_OS" else "NO_PREVIEW"
                val dedupeKey = "meta:${title.ifBlank { packageName }}:$fallbackCategory"

                if (!MessageConsistencyCoordinator.shouldProcessNotification(packageName, dedupeKey)) {
                    return
                }

                NotificationEventEmitter.sendNotification(
                    context = applicationContext,
                    appName = appName,
                    title = title.ifBlank { appName },
                    text = "",
                    packageName = packageName,
                    flagged = false,
                    matchedCategory = fallbackCategory,
                    conversationContext = "",
                    confidence = 0.0f,
                    sender = title.ifBlank { appName },
                    appSource = appName,
                    captureMethod = "notification",
                    eventTimestamp = eventTimestamp,
                )

                Log.d(
                    "IntentFirewall|NotifService",
                    "Buffered metadata-only notification package=$packageName category=$fallbackCategory"
                )
                return
            }

            cancelPendingHiddenForward(notificationKey)

            if (!MessageConsistencyCoordinator.shouldProcessNotification(packageName, extractedText)) {
                return
            }

            val appName = getAppName(packageName)

            Log.d(
                "IntentFirewall",
                "SOURCE=NOTIFICATION package=$packageName title=$title text=$extractedText"
            )

            pipeline.processMessage(
                appName = appName,
                packageName = packageName,
                title = title,
                text = extractedText,
                sender = title,
                appSource = appName,
                captureMethod = "notification",
                eventTimestamp = eventTimestamp,
            )
        } catch (e: Exception) {
            Log.e("IntentFirewall|NotifService", "Failed in onNotificationPosted", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    override fun onDestroy() {
        synchronized(pendingHiddenEvents) {
            pendingHiddenEvents.values.forEach { mainHandler.removeCallbacks(it.runnable) }
            pendingHiddenEvents.clear()
        }
        ServiceHealthMonitor.onNotificationListenerDisconnected(applicationContext)
        super.onDestroy()
    }

    private fun extractBestText(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras

        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (text.isNotBlank()) return text

        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        if (bigText.isNotBlank()) return bigText

        val messagingStyleText = extractMessagingStyleText(extras)
        if (messagingStyleText.isNotBlank()) return messagingStyleText

        val remoteInputHistory = extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
        if (remoteInputHistory != null && remoteInputHistory.isNotEmpty()) {
            val joinedHistory = remoteInputHistory
                .mapNotNull { it?.toString()?.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            if (joinedHistory.isNotBlank()) return joinedHistory
        }

        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (lines != null && lines.isNotEmpty()) {
            val joined = lines.mapNotNull { it?.toString()?.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            if (joined.isNotBlank()) return joined
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?.toString()
                .orEmpty()
            if (conversation.isNotBlank()) return conversation
        }

        val ticker = sbn.notification.tickerText?.toString().orEmpty()
        if (ticker.isNotBlank()) return ticker

        return ""
    }

    private fun extractMessagingStyleText(extras: Bundle): String {
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return ""
        val lines = mutableListOf<String>()

        for (item in messages) {
            if (item is Bundle) {
                val line = item.getCharSequence("text")?.toString()?.trim().orEmpty()
                if (line.isNotBlank()) {
                    lines.add(line)
                }
            }
        }

        return lines.joinToString(" ").trim()
    }

    private fun isLikelyRedacted(text: String): Boolean {
        if (text.isBlank()) return false
        val normalized = text.lowercase().replace("\\s+".toRegex(), " ").trim()

        if (normalized.length <= 2) return true
        if (redactionMarkers.any { normalized.contains(it) }) return true

        return false
    }

    private fun buildNotificationKey(sbn: StatusBarNotification): String {
        val tag = sbn.tag ?: ""
        val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) sbn.key else ""
        return "${sbn.packageName}|${sbn.id}|$tag|$key"
    }

    private fun cancelPendingHiddenForward(notificationKey: String) {
        synchronized(pendingHiddenEvents) {
            val pending = pendingHiddenEvents.remove(notificationKey) ?: return
            mainHandler.removeCallbacks(pending.runnable)
        }
    }

    private fun scheduleHiddenOrNoPreviewForward(
        notificationKey: String,
        packageName: String,
        title: String,
        isRedacted: Boolean,
        eventTimestamp: Long,
    ) {
        cancelPendingHiddenForward(notificationKey)

        val runnable = Runnable {
            val appName = getAppName(packageName)
            val fallbackText = if (isRedacted) {
                "[Hidden by Android privacy settings]"
            } else {
                "[No preview available for this notification]"
            }
            val fallbackCategory = if (isRedacted) "HIDDEN_BY_OS" else "NO_PREVIEW"

            if (!MessageConsistencyCoordinator.shouldProcessNotification(packageName, fallbackText)) {
                return@Runnable
            }

            NotificationEventEmitter.sendNotification(
                context = applicationContext,
                appName = appName,
                title = title.ifBlank { appName },
                text = fallbackText,
                packageName = packageName,
                flagged = false,
                matchedCategory = fallbackCategory,
                conversationContext = "",
                confidence = 0.0f,
                sender = title.ifBlank { appName },
                appSource = appName,
                captureMethod = "notification",
                eventTimestamp = eventTimestamp,
            )

            Log.d(
                "IntentFirewall|NotifService",
                "Forwarded metadata-only notification package=$packageName category=$fallbackCategory"
            )
        }

        synchronized(pendingHiddenEvents) {
            pendingHiddenEvents[notificationKey] = PendingHiddenEvent(
                scheduledAt = System.currentTimeMillis(),
                runnable = runnable,
            )
        }

        mainHandler.postDelayed(runnable, UPGRADE_WINDOW_MS)
    }

    private fun getAppName(packageName: String): String {
        return when (packageName) {
            "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
            "org.telegram.messenger" -> "Telegram"
            "com.google.android.gm" -> "Gmail"
            "com.microsoft.office.outlook" -> "Outlook"
            "com.android.mms", "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.truecaller" -> "SMS"
            "com.android.shell" -> "System"
            else -> "System"
        }
    }
}