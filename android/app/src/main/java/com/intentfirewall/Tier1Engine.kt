package com.intentfirewall

import android.util.Log

enum class ScoreTier { HIGH, MEDIUM, LOW, SAFE }

data class Tier1Result(
    val score: Int,
    val tier: ScoreTier,
    val category: ScamCategory?,
    val matchedKeywords: List<String>,
    val evidencePhrases: List<String>,
    val requiresTier3: Boolean,
    val showProcessingNotif: Boolean,
)

/** Deterministic on-device tier that scores scam likelihood in <5ms for short messages. */
object Tier1Engine : Tier1EnginePort {
    private const val TAG = "SCAM_Tier1Engine"
    private val amountRegex = Regex("""\b(?:rs\.?|inr|₹)\s?\d{2,3}(?:,\d{3})+\b|\b\d{2,3}(?:,\d{3})+\s?(?:rupees|rs)\b""")
    private val coercionSignals = setOf(
        "hit and run", "accident", "victim", "medical expenses", "medical expense",
        "fir", "arrest", "jail", "warrant", "legal action", "settle this", "settle",
        "pay now", "transfer now", "or else", "otherwise"
    )
    private val authorityClaimSignals = setOf(
        "inspector", "police station", "police", "cyber crime", "officer", "department",
        "court", "crime branch", "investigation"
    )
    private val refundPhishSignals = setOf(
        "itr refund", "income tax", "refund", "approved", "credited", "credit", "pan details",
        "bank details", "update your", "update pan", "update bank", "could not be credited"
    )

