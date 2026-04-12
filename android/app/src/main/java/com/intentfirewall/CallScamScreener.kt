package com.intentfirewall

import android.content.Intent
import android.os.Build
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
        emitIncomingDetectedEvent(number)

        scope.launch {
            try {
                val risk = NumberRiskEngine.evaluate(applicationContext, number)
                Log.d(tag, "Risk score for $number: ${risk.score} (${risk.reason})")

                val response = CallResponse.Builder()

                when {
                    risk.score >= 85 -> {
                        response
                            .setDisallowCall(true)
                            .setRejectCall(true)
                            .setSkipCallLog(false)
                            .setSkipNotification(false)
                        Log.d(tag, "AUTO-REJECTED call from $number")
                        emitBlockedCallEvent(number, risk)
                    }

                    risk.score >= 55 -> {
                        response.setSilenceCall(true)
                        Log.d(tag, "SILENCED suspicious call from $number")
                        emitSuspiciousCallEvent(number, risk)
                        startCallAudioMonitor(number)
                    }

                    else -> {
                        Log.d(tag, "ALLOWED call from $number (score: ${risk.score})")
                        startCallAudioMonitor(number)
                    }
                }

                respondToCall(callDetails, response.build())
            } catch (e: Exception) {
                Log.e(tag, "Error screening call, defaulting to allow", e)
                respondToCall(callDetails, CallResponse.Builder().build())
            }
        }
    }

    private fun emitBlockedCallEvent(number: String, risk: NumberRiskEngine.RiskResult) {
        val data = android.os.Bundle().apply {
            putString("type", "call_blocked")
            putString("number", number)
            putInt("riskScore", risk.score)
            putString("reason", risk.reason)
            putString("captureMethod", "call_screening_pre_answer")
            putString("tierUsed", "tier0-screening")
        }
        NotificationEventEmitter.sendCallEvent(applicationContext, data)
        CallRiskAlertNotifier.show(
            context = applicationContext,
            type = "call_blocked",
            number = number,
            riskScore = risk.score,
            reason = risk.reason,
        )
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
        CallRiskAlertNotifier.show(
            context = applicationContext,
            type = "call_suspicious",
            number = number,
            riskScore = risk.score,
            reason = risk.reason,
        )
    }

    private fun startCallAudioMonitor(number: String) {
        try {
            val intent = Intent(applicationContext, CallAudioMonitorService::class.java).apply {
                action = CallAudioMonitorService.ACTION_START
                putExtra(CallAudioMonitorService.EXTRA_CALLER_NUMBER, number)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to start call audio monitor", e)
        }
    }

    private fun emitIncomingDetectedEvent(number: String) {
        val data = android.os.Bundle().apply {
            putString("type", "call_incoming_detected")
            putString("number", number)
            putString("message", "Incoming call detected")
            putString("captureMethod", "call_screening_pre_answer")
            putString("tierUsed", "tier0-screening")
        }
        NotificationEventEmitter.sendCallEvent(applicationContext, data)
    }
}
