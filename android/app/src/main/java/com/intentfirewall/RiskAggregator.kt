package com.intentfirewall

import android.util.Log
import java.util.LinkedList

class RiskAggregator(
    private val onAlert: (score: Int, reason: String) -> Unit,
    private val onUpdate: (score: Int) -> Unit
) {

    private val tag = "RiskAggregator"
    private val geminiWindow = LinkedList<GeminiEntry>()
    private val windowMaxSize = 6
    private val alertThresholdScore = 65
    private val alertThresholdCount = 2
    private val immediateAlertScore = 80

    private var lastAlertTime = 0L
    private val alertCooldownMs = 60_000L

    data class GeminiEntry(
        val score: Int,
        val intent: String,
        val reason: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun addGeminiScore(score: Int, intent: String, reason: String) {
        geminiWindow.add(GeminiEntry(score, intent, reason))
        if (geminiWindow.size > windowMaxSize) geminiWindow.poll()

        val combined = computeCombinedScore()
        Log.d(tag, "AGGREGATOR: score=$score intent=$intent windowSize=${geminiWindow.size} combined=$combined")
        onUpdate(combined)

        Log.d(tag, "Score added: $score ($intent) - combined: $combined - window: ${geminiWindow.size}")
        checkAlertCondition(combined, score, reason)
    }

    private fun computeCombinedScore(): Int {
        if (geminiWindow.isEmpty()) return 0

        var weightedSum = 0.0
        var totalWeight = 0.0
        geminiWindow.forEachIndexed { index, entry ->
            val weight = (index + 1).toDouble()
            weightedSum += entry.score * weight
            totalWeight += weight
        }
        return (weightedSum / totalWeight).toInt().coerceIn(0, 100)
    }

    private fun checkAlertCondition(combinedScore: Int, latestScore: Int, latestReason: String) {
        val now = System.currentTimeMillis()
        if (now - lastAlertTime < alertCooldownMs) return

        if (latestScore >= immediateAlertScore) {
            lastAlertTime = now
            Log.w(tag, "ALERT triggered immediately: latestScore=$latestScore reason=$latestReason")
            onAlert(latestScore, latestReason)
            return
        }

        val highScoreCount = geminiWindow.count { it.score >= alertThresholdScore }

        if (combinedScore >= alertThresholdScore && highScoreCount >= alertThresholdCount) {
            lastAlertTime = now
            val topReason = geminiWindow
                .filter { it.score >= alertThresholdScore }
                .maxByOrNull { it.score }
                ?.reason ?: latestReason
            Log.w(tag, "ALERT triggered: score=$combinedScore, highCount=$highScoreCount, reason=$topReason")
            onAlert(combinedScore, topReason)
        }
    }
}
