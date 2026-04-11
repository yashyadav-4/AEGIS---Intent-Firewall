package com.intentfirewall

// Gradle dependencies:
// implementation 'androidx.core:core-ktx:1.12.0'
// implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'

// AndroidManifest permissions:
// uses-permission android:name="android.permission.RECORD_AUDIO"
// uses-permission android:name="android.permission.READ_PHONE_STATE"
// uses-permission android:name="android.permission.FOREGROUND_SERVICE"
// uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"
// uses-permission android:name="android.permission.WAKE_LOCK"
//
// Foreground service declaration (Android 12+):
// <service
//     android:name=".audio.AegisCallMonitor"
//     android:foregroundServiceType="microphone" />

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AegisCallMonitor : Service() {

    companion object {
        private const val TAG = "AegisCallMonitor"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 3200
        private const val WINDOW_SAMPLES = 48000 // 3 seconds @ 16kHz
        private const val OVERLAP_SAMPLES = 0
        private const val FLAT_PITCH_THRESHOLD = 0.15f
        private const val CHANNEL_ID = "aegis_protection"
        private const val ALERT_CHANNEL_ID = "aegis_voice_alerts"
        private const val NOTIFICATION_ID = 1001
        private const val VOICE_ALERT_NOTIFICATION_ID = 1002
        private const val GEMINI_WINDOW_INTERVAL_MS = 3500L
        private const val LOCAL_ANALYZE_INTERVAL_MS = 2200L
        private const val ALERT_COOLDOWN_MS = 10_000L
        private const val MAX_PENDING_CHUNKS = 10
        const val ACTION_START = "com.aegis.START_MONITOR"
        const val ACTION_STOP = "com.aegis.STOP_MONITOR"
        const val ACTION_VOICE_SCAM_INTENT = "com.aegis.VOICE_SCAM_INTENT"

        @Volatile
        var isServiceRunning: Boolean = false
        @Volatile
        var lastTier3Status: String = "idle"
        @Volatile
        var lastTier3AtMs: Long = 0L
        @Volatile
        var lastCaptureStatus: String = "idle"
        @Volatile
        var lastCaptureAtMs: Long = 0L
        @Volatile
        var monitorArmed: Boolean = false
        @Volatile
        var inCallDetected: Boolean = false
        @Volatile
        var audioCaptureRunning: Boolean = false
    }

    private var audioRecord: AudioRecord? = null
    private var detector: AegisAudioDetector? = null
    private val accumulator = FloatArray(WINDOW_SAMPLES)
    private var accumulatorPos = 0
    private var isRecording = false
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var geminiVoiceInFlight = false
    @Volatile
    private var localAnalyzeInFlight = false
    private var lastGeminiVoiceAtMs = 0L
    private var lastLocalAnalyzeAtMs = 0L
    private var lastVoiceAlertAtMs = 0L
    @Volatile
    private var lastLocalSyntheticScore = 0f
    @Volatile
    private var lastCallerUnknown = true

    private data class VoiceChunk(
        val waveform: FloatArray,
        val pitchVar: Float,
        val zcr: Float,
        val enqueuedAt: Long,
    )

    private enum class VoiceDecision {
        ALERT_NOW,
        MONITOR,
        SAFE,
    }

    private val chunkQueue = ArrayDeque<VoiceChunk>()
    private var queueWorkerRunning = false
    private val recentGeminiResults = ArrayDeque<GeminiVoiceDecision>()

    private var telephonyManager: TelephonyManager? = null
    private var audioManager: AudioManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var monitorEnabled = false
    private var isInCall = false
    @Volatile
    private var callStatePollerRunning = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        detector = try {
            AegisAudioDetector(this)
        } catch (e: Exception) {
            Log.w(TAG, "Local audio detector disabled (missing/corrupt model assets): ${e.message}")
            null
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:AegisCallMonitor").apply {
            setReferenceCounted(false)
        }

        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                handleCallStateChanged(state, incomingNumber)
            }
        }

        try {
            @Suppress("DEPRECATION")
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (se: SecurityException) {
            lastCaptureStatus = "read_phone_state_missing"
            Log.w(TAG, "READ_PHONE_STATE not granted; call-state monitoring disabled", se)
        } catch (e: Exception) {
            lastCaptureStatus = "call_state_listener_failed:${e.javaClass.simpleName}"
            Log.e(TAG, "Failed to register phone state listener", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isServiceRunning = true
        startForeground(NOTIFICATION_ID, buildNotification())

        when (intent?.action) {
            ACTION_START -> {
                updateCallerMetadataFromIntent(intent)
                monitorEnabled = true
                monitorArmed = true
                isInCall = isVoiceSessionActive()
                inCallDetected = isInCall
                ensureCallStatePoller()
                if (isInCall) {
                    startCapture()
                } else {
                    lastCaptureStatus = "waiting_for_call"
                    Log.i(TAG, "Call protection armed; waiting for active call")
                }
            }
            ACTION_STOP -> {
                monitorEnabled = false
                monitorArmed = false
                isInCall = false
                inCallDetected = false
                stopCapture()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                if (monitorEnabled && isInCall && !isRecording) startCapture()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        detector?.close()
        detector = null
        isServiceRunning = false

        try {
            @Suppress("DEPRECATION")
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        } catch (_: Exception) {
        }
        phoneStateListener = null
        telephonyManager = null
        audioManager = null

        serviceScope.cancel()

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Aegis Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)

            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Aegis Voice Scam Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                setShowBadge(true)
            }
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aegis Active")
            .setContentText("Monitoring call for safety by Aegis")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun handleCallStateChanged(state: Int, incomingNumber: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_IDLE -> {
                isInCall = isVoiceSessionActive()
                inCallDetected = false
                lastCaptureStatus = "waiting_for_call"
                if (!isInCall) {
                    stopCapture()
                }
            }

            TelephonyManager.CALL_STATE_RINGING -> {
                inCallDetected = true
                lastCaptureStatus = "ringing_waiting_answer"
            }

            TelephonyManager.CALL_STATE_OFFHOOK -> {
                isInCall = true
                inCallDetected = true
                val isUnknown = incomingNumber.isNullOrEmpty()
                detector?.onCallMetadataUpdate(
                    isUnknownNumber = isUnknown,
                    isVideoCall = false
                )
                if (monitorEnabled) {
                    startCapture()
                } else {
                    lastCaptureStatus = "monitor_disabled"
                }
            }
        }
    }

    private fun ensureCallStatePoller() {
        if (callStatePollerRunning) return
        callStatePollerRunning = true

        serviceScope.launch(Dispatchers.Default) {
            while (isActive && monitorEnabled) {
                try {
                    val activeVoiceSession = isVoiceSessionActive()
                    if (activeVoiceSession) {
                        isInCall = true
                        if (!isRecording) {
                            startCapture()
                        }
                    } else {
                        isInCall = false
                        if (isRecording) {
                            stopCapture()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Call-state poller check failed: ${e.message}")
                }

                delay(1500L)
            }

            callStatePollerRunning = false
        }
    }

    private fun startCapture() {
        if (isRecording) return
        if (!monitorEnabled) {
            lastCaptureStatus = "monitor_disabled"
            return
        }
        if (!isInCall && !isVoiceSessionActive()) {
            lastCaptureStatus = "waiting_for_call"
            return
        }
        isInCall = true

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            lastCaptureStatus = "record_audio_missing"
            Log.w(TAG, "RECORD_AUDIO permission not granted; capture not started")
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            lastCaptureStatus = "audio_min_buffer_failed"
            Log.e(TAG, "AudioRecord min buffer size query failed")
            return
        }

        val bufferSize = minBuffer * 2

        val newRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            lastCaptureStatus = "audio_record_init_exception:${e.javaClass.simpleName}"
            Log.e(TAG, "AudioRecord initialization failed", e)
            return
        }

        if (newRecord.state != AudioRecord.STATE_INITIALIZED) {
            lastCaptureStatus = "audio_record_not_initialized"
            Log.e(TAG, "AudioRecord not initialized")
            newRecord.release()
            return
        }

        try {
            newRecord.startRecording()
        } catch (e: IllegalStateException) {
            lastCaptureStatus = "audio_record_start_failed"
            Log.e(TAG, "Failed to start recording", e)
            newRecord.release()
            return
        }

        audioRecord = newRecord
        isRecording = true
        audioCaptureRunning = true
        lastCaptureAtMs = System.currentTimeMillis()
        lastCaptureStatus = "capturing"

        if (wakeLock?.isHeld != true) {
            wakeLock?.acquire()
        }

        ensureQueueWorker()

        serviceScope.launch(Dispatchers.IO) {
            val buffer = ShortArray(CHUNK_SAMPLES)

            while (isRecording && isActive) {
                val read = try {
                    audioRecord?.read(buffer, 0, CHUNK_SAMPLES) ?: break
                } catch (e: Exception) {
                    lastCaptureStatus = "audio_read_failed:${e.javaClass.simpleName}"
                    Log.e(TAG, "AudioRecord read failed", e)
                    break
                }

                if (read <= 0) continue

                for (i in 0 until read) {
                    val sample = buffer[i].toFloat() / 32768f
                    if (accumulatorPos < WINDOW_SAMPLES) {
                        accumulator[accumulatorPos++] = sample
                    }
                }

                if (accumulatorPos >= WINDOW_SAMPLES) {
                    val window = accumulator.copyOf(WINDOW_SAMPLES)

                    val pitchVar = computePitchVariance(window)
                    val zcr = computeZeroCrossingRate(window)
                    val rms = computeRms(window)
                    val spectralGap = computeSpectralGapPlaceholder(window)
                    
                    // Simple multi-signal gating logic
                    val zcrTrigger = zcr > 0.15f
                    val gapTrigger = spectralGap > 0.8f
                    val combinedSpectralScore = if (pitchVar < FLAT_PITCH_THRESHOLD || zcrTrigger || gapTrigger) 0.0f else 1.0f
                    val speechActive = rms >= 0.0035f

                    detector?.onSpectralEnergyUpdate(combinedSpectralScore)

                    val now = SystemClock.elapsedRealtime()
                    val canRunLocal = !localAnalyzeInFlight && (now - lastLocalAnalyzeAtMs >= LOCAL_ANALYZE_INTERVAL_MS)
                    if (speechActive && detector?.shouldActivate() == true && canRunLocal) {
                        localAnalyzeInFlight = true
                        lastLocalAnalyzeAtMs = now
                        serviceScope.launch(Dispatchers.Default) {
                            try {
                                Log.d("IntentFirewall", "Starting audio detection analysis")
                                val result = detector?.analyze(window)
                                result?.let { 
                                    onDetectionResult(it) 
                                    Log.d("IntentFirewall", "Audio detection complete: synthetic=${it.isSynthetic} conf=${it.confidence}")
                                }
                            } catch (e: Exception) {
                                Log.e("IntentFirewall", "Audio detection failed: ${e.message}", e)
                            } finally {
                                localAnalyzeInFlight = false
                            }
                        }
                    }

                    if (speechActive) {
                        enqueueChunk(window, pitchVar, zcr)
                    } else {
                        lastTier3Status = "speech_inactive_skip"
                    }

                    if (OVERLAP_SAMPLES > 0) {
                        System.arraycopy(
                            accumulator,
                            OVERLAP_SAMPLES,
                            accumulator,
                            0,
                            WINDOW_SAMPLES - OVERLAP_SAMPLES
                        )
                        accumulatorPos = WINDOW_SAMPLES - OVERLAP_SAMPLES
                    } else {
                        accumulatorPos = 0
                    }
                }
            }

            stopCapture()
        }
    }

    private fun stopCapture() {
        isRecording = false
        audioCaptureRunning = false

        val record = audioRecord
        audioRecord = null

        if (record != null) {
            try {
                record.stop()
            } catch (_: IllegalStateException) {
            }
            record.release()
        }

        accumulatorPos = 0
        synchronized(chunkQueue) {
            chunkQueue.clear()
        }
        synchronized(recentGeminiResults) {
            recentGeminiResults.clear()
        }

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    private fun enqueueChunk(window: FloatArray, pitchVar: Float, zcr: Float) {
        val chunk = VoiceChunk(
            waveform = window.copyOf(),
            pitchVar = pitchVar,
            zcr = zcr,
            enqueuedAt = System.currentTimeMillis(),
        )

        synchronized(chunkQueue) {
            if (chunkQueue.size >= MAX_PENDING_CHUNKS) {
                chunkQueue.removeFirstOrNull()
                lastTier3Status = "queue_overflow_drop_oldest"
            }
            chunkQueue.addLast(chunk)
        }
    }

    private fun ensureQueueWorker() {
        if (queueWorkerRunning) return
        queueWorkerRunning = true

        serviceScope.launch(Dispatchers.IO) {
            while (isActive && isRecording) {
                val now = SystemClock.elapsedRealtime()
                if (geminiVoiceInFlight || now - lastGeminiVoiceAtMs < GEMINI_WINDOW_INTERVAL_MS) {
                    delay(120L)
                    continue
                }

                val chunk = synchronized(chunkQueue) {
                    chunkQueue.removeFirstOrNull()
                }

                if (chunk == null) {
                    delay(120L)
                    continue
                }

                maybeRunTier3VoiceIntent(chunk.waveform, chunk.pitchVar, chunk.zcr)
            }
            queueWorkerRunning = false
        }
    }

    private fun evaluateRollingDecision(decision: GeminiVoiceDecision): VoiceDecision {
        synchronized(recentGeminiResults) {
            recentGeminiResults.addLast(decision)
            while (recentGeminiResults.size > 5) {
                recentGeminiResults.removeFirst()
            }

            val scamCount = recentGeminiResults.count {
                it.isScam || it.action == "alert"
            }

            val highConfidenceScam = (decision.isScam || decision.action == "alert") && decision.confidence >= 0.85f
            return when {
                highConfidenceScam -> VoiceDecision.ALERT_NOW
                scamCount >= 2 -> VoiceDecision.ALERT_NOW
                scamCount == 1 -> VoiceDecision.MONITOR
                else -> VoiceDecision.SAFE
            }
        }
    }

    private fun computePitchVariance(window: FloatArray): Float {
        if (window.isEmpty()) return 0f

        val frameSize = CHUNK_SAMPLES
        val frames = WINDOW_SAMPLES / frameSize
        if (frames <= 0) return 0f

        val energies = FloatArray(frames)
        for (frame in 0 until frames) {
            val start = frame * frameSize
            var sum = 0f
            for (i in 0 until frameSize) {
                val idx = start + i
                if (idx >= window.size) break
                val s = window[idx]
                sum += s * s
            }
            energies[frame] = sum / frameSize
        }

        var mean = 0f
        for (e in energies) {
            mean += e
        }
        mean /= energies.size

        if (mean <= 1e-8f) return FLAT_PITCH_THRESHOLD

        var variance = 0f
        for (e in energies) {
            val d = e - mean
            variance += d * d
        }
        variance /= energies.size

        return variance / (mean * mean)
    }

    private fun computeZeroCrossingRate(window: FloatArray): Float {
        if (window.isEmpty()) return 0f
        var crossings = 0
        for (i in 1 until window.size) {
            if ((window[i] > 0 && window[i - 1] <= 0) || (window[i] <= 0 && window[i - 1] > 0)) {
                crossings++
            }
        }
        return crossings.toFloat() / window.size
    }

    private fun computeSpectralGapPlaceholder(window: FloatArray): Float {
        // Placeholder for advanced multi-signal logic (e.g. evaluating high-frequency spectral artifacts)
        return 0.1f // Default safe value
    }

    private fun computeRms(window: FloatArray): Float {
        if (window.isEmpty()) return 0f
        var sum = 0f
        for (v in window) {
            sum += v * v
        }
        return kotlin.math.sqrt(sum / window.size)
    }

    private fun maybeRunTier3VoiceIntent(window: FloatArray, pitchVar: Float, zcr: Float) {
        val now = SystemClock.elapsedRealtime()
        if (geminiVoiceInFlight) return
        if (now - lastGeminiVoiceAtMs < GEMINI_WINDOW_INTERVAL_MS) return

        geminiVoiceInFlight = true
        lastGeminiVoiceAtMs = now
        lastTier3AtMs = System.currentTimeMillis()
        lastTier3Status = "request_started"

        serviceScope.launch(Dispatchers.IO) {
            try {
                val decision = GeminiVoiceScamClassifier.analyzeLiveWindow(
                    context = this@AegisCallMonitor,
                    waveform = window,
                    callerUnknown = lastCallerUnknown,
                    pitchVariance = pitchVar,
                    zcr = zcr,
                    localSyntheticScore = lastLocalSyntheticScore,
                )

                if (decision != null) {
                        val rollingDecision = evaluateRollingDecision(decision)
                        NotificationEventEmitter.sendNotification(
                            context = this@AegisCallMonitor,
                            appName = "Call Monitor",
                            title = "Live voice analysis",
                            text = decision.reason,
                            packageName = packageName,
                            flagged = rollingDecision == VoiceDecision.ALERT_NOW,
                            matchedCategory = decision.intent,
                            conversationContext = "",
                            confidence = decision.confidence,
                            sender = "Live Call",
                            appSource = "call",
                            captureMethod = "call_audio",
                            eventTimestamp = System.currentTimeMillis(),
                            tierUsed = "tier3-voice",
                            tier1Score = 0.0f,
                            tier1Decision = "DISABLED",
                            tier1Category = "DISABLED",
                            tier3Reason = decision.reason,
                            tier3Model = decision.model,
                            tier3KeyIndex = decision.keyIndex,
                        )

                    val intent = Intent(ACTION_VOICE_SCAM_INTENT).apply {
                        putExtra("isScam", decision.isScam)
                        putExtra("confidence", decision.confidence)
                        putExtra("intent", decision.intent)
                        putExtra("reason", decision.reason)
                        putExtra("action", decision.action)
                        putExtra("model", decision.model)
                        putExtra("keyIndex", decision.keyIndex)
                    }
                    LocalBroadcastManager.getInstance(this@AegisCallMonitor).sendBroadcast(intent)

                    when (rollingDecision) {
                        VoiceDecision.ALERT_NOW -> {
                            lastTier3Status = "alert:${decision.intent}:${decision.confidence}"
                            maybeNotifyVoiceScam(decision)
                        }
                        VoiceDecision.MONITOR -> {
                            lastTier3Status = "monitor:${decision.intent}:${decision.confidence}"
                        }
                        VoiceDecision.SAFE -> {
                            lastTier3Status = "safe:${decision.intent}:${decision.confidence}"
                        }
                    }

                    if (rollingDecision == VoiceDecision.ALERT_NOW) {
                        Log.w(
                            TAG,
                            "Tier3 voice alert intent=${decision.intent} confidence=${decision.confidence} reason=${decision.reason}"
                        )
                    } else {
                        Log.d(
                            TAG,
                            "Tier3 voice safe intent=${decision.intent} confidence=${decision.confidence}"
                        )
                    }
                } else {
                    lastTier3Status = "no_decision:${GeminiVoiceScamClassifier.lastRunStatus}"
                }
            } catch (e: Exception) {
                lastTier3Status = "request_failed:${e.javaClass.simpleName}"
                Log.e(TAG, "Tier3 voice intent analysis failed: ${e.message}", e)
            } finally {
                geminiVoiceInFlight = false
            }
        }
    }

    private fun isVoiceSessionActive(): Boolean {
        val telephonyState = telephonyManager?.callState ?: TelephonyManager.CALL_STATE_IDLE
        if (telephonyState == TelephonyManager.CALL_STATE_OFFHOOK) {
            return true
        }

        val mode = audioManager?.mode ?: AudioManager.MODE_NORMAL
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun updateCallerMetadataFromIntent(intent: Intent?) {
        lastCallerUnknown = intent?.getBooleanExtra("caller_unknown", true) ?: true
    }

    private fun maybeNotifyVoiceScam(decision: GeminiVoiceDecision) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastVoiceAlertAtMs < ALERT_COOLDOWN_MS) return
        lastVoiceAlertAtMs = now

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val title = "Live call scam risk detected"
        val body = "Intent: ${decision.intent} (${(decision.confidence * 100).toInt()}%)"

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$body\n${decision.reason}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()

        manager.notify(VOICE_ALERT_NOTIFICATION_ID, notification)
    }

    private fun onDetectionResult(result: DetectionResult) {
        lastLocalSyntheticScore = result.confidence
        if (result.isSynthetic) {
            Log.w(TAG, "DEEPFAKE DETECTED confidence=${result.confidence}")

            val intent = Intent("com.aegis.DEEPFAKE_DETECTED").apply {
                putExtra("confidence", result.confidence)
                putExtra("phaseScore", result.phaseScore)
                putExtra("glottalScore", result.glottalScore)
                putExtra("wavlmScore", result.wavlmScore)
                putExtra("latencyMs", result.latencyMs)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        } else {
            Log.i(TAG, "Voice verified real confidence=${1f - result.confidence}")
        }
    }
}
