package com.intentfirewall

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

class GeminiLiveConnection(
    private val callerNumber: String,
    private val onRiskScore: (score: Int, intent: String, reason: String) -> Unit,
    private val onError: (error: String) -> Unit,
    private val onReadyChanged: (ready: Boolean) -> Unit = {},
    private val onChunkSent: (count: Int) -> Unit = {},
    private val onResponseReceived: (count: Int) -> Unit = {},
) {

    private val tag = "GeminiLiveConnection"
    private var webSocket: WebSocket? = null
    private var isSetupComplete = false
    private var chunksSent = 0
    private var responsesReceived = 0
    private var isManualDisconnect = false
    private var lastTranscript = ""
    private var lastTranscriptAtMs = 0L
    private var rawMessagesLogged = 0
    private var serverMessagesReceived = 0
    private val pendingChunks = ArrayDeque<ByteArray>()
    private val maxPendingChunks = 10

    private val apiKey = BuildConfig.GEMINI_API_KEY
    private val model = BuildConfig.GEMINI_LIVE_MODEL

    private val systemPrompt = """
        You are a real-time scam call detector for Indian phone calls.
        After each turn, you MUST respond with ONLY a valid JSON object, no prose, no markdown:
        {"risk": <0-100>, "intent": "<safe|otp_request|payment_pressure|impersonation|urgency_manipulation|kyc_panic|remote_access>", "flag": <true if risk >= 55>, "reason": "<max 12 words>"}

        Scam signals: OTP/PIN requests, bank/govt impersonation, account block threats,
        urgency pressure, AnyDesk/TeamViewer install requests, UPI transfer demands, KYC panic.

        If audio is unclear or silent respond: {"risk": 0, "intent": "safe", "flag": false, "reason": "no signal detected"}
        Never refuse. Always return valid JSON only.
        Caller number context: $callerNumber.
    """.trimIndent()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun connect() {
        if (apiKey.isBlank()) {
            onError("Missing Gemini API key")
            onReadyChanged(false)
            return
        }

        isManualDisconnect = false
        serverMessagesReceived = 0
        rawMessagesLogged = 0
        if (model.isBlank()) {
            onError("No compatible Gemini Live model configured")
            onReadyChanged(false)
            return
        }

        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .build()

        Log.d(tag, "Connecting to Gemini Live with model=$model modality=TEXT")
        val maskedKey = if (apiKey.length >= 10) {
            "${apiKey.take(6)}...${apiKey.takeLast(4)}"
        } else {
            "${apiKey.take(2)}..."
        }
        Log.d(tag, "WS_URL=wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=<masked>")
        Log.d(tag, "API_KEY_MASKED=$maskedKey isNonEmpty=${apiKey.isNotBlank()}")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(tag, "CB:onOpen entered")
                Log.d(tag, "WebSocket opened - sending setup")
                sendSetup(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(tag, "CB:onMessage(text) fired")
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(tag, "CB:onMessage(binary) fired")
                handleBinaryMessage(bytes)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.d(tag, "CB:onFailure entered")
                val message = t.message ?: "unknown"
                Log.e(tag, "WebSocket failure (model=$model): $message")
                isSetupComplete = false
                onReadyChanged(false)
                onError("Connection failed: $message")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(tag, "CB:onClosed entered")
                Log.d(tag, "WebSocket closed: $code $reason (model=$model)")
                isSetupComplete = false
                onReadyChanged(false)
            }
        })
    }

    private fun sendSetup(ws: WebSocket) {
        val setup = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", model)
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", systemPrompt)
                        })
                    })
                })
                put("generation_config", JSONObject().apply {
                    put("response_modalities", JSONArray().apply { put("TEXT") })
                })
            })
        }
        Log.d(tag, "SETUP_JSON_PRETTY=\n${setup.toString(2)}")
        ws.send(setup.toString())
        isSetupComplete = true
        onReadyChanged(true)
        flushPendingChunks(ws)
        Log.d(tag, "Setup message sent")
        Log.d(tag, "Setup complete, flushed pending chunks")
    }

    fun sendAudioChunk(pcmBytes: ByteArray) {
        if (!isSetupComplete) {
            enqueuePendingChunk(pcmBytes)
            return
        }

        val ws = webSocket
        if (ws == null) {
            enqueuePendingChunk(pcmBytes)
            return
        }

        flushPendingChunks(ws)
        sendChunkInternal(ws, pcmBytes)
    }

    private fun enqueuePendingChunk(pcmBytes: ByteArray) {
        if (pendingChunks.size >= maxPendingChunks) {
            pendingChunks.removeFirst()
        }
        pendingChunks.addLast(pcmBytes.copyOf())
        Log.d(tag, "Queued pre-setup chunk (queue=${pendingChunks.size})")
    }

    private fun flushPendingChunks(ws: WebSocket) {
        while (pendingChunks.isNotEmpty()) {
            val chunk = pendingChunks.removeFirst()
            sendChunkInternal(ws, chunk)
            Log.d(tag, "Flushed queued chunk")
        }
    }

    private fun sendChunkInternal(ws: WebSocket, pcmBytes: ByteArray) {
        val b64 = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
        val message = JSONObject().apply {
            put("realtime_input", JSONObject().apply {
                put("media_chunks", JSONArray().apply {
                    put(JSONObject().apply {
                        put("mime_type", "audio/pcm;rate=16000")
                        put("data", b64)
                    })
                })
            })
        }

        Log.d(tag, "CHUNK_SEND: bytes=${pcmBytes.size} b64len=${b64.length} setupComplete=$isSetupComplete ws=true")
        Log.d(tag, "CHUNK_B64_PREFIX=${b64.take(20)}")
        ws.send(message.toString())
        chunksSent++
        onChunkSent(chunksSent)
        if (chunksSent == 1 || chunksSent % 5 == 0) {
            Log.d(tag, "Sent audio chunks=$chunksSent")
        }

        // Force periodic flush so the model doesn't wait indefinitely for VAD turn end.
        if (chunksSent % 12 == 0) {
            sendAudioStreamEnd(ws, "periodic_flush")
        }
    }

    private fun sendAudioStreamEnd(ws: WebSocket, reason: String) {
        try {
            val endMessage = JSONObject().apply {
                put("realtime_input", JSONObject().apply {
                    put("audio_stream_end", true)
                })
            }
            ws.send(endMessage.toString())
            Log.d(tag, "Sent audio_stream_end ($reason) at chunks=$chunksSent")
        } catch (e: Exception) {
            Log.w(tag, "Failed to send audio_stream_end ($reason): ${e.message}")
        }
    }

    fun sendTurnComplete() {
        val ws = webSocket ?: return
        val message = JSONObject().apply {
            put("client_content", JSONObject().apply {
                put("turn_complete", true)
            })
        }
        ws.send(message.toString())
        Log.d(tag, "Sent turn_complete signal")
    }

    private fun handleMessage(rawText: String) {
        try {
            Log.d(tag, "RAW_ONMESSAGE: $rawText")
            serverMessagesReceived++
            if (serverMessagesReceived <= 3) {
                val sample = rawText.replace("\n", " ").take(280)
                Log.d(tag, "Gemini server message #$serverMessagesReceived: $sample")
            }

            val response = JSONObject(rawText)
            val serverContent = response.optJSONObject("serverContent")

            var handledSignal = false

            if (serverContent != null) {
                val inputTranscript = serverContent
                    .optJSONObject("inputTranscription")
                    ?.optString("text", "")
                    ?.trim()
                    .orEmpty()
                    .ifBlank {
                        serverContent
                            .optJSONObject("input_transcription")
                            ?.optString("text", "")
                            ?.trim()
                            .orEmpty()
                    }
                if (inputTranscript.isNotBlank()) {
                    handledSignal = true
                    evaluateTranscript(inputTranscript, "inputTranscription")
                }

                val outputTranscript = serverContent
                    .optJSONObject("outputTranscription")
                    ?.optString("text", "")
                    ?.trim()
                    .orEmpty()
                    .ifBlank {
                        serverContent
                            .optJSONObject("output_transcription")
                            ?.optString("text", "")
                            ?.trim()
                            .orEmpty()
                    }
                if (outputTranscript.isNotBlank()) {
                    handledSignal = true
                    evaluateTranscript(outputTranscript, "outputTranscription")
                }

                val modelTurn = serverContent.optJSONObject("modelTurn")
                val parts = modelTurn?.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        val text = part.optString("text", "").trim()
                        if (text.isNotBlank()) {
                            handledSignal = true
                            parseRiskResponse(text)
                        }

                        val inlineData = part.optJSONObject("inlineData")
                            ?: part.optJSONObject("inline_data")
                        if (inlineData != null) {
                            handledSignal = true
                            Log.d(tag, "Received Gemini audio response part")
                        }
                    }
                }
            }

            if (handledSignal) {
                responsesReceived++
                onResponseReceived(responsesReceived)
                Log.d(tag, "Received Gemini signal #$responsesReceived")
            } else if (rawMessagesLogged < 5) {
                rawMessagesLogged++
                val snippet = rawText.replace("\n", " ").take(280)
                Log.d(tag, "Gemini message without transcript/text (sample #$rawMessagesLogged): $snippet")
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to parse Gemini response: ${e.message} - raw: $rawText")
        }
    }

    private fun handleBinaryMessage(bytes: ByteString) {
        serverMessagesReceived++

        // Some Live API implementations can return binary frames for audio responses.
        // Try UTF-8 decode first in case this binary frame is actually JSON bytes.
        val maybeJson = try {
            bytes.utf8()
        } catch (_: Exception) {
            ""
        }

        if (maybeJson.startsWith("{") && maybeJson.contains("serverContent")) {
            handleMessage(maybeJson)
            return
        }

        responsesReceived++
        onResponseReceived(responsesReceived)
        if (responsesReceived <= 5 || responsesReceived % 10 == 0) {
            Log.d(
                tag,
                "Received Gemini binary frame bytes=${bytes.size} (responses=$responsesReceived, serverMessages=$serverMessagesReceived)",
            )
        }
    }

    private fun evaluateTranscript(text: String, source: String) {
        val normalized = text.lowercase().replace("\\s+".toRegex(), " ").trim()
        if (normalized.isBlank()) return

        val now = System.currentTimeMillis()
        if (normalized == lastTranscript && now - lastTranscriptAtMs < 2500) {
            return
        }
        lastTranscript = normalized
        lastTranscriptAtMs = now

        val scored = scoreTranscript(normalized)
        if (scored.risk >= 55) {
            Log.d(tag, "Transcript risk from $source: risk=${scored.risk}, intent=${scored.intent}, reason=${scored.reason}")
            onRiskScore(scored.risk, scored.intent, scored.reason)
        }
    }

    private data class TranscriptRisk(
        val risk: Int,
        val intent: String,
        val reason: String,
    )

    private fun scoreTranscript(text: String): TranscriptRisk {
        var risk = 0
        var intent = "unknown"
        val reasons = mutableListOf<String>()

        fun any(vararg markers: String): Boolean = markers.any { text.contains(it) }

        if (any("otp", "one time password", "pin", "cvv", "mpin", "password")) {
            risk += 40
            intent = "otp_request"
            reasons.add("credential request")
        }

        if (any("upi", "payment", "transfer", "send money", "refund", "gift card", "wallet")) {
            risk += 25
            if (intent == "unknown") intent = "payment_pressure"
            reasons.add("money transfer language")
        }

        if (any("bank", "rbi", "trai", "police", "income tax", "customs", "kyc")) {
            risk += 20
            if (intent == "unknown") intent = "impersonation"
            reasons.add("authority impersonation")
        }

        if (any("urgent", "immediately", "right now", "account blocked", "suspended", "legal action", "arrest")) {
            risk += 20
            if (intent == "unknown") intent = "urgency_manipulation"
            reasons.add("urgency pressure")
        }

        if (any("anydesk", "teamviewer", "screen share", "install app")) {
            risk += 25
            if (intent == "unknown") intent = "impersonation"
            reasons.add("remote-access setup")
        }

        risk = risk.coerceIn(0, 100)
        val reason = if (reasons.isEmpty()) "No explicit scam markers" else reasons.joinToString(", ")
        return TranscriptRisk(risk = risk, intent = intent, reason = reason)
    }

    private fun parseRiskResponse(jsonText: String) {
        try {
            Log.d(tag, "PARSE_INPUT: '$jsonText'")
            val clean = jsonText
                .replace("```json", "")
                .replace("```", "")
                .trim()

            val obj = JSONObject(clean)
            val risk = obj.optInt("risk", 0).coerceIn(0, 100)
            val intent = obj.optString("intent", "unknown")
            val reason = obj.optString("reason", "No reason provided")
            val flag = obj.optBoolean("flag", false)

            Log.d(tag, "Gemini risk: $risk | intent: $intent | reason: $reason")
            Log.d(tag, "PARSED: risk=$risk intent=$intent flag=$flag reason=$reason")

            // Temporary diagnostic behavior: always route parsed scores into aggregator.
            onRiskScore(risk, intent, reason)
        } catch (_: Exception) {
            Log.w(tag, "Failed to parse risk JSON: $jsonText")
        }
    }

    fun disconnect() {
        Log.d(tag, "Disconnecting Gemini Live (sent $chunksSent chunks, responses=$responsesReceived, serverMessages=$serverMessagesReceived)")
        isManualDisconnect = true
        val ws = webSocket
        if (ws != null && isSetupComplete) {
            sendAudioStreamEnd(ws, "final_flush")

            thread(name = "gemini-live-close", start = true) {
                try {
                    Thread.sleep(2500)
                } catch (_: Exception) {
                }
                try {
                    ws.close(1000, "Call ended")
                } catch (_: Exception) {
                }
            }
        } else {
            ws?.close(1000, "Call ended")
        }
        webSocket = null
        isSetupComplete = false
        onReadyChanged(false)
        chunksSent = 0
        responsesReceived = 0
        rawMessagesLogged = 0
        pendingChunks.clear()
    }
}
