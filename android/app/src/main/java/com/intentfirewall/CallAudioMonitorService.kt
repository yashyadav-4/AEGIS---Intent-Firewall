package com.intentfirewall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyCallback
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CallAudioMonitorService : Service() {

    private val tag = "CallAudioMonitorService"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var geminiRestAnalyzer: GeminiRestAnalyzer? = null
    private var riskAggregator: RiskAggregator? = null
    private var telephonyManager: TelephonyManager? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var callerNumber: String = "unknown"
    private var isMonitorEnabled = false
    private var isSessionActive = false
    private var isCapturing = false
    private var speakerPollJob: Job? = null
    private var callStatePollJob: Job? = null
    private var incomingEventEmitted = false
    private var metadataEvaluatedForCurrentCall = false
    private var hasObservedCallState = false
    private var currentCallState = TelephonyManager.CALL_STATE_IDLE
    private var currentRiskScore = 0
    private var captureChunkCount = 0
    private var activeAudioSource: Int? = null
    private var zeroRmsStreak = 0
    private var isSwitchingSource = false
    private var chunksSentForSession = 0
    private var responsesForSession = 0
    private var geminiReady = false

    private val sampleRate = 16000
    private val chunkSamples = 8000
    private val chunkBytes = chunkSamples * 2
    private val minRmsForGeminiSend = 10

    companion object {
        const val ACTION_START = "com.intentfirewall.START_CALL_MONITOR"
        const val ACTION_STOP = "com.intentfirewall.STOP_CALL_MONITOR"
        const val ACTION_HINT_STATE = "com.intentfirewall.HINT_CALL_STATE"
        const val EXTRA_CALLER_NUMBER = "caller_number"
        const val EXTRA_HINT_STATE = "hint_state"
        const val EXTRA_HINT_SOURCE = "hint_source"
        const val CHANNEL_ID = "call_monitor_channel"
        const val NOTIF_ID = 2002

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        @Volatile
        var isAudioCaptureRunning: Boolean = false
            private set

        @Volatile
        var totalGeminiChunksSent: Int = 0
            private set

        @Volatile
        var totalGeminiResponses: Int = 0
            private set

        @Volatile
        var lastGeminiChunkAtMs: Long = 0L
            private set

        @Volatile
        var lastGeminiResponseAtMs: Long = 0L
            private set

        @Volatile
        var lastGeminiStatus: String = "idle"
            private set
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()
        registerCallStateListeners()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                callerNumber = intent.getStringExtra(EXTRA_CALLER_NUMBER) ?: "unknown"
                isMonitorEnabled = true
                hasObservedCallState = false
                lastGeminiStatus = "armed"
                Log.d(tag, "Arming call monitor for: $callerNumber")
                startForeground(NOTIF_ID, buildNotification("Armed - waiting for call"))
                startCallStatePollerIfNeeded()
                if (isCurrentlyOffHook()) {
                    Log.d(tag, "Call already active while arming, starting session")
                    startSessionIfNeeded()
                }
            }

            ACTION_STOP -> {
                Log.d(tag, "Stopping call monitor")
                isMonitorEnabled = false
                callStatePollJob?.cancel()
                callStatePollJob = null
                teardownSession()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_HINT_STATE -> {
                val hintedNumber = intent.getStringExtra(EXTRA_CALLER_NUMBER)
                if (!hintedNumber.isNullOrBlank()) {
                    callerNumber = hintedNumber
                }

                if (!isMonitorEnabled && CallProtectionPrefs.isArmed(applicationContext)) {
                    isMonitorEnabled = true
                    lastGeminiStatus = "armed"
                    startForeground(NOTIF_ID, buildNotification("Armed - waiting for call"))
                    startCallStatePollerIfNeeded()
                }

                val hintedState = intent.getIntExtra(EXTRA_HINT_STATE, -1)
                val hintSource = intent.getStringExtra(EXTRA_HINT_SOURCE) ?: "ExternalHint"
                if (hintedState != -1 && isMonitorEnabled) {
                    handleCallStateChanged(hintedState, callerNumber, hintSource)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startSessionIfNeeded() {
        if (!isMonitorEnabled) return
        if (isSessionActive) return
        isSessionActive = true
        initializeSession()
    }

    private fun initializeSession() {
        chunksSentForSession = 0
        responsesForSession = 0
        currentRiskScore = 0
        captureChunkCount = 0
        activeAudioSource = null
        zeroRmsStreak = 0
        isSwitchingSource = false
        geminiReady = false
        totalGeminiChunksSent = 0
        totalGeminiResponses = 0
        lastGeminiChunkAtMs = 0L
        lastGeminiResponseAtMs = 0L
        lastGeminiStatus = "connecting"
        updateNotification(0)
        riskAggregator = RiskAggregator(
            onAlert = { score, reason -> emitRiskAlert(score, reason) },
            onUpdate = { score ->
                currentRiskScore = score
                updateNotification(score)
            }
        )

        geminiRestAnalyzer = GeminiRestAnalyzer(
            callerNumber = callerNumber,
            onRiskScore = { score, intent, reason ->
                responsesForSession += 1
                totalGeminiResponses = responsesForSession
                lastGeminiResponseAtMs = System.currentTimeMillis()
                Log.d(tag, "Gemini REST telemetry: windows=$responsesForSession")
                riskAggregator?.addGeminiScore(score, intent, reason)
            },
            onError = { error ->
                Log.e(tag, "Gemini REST error: $error")
                lastGeminiStatus = "error"
            },
        )
        geminiReady = true
        lastGeminiStatus = "connected"
        updateNotification(currentRiskScore)

        checkAndStartCapture()
    }

    private fun checkAndStartCapture() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audioManager.isSpeakerphoneOn) {
            startAudioCapture()
        } else {
            Log.d(tag, "Speakerphone is OFF - waiting to start audio capture")
            val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notifManager.notify(NOTIF_ID, buildNotification("Enable speakerphone for live scam detection"))
            emitSpeakerphonePrompt()
            speakerPollJob?.cancel()
            speakerPollJob = scope.launch {
                while (isActive && isMonitorEnabled && isSessionActive && !isCapturing) {
                    delay(2000)
                    if (audioManager.isSpeakerphoneOn) {
                        Log.d(tag, "Speakerphone turned ON - starting audio capture")
                        startAudioCapture()
                        break
                    }
                }
            }
        }
    }

    private fun startAudioCapture(preferredSource: Int? = null) {
        if (isCapturing) return
        Log.d(tag, "Starting audio capture (speakerphone active)")

        val minBufSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBufSize, chunkBytes * 4)

        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            try {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (audioManager.isMicrophoneMute) {
                    audioManager.isMicrophoneMute = false
                }
            } catch (_: Exception) {
            }

            val sourceCandidates = buildSourceOrder(preferredSource)

            var selectedSource: Int? = null
            for (source in sourceCandidates) {
                try {
                    val candidate = AudioRecord(
                        source,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufSize,
                    )
                    if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                        audioRecord = candidate
                        selectedSource = source
                        break
                    }
                    candidate.release()
                } catch (_: Exception) {
                }
            }

            if (audioRecord == null || selectedSource == null) {
                Log.e(tag, "AudioRecord failed to initialize for MIC and VOICE_COMMUNICATION")
                return
            }

            Log.d(tag, "Audio capture source selected: ${audioSourceLabel(selectedSource)}")
            Log.d(tag, "SPEAKER_STATE: ${audioManager.isSpeakerphoneOn}")

            audioRecord?.startRecording()
            isCapturing = true
            isAudioCaptureRunning = true
            activeAudioSource = selectedSource
            captureChunkCount = 0
            zeroRmsStreak = 0
            Log.d(tag, "Audio capture started at 16kHz, chunk=${chunkSamples} samples")

            captureJob = scope.launch {
                val buffer = ByteArray(chunkBytes)
                while (isActive && isMonitorEnabled && isSessionActive && isCapturing) {
                    val bytesRead = audioRecord?.read(buffer, 0, chunkBytes) ?: -1
                    if (bytesRead > 0) {
                        captureChunkCount += 1
                        val chunk = buffer.copyOf(bytesRead)
                        val rms = estimateRms(chunk)
                        if (rms <= 2) {
                            zeroRmsStreak += 1
                        } else {
                            zeroRmsStreak = 0
                        }

                        if (captureChunkCount == 1 || captureChunkCount % 10 == 0) {
                            Log.d(tag, "Capture RMS chunk=$captureChunkCount rms=$rms")
                        }

                        if (zeroRmsStreak >= 12 && !isSwitchingSource) {
                            val nextSource = nextCaptureSource(activeAudioSource)
                            if (nextSource != null) {
                                Log.w(
                                    tag,
                                    "Sustained silent capture detected (streak=$zeroRmsStreak) on ${audioSourceLabel(activeAudioSource)}; switching to ${audioSourceLabel(nextSource)}"
                                )
                                scheduleCaptureSourceSwitch(nextSource)
                                break
                            }
                        }

                        if (rms < minRmsForGeminiSend) {
                            if (captureChunkCount == 1 || captureChunkCount % 10 == 0) {
                                Log.d(tag, "Skipping silent chunk rms=$rms")
                            }
                            continue
                        }

                        geminiRestAnalyzer?.addChunk(chunk)
                        chunksSentForSession += 1
                        totalGeminiChunksSent = chunksSentForSession
                        lastGeminiChunkAtMs = System.currentTimeMillis()
                        if (chunksSentForSession == 1 || chunksSentForSession % 10 == 0) {
                            updateNotification(currentRiskScore)
                            Log.d(tag, "Gemini stream telemetry: chunks=$chunksSentForSession responses=$responsesForSession")
                        }
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

    private fun buildSourceOrder(preferredSource: Int?): List<Int> {
        val base = mutableListOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            base.add(MediaRecorder.AudioSource.UNPROCESSED)
        }

        return if (preferredSource == null || !base.contains(preferredSource)) {
            base
        } else {
            listOf(preferredSource) + base.filter { it != preferredSource }
        }
    }

    private fun nextCaptureSource(current: Int?): Int? {
        val order = buildSourceOrder(null)
        if (current == null) return order.firstOrNull()
        val idx = order.indexOf(current)
        if (idx == -1) return order.firstOrNull()
        return order.getOrNull(idx + 1)
    }

    private fun scheduleCaptureSourceSwitch(nextSource: Int) {
        if (isSwitchingSource) return
        isSwitchingSource = true

        scope.launch {
            try {
                stopAudioCaptureOnly()
                delay(180)
                if (isMonitorEnabled && isSessionActive) {
                    startAudioCapture(preferredSource = nextSource)
                }
            } finally {
                isSwitchingSource = false
            }
        }
    }

    private fun stopAudioCaptureOnly() {
        isCapturing = false
        isAudioCaptureRunning = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
        audioRecord = null
        captureJob?.cancel()
        captureJob = null
    }

    private fun estimateRms(pcm16le: ByteArray): Int {
        if (pcm16le.size < 2) return 0

        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < pcm16le.size) {
            val lo = pcm16le[i].toInt() and 0xFF
            val hi = pcm16le[i + 1].toInt()
            val sample = (hi shl 8) or lo
            sum += sample * sample.toDouble()
            count += 1
            i += 2
        }
        if (count == 0) return 0
        return kotlin.math.sqrt(sum / count).toInt()
    }

    private fun audioSourceLabel(source: Int): String {
        return when (source) {
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            else -> "SOURCE_$source"
        }
    }

    private fun audioSourceLabel(source: Int?): String {
        return if (source == null) "NONE" else audioSourceLabel(source)
    }

    private fun tryLocalDetection(chunk: ByteArray) {
        try {
            // Optional hook for existing local detector integration.
        } catch (_: Exception) {
        }
    }

    private fun teardownSession() {
        speakerPollJob?.cancel()
        speakerPollJob = null

        stopAudioCaptureOnly()
        isSessionActive = false
        activeAudioSource = null
        zeroRmsStreak = 0
        isSwitchingSource = false

        geminiRestAnalyzer?.flush()
        geminiRestAnalyzer?.shutdown()
        geminiRestAnalyzer = null
        riskAggregator = null
        geminiReady = false
        lastGeminiStatus = if (isMonitorEnabled) "armed" else "stopped"
        Log.d(tag, "Session torn down")
    }

    private fun registerCallStateListeners() {
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            telephonyManager = tm
            phoneStateListener = object : PhoneStateListener() {
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallStateChanged(state, phoneNumber, "PhoneStateListener")
                }
            }
            @Suppress("DEPRECATION")
            tm.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallStateChanged(state, null, "TelephonyCallback")
                    }
                }
                telephonyCallback = callback
                tm.registerTelephonyCallback(mainExecutor, callback)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to register call state listeners", e)
        }
    }

    private fun handleCallStateChanged(state: Int, phoneNumber: String?, source: String) {
        if (!phoneNumber.isNullOrBlank()) {
            callerNumber = phoneNumber
        }

        if (hasObservedCallState && state == currentCallState) {
            return
        }

        val previous = currentCallState
        currentCallState = state
        val stateLabel = callStateName(state)
        Log.d(tag, "Call state via $source: ${callStateName(previous)} -> $stateLabel ($callerNumber)")

        if (!hasObservedCallState) {
            hasObservedCallState = true
            if (state == TelephonyManager.CALL_STATE_IDLE && !isSessionActive && !incomingEventEmitted) {
                // Initial IDLE callback is expected while simply arming the monitor.
                return
            }
        }

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                if (!isMonitorEnabled) return
                val label = if (callerNumber == "unknown") "incoming call" else callerNumber
                val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notifManager.notify(NOTIF_ID, buildNotification("Ringing: $label"))
                emitIncomingDetectedIfNeeded(label)
                evaluateIncomingMetadataIfNeeded()
            }

            TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.d(tag, "Call answered")
                evaluateIncomingMetadataIfNeeded()
                startSessionIfNeeded()
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                if (!isSessionActive && !incomingEventEmitted) {
                    if (isMonitorEnabled) {
                        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        notifManager.notify(NOTIF_ID, buildNotification("Armed - waiting for call"))
                    }
                    return
                }

                Log.d(tag, "Call ended - stopping capture")
                teardownSession()
                incomingEventEmitted = false
                metadataEvaluatedForCurrentCall = false
                if (isMonitorEnabled) {
                    val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notifManager.notify(NOTIF_ID, buildNotification("Armed - waiting for call"))
                } else {
                    stopSelf()
                }
            }
        }
    }

    private fun startCallStatePollerIfNeeded() {
        callStatePollJob?.cancel()
        callStatePollJob = scope.launch {
            while (isActive && isMonitorEnabled) {
                delay(1500)
                val offhook = isCurrentlyOffHook()
                if (offhook && !isSessionActive) {
                    handleCallStateChanged(TelephonyManager.CALL_STATE_OFFHOOK, null, "Poller")
                } else if (!offhook && isSessionActive) {
                    handleCallStateChanged(TelephonyManager.CALL_STATE_IDLE, null, "Poller")
                }
            }
        }
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
        Log.d(tag, "EMIT_ALERT: score=$score reason=$reason")
        val data = android.os.Bundle().apply {
            putString("type", "call_scam_alert")
            putString("number", callerNumber)
            putInt("riskScore", score)
            putString("reason", reason)
            putString("captureMethod", "call_audio_speakerphone")
            putString("tierUsed", "tier2-gemini-live")
        }
        NotificationEventEmitter.sendCallEvent(applicationContext, data)
        CallRiskAlertNotifier.show(
            context = applicationContext,
            type = "call_scam_alert",
            number = callerNumber,
            riskScore = score,
            reason = reason,
        )
    }

    private fun emitIncomingDetectedIfNeeded(label: String) {
        if (incomingEventEmitted) return
        incomingEventEmitted = true

        val data = android.os.Bundle().apply {
            putString("type", "call_incoming_detected")
            putString("number", callerNumber)
            putString("message", "Incoming call detected: $label")
            putString("captureMethod", "telephony_ring_state")
            putString("tierUsed", "tier1-metadata-fallback")
        }
        NotificationEventEmitter.sendCallEvent(applicationContext, data)
    }

    private fun evaluateIncomingMetadataIfNeeded() {
        if (metadataEvaluatedForCurrentCall) return
        metadataEvaluatedForCurrentCall = true

        val number = callerNumber
        scope.launch {
            try {
                val risk = NumberRiskEngine.evaluate(applicationContext, number)
                Log.d(tag, "Metadata fallback risk for $number: ${risk.score} (${risk.reason})")

                if (risk.score >= 55) {
                    val type = if (risk.score >= 85) "call_scam_alert" else "call_suspicious"
                    val data = android.os.Bundle().apply {
                        putString("type", type)
                        putString("number", number)
                        putInt("riskScore", risk.score)
                        putString("reason", risk.reason)
                        putString("captureMethod", "telephony_metadata_fallback")
                        putString("tierUsed", "tier1-metadata-fallback")
                    }
                    NotificationEventEmitter.sendCallEvent(applicationContext, data)
                    CallRiskAlertNotifier.show(
                        context = applicationContext,
                        type = type,
                        number = number,
                        riskScore = risk.score,
                        reason = risk.reason,
                    )
                }
            } catch (e: Exception) {
                Log.e(tag, "Metadata fallback evaluation failed", e)
            }
        }
    }

    private fun updateNotification(currentScore: Int) {
        val level = when {
            currentScore >= 55 -> "Suspicious"
            isCapturing && geminiReady -> "Live"
            isCapturing -> "Capturing"
            else -> "Monitoring"
        }
        val text = if (isCapturing) {
            "$level - Risk: $currentScore/100 | Chunks:$chunksSentForSession Responses:$responsesForSession"
        } else {
            "$level - Risk: $currentScore/100"
        }
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifManager.notify(NOTIF_ID, buildNotification(text))
    }

    private fun callStateName(state: Int): String {
        return when (state) {
            TelephonyManager.CALL_STATE_IDLE -> "IDLE"
            TelephonyManager.CALL_STATE_RINGING -> "RINGING"
            TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
            else -> "UNKNOWN($state)"
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("IntentFirewall - Call Monitor")
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
            ).apply {
                description = "Real-time scam call monitoring"
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        isAudioCaptureRunning = false
        isMonitorEnabled = false
        callStatePollJob?.cancel()
        callStatePollJob = null
        teardownSession()
        phoneStateListener?.let {
            try {
                (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
                    .listen(it, PhoneStateListener.LISTEN_NONE)
            } catch (_: Exception) {
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = telephonyCallback
            val tm = telephonyManager
            if (callback != null && tm != null) {
                try {
                    tm.unregisterTelephonyCallback(callback)
                } catch (_: Exception) {
                }
            }
        }
        telephonyCallback = null
        telephonyManager = null
        lastGeminiStatus = "stopped"
        scope.cancel()
    }

    private fun isCurrentlyOffHook(): Boolean {
        return try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION")
            tm.callState == TelephonyManager.CALL_STATE_OFFHOOK
        } catch (_: Exception) {
            false
        }
    }
}
