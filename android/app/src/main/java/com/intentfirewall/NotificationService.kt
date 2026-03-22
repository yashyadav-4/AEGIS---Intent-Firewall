package com.intentfirewall

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        Log.d("IntentFirewall", "Notification received from: $packageName")

        val extras = sbn.notification.extras

        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        Log.d("IntentFirewall", "Title: $title | Text: $text")

        // Only process relevant apps
        val trackedApps = listOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "com.android.mms",
            "com.google.android.apps.messaging"
        )

        if (packageName in trackedApps && text.isNotEmpty()) {
            val appName = getAppName(packageName)
            Log.d("IntentFirewall", "Scam check for $appName: $text")

            NotificationEventEmitter.sendNotification(
                applicationContext,
                appName,
                title,
                text,
                packageName
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    private fun getAppName(packageName: String): String {
        return when (packageName) {
            "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
            "org.telegram.messenger" -> "Telegram"
            "com.android.mms", "com.google.android.apps.messaging" -> "SMS"
            else -> "Unknown"
        }
    }
}