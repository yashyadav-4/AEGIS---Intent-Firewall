package com.intentfirewall

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

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
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val RETRY_DELAY_MS = 600L
    private val keyRoundRobinCursor = AtomicInteger(0)
    private val requestMutex = Mutex()

    /** Analyze text using Gemini with one retry and strict JSON parsing. */
    override suspend fun analyze(text: String, context: List<String>, tier1: Tier1Result): Tier3GeminiResult = withContext(Dispatchers.IO) {
        requestMutex.withLock {
            val apiKeys = buildApiKeyCandidates(BuildConfig.GEMINI_API_KEY, BuildConfig.GEMINI_API_KEYS)
            require(apiKeys.isNotEmpty()) { "Gemini API key is missing" }

            val prompt = buildPrompt(text, context, tier1)
            val modelCandidates = buildModelCandidates(BuildConfig.GEMINI_MODEL)
            val keyAttempts = buildRoundRobinKeyAttempts(apiKeys)
            if (keyAttempts.isNotEmpty()) {
                Log.i(
                    TAG,
                    "roundRobin requestStartKeyIndex=${keyAttempts.first().first} totalKeys=${keyAttempts.size} model=${modelCandidates.firstOrNull().orEmpty()}"
                )
            }

            var lastError: Exception? = null
            keyAttempts.forEach { (keyIndex, apiKey) ->
                modelCandidates.forEach { model ->
                    val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                    repeat(2) { attempt ->
                        try {
                            Log.i(
                                "IntentFirewall",
                                "TIER3_REQUEST keyIndex=$keyIndex model=$model attempt=${attempt + 1} text=$text"
                            )
                            val result = call(endpoint, prompt)
                            Log.i(TAG, "analyze success keyIndex=$keyIndex model=$model")
                            Log.i(
                                "IntentFirewall",
                                "TIER3_REPLY keyIndex=$keyIndex model=$model class=${result.classification} confidence=${result.confidence} category=${result.category} score=${result.confidenceScore} evidence=${result.evidence} reasoning=${result.reasoning}"
                            )
                            return@withContext result
                        } catch (e: Exception) {
                            lastError = e
                            Log.w(
                                TAG,
                                "analyze keyIndex=$keyIndex keySuffix=${keySuffix(apiKey)} model=$model attempt=${attempt + 1} failed: ${e.message}",
                            )
                            Log.w(
                                "IntentFirewall",
                                "TIER3_ERROR keyIndex=$keyIndex model=$model attempt=${attempt + 1} error=${e.message}"
                            )
                            if (attempt == 0) delay(RETRY_DELAY_MS)
                        }
                    }
                }
            }

            throw IllegalStateException("Tier3 failed after retry", lastError)
        }
    }

    private fun buildApiKeyCandidates(primary: String, csvKeys: String): List<String> {
        val keys = linkedSetOf<String>()
        val normalizedPrimary = primary.trim()
        if (normalizedPrimary.isNotBlank()) keys.add(normalizedPrimary)
        csvKeys.split(',').map { it.trim() }.filter { it.isNotBlank() }.forEach { keys.add(it) }
        return keys.toList()
    }

    private fun buildModelCandidates(configuredModel: String): List<String> {
        return listOf("gemma-4-31b-it")
    }

    private fun buildRoundRobinKeyAttempts(apiKeys: List<String>): List<Pair<Int, String>> {
        if (apiKeys.isEmpty()) return emptyList()
        val start = Math.floorMod(keyRoundRobinCursor.getAndIncrement(), apiKeys.size)
        return (0 until apiKeys.size).map { offset ->
            val idx = (start + offset) % apiKeys.size
            (idx + 1) to apiKeys[idx]
        }
    }

    private fun keySuffix(key: String): String {
        val trimmed = key.trim()
        return if (trimmed.length <= 6) trimmed else trimmed.takeLast(6)
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
                    put("responseMimeType", "application/json")
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
        val obj = extractJsonObject(cleaned)
        if (obj == null) {
            Log.w("IntentFirewall", "TIER3_NON_JSON raw=$cleaned")
            return fallbackFromText(cleaned)
        }

        return Tier3GeminiResult(
            classification = obj.optString("classification", "UNCERTAIN").uppercase(),
            confidence = obj.optString("confidence", "LOW").uppercase(),
            category = obj.optString("category", "UNCERTAIN").uppercase(),
            evidence = obj.optString("evidence", ""),
            confidenceScore = obj.optInt("confidence_score", 50).coerceIn(0, 100),
            reasoning = obj.optString("reasoning", ""),
        )
    }

    private fun extractJsonObject(text: String): JSONObject? {
        try {
            return JSONObject(text)
        } catch (_: Exception) {
        }

        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val candidate = text.substring(start, end + 1)
            try {
                return JSONObject(candidate)
            } catch (_: Exception) {
            }
        }

        return null
    }

    private fun fallbackFromText(raw: String): Tier3GeminiResult {
        val normalized = raw.lowercase()

        val classification = when {
            listOf("scam", "fraud", "phishing", "otp", "extortion", "threat", "blackmail").any { normalized.contains(it) } -> "SCAM"
            listOf("safe", "harmless", "benign").any { normalized.contains(it) } -> "SAFE"
            else -> "UNCERTAIN"
        }

        val confidence = when {
            listOf("high confidence", "certain", "definitely").any { normalized.contains(it) } -> "HIGH"
            listOf("medium", "likely", "probably").any { normalized.contains(it) } -> "MEDIUM"
            else -> "LOW"
        }

        val score = when (confidence) {
            "HIGH" -> if (classification == "SAFE") 85 else 82
            "MEDIUM" -> if (classification == "SAFE") 68 else 66
            else -> if (classification == "SAFE") 35 else 45
        }

        val category = when (classification) {
            "SAFE" -> "SAFE"
            "SCAM" -> "SCAM"
            else -> "UNCERTAIN"
        }

        return Tier3GeminiResult(
            classification = classification,
            confidence = confidence,
            category = category,
            evidence = raw.take(220),
            confidenceScore = score,
            reasoning = "Model returned non-JSON output; fallback parser applied.",
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
You are a smart fraud and harm detector for India chat messages.
Act like a careful human reviewer: focus on scam intent, coercion, impersonation, extortion, harassment, and user harm.
Do not overflag normal messages.
If evidence is weak, return UNCERTAIN.
Return ONLY valid JSON (no markdown).

Recent context (reference only):
$ctx

Current message to judge:
$text

Tier1 hint score: ${tier1.score}
Tier1 matched hints: $matched

Use these categories when relevant:
OTP_SCAM, KYC_SCAM, LOTTERY_SCAM, JOB_SCAM, LOAN_SCAM, INVESTMENT_SCAM,
IMPERSONATION_SCAM, ROMANCE_SCAM, TECH_SUPPORT_SCAM, COURIER_SCAM, UTILITY_SCAM,
FAKE_IDENTITY, HARASSMENT, EXTORTION, CRUELTY, SAFE, UNCERTAIN.

Confidence scoring rules (important):
- confidence_score must match your real certainty on THIS message.
- Use this scale:
    0-20: almost certainly SAFE
    21-40: likely SAFE but some weak suspicion
    41-59: mixed or ambiguous evidence
    60-79: likely scam/malicious with meaningful evidence
    80-100: very strong direct evidence of scam/malicious intent
- If confidence is HIGH, confidence_score should usually be >= 80.
- If confidence is MEDIUM, confidence_score should usually be 60-79.
- If confidence is LOW, confidence_score should usually be <= 59.
- Never output 0 unless message is clearly harmless.
- Base evidence on exact phrases from current message, not assumptions.

Return this JSON schema exactly:
{
    "classification": "SCAM | MALICIOUS | SAFE | UNCERTAIN | FAKE_IDENTITY | HARASSMENT | EXTORTION | CRUELTY",
  "confidence": "HIGH | MEDIUM | LOW",
    "category": "OTP_SCAM | KYC_SCAM | LOTTERY_SCAM | JOB_SCAM | LOAN_SCAM | INVESTMENT_SCAM | IMPERSONATION_SCAM | ROMANCE_SCAM | TECH_SUPPORT_SCAM | COURIER_SCAM | UTILITY_SCAM | FAKE_IDENTITY | HARASSMENT | EXTORTION | CRUELTY | SAFE | UNCERTAIN",
  "evidence": "exact phrase from current message that triggered this",
  "confidence_score": 0-100,
  "reasoning": "one sentence max"
}
""".trimIndent()
    }
}
