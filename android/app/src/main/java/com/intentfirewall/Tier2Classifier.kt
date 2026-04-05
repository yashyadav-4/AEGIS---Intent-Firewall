package com.intentfirewall

import android.content.Context

data class Tier2Result(
    val isScam: Boolean,
    val confidence: Float,
    val label: String
)

class Tier2Classifier(@Suppress("UNUSED_PARAMETER") context: Context) {
    private val suspiciousTerms = listOf(
        "otp",
        "bank",
        "verify",
        "kyc",
        "urgent",
        "refund",
        "upi",
        "link",
        "pin",
        "account blocked"
    )

    fun analyze(text: String): Tier2Result {
        val normalized = text.lowercase()
        val hits = suspiciousTerms.count { term -> normalized.contains(term) }

        if (hits == 0) {
            return Tier2Result(isScam = false, confidence = 0.08f, label = "SAFE")
        }

        val confidence = (0.35f + hits * 0.12f).coerceAtMost(0.95f)
        return Tier2Result(isScam = confidence >= 0.55f, confidence = confidence, label = "SCAM_SUSPECT")
    }
}