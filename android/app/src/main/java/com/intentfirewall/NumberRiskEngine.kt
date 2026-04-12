package com.intentfirewall

import android.content.Context
import android.database.Cursor
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NumberRiskEngine {

    private val tag = "NumberRiskEngine"

    data class RiskResult(
        val score: Int,
        val reason: String,
        val isKnownContact: Boolean,
        val signals: List<String>
    )

    suspend fun evaluate(context: Context, rawNumber: String): RiskResult =
        withContext(Dispatchers.IO) {
            val signals = mutableListOf<String>()
            var score = 0

            val number = normalizeNumber(rawNumber)

            val isContact = isKnownContact(context, number)
            if (isContact) {
                Log.d(tag, "$number is a known contact - low risk")
                return@withContext RiskResult(
                    score = 5,
                    reason = "Known contact",
                    isKnownContact = true,
                    signals = listOf("known_contact")
                )
            }
            signals.add("unknown_number")
            score += 20

            if (number == "unknown" || number.isBlank()) {
                score += 30
                signals.add("hidden_number")
            }

            if (number.startsWith("+") && !number.startsWith("+91")) {
                score += 15
                signals.add("international_prefix")
            }

            if (number.length < 6 && number != "unknown") {
                score += 10
                signals.add("short_number_suspicious")
            }

            val recentCallCount = getRecentCallCount(context, number, windowMinutes = 60)
            if (recentCallCount >= 3) {
                score += 20
                signals.add("high_frequency_calls_${recentCallCount}x")
            }

            val isKnownSpam = SpamNumberCache.isKnownSpam(number)
            if (isKnownSpam) {
                score += 40
                signals.add("known_spam_db_hit")
            }

            score = score.coerceIn(0, 100)

            val reason = when {
                score >= 85 -> "High-risk: ${signals.joinToString(", ")}"
                score >= 55 -> "Suspicious: ${signals.joinToString(", ")}"
                else -> "Low risk (${signals.joinToString(", ")})"
            }

            RiskResult(
                score = score,
                reason = reason,
                isKnownContact = false,
                signals = signals
            )
        }

    private fun normalizeNumber(raw: String): String {
        if (raw.isBlank()) return "unknown"
        return raw.filter { it.isDigit() || it == '+' }
            .trim()
            .ifBlank { "unknown" }
    }

    private fun isKnownContact(context: Context, number: String): Boolean {
        if (number == "unknown" || number.isBlank()) return false
        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(number)
            )
            val cursor: Cursor? = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null,
                null,
                null
            )
            val found = (cursor?.count ?: 0) > 0
            cursor?.close()
            found
        } catch (e: Exception) {
            Log.e(tag, "Contact lookup failed", e)
            false
        }
    }

    private fun getRecentCallCount(context: Context, number: String, windowMinutes: Int): Int {
        if (number == "unknown") return 0
        return try {
            val since = System.currentTimeMillis() - (windowMinutes * 60 * 1000L)
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER),
                "${CallLog.Calls.NUMBER} = ? AND ${CallLog.Calls.DATE} > ?",
                arrayOf(number, since.toString()),
                null
            )
            val count = cursor?.count ?: 0
            cursor?.close()
            count
        } catch (_: Exception) {
            0
        }
    }
}
