package com.intentfirewall

import android.content.Intent
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class AegisCallScreeningService : CallScreeningService() {

    companion object {
        private const val TAG = "AegisScreening"
        private const val RESPOND_TIMEOUT_MS = 4000L
    }

    override fun onScreenCall(callDetails: Call.Details) {
        val handle = callDetails.handle?.schemeSpecificPart ?: ""
        val direction = callDetails.callDirection
        val verificationStatus = callDetails.callerNumberVerificationStatus

        Log.i(
            TAG,
            "Screening: number=$handle direction=$direction verification=$verificationStatus"
        )

        if (direction != Call.Details.DIRECTION_INCOMING) {
            allowCall(callDetails)
            return
        }

        var riskScore = 0.0f
        val reasons = mutableListOf<String>()

        when (verificationStatus) {
            Connection.VERIFICATION_STATUS_FAILED -> {
                riskScore += 0.45f
                reasons.add("STIR_FAILED")
                Log.w(TAG, "STIR/SHAKEN verification FAILED")
            }
            Connection.VERIFICATION_STATUS_PASSED -> {
                riskScore -= 0.10f
                reasons.add("STIR_PASSED")
            }
            else -> {
                riskScore += 0.10f
                reasons.add("STIR_UNKNOWN")
            }
        }

        if (handle.isBlank()) {
            riskScore += 0.35f
            reasons.add("WITHHELD_NUMBER")
        }

        val dbResult = ScamDatabase.check(handle)
        riskScore += dbResult.riskScore
        if (dbResult.riskScore > 0f) {
            reasons.add(dbResult.reason)
        }

        val finalScore = riskScore.coerceIn(0f, 1f)
        val reasonStr = reasons.joinToString("|")

        Log.i(TAG, "Risk: score=$finalScore reasons=$reasonStr")

        when {
            finalScore >= 0.80f -> {
                Log.w(TAG, "HIGH RISK — blocking call")
                blockCall(callDetails, reasonStr, finalScore)
            }

            finalScore >= 0.45f -> {
                Log.w(TAG, "MEDIUM RISK — silencing + overlay")
                silenceAndWarn(callDetails, handle, reasonStr, finalScore)
            }

            else -> {
                Log.i(TAG, "LOW RISK — allowing call")
                allowCall(callDetails)
            }
        }
    }

    private fun allowCall(callDetails: Call.Details) {
        respondToCall(
            callDetails,
            CallResponse.Builder()
                .setDisallowCall(false)
                .setRejectCall(false)
                .setSilenceCall(false)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
        )
    }

    private fun blockCall(
        callDetails: Call.Details,
        reason: String,
        score: Float
    ) {
        respondToCall(
            callDetails,
            CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSilenceCall(true)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
        )
        broadcastThreatDetected(
            callDetails.handle?.schemeSpecificPart ?: "",
            reason,
            score,
            "BLOCKED"
        )
    }

    private fun silenceAndWarn(
        callDetails: Call.Details,
        number: String,
        reason: String,
        score: Float
    ) {
        respondToCall(
            callDetails,
            CallResponse.Builder()
                .setDisallowCall(false)
                .setRejectCall(false)
                .setSilenceCall(true)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
        )
        showCallWarningOverlay(number, reason, score)
        broadcastThreatDetected(number, reason, score, "SILENCED")
    }

    private fun showCallWarningOverlay(
        number: String,
        reason: String,
        score: Float
    ) {
        val intent = Intent(this, CallWarningOverlayService::class.java).apply {
            putExtra("CALLER_NUMBER", number)
            putExtra("RISK_REASON", reason)
            putExtra("RISK_SCORE", score)
        }
        startService(intent)
    }

    private fun broadcastThreatDetected(
        number: String,
        reason: String,
        score: Float,
        action: String
    ) {
        LocalBroadcastManager.getInstance(this)
            .sendBroadcast(
                Intent("com.aegis.CALL_THREAT").apply {
                    putExtra("number", number)
                    putExtra("reason", reason)
                    putExtra("score", score)
                    putExtra("action", action)
                }
            )
    }
}