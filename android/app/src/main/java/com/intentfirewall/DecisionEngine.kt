package com.intentfirewall

import android.content.Context
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

object DecisionEngine {
    private const val TAG = "SCAM_DecisionEngine"
    private const val WINDOW_SIZE = 5

    enum class EngineAction {
        ALERT_IMMEDIATE,
        MONITOR,
        BUFFER,
        RESET,
    }

    private val windows = ConcurrentHashMap<String, ArrayDeque<GeminiQueueResult>>()

    /**
     * Evaluates Gemini output in a rolling per-package window.
     */
    fun evaluate(context: Context, payload: AnalysisPayload, result: GeminiQueueResult): EngineAction {
        return try {
            DecisionTraceLogger.initialize(context)
            val window = windows.getOrPut(payload.packageName) { ArrayDeque() }
            if (window.size >= WINDOW_SIZE) {
                window.removeFirst()
            }
            window.addLast(result)

            val scamCount = window.count { it.classification.equals("SCAM", ignoreCase = true) }
            val action = when {
                result.classification.equals("SCAM", ignoreCase = true) &&
                    result.confidence.equals("HIGH", ignoreCase = true) -> EngineAction.ALERT_IMMEDIATE
                scamCount >= 2 -> EngineAction.ALERT_IMMEDIATE
                scamCount == 1 -> EngineAction.MONITOR
                result.classification.equals("UNCERTAIN", ignoreCase = true) -> EngineAction.BUFFER
                result.classification.equals("SAFE", ignoreCase = true) -> {
                    window.clear()
                    EngineAction.RESET
                }
                else -> EngineAction.BUFFER
            }

            if (action == EngineAction.ALERT_IMMEDIATE) {
                showOverlay(context, payload, result)
            }

            DecisionTraceLogger.logDecisionTrace(
                packageName = payload.packageName,
                currentMessage = payload.currentMessage,
                classification = result.classification,
                confidence = result.confidence,
                category = result.category,
                evidence = result.evidence,
                action = action.name,
            )
            action
        } catch (e: Exception) {
            Log.e(TAG, "evaluate failed", e)
            EngineAction.BUFFER
        }
    }

    private fun showOverlay(context: Context, payload: AnalysisPayload, result: GeminiQueueResult) {
        AlertManager.showAlert(
            context = context,
            appName = payload.packageName,
            categoryDisplayName = result.category.ifBlank { "SCAM" },
            evidencePhrases = listOf(result.evidence.ifBlank { payload.currentMessage.take(80) }),
            confidenceScore = when (result.confidence.uppercase()) {
                "HIGH" -> 90
                "MEDIUM" -> 70
                else -> 50
            },
            tier = 3,
            tier3Used = true,
        )
    }
}
