# IntentFirewall — Call Scam Detection: Complete Implementation Guide

> **For the implementing agent:** Read this entire document before writing a single line of code.
> Every section builds on the previous one. Skipping ahead will cause breakage.
> The existing text scam detection (NotificationListenerService + AccessibilityService) must remain completely untouched.
> All new code lives in new files unless explicitly told otherwise.

---

## 0. Ground Rules Before You Start

### 0.1 What is already working — DO NOT TOUCH

The following system is live and working. Your job is to ADD the call detection layer alongside it, not replace or refactor it.

- `NotificationListenerService` — scans notification text for scam signals
- `AccessibilityService` — reads on-screen text during active sessions
- `NotificationEventEmitter` — the React Native bridge that emits scam events to JS
- `AegisDetectionModule` (exported as `NotificationService`) — existing RN bridge
- All existing JS screens (`SettingsScreen`, `DebugScreen`, history views)
- All existing `permissionManager.ts` logic

**Isolation rule:** Every new class you write for call detection must be a new file. You may add new methods to `AegisDetectionModule.kt` and new entries to `AndroidManifest.xml`, but do not modify the bodies of any existing methods.

### 0.2 What you are building

A layered call scam detection system with three tiers:

| Tier | When | What |
|------|------|------|
| Tier 0 | Pre-answer | `CallScreeningService` — block/silence high-risk numbers before the phone even rings |
| Tier 1 | Always, call answered | Metadata risk scoring — number analysis, spam DB, heuristics |
| Tier 2 | Call answered + speakerphone | `AudioRecord` mic capture → chunked to Gemini Live WebSocket for real-time scam intent analysis |

These three tiers feed a single combined risk engine that emits events through the **existing** `NotificationEventEmitter` so the JS layer sees call scam events exactly like text scam events.

### 0.3 The Android audio wall — read this or you will waste days

Android's security model does NOT allow third-party apps to record call audio from the telephony path. `AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION)` captures the device microphone only. During a normal earpiece call, the caller's voice is routed entirely through the telephony stack and never reaches your buffer. You get silence or your own voice only.

The only legal workaround without being the system default dialer is **speakerphone**. When the user enables speakerphone, the caller's voice comes through the speaker, bounces off surfaces, and is picked up by the microphone. Your `AudioRecord` buffer will then contain both voices. This is why speakerphone is a prompt in the UI, not a silent background behavior.

Do not attempt to work around this. Do not add flags or hacks to try to access the telephony audio path. Build the speakerphone path correctly and communicate it honestly to the user.

---

## 1. Manifest Changes

File: `android/app/src/main/AndroidManifest.xml`

Add these permissions (after existing permissions, do not remove any):

```xml
<!-- Call screening and phone state -->
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.READ_CALL_LOG" />

<!-- Already present but verify these exist -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

Add these service declarations (inside `<application>`, after existing services):

```xml
<!-- Tier 0: Pre-answer call screening -->
<service
    android:name=".CallScamScreener"
    android:permission="android.permission.BIND_SCREENING_SERVICE"
    android:exported="true">
    <intent-filter>
        <action android:name="android.telecom.CallScreeningService" />
    </intent-filter>
</service>

<!-- Tier 2: Live call audio monitor (foreground, mic type) -->
<service
    android:name=".CallAudioMonitorService"
    android:foregroundServiceType="microphone"
    android:exported="false" />
```

---

## 2. Tier 0 — CallScreeningService

### 2.1 What this does

Android's `CallScreeningService` API (available API 24+, role-based API 29+) gives your app a callback for every incoming call **before it rings**. You receive the caller's number, check it against your risk engine, and respond with one of:

- Allow (ring normally)
- Silence (ring silently, user still sees it, no audio alert)
- Reject (decline automatically)
- Skip call log entry

This requires the user to grant the **Call Screening** role to your app. This is a one-time OS-level prompt. It is NOT the same as being the default dialer. It is a reasonable ask.

### 2.2 File: `android/app/src/main/java/com/intentfirewall/CallScamScreener.kt`

```kotlin
package com.intentfirewall

