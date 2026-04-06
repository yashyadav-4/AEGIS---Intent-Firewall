package com.intentfirewall

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationService : NotificationListenerService() {
    private val tier3: Tier3Classifier? by lazy {
        try {
            Tier3Classifier(applicationContext)
        } catch (e: Exception) {
            Log.e("IntentFirewall", "Tier3 disabled: model asset missing or failed to load", e)
            null
        }
    }

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
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.truecaller"
        )

        if (packageName in trackedApps && text.isNotEmpty()) {
            val appName = getAppName(packageName)
            ContextBuffer.addTurn(packageName, "[THEM]", text)
            val context = ContextBuffer.getContext(packageName)

            // Tier 1 — Regex Sentinel (~0.1ms)
            var finalFlagged = false
            var finalCategory = ""
            var waitTier3 = false

            val sentinelResult = RegexSentinel.analyze(text)
            if (sentinelResult.flagged) {
                finalFlagged = true
                finalCategory = sentinelResult.matchedCategory ?: "REGEX_TRIGGER"
                waitTier3 = true
            } else {
                // Check Hinglish
                val hinglishResult = RegexSentinel.detectHinglishScam(text)
                android.util.Log.d("IntentFirewall|HINGLISH", "HinglishScamResult: detected=${hinglishResult.detected}, confidence=${hinglishResult.confidence}")
                if (hinglishResult.detected) {
                    if (hinglishResult.confidence > 0.85f) {
                        android.util.Log.i("IntentFirewall|HINGLISH", "Escalation: immediateWarning=true, waitTier3=false")
                        finalFlagged = true
                        finalCategory = hinglishResult.categories.firstOrNull() ?: "HINGLISH_SCAM"
                    } else if (hinglishResult.confidence > 0.7f) {
                        android.util.Log.i("IntentFirewall|HINGLISH", "Escalation: immediateWarning=false, waitTier3=true")
                        waitTier3 = true
                    }
                }
            }

            // Tier 2 — DistilBERT keyword classifier
            val tier2Result = Tier2Classifier(applicationContext).analyze(
                context.ifEmpty { text }
            )

            // Tier 3 — escalate
            val tier3Flagged = if (waitTier3 || tier2Result.isScam) {
                val tier3Model = tier3
                if (tier3Model != null) {
                    // Use Tier 1 / Tier 2 result as proxy feature vector
                    val proxyFeatures = FloatArray(768) {
                        if (finalFlagged) 0.8f else tier2Result.confidence
                    }
                    val tier3Result = tier3Model.analyze(proxyFeatures)
                    Log.d("AegisZero", "[T3] score=${tier3Result.confidence} " +
                          "latency=${tier3Result.latencyMs}ms")
                    tier3Result.isScam
                } else {
                    Log.w("IntentFirewall", "Tier3 skipped: model not available")
                    false
                }
            } else false

            // Final combined flag
            finalFlagged = finalFlagged || tier2Result.isScam || tier3Flagged

            // Always forward to React Native layer
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
                    finalCategory.isNotEmpty() -> finalCategory
                    else -> ""
                },
                context
            )

            Log.d("AegisZero", "[PIPELINE] ${appName}: " +
                  "T1Flagged=${finalFlagged} " +
                  "T2=${tier2Result.isScam}(${tier2Result.confidence}) " +
                  "T3=$tier3Flagged final=$finalFlagged")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    private fun getAppName(packageName: String): String {
        return when (packageName) {
            "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
            "org.telegram.messenger" -> "Telegram"
            "com.android.mms", "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.truecaller" -> "SMS"
            else -> "Unknown"
        }
    }
}