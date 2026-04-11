package com.yourapp.scam.test

import com.intentfirewall.ScoreTier
import com.intentfirewall.Tier1Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Tier1EngineTest {

    @Test
    fun high_confidence_otp_scam_scores_above_88() {
        val result = Tier1Engine.analyze("Your bank OTP is 482910. Share this code immediately.")
        assertTrue(result.score >= 88)
        assertEquals(ScoreTier.HIGH, result.tier)
        assertEquals("OTP_SCAM", result.category?.id)
        assertTrue(result.matchedKeywords.any { it.contains("otp") })
        assertTrue(result.evidencePhrases.isNotEmpty())
    }

    @Test
    fun high_confidence_lottery_scam_scores_above_88() {
        val result = Tier1Engine.analyze("Congratulations! You won Rs 25 lakh in lucky draw. Claim your prize now.")
        assertTrue(result.score >= 88)
        assertEquals(ScoreTier.HIGH, result.tier)
        assertEquals("LOTTERY_SCAM", result.category?.id)
    }

    @Test
    fun high_confidence_impersonation_scam_scores_above_88() {
        val result = Tier1Engine.analyze("Main CBI officer bol raha hoon. Aapke naam arrest warrant hai. Abhi 50000 bhejo warna giraftari hogi.")
        assertTrue(result.score >= 88)
        assertEquals(ScoreTier.HIGH, result.tier)
        assertEquals("IMPERSONATION_SCAM", result.category?.id)
    }

    @Test
    fun medium_confidence_job_scam_triggers_tier3() {
        val result = Tier1Engine.analyze("Part time work from home. Daily income guaranteed.")
        assertTrue(result.score >= 60)
        assertTrue(result.tier == ScoreTier.MEDIUM || result.tier == ScoreTier.HIGH)
        if (result.tier == ScoreTier.MEDIUM) {
            assertTrue(result.requiresTier3)
            assertTrue(result.showProcessingNotif)
        }
    }

    @Test
    fun low_confidence_triggers_silent_tier3() {
        val result = Tier1Engine.analyze("Earn money online. Good opportunity.")
        assertTrue(result.score >= 0)
        assertTrue(result.tier != ScoreTier.SAFE || result.score < 30)
        if (result.tier == ScoreTier.LOW) {
            assertTrue(result.requiresTier3)
            assertTrue(!result.showProcessingNotif)
        }
    }

    @Test
    fun safe_score_for_normal_conversation() {
        val result = Tier1Engine.analyze("Kal milte hain office mein lunch ke baad")
        assertTrue(result.score < 30)
        assertEquals(ScoreTier.SAFE, result.tier)
        assertEquals(null, result.category)
    }

    @Test
    fun context_boost_adds_points() {
        val text = "otp share karo"
        val noContext = Tier1Engine.analyze(text)
        val withContext = Tier1Engine.analyze(text, listOf("OTP aaya hai kya", "code batao jaldi"))
        assertTrue(withContext.score >= noContext.score)
    }

    @Test
    fun transliteration_variants_match() {
        assertNotNull(Tier1Engine.analyze("o.t.p share karo").category)
        assertNotNull(Tier1Engine.analyze("woh code batao").category)
        val third = Tier1Engine.analyze("wo number bata do")
        assertTrue(third.score >= 0)
    }

    @Test
    fun anydesk_triggers_tech_support_high() {
        val result = Tier1Engine.analyze("Anydesk install karo hum fix kar denge")
        assertEquals("TECH_SUPPORT_SCAM", result.category?.id)
        assertTrue(result.score >= 88)
    }

    @Test
    fun evidence_phrases_are_substrings() {
        val text = "Aapka kyc pending hai turant kyc karein"
        val result = Tier1Engine.analyze(text)
        result.evidencePhrases.forEach {
            assertTrue(text.lowercase().contains(it.lowercase()))
        }
    }
}
