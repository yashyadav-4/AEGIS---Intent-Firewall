package com.intentfirewall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

class ScamAlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ScamAlertNotifier.ACTION_DISCARD) return

        val notificationId = intent.getIntExtra(ScamAlertNotifier.EXTRA_NOTIFICATION_ID, Int.MIN_VALUE)
        if (notificationId != Int.MIN_VALUE) {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }
    }
}