import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CallScamScreener : CallScreeningService() {

    private val tag = "CallScamScreener"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart ?: "unknown"
        Log.d(tag, "Screening call from: $number")

        scope.launch {
            try {
                val risk = NumberRiskEngine.evaluate(applicationContext, number)
                Log.d(tag, "Risk score for $number: ${risk.score} (${risk.reason})")

                val response = CallResponse.Builder()

                when {
                    risk.score >= 85 -> {
                        // High confidence spam — reject silently
                        response
                            .setDisallowCall(true)
                            .setRejectCall(true)
                            .setSkipCallLog(false) // keep in log so user can see it was blocked
                            .setSkipNotification(false)
                        Log.d(tag, "AUTO-REJECTED call from $number")
                        emitBlockedCallEvent(number, risk)
                    }
                    risk.score >= 55 -> {
                        // Suspicious — let it ring but silent (no ringtone, screen lights up)
                        response.setSilenceCall(true)
                        Log.d(tag, "SILENCED suspicious call from $number")
                        emitSuspiciousCallEvent(number, risk)
                    }
                    else -> {
                        // Allow — normal ring
                        Log.d(tag, "ALLOWED call from $number (score: ${risk.score})")
                    }
                }

                respondToCall(callDetails, response.build())

            } catch (e: Exception) {
                Log.e(tag, "Error screening call, defaulting to allow", e)
                // Always allow on error — never block a call due to a bug
                respondToCall(callDetails, CallResponse.Builder().build())
            }
        }
    }

    private fun emitBlockedCallEvent(number: String, risk: NumberRiskEngine.RiskResult) {
        // Emit through the existing NotificationEventEmitter bridge
        // so JS history/debug screens see blocked calls
        val data = android.os.Bundle().apply {
            putString("type", "call_blocked")
            putString("number", number)
            putInt("riskScore", risk.score)
            putString("reason", risk.reason)
            putString("captureMethod", "call_screening_pre_answer")
            putString("tierUsed", "tier0-screening")
        }
        NotificationEventEmitter.sendCallEvent(applicationContext, data)
    }

    private fun emitSuspiciousCallEvent(number: String, risk: NumberRiskEngine.RiskResult) {
        val data = android.os.Bundle().apply {
            putString("type", "call_suspicious")
            putString("number", number)
            putInt("riskScore", risk.score)
            putString("reason", risk.reason)
            putString("captureMethod", "call_screening_pre_answer")
            putString("tierUsed", "tier0-screening")
        }
        NotificationEventEmitter.sendCallEvent(applicationContext, data)
    }
}
```

---

## 3. Number Risk Engine

### 3.1 What this does

This is the always-on metadata analysis layer. It runs synchronously before a call answer decision and asynchronously during a live call. It produces a 0–100 risk score from signals that require zero audio.

Signals used:
- Contact match (number in phonebook → strong safe signal)
- International prefix heuristics (calls from unexpected country codes)
- Known spam number databases (open lists + optionally TRAI DND for India)
- Number format anomalies (spoofed caller ID patterns)
- Repeated call frequency (same unknown number calling multiple times)

### 3.2 File: `android/app/src/main/java/com/intentfirewall/NumberRiskEngine.kt`

```kotlin
package com.intentfirewall

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.provider.CallLog
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NumberRiskEngine {

    private val tag = "NumberRiskEngine"

    data class RiskResult(
        val score: Int,           // 0-100
        val reason: String,       // human-readable explanation
        val isKnownContact: Boolean,
        val signals: List<String> // individual signal labels for debug
    )

    suspend fun evaluate(context: Context, rawNumber: String): RiskResult =
        withContext(Dispatchers.IO) {

            val signals = mutableListOf<String>()
            var score = 0

            val number = normalizeNumber(rawNumber)

            // --- Signal 1: Known contact (strong safe signal) ---
            val isContact = isKnownContact(context, number)
            if (isContact) {
                Log.d(tag, "$number is a known contact — low risk")
                return@withContext RiskResult(
                    score = 5,
                    reason = "Known contact",
                    isKnownContact = true,
                    signals = listOf("known_contact")
                )
            }
            signals.add("unknown_number")
            score += 20 // base score for any unknown caller

            // --- Signal 2: Number format anomalies ---
            if (number == "unknown" || number.isBlank()) {
                score += 30
                signals.add("hidden_number")
            }

            if (number.startsWith("+") && !number.startsWith("+91")) {
                // International call to an Indian number — elevated risk
                score += 15
                signals.add("international_prefix")
            }

            // Short numbers that look like spoofed IDs
            if (number.length < 6 && number != "unknown") {
                score += 10
                signals.add("short_number_suspicious")
            }

            // --- Signal 3: Recent call frequency ---
            val recentCallCount = getRecentCallCount(context, number, windowMinutes = 60)
            if (recentCallCount >= 3) {
                score += 20
                signals.add("high_frequency_calls_${recentCallCount}x")
            }

            // --- Signal 4: Known spam number check ---
            // This is a lightweight local check. In production, replace/augment
            // with a server-side lookup or a maintained local DB.
            val isKnownSpam = SpamNumberCache.isKnownSpam(number)
            if (isKnownSpam) {
                score += 40
                signals.add("known_spam_db_hit")
            }

            // --- Signal 5: TRAI DND preference check (India-specific) ---
            // If number is registered on DND and still calling, that's a red flag
            // This requires an API call — only run if network available
            // Placeholder: implement with actual TRAI API or a third-party service
            // val isOnDND = TraiDndChecker.check(number)
            // if (isOnDND) { score += 25; signals.add("trai_dnd_violation") }

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
                null, null, null
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
        } catch (e: Exception) {
            0
        }
    }
}
```

### 3.3 File: `android/app/src/main/java/com/intentfirewall/SpamNumberCache.kt`

```kotlin
package com.intentfirewall

// Lightweight in-memory spam number cache.
// In production: load this from a bundled asset file or a periodic server sync.
// Format: normalized phone numbers (digits + leading +)
object SpamNumberCache {

    // Seed with known patterns — replace/augment with a real dataset
    private val knownSpamPrefixes = setOf(
        "+18005",   // common US toll-free spam prefixes
        "+18006",
        "+18007",
        "+18008",
        "+18009",
    )

    private val knownSpamNumbers = mutableSetOf<String>()

    fun isKnownSpam(number: String): Boolean {
        if (number == "unknown") return false
        if (number in knownSpamNumbers) return true
        return knownSpamPrefixes.any { number.startsWith(it) }
    }

