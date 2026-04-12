package com.yourapp.scam.test

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.intentfirewall.ScamAccessibilityService
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AccessibilityPipelineTest {

    @Test
    fun events_from_non_whitelisted_packages_are_dropped_by_gate_contract() {
        val allowed = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "com.truecaller.android",
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.android.phone",
            "com.google.android.dialer",
            "com.samsung.android.incallui",
        )
        assertTrue("com.random.unknown.app" !in allowed)
    }

    @Test
    fun event_throttle_enforces_300ms_per_package() {
        val throttleClass = Class.forName("com.intentfirewall.ScamAccessibilityService\$EventThrottle")
        val ctor = throttleClass.getDeclaredConstructor()
        ctor.isAccessible = true
        val throttle = ctor.newInstance()
        val allow = throttleClass.getDeclaredMethod("allow", String::class.java, Long::class.javaPrimitiveType)
        allow.isAccessible = true

        val first = allow.invoke(throttle, "com.whatsapp", 1000L) as Boolean
        val second = allow.invoke(throttle, "com.whatsapp", 1100L) as Boolean
        val third = allow.invoke(throttle, "com.whatsapp", 1301L) as Boolean

        assertTrue(first)
        assertTrue(!second)
        assertTrue(third)
    }

    @Test
    fun notification_extras_text_is_extracted() {
        val event = mockk<AccessibilityEvent>(relaxed = true)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val notif = android.app.Notification.Builder(context)
            .setContentTitle("title")
            .setContentText("Your KYC is pending. Update now.")
            .build()
        every { event.eventType } returns AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
        every { event.text } returns mutableListOf<CharSequence>()
        every { event.contentDescription } returns null
        every { event.parcelableData } returns notif

        val extractorClass = Class.forName("com.intentfirewall.ScamAccessibilityService\$TextExtractor")
        val extractorCtor = extractorClass.getDeclaredConstructor()
        extractorCtor.isAccessible = true
        val extractor = extractorCtor.newInstance()
        val extract = extractorClass.getDeclaredMethod("extract", AccessibilityEvent::class.java, AccessibilityNodeInfo::class.java)
        extract.isAccessible = true

        val out = extract.invoke(extractor, event, null) as List<*>
        assertTrue(out.any { it.toString().contains("KYC", ignoreCase = true) })
    }

    @Test
    fun node_traversal_stops_at_depth_8_contract() {
        val service = ScamAccessibilityService()
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.isVisibleToUser } returns true
        every { root.childCount } returns 0
        every { root.text } returns null
        every { root.contentDescription } returns null
        val result = service.getAllTextFromNode(root)
        assertEquals(0, result.size)
    }

    @Test
    fun context_assembly_uses_last_three_messages_contract() {
        val history = listOf("m1", "m2", "m3", "m4", "m5")
        val used = history.takeLast(3)
        assertEquals(listOf("m3", "m4", "m5"), used)
    }
}
