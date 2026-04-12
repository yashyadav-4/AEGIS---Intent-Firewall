package com.intentfirewall

import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log

class CallScreeningService : CallScreeningService() {
    companion object {
        private const val TAG = "AegisCallScreening"
    }

    private val bridgeConfig by lazy { ScamAssistBridgeConfig(this) }

    override fun onScreenCall(callDetails: Call.Details) {
        val callerHandle = callDetails.handle?.schemeSpecificPart ?: "Unknown"
        val callDirection = if (callDetails.callDirection == Call.Details.DIRECTION_INCOMING) "INCOMING" else "OUTGOING"
        AegisCallState.onCallScreened(this, callerHandle)
        
        Log.i(TAG, "🛡️ AEGIS INTERCEPT: Screened $callDirection call from $callerHandle")

        val config = bridgeConfig.load()
        if (config.enabled && config.endpoint.isNotBlank()) {
            Thread {
                val sent = ScamAssistBridgeUploader.sendScreenEvent(
                    settings = config,
                    callerId = callerHandle,
                    callDirection = callDirection,
                    eventTimeMs = System.currentTimeMillis()
                )
                Log.i(TAG, "Scam Assist screen event sent=$sent")
            }.start()
        }

        // Allow the call through, AegisCallMonitor handles the actual deepfake biometrics via TelephonyManager
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()
            
        respondToCall(callDetails, response)
        

        Log.i(TAG, "🟢 AEGIS VERDICT: Call allowed to ring. Biometric monitor standing by.")

        // WAKE UP THE VAD PIPELINE
        try {
            val monitorIntent = android.content.Intent(this, com.intentfirewall.AegisCallMonitor::class.java).apply {
                action = com.intentfirewall.AegisCallMonitor.ACTION_PRIME
                putExtra("caller_id", callerHandle)
            }
            androidx.core.content.ContextCompat.startForegroundService(this, monitorIntent)
            Log.i(TAG, "AegisCallMonitor primed from call screening.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AegisCallMonitor: ${e.message}")
        }
    }
}
