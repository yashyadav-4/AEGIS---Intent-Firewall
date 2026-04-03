package com.intentfirewall

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationService : NotificationListenerService() {
    private val tier3 by lazy { Tier3Classifier(applicationContext) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        Log.d("IntentFirewall", "Notification received from: $packageName")

        val extras = sbn.notification.extras

        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        Log.d("IntentFirewall", "Title: $title | Text: $text")

        // Only process relevant apps
        val trackedApps = listOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "com.android.mms",
            "com.google.android.apps.messaging"
        )

        if (packageName in trackedApps && text.isNotEmpty()) {
            val appName = getAppName(packageName)
            ContextBuffer.addTurn(packageName, "[THEM]", text)
            val context = ContextBuffer.getContext(packageName)

            // Tier 1 — Regex Sentinel (~0.1ms)
            val sentinelResult = RegexSentinel.analyze(text)

            // Tier 2 — DistilBERT keyword classifier
            val tier2Result = Tier2Classifier(applicationContext).analyze(
                context.ifEmpty { text }
            )

            // Tier 3 — escalate only if Tier 1 OR Tier 2 flagged
            val tier3Flagged = if (sentinelResult.flagged || tier2Result.isScam) {
                // Use Tier 1 sentinel result as proxy feature vector
                // (real implementation feeds DistilBERT hidden states)
                val proxyFeatures = FloatArray(768) {
                    if (sentinelResult.flagged) 0.8f else tier2Result.confidence
                }
                val tier3Result = tier3.analyze(proxyFeatures)
                Log.d("AegisZero", "[T3] score=${tier3Result.confidence} " +
                      "latency=${tier3Result.latencyMs}ms")
                tier3Result.isScam
            } else false

            // Final combined flag
            val finalFlagged = sentinelResult.flagged || tier2Result.isScam || tier3Flagged

            // Always forward to React Native layer with sentinel result
            NotificationEventEmitter.sendNotification(
                applicationContext,
                appName,
                title,
                text,
                packageName,
                finalFlagged,
                when {
                    tier3Flagged -> "AI_CONFIRMED"
                    tier2Result.isScam -> tier2Result.label
                    sentinelResult.flagged -> sentinelResult.matchedCategory ?: ""
                    else -> ""
                },
                context
            )

            Log.d("AegisZero", "[PIPELINE] ${appName}: " +
                  "T1=${sentinelResult.flagged} " +
                  "T2=${tier2Result.isScam}(${tier2Result.confidence}) " +
                  "T3=$tier3Flagged final=$finalFlagged")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    private fun getAppName(packageName: String): String {
        return when (packageName) {
            "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
            "org.telegram.messenger" -> "Telegram"
            "com.android.mms", "com.google.android.apps.messaging" -> "SMS"
            else -> "Unknown"
        }
    }
}