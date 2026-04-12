package com.intentfirewall

import android.app.ActivityManager
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
import android.text.TextUtils
import android.util.Log
import androidx.core.app.NotificationCompat

object ServiceHealthMonitor {
    enum class HealthState {
        HEALTHY,
        STALE,
        DEAD,
        PERMISSION_REVOKED,
    }

    data class HealthSnapshot(
        val notificationEnabled: Boolean,
        val notificationConnected: Boolean,
        val accessibilityEnabled: Boolean,
        val accessibilityConnected: Boolean,
        val lastNotificationEventAt: Long,
        val lastAccessibilityEventAt: Long,
        val lastSmsFallbackEventAt: Long,
        val healthState: HealthState,
    )

    private const val TAG = "SCAM_ServiceHealthMonitor"
    private const val INTERVAL_MS = 30_000L
    private const val STALE_GAP_MS = 20 * 60_000L
    private const val CHANNEL_ID = "scam_health_alerts"
    private const val NOTIF_ID = 770021

    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile
    private var started = false

    @Volatile
    private var state: HealthState = HealthState.HEALTHY

    private var appContext: Context? = null
    private var notificationConnected = false
    private var accessibilityConnected = false
    private var lastNotificationEventAt = 0L
    private var lastAccessibilityEventAt = 0L
    private var lastSmsFallbackEventAt = 0L

