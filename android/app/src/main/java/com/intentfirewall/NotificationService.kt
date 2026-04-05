package com.intentfirewall

import android.app.Notification
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationService : NotificationListenerService() {
    private val tier3 by lazy { Tier3Classifier(applicationContext) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val notificationKey = sbn.key

        val extras = sbn.notification.extras

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extractMessageText(extras, sbn.notification)
        val isRedacted = isLikelyRedacted(text)

        Log.d(
            "IntentFirewall",
            "Notification received from: $packageName | Title: $title | Text: $text | Redacted: $isRedacted"
        )

        val aiEligibleApps = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging"
        )
        val shouldRunAiPipeline = packageName in aiEligibleApps

        val signature = "$notificationKey|${sbn.postTime}|$packageName|$title|$text|$isRedacted"
        if (MessageConsistencyCoordinator.shouldSkipSignature(signature)) {
            return
        }

        val appName = getAppName(packageName)

        if (text.isNotEmpty() && !isRedacted) {
            if (appName == "SMS" && MessageConsistencyCoordinator.hasRecentSmsBody(text)) {
                Log.d("IntentFirewall", "Skipping SMS notification text because SMS broadcast already captured it.")
                return
            }

            if (!shouldRunAiPipeline) {
                NotificationEventEmitter.sendNotification(
                    applicationContext,
                    appName,
                    if (title.isNotEmpty()) title else appName,
                    text,
                    packageName,
                    false,
                    "NOTIFICATION_CAPTURED",
                    ""
                )
                return
            }

            try {
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
                return
            } catch (e: Exception) {
                Log.e("IntentFirewall", "AI pipeline failed for $packageName, forwarding raw notification", e)
                NotificationEventEmitter.sendNotification(
                    applicationContext,
                    appName,
                    if (title.isNotEmpty()) title else appName,
                    text,
                    packageName,
                    false,
                    "AI_PIPELINE_ERROR",
                    ""
                )
                return
            }
        }

        val fallbackText = when {
            isRedacted -> "[Hidden by Android privacy settings]"
            text.isEmpty() -> "[No preview available for this notification]"
            else -> text
        }

        NotificationEventEmitter.sendNotification(
            applicationContext,
            appName,
            if (title.isNotEmpty()) title else appName,
            fallbackText,
            packageName,
            false,
            when {
                isRedacted -> "HIDDEN_BY_OS"
                text.isEmpty() -> "NO_PREVIEW"
                else -> ""
            },
            ""
        )

        Log.d(
            "IntentFirewall",
            "Metadata-only event forwarded for $packageName (redacted=$isRedacted, empty=${text.isEmpty()})."
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    private fun extractMessageText(extras: Bundle, notification: Notification): String {
        val directText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (directText.isNotEmpty()) {
            return directText
        }

        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        if (bigText.isNotEmpty()) {
            return bigText
        }

        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.map { it.toString().trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (textLines.isNotEmpty()) {
            return textLines.joinToString(separator = "\n")
        }

        val remoteInputHistory = extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
            ?.map { it.toString().trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (remoteInputHistory.isNotEmpty()) {
            return remoteInputHistory.joinToString(separator = "\n")
        }

        // MessagingStyle notifications often store message bodies in EXTRA_MESSAGES.
        val messageLines = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val parcelables = extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
            parcelables?.forEach { bundle ->
                val line = bundle.getCharSequence("text")?.toString()?.trim().orEmpty()
                if (line.isNotEmpty()) {
                    messageLines.add(line)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val parcelables = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            parcelables?.forEach { item ->
                val bundle = item as? Bundle ?: return@forEach
                val line = bundle.getCharSequence("text")?.toString()?.trim().orEmpty()
                if (line.isNotEmpty()) {
                    messageLines.add(line)
                }
            }
        }

        if (messageLines.isNotEmpty()) {
            return messageLines.joinToString(separator = "\n")
        }

        val tickerText = notification.tickerText?.toString()?.trim().orEmpty()
        if (tickerText.isNotEmpty()) {
            return tickerText
        }

        return ""
    }

    private fun isLikelyRedacted(text: String): Boolean {
        if (text.isEmpty()) return false
        val lower = text.lowercase()
        return lower.contains("sensitive notification content hidden") ||
            lower.contains("notification content hidden") ||
            lower.contains("hidden content")
    }

    private fun getAppName(packageName: String): String {
        return when (packageName) {
            "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
            "org.telegram.messenger" -> "Telegram"
            "com.android.mms", "com.google.android.apps.messaging", "com.samsung.android.messaging" -> "SMS"
            "com.android.shell" -> "System"
            else -> packageName
        }
    }
}