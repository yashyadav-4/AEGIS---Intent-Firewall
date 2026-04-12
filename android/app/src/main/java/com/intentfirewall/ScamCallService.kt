package com.intentfirewall

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import android.telephony.TelephonyManager
import android.util.Log

class ScamCallService : InCallService() {
    private val callbacks = mutableMapOf<Call, Call.Callback>()
    private val tag = "ScamCallService"

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)

        if (!CallProtectionPrefs.isArmed(this)) {
            Log.i(tag, "Call added but protection is not armed")
            return
        }

        val callerNumber = call.details.handle?.schemeSpecificPart ?: "UNKNOWN"
        val unknownCaller = callerNumber.isBlank() || callerNumber == "UNKNOWN"

        val startIntent = Intent(this, CallAudioMonitorService::class.java).apply {
            action = CallAudioMonitorService.ACTION_START
            putExtra(CallAudioMonitorService.EXTRA_CALLER_NUMBER, if (unknownCaller) "unknown" else callerNumber)
        }

        try {
            startForegroundService(startIntent)
        } catch (e: Exception) {
            Log.e(tag, "Failed to start call monitor", e)
        }

        sendCallStateHint(call.state, if (unknownCaller) null else callerNumber)

        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                super.onStateChanged(call, state)
                sendCallStateHint(state, call.details.handle?.schemeSpecificPart)
                if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
                    val stopIntent = Intent(this@ScamCallService, CallAudioMonitorService::class.java).apply {
                        action = CallAudioMonitorService.ACTION_STOP
                    }
                    startService(stopIntent)
                }
            }
        }

        callbacks[call] = callback
        call.registerCallback(callback)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)

        callbacks.remove(call)?.let {
            try {
                call.unregisterCallback(it)
            } catch (_: Exception) {
            }
        }

        val stopIntent = Intent(this, CallAudioMonitorService::class.java).apply {
            action = CallAudioMonitorService.ACTION_STOP
        }
        startService(stopIntent)
    }

    private fun sendCallStateHint(callState: Int, rawNumber: String?) {
        val telephonyState = mapToTelephonyState(callState) ?: return
        val normalized = rawNumber?.trim().orEmpty()

        val hintIntent = Intent(this, CallAudioMonitorService::class.java).apply {
            action = CallAudioMonitorService.ACTION_HINT_STATE
            putExtra(CallAudioMonitorService.EXTRA_HINT_STATE, telephonyState)
            putExtra(CallAudioMonitorService.EXTRA_HINT_SOURCE, "InCallService")
            if (normalized.isNotBlank()) {
                putExtra(CallAudioMonitorService.EXTRA_CALLER_NUMBER, normalized)
            }
        }

        try {
            startService(hintIntent)
        } catch (e: Exception) {
            Log.w(tag, "Failed to dispatch call state hint", e)
        }
    }

    private fun mapToTelephonyState(callState: Int): Int? {
        return when (callState) {
            Call.STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
            Call.STATE_ACTIVE,
            Call.STATE_DIALING,
            Call.STATE_CONNECTING,
            Call.STATE_HOLDING -> TelephonyManager.CALL_STATE_OFFHOOK
            Call.STATE_DISCONNECTED,
            Call.STATE_DISCONNECTING -> TelephonyManager.CALL_STATE_IDLE
            else -> null
        }
    }
}
