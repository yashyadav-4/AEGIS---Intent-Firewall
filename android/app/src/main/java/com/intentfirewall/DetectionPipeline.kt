package com.intentfirewall

import android.content.Context
import android.util.Log

class DetectionPipeline(private val context: Context) {
    private val tier3: Tier3Classifier? by lazy {
        try {
            Tier3Classifier(context)
        } catch (e: Exception) {
            Log.e("IntentFirewall", "Tier3 disabled: model asset missing or failed to load", e)
            null
        }
    }

    fun processMessage(
        appName: String,
        packageName: String,
        title: String,
        text: String,
        sender: String = "",
        appSource: String = appName,
        captureMethod: String = "notification",
        eventTimestamp: Long = System.currentTimeMillis(),
    ) {
        val normalizedTitle = title.trim()
        val normalizedText = text.trim()
        val messageForOutput = if (normalizedText.isNotEmpty()) normalizedText else normalizedTitle
        val senderForOutput = sender.trim().ifEmpty {
            if (normalizedTitle.isNotEmpty()) normalizedTitle else appName
        }

        val combinedForDetection = listOf(normalizedTitle, messageForOutput)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

        if (combinedForDetection.isEmpty()) return

        ContextBuffer.addTurn(packageName, "[THEM]", combinedForDetection)
        val conversationContext = ContextBuffer.getContext(packageName)

        // Tier 1 — Regex Sentinel (~0.1ms)
        var finalFlagged = false
        var finalCategory = ""
        var waitTier3 = false

        val sentinelResult = RegexSentinel.analyze(combinedForDetection)
        if (sentinelResult.flagged) {
            finalFlagged = true
            finalCategory = sentinelResult.matchedCategory ?: "REGEX_TRIGGER"
            waitTier3 = true
        } else {
            // Check Hinglish
            val hinglishResult = RegexSentinel.detectHinglishScam(combinedForDetection)
            Log.d("IntentFirewall|HINGLISH", "HinglishScamResult: detected=${hinglishResult.detected}, confidence=${hinglishResult.confidence}")
            if (hinglishResult.detected) {
                if (hinglishResult.confidence > 0.85f) {
                    Log.i("IntentFirewall|HINGLISH", "Escalation: immediateWarning=true, waitTier3=false")
                    finalFlagged = true
                    finalCategory = hinglishResult.categories.firstOrNull() ?: "HINGLISH_SCAM"
                } else if (hinglishResult.confidence > 0.7f) {
                    Log.i("IntentFirewall|HINGLISH", "Escalation: immediateWarning=false, waitTier3=true")
                    waitTier3 = true
                }
            }
        }

        // Tier 2 — DistilBERT keyword classifier
        val tier2Result = Tier2Classifier(context).analyze(
            conversationContext.ifEmpty { combinedForDetection }
        )

        val hasRiskTokens = hasMediumRiskSignals(combinedForDetection)
        if (!finalFlagged && !tier2Result.isScam && tier2Result.confidence >= 0.42f && hasRiskTokens) {
            finalFlagged = true
            finalCategory = "SCAM_SUSPECT"
        }

        // Tier 3 — escalate
        val tier3Flagged = if (waitTier3 || tier2Result.isScam) {
            val tier3Model = tier3
            if (tier3Model != null) {
                val proxyFeatures = FloatArray(768) {
                    if (finalFlagged) 0.8f else tier2Result.confidence
                }
                val tier3Result = tier3Model.analyze(proxyFeatures)
                Log.d("AegisZero", "[T3] score=${tier3Result.confidence} latency=${tier3Result.latencyMs}ms")
                tier3Result.isScam
            } else {
                Log.w("IntentFirewall", "Tier3 skipped: model not available")
                false
            }
        } else false

        // Final combined flag
        finalFlagged = finalFlagged || tier2Result.isScam || tier3Flagged

        val finalMatchedCategory = when {
            tier3Flagged -> "AI_CONFIRMED"
            tier2Result.isScam -> tier2Result.label
            finalCategory.isNotEmpty() -> finalCategory
            else -> ""
        }

        if (finalFlagged) {
            ScamAlertNotifier.showAlert(
                context = context,
                appName = appName,
                message = combinedForDetection,
                category = finalMatchedCategory,
                confidence = tier2Result.confidence,
            )
        }

        // Always forward to React Native layer
        NotificationEventEmitter.sendNotification(
            context,
            appName,
            senderForOutput,
            messageForOutput,
            packageName,
            finalFlagged,
            finalMatchedCategory,
            conversationContext,
            tier2Result.confidence,
            senderForOutput,
            appSource,
            captureMethod,
            eventTimestamp,
        )

        Log.d("AegisZero", "[PIPELINE] ${appName}: T1Flagged=${finalFlagged} T2=${tier2Result.isScam}(${tier2Result.confidence}) T3=$tier3Flagged final=$finalFlagged")
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
}