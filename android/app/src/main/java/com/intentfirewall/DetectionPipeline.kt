package com.intentfirewall

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Entry point for text scam detection that delegates to the two-tier pipeline. */
class DetectionPipeline(private val context: Context) {
    private val tag = "SCAM_DetectionPipeline"
    private val scamPipeline = ScamPipeline(context)

    init {
        scamPipeline.initialize(context)
        AlertManager.initialize(context)
    }

    /** Process one captured text event and emit notification + optional alert. */
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
        val senderForOutput = sender.trim().ifEmpty {
            if (normalizedTitle.isNotEmpty()) normalizedTitle else appName
        }

        val messageForOutput = if (normalizedText.isNotEmpty()) normalizedText else normalizedTitle
        if (messageForOutput.isBlank()) return

        ContextBuffer.addTurn(packageName, "[THEM]", messageForOutput)
        val conversationContext = ContextBuffer.getContext(packageName)
        val recent = conversationContext
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .takeLast(5)

        val result = runBlocking(Dispatchers.IO) {
            scamPipeline.process(
                text = messageForOutput,
                packageName = packageName,
                context = recent,
            )
        }

        val sourceLabel = when (captureMethod.lowercase()) {
            "accessibility" -> "ACCESSIBILITY"
            "notification" -> "NOTIFICATION"
            else -> captureMethod.uppercase()
        }

        val flagged = result.decision == Decision.ALERT
        val category = result.category ?: if (result.decision == Decision.SAFE) "SAFE" else "UNCERTAIN"

        if (flagged) {
            AlertManager.showAlert(
                context = context,
                appName = appName,
                categoryDisplayName = category,
                evidencePhrases = result.evidence,
                confidenceScore = result.confidenceScore,
                tier = result.tier,
                tier3Used = result.usedTier3,
            )

            ScamAlertNotifier.showAlert(
                context = context,
                appName = appName,
                message = messageForOutput,
                category = category,
                confidence = (result.confidenceScore.toFloat() / 100f).coerceIn(0f, 1f),
            )
        }

        NotificationEventEmitter.sendNotification(
            context = context,
            appName = appName,
            title = senderForOutput,
            text = messageForOutput,
            packageName = packageName,
            flagged = flagged,
            matchedCategory = category,
            conversationContext = conversationContext,
            confidence = (result.confidenceScore.toFloat() / 100f).coerceIn(0f, 1f),
            sender = senderForOutput,
            appSource = appSource,
            captureMethod = captureMethod,
            eventTimestamp = eventTimestamp,
            tierUsed = if (result.usedTier3) "tier3" else "tier1",
            tier1Score = if (result.tier == 1) (result.confidenceScore.toFloat() / 100f) else 0.0f,
            tier1Decision = when (result.decision) {
                Decision.ALERT -> "BLOCK"
                Decision.SAFE -> "ALLOW"
                Decision.UNCERTAIN -> "REVIEW"
            },
            tier1Category = if (result.tier == 1) category else "NONE",
            tier3Reason = result.evidence.joinToString(", "),
            tier3Model = BuildConfig.GEMINI_MODEL,
            tier3KeyIndex = 0,
        )

        DecisionTraceLogger.log(result, messageForOutput, packageName)
        Log.i(
            tag,
            "[$sourceLabel] app=$packageName decision=${result.decision} tier=${result.tier} category=$category text=\"${messageForOutput.take(140)}\""
        )
        Log.d(
            "IntentFirewall",
            "SOURCE=$sourceLabel TIER=${result.tier} TIER_USED=${if (result.usedTier3) "tier3" else "tier1"} app=$packageName decision=${result.decision} category=$category text=${messageForOutput.take(140)}"
        )
    }
}
