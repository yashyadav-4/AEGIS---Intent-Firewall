package com.intentfirewall

import android.content.Context
import android.os.SystemClock
import kotlin.math.max

data class Tier3Result(
    val isScam: Boolean,
    val confidence: Float,
    val latencyMs: Long
)

class Tier3Classifier(@Suppress("UNUSED_PARAMETER") context: Context) {
    fun analyze(features: FloatArray): Tier3Result {
        val start = SystemClock.elapsedRealtimeNanos()

        if (features.isEmpty()) {
            return Tier3Result(isScam = false, confidence = 0.1f, latencyMs = 0L)
        }

        var sum = 0f
        for (value in features) {
            sum += value
        }
        val confidence = max(0f, (sum / features.size)).coerceIn(0f, 1f)
        val latencyMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000L
        return Tier3Result(isScam = confidence >= 0.6f, confidence = confidence, latencyMs = latencyMs)
    }
}