    /** Analyze message with optional recent text context and return deterministic score and routing hints. */
    override fun analyze(text: String, context: List<String>): Tier1Result {
        val normalized = text.lowercase().trim().replace("\\s+".toRegex(), " ")
        val contextText = context.joinToString(" ").lowercase().replace("\\s+".toRegex(), " ")
        val combined = ("$normalized $contextText").trim()

        val authoritySignal = hasAny(combined, ScamKeywordDatabase.authoritySignals) || hasAny(combined, authorityClaimSignals)
        val moneySignal = hasAny(combined, ScamKeywordDatabase.moneySignals) || amountRegex.containsMatchIn(combined)
        val coercionSignal = hasAny(combined, ScamKeywordDatabase.urgencySignals) || hasAny(combined, coercionSignals)

        if (authoritySignal && moneySignal && coercionSignal) {
            val evidence = listOf("authority_claim", "money_demand", "coercion_or_harm")
            Log.d(TAG, "analyze hardRule=COERCIVE_IMPERSONATION score=96 tier=HIGH category=IMPERSONATION_SCAM")
            return Tier1Result(
                score = 96,
                tier = ScoreTier.HIGH,
                category = ScamCategory("IMPERSONATION_SCAM", "Impersonation Scam"),
                matchedKeywords = evidence,
                evidencePhrases = evidence,
                requiresTier3 = false,
                showProcessingNotif = false,
            )
        }

        val hasRefundSignal = hasAny(combined, refundPhishSignals)
        val hasUpdateSignal = combined.contains("update") &&
            (combined.contains("bank") || combined.contains("pan") || combined.contains("details"))
        val hasAuthorityOrMoney = authoritySignal || moneySignal || combined.contains("rs") || combined.contains("inr")

        if (hasRefundSignal && hasUpdateSignal && hasAuthorityOrMoney) {
            val evidence = listOf("refund_notice", "details_update_request", "authority_or_money_context")
            Log.d(TAG, "analyze hardRule=REFUND_UPDATE_PHISH score=94 tier=HIGH category=KYC_SCAM")
            return Tier1Result(
                score = 94,
                tier = ScoreTier.HIGH,
                category = ScamCategory("KYC_SCAM", "KYC Scam"),
                matchedKeywords = evidence,
                evidencePhrases = evidence,
                requiresTier3 = false,
                showProcessingNotif = false,
            )
        }

        var bestScore = 0
        var bestCategory: ScamCategory? = null
        var bestMatches: List<String> = emptyList()
        var bestEvidence: List<String> = emptyList()

        ScamKeywordDatabase.categories.forEach { cat ->
            val matches = mutableSetOf<String>()
            val evidence = mutableSetOf<String>()
            val highCount = matchedCount(normalized, cat.high, matches, evidence)
            val mediumCount = matchedCount(normalized, cat.medium, matches, evidence)
            val lowCount = matchedCount(normalized, cat.low, matches, evidence)
            val hinglishCount = matchedCount(normalized, cat.hinglish, matches, evidence)

            var score = 0
            score += highCount * 20
            score += mediumCount * 10
            score += lowCount * 5
            score += hinglishCount * 12

            if (highCount > 0) {
                score += cat.baseScore
            } else if (mediumCount + hinglishCount >= 2) {
                score += cat.baseScore / 2
            }

            val hasPrimarySignal = matches.isNotEmpty()

            if (cat.evidenceBoostKeywords.any { normalized.contains(it) }) {
                score += 10
            }

            val allGroupsSatisfied = cat.requireAllGroups.all { groupName ->
                val terms = cat.groups[groupName].orEmpty()
                terms.any { normalized.contains(it) }
            }
            if (hasPrimarySignal && cat.requireAllGroups.isNotEmpty() && !allGroupsSatisfied) {
                score -= 35
            }

            val anyGroupsSatisfied = cat.requireAnyGroup.isEmpty() || cat.requireAnyGroup.any { groupName ->
                val terms = cat.groups[groupName].orEmpty()
                terms.any { normalized.contains(it) }
            }
            if (hasPrimarySignal && !anyGroupsSatisfied) {
                score -= 20
            }

            if (hasPrimarySignal && hasAny(context.joinToString(" ").lowercase(), ScamKeywordDatabase.allKeywords)) score += 5
            // Keep primary-signal strength stable across long sessions; context presence should not weaken detection.
            if (hasPrimarySignal) score += 8
            if (hasPrimarySignal && hasAny(normalized, ScamKeywordDatabase.urgencySignals)) score += 6
            if (hasPrimarySignal && hasAny(normalized, ScamKeywordDatabase.moneySignals)) score += 6
            if (hasPrimarySignal && hasAny(normalized, ScamKeywordDatabase.authoritySignals)) score += 8

            score = score.coerceIn(0, 100)

            if (score > bestScore) {
                bestScore = score
                bestCategory = cat.category
                bestMatches = matches.toList().sorted()
                bestEvidence = evidence.toList().sortedByDescending { it.length }
            }
        }

        val tier = when {
            bestScore >= 88 -> ScoreTier.HIGH
            bestScore >= 60 -> ScoreTier.MEDIUM
            bestScore >= 30 -> ScoreTier.LOW
            else -> ScoreTier.SAFE
        }

        Log.d(TAG, "analyze score=$bestScore tier=$tier category=${bestCategory?.id ?: "SAFE"}")

        return Tier1Result(
            score = bestScore,
            tier = tier,
            category = if (tier == ScoreTier.SAFE) null else bestCategory,
            matchedKeywords = bestMatches,
            evidencePhrases = bestEvidence,
            requiresTier3 = tier == ScoreTier.MEDIUM || tier == ScoreTier.LOW,
            showProcessingNotif = tier == ScoreTier.MEDIUM,
        )
    }

    private fun matchedCount(
        text: String,
        candidates: Set<String>,
        matched: MutableSet<String>,
        evidence: MutableSet<String>,
    ): Int {
        var count = 0
        candidates.forEach { key ->
            if (text.contains(key)) {
                matched.add(key)
                evidence.add(key)
                count += 1
            }
        }
        return count
    }

    private fun hasAny(text: String, keys: Set<String>): Boolean = keys.any { text.contains(it) }
}
