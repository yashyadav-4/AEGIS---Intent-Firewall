package com.intentfirewall

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object CallRiskAlertNotifier {
    private const val CHANNEL_ID = "call_risk_alerts"
    private const val CHANNEL_NAME = "Call Risk Alerts"
    private const val CHANNEL_DESC = "Vibration alerts for suspicious or scam calls"

    private val vibrationPattern = longArrayOf(0, 300, 200, 400, 200, 500)

    fun show(
        context: Context,
        type: String,
        number: String,
        riskScore: Int,
        reason: String,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        createChannelIfNeeded(context)

        val title = when (type) {
            "call_blocked" -> "Blocked spam call"
            "call_suspicious" -> "Suspicious incoming call"
            else -> "Potential scam call"
        }

        val safeNumber = number.ifBlank { "unknown" }
        val details = if (reason.isBlank()) {
            "Number: $safeNumber | Risk: $riskScore/100"
        } else {
            "$reason (Risk: $riskScore/100)"
        }

        val notificationId = ("$type|$safeNumber").hashCode()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(details)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(vibrationPattern)
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
            vibrationPattern = this@CallRiskAlertNotifier.vibrationPattern
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        manager.createNotificationChannel(channel)
    }
}