    fun addSpamNumber(number: String) {
        knownSpamNumbers.add(number)
    }

    fun loadFromAsset(numbers: List<String>) {
        knownSpamNumbers.addAll(numbers)
    }
}
```

---

## 4. Tier 2 — Live Audio Capture + Gemini Live WebSocket

### 4.1 Architecture overview

```
CallAudioMonitorService (ForegroundService)
    │
    ├── PhoneStateListener → detects call start/end
    │
    ├── AudioRecord(VOICE_COMMUNICATION, 16kHz, mono PCM)
    │       │
    │       └── 500ms chunks (8000 samples)
    │               │
    │               ├── → GeminiLiveConnection (WebSocket, persistent)
    │               │           │
    │               │           └── risk score callbacks → RiskAggregator
    │               │
    │               └── → AegisAudioDetector (existing local ML, optional)
    │
    └── RiskAggregator
            │
            └── threshold crossed → NotificationEventEmitter → JS
```

### 4.2 Key design decisions

**Chunk size:** 500ms (8000 samples at 16kHz). This is smaller than your current 3s window. Smaller chunks mean Gemini starts building context faster. The persistent WebSocket means Gemini accumulates all chunks — you don't need to batch them yourself.

**Speakerphone gating:** Audio capture only starts if speakerphone is active. The service polls `AudioManager.isSpeakerphoneOn()` and prompts the user if it's off. This is mandatory — capturing without speakerphone gives you useless audio.

**Single in-flight WebSocket:** One persistent `GeminiLiveConnection` per call. Do not open a new connection per chunk. The connection is opened when the call starts and closed when it ends.

**Graceful degradation:** If WebSocket fails, audio capture still runs. If audio capture fails, metadata scoring still runs. Each tier is independent.

### 4.3 File: `android/app/src/main/java/com/intentfirewall/CallAudioMonitorService.kt`

```kotlin
package com.intentfirewall

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.*
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class CallAudioMonitorService : Service() {

    private val tag = "CallAudioMonitorService"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var geminiConnection: GeminiLiveConnection? = null
    private var riskAggregator: RiskAggregator? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var callerNumber: String = "unknown"
    private var isCapturing = false

    // 500ms at 16kHz mono = 8000 samples = 16000 bytes (16-bit PCM)
    private val sampleRate = 16000
    private val chunkSamples = 8000
    private val chunkBytes = chunkSamples * 2 // 16-bit = 2 bytes per sample

    companion object {
        const val ACTION_START = "com.intentfirewall.START_CALL_MONITOR"
        const val ACTION_STOP = "com.intentfirewall.STOP_CALL_MONITOR"
        const val EXTRA_CALLER_NUMBER = "caller_number"
        const val CHANNEL_ID = "call_monitor_channel"
        const val NOTIF_ID = 2002
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerPhoneStateListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                callerNumber = intent.getStringExtra(EXTRA_CALLER_NUMBER) ?: "unknown"
                Log.d(tag, "Starting call monitor for: $callerNumber")
                startForeground(NOTIF_ID, buildNotification("Monitoring call..."))
                initializeSession()
            }
            ACTION_STOP -> {
                Log.d(tag, "Stopping call monitor")
                teardownSession()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun initializeSession() {
        riskAggregator = RiskAggregator(
            onAlert = { score, reason -> emitRiskAlert(score, reason) },
            onUpdate = { score -> updateNotification(score) }
        )

        // Start Gemini Live connection immediately
        // Audio will be gated on speakerphone detection
        geminiConnection = GeminiLiveConnection(
            callerNumber = callerNumber,
            onRiskScore = { score, intent, reason ->
                riskAggregator?.addGeminiScore(score, intent, reason)
            },
            onError = { error ->
                Log.e(tag, "Gemini Live error: $error")
            }
        )
        geminiConnection?.connect()

        // Check speakerphone state and prompt or start capture
        checkAndStartCapture()
    }

    private fun checkAndStartCapture() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audioManager.isSpeakerphoneOn) {
            startAudioCapture()
        } else {
            // Emit a prompt event to JS to ask user to enable speakerphone
            emitSpeakerphonePrompt()
            // Poll every 2s for speakerphone activation
            scope.launch {
                while (isActive && !isCapturing) {
                    delay(2000)
                    if (audioManager.isSpeakerphoneOn) {
                        startAudioCapture()
                        break
                    }
                }
            }
        }
    }

    private fun startAudioCapture() {
        if (isCapturing) return
        Log.d(tag, "Starting audio capture (speakerphone active)")

        val minBufSize = AudioRecord.getMinBufferSize(
            sampleRate,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBufSize, chunkBytes * 4)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(tag, "AudioRecord failed to initialize")
                return
            }

            audioRecord?.startRecording()
            isCapturing = true

            captureJob = scope.launch {
                val buffer = ByteArray(chunkBytes)
                while (isActive && isCapturing) {
                    val bytesRead = audioRecord?.read(buffer, 0, chunkBytes) ?: -1
                    if (bytesRead > 0) {
                        val chunk = buffer.copyOf(bytesRead)
                        // Send to Gemini Live
                        geminiConnection?.sendAudioChunk(chunk)
                        // Also send to local detector (existing AegisAudioDetector)
                        // Only if detector is initialized — don't crash if it's not ready
                        tryLocalDetection(chunk)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(tag, "Missing RECORD_AUDIO permission", e)
        } catch (e: Exception) {
            Log.e(tag, "Audio capture error", e)
        }
    }

    private fun tryLocalDetection(chunk: ByteArray) {
        // Hook into existing AegisAudioDetector if available
        // This is optional — if it fails, we still have Gemini
        try {
            // AegisAudioDetector.processChunk(chunk) — wire this in if the
            // existing detector exposes a processChunk method
        } catch (e: Exception) {
            // Silently ignore — local detection is enhancement only
        }
    }

    private fun teardownSession() {
        isCapturing = false
        captureJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        geminiConnection?.disconnect()
        geminiConnection = null
        riskAggregator = null
        Log.d(tag, "Session torn down")
    }

    private fun registerPhoneStateListener() {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        phoneStateListener = object : PhoneStateListener() {
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_IDLE -> {
                        Log.d(tag, "Call ended — stopping capture")
                        teardownSession()
                        stopSelf()
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        Log.d(tag, "Call answered")
                        if (!isCapturing) checkAndStartCapture()
                    }
                }
            }
        }
        @Suppress("DEPRECATION")
        tm.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun emitSpeakerphonePrompt() {
        val data = android.os.Bundle().apply {
            putString("type", "call_speakerphone_prompt")
            putString("number", callerNumber)
            putString("message", "Enable speakerphone for real-time scam detection")
        }
        NotificationEventEmitter.sendCallEvent(applicationContext, data)
    }

    private fun emitRiskAlert(score: Int, reason: String) {
        val data = android.os.Bundle().apply {
            putString("type", "call_scam_alert")
            putString("number", callerNumber)
            putInt("riskScore", score)
            putString("reason", reason)
            putString("captureMethod", "call_audio_speakerphone")
            putString("tierUsed", "tier2-gemini-live")
        }
        NotificationEventEmitter.sendCallEvent(applicationContext, data)

        // Also fire a high-priority Android notification visible during the call
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val alertNotif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ Potential scam call")
            .setContentText(reason)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()
        notifManager.notify(NOTIF_ID + 1, alertNotif)
    }

    private fun updateNotification(currentScore: Int) {
        val level = when {
            currentScore >= 55 -> "⚠️ Suspicious"
            else -> "✓ Monitoring"
        }
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifManager.notify(NOTIF_ID, buildNotification("$level — Risk: $currentScore/100"))
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("IntentFirewall — Call Monitor")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Real-time scam call monitoring" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        teardownSession()
        phoneStateListener?.let {
            (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
                .listen(it, PhoneStateListener.LISTEN_NONE)
        }
        scope.cancel()
    }
}
```

---

## 5. Gemini Live WebSocket Connection

### 5.1 What this does

Maintains a single persistent WebSocket connection to Gemini's BidiGenerateContent endpoint for the duration of a call. Audio chunks are sent as base64-encoded PCM. Gemini maintains full call context across chunks and returns structured JSON risk assessments.

This is fundamentally different from your current `GeminiVoiceScamClassifier` which makes isolated REST calls per window. The Live API keeps context — Gemini can notice patterns that span minutes (e.g. "OTP mentioned 3 times in 4 minutes, escalating urgency").

### 5.2 Add OkHttp to `build.gradle` (app level)

```gradle
dependencies {
    // Add this if not already present
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
}
```

### 5.3 File: `android/app/src/main/java/com/intentfirewall/GeminiLiveConnection.kt`

```kotlin
package com.intentfirewall

