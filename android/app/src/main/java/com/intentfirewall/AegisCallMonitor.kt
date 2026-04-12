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
import android.media.AudioManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AegisCallMonitor : Service() {

    companion object {
        private const val TAG = "AegisCallMonitor"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 3200
        private const val PROBE_READ_SAMPLES = 1024
        private const val PROBE_ROUNDS = 4
        private const val PROBE_INTERVAL_MS = 80L
        private const val SILENCE_RMS_THRESHOLD = 0.0015
        private const val SILENCE_CHUNKS_BEFORE_RECOVERY = 40
        private const val SOURCE_RECOVERY_COOLDOWN_MS = 5000L
        private const val SILENCE_ATTEMPTS_BEFORE_LOOPBACK = 3
        private const val WINDOW_SAMPLES = 32000
        private const val OVERLAP_SAMPLES = 16000
        private const val FLAT_PITCH_THRESHOLD = 0.15f
        private const val CHANNEL_ID = "aegis_protection"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.aegis.START_MONITOR"
        const val ACTION_PRIME = "com.aegis.PRIME_MONITOR"
        const val ACTION_STOP = "com.aegis.STOP_MONITOR"
    }

    private var audioRecord: AudioRecord? = null
    private var detector: AegisAudioDetector? = null
    private var transcriptStore: CallTranscriptStore? = null
    private var transcriptAnalyzer: AegisCallTextAnalyzer? = null
    private var bridgeConfig: ScamAssistBridgeConfig? = null
    private var pcmOutputStream: BufferedOutputStream? = null
    private var activePcmFile: File? = null
    private var activeWavFile: File? = null
    private var activeCallToken: String? = null
    private val accumulator = FloatArray(WINDOW_SAMPLES)
    private var accumulatorPos = 0
    private var isRecording = false
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var previousSpeakerphoneOn: Boolean = false
    private var activeCallerId: String? = null
    private var callStartedAtMs: Long = 0L
    private var callEndedAtMs: Long = 0L
    private var selectedCaptureSource: String? = null
    private var currentAudioSource: Int? = null
    private var consecutiveSilentChunks = 0
    private var lastSourceRecoveryMs = 0L
    private var silenceRecoveryAttempts = 0
    private var loopbackModeEnabled = false

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private val riskEventListener: (String, String) -> Unit = { label, text ->
        transcriptStore?.appendLine("RISK_$label", text)
    }

    private data class AudioCaptureCandidate(
        val source: Int,
        val record: AudioRecord,
        val buffer: ShortArray = ShortArray(PROBE_READ_SAMPLES),
        var energy: Double = 0.0,
        var reads: Int = 0
    )

    private val captureSourceOrder = listOf(
        MediaRecorder.AudioSource.VOICE_DOWNLINK,
        MediaRecorder.AudioSource.VOICE_CALL,
        MediaRecorder.AudioSource.VOICE_UPLINK,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.VOICE_RECOGNITION
    )

    private val loopbackSourceOrder = listOf(
        MediaRecorder.AudioSource.MIC
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        detector = runCatching { AegisAudioDetector(this) }
            .onFailure { e -> Log.w(TAG, "Audio detector disabled; model assets unavailable", e) }
            .getOrNull()
        transcriptStore = CallTranscriptStore(this)
        bridgeConfig = ScamAssistBridgeConfig(this)
        transcriptAnalyzer = AegisCallTextAnalyzer(this).apply {
            onTranscriptReady = { transcript ->
                transcriptStore?.appendLine("STT", transcript)
            }
        }
        AegisCallState.registerRiskEventListener(riskEventListener)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:AegisCallMonitor").apply {
            setReferenceCounted(false)
        }

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_IDLE -> {
                        AegisCallState.onCallEnded()
                        callEndedAtMs = System.currentTimeMillis()
                        stopCapture()
                        transcriptAnalyzer?.stop()
                        transcriptStore?.finishSession("call ended")
                        restoreAudioRoute()
                    }

                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        val isUnknown = incomingNumber.isNullOrEmpty()
                        detector?.onCallMetadataUpdate(
                            isUnknownNumber = isUnknown,
                            isVideoCall = false
                        )
                        activeCallToken = System.currentTimeMillis().toString()
                        activeCallerId = incomingNumber
                        callStartedAtMs = System.currentTimeMillis()
                        callEndedAtMs = 0L
                        selectedCaptureSource = null
                        currentAudioSource = null
                        consecutiveSilentChunks = 0
                        lastSourceRecoveryMs = 0L
                        silenceRecoveryAttempts = 0
                        loopbackModeEnabled = false
                        AegisCallState.onCallStarted(this@AegisCallMonitor, incomingNumber)
                        transcriptStore?.startSession(incomingNumber)
                        transcriptStore?.appendLine("META", "call_state=OFFHOOK unknown=$isUnknown")
                        transcriptAnalyzer?.start()
                        prepareAudioRouteForCall()
                        startCapture()
                    }
                }
            }
        }

        try {
            @Suppress("DEPRECATION")
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (se: SecurityException) {
            Log.w(TAG, "READ_PHONE_STATE not granted; call-state monitoring disabled", se)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register phone state listener", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start foreground service: ${e.message}. Continuing without foreground status.", e)
        }

        when (intent?.action) {
            ACTION_PRIME -> {
                Log.i(TAG, "Monitor primed from foreground UI; waiting for call state changes")
            }
            ACTION_START -> startCapture()
            ACTION_STOP -> {
                callEndedAtMs = System.currentTimeMillis()
                stopCapture()
                transcriptAnalyzer?.stop()
                transcriptStore?.finishSession("service stopped")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                if (!isRecording) startCapture()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        transcriptAnalyzer?.stop()
        transcriptStore?.finishSession("service destroyed")
        detector?.close()
        detector = null
        transcriptAnalyzer = null
        transcriptStore = null
        AegisCallState.unregisterRiskEventListener(riskEventListener)

        try {
            @Suppress("DEPRECATION")
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        } catch (_: Exception) {
        }
        phoneStateListener = null
        telephonyManager = null

        serviceScope.cancel()

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null

        restoreAudioRoute()

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
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aegis Active")
            .setContentText("Monitoring call for deepfake audio")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startCapture() {
        if (isRecording) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission not granted; capture not started")
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "AudioRecord min buffer size query failed")
            return
        }

        val bufferSize = minBuffer * 2

        isRecording = true
        startPcmCaptureSession()

        if (wakeLock?.isHeld != true) {
            wakeLock?.acquire()
        }

        serviceScope.launch(Dispatchers.IO) {
            val selected = selectAudioRecord(bufferSize)
            if (selected == null) {
                stopCapture()
                return@launch
            }

            audioRecord = selected.record
            currentAudioSource = selected.source
            val buffer = ShortArray(CHUNK_SAMPLES)

            while (isRecording && isActive) {
                val read = try {
                    audioRecord?.read(buffer, 0, CHUNK_SAMPLES) ?: break
                } catch (e: Exception) {
                    Log.e(TAG, "AudioRecord read failed", e)
                    break
                }

                if (read <= 0) continue

                val chunkRms = computeChunkRms(buffer, read)
                if (chunkRms < SILENCE_RMS_THRESHOLD) {
                    consecutiveSilentChunks += 1
                } else {
                    consecutiveSilentChunks = 0
                }

                val now = System.currentTimeMillis()
                if (
                    consecutiveSilentChunks >= SILENCE_CHUNKS_BEFORE_RECOVERY &&
                    now - lastSourceRecoveryMs >= SOURCE_RECOVERY_COOLDOWN_MS
                ) {
                    silenceRecoveryAttempts += 1
                    transcriptStore?.appendLine(
                        "AUDIO",
                        "silence_detected rms=${String.format(java.util.Locale.US, "%.6f", chunkRms)} chunks=$consecutiveSilentChunks attempting_source_recovery"
                    )
                    val switched = if (
                        !loopbackModeEnabled &&
                        silenceRecoveryAttempts >= SILENCE_ATTEMPTS_BEFORE_LOOPBACK
                    ) {
                        attemptLoopbackRecovery(bufferSize)
                    } else {
                        attemptSourceRecovery(bufferSize)
                    }
                    lastSourceRecoveryMs = now
                    consecutiveSilentChunks = 0
                    if (switched) {
                        continue
                    }
                }

                writePcmChunk(buffer, read)

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
                    val spectralGap = computeSpectralGapPlaceholder(window)
                    
                    // Simple multi-signal gating logic
                    val zcrTrigger = zcr > 0.15f
                    val gapTrigger = spectralGap > 0.8f
                    val combinedSpectralScore = if (pitchVar < FLAT_PITCH_THRESHOLD || zcrTrigger || gapTrigger) 0.0f else 1.0f

                    detector?.onSpectralEnergyUpdate(combinedSpectralScore)

                    if (detector?.shouldActivate() == true) {
                        serviceScope.launch(Dispatchers.Default) {
                            try {
                                Log.d("IntentFirewall", "Starting audio detection analysis")
                                val result = detector?.analyze(window)
                                result?.let { 
                                    onDetectionResult(it) 
                                    Log.d("IntentFirewall", "Audio detection complete: synthetic=${it.isSynthetic} conf=${it.confidence}")
                                    transcriptStore?.appendLine(
                                        "DETECTION",
                                        "synthetic=${it.isSynthetic} confidence=${it.confidence} latencyMs=${it.latencyMs}"
                                    )
                                }
                            } catch (e: Exception) {
                                Log.e("IntentFirewall", "Audio detection failed: ${e.message}", e)
                            }
                        }
                    }

                    transcriptAnalyzer?.processAudioChunk(window)

                    System.arraycopy(
                        accumulator,
                        OVERLAP_SAMPLES,
                        accumulator,
                        0,
                        WINDOW_SAMPLES - OVERLAP_SAMPLES
                    )
                    accumulatorPos = WINDOW_SAMPLES - OVERLAP_SAMPLES
                }
            }

            stopCapture()
        }
    }

    private fun stopCapture() {
        isRecording = false

        val record = audioRecord
        audioRecord = null
        val pcmFileToConvert = closePcmCaptureSession()

        if (record != null) {
            try {
                record.stop()
            } catch (_: IllegalStateException) {
            }
            record.release()
        }

        accumulatorPos = 0

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        if (pcmFileToConvert != null) {
            convertPcmToWav(pcmFileToConvert)
        }
    }

    private suspend fun selectAudioRecord(bufferSize: Int, sources: List<Int> = captureSourceOrder): AudioCaptureCandidate? {
        val candidates = createAudioCandidates(bufferSize, sources)

        if (candidates.isEmpty()) {
            Log.e(TAG, "No audio capture candidates available")
            transcriptStore?.appendLine("AUDIO", "capture_failed_no_candidates")
            return null
        }

        val startedCandidates = mutableListOf<AudioCaptureCandidate>()
        for (candidate in candidates) {
            try {
                candidate.record.startRecording()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Failed to start recording for source=${candidate.source}", e)
                candidate.record.release()
                continue
            }

            if (candidate.record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                startedCandidates.add(candidate)
                Log.i(TAG, "Audio probe started for source=${candidate.source}")
            } else {
                candidate.record.release()
            }
        }

        if (startedCandidates.isEmpty()) {
            Log.e(TAG, "All audio capture sources failed; no usable audio stream available")
            transcriptStore?.appendLine("AUDIO", "capture_failed_all_sources")
            return null
        }

        repeat(PROBE_ROUNDS) {
            for (candidate in startedCandidates) {
                val read = try {
                    candidate.record.read(
                        candidate.buffer,
                        0,
                        candidate.buffer.size,
                        AudioRecord.READ_NON_BLOCKING
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Probe read failed for source=${candidate.source}", e)
                    continue
                }

                if (read > 0) {
                    candidate.energy += averageAbsAmplitude(candidate.buffer, read)
                    candidate.reads += 1
                }
            }
            delay(PROBE_INTERVAL_MS)
        }

        val chosenCandidate = startedCandidates
            .filter { it.reads > 0 }
            .maxByOrNull { it.energy / it.reads }
            ?: startedCandidates.first()

        startedCandidates.forEach { candidate ->
            if (candidate !== chosenCandidate) {
                try {
                    candidate.record.stop()
                } catch (_: Exception) {
                }
                candidate.record.release()
            }
        }

        val score = if (chosenCandidate.reads > 0) {
            chosenCandidate.energy / chosenCandidate.reads
        } else {
            0.0
        }

        Log.i(TAG, "Selected audio capture source=${sourceName(chosenCandidate.source)} score=$score")
        selectedCaptureSource = sourceName(chosenCandidate.source)
        currentAudioSource = chosenCandidate.source
        transcriptStore?.appendLine(
            "AUDIO",
            "capture_source=${sourceName(chosenCandidate.source)} probe_score=$score reads=${chosenCandidate.reads}"
        )

        return chosenCandidate
    }

    private fun createAudioCandidates(bufferSize: Int, sources: List<Int>): List<AudioCaptureCandidate> {
        return sources.mapNotNull { source ->
            val record = try {
                AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord creation failed for source=$source", e)
                null
            } ?: return@mapNotNull null

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord not initialized for source=$source")
                record.release()
                return@mapNotNull null
            }

            AudioCaptureCandidate(source, record)
        }
    }

    private suspend fun attemptSourceRecovery(bufferSize: Int): Boolean {
        val current = currentAudioSource
        val ordered = if (current == null || current !in captureSourceOrder) {
            captureSourceOrder
        } else {
            val idx = captureSourceOrder.indexOf(current)
            captureSourceOrder.drop(idx + 1) + captureSourceOrder.take(idx + 1)
        }

        val currentName = current?.let { sourceName(it) } ?: "unknown"
        val next = selectAudioRecord(bufferSize, ordered) ?: return false

        if (current != null && next.source == current) {
            try {
                next.record.stop()
            } catch (_: Exception) {
            }
            next.record.release()
            transcriptStore?.appendLine("AUDIO", "source_recovery_no_change source=${sourceName(current)}")
            return false
        }

        val oldRecord = audioRecord
        audioRecord = next.record
        currentAudioSource = next.source
        selectedCaptureSource = sourceName(next.source)
        if (loopbackModeEnabled && next.source != MediaRecorder.AudioSource.MIC) {
            setSpeakerLoopback(false)
            loopbackModeEnabled = false
            transcriptStore?.appendLine("AUDIO", "loopback_mode_disabled")
        }

        if (oldRecord != null) {
            try {
                oldRecord.stop()
            } catch (_: Exception) {
            }
            oldRecord.release()
        }

        transcriptStore?.appendLine(
            "AUDIO",
            "source_recovered from=$currentName to=${sourceName(next.source)}"
        )
        Log.i(TAG, "Audio source recovered from=$currentName to=${sourceName(next.source)}")
        return true
    }

    private suspend fun attemptLoopbackRecovery(bufferSize: Int): Boolean {
        transcriptStore?.appendLine("AUDIO", "loopback_mode_attempt source=MIC speakerphone=true")
        setSpeakerLoopback(true)

        val next = selectAudioRecord(bufferSize, loopbackSourceOrder)
        if (next == null) {
            setSpeakerLoopback(false)
            transcriptStore?.appendLine("AUDIO", "loopback_mode_failed")
            return false
        }

        val oldRecord = audioRecord
        audioRecord = next.record
        currentAudioSource = next.source
        selectedCaptureSource = sourceName(next.source)
        loopbackModeEnabled = true

        if (oldRecord != null) {
            try {
                oldRecord.stop()
            } catch (_: Exception) {
            }
            oldRecord.release()
        }

        transcriptStore?.appendLine("AUDIO", "loopback_mode_enabled source=${sourceName(next.source)}")
        Log.i(TAG, "Loopback mode enabled with source=${sourceName(next.source)}")
        return true
    }

    private fun setSpeakerLoopback(enabled: Boolean) {
        val manager = audioManager ?: return
        try {
            manager.isSpeakerphoneOn = enabled
            transcriptStore?.appendLine("AUDIO", "speaker_loopback=$enabled")
        } catch (e: Exception) {
            transcriptStore?.appendLine("AUDIO", "speaker_loopback_failed reason=${e.message}")
        }
    }

    private fun prepareAudioRouteForCall() {
        val manager = audioManager ?: return
        try {
            previousAudioMode = manager.mode
            previousSpeakerphoneOn = manager.isSpeakerphoneOn
            try {
                manager.mode = AudioManager.MODE_IN_CALL
            } catch (_: Exception) {
                manager.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            manager.isSpeakerphoneOn = false
            Log.i(TAG, "Audio route prepared for call: mode=${manager.mode} speakerphoneOn=false")
            transcriptStore?.appendLine("AUDIO", "route_prepared mode=${manager.mode} speakerphone=false")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to prepare call audio route", e)
            transcriptStore?.appendLine("AUDIO", "route_prepare_failed reason=${e.message}")
        }
    }

    private fun restoreAudioRoute() {
        val manager = audioManager ?: return
        try {
            manager.isSpeakerphoneOn = previousSpeakerphoneOn
            manager.mode = previousAudioMode
            Log.i(TAG, "Audio route restored: mode=$previousAudioMode speakerphoneOn=$previousSpeakerphoneOn")
            transcriptStore?.appendLine("AUDIO", "route_restored mode=$previousAudioMode speakerphone=$previousSpeakerphoneOn")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore audio route", e)
        }
    }

    private fun recordingsDirectory(): File {
        val root = getExternalFilesDir(null) ?: filesDir
        return File(root, "call_recordings").apply { mkdirs() }
    }

    private fun startPcmCaptureSession() {
        val token = activeCallToken ?: System.currentTimeMillis().toString().also { activeCallToken = it }
        val dir = recordingsDirectory()
        activePcmFile = File(dir, "call_$token.pcm")
        activeWavFile = File(dir, "call_$token.wav")

        try {
            pcmOutputStream = BufferedOutputStream(FileOutputStream(activePcmFile, false))
            transcriptStore?.appendLine("AUDIO", "pcm_capture_started path=${activePcmFile?.absolutePath}")
            Log.i(TAG, "PCM capture started: ${activePcmFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start PCM capture file", e)
            transcriptStore?.appendLine("AUDIO", "pcm_capture_failed reason=${e.message}")
            pcmOutputStream = null
            activePcmFile = null
        }
    }

    private fun writePcmChunk(buffer: ShortArray, read: Int) {
        val out = pcmOutputStream ?: return
        try {
            val bytes = ByteArray(read * 2)
            var j = 0
            for (i in 0 until read) {
                val s = buffer[i].toInt()
                bytes[j++] = (s and 0xFF).toByte()
                bytes[j++] = ((s shr 8) and 0xFF).toByte()
            }
            out.write(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing PCM chunk", e)
        }
    }

    private fun averageAbsAmplitude(buffer: ShortArray, read: Int): Double {
        if (read <= 0) return 0.0
        var total = 0.0
        for (i in 0 until read) {
            total += abs(buffer[i].toInt()).toDouble()
        }
        return total / read
    }

    private fun computeChunkRms(buffer: ShortArray, read: Int): Double {
        if (read <= 0) return 0.0
        var sumSquares = 0.0
        for (i in 0 until read) {
            val normalized = buffer[i].toDouble() / 32768.0
            sumSquares += normalized * normalized
        }
        return kotlin.math.sqrt(sumSquares / read)
    }

    private fun sourceName(source: Int): String {
        return when (source) {
            MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
            MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
            MediaRecorder.AudioSource.VOICE_UPLINK -> "VOICE_UPLINK"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.MIC -> "MIC"
            else -> source.toString()
        }
    }

    private fun closePcmCaptureSession(): File? {
        val file = activePcmFile
        try {
            pcmOutputStream?.flush()
            pcmOutputStream?.close()
        } catch (_: Exception) {
        }
        pcmOutputStream = null
        activePcmFile = null
        return if (file != null && file.exists() && file.length() > 0L) file else null
    }

    private fun convertPcmToWav(pcmFile: File) {
        val wavFile = activeWavFile ?: File(recordingsDirectory(), pcmFile.nameWithoutExtension + ".wav")
        val callerId = activeCallerId
        val startedAt = callStartedAtMs
        val endedAt = if (callEndedAtMs > 0L) callEndedAtMs else System.currentTimeMillis()
        val captureSource = selectedCaptureSource
        val latestTranscript = transcriptStore?.latestTranscriptFile()
        serviceScope.launch(Dispatchers.IO) {
            try {
                val pcmBytes = pcmFile.readBytes()
                recordWavDiagnostics(latestTranscript, pcmBytes)
                FileOutputStream(wavFile, false).use { out ->
                    out.write(buildWavHeader(pcmBytes.size, SAMPLE_RATE, 1, 16))
                    out.write(pcmBytes)
                }
                if (wavFile.exists() && wavFile.length() > 44L) {
                    Log.i(TAG, "WAV export success: ${wavFile.absolutePath}")
                    appendTranscriptLine(latestTranscript, "AUDIO", "wav_created path=${wavFile.absolutePath} size=${wavFile.length()}")
                    maybeUploadScamAssistBridge(
                        wavFile = wavFile,
                        transcriptFile = latestTranscript,
                        callerId = callerId,
                        startedAtMs = startedAt,
                        endedAtMs = endedAt,
                        captureSource = captureSource
                    )
                } else {
                    Log.e(TAG, "WAV export failed")
                    transcriptStore?.appendLine("AUDIO", "wav_export_failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "WAV export exception", e)
                transcriptStore?.appendLine("AUDIO", "wav_export_exception reason=${e.message}")
            }
        }
    }

    private fun maybeUploadScamAssistBridge(
        wavFile: File,
        transcriptFile: File?,
        callerId: String?,
        startedAtMs: Long,
        endedAtMs: Long,
        captureSource: String?
    ) {
        val config = bridgeConfig?.load() ?: return
        if (!config.enabled || config.endpoint.isBlank()) {
            return
        }

        appendTranscriptLine(transcriptFile, "BRIDGE", "upload_start endpoint=${config.endpoint}")
        val success = ScamAssistBridgeUploader.uploadSession(
            settings = config,
            wavFile = wavFile,
            transcriptFile = transcriptFile,
            callerId = callerId,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            captureSource = captureSource
        )
        appendTranscriptLine(transcriptFile, "BRIDGE", "upload_result success=$success")
    }

    private fun recordWavDiagnostics(transcriptFile: File?, pcmBytes: ByteArray) {
        if (pcmBytes.isEmpty()) {
            appendTranscriptLine(transcriptFile, "WAV_DIAG", "pcm_empty")
            return
        }

        var sampleCount = 0
        var sumSquares = 0.0
        var peak = 0
        var activeSamples = 0

        var index = 0
        while (index + 1 < pcmBytes.size) {
            val sample = (pcmBytes[index].toInt() and 0xFF) or ((pcmBytes[index + 1].toInt() and 0xFF) shl 8)
            val signedSample = if (sample > 0x7FFF) sample - 0x10000 else sample
            val absSample = kotlin.math.abs(signedSample)

            if (absSample > peak) {
                peak = absSample
            }
            if (absSample > 1500) {
                activeSamples += 1
            }

            val normalized = signedSample / 32768.0
            sumSquares += normalized * normalized
            sampleCount += 1
            index += 2
        }

        val rms = kotlin.math.sqrt(sumSquares / sampleCount.coerceAtLeast(1))
        val activeRatio = activeSamples.toDouble() / sampleCount.coerceAtLeast(1).toDouble()
        val diag = "samples=$sampleCount rms=${String.format(java.util.Locale.US, "%.5f", rms)} peak=$peak active_ratio=${String.format(java.util.Locale.US, "%.3f", activeRatio)}"

        Log.i(TAG, "WAV diagnostic: $diag")
        appendTranscriptLine(transcriptFile, "WAV_DIAG", diag)
    }

    private fun appendTranscriptLine(transcriptFile: File?, label: String, text: String) {
        try {
            if (transcriptFile != null) {
                transcriptFile.appendText("[$label] ${text.trim()}\n")
            } else {
                transcriptStore?.appendLine(label, text)
            }
        } catch (_: Exception) {
        }
    }

    private fun buildWavHeader(dataSize: Int, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val totalDataLen = dataSize + 36

        return ByteArray(44).apply {
            this[0] = 'R'.code.toByte(); this[1] = 'I'.code.toByte(); this[2] = 'F'.code.toByte(); this[3] = 'F'.code.toByte()
            writeIntLE(this, 4, totalDataLen)
            this[8] = 'W'.code.toByte(); this[9] = 'A'.code.toByte(); this[10] = 'V'.code.toByte(); this[11] = 'E'.code.toByte()
            this[12] = 'f'.code.toByte(); this[13] = 'm'.code.toByte(); this[14] = 't'.code.toByte(); this[15] = ' '.code.toByte()
            writeIntLE(this, 16, 16)
            writeShortLE(this, 20, 1)
            writeShortLE(this, 22, channels)
            writeIntLE(this, 24, sampleRate)
            writeIntLE(this, 28, byteRate)
            writeShortLE(this, 32, blockAlign)
            writeShortLE(this, 34, bitsPerSample)
            this[36] = 'd'.code.toByte(); this[37] = 'a'.code.toByte(); this[38] = 't'.code.toByte(); this[39] = 'a'.code.toByte()
            writeIntLE(this, 40, dataSize)
        }
    }

    private fun writeIntLE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShortLE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
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

    private fun onDetectionResult(result: DetectionResult) {
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
