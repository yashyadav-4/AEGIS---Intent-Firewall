package com.yourapp.scam.test

import com.intentfirewall.NoiseGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseGateTest {

    @Test
    fun drops_pure_acknowledgements() {
        listOf("ok", "okay", "hlo", "hello", "haan", "noted", "done", "sure", "theek hai", "bilkul", "acha")
            .forEach { assertTrue("Expected drop for $it", NoiseGate.shouldDrop(it)) }
    }

    @Test
    fun drops_call_status_strings() {
        listOf("calling", "on a call", "missed call", "declined", "call ended", "ringing", "typing...")
            .forEach { assertTrue("Expected drop for $it", NoiseGate.shouldDrop(it)) }
    }

    @Test
    fun drops_short_strings_under_four_chars() {
        listOf("hi", "k", "?", "").forEach { assertTrue(NoiseGate.shouldDrop(it)) }
    }

    @Test
    fun drops_emoji_only_strings() {
        listOf("😊", "👍", "😂😂😂").forEach { assertTrue(NoiseGate.shouldDrop(it)) }
    }

    @Test
    fun drops_time_strings() {
        listOf("3:45", "11:30 AM", "9:00 PM").forEach { assertTrue(NoiseGate.shouldDrop(it)) }
    }

    @Test
    fun drops_pure_numeric_strings() {
        listOf("12345", "9999999999").forEach { assertTrue(NoiseGate.shouldDrop(it)) }
    }

    @Test
    fun does_not_drop_messages_with_scam_keywords() {
        assertFalse(NoiseGate.shouldDrop("ok share your otp"))
        assertFalse(NoiseGate.shouldDrop("noted send payment"))
    }

    @Test
    fun does_not_drop_messages_over_eight_words() {
        assertFalse(NoiseGate.shouldDrop("ok noted yes sure hello hi done fine bye thanks"))
    }

    @Test
    fun does_not_drop_hinglish_scam_messages() {
        assertFalse(NoiseGate.shouldDrop("aapka kyc pending hai turant karein"))
        assertFalse(NoiseGate.shouldDrop("otp share karo abhi"))
    }

    @Test
    fun handles_whitespace_and_punctuation_edges() {
        assertTrue(NoiseGate.shouldDrop("   "))
        assertTrue(NoiseGate.shouldDrop("\n\t"))
        assertTrue(NoiseGate.shouldDrop("."))
    }
}
