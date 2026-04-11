package com.intentfirewall

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class GeminiDecision(
    val isScam: Boolean,
    val confidence: Float,
    val reason: String,
    val threatType: String?,
    val action: String,
    val keyIndex: Int,
    val model: String,
)

object GeminiScamClassifier {
    private const val TAG = "IntentFirewall"
    private const val PREF_NAME = "intentfirewall_gemini"
    private const val KEY_QUEUE = "pending_queue"
    private const val KEY_CURSOR = "api_key_cursor"
    private const val MAX_QUEUE = 200

    private data class PendingItem(
        val message: String,
        val sender: String,
        val appSource: String,
        val timestamp: Long,
    )

    fun analyzeMessage(
        context: Context,
        message: String,
        sender: String,
        appSource: String,
        timestamp: Long,
        captureMethod: String,
        recentMessages: List<String>,
        senderKnown: Boolean,
    ): GeminiDecision? {
        val normalizedMessage = message.trim()
        if (normalizedMessage.isEmpty()) return null

        if (!isOnline(context)) {
            enqueue(context, PendingItem(normalizedMessage, sender, appSource, timestamp))
            Log.w(TAG, "[Tier3] Offline: queued message for analysis")
            return null
        }

        val keys = getApiKeys()
        if (keys.isEmpty()) {
            Log.w(TAG, "[Tier3] No API keys configured")
            return null
        }

        val model = getPreferredModel()
        val prompt = buildPrompt(
            message = normalizedMessage,
            sender = sender,
            appSource = appSource,
            timestamp = timestamp,
            captureMethod = captureMethod,
            recentMessages = recentMessages,
            senderKnown = senderKnown,
        )
        val startIndex = getStartIndex(context, keys.size)

        for (offset in keys.indices) {
            val idx = (startIndex + offset) % keys.size
            val key = keys[idx]
            try {
                val decision = callGemini(model, key, prompt)
                Log.i(TAG, "[Tier3] success keyIndex=${idx + 1}")
                setStartIndex(context, idx + 1, keys.size)
                return decision.copy(keyIndex = idx + 1, model = model)
            } catch (e: GeminiRetryableException) {
                Log.w(TAG, "[Tier3] retryable failure keyIndex=${idx + 1}: ${e.message}")
                Thread.sleep(1000)
            } catch (e: Exception) {
                Log.e(TAG, "[Tier3] failure keyIndex=${idx + 1}: ${e.message}")
            }
        }

        // Move cursor forward so next request does not always hammer the same key first.
        setStartIndex(context, startIndex + 1, keys.size)

        enqueue(context, PendingItem(normalizedMessage, sender, appSource, timestamp))
        Log.w(TAG, "[Tier3] All keys failed: queued message for later")
        return null
    }