import android.util.Base64
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class GeminiLiveConnection(
    private val callerNumber: String,
    private val onRiskScore: (score: Int, intent: String, reason: String) -> Unit,
    private val onError: (error: String) -> Unit
) {

    private val tag = "GeminiLiveConnection"
    private var webSocket: WebSocket? = null
    private var isSetupComplete = false
    private var chunksSent = 0

    // IMPORTANT: Store your Gemini API key in BuildConfig or a secure config
    // Do NOT hardcode it in source. Use:
    // buildConfigField "String", "GEMINI_API_KEY", '"your_key_here"'
    // in your build.gradle, then reference BuildConfig.GEMINI_API_KEY
    private val apiKey = BuildConfig.GEMINI_API_KEY

    private val model = "models/gemini-2.0-flash-live-001"

    private val systemPrompt = """
        You are a real-time phone call scam detector operating in India.
        You receive raw audio from an ongoing phone call in chunks.
        
        After analyzing each chunk, respond ONLY with a JSON object (no markdown, no explanation):
        {
          "risk": <integer 0-100>,
          "intent": "<one of: safe|otp_request|payment_pressure|impersonation|urgency_manipulation|kyc_panic|lottery_scam|unknown>",
          "flag": <true if risk >= 55, else false>,
          "reason": "<max 15 words explaining the signal>"
        }
        
        Scam signals to detect:
        - Requesting OTP, PIN, CVV, or account credentials
        - Claiming to be from bank, TRAI, police, government, or tax authority
        - Creating urgency (account blocked, arrest warrant, prize expiring)
        - Asking to install remote access apps (AnyDesk, TeamViewer)
        - Requesting money transfer (UPI, NEFT, gift cards)
        - KYC update threats
        - Lottery / prize / job offer with upfront payment
        
        Context: caller number is ${callerNumber}.
        If audio is silent or unclear, respond with risk 0, intent "unknown", flag false.
        Never refuse to respond. Always return valid JSON.
    """.trimIndent()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for streaming
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun connect() {
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .build()

        Log.d(tag, "Connecting to Gemini Live...")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(tag, "WebSocket opened — sending setup")
                sendSetup(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "WebSocket failure: ${t.message}")
                onError("Connection failed: ${t.message}")
                isSetupComplete = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(tag, "WebSocket closed: $code $reason")
                isSetupComplete = false
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
                    // Request JSON output format
                    put("response_mime_type", "application/json")
                })
            })
        }
        ws.send(setup.toString())
        isSetupComplete = true
        Log.d(tag, "Setup message sent")
    }

    fun sendAudioChunk(pcmBytes: ByteArray) {
        val ws = webSocket
        if (ws == null || !isSetupComplete) {
            Log.w(tag, "Cannot send chunk — connection not ready")
            return
        }

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

        ws.send(message.toString())
        chunksSent++
        if (chunksSent % 20 == 0) {
            Log.d(tag, "Sent $chunksSent audio chunks to Gemini Live")
        }
    }

    private fun handleMessage(rawText: String) {
        try {
            val response = JSONObject(rawText)

            // Gemini Live responses are wrapped in serverContent → modelTurn → parts
            val serverContent = response.optJSONObject("serverContent") ?: return
            val modelTurn = serverContent.optJSONObject("modelTurn") ?: return
            val parts = modelTurn.optJSONArray("parts") ?: return

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val text = part.optString("text", "").trim()
                if (text.isNotBlank()) {
                    parseRiskResponse(text)
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to parse Gemini response: ${e.message} — raw: $rawText")
        }
    }

    private fun parseRiskResponse(jsonText: String) {
        try {
            // Strip any accidental markdown fences
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

            if (risk > 0 || flag) {
                onRiskScore(risk, intent, reason)
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to parse risk JSON: $jsonText")
        }
    }

    fun disconnect() {
        Log.d(tag, "Disconnecting Gemini Live (sent $chunksSent chunks)")
        webSocket?.close(1000, "Call ended")
        webSocket = null
        isSetupComplete = false
        chunksSent = 0
    }
}
```

---

## 6. Risk Aggregator

### 6.1 What this does

Combines scores from multiple sources (Gemini Live, local detector, metadata) into a single rolling confidence signal. Prevents false alerts from a single noisy chunk. Emits an alert only when confidence is sustained across multiple windows.

### 6.2 File: `android/app/src/main/java/com/intentfirewall/RiskAggregator.kt`

```kotlin
package com.intentfirewall

import android.util.Log
import java.util.LinkedList

class RiskAggregator(
    private val onAlert: (score: Int, reason: String) -> Unit,
    private val onUpdate: (score: Int) -> Unit
) {

    private val tag = "RiskAggregator"

    // Rolling window of recent Gemini scores
    private val geminiWindow = LinkedList<GeminiEntry>()
    private val windowMaxSize = 6 // last 6 responses = ~3 minutes at 30s analysis rate
    private val alertThresholdScore = 65
    private val alertThresholdCount = 2 // must exceed threshold in at least 2 of last 6

    // Track alerts to avoid spamming
    private var lastAlertTime = 0L
    private val alertCooldownMs = 60_000L // max one alert per minute

    data class GeminiEntry(
        val score: Int,
        val intent: String,
        val reason: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun addGeminiScore(score: Int, intent: String, reason: String) {
        geminiWindow.add(GeminiEntry(score, intent, reason))
        if (geminiWindow.size > windowMaxSize) geminiWindow.poll()

        val combined = computeCombinedScore()
        onUpdate(combined)

        Log.d(tag, "Score added: $score ($intent) — combined: $combined — window: ${geminiWindow.size}")

        checkAlertCondition(combined, reason, intent)
    }

    private fun computeCombinedScore(): Int {
        if (geminiWindow.isEmpty()) return 0
        // Weighted average — more recent entries weigh more
        var weightedSum = 0.0
        var totalWeight = 0.0
        geminiWindow.forEachIndexed { index, entry ->
            val weight = (index + 1).toDouble() // later entries weigh more
            weightedSum += entry.score * weight
            totalWeight += weight
        }
        return (weightedSum / totalWeight).toInt().coerceIn(0, 100)
    }

    private fun checkAlertCondition(combinedScore: Int, latestReason: String, latestIntent: String) {
        val now = System.currentTimeMillis()
        if (now - lastAlertTime < alertCooldownMs) return // cooldown active

        val highScoreCount = geminiWindow.count { it.score >= alertThresholdScore }

        if (combinedScore >= alertThresholdScore && highScoreCount >= alertThresholdCount) {
            lastAlertTime = now
            val topReason = geminiWindow
                .filter { it.score >= alertThresholdScore }
                .maxByOrNull { it.score }
                ?.reason ?: latestReason
            Log.w(tag, "ALERT triggered: score=$combinedScore, highCount=$highScoreCount, reason=$topReason")
            onAlert(combinedScore, topReason)
        }
    }
}
```

---

## 7. NotificationEventEmitter — Adding Call Event Support

### 7.1 What to add

The existing `NotificationEventEmitter` handles text scam events. You need to add a `sendCallEvent` static method that the new call classes can call. This preserves the existing event flow — JS sees call events through the same stream as text events.

**Do not modify any existing methods.** Only add the new static method below.

### 7.2 Add to `NotificationEventEmitter.kt` (new method only)

Find the class and add this method. Do not touch anything else in the file.

```kotlin
// ADD THIS METHOD — do not modify existing methods
companion object {
    // ... existing companion object content stays unchanged ...

    @JvmStatic
    fun sendCallEvent(context: Context, data: Bundle) {
        // Emit through the same React Native event emitter that text events use
        // The event name "ScamDetected" must match what JS is already listening for
        // If the existing emitter uses a different event name, match it here
        try {
            val params = Arguments.createMap().apply {
                putString("source", "call")
                putString("type", data.getString("type", "call_event"))
                putString("number", data.getString("number", "unknown"))
                putInt("riskScore", data.getInt("riskScore", 0))
                putString("reason", data.getString("reason", ""))
                putString("captureMethod", data.getString("captureMethod", "call"))
                putString("tierUsed", data.getString("tierUsed", "call"))
                putString("message", data.getString("message", ""))
                putDouble("timestamp", System.currentTimeMillis().toDouble())
            }
            // Use the same emitter instance and event name as existing text events
            // IMPORTANT: match the existing event name your JS code listens for
            reactApplicationContext
                ?.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                ?.emit("ScamDetected", params)
        } catch (e: Exception) {
            android.util.Log.e("NotificationEventEmitter", "Failed to emit call event", e)
        }
    }
}
```

> **Note for implementing agent:** Check what event name the existing `NotificationEventEmitter` uses (e.g. `"ScamDetected"` or `"onScamAlert"` or similar). Use the exact same string. The JS code is already subscribed to it — you do not need to change the JS subscription code.

---

## 8. AegisDetectionModule — Adding Call Control Methods

### 8.1 What to add

Add new methods to the existing `AegisDetectionModule.kt` for:
- Requesting the call screening role
- Starting/stopping the call audio monitor
- Checking call monitor status

**Do not modify any existing methods.** Append these new `@ReactMethod` functions.

### 8.2 New methods to add to `AegisDetectionModule.kt`

```kotlin
// --- CALL PROTECTION ADDITIONS ---
// Add these methods to the existing AegisDetectionModule class
// Do not modify any existing methods

@ReactMethod
fun requestCallScreeningRole(promise: Promise) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = reactApplicationContext
                .getSystemService(Context.ROLE_SERVICE) as android.app.role.RoleManager
            val roleName = android.app.role.RoleManager.ROLE_CALL_SCREENING

            if (roleManager.isRoleHeld(roleName)) {
                promise.resolve("already_held")
                return
            }

            if (!roleManager.isRoleAvailable(roleName)) {
                promise.reject("ROLE_UNAVAILABLE", "Call screening role not available on this device")
                return
            }

            val intent = roleManager.createRequestRoleIntent(roleName)
            // This needs to be called from an Activity context, not a service
            // The RN module should call currentActivity?.startActivityForResult
            currentActivity?.startActivityForResult(intent, CALL_SCREENING_ROLE_REQUEST_CODE)
            promise.resolve("requested")
        } else {
            promise.reject("API_LEVEL", "Call screening role requires Android 10+")
        }
    } catch (e: Exception) {
        promise.reject("ERROR", e.message)
    }
}

