package com.yourapp.scam.test

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.intentfirewall.ProcessingNotifManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class NotificationTest {

    @Test
    fun show_processing_posts_notification_immediately() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ProcessingNotifManager.initialize(context)
        ProcessingNotifManager.showProcessing("com.whatsapp", "preview")

        val nm = context.getSystemService(NotificationManager::class.java)
        assertTrue(Shadows.shadowOf(nm).getNotification(2001) != null)
    }

    @Test
    fun dismiss_processing_cancels_notification() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ProcessingNotifManager.initialize(context)
        ProcessingNotifManager.showProcessing("com.whatsapp", "preview")
        ProcessingNotifManager.dismissProcessing()

        val nm = context.getSystemService(NotificationManager::class.java)
        assertTrue(Shadows.shadowOf(nm).getNotification(2001) == null)
    }

    @Test
    fun dismiss_processing_is_idempotent() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ProcessingNotifManager.initialize(context)
        ProcessingNotifManager.showProcessing("com.whatsapp", "preview")
        ProcessingNotifManager.dismissProcessing()
        ProcessingNotifManager.dismissProcessing()

        val nm = context.getSystemService(NotificationManager::class.java)
        assertTrue(Shadows.shadowOf(nm).getNotification(2001) == null)
    }

    @Test
    fun auto_dismiss_after_12_seconds() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ProcessingNotifManager.initialize(context)
        ProcessingNotifManager.showProcessing("com.whatsapp", "preview")
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(12))

        val nm = context.getSystemService(NotificationManager::class.java)
        assertTrue(Shadows.shadowOf(nm).getNotification(2001) == null)
    }

    @Test
    fun manual_dismiss_cancels_future_auto_dismiss() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ProcessingNotifManager.initialize(context)
        ProcessingNotifManager.showProcessing("com.whatsapp", "preview")
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        ProcessingNotifManager.dismissProcessing()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(12))

        val nm = context.getSystemService(NotificationManager::class.java)
        assertTrue(Shadows.shadowOf(nm).getNotification(2001) == null)
    }

    @Test
    fun channel_importance_low() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ProcessingNotifManager.initialize(context)
        ProcessingNotifManager.showProcessing("com.whatsapp", "preview")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val channel = nm.getNotificationChannel("scam_processing_channel")
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        }
    }

    @Test
    fun notification_is_ongoing_non_dismissable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ProcessingNotifManager.initialize(context)
        ProcessingNotifManager.showProcessing("com.whatsapp", "preview")

        val nm = context.getSystemService(NotificationManager::class.java)
        val notif = Shadows.shadowOf(nm).getNotification(2001)
        assertTrue(notif != null)
        notif as Notification
        assertTrue((notif.flags and Notification.FLAG_ONGOING_EVENT) > 0)
    }
}
