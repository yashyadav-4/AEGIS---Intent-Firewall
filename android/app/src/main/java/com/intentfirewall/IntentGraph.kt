package com.intentfirewall

/**
 * Forms a graph/chain of intent escalations across a conversation.
 * Determines if independent benign intents chain together into a scam scenario.
 */
class IntentGraph {
    
    data class IntentNode(
        val category: String,
        val weight: Float,
        val textSpan: String,
        val timestamp: Long
    )
    
    private val nodes = mutableListOf<IntentNode>()
    
    /**
     * Integrates findings from RegexSentinel into the situational graph.
     */
    fun updateGraph(result: RegexSentinel.HinglishScamResult, timestamp: Long = System.currentTimeMillis()) {
        for ((category, matches) in result.matches) {
            for (match in matches) {
                // Approximate baseline category weights for graph progression
                val weight = getBaselineWeight(category)
                nodes.add(IntentNode(category, weight, match.value, timestamp))
            }
        }
    }
    
    private fun getBaselineWeight(category: String): Float {
        return when (category) {
            "OTP_HARVEST_HINGLISH" -> 0.9f
            "AUTHORITY_HINDI" -> 0.8f
            "REMOTE_ACCESS_HINGLISH" -> 0.85f
            "KYC_SCAM_HINGLISH" -> 0.75f
            "CUSTOMS_SCAM_HINGLISH" -> 0.7f
            "FINANCIAL_HINGLISH" -> 0.65f
            "URGENCY_HINGLISH" -> 0.6f
            else -> 0.5f
        }
    }

    /**
     * Evaluates sequence heuristics (e.g. Authority -> Urgency -> OTP).
     */
    fun calculateOverallThreatLevel(): Float {
        if (nodes.isEmpty()) return 0f
        
        var threatScore = 0f
        val seenCategories = mutableSetOf<String>()
        
        for (node in nodes) {
            if (seenCategories.add(node.category)) {
                threatScore += node.weight * 0.5f // Main contribution
            } else {
                threatScore += node.weight * 0.1f // Diminishing returns for repeated intents
            }
        }
        
        return kotlin.math.min(threatScore + evaluateKillChains(), 1.0f)
    }
    
    /**
     * Checks for established scam flow sequences.
     */
    private fun evaluateKillChains(): Float {
        val cats = nodes.map { it.category }
        var multiplier = 0f
        
        // 1. Fake Authority / Customs demanding Money
        if ((cats.contains("AUTHORITY_HINDI") || cats.contains("CUSTOMS_SCAM_HINGLISH")) && 
            cats.contains("FINANCIAL_HINGLISH")) {
            multiplier += 0.3f
        }
        
        // 2. High pressure urgency for OTP / Access
        if (cats.contains("URGENCY_HINGLISH") && 
            (cats.contains("OTP_HARVEST_HINGLISH") || cats.contains("REMOTE_ACCESS_HINGLISH"))) {
            multiplier += 0.4f
        }

        // 3. KYC block threat -> Remote Access/OTP
        if (cats.contains("KYC_SCAM_HINGLISH") && 
            (cats.contains("REMOTE_ACCESS_HINGLISH") || cats.contains("OTP_HARVEST_HINGLISH"))) {
            multiplier += 0.35f
        }
        
        return multiplier
    }
    
    fun clear() {
        nodes.clear()
    }
}