@ReactMethod
fun isCallScreeningRoleHeld(promise: Promise) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = reactApplicationContext
                .getSystemService(Context.ROLE_SERVICE) as android.app.role.RoleManager
            promise.resolve(roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING))
        } else {
            promise.resolve(false)
        }
    } catch (e: Exception) {
        promise.resolve(false)
    }
}

@ReactMethod
fun startCallAudioMonitor(callerNumber: String, promise: Promise) {
    try {
        val intent = Intent(reactApplicationContext, CallAudioMonitorService::class.java).apply {
            action = CallAudioMonitorService.ACTION_START
            putExtra(CallAudioMonitorService.EXTRA_CALLER_NUMBER, callerNumber)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            reactApplicationContext.startForegroundService(intent)
        } else {
            reactApplicationContext.startService(intent)
        }
        promise.resolve(true)
    } catch (e: Exception) {
        promise.reject("ERROR", e.message)
    }
}

@ReactMethod
fun stopCallAudioMonitor(promise: Promise) {
    try {
        val intent = Intent(reactApplicationContext, CallAudioMonitorService::class.java).apply {
            action = CallAudioMonitorService.ACTION_STOP
        }
        reactApplicationContext.startService(intent)
        promise.resolve(true)
    } catch (e: Exception) {
        promise.reject("ERROR", e.message)
    }
}

