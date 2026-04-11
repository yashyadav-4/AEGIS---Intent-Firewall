package com.intentfirewall

import android.content.Context
import android.util.Log

interface Tier1EnginePort {
    fun analyze(text: String, context: List<String> = emptyList()): Tier1Result
}

interface Tier3GeminiClientPort {
    suspend fun analyze(text: String, context: List<String>, tier1: Tier1Result): Tier3GeminiResult
}

enum class Decision { ALERT, SAFE, UNCERTAIN }

data class PipelineResult(
    val decision: Decision,
    val tier: Int,
    val category: String?,
    val confidenceScore: Int,
    val evidence: List<String>,
    val usedTier3: Boolean,
)

/** Two-tier orchestration: NoiseGate -> Tier1 -> optional Tier3 -> mapped final decision. */
class ScamPipeline(
    context: Context? = null,
    private val tier1Engine: Tier1EnginePort = Tier1Engine,
    private val tier3GeminiClient: Tier3GeminiClientPort = Tier3GeminiClient,
) {
    private val TAG = "SCAM_ScamPipeline"
    private var appContext: Context? = context?.applicationContext

    /** Initialize pipeline with application context for notification and logging components. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        ProcessingNotifManager.initialize(appContext ?: context)
        DecisionTraceLogger.initialize(appContext ?: context)
    }

    /** Process one incoming text through deterministic Tier1 and selective Tier3 escalation. */
    suspend fun process(
        text: String,
        packageName: String = "unknown",
        context: List<String> = emptyList(),
    ): PipelineResult {
        if (NoiseGate.shouldDrop(text)) {
            val result = PipelineResult(Decision.SAFE, tier = 1, category = null, confidenceScore = 0, evidence = emptyList(), usedTier3 = false)
            DecisionTraceLogger.log(result, text, packageName)
            return result
        }

        val t1 = tier1Engine.analyze(text, context)

        return when (t1.tier) {
            ScoreTier.HIGH -> {
                val result = PipelineResult(
                    decision = Decision.ALERT,
                    tier = 1,
                    category = t1.category?.id,
                    confidenceScore = t1.score,
                    evidence = t1.evidencePhrases,
                    usedTier3 = false,
                )
                DecisionTraceLogger.log(result, text, packageName)
                result
            }
            ScoreTier.MEDIUM -> {
                val result = try {
                    ProcessingNotifManager.showProcessing(packageName, text.take(40))
                    val t3 = try {
                        tier3GeminiClient.analyze(text, context, t1)
                    } catch (e: Exception) {
                        Log.w(TAG, "Tier3 failed for MEDIUM: ${e.message}")
                        null
                    }

                    if (t3 == null) {
                        PipelineResult(
                            decision = Decision.UNCERTAIN,
                            tier = 1,
                            category = t1.category?.id,
                            confidenceScore = t1.score,
                            evidence = t1.evidencePhrases,
                            usedTier3 = true,
                        )
                    } else {
                        mapTier3ToResult(t3, usedTier3 = true)
                    }
                } finally {
                    ProcessingNotifManager.dismissProcessing()
                }

                DecisionTraceLogger.log(result, text, packageName)
                result
            }
            ScoreTier.LOW -> {
                val t3 = try {
                    tier3GeminiClient.analyze(text, context, t1)
                } catch (e: Exception) {
                    Log.w(TAG, "Tier3 failed for LOW: ${e.message}")
                    null
                }

                val result = if (t3 == null) {
                    PipelineResult(
                        decision = Decision.SAFE,
                        tier = 1,
                        category = null,
                        confidenceScore = t1.score,
                        evidence = emptyList(),
                        usedTier3 = true,
                    )
                } else {
                    mapTier3ToResult(t3, usedTier3 = true)
                }

                DecisionTraceLogger.log(result, text, packageName)
                result
            }
            ScoreTier.SAFE -> {
                val result = PipelineResult(
                    decision = Decision.SAFE,
                    tier = 1,
                    category = null,
                    confidenceScore = t1.score,
                    evidence = emptyList(),
                    usedTier3 = false,
                )
                DecisionTraceLogger.log(result, text, packageName)
                result
            }
        }
    }

    companion object {
        @Volatile
        private var defaultPipeline: ScamPipeline = ScamPipeline()

        fun initialize(context: Context) {
            defaultPipeline = ScamPipeline(context).also { it.initialize(context) }
        }

        suspend fun process(
            text: String,
            packageName: String = "unknown",
            context: List<String> = emptyList(),
        ): PipelineResult = defaultPipeline.process(text, packageName, context)
    }

    private fun mapTier3ToResult(t3: Tier3GeminiResult, usedTier3: Boolean): PipelineResult {
        val decision = when {
            t3.classification == "SCAM" && t3.confidence in listOf("HIGH", "MEDIUM") -> Decision.ALERT
            t3.classification == "SCAM" && t3.confidence == "LOW" -> Decision.UNCERTAIN
            t3.classification == "UNCERTAIN" -> Decision.UNCERTAIN
            else -> Decision.SAFE
        }
        return PipelineResult(
            decision = decision,
            tier = 3,
            category = t3.category,
            confidenceScore = t3.confidenceScore,
            evidence = listOf(t3.evidence).filter { it.isNotBlank() },
            usedTier3 = usedTier3,
        )
    }
}
