package com.intentfirewall

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object ScamAlertNotifier {
    private const val CHANNEL_ID = "scam_alerts"
    private const val CHANNEL_NAME = "Scam Alerts"
    private const val CHANNEL_DESC = "High-priority scam detection alerts"

    const val ACTION_DISCARD = "com.intentfirewall.ACTION_DISCARD_THREAT"
    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

    fun showAlert(
        context: Context,
        appName: String,
        message: String,
        category: String,
        confidence: Float,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                return
            }
        }

        createChannelIfNeeded(context)

        val normalized = message.trim().lowercase().replace("\\s+".toRegex(), " ")
        if (normalized.isBlank()) return

        val key = "$appName|$category|$normalized"
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences("scam_alert_notifier", Context.MODE_PRIVATE)
        val lastShownAt = prefs.getLong("last_$key", 0L)

        // Prevent repeated alert spam for the same content in a short window.
        if (now - lastShownAt < 15_000L) {
            return
        }
        prefs.edit().putLong("last_$key", now).apply()

        val notificationId = key.hashCode()

        val discardIntent = Intent(context, ScamAlertActionReceiver::class.java).apply {
            action = ACTION_DISCARD
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }

        val discardPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            discardIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val alertIntent = Intent(context, ScamAlertActivity::class.java).apply {
            putExtra("appName", appName)
            putExtra("message", message)
            putExtra("category", category)
            putExtra("confidence", confidence)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val alertPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val confidencePercent = (confidence * 100).toInt().coerceIn(0, 99)
        val body = if (category.isNotBlank()) {
            "$category detected in $appName: $message"
        } else {
            "Potential scam detected in $appName: $message"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Scam Alert")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(alertPendingIntent)
            .setFullScreenIntent(alertPendingIntent, true)
            .setSubText("Confidence: $confidencePercent%")
            .addAction(0, "Discard Threat", discardPendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    private fun createChannelIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = CHANNEL_DESC
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        manager.createNotificationChannel(channel)
    }
}