    private fun callGemini(model: String, apiKey: String, prompt: String): GeminiDecision {
        val endpoint =
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.1)
                    put("responseMimeType", "application/json")
                }
            )
        }

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }

        return try {
            OutputStreamWriter(connection.outputStream).use { it.write(payload.toString()) }

            val code = connection.responseCode
            val body = readBody(connection, code)

            if (code == 429 || code == 503) {
                throw GeminiRetryableException("HTTP $code")
            }
            if (code !in 200..299) {
                if (body.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                    body.contains("rate", ignoreCase = true)
                ) {
                    throw GeminiRetryableException("$code $body")
                }
                throw IllegalStateException("Tier3 HTTP $code: $body")
            }

            parseGeminiDecision(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseGeminiDecision(responseBody: String): GeminiDecision {
        val root = JSONObject(responseBody)
        val candidates = root.optJSONArray("candidates")
        val first = candidates?.optJSONObject(0)
            ?: throw IllegalStateException("Tier3 empty candidates")
        val content = first.optJSONObject("content")
            ?: throw IllegalStateException("Tier3 missing content")
        val parts = content.optJSONArray("parts")
        val text = parts?.optJSONObject(0)?.optString("text", "")?.trim().orEmpty()
        if (text.isEmpty()) {
            throw IllegalStateException("Tier3 empty text output")
        }

        val cleaned = text
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val obj = JSONObject(cleaned)

        // New strict schema support:
        // {
        //   "classification": "SAFE|SCAM|UNCERTAIN",
        //   "confidence": "HIGH|MEDIUM|LOW",
        //   "category": "...",
        //   "evidence": "...",
        //   "context_used": true|false,
        //   "reasoning": "...",
        //   "hard_rule_triggered": "..."
        // }
        val classificationRaw = obj.optString("classification", "").trim().uppercase()
        val confidenceRaw = obj.optString("confidence", "").trim().uppercase()
        val categoryRaw = obj.optString("category", "").trim()
        val evidenceRaw = obj.optString("evidence", "").trim()
        val reasoningRaw = obj.optString("reasoning", "").trim()

        val hasNewSchema = classificationRaw.isNotBlank() || categoryRaw.isNotBlank() || confidenceRaw.isNotBlank()

        val isScam: Boolean
        val confidence: Float
        val threatType: String?
        val action: String
        val reason: String

        if (hasNewSchema) {
            isScam = classificationRaw == "SCAM"
            confidence = when (confidenceRaw) {
                "HIGH" -> 0.92f
                "MEDIUM" -> 0.72f
                "LOW" -> 0.45f
                else -> if (isScam) 0.70f else 0.25f
            }.coerceIn(0f, 1f)

            threatType = when {
                categoryRaw.isBlank() || categoryRaw.equals("null", ignoreCase = true) -> null
                else -> categoryRaw
            }

            action = if (classificationRaw == "SCAM") "alert" else "safe"
            reason = listOf(evidenceRaw, reasoningRaw)
                .filter { it.isNotBlank() }
                .joinToString(" | ")
                .ifBlank { "Tier3 analysis" }
        } else {
            // Backward compatibility with prior schema.
            val legacyScam = obj.optBoolean("isScam", false)
            isScam = legacyScam
            confidence = obj.optDouble("confidence", if (legacyScam) 0.75 else 0.20)
                .toFloat()
                .coerceIn(0f, 1f)
            reason = obj.optString("reason", "Tier3 analysis")
            val rawThreatType = obj.optString("threatType", "")
            threatType = if (rawThreatType.equals("null", ignoreCase = true) || rawThreatType.isBlank()) {
                null
            } else {
                rawThreatType
            }
            action = obj.optString("action", if (legacyScam) "alert" else "safe")
                .lowercase()
                .let { if (it == "alert" || it == "safe") it else if (legacyScam) "alert" else "safe" }
        }

        return GeminiDecision(
            isScam = isScam,
            confidence = confidence,
            reason = reason,
            threatType = threatType,
            action = action,
            keyIndex = -1,
            model = "",
        )
    }

    private fun buildPrompt(
        message: String,
        sender: String,
        appSource: String,
        timestamp: Long,
        captureMethod: String,
        recentMessages: List<String>,
        senderKnown: Boolean,
    ): String {
                val contextWindow = recentMessages
                        .takeLast(5)
                        .joinToString("\n")
                        .ifBlank { "[empty]" }

                val senderType = when {
                        senderKnown -> "KNOWN_CONTACT"
                        sender.contains("bank", ignoreCase = true) || sender.contains("support", ignoreCase = true) || sender.contains("service", ignoreCase = true) -> "BUSINESS"
                        else -> "UNKNOWN"
                }

                return """
You are a scam detection engine for real-time SMS and chat analysis in India.
Your job is to classify the CURRENT MESSAGE only, using recent context only
to resolve ambiguity — not to inherit risk from it.

---

## INPUT FORMAT

<context>
$contextWindow
</context>

<current_message>
$message
</current_message>

<sender_type>
$senderType
</sender_type>

---

## CLASSIFICATION RULES

### STEP 1 — HARD SAFE (check first, exit immediately if matched)

Return SAFE with no further analysis if current message matches ANY of:
- Pure acknowledgement: ok, okay, hlo, hello, yes, no, noted, hmm, thanks,
    thank you, done, sure, fine, received
- Call status strings: calling, on a call, open chat, read more,
    missed call, declined
- Single emoji or punctuation only
- Purely personal/social: how are you, good morning, where are you,
    what are you doing

These are NEVER scam regardless of context.

### STEP 2 — HARD SCAM (check second, exit immediately if matched)

Return SCAM with high confidence if current message contains ALL of:

Pattern A — Authority + Verification ask:
    - Claims to be: bank, RBI, government, police, court, TRAI, telecom
    - AND asks for: OTP, PIN, password, account number, Aadhaar, PAN

Pattern B — Urgency + Money ask:
    - Urgency signal: urgent, immediately, today only, last chance,
        account blocked, legal action, arrested
    - AND money ask: send money, pay now, transfer, recharge,
        wallet, UPI, NEFT, RTGS

Pattern C — Friend-in-distress:
    - Identity claim from unknown number: I am [name], this is my new number
    - AND money or help request in same or next message

### STEP 3 — EVIDENCE-BOUND CATEGORY ASSIGNMENT

Only assign a category if direct evidence exists in the CURRENT MESSAGE.
Do not infer category from context alone.

| Category       | Required evidence in current message                        |
|----------------|--------------------------------------------------------------|
| OTP_SCAM       | OTP / one-time password / verification code explicitly present |
| KYC_SCAM       | KYC / document update / account expire / verify account      |
| LOTTERY_SCAM   | Won / prize / reward / lucky draw / claim now                |
| JOB_SCAM       | Work from home / part time / earn daily / task complete      |
| LOAN_SCAM      | Instant loan / pre-approved / low interest / apply now       |
| IMPERSONATION  | Claims false identity + request                              |
| INVESTMENT_SCAM| Returns / profit / trading / doubling money                  |
| GENERIC_SCAM   | Scam signals present but no specific category fits          |
| SAFE           | No scam signals or hard-safe match                          |

### STEP 4 — HINGLISH AND TRANSLITERATION HANDLING

Treat these as equivalent:
- OTP = otp = O.T.P. = "woh code" = "verification wala number"
- Paisa = paise = money = rupee = Rs = ₹ = amount bhejna
- Urgent = jaldi = abhi = turant = "kal tak"
- Bank = baink = "aapka account"

Do not miss scam signals because of spelling variation or transliteration.

### STEP 5 — AMBIGUITY RULE

If you cannot assign SCAM or SAFE with clear evidence:
- Return UNCERTAIN
- Do not force a category
- State what evidence is missing

---

## OUTPUT FORMAT (strict JSON only)

{
    "classification": "SAFE | SCAM | UNCERTAIN",
    "confidence": "HIGH | MEDIUM | LOW",
    "category": "OTP_SCAM | KYC_SCAM | LOTTERY_SCAM | JOB_SCAM | LOAN_SCAM | IMPERSONATION | INVESTMENT_SCAM | GENERIC_SCAM | SAFE | UNCERTAIN",
    "evidence": "Exact phrase(s) from current message that triggered this classification. EMPTY if SAFE.",
    "context_used": true | false,
    "reasoning": "One sentence max. Why this classification was chosen.",
    "hard_rule_triggered": "HARD_SAFE | HARD_SCAM | NONE"
}

---

## STRICT CONSTRAINTS

- Classify CURRENT MESSAGE only. Context is reference, not subject.
- Never assign OTP_SCAM unless OTP evidence exists in current message.
- Never classify UI/system strings as scam.
- UNCERTAIN is always better than a forced wrong label.
- Return JSON only. No explanation outside JSON.
- Evidence field must quote directly from current message, never paraphrased.
""".trimIndent()
    }

    private fun getApiKeys(): List<String> {
        val keysRaw = BuildConfig.GEMINI_API_KEYS
        val fromList = keysRaw
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (fromList.isNotEmpty()) return fromList

        val fallback = BuildConfig.GEMINI_API_KEY.trim()
        return if (fallback.isNotEmpty()) listOf(fallback) else emptyList()
    }

    private fun getPreferredModel(): String {
        val configured = BuildConfig.GEMINI_MODEL.trim()
        return if (configured.isNotEmpty()) configured else "gemini-3.1-flash-lite-preview"
    }

    private fun readBody(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        if (stream == null) return ""
        return BufferedReader(stream.reader()).use { reader ->
            val out = StringBuilder()
            var line: String? = reader.readLine()
            while (line != null) {
                out.append(line)
                line = reader.readLine()
            }
            out.toString()
        }
    }

    private fun enqueue(context: Context, item: PendingItem) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_QUEUE, "[]") ?: "[]"
        val current = try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }

        val updated = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("message", item.message)
                    put("sender", item.sender)
                    put("appSource", item.appSource)
                    put("timestamp", item.timestamp)
                }
            )
            for (i in 0 until minOf(current.length(), MAX_QUEUE - 1)) {
                put(current.optJSONObject(i) ?: continue)
            }
        }

        prefs.edit().putString(KEY_QUEUE, updated.toString()).apply()
    }

    private fun isOnline(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            Log.w(TAG, "[Tier3] Connectivity check failed, proceeding with online attempt")
            true
        }
    }

    private fun getStartIndex(context: Context, keyCount: Int): Int {
        if (keyCount <= 0) return 0
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getInt(KEY_CURSOR, 0)
        return ((raw % keyCount) + keyCount) % keyCount
    }

    private fun setStartIndex(context: Context, nextIndex: Int, keyCount: Int) {
        if (keyCount <= 0) return
        val normalized = ((nextIndex % keyCount) + keyCount) % keyCount
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_CURSOR, normalized).apply()
    }

    private class GeminiRetryableException(message: String) : Exception(message)
}
