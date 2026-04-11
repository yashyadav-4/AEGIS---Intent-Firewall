package com.intentfirewall

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class AnalysisPayload(
    val packageName: String,
    val context: List<String>,
    val currentMessage: String,
    val timestamp: Long,
)

data class GeminiQueueResult(
    val classification: String,
    val confidence: String,
    val category: String,
    val evidence: String,
    val reasoning: String,
)

object GeminiAnalysisQueue {
    private const val TAG = "SCAM_GeminiAnalysisQueue"
    private const val QUEUE_CAPACITY = 10
    private const val TIMEOUT_MS = 8_000

    @Volatile
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<AnalysisPayload>(
        capacity = QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @Volatile
    private var initialized = false

    @Volatile
    private var appContext: Context? = null

    /**
     * Initializes queue worker exactly once.
     */
    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            appContext = context.applicationContext
            scope.launch { consumeLoop() }
            initialized = true
            Log.i(TAG, "Gemini queue initialized")
        }
    }

    /**
     * Enqueues payload without blocking caller thread.
     */
    fun enqueue(payload: AnalysisPayload) {
        try {
            if (!initialized) {
                Log.w(TAG, "enqueue before initialize, dropping")
                return
            }
            queue.trySend(payload)
        } catch (e: Exception) {
            Log.e(TAG, "enqueue failed", e)
        }
    }

    /**
     * Stops queue worker and cancels in-flight coroutines.
     */
    fun shutdown() {
        synchronized(this) {
            scope.cancel("Accessibility service destroyed")
            initialized = false
        }
    }

    private suspend fun consumeLoop() {
        for (payload in queue) {
            process(payload)
        }
    }

    private suspend fun process(payload: AnalysisPayload) {
        val context = appContext ?: return
        val result = runCatching { callGemini(payload) }
            .recoverCatching {
                delay(500)
                callGemini(payload)
            }
            .getOrElse {
                Log.e(TAG, "Gemini failed for ${payload.packageName}", it)
                return
            }

        DecisionEngine.evaluate(context, payload, result)
    }

    private suspend fun callGemini(payload: AnalysisPayload): GeminiQueueResult = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY.trim().ifBlank {
            BuildConfig.GEMINI_API_KEYS.split(',').map { it.trim() }.firstOrNull { it.isNotBlank() } ?: ""
        }
        require(apiKey.isNotBlank()) { "Gemini API key missing" }

        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
        val prompt = buildPrompt(payload)

        val req = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt)),
                    ),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.1)
                    .put("responseMimeType", "application/json"),
            )

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }

        try {
            OutputStreamWriter(conn.outputStream).use { it.write(req.toString()) }
            val code = conn.responseCode
            val body = readBody(conn, code)
            if (code !in 200..299) {
                error("Gemini HTTP $code: $body")
            }
            parseResponse(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResponse(body: String): GeminiQueueResult {
        val root = JSONObject(body)
        val text = root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text", "")
            .orEmpty()
            .trim()

        require(text.isNotBlank()) { "Gemini response empty" }

        val cleaned = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val json = JSONObject(cleaned)

        return GeminiQueueResult(
            classification = json.optString("classification", "UNCERTAIN").uppercase(),
            confidence = json.optString("confidence", "LOW").uppercase(),
            category = json.optString("category", "UNCERTAIN"),
            evidence = json.optString("evidence", ""),
            reasoning = json.optString("reasoning", ""),
        )
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        if (stream == null) return ""
        return BufferedReader(stream.reader()).use { reader ->
            buildString {
                var line = reader.readLine()
                while (line != null) {
                    append(line)
                    line = reader.readLine()
                }
            }
        }
    }

    private fun buildPrompt(payload: AnalysisPayload): String {
        val priorContext = payload.context.takeLast(3).joinToString("\n").ifBlank { "none" }
        return """
You are an anti-scam classifier for Indian messaging context.
Return only JSON object fields: classification, confidence, category, evidence, reasoning.
classification must be one of SCAM, SAFE, UNCERTAIN.
confidence must be one of HIGH, MEDIUM, LOW.

Package: ${payload.packageName}
Context (oldest to newest):
$priorContext

Current message:
${payload.currentMessage}

Rules:
1) Decide mainly from current message.
2) Use context only for continuity.
3) If uncertain, return UNCERTAIN.
4) Evidence should quote exact phrase from current message.
""".trimIndent()
    }
}
