package com.intentfirewall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) {
            return
        }

        val sender = messages.firstOrNull()?.originatingAddress?.trim().orEmpty().ifEmpty { "Unknown Sender" }
        val body = messages.joinToString(separator = "") {
            it.messageBody.orEmpty()
        }.trim()

        if (body.isEmpty()) {
            Log.d("IntentFirewall", "SMS broadcast received but body was empty.")
            return
        }

        if (MessageConsistencyCoordinator.hasRecentSmsMatch(sender, body)) {
            Log.d("IntentFirewall", "Skipping duplicate SMS broadcast from: $sender")
            return
        }
        MessageConsistencyCoordinator.noteSms(sender, body)

        val smsKey = "android.sms.broadcast"
        ContextBuffer.addTurn(smsKey, "[THEM]", body)
        val contextText = ContextBuffer.getContext(smsKey)

        NotificationEventEmitter.sendNotification(
            context = context.applicationContext,
            appName = "SMS",
            title = sender,
            text = body,
            packageName = "com.android.providers.telephony",
            flagged = false,
            matchedCategory = "SMS_BROADCAST_DIRECT",
            conversationContext = contextText
        )

        Log.d("IntentFirewall", "SMS broadcast captured from: $sender | Text: $body")
    }
}
