package com.intentfirewall

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log

class ScamCallService : InCallService() {
    private val callbacks = mutableMapOf<Call, Call.Callback>()

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)

        if (!CallProtectionPrefs.isArmed(this)) {
            Log.i("ScamCallService", "Call added but protection is not armed")
            return
        }

        val callerNumber = call.details.handle?.schemeSpecificPart ?: "UNKNOWN"
        val unknownCaller = callerNumber.isBlank() || callerNumber == "UNKNOWN"

        val startIntent = Intent(this, AegisCallMonitor::class.java).apply {
            action = AegisCallMonitor.ACTION_START
            putExtra("caller_number", callerNumber)
            putExtra("caller_unknown", unknownCaller)
        }

        try {
            startForegroundService(startIntent)
        } catch (e: Exception) {
            Log.e("ScamCallService", "Failed to start call monitor", e)
        }

        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                super.onStateChanged(call, state)
                if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
                    val stopIntent = Intent(this@ScamCallService, AegisCallMonitor::class.java).apply {
                        action = AegisCallMonitor.ACTION_STOP
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

        val stopIntent = Intent(this, AegisCallMonitor::class.java).apply {
            action = AegisCallMonitor.ACTION_STOP
        }
        startService(stopIntent)
    }
}