    private val checker = object : Runnable {
        override fun run() {
            appContext?.let { runHealthCheck(it) }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    /**
     * Starts health polling loop once.
     */
    fun ensureStarted(context: Context) {
        if (started) return
        synchronized(lock) {
            if (started) return
            appContext = context.applicationContext
            started = true
            // Delay the first check so cold-start remains responsive.
            handler.postDelayed(checker, INTERVAL_MS)
            Log.i(TAG, "Health monitor started")
        }
    }

    /**
     * Triggers an immediate health check.
     */
    fun runImmediateCheck(context: Context) {
        ensureStarted(context)
        runHealthCheck(context.applicationContext)
    }

    /**
     * Returns latest monitor state.
     */
    fun currentState(): HealthState = state

    /**
     * Hooks monitor lifecycle to foreground service.
     */
    fun attachForegroundService(service: ScamForegroundService) {
        ensureStarted(service.applicationContext)
    }

    /**
     * Detaches lifecycle hooks from foreground service.
     */
    fun detachForegroundService(service: ScamForegroundService) {
        ensureStarted(service.applicationContext)
    }

    fun onNotificationListenerConnected(context: Context) {
        ensureStarted(context)
        notificationConnected = true
    }

    fun onNotificationListenerDisconnected(context: Context) {
        ensureStarted(context)
        notificationConnected = false
    }

    fun onAccessibilityServiceConnected(context: Context) {
        ensureStarted(context)
        accessibilityConnected = true
    }

    fun onAccessibilityServiceDisconnected(context: Context) {
        ensureStarted(context)
        accessibilityConnected = false
    }

    fun onNotificationEventCaptured(context: Context) {
        ensureStarted(context)
        lastNotificationEventAt = System.currentTimeMillis()
    }

    fun onAccessibilityEventCaptured(context: Context) {
        ensureStarted(context)
        lastAccessibilityEventAt = System.currentTimeMillis()
    }

    fun onSmsFallbackUsed(context: Context) {
        ensureStarted(context)
        lastSmsFallbackEventAt = System.currentTimeMillis()
    }

    /**
     * Returns snapshot used by diagnostics screen.
     */
    fun getSnapshot(context: Context): HealthSnapshot {
        ensureStarted(context)
        return HealthSnapshot(
            notificationEnabled = isNotificationListenerEnabled(context),
            notificationConnected = notificationConnected,
            accessibilityEnabled = isAccessibilityServiceEnabled(context),
            accessibilityConnected = accessibilityConnected,
            lastNotificationEventAt = lastNotificationEventAt,
            lastAccessibilityEventAt = lastAccessibilityEventAt,
            lastSmsFallbackEventAt = lastSmsFallbackEventAt,
            healthState = state,
        )
    }

    fun getPreferredCaptureMethod(context: Context): String {
        val snapshot = getSnapshot(context)
        if (snapshot.notificationEnabled && snapshot.notificationConnected) return "notification"
        if (snapshot.accessibilityEnabled && snapshot.accessibilityConnected) return "accessibility"
        return "sms_fallback"
    }

    private fun runHealthCheck(context: Context) {
        val accessibilityEnabled = isAccessibilityServiceEnabled(context)
        val notificationEnabled = isNotificationListenerEnabled(context)
        val foregroundRunning = isServiceRunning(context, ScamForegroundService::class.java)
        val lastEvent = context
            .getSharedPreferences(ScamForegroundService.PREF_HEALTH, Context.MODE_PRIVATE)
            .getLong(ScamForegroundService.KEY_LAST_EVENT_AT, 0L)

        val now = System.currentTimeMillis()
        val anyCapturePermission = accessibilityEnabled || notificationEnabled
        val anyCaptureConnected =
            (accessibilityEnabled && accessibilityConnected) ||
                (notificationEnabled && notificationConnected)
        val stale = isActiveHours() && anyCaptureConnected && lastEvent > 0L && now - lastEvent > STALE_GAP_MS

        val next = when {
            !anyCapturePermission -> HealthState.PERMISSION_REVOKED
            !foregroundRunning -> HealthState.DEAD
            stale -> HealthState.STALE
            else -> HealthState.HEALTHY
        }

        state = next

        if (next == HealthState.DEAD || next == HealthState.PERMISSION_REVOKED) {
            if (OemWhitelistGuide.shouldPrompt(context)) {
                showPausedNotification(context)
            }
            Log.w(
                TAG,
                "state=${next.name} model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
                    "accessibilityEnabled=$accessibilityEnabled notificationEnabled=$notificationEnabled " +
                    "foregroundRunning=$foregroundRunning lastGap=${now - lastEvent}",
            )
        } else if (next == HealthState.STALE) {
            Log.w(
                TAG,
                "state=${next.name} model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
                    "accessibilityEnabled=$accessibilityEnabled notificationEnabled=$notificationEnabled " +
                    "foregroundRunning=$foregroundRunning lastGap=${now - lastEvent}",
            )
        } else {
            clearPausedNotification(context)
        }
    }

    private fun isActiveHours(): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return hour in 8..23
    }

    private fun isServiceRunning(context: Context, clazz: Class<*>): Boolean {
        return try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            @Suppress("DEPRECATION")
            manager.getRunningServices(256).any { it.service.className == clazz.name }
        } catch (e: Exception) {
            Log.e(TAG, "isServiceRunning failed", e)
            false
        }
    }

    private fun isNotificationListenerEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        val component = ComponentName(context, NotificationService::class.java).flattenToString()
        return enabled.contains(component)
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        return try {
            val enabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0,
            ) == 1
            if (!enabled) return false

            val listed = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false

            val full = ComponentName(context, ScamAccessibilityService::class.java).flattenToString()
            val short = ComponentName(context, ScamAccessibilityService::class.java).flattenToShortString()

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(listed)
            while (splitter.hasNext()) {
                val service = splitter.next()
                if (service.equals(full, ignoreCase = true) || service.equals(short, ignoreCase = true)) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "isAccessibilityServiceEnabled failed", e)
            false
        }
    }

    private fun showPausedNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.health_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.health_channel_desc)
            }
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(context, OemWhitelistGuide::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            91,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentTitle(context.getString(R.string.health_paused_title))
            .setContentText(context.getString(R.string.health_paused_body))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(NOTIF_ID, notification)
    }

    private fun clearPausedNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIF_ID)
    }
}