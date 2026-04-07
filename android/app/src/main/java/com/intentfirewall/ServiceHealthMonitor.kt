package com.intentfirewall

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationCompat

object ServiceHealthMonitor {
    private const val TAG = "IntentFirewall|Health"
    private const val HEALTH_CHECK_INTERVAL_MS = 30_000L
    private const val DOWN_ALERT_NOTIFICATION_ID = 770021
    private const val DOWN_ALERT_CHANNEL_ID = "service_health_alerts"

    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var started = false
    private var appContext: Context? = null

    private var notificationConnected = false
    private var accessibilityConnected = false
    private var lastNotificationEventAt = 0L
    private var lastAccessibilityEventAt = 0L
    private var lastSmsFallbackEventAt = 0L
    private var lastHealthLogAt = 0L

    private val healthRunnable = object : Runnable {
        override fun run() {
            val context = appContext
            if (context != null) {
                runHealthCheck(context)
                handler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS)
            }
        }
    }

    fun ensureStarted(context: Context) {
        if (started) return
        synchronized(lock) {
            if (started) return
            appContext = context.applicationContext
            started = true
            handler.post(healthRunnable)
            Log.i(TAG, "Service health monitor started")
        }
    }

    fun onNotificationListenerConnected(context: Context) {
        ensureStarted(context)
        synchronized(lock) {
            notificationConnected = true
        }
    }

    fun onNotificationListenerDisconnected(context: Context) {
        ensureStarted(context)
        synchronized(lock) {
            notificationConnected = false
        }
    }

    fun onAccessibilityServiceConnected(context: Context) {
        ensureStarted(context)
        synchronized(lock) {
            accessibilityConnected = true
        }
    }

    fun onAccessibilityServiceDisconnected(context: Context) {
        ensureStarted(context)
        synchronized(lock) {
            accessibilityConnected = false
        }
    }

    fun onNotificationEventCaptured(context: Context) {
        ensureStarted(context)
        synchronized(lock) {
            lastNotificationEventAt = System.currentTimeMillis()
        }
    }

    fun onAccessibilityEventCaptured(context: Context) {
        ensureStarted(context)
        synchronized(lock) {
            lastAccessibilityEventAt = System.currentTimeMillis()
        }
    }

    fun onSmsFallbackUsed(context: Context) {
        ensureStarted(context)
        synchronized(lock) {
            lastSmsFallbackEventAt = System.currentTimeMillis()
        }
    }

    fun getPreferredCaptureMethod(context: Context): String {
        val notificationHealthy = isNotificationListenerEnabled(context) && notificationConnected
        if (notificationHealthy) return "notification"

        val accessibilityHealthy = isAccessibilityServiceEnabled(context) && accessibilityConnected
        if (accessibilityHealthy) return "accessibility"

        return "sms_fallback"
    }

    private fun runHealthCheck(context: Context) {
        val now = System.currentTimeMillis()
        val notificationEnabled = isNotificationListenerEnabled(context)
        val accessibilityEnabled = isAccessibilityServiceEnabled(context)

        val notificationHealthy: Boolean
        val accessibilityHealthy: Boolean
        val notifLastEvent: Long
        val accessLastEvent: Long
        val smsLastEvent: Long

        synchronized(lock) {
            notificationHealthy = notificationEnabled && notificationConnected
            accessibilityHealthy = accessibilityEnabled && accessibilityConnected
            notifLastEvent = lastNotificationEventAt
            accessLastEvent = lastAccessibilityEventAt
            smsLastEvent = lastSmsFallbackEventAt
        }

        if (notificationEnabled && !notificationConnected) {
            NotificationListenerService.requestRebind(
                ComponentName(context, NotificationService::class.java)
            )
            Log.w(TAG, "Notification listener enabled but disconnected; requested rebind")
        }

        if (now - lastHealthLogAt >= HEALTH_CHECK_INTERVAL_MS) {
            lastHealthLogAt = now
            Log.i(
                TAG,
                "health notifEnabled=$notificationEnabled notifConnected=$notificationConnected " +
                    "accessEnabled=$accessibilityEnabled accessConnected=$accessibilityConnected " +
                    "lastNotifEvent=${if (notifLastEvent == 0L) 0 else (now - notifLastEvent)}ms " +
                    "lastAccessEvent=${if (accessLastEvent == 0L) 0 else (now - accessLastEvent)}ms " +
                    "lastSmsFallback=${if (smsLastEvent == 0L) 0 else (now - smsLastEvent)}ms"
            )
        }

        if (!notificationHealthy && !accessibilityHealthy) {
            showBothServicesDownAlert(context)
            return
        }

        clearBothServicesDownAlert(context)
    }

    private fun isNotificationListenerEnabled(context: Context): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        val component = ComponentName(context, NotificationService::class.java).flattenToString()
        return enabledListeners.contains(component)
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        try {
            val expectedClass = OpenChatAccessibilityService::class.java.name
            val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
            val enabledServices = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )

            for (service in enabledServices) {
                val info = service.resolveInfo?.serviceInfo ?: continue
                if (info.packageName == context.packageName && info.name == expectedClass) {
                    return true
                }
            }

            val enabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) == 1
            if (!enabled) return false

            val listed = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val full = ComponentName(context, OpenChatAccessibilityService::class.java).flattenToString()
            val short = ComponentName(context, OpenChatAccessibilityService::class.java).flattenToShortString()

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(listed)
            while (splitter.hasNext()) {
                val service = splitter.next()
                if (service.equals(full, ignoreCase = true) || service.equals(short, ignoreCase = true)) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed checking accessibility service state", e)
        }

        return false
    }

    private fun showBothServicesDownAlert(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DOWN_ALERT_CHANNEL_ID,
                "Aegis Service Health",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when capture services are down"
            }
            manager.createNotificationChannel(channel)
        }

        val settingsIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        val settingsPendingIntent = PendingIntent.getActivity(
            context,
            3201,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, DOWN_ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Aegis monitoring needs attention")
            .setContentText("Notification and Accessibility capture are both unavailable. Tap to re-enable services.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Notification and Accessibility capture are both unavailable. " +
                        "Enable at least one capture method to keep protection active."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(settingsPendingIntent)
            .build()

        manager.notify(DOWN_ALERT_NOTIFICATION_ID, notification)
    }

    private fun clearBothServicesDownAlert(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(DOWN_ALERT_NOTIFICATION_ID)
    }
}