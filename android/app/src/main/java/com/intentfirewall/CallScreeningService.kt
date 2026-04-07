package com.intentfirewall

import android.telecom.Call
import android.telecom.CallScreeningService

class CallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        // TODO: Implement call screening logic
        // For now, respond with PASS (allow all calls)
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()
        respondToCall(callDetails, response)
    }
}
