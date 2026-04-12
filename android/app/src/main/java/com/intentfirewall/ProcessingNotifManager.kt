package com.intentfirewall

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat

/** Shows/dismisses transient processing notification while Tier 3 is running. */
object ProcessingNotifManager {
    private const val TAG = "SCAM_ProcessingNotifManager"
    private const val CHANNEL_ID = "scam_processing_channel"
    private const val CHANNEL_NAME = "Scam analysis in progress"
    private const val NOTIFICATION_ID = 2001
    private const val AUTO_DISMISS_MS = 12_000L

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private val autoDismissRunnable = Runnable { dismissProcessing() }

    /** Initialize manager with app context once during app/service startup. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        createChannel()
    }

    /** Show status notification and schedule auto-dismiss after 12 seconds. */
    fun showProcessing(packageName: String, preview: String) {
        val context = appContext ?: return
        createChannel()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val content = "Analyzing for scam patterns. This takes a few seconds."

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Checking this message...")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        manager.notify(NOTIFICATION_ID, notif)
        handler.removeCallbacks(autoDismissRunnable)
        handler.postDelayed(autoDismissRunnable, AUTO_DISMISS_MS)
    }

    /** Dismiss processing notification and clear pending timeout callback. */
    fun dismissProcessing() {
        val context = appContext ?: return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.cancel(NOTIFICATION_ID)
        handler.removeCallbacks(autoDismissRunnable)
    }

    private fun createChannel() {
        val context = appContext ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }
}