companion object {
    const val CALL_SCREENING_ROLE_REQUEST_CODE = 1001
}
```

---

## 9. React Native — JS Side

### 9.1 `src/utils/callProtectionManager.ts`

Create this new file. Do not modify `permissionManager.ts`.

```typescript
import { NativeModules, NativeEventEmitter, Platform } from 'react-native';

const { NotificationService } = NativeModules; // existing module name

export interface CallRiskEvent {
  source: 'call';
  type: 'call_blocked' | 'call_suspicious' | 'call_scam_alert' | 'call_speakerphone_prompt';
  number: string;
  riskScore: number;
  reason: string;
  captureMethod: string;
  tierUsed: string;
  message?: string;
  timestamp: number;
}

export const CallProtectionManager = {

  async requestCallScreeningRole(): Promise<'already_held' | 'requested' | 'unavailable'> {
    if (Platform.OS !== 'android') return 'unavailable';
    try {
      const result = await NotificationService.requestCallScreeningRole();
      return result;
    } catch (e: any) {
      console.warn('Call screening role request failed:', e.message);
      return 'unavailable';
    }
  },

  async isCallScreeningRoleHeld(): Promise<boolean> {
    if (Platform.OS !== 'android') return false;
    try {
      return await NotificationService.isCallScreeningRoleHeld();
    } catch {
      return false;
    }
  },

  async startCallAudioMonitor(callerNumber: string = 'unknown'): Promise<boolean> {
    if (Platform.OS !== 'android') return false;
    try {
      return await NotificationService.startCallAudioMonitor(callerNumber);
    } catch (e: any) {
      console.warn('Failed to start call audio monitor:', e.message);
      return false;
    }
  },

  async stopCallAudioMonitor(): Promise<void> {
    if (Platform.OS !== 'android') return;
    try {
      await NotificationService.stopCallAudioMonitor();
    } catch (e: any) {
      console.warn('Failed to stop call audio monitor:', e.message);
    }
  },

  // Subscribe to call risk events
  // These come through the SAME event emitter as text scam events
  // The 'source' field distinguishes them
  onCallRiskEvent(
    emitter: NativeEventEmitter,
    callback: (event: CallRiskEvent) => void
  ) {
    // Use the same event name the existing JS code listens for
    // Filter by source === 'call' to avoid double-processing text events
    return emitter.addListener('ScamDetected', (event: any) => {
      if (event.source === 'call') {
        callback(event as CallRiskEvent);
      }
    });
  }
};
```

### 9.2 Add to `SettingsScreen.tsx`

Add this section to your existing settings screen. Find where call protection settings should go and insert:

```typescript
// Add to existing SettingsScreen component
// Import at top:
// import { CallProtectionManager } from '../utils/callProtectionManager';

