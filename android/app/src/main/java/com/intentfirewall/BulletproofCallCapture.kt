package com.intentfirewall

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log

/**
 * Multi-strategy capture with priority escalation:
 * 1. AudioRecord (Standard API)
 * 2. CallScreeningService (Telecom API, often bypasses MIC restrictions during calls)
 * 3. AccessibilityService (Fallback for capturing system audio / UI states if directly recording fails)
 */
class BulletproofCallCapture(private val context: Context) {
    companion object {
        private const val TAG = "BulletproofCallCapture"
        private const val SAMPLE_RATE = 16000
    }

    private var audioRecord: AudioRecord? = null
    var isCapturing = false
        private set

    fun startCapture(): AudioRecord? {
        if (isCapturing) return audioRecord

        Log.i(TAG, "Attempting to start capture using multi-strategy escalation...")

        // Strategy 1: Standard AudioRecord
        audioRecord = tryStandardAudioRecord()
        if (audioRecord != null && audioRecord!!.state == AudioRecord.STATE_INITIALIZED) {
            try {
                audioRecord!!.startRecording()
                if (audioRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    Log.i(TAG, "Strategy 1 Success: Standard AudioRecord started.")
                    isCapturing = true
                    return audioRecord
                } else {
                    Log.w(TAG, "Standard AudioRecord initialized but failed to start recording.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Standard AudioRecord Exception", e)
            }
        }

        // Strategy 2: Voice Recognition (Maximizes concurrent sharing with SpeechRecognizer)
        Log.i(TAG, "Strategy 1 Failed. Attempting Strategy 2: Voice Recognition Source")
        audioRecord = tryVoiceRecognitionRecord()
        if (audioRecord != null && audioRecord!!.state == AudioRecord.STATE_INITIALIZED) {
            try {
                audioRecord!!.startRecording()
                if (audioRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    Log.i(TAG, "Strategy 2 Success: Voice Recognition AudioRecord started.")
                    isCapturing = true
                    return audioRecord
                }
            } catch (e: Exception) {
                Log.e(TAG, "Voice Recognition AudioRecord Exception", e)
            }
        }

        // Strategy 3: Voice Communication / CallScreening
        Log.i(TAG, "Strategy 2 Failed. Attempting Strategy 3: Voice Communication Source")
        audioRecord = tryVoiceCommunicationRecord()
        if (audioRecord != null && audioRecord!!.state == AudioRecord.STATE_INITIALIZED) {
            try {
                audioRecord!!.startRecording()
                if (audioRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    Log.i(TAG, "Strategy 3 Success: Voice Communication AudioRecord started.")
                    isCapturing = true
                    return audioRecord
                }
            } catch (e: Exception) {
                Log.e(TAG, "Voice Communication AudioRecord Exception", e)
            }
        }

        Log.e(TAG, "All direct audio capture strategies failed. Relying on CallScreeningService/AccessibilityService as fallback.")
        return null
    }

    private fun tryStandardAudioRecord(): AudioRecord? {
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            return AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Mic permission denied", e)
        } catch (e: Exception) {
            Log.e(TAG, "Standard AudioRecord creation failed", e)
        }
        return null
    }

    private fun tryVoiceRecognitionRecord(): AudioRecord? {
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            return AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Mic permission denied (Voice Recognition)", e)
        } catch (e: Exception) {
            Log.e(TAG, "Voice Recognition AudioRecord creation failed", e)
        }
        return null
    }

    private fun tryVoiceCommunicationRecord(): AudioRecord? {
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            return AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Mic permission denied (Voice Comm)", e)
        } catch (e: Exception) {
            Log.e(TAG, "Voice Communication AudioRecord creation failed", e)
        }
        return null
    }

    fun stopCapture() {
        if (!isCapturing) return
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping capture", e)
        } finally {
            audioRecord = null
            isCapturing = false
            Log.i(TAG, "Capture stopped and released.")
        }
    }
}
