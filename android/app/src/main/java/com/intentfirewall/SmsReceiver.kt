package com.intentfirewall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val action = intent.action.orEmpty()
        if (action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION &&
            action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) {
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (!messages.isNullOrEmpty()) {
            messages.forEach { sms ->
                val body = sms.messageBody.orEmpty()
                val sender = sms.displayOriginatingAddress
                AegisCallState.onSmsSignal(context, sender, body)
            }
            return
        }

        // Fallback parser for OEMs that do not populate helper API.
        val extras = intent.extras ?: return
        @Suppress("UNCHECKED_CAST")
        val pdus = extras.get("pdus") as? Array<Any> ?: return
        val format = extras.getString("format")

        for (pdu in pdus) {
            val message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                SmsMessage.createFromPdu(pdu as ByteArray, format)
            } else {
                @Suppress("DEPRECATION")
                SmsMessage.createFromPdu(pdu as ByteArray)
            }

            if (message != null) {
                AegisCallState.onSmsSignal(
                    context,
                    message.displayOriginatingAddress,
                    message.messageBody.orEmpty()
                )
            }
        }

        Log.d("AegisSms", "SMS broadcast processed for side-channel risk")
    }
}
