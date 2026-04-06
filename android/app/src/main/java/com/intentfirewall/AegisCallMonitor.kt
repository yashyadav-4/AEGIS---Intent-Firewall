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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AegisCallMonitor : Service() {

    companion object {
        private const val TAG = "AegisCallMonitor"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 3200
        private const val WINDOW_SAMPLES = 32000
        private const val OVERLAP_SAMPLES = 16000
        private const val FLAT_PITCH_THRESHOLD = 0.15f
        private const val CHANNEL_ID = "aegis_protection"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.aegis.START_MONITOR"
        const val ACTION_STOP = "com.aegis.STOP_MONITOR"
    }

    private var audioRecord: AudioRecord? = null
    private var detector: AegisAudioDetector? = null
    private val accumulator = FloatArray(WINDOW_SAMPLES)
    private var accumulatorPos = 0
    private var isRecording = false
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        detector = AegisAudioDetector(this)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:AegisCallMonitor").apply {
            setReferenceCounted(false)
        }

        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_IDLE -> {
                        stopCapture()
                    }

                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        val isUnknown = incomingNumber.isNullOrEmpty()
                        detector?.onCallMetadataUpdate(
                            isUnknownNumber = isUnknown,
                            isVideoCall = false
                        )
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
        startForeground(NOTIFICATION_ID, buildNotification())

        when (intent?.action) {
            ACTION_START -> startCapture()
            ACTION_STOP -> {
                stopCapture()
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
        detector?.close()
        detector = null

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

        val newRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord initialization failed", e)
            return
        }

        if (newRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            newRecord.release()
            return
        }

        try {
            newRecord.startRecording()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to start recording", e)
            newRecord.release()
            return
        }

        audioRecord = newRecord
        isRecording = true

        if (wakeLock?.isHeld != true) {
            wakeLock?.acquire()
        }

        serviceScope.launch(Dispatchers.IO) {
            val buffer = ShortArray(CHUNK_SAMPLES)

            while (isRecording && isActive) {
                val read = try {
                    audioRecord?.read(buffer, 0, CHUNK_SAMPLES) ?: break
                } catch (e: Exception) {
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
                                }
                            } catch (e: Exception) {
                                Log.e("IntentFirewall", "Audio detection failed: ${e.message}", e)
                            }
                        }
                    }

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
