package com.intentfirewall

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GeminiRestAnalyzer(
    private val callerNumber: String,
    private val onRiskScore: (score: Int, intent: String, reason: String) -> Unit,
    private val onError: (error: String) -> Unit,
) {
    companion object {
        private const val WINDOW_SIZE_BYTES = 480000
        private const val FIRST_WINDOW_SIZE_BYTES = 240000
        private const val MODEL = "gemini-3.1-flash-lite-preview"
        private const val GEMINI_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite-preview:generateContent"
    }

    private val tag = "GeminiRestAnalyzer"

    private val apiKeys: List<String> = listOf(
        BuildConfig.GEMINI_API_KEY_1,
        BuildConfig.GEMINI_API_KEY_2,
        BuildConfig.GEMINI_API_KEY_3,
        BuildConfig.GEMINI_API_KEY_4,
        BuildConfig.GEMINI_API_KEY_5,
    ).map { it.trim() }.filter { it.isNotBlank() }.ifEmpty {
        BuildConfig.GEMINI_API_KEYS
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty {
                listOf(BuildConfig.GEMINI_API_KEY.trim()).filter { it.isNotBlank() }
            }
    }

    private val keyCooldownUntil = LongArray(apiKeys.size) { 0L }
    private val keyIndex = AtomicInteger(0)
    private val cooldownMs = 60_000L

    private fun endpointFor(): String = GEMINI_ENDPOINT

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val windowBuffer = mutableListOf<ByteArray>()
    private var currentBufferSize = 0
    private var isFirstWindow = true
    private var isAnalyzing = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val SYSTEM_PROMPT = """
You are a real-time scam call detector for Indian phone calls (Hindi, English, Hinglish).
Analyze the provided audio for scam patterns.

Respond ONLY with a valid JSON object matching this exact schema - no prose, no markdown, no code fences:
{"risk": <integer 0-100>, "intent": "<one of: safe|otp_request|payment_pressure|impersonation|urgency_manipulation|kyc_panic|remote_access>", "flag": <true if risk >= 55>, "reason": "<max 12 words in English>"}

English scam signals to detect:
- OTP or PIN requests ("share your OTP", "tell me the code")
- Bank or government impersonation ("I'm calling from SBI/RBI/TRAI/police/CBI")
- Account block threats ("your account will be blocked/suspended")
- Urgency and pressure tactics ("you must act now", "only 30 minutes left")
- Remote access requests ("install AnyDesk", "install TeamViewer", "give me access")
- UPI transfer demands ("send money to verify", "transfer to unblock")
- KYC panic ("your KYC is expired", "update KYC immediately")

Hindi/Hinglish scam signals to detect:
- "OTP batao", "OTP share karo", "OTP bata do"
- "aapka account band ho jayega", "account block ho gaya"
- "KYC update karo", "KYC expire ho gayi", "KYC verify karo"
- "paisa transfer karo", "paise bhejo", "abhi transfer karo"
- "link pe click karo", "yeh app install karo"
- "mujhe remote access do", "screen share karo"
- "aapke naam pe FIR hai", "police aa rahi hai", "CBI se hoon"
- "income tax notice aaya hai", "arrest hoga"
- "lottery lagi hai", "prize jeeta hai", "reward claim karo"
- "pin number batao", "card number batao"
- "ek baar OTP share karo sirf verify ke liye"
- "account safe karne ke liye abhi karo"

If audio is silent, unclear, ambient noise only, or no speech detected:
{"risk": 0, "intent": "safe", "flag": false, "reason": "no speech detected"}

Rules:
- Never refuse to respond
- Always return valid JSON only
- If speech is present but not a scam, return risk 0-20
- If speech matches 1-2 scam signals, return risk 40-65
- If speech matches 3+ scam signals, return risk 75-95
""".trimIndent()

    init {
        scope.launch {
            runModelProbe()
        }
    }

    private fun pickKey(): Pair<Int, String>? {
        if (apiKeys.isEmpty()) {
            return null
        }

        val now = System.currentTimeMillis()
        repeat(apiKeys.size) {
            val idx = keyIndex.getAndIncrement().mod(apiKeys.size)
            if (keyCooldownUntil[idx] <= now) {
                return Pair(idx, apiKeys[idx])
            }
        }

        val soonestIdx = keyCooldownUntil.indices.minByOrNull { keyCooldownUntil[it] } ?: 0
        val waitMs = keyCooldownUntil[soonestIdx] - now
        Log.w(tag, "All keys on cooldown. Soonest recovery in ${waitMs}ms on key index $soonestIdx")
        return null
    }

    private fun markKeyCooldown(idx: Int) {
        if (idx < 0 || idx >= keyCooldownUntil.size) return
        keyCooldownUntil[idx] = System.currentTimeMillis() + cooldownMs
        Log.w(tag, "Key index $idx on 429 cooldown for ${cooldownMs / 1000}s")
    }

    private fun logModelDeadIfNeeded(responseCode: Int, errorText: String) {
        if (responseCode == 404 || (responseCode == 400 && errorText.contains("not found", ignoreCase = true))) {
            Log.e(tag, "MODEL_DEAD: The model '$MODEL' does not exist or was shut down. Update MODEL constant.")
        }
    }

    private fun buildRequestBody(pcmBytes: ByteArray, callerNumber: String): String {
        val base64Audio = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
        val durationSec = pcmBytes.size / (16000f * 2f)
        Log.d(tag, "WINDOW: bytes=${pcmBytes.size} duration=${durationSec}s")

        return JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().put("text", SYSTEM_PROMPT))
                })
            })
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", "audio/l16;rate=16000;channels=1")
                                put("data", base64Audio)
                            })
                        })
                        put(
                            JSONObject().put(
                                "text",
                                "Caller number: $callerNumber. Analyze this audio for scam patterns.",
                            ),
                        )
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 150)
                put("responseMimeType", "application/json")
            })
        }.toString()
    }

    private fun sendToGemini(requestBodyJson: String, keyIndex: Int, apiKey: String): Pair<Int, String> {
        val request = Request.Builder()
            .url(endpointFor())
            .addHeader("x-goog-api-key", apiKey)
            .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val responseCode = response.code
            val responseText = response.body?.string() ?: ""
            Log.d(tag, "REST_RESPONSE: code=$responseCode keyIndex=$keyIndex")

            if (responseCode != 200 && responseCode != 429) {
                val body = if (responseText.isBlank()) "no error body" else responseText
                Log.e(tag, "API_ERROR: code=$responseCode key=$keyIndex error=$body")
                logModelDeadIfNeeded(responseCode, responseText)
            }

            return Pair(responseCode, responseText)
        }
    }

    private fun runModelProbe() {
        try {
            val apiKey = apiKeys.firstOrNull()
            if (apiKey.isNullOrBlank()) {
                Log.e(tag, "MODEL_PROBE: no API key configured")
                return
            }

            val testBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", "Reply with exactly: OK"))
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("maxOutputTokens", 5)
                })
            }.toString()

            val request = Request.Builder()
                .url(endpointFor())
                .addHeader("x-goog-api-key", apiKey)
                .post(testBody.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val code = response.code
                val responseText = response.body?.string() ?: ""
                if (code == 200) {
                    Log.d(tag, "MODEL_PROBE: $MODEL is ALIVE (code=200)")
                } else {
                    val body = if (responseText.isBlank()) "no error body" else responseText
                    Log.e(tag, "MODEL_PROBE: $MODEL returned code=$code error=$body - MODEL MAY BE DEAD")
                    logModelDeadIfNeeded(code, responseText)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "MODEL_PROBE: exception ${e.message}")
        }
    }

    fun addChunk(pcmBytes: ByteArray) {
        synchronized(windowBuffer) {
            windowBuffer.add(pcmBytes.copyOf())
            currentBufferSize += pcmBytes.size
        }

        val threshold = if (isFirstWindow) FIRST_WINDOW_SIZE_BYTES else WINDOW_SIZE_BYTES
        if (currentBufferSize >= threshold && !isAnalyzing) {
            val snapshot: List<ByteArray>
            synchronized(windowBuffer) {
                snapshot = windowBuffer.toList()
                windowBuffer.clear()
                currentBufferSize = 0
            }
            val drainedBytes = snapshot.sumOf { it.size }
            Log.d(tag, "BUFFER_DRAINED: chunks=${snapshot.size} bytes=$drainedBytes threshold=$threshold")
            isFirstWindow = false
            analyzeWindow(snapshot, threshold)
        }
    }

    private fun analyzeWindow(chunks: List<ByteArray>, threshold: Int) {
        isAnalyzing = true
        scope.launch {
            try {
                val keyPair = pickKey()
                if (keyPair == null) {
                    Log.w(tag, "Skipping window - all keys exhausted or unavailable")
                    onError("all_keys_exhausted")
                    return@launch
                }

                val (keyIdx, apiKey) = keyPair

                val totalSize = chunks.sumOf { it.size }
                val combined = ByteArray(totalSize)
                var offset = 0
                for (chunk in chunks) {
                    chunk.copyInto(combined, offset)
                    offset += chunk.size
                }

                val requestBody = buildRequestBody(combined, callerNumber)
                Log.d(
                    tag,
                    "Analyzing window: bytes=$totalSize keyIndex=$keyIdx model=$MODEL target=$threshold",
                )

                val initialResult = sendToGemini(requestBody, keyIdx, apiKey)
                val responseCode = initialResult.first
                val responseText = initialResult.second

                when (responseCode) {
                    200 -> {
                        Log.d(tag, "PARSE_INPUT: $responseText")
                        parseResponse(responseText)
                    }

                    429 -> {
                        markKeyCooldown(keyIdx)
                        Log.w(tag, "429 on key $keyIdx - trying additional keys")

                        var handled = false
                        var attempts = 0
                        var retryPair = pickKey()
                        while (!handled && retryPair != null && attempts < apiKeys.size) {
                            attempts += 1
                            val retryResult = sendToGemini(requestBody, retryPair.first, retryPair.second)
                            val retryCode = retryResult.first
                            val retryText = retryResult.second

                            when (retryCode) {
                                200 -> {
                                    Log.d(tag, "PARSE_INPUT: $retryText")
                                    parseResponse(retryText)
                                    handled = true
                                }

                                429 -> {
                                    markKeyCooldown(retryPair.first)
                                    Log.w(tag, "Retry-$attempts also 429 on key ${retryPair.first}")
                                    retryPair = pickKey()
                                }

                                else -> {
                                    onError("API error $retryCode")
                                    handled = true
                                }
                            }
                        }

                        if (!handled) {
                            onError("quota_exhausted")
                        }
                    }

                    else -> {
                        onError("API error $responseCode")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Analysis failed", e)
                onError(e.message ?: "Unknown error")
            } finally {
                isAnalyzing = false
            }
        }
    }

    private fun parseResponse(responseText: String) {
        try {
            val root = JSONObject(responseText)
            val candidates = root.optJSONArray("candidates") ?: return
            val content = candidates.getJSONObject(0)
                .optJSONObject("content") ?: return
            val parts = content.optJSONArray("parts") ?: return
            val text = parts.getJSONObject(0).optString("text", "").trim()

            Log.d(tag, "PARSE_INPUT: $text")

            val clean = text.replace("```json", "").replace("```", "").trim()
            val obj = JSONObject(clean)
            val risk = obj.optInt("risk", 0).coerceIn(0, 100)
            val intent = obj.optString("intent", "unknown")
            val reason = obj.optString("reason", "no reason")

            Log.d(tag, "PARSED: risk=$risk intent=$intent reason=$reason")
            onRiskScore(risk, intent, reason)
        } catch (e: Exception) {
            Log.e(tag, "Parse failed: $responseText", e)
        }
    }

    fun flush() {
        val snapshot: List<ByteArray>
        synchronized(windowBuffer) {
            if (windowBuffer.size < 4 || isAnalyzing) return
            snapshot = windowBuffer.toList()
            windowBuffer.clear()
            currentBufferSize = 0
        }
        val threshold = if (isFirstWindow) FIRST_WINDOW_SIZE_BYTES else WINDOW_SIZE_BYTES
        val drainedBytes = snapshot.sumOf { it.size }
        Log.d(tag, "BUFFER_DRAINED: chunks=${snapshot.size} bytes=$drainedBytes threshold=$threshold flush=true")
        isFirstWindow = false
        analyzeWindow(snapshot, threshold)
    }

    fun shutdown() {
        scope.cancel()
    }
}
