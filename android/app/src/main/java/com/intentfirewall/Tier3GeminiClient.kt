package com.intentfirewall

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class Tier3GeminiResult(
    val classification: String,
    val confidence: String,
    val category: String,
    val evidence: String,
    val confidenceScore: Int,
    val reasoning: String,
)

/** Gemini Tier 3 client with strict prompt, timeout, retry, and JSON parsing. */
object Tier3GeminiClient : Tier3GeminiClientPort {
    private const val TAG = "SCAM_Tier3GeminiClient"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val RETRY_DELAY_MS = 600L

    /** Analyze text using Gemini with one retry and strict JSON parsing. */
    override suspend fun analyze(text: String, context: List<String>, tier1: Tier1Result): Tier3GeminiResult = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY.trim().ifBlank {
            BuildConfig.GEMINI_API_KEYS.split(',').map { it.trim() }.firstOrNull { it.isNotBlank() } ?: ""
        }
        require(apiKey.isNotBlank()) { "Gemini API key is missing" }

        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
        val prompt = buildPrompt(text, context, tier1)

        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                return@withContext call(endpoint, prompt)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "analyze attempt=${attempt + 1} failed: ${e.message}")
                if (attempt == 0) delay(RETRY_DELAY_MS)
            }
        }

        throw IllegalStateException("Tier3 failed after retry", lastError)
    }

    private fun call(endpoint: String, prompt: String): Tier3GeminiResult {
        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put("text", prompt)
                        )
                    )
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.1)
                    put("maxOutputTokens", 300)
                }
            )
        }

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            doInput = true
            setRequestProperty("Content-Type", "application/json")
        }

        return try {
            OutputStreamWriter(connection.outputStream).use { it.write(payload.toString()) }
            val code = connection.responseCode
            val body = readBody(connection, code)
            if (code !in 200..299) {
                throw IllegalStateException("Tier3 HTTP $code: $body")
            }
            parse(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(body: String): Tier3GeminiResult {
        val root = JSONObject(body)
        val text = root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text", "")
            ?.trim()
            .orEmpty()

        require(text.isNotBlank()) { "Tier3 empty output" }

        val cleaned = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = JSONObject(cleaned)

        return Tier3GeminiResult(
            classification = obj.optString("classification", "UNCERTAIN").uppercase(),
            confidence = obj.optString("confidence", "LOW").uppercase(),
            category = obj.optString("category", "UNCERTAIN").uppercase(),
            evidence = obj.optString("evidence", ""),
            confidenceScore = obj.optInt("confidence_score", 50).coerceIn(0, 100),
            reasoning = obj.optString("reasoning", ""),
        )
    }

    private fun readBody(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        if (stream == null) return ""
        return BufferedReader(stream.reader()).use { br ->
            val sb = StringBuilder()
            var line = br.readLine()
            while (line != null) {
                sb.append(line)
                line = br.readLine()
            }
            sb.toString()
        }
    }

    private fun buildPrompt(text: String, context: List<String>, tier1: Tier1Result): String {
        val ctx = context.takeLast(3).joinToString("\n").ifBlank { "[empty]" }
        val matched = tier1.matchedKeywords.joinToString(", ").ifBlank { "none" }

        return """
You are a scam detection engine for India. Analyze the message below.
Return ONLY a JSON object - no explanation, no markdown, no preamble.

Context (last 3 messages, may be empty):
$ctx

Current message:
$text

App source: Notification
Tier 1 score: ${tier1.score} / 100
Tier 1 matched: $matched

Scam categories to check:
OTP_SCAM, KYC_SCAM, LOTTERY_SCAM, JOB_SCAM, LOAN_SCAM,
INVESTMENT_SCAM, IMPERSONATION_SCAM, ROMANCE_SCAM,
TECH_SUPPORT_SCAM, COURIER_SCAM, UTILITY_SCAM

Rules:
1. Classify CURRENT MESSAGE only. Context is reference only.
2. Never assign OTP_SCAM without OTP/code evidence in current message.
3. UNCERTAIN is better than a forced wrong label.
4. Consider Hinglish, transliteration, slang, bad spelling.
5. Consider Tier 1 score as a prior - if Tier 1 is 70+ and you are uncertain, lean toward SCAM not SAFE.
6. Short messages with unclear intent = UNCERTAIN.

Return exactly this JSON:
{
  "classification": "SCAM | SAFE | UNCERTAIN",
  "confidence": "HIGH | MEDIUM | LOW",
  "category": "OTP_SCAM | KYC_SCAM | LOTTERY_SCAM | JOB_SCAM | LOAN_SCAM | INVESTMENT_SCAM | IMPERSONATION_SCAM | ROMANCE_SCAM | TECH_SUPPORT_SCAM | COURIER_SCAM | UTILITY_SCAM | SAFE | UNCERTAIN",
  "evidence": "exact phrase from current message that triggered this",
  "confidence_score": 0-100,
  "reasoning": "one sentence max"
}
""".trimIndent()
    }
}
