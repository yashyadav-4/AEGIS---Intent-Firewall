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

    private val model = "gemini-2.5-flash-lite"
    private fun endpointFor(key: String): String {
        return "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // Start with a faster first decision (5s), then switch to 15s steady windows.
    // 5s at 16kHz 16-bit mono = 160000 bytes.
    // 15s at 16kHz 16-bit mono = 480000 bytes.
    private val windowBuffer = mutableListOf<ByteArray>()
    private val firstWindowTargetBytes = 160000
    private val steadyWindowTargetBytes = 480000
    private var currentWindowTargetBytes = firstWindowTargetBytes
    private var currentBufferSize = 0
    private var isAnalyzing = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val systemPrompt = """
        You are a real-time scam call detector for Indian phone calls.
        Analyze the provided audio for scam patterns.
        Respond with ONLY a valid JSON object, no prose, no markdown:
        {"risk": <0-100>, "intent": "<safe|otp_request|payment_pressure|impersonation|urgency_manipulation|kyc_panic|remote_access>", "flag": <true if risk >= 55>, "reason": "<max 12 words>"}

        Scam signals: OTP/PIN requests, bank/govt impersonation, account block threats,
        urgency pressure, AnyDesk/TeamViewer install requests, UPI transfer demands, KYC panic.

        If audio is unclear or silent respond: {"risk": 0, "intent": "safe", "flag": false, "reason": "no signal detected"}
        Never refuse. Always return valid JSON only.
        Caller number context: $callerNumber.
    """.trimIndent()

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

    fun addChunk(pcmBytes: ByteArray) {
        synchronized(windowBuffer) {
            windowBuffer.add(pcmBytes.copyOf())
            currentBufferSize += pcmBytes.size
        }

        if (currentBufferSize >= currentWindowTargetBytes && !isAnalyzing) {
            val snapshot: List<ByteArray>
            synchronized(windowBuffer) {
                snapshot = windowBuffer.toList()
                windowBuffer.clear()
                currentBufferSize = 0
            }
            analyzeWindow(snapshot)
        }
    }

    private fun analyzeWindow(chunks: List<ByteArray>) {
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

                val b64 = Base64.encodeToString(combined, Base64.NO_WRAP)
                Log.d(
                    tag,
                    "Analyzing window: bytes=$totalSize keyIndex=$keyIdx model=$model target=$currentWindowTargetBytes",
                )

                val requestBody = JSONObject().apply {
                    put("system_instruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", systemPrompt) })
                        })
                    })
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("inline_data", JSONObject().apply {
                                        put("mime_type", "audio/pcm;rate=16000")
                                        put("data", b64)
                                    })
                                })
                                put(JSONObject().apply {
                                    put("text", "Analyze this audio for scam patterns and respond with JSON only.")
                                })
                            })
                        })
                    })
                    put("generation_config", JSONObject().apply {
                        put("response_mime_type", "application/json")
                        put("temperature", 0.1)
                    })
                }.toString()

                val request = Request.Builder()
                    .url(endpointFor(apiKey))
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseText = response.body?.string() ?: ""

                when (response.code) {
                    200 -> {
                        Log.d(tag, "REST_RESPONSE: code=200 keyIndex=$keyIdx")
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
                            val retryRequest = Request.Builder()
                                .url(endpointFor(retryPair.second))
                                .post(requestBody.toRequestBody("application/json".toMediaType()))
                                .build()

                            val retryResponse = client.newCall(retryRequest).execute()
                            val retryText = retryResponse.body?.string() ?: ""

                            when (retryResponse.code) {
                                200 -> {
                                    Log.d(tag, "REST_RESPONSE: code=200 keyIndex=${retryPair.first} (retry-$attempts)")
                                    parseResponse(retryText)
                                    handled = true
                                }

                                429 -> {
                                    markKeyCooldown(retryPair.first)
                                    Log.w(tag, "Retry-$attempts also 429 on key ${retryPair.first}")
                                    retryPair = pickKey()
                                }

                                else -> {
                                    Log.e(tag, "Retry error ${retryResponse.code}: $retryText")
                                    onError("API error ${retryResponse.code}")
                                    handled = true
                                }
                            }
                        }

                        if (!handled) {
                            onError("quota_exhausted")
                        }
                    }

                    else -> {
                        Log.e(tag, "REST error ${response.code}: $responseText")
                        onError("API error ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Analysis failed", e)
                onError(e.message ?: "Unknown error")
            } finally {
                isAnalyzing = false
                if (currentWindowTargetBytes != steadyWindowTargetBytes) {
                    currentWindowTargetBytes = steadyWindowTargetBytes
                    Log.d(tag, "Switched analyzer window target to steady=$steadyWindowTargetBytes bytes")
                }
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
        analyzeWindow(snapshot)
    }

    fun shutdown() {
        scope.cancel()
    }
}