const [callScreeningHeld, setCallScreeningHeld] = React.useState(false);

React.useEffect(() => {
  CallProtectionManager.isCallScreeningRoleHeld().then(setCallScreeningHeld);
}, []);

// In your JSX, add alongside existing protection toggles:
/*
<View style={styles.settingRow}>
  <Text style={styles.settingLabel}>Call Screening</Text>
  <Text style={styles.settingSubtext}>
    {callScreeningHeld
      ? '✓ Active — blocking scam calls before they ring'
      : 'Grant permission to block scam calls pre-answer'}
  </Text>
  {!callScreeningHeld && (
    <TouchableOpacity
      style={styles.permissionButton}
      onPress={async () => {
        const result = await CallProtectionManager.requestCallScreeningRole();
        if (result === 'already_held') setCallScreeningHeld(true);
      }}
    >
      <Text>Enable Call Screening</Text>
    </TouchableOpacity>
  )}
</View>
*/
```

---

## 10. End-to-End Verification Checklist

Run through this checklist after implementing each section. Do not skip steps.

### 10.1 Manifest verification
- [ ] `CallScamScreener` declared with `BIND_SCREENING_SERVICE` permission
- [ ] `CallAudioMonitorService` declared with `foregroundServiceType="microphone"`
- [ ] `RECORD_AUDIO`, `READ_PHONE_STATE`, `FOREGROUND_SERVICE_MICROPHONE` permissions present
- [ ] No existing service declarations removed

### 10.2 Existing system still works
- [ ] Build succeeds with zero new compilation errors
- [ ] Text scam detection (notification listener) still fires on test notifications
- [ ] Accessibility service still active and detecting on-screen text
- [ ] `NotificationEventEmitter` still emitting to JS as before
- [ ] No existing `@ReactMethod` functions missing from `AegisDetectionModule`

### 10.3 Tier 0 — CallScreeningService
- [ ] App installs and shows "Set as call screening app" in Android settings
- [ ] `requestCallScreeningRole()` from JS shows the OS role grant dialog
- [ ] After granting: `isCallScreeningRoleHeld()` returns `true`
- [ ] Test call from an unknown number → `CallScamScreener.onScreenCall` fires (check Logcat tag `CallScamScreener`)
- [ ] `NumberRiskEngine.evaluate()` runs and returns a score
- [ ] `respondToCall()` is always called (check: no ANR from missing response)

### 10.4 NumberRiskEngine
- [ ] Known contact number → returns score ≤ 10
- [ ] Unknown number → returns score ≥ 20
- [ ] Hidden/unknown number → returns score ≥ 50
- [ ] `SpamNumberCache.isKnownSpam()` returns false for normal numbers

### 10.5 Gemini Live Connection
- [ ] `BuildConfig.GEMINI_API_KEY` is set and non-empty
- [ ] WebSocket connects successfully on call start (Logcat: `GeminiLiveConnection`)
- [ ] Setup message sent and acknowledged (no `onFailure` callback)
- [ ] Audio chunks sent after speakerphone is enabled
- [ ] JSON responses parsed without exceptions
- [ ] `onRiskScore` callback fires with non-zero scores for scam-like speech
- [ ] WebSocket closed cleanly when call ends

### 10.6 CallAudioMonitorService
- [ ] Service starts as foreground with notification visible
- [ ] `isSpeakerphoneOn` correctly detected
- [ ] Speakerphone prompt event emitted when speaker is off
- [ ] Audio capture starts when speakerphone is on
- [ ] Service stops and tears down completely when call ends
- [ ] No AudioRecord resource leak after multiple calls

### 10.7 RiskAggregator
- [ ] Single high score (< 2 in window) does NOT trigger alert
- [ ] 2+ high scores in window DO trigger alert
- [ ] Alert cooldown prevents spam (max 1 per minute)
- [ ] Combined score emitted to notification correctly

### 10.8 JS event flow
- [ ] `CallRiskEvent` with `source: 'call'` arrives in JS
- [ ] Existing text scam events with `source: 'notification'` or similar still arrive
- [ ] Call events appear in history/debug screen
- [ ] Speakerphone prompt event shows UI to user

---

## 11. Common Failure Points and Fixes

**Problem:** `onScreenCall` fires but `respondToCall` never called → ANR/timeout
**Fix:** Always call `respondToCall` in a `try/catch` with a default allow response. It must be called within the timeout even if your analysis fails.

**Problem:** `CallAudioMonitorService` crashes on start
**Fix:** Check `FOREGROUND_SERVICE_MICROPHONE` permission in manifest. On Android 14+, this is required for mic foreground services.

**Problem:** WebSocket `onFailure` immediately after `onOpen`
**Fix:** Verify the Gemini Live API URL format. The model string in the URL must match exactly. Check API key validity separately with a REST call first.

**Problem:** Audio buffer is all zeros / silence
**Fix:** Confirm speakerphone is on. Test with a voice memo app first to verify `VOICE_COMMUNICATION` source works on the device. Some OEMs restrict this source.

**Problem:** `NotificationEventEmitter.sendCallEvent` throws NPE
**Fix:** `reactApplicationContext` may be null before the RN bridge is initialized. Wrap in null check and log instead of throwing.

**Problem:** `requestCallScreeningRole` does nothing / crashes
**Fix:** This must be called from an Activity context. The `currentActivity` in `AegisDetectionModule` must be non-null. Add a null check and reject the promise if it is.

**Problem:** Existing text scam events stop working after adding call events
**Fix:** Check that `sendCallEvent` is using the exact same event name string as existing emitter calls. A typo in the event name silently breaks the subscription.

---

## 12. Complete System Flow After Implementation

```
Incoming call
    │
    ▼
