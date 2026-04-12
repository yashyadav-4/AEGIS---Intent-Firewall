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

    private val tier2: Tier2Classifier? by lazy {
        try {
            Tier2Classifier(applicationContext)
        } catch (e: Exception) {
            Log.e("IntentFirewall", "Tier2 disabled: failed to load", e)
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tier3?.close()
        tier2?.close()
    }

    private fun extractDeepText(bundle: android.os.Bundle): String? {
        for (key in bundle.keySet()) {
            val value = bundle.get(key)
            if (value is CharSequence && value.toString().contains("₹500")) {
                return value.toString()
            }
            if (value is android.os.Bundle) {
                val res = extractDeepText(value)
                if (res != null) return res
            }
            if (value is Array<*>) {
                for (item in value) {
                    if (item is android.os.Bundle) {
                        val res = extractDeepText(item)
                        if (res != null) return res
                    }
                }
            }
        }
        return null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val packageName = sbn.packageName
        
        // Ignore our own foreground service notifications to prevent infinite mic loops
        if (packageName == applicationContext.packageName) return
        
        Log.d("IntentFirewall", "Notification received from: $packageName")

        val extras = sbn.notification?.extras ?: return

        val title = extras.getString("android.title") ?: ""
        var text = extras.getCharSequence("android.text")?.toString() ?: ""
        
        // Android 13/14/15 hides sensitive OTPs inside bigText or other extras
        if (text.contains("Sensitive notification") || text.isEmpty()) {
            val deepFound = extractDeepText(extras)
            if (!deepFound.isNullOrEmpty()) {
                text = deepFound
            }
        }

        AegisCallState.onNotificationSignal(
            context = applicationContext,
            packageName = packageName,
            title = title,
            text = text
        )

        Log.d("IntentFirewall", "Title: $title | Text: $text")

        // Ignore UI summaries and telecom logs 
        val isCallNotification = text.contains("call", ignoreCase = true) || 
                                 title.contains("call", ignoreCase = true) ||
                                 text.contains("ongoing", ignoreCase = true) ||
                                 packageName.contains("dialer") ||
                                 packageName.contains("incallui") || 
                                 packageName.contains("telecom") ||
                                 packageName.contains("whatsapp") && (text.contains("voice") || text.contains("video"));
                                 
        if (isCallNotification || text.matches(Regex(".*\\d+ messages from \\d+ chats.*", RegexOption.IGNORE_CASE))) {
            Log.d("IntentFirewall", "Skipping analysis for raw Call/VoIP notification, BUT LAUNCHING ACTIVE AUDIO+STT MONITOR")
            try {
                val monitorIntent = Intent(applicationContext, AegisCallMonitor::class.java).apply {
                    action = AegisCallMonitor.ACTION_START
                }
                androidx.core.content.ContextCompat.startForegroundService(applicationContext, monitorIntent)
            } catch (e: Exception) {
                Log.e("IntentFirewall", "Failed to start Call Monitor: ${e.message}")
            }
            return
        }
        
        // Ignore very short normal texts
        if (text.length <= 4 && !text.matches(Regex(".*\\d+.*"))) {
             Log.d("IntentFirewall", "Skipping deeply short message: $text")
             return
        }


        // Only process relevant apps
        val trackedApps = listOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.truecaller",
            "com.android.shell"
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
            val tier2Result = tier2?.analyze(
                context.ifEmpty { text }
            ) ?: Tier2Result(isScam = false, confidence = 0.0f, label = "SAFE")

            // Tier 3 — escalate
            val tier3Flagged = if (tier2Result.isScam || finalFlagged || tier2Result.confidence > 0.6f) {
                val tier3Model = tier3
                if (tier3Model != null) {
                    // Always process semantic data through Tier 3 to detect purely evasive texts
                    val confidenceUsed = if (tier2Result.confidence > 0) tier2Result.confidence else 0.85f
                    val proxyFeatures = FloatArray(768) {
                        if (finalFlagged) 0.8f else confidenceUsed
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

            if (finalFlagged) {
                val warningIntent = Intent(applicationContext, FrictionWarningActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(FrictionWarningActivity.EXTRA_CONFIDENCE_SCORE, 0.95f)
                    putExtra(FrictionWarningActivity.WARNING_REASON, "Scam Message Intercepted.")
                }
                runCatching {
                    applicationContext.startActivity(warningIntent)
                }.onFailure { error ->
                    Log.e("IntentFirewall", "Failed to launch scam warning UI", error)
                }
            }

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

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

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
