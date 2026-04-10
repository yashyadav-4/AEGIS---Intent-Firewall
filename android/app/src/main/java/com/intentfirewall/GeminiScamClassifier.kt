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
        val isScam = obj.optBoolean("isScam", false)
        val confidence = obj.optDouble("confidence", if (isScam) 0.75 else 0.20)
            .toFloat()
            .coerceIn(0f, 1f)
        val reason = obj.optString("reason", "Tier3 analysis")
        val rawThreatType = obj.optString("threatType", "")
        val threatType = if (rawThreatType.equals("null", ignoreCase = true) || rawThreatType.isBlank()) {
            null
        } else {
            rawThreatType
        }

        val action = obj.optString("action", if (isScam) "alert" else "safe")
            .lowercase()
            .let { if (it == "alert" || it == "safe") it else if (isScam) "alert" else "safe" }

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
            .takeLast(6)
            .joinToString(" | ")
            .ifBlank { "none" }

        return """
    You are a strict scam detection assistant for Indian chat/SMS scams.

    Priority rule: minimize false positives. If not clearly malicious, mark safe.

    Analyze the CURRENT message text first. Use recent context only for disambiguation.
    Never label a short acknowledgement (for example: "yes sir", "ok", "noted") as scam unless the same message contains direct scam content.

    Only choose action="alert" when current message has clear scam intent such as:
    - direct OTP/password/payment request
    - forced urgency + account blocking/KYC fear tactics
    - impersonation (bank/police/authority) with demand for money/data/OTP
    - suspicious link with pressure to click or pay

    Choose action="safe" for benign communication, including:
    - school/college/work planning (example: "anyone participating in hackathon")
    - teacher/class/group coordination
    - normal replies, greetings, announcements, schedules, attendance, assignments
    - generic conversation with no ask for money, OTP, credential, link click, or sensitive data

    Understand Hinglish/Hindi-English mixed text (examples: "OTP de do", "lottery claim", "6 digit number batao").

    Allowed threatType values only:
    - SAFE_MESSAGE
    - PHISHING_LINK
    - OTP_SCAM
    - BANKING_SCAM
    - AUTHORITY_IMPERSONATION
    - PAYMENT_PRESSURE
    - JOB_SCAM
    - SCAM_SUSPECT

    If evidence is weak or ambiguous, choose safe with threatType=SAFE_MESSAGE.

Message: "$message"
Sender: $sender
App Source: $appSource
Time: $timestamp
Capture Method: $captureMethod
Recent Context: $contextWindow
Sender Known Contact: $senderKnown

Is this message a scam? Answer in JSON:
{
"isScam": true/false,
"confidence": 0.0-1.0,
"reason": "brief explanation",
"threatType": "SAFE_MESSAGE/PHISHING_LINK/OTP_SCAM/BANKING_SCAM/AUTHORITY_IMPERSONATION/PAYMENT_PRESSURE/JOB_SCAM/SCAM_SUSPECT",
"action": "alert/safe"
}
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
