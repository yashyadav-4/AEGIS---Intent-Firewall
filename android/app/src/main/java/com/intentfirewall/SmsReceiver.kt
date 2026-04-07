package com.intentfirewall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            Log.d("IntentFirewall|SmsReceiver", "SMS Broadcast received")

            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages == null) return

            val pipeline = DetectionPipeline(context)

            for (sms in messages) {
                val sender = sms.displayOriginatingAddress ?: "Unknown"
                val body = sms.displayMessageBody ?: ""

                Log.d("IntentFirewall|SmsReceiver", "From: $sender | Body: $body")

                if (MessageConsistencyCoordinator.shouldProcessSms(sender, body)) {
                    ServiceHealthMonitor.onSmsFallbackUsed(context)

                    pipeline.processMessage(
                        appName = "SMS",
                        packageName = "com.android.mms",
                        title = sender,
                        text = body,
                        sender = sender,
                        appSource = "SMS",
                        captureMethod = "sms_fallback",
                        eventTimestamp = sms.timestampMillis,
                    )
                }
            }
        }
    }
}