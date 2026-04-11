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

    /** Analyze message with optional recent text context and return deterministic score and routing hints. */
    override fun analyze(text: String, context: List<String>): Tier1Result {
        val normalized = text.lowercase().trim().replace("\\s+".toRegex(), " ")
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
