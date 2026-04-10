package com.intentfirewall

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class DetectionPipeline(private val context: Context) {
    private companion object {
        private const val LEGACY_TIER1_SCORE = 0.0f
        private const val LEGACY_TIER1_DECISION = "DISABLED"
        private const val LEGACY_TIER1_CATEGORY = "DISABLED"
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
        val detectionText = messageForOutput.ifBlank { normalizedText }.ifBlank { combinedFallback(normalizedTitle, messageForOutput) }

        val combinedForDetection = listOf(normalizedTitle, detectionText)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

        if (combinedForDetection.isEmpty()) return

        ContextBuffer.addTurn(packageName, "[THEM]", combinedForDetection)
        val conversationContext = ContextBuffer.getContext(packageName)
        val recentMessages = conversationContext
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .takeLast(6)

        val hasRiskTokens = hasMediumRiskSignals(detectionText)
        val hasUrl = containsUrl(detectionText)
        if (isBenignAcknowledgement(detectionText) && !hasRiskTokens && !hasUrl) {
            NotificationEventEmitter.sendNotification(
                context,
                appName,
                senderForOutput,
                messageForOutput,
                packageName,
                false,
                "SAFE_MESSAGE",
                conversationContext,
                0.0f,
                senderForOutput,
                appSource,
                captureMethod,
                eventTimestamp,
                "tier3-only",
                LEGACY_TIER1_SCORE,
                LEGACY_TIER1_DECISION,
                LEGACY_TIER1_CATEGORY,
                "Benign acknowledgement",
                "",
                0,
            )
            Log.i("IntentFirewall", "[Tier3] benign acknowledgement bypassed: $detectionText")
            return
        }

        Log.i("IntentFirewall", "[Tier3] Analyzing message (tier3-only mode)")
        val tier3Decision = try {
            runBlocking(Dispatchers.IO) {
                GeminiScamClassifier.analyzeMessage(
                    context = context,
                    message = combinedForDetection,
                    sender = senderForOutput,
                    appSource = appSource,
                    timestamp = eventTimestamp,
                    captureMethod = captureMethod,
                    recentMessages = recentMessages,
                    senderKnown = false,
                )
            }
        } catch (e: Exception) {
            Log.e("IntentFirewall", "[Tier3] fatal error", e)
            null
        }

        val strictRiskSignal = hasHighRiskScamSignals(detectionText)
        val strongBenignSignal = hasStrongBenignSignals(detectionText)

        Log.i(
            "IntentFirewall|TierFlow",
            "rule=0.0 waitTier3=false runTier3=true " +
                "tier1Category=$LEGACY_TIER1_CATEGORY tier1Decision=$LEGACY_TIER1_DECISION tier1Score=$LEGACY_TIER1_SCORE " +
                "tier2Score=0.0 " +
                "message=\"${messageForOutput.take(120).replace("\\n", " ").replace("\\r", " ")}\""
        )

        var finalFlagged = false
        var finalCategory = ""

        if (tier3Decision != null) {
            val threatTypeNormalized = tier3Decision.threatType?.trim()?.uppercase().orEmpty()
            val isGenericOther = threatTypeNormalized == "OTHER"
            val tier3AlertFromModel = tier3Decision.action == "alert" || tier3Decision.isScam

            val tier3Alert = if (strongBenignSignal && !strictRiskSignal && tier3Decision.confidence < 0.90f) {
                false
            } else if (isGenericOther && !strictRiskSignal && tier3Decision.confidence < 0.82f) {
                false
            } else {
                tier3AlertFromModel && (strictRiskSignal || hasRiskTokens || tier3Decision.confidence >= 0.85f)
            }

            finalFlagged = tier3Alert
            finalCategory = normalizeThreatCategory(
                rawThreatType = tier3Decision.threatType,
                message = detectionText,
                flagged = tier3Alert,
                fallbackToScamSuspect = isGenericOther
            )

            Log.i(
                "IntentFirewall",
                "[Tier3] decision action=${tier3Decision.action} isScam=${tier3Decision.isScam} intent=${tier3Decision.threatType} " +
                    "confidence=${tier3Decision.confidence} strictSignal=$strictRiskSignal benignSignal=$strongBenignSignal " +
                    "other=$isGenericOther keyIndex=${tier3Decision.keyIndex}"
            )
        } else {
            Log.w("IntentFirewall", "[Tier3] unavailable; defaulting to safe (tier3-only mode)")
        }

        val alertConfidence = if (tier3Decision != null) {
            val c = tier3Decision.confidence.coerceIn(0f, 1f)
            if (finalFlagged) maxOf(c, 0.65f) else c
        } else {
            0.0f
        }

        val finalMatchedCategory = if (tier3Decision != null) finalCategory else "SAFE_MESSAGE"
        val tierUsed = "tier3-only"

        if (finalFlagged) {
            ScamAlertNotifier.showAlert(
                context = context,
                appName = appName,
                message = combinedForDetection,
                category = finalMatchedCategory,
                confidence = alertConfidence,
            )
        }

        NotificationEventEmitter.sendNotification(
            context,
            appName,
            senderForOutput,
            messageForOutput,
            packageName,
            finalFlagged,
            finalMatchedCategory,
            conversationContext,
            if (finalFlagged) alertConfidence else (tier3Decision?.confidence ?: 0.0f),
            senderForOutput,
            appSource,
            captureMethod,
            eventTimestamp,
            tierUsed,
            LEGACY_TIER1_SCORE,
            LEGACY_TIER1_DECISION,
            LEGACY_TIER1_CATEGORY,
            tier3Decision?.reason ?: "",
            tier3Decision?.model ?: "",
            tier3Decision?.keyIndex ?: 0,
        )

        Log.d(
            "AegisZero",
            "[PIPELINE] ${appName}: rule=0.0 combined=0.0 " +
                "T1Flagged=false T2=false(0.0) T3=${tier3Decision != null} final=$finalFlagged tierUsed=$tierUsed"
        )
    }

    private fun combinedFallback(title: String, text: String): String {
        return listOf(title.trim(), text.trim()).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun containsUrl(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("http://") ||
            lower.contains("https://") ||
            lower.contains("www.") ||
            lower.contains("bit.ly") ||
            lower.contains("tinyurl") ||
            Regex("\\b[a-z0-9-]+\\.[a-z]{2,}(/\\S*)?\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)
    }

    private fun isBenignAcknowledgement(text: String): Boolean {
        val lower = text.lowercase().trim().replace("\\s+".toRegex(), " ")
        if (lower.length > 40) return false
        val benignShortReplies = setOf(
            "yes",
            "yes sir",
            "ok",
            "ok sir",
            "okay",
            "okay sir",
            "thanks",
            "thank you",
            "noted",
            "done",
            "sure",
            "alright",
        )
        return lower in benignShortReplies
    }

    private fun normalizeThreatCategory(
        rawThreatType: String?,
        message: String,
        flagged: Boolean,
        fallbackToScamSuspect: Boolean,
    ): String {
        if (!flagged) return "SAFE_MESSAGE"

        val lowerRaw = rawThreatType?.trim()?.lowercase().orEmpty()
        val lowerMessage = message.lowercase()
        val hasUrl = containsUrl(lowerMessage)

        if (hasUrl) return "PHISHING_LINK"

        return when {
            lowerRaw.contains("otp") -> "OTP_SCAM"
            lowerRaw.contains("bank") || lowerRaw.contains("kyc") -> "BANKING_SCAM"
            lowerRaw.contains("authority") || lowerRaw.contains("police") || lowerRaw.contains("government") -> "AUTHORITY_IMPERSONATION"
            lowerRaw.contains("job") -> "JOB_SCAM"
            lowerRaw.contains("payment") || lowerRaw.contains("upi") -> "PAYMENT_PRESSURE"
            lowerRaw.contains("phish") || lowerRaw.contains("link") -> "PHISHING_LINK"
            fallbackToScamSuspect -> "SCAM_SUSPECT"
            else -> "SCAM_SUSPECT"
        }
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

    private fun hasHighRiskScamSignals(text: String): Boolean {
        val lower = text.lowercase()

        val otpSignal = listOf("otp", "one time password", "6 digit", "6-digit").any { lower.contains(it) }
        val requestSignal = listOf("send", "share", "bolo", "batayein", "batana", "de do", "de dena").any { lower.contains(it) }
        val lotterySignal = listOf("lottery", "winner", "claim", "prize", "jackpot", "reward").any { lower.contains(it) }
        val impersonationSignal = listOf("police", "bank", "rbi", "k yc", "kyc", "account suspended").any { lower.contains(it) }

        if (lotterySignal && (otpSignal || requestSignal)) return true
        if (otpSignal && requestSignal) return true
        if (impersonationSignal && (otpSignal || requestSignal)) return true

        return false
    }

    private fun hasStrongBenignSignals(text: String): Boolean {
        val lower = text.lowercase()
        val benignTokens = listOf(
            "teacher", "class", "assignment", "exam", "attendance",
            "hackathon", "project", "meeting", "schedule", "participating"
        )
        val hasBenignTopic = benignTokens.any { lower.contains(it) }
        return hasBenignTopic && !hasMediumRiskSignals(lower)
    }
}
