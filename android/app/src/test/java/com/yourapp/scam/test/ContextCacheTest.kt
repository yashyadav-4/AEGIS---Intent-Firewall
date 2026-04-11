package com.yourapp.scam.test

import androidx.test.core.app.ApplicationProvider
import com.intentfirewall.ContextCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ContextCacheTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun stores_up_to_five_entries_per_package() {
        val now = System.currentTimeMillis()
        repeat(7) { ContextCache.write(context, "com.whatsapp", "msg$it", now + it) }
        val values = ContextCache.read(context, "com.whatsapp")
        assertEquals(5, values.size)
        assertEquals(listOf("msg2", "msg3", "msg4", "msg5", "msg6"), values)
    }

    @Test
    fun entries_older_than_ten_minutes_filtered_on_read() {
        val now = System.currentTimeMillis()
        ContextCache.write(context, "org.telegram.messenger", "old1", now - 15 * 60 * 1000L)
        ContextCache.write(context, "org.telegram.messenger", "old2", now - 14 * 60 * 1000L)
        ContextCache.write(context, "org.telegram.messenger", "new1", now)
        ContextCache.write(context, "org.telegram.messenger", "new2", now)

        val values = ContextCache.read(context, "org.telegram.messenger")
        assertEquals(listOf("new1", "new2"), values.takeLast(2))
    }

    @Test
    fun different_packages_are_isolated() {
        ContextCache.write(context, "com.whatsapp", "wa1")
        ContextCache.write(context, "org.telegram.messenger", "tg1")

        val wa = ContextCache.read(context, "com.whatsapp")
        val tg = ContextCache.read(context, "org.telegram.messenger")
        assertTrue(wa.contains("wa1"))
        assertTrue(!wa.contains("tg1"))
        assertTrue(tg.contains("tg1"))
    }

    @Test
    fun survives_process_death_via_preferences() {
        ContextCache.write(context, "com.android.mms", "persist1")
        val values = ContextCache.read(context, "com.android.mms")
        assertTrue(values.contains("persist1"))
    }

    @Test
    fun flush_restore_round_trip() {
        val now = System.currentTimeMillis()
        ContextCache.write(context, "com.google.android.apps.messaging", "a", now)
        ContextCache.write(context, "com.google.android.apps.messaging", "b", now + 1)

        ContextCache.flushSnapshot(context)
        val restored = ContextCache.restoreSnapshot(context)
        assertEquals(listOf("a", "b"), restored["com.google.android.apps.messaging"])
    }

    @Test
    fun empty_context_returns_empty_list() {
        val result = ContextCache.read(context, "unknown.package")
        assertNotNull(result)
        assertTrue(result.isEmpty())
    }
}
