package com.intentfirewall

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

data class GeminiVoiceDecision(
    val isScam: Boolean,
    val confidence: Float,
    val intent: String,
    val reason: String,
    val action: String,
    val model: String,
    val keyIndex: Int,
)

object GeminiVoiceScamClassifier {
    private const val TAG = "IntentFirewall|Tier3Voice"
    private const val SAMPLE_RATE = 16000
    private const val PREF_NAME = "intentfirewall_gemini_voice"
    private const val KEY_CURSOR = "api_key_cursor_voice"
    @Volatile
    var lastRunStatus: String = "idle"

    fun analyzeLiveWindow(
        context: Context,
        waveform: FloatArray,
        callerUnknown: Boolean,
        pitchVariance: Float,
        zcr: Float,
        localSyntheticScore: Float,
    ): GeminiVoiceDecision? {
        if (!isOnline(context)) {
            lastRunStatus = "offline"
            return null
        }

        val keys = getApiKeys()
        if (keys.isEmpty()) {
            Log.w(TAG, "No API keys configured")
            lastRunStatus = "no_api_keys"
            return null
        }

        val audioBase64 = encodeWavBase64(waveform)
        val model = getPreferredModel()
        val prompt = buildPrompt(callerUnknown, pitchVariance, zcr, localSyntheticScore)
        val startIndex = getStartIndex(context, keys.size)

        for (offset in keys.indices) {
            val idx = (startIndex + offset) % keys.size
            val key = keys[idx]
            for (attempt in 0..1) {
                try {
                    lastRunStatus = "request_key_${idx + 1}_attempt_${attempt + 1}"
                    val decision = callGemini(model, key, prompt, audioBase64)
                    setStartIndex(context, idx + 1, keys.size)
                    lastRunStatus = "success_key_${idx + 1}"
                    return decision.copy(model = model, keyIndex = idx + 1)
                } catch (e: GeminiRetryableException) {
                    Log.w(TAG, "retryable failure keyIndex=${idx + 1} attempt=${attempt + 1}: ${e.message}")
                    lastRunStatus = "retryable_key_${idx + 1}:${e.message ?: "unknown"}"
                    if (attempt == 0) {
                        Thread.sleep(500L)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "failure keyIndex=${idx + 1}: ${e.message}")
                    lastRunStatus = "failure_key_${idx + 1}:${e.javaClass.simpleName}"
                    break
                }
            }
        }

        setStartIndex(context, startIndex + 1, keys.size)
        lastRunStatus = "all_keys_failed"
        return null
    }

    private fun callGemini(model: String, apiKey: String, prompt: String, audioBase64: String): GeminiVoiceDecision {
        val endpoint =
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray()
                            .put(JSONObject().put("text", prompt))
                            .put(
                                JSONObject().put(
                                    "inlineData",
                                    JSONObject().apply {
                                        put("mimeType", "audio/wav")
                                        put("data", audioBase64)
                                    }
                                )
                            )
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
            connectTimeout = 8_000
            readTimeout = 8_000
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
                throw IllegalStateException("Tier3 voice HTTP $code: $body")
            }

            parseDecision(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseDecision(responseBody: String): GeminiVoiceDecision {
        val root = JSONObject(responseBody)
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw IllegalStateException("Tier3 voice empty candidates")
        val text = candidate.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text", "")
            ?.trim()
            .orEmpty()

        if (text.isBlank()) throw IllegalStateException("Tier3 voice empty output")

        val cleaned = text
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val obj = JSONObject(cleaned)

        val isScam = obj.optBoolean("isScam", false)
        val confidence = obj.optDouble("confidence", if (isScam) 0.75 else 0.2)
            .toFloat()
            .coerceIn(0f, 1f)
        val intent = obj.optString("intent", if (isScam) "social_engineering_scam" else "safe_call")
        val reason = obj.optString("reason", "Tier3 voice analysis")
        val action = obj.optString("action", if (isScam) "alert" else "safe")
            .lowercase()
            .let { if (it == "alert" || it == "safe") it else if (isScam) "alert" else "safe" }

        return GeminiVoiceDecision(
            isScam = isScam,
            confidence = confidence,
            intent = intent,
            reason = reason,
            action = action,
            model = "",
            keyIndex = -1,
        )
    }

    private fun buildPrompt(
        callerUnknown: Boolean,
        pitchVariance: Float,
        zcr: Float,
        localSyntheticScore: Float,
    ): String {
        return """
You are a real-time voice scam intent detector for live calls in India.

Task:
- Listen to the attached 2-second call audio clip.
- Determine if there is active scam/social-engineering intent.
- Understand Hindi/English/Hinglish.

High-risk scam intent examples:
- Asking for OTP, CVV, UPI PIN, bank details
- Urgent payment pressure, KYC panic, account blocked threats
- Impersonation of bank/police/government with demand for money/data

Caller context:
- callerUnknown: $callerUnknown
- localPitchVariance: ${"%.5f".format(pitchVariance)}
- localZeroCrossingRate: ${"%.5f".format(zcr)}
- localSyntheticVoiceScore: ${"%.4f".format(localSyntheticScore)}

If evidence is weak or unclear, prefer safe.

Return strict JSON only:
{
  "isScam": true/false,
  "confidence": 0.0-1.0,
  "intent": "otp_scam/banking_scam/authority_impersonation/payment_pressure/safe_call/uncertain",
  "reason": "short reason",
  "action": "alert/safe"
}
""".trimIndent()
    }

    private fun encodeWavBase64(waveform: FloatArray): String {
        val pcm = ShortArray(waveform.size)
        for (i in waveform.indices) {
            val clamped = waveform[i].coerceIn(-1f, 1f)
            pcm[i] = (clamped * 32767f).toInt().toShort()
        }

        val pcmBytes = ByteBuffer.allocate(pcm.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                for (s in pcm) putShort(s)
            }
            .array()

        val wavBytes = buildWav(pcmBytes, SAMPLE_RATE, channels = 1, bitsPerSample = 16)
        return Base64.encodeToString(wavBytes, Base64.NO_WRAP)
    }

    private fun buildWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val chunkSize = 36 + dataSize

        val out = ByteArrayOutputStream(44 + dataSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(chunkSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray())
        header.putInt(dataSize)

        out.write(header.array())
        out.write(pcmData)
        return out.toByteArray()
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
        val configured = BuildConfig.GEMINI_VOICE_MODEL.trim()
        return if (configured.isNotEmpty()) configured else "gemini-2.0-flash"
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

    private fun isOnline(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    private fun getStartIndex(context: Context, keyCount: Int): Int {
        if (keyCount <= 0) return 0
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getInt(KEY_CURSOR, 0)
        return abs(raw) % keyCount
    }

    private fun setStartIndex(context: Context, nextIndex: Int, keyCount: Int) {
        if (keyCount <= 0) return
        val normalized = abs(nextIndex) % keyCount
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_CURSOR, normalized).apply()
    }

    private class GeminiRetryableException(message: String) : Exception(message)
}