package com.intentfirewall

import android.content.Context
import android.provider.ContactsContract
import android.net.Uri
import android.util.Log

object IntentScorer {

    fun isCallerKnown(context: Context, number: String): Boolean {
        if (number.isBlank()) return false
        try {
            if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return false
            }
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val cursor = context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            var known = false
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    known = true
                }
                cursor.close()
            }
            return known
        } catch (e: Exception) {
            Log.e("IntentScorer", "Failed to check contacts", e)
            return false
        }
    }


    data class TurnContext(
        val role: String,     // "CALLER" or "CALLEE"
        val text: String,
        val timestamp: Long,
        val intents: List<String>
    )

    class ConversationBuffer(private val maxTurns: Int = 10) {
        private val turns = mutableListOf<TurnContext>()

        fun addTurn(turn: TurnContext) {
            turns.add(turn)
            if (turns.size > maxTurns) {
                turns.removeAt(0)
            }
        }

        fun getRecentIntents(timeWindowMs: Long): Set<String> {
            val now = System.currentTimeMillis()
            return turns.filter { now - it.timestamp <= timeWindowMs }
                .flatMap { it.intents }
                .toSet()
        }
        
        fun clear() {
            turns.clear()
        }
    }

    fun calculateThreatIndex(
        currentIntents: List<String>,
        history: ConversationBuffer,
        isCallerKnown: Boolean, // From Contacts provider
        deepfakeProb: Float
    ): Float {
        // Base score from current intent
        var score = 0.0f
        
        val recentIntents = history.getRecentIntents(30_000) // Last 30 seconds
        val allIntents = currentIntents.toSet() + recentIntents

        // 1. Intent Combinations (Non-linear scaling)
        if ("OTP_HARVEST_HINGLISH" in allIntents || "REMOTE_ACCESS_HINGLISH" in allIntents || "OTP_HARVEST" in allIntents) {
            score += 0.6f
        }
        if ("AUTHORITY_HINDI" in allIntents || "IMPERSONATION" in allIntents || "ACCOUNT_SCARE" in allIntents) {
            score += 0.4f
        }
        if ("URGENCY_HINGLISH" in allIntents || "FINANCIAL_PRESSURE" in allIntents || "FINANCIAL_HINGLISH" in allIntents) {
            score += 0.3f
        }

        // Synergy: Authority + Urgency + OTP = 100% scam
        if (("AUTHORITY_HINDI" in allIntents || "IMPERSONATION" in allIntents) && 
            ("URGENCY_HINGLISH" in allIntents) && 
            ("OTP_HARVEST_HINGLISH" in allIntents || "FINANCIAL_HINGLISH" in allIntents)) {
            score += 0.5f 
        }

        // 2. Caller Context Multiplier
        if (isCallerKnown) {
            // If it's your dad asking for an OTP, severely reduce intent score
            score *= 0.1f 
        }

        // 3. Deepfake Fusion
        // If voice is synthetic, intent score threshold drops dramatically
        if (deepfakeProb > 0.6f) {
            score += 0.4f
        } else if (deepfakeProb > 0.8f && score > 0.2f) {
            // Highly synthetic + ANY sketchy intent = insta-block
            score += 0.5f
        }

        return score.coerceIn(0.0f, 1.0f)
    }
}
