package com.intentfirewall

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import java.io.File
import java.io.FileOutputStream

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
    private var activeAudioSource: Int? = null
    private var chunksSentForSession = 0
    private var responsesForSession = 0
    private var geminiReady = false

    // Debug WAV recording
    private var debugWavFile: File? = null
    private var debugOutputStream: FileOutputStream? = null
    private var totalBytesWritten = 0
    private val DEBUG_RECORDING_ENABLED = true

    private val chunkSamples = 8000
    private val chunkBytes = chunkSamples * 2
    private val geminiRmsGate = 40

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

        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Ordered by likelihood of having signal during active calls on OEM devices.
        private val SOURCE_PROBE_ORDER: List<Int> = mutableListOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                add(1, MediaRecorder.AudioSource.UNPROCESSED)
            }
        }

        private fun sourceName(source: Int): String = when (source) {
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            else -> "UNKNOWN($source)"
        }
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
        activeAudioSource = null
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
        if (isCapturing) return

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (!audioManager.isSpeakerphoneOn) {
            Log.d(tag, "Speakerphone is OFF - waiting to start audio capture")
            val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notifManager.notify(NOTIF_ID, buildNotification("Enable speakerphone for live scam detection"))
            emitSpeakerphonePrompt()
        }

        waitForSpeakerphoneAndStart(callerNumber)
    }

    private fun waitForSpeakerphoneAndStart(callerNumber: String) {
        speakerPollJob?.cancel()
        speakerPollJob = scope.launch {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            var waited = 0

            while (
                isActive && isMonitorEnabled && isSessionActive &&
                !am.isSpeakerphoneOn && waited < 15000 && !isCapturing
            ) {
                delay(500)
                waited += 500
            }

            if (!isActive || !isMonitorEnabled || !isSessionActive || isCapturing) {
                return@launch
            }

            if (am.isSpeakerphoneOn) {
                Log.d("CallAudio", "Speakerphone confirmed ON after ${waited}ms — starting AudioRecord")
                startAudioCapture(callerNumber = callerNumber)
            } else {
                Log.w("CallAudio", "Speakerphone not detected after 15s — starting anyway (degraded mode)")
                startAudioCapture(callerNumber = callerNumber)
            }
        }
    }

    private fun startAudioCapture(callerNumber: String = this.callerNumber) {
        if (isCapturing) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("CallAudio", "RECORD_AUDIO permission not granted - cannot start audio capture")
            return
        }
        Log.d(tag, "Starting audio capture (speakerphone active)")

        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            try {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (audioManager.isMicrophoneMute) {
                    audioManager.isMicrophoneMute = false
                }
            } catch (_: Exception) {
            }

            Log.d("CallAudio", "Starting audio source probe...")
            val workingSource = detectWorkingAudioSource()
            activeAudioSource = workingSource
            Log.d("CallAudio", "Using source: ${sourceName(workingSource)}")

            val minBufSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
            )
            if (minBufSize <= 0) {
                Log.e("CallAudio", "Main AudioRecord invalid min buffer size: $minBufSize")
                return
            }
            val bufferSize = maxOf(minBufSize * 8, 16384)

            audioRecord = AudioRecord(
                workingSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize,
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("CallAudio", "Main AudioRecord failed to init on ${sourceName(workingSource)}")
                try {
                    audioRecord?.release()
                } catch (_: Exception) {
                }
                audioRecord = null
                return
            }

            Log.d("CallAudio", "AudioRecord initialized: state=${audioRecord?.state} bufSize=$bufferSize")
            Log.d(tag, "SPEAKER_STATE: ${audioManager.isSpeakerphoneOn}")

            startDebugRecording(callerNumber)
            audioRecord?.startRecording()
            isCapturing = true
            isAudioCaptureRunning = true
            Log.d("CallAudio", "AudioRecord started: source=${sourceName(workingSource)} bufSize=$bufferSize")
            Log.d(tag, "Audio capture started at 16kHz, chunk=${chunkSamples} samples")

            captureJob = scope.launch {
                val buffer = ByteArray(chunkBytes)
                while (isActive && isMonitorEnabled && isSessionActive && isCapturing) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (bytesRead > 0) {
                        val raw = buffer.copyOf(bytesRead)
                        val rmsRaw = computeRmsFromBytes(raw, raw.size)
                        Log.d("CallAudio", "CHUNK_RMS: $rmsRaw source=${sourceName(workingSource)}")

                        // Always persist raw audio for diagnostics, even below model gate.
                        writeDebugChunk(raw)

                        if (rmsRaw >= geminiRmsGate) {
                            geminiRestAnalyzer?.addChunk(raw)
                            chunksSentForSession += 1
                            totalGeminiChunksSent = chunksSentForSession
                            lastGeminiChunkAtMs = System.currentTimeMillis()
                            updateNotification(currentRiskScore)
                            if (chunksSentForSession == 1 || chunksSentForSession % 10 == 0) {
                                Log.d(tag, "Gemini stream telemetry: chunks=$chunksSentForSession responses=$responsesForSession")
                            }
                        } else {
                            Log.d("CallAudio", "CHUNK_SKIPPED: rms=$rmsRaw below gate=$geminiRmsGate")
                        }

                        tryLocalDetection(raw)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(tag, "Missing RECORD_AUDIO permission", e)
        } catch (e: Exception) {
            Log.e(tag, "Audio capture error", e)
        }
    }

    /**
     * Probes each source for ~0.5 seconds and returns the first source with non-zero RMS.
     * Falls back to VOICE_RECOGNITION when all sources are silent.
     */
    private fun detectWorkingAudioSource(): Int {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuf <= 0) {
            Log.w("CallAudio", "PROBE: invalid min buffer size=$minBuf; defaulting to VOICE_RECOGNITION")
            return MediaRecorder.AudioSource.VOICE_RECOGNITION
        }

        val probeBuf = ByteArray(minBuf * 4)
        for (source in SOURCE_PROBE_ORDER) {
            var probe: AudioRecord? = null
            try {
                probe = AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, probeBuf.size)
                if (probe.state != AudioRecord.STATE_INITIALIZED) {
                    Log.d("CallAudio", "PROBE: source=${sourceName(source)} failed to init - skipping")
                    continue
                }

                probe.startRecording()
                val bytesRead = probe.read(probeBuf, 0, probeBuf.size)
                val rms = computeRmsFromBytes(probeBuf, bytesRead)
                Log.d("CallAudio", "PROBE: source=${sourceName(source)} bytesRead=$bytesRead rms=$rms")

                if (rms > 2) {
                    Log.d("CallAudio", "PROBE_SELECTED: ${sourceName(source)} rms=$rms")
                    return source
                }
            } catch (e: Exception) {
                Log.e("CallAudio", "PROBE: source=${sourceName(source)} exception: ${e.message}")
            } finally {
                try {
                    probe?.stop()
                } catch (_: Exception) {
                }
                try {
                    probe?.release()
                } catch (_: Exception) {
                }
            }
        }

        Log.w("CallAudio", "PROBE: all sources returned zero - defaulting to VOICE_RECOGNITION")
        return MediaRecorder.AudioSource.VOICE_RECOGNITION
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
        stopDebugRecording()
        captureJob?.cancel()
        captureJob = null
    }

    private fun computeRmsFromBytes(buf: ByteArray, length: Int): Int {
        if (length <= 1) return 0
        var sumSq = 0.0
        var count = 0
        var i = 0
        val limit = minOf(length, buf.size)
        while (i < limit - 1) {
            val sample = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toDouble()
            sumSq += sample * sample
            count++
            i += 2
        }
        return if (count == 0) 0 else Math.sqrt(sumSq / count).toInt()
    }

    private fun startDebugRecording(callerNumber: String) {
        if (!DEBUG_RECORDING_ENABLED) return
        try {
            val dir = File(getExternalFilesDir(null), "call_debug")
            dir.mkdirs()
            val timestamp = System.currentTimeMillis()
            val safe = callerNumber.replace(Regex("[^0-9+]"), "_")
            debugWavFile = File(dir, "call_${safe}_${timestamp}.wav")
            debugOutputStream = FileOutputStream(debugWavFile!!)
            writeWavHeader(debugOutputStream!!, 0)
            totalBytesWritten = 0
            Log.d("CallAudio", "DEBUG_WAV_START: ${debugWavFile!!.absolutePath}")
        } catch (e: Exception) {
            Log.e("CallAudio", "DEBUG_WAV_START failed: ${e.message}")
        }
    }

    private fun writeDebugChunk(pcm: ByteArray) {
        if (!DEBUG_RECORDING_ENABLED) return
        try {
            debugOutputStream?.write(pcm)
            totalBytesWritten += pcm.size
        } catch (e: Exception) {
            Log.e("CallAudio", "DEBUG_WAV_WRITE failed: ${e.message}")
        }
    }

    private fun stopDebugRecording() {
        if (!DEBUG_RECORDING_ENABLED) return
        try {
            debugOutputStream?.flush()
            debugOutputStream?.close()
            debugWavFile?.let { file ->
                fixWavHeader(file, totalBytesWritten)
                val seconds = totalBytesWritten / (16000 * 2)
                Log.d(
                    "CallAudio",
                    "DEBUG_WAV_SAVED: ${file.absolutePath} | bytes=$totalBytesWritten | duration=${seconds}s",
                )
            }
        } catch (e: Exception) {
            Log.e("CallAudio", "DEBUG_WAV_STOP failed: ${e.message}")
        } finally {
            debugOutputStream = null
            debugWavFile = null
            totalBytesWritten = 0
        }
    }

    private fun writeWavHeader(out: FileOutputStream, dataSize: Int) {
        val sampleRate = 16000
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val totalSize = 36 + dataSize
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(intToLEBytes(totalSize))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(intToLEBytes(16))
        out.write(shortToLEBytes(1))
        out.write(shortToLEBytes(channels.toShort()))
        out.write(intToLEBytes(sampleRate))
        out.write(intToLEBytes(byteRate))
        out.write(shortToLEBytes(blockAlign))
        out.write(shortToLEBytes(bitsPerSample.toShort()))
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(intToLEBytes(dataSize))
    }

    private fun fixWavHeader(file: File, dataSize: Int) {
        val raf = java.io.RandomAccessFile(file, "rw")
        try {
            raf.seek(4)
            raf.write(intToLEBytes(36 + dataSize))
            raf.seek(40)
            raf.write(intToLEBytes(dataSize))
        } finally {
            raf.close()
        }
    }

    private fun intToLEBytes(v: Int): ByteArray =
        byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun shortToLEBytes(v: Short): ByteArray =
        byteArrayOf(v.toByte(), (v.toInt() shr 8).toByte())

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