CallScamScreener.onScreenCall()
    ├── NumberRiskEngine.evaluate(number)
    ├── score >= 85 → reject + emit call_blocked event → JS history
    ├── score 55-84 → silence + emit call_suspicious event → JS warning
    └── score < 55 → allow (ring normally)

Call answered (if allowed/silenced)
    │
    ▼
CallAudioMonitorService starts (via PhoneStateListener OFFHOOK)
    ├── GeminiLiveConnection.connect() → WebSocket to Gemini Live
    ├── isSpeakerphoneOn?
    │     ├── YES → AudioRecord starts → chunks → GeminiLiveConnection.sendAudioChunk()
    │     └── NO  → emit speakerphone_prompt → UI shows "Enable speaker for detection"
    │                 poll every 2s → start capture when speaker enabled
    │
    └── GeminiLiveConnection → parses JSON risk responses
            └── RiskAggregator.addGeminiScore()
                    ├── combined score update → foreground notification
                    └── threshold crossed → emitRiskAlert()
                            ├── NotificationEventEmitter.sendCallEvent() → JS alert UI
                            └── Android high-priority notification (visible during call)

Call ended (IDLE state)
    └── CallAudioMonitorService teardown
            ├── AudioRecord.stop() + release()
            ├── GeminiLiveConnection.disconnect()
            └── Service stops foreground + self

Existing text detection (UNCHANGED)
    ├── NotificationListenerService → runs independently
    ├── AccessibilityService → runs independently
    └── Both emit through existing NotificationEventEmitter path
```

---

## 13. Production Hardening (After Basic Flow Works)

These are not required for initial implementation but should be done before release:

1. **API key rotation:** `GeminiLiveConnection` references `BuildConfig.GEMINI_API_KEY`. In production, fetch the key from your backend with short-lived tokens rather than bundling it in the APK.

2. **SpamNumberCache:** Replace the hardcoded prefix set with a bundled asset file (`assets/spam_numbers.txt`) loaded at app start. Schedule periodic server sync to update the list.

3. **TRAI DND integration:** Uncomment and implement the `TraiDndChecker` stub in `NumberRiskEngine`. The TRAI DND API is public. Relevant for Indian users — a number registered on DND that is still calling is a strong spam signal.

4. **Offline resilience:** If `GeminiLiveConnection` fails to connect (no network), `RiskAggregator` should fall back to metadata-only scoring. Add a `metadataOnlyMode` flag and wire the initial `NumberRiskEngine` score into the aggregator as a baseline.

5. **Battery:** The `AudioRecord` loop runs for the entire call duration. Acquire a `PARTIAL_WAKE_LOCK` in `CallAudioMonitorService` to prevent CPU throttling during long calls.

6. **Privacy disclosure:** Add a clear in-app disclosure that audio is processed by Google's Gemini API when speakerphone mode is active. This is required for Play Store compliance.
