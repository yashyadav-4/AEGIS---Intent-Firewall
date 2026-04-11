package com.intentfirewall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

enum class HealthStatus {
    ALIVE,
    DEGRADED,
    DEAD,
}

class ScamForegroundService : Service() {
    inner class ServiceBinder : Binder() {
        fun getService(): ScamForegroundService = this@ScamForegroundService
    }

    companion object {
        private const val TAG = "SCAM_ScamForegroundService"
        private const val CHANNEL_ID = "SCAM_DETECTION_CHANNEL"
        private const val NOTIFICATION_ID = 4101
        private const val HEALTH_INTERVAL_MS = 10_000L
        private const val EVENT_TIMEOUT_MS = 60_000L

        const val ACTION_HEALTH_CHANGED = "com.intentfirewall.ACTION_HEALTH_CHANGED"
        const val EXTRA_HEALTH = "extra_health"
        const val PREF_HEALTH = "scam_health_monitor"
        const val KEY_LAST_EVENT_AT = "last_event_at"
    }

    private val binder = ServiceBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val contextStore = ConcurrentHashMap<String, ArrayDeque<String>>()

    @Volatile
    private var healthStatus: HealthStatus = HealthStatus.ALIVE

    @Volatile
    private var lastEventAt: Long = 0L

    private val healthRunnable = object : Runnable {
        override fun run() {
            try {
                val now = System.currentTimeMillis()
                if (lastEventAt > 0L && now - lastEventAt >= EVENT_TIMEOUT_MS) {
                    reportServiceHealth(HealthStatus.DEGRADED)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Health tick failed", e)
            } finally {
                handler.postDelayed(this, HEALTH_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        handler.post(healthRunnable)
        ServiceHealthMonitor.ensureStarted(applicationContext)
        ServiceHealthMonitor.attachForegroundService(this)
        Log.i(TAG, "Foreground service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        reportServiceHealth(HealthStatus.ALIVE)
        return binder
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        ServiceHealthMonitor.detachForegroundService(this)
        super.onDestroy()
    }

    /**
     * Tracks a newly captured text event for package-specific context.
     */
    fun reportEvent(pkg: String, text: String, timestamp: Long) {
        try {
            if (pkg.isBlank() || text.isBlank()) return
            val queue = contextStore.getOrPut(pkg) { ArrayDeque() }
            if (queue.size >= 5) {
                queue.removeFirst()
            }
            queue.addLast(text)
            lastEventAt = timestamp
            val prefs = getSharedPreferences(PREF_HEALTH, Context.MODE_PRIVATE)
            prefs.edit().putLong(KEY_LAST_EVENT_AT, timestamp).apply()
            reportServiceHealth(HealthStatus.ALIVE)
        } catch (e: Exception) {
            Log.e(TAG, "reportEvent failed", e)
        }
    }

    /**
     * Returns recent context for a package ordered oldest-to-newest.
     */
    fun getRecentContext(pkg: String): List<String> {
        return try {
            contextStore[pkg]?.toList().orEmpty().takeLast(5)
        } catch (e: Exception) {
            Log.e(TAG, "getRecentContext failed", e)
            emptyList()
        }
    }

    /**
     * Updates service health and broadcasts changes to local listeners.
     */
    fun reportServiceHealth(status: HealthStatus) {
        if (healthStatus == status) return
        healthStatus = status
        val intent = Intent(ACTION_HEALTH_CHANGED).putExtra(EXTRA_HEALTH, status.name)
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
        Log.i(TAG, "Health changed to ${status.name}")
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            11,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.protection_active_title))
            .setContentText(getString(R.string.protection_active_body))
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.scam_detection_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.scam_detection_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }
}
