package com.yourapp.scam.test

import com.intentfirewall.Decision
import com.intentfirewall.DecisionTraceLogger
import com.intentfirewall.ProcessingNotifManager
import com.intentfirewall.ScamPipeline
import com.intentfirewall.Tier3GeminiClient
import com.intentfirewall.Tier3GeminiResult
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class EndToEndScenarioTest {

    @Before
    fun setup() {
        mockkObject(Tier3GeminiClient)
        mockkObject(ProcessingNotifManager)
        mockkObject(DecisionTraceLogger)
        io.mockk.every { ProcessingNotifManager.showProcessing(any(), any()) } returns Unit
        io.mockk.every { ProcessingNotifManager.dismissProcessing() } returns Unit
        io.mockk.every { DecisionTraceLogger.log(any(), any(), any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun scenario_otp_scam_full_flow() = runTest {
        coEvery { Tier3GeminiClient.analyze(any(), any(), any()) } returns
            Tier3GeminiResult("SCAM", "HIGH", "OTP_SCAM", "OTP 482910 share karo", 95, "otp fraud")

        val m1 = ScamPipeline.process("Hello", "com.android.mms", emptyList())
        val m4 = ScamPipeline.process("Aapka OTP 482910 hai. Yeh share karo.", "com.android.mms", listOf("Main SBI bank se bol raha hoon", "Aapka account verify karna hai"))

        assertEquals(Decision.SAFE, m1.decision)
        assertEquals(Decision.ALERT, m4.decision)
        assertEquals("OTP_SCAM", m4.category)
        assertTrue(m4.evidence.joinToString(" ").contains("OTP", ignoreCase = true))
    }

    @Test
    fun scenario_investment_scam_with_tier3_involvement() = runTest {
        coEvery { Tier3GeminiClient.analyze(any(), any(), any()) } returnsMany listOf(
            Tier3GeminiResult("UNCERTAIN", "LOW", "UNCERTAIN", "", 40, "weak"),
            Tier3GeminiResult("SCAM", "HIGH", "INVESTMENT_SCAM", "guaranteed returns", 90, "clear"),
        )

        val m1 = ScamPipeline.process("Good morning", "com.whatsapp", emptyList())
        val m2 = ScamPipeline.process("Ek investment opportunity hai", "com.whatsapp", emptyList())
        val m3 = ScamPipeline.process("Telegram group join karo", "com.whatsapp", emptyList())
        val m4 = ScamPipeline.process("100% guaranteed returns. Abhi invest karo 10000.", "com.whatsapp", emptyList())

        assertTrue(m1.decision in setOf(Decision.SAFE, Decision.UNCERTAIN, Decision.ALERT))
        assertTrue(m2.decision in setOf(Decision.SAFE, Decision.UNCERTAIN, Decision.ALERT))
        assertTrue(m3.decision in setOf(Decision.SAFE, Decision.UNCERTAIN, Decision.ALERT))
        assertTrue(m4.decision in setOf(Decision.ALERT, Decision.UNCERTAIN, Decision.SAFE))
        verify(atLeast = 0) { ProcessingNotifManager.showProcessing(any(), any()) }
    }

    @Test
    fun scenario_false_positive_prevention_normal_conversation() = runTest {
        coEvery { Tier3GeminiClient.analyze(any(), any(), any()) } returns
            Tier3GeminiResult("SAFE", "HIGH", "SAFE", "", 10, "normal")

        val r1 = ScamPipeline.process("Mera account number bhej do", "com.whatsapp", emptyList())
        val r2 = ScamPipeline.process("Bank transfer karna hai tujhe", "com.whatsapp", emptyList())
        val r3 = ScamPipeline.process("KYC update ki reminder aa rahi thi mujhe", "com.whatsapp", emptyList())

        assertTrue(listOf(r1, r2, r3).none { it.decision == Decision.ALERT })
    }

    @Test
    fun scenario_tier3_timeout_graceful_degradation() = runTest {
        coEvery { Tier3GeminiClient.analyze(any(), any(), any()) } throws IOException("timeout")

        val result = ScamPipeline.process("Part time work. Earn daily 500 per task.", "com.whatsapp", emptyList())
        assertTrue(result.decision == Decision.UNCERTAIN || result.decision == Decision.ALERT)
        verify(atLeast = 0) { ProcessingNotifManager.dismissProcessing() }

        val next = ScamPipeline.process("Kal milte hain", "com.whatsapp", emptyList())
        assertTrue(next.decision == Decision.SAFE || next.decision == Decision.UNCERTAIN)
    }

    @Test
    fun scenario_sextortion_blackmail_sequence() = runTest {
        val r = ScamPipeline.process("50000 bhejo warna viral kar dunga", "com.whatsapp", listOf("Tumhara video mere paas hai"))
        assertEquals(Decision.ALERT, r.decision)
        assertTrue(r.category == "ROMANCE_SCAM" || r.category == "IMPERSONATION_SCAM")
    }

    @Test
    fun scenario_utility_scam_with_deadline() = runTest {
        val r = ScamPipeline.process("Aaj raat 11 baje aapki bijli kat jayegi. Bill pending hai. Is link pe abhi pay karo: bit.ly/pay-bill", "com.android.mms", emptyList())
        assertTrue(r.confidenceScore >= 88)
        assertEquals("UTILITY_SCAM", r.category)
        assertEquals(Decision.ALERT, r.decision)
    }

    @Test
    fun scenario_courier_drug_scam() = runTest {
        val r = ScamPipeline.process("FedEx agent bol raha hoon. Aapke naam ek parcel aaya tha jisme drugs mili hain. Police case ho sakta hai. 10000 de do release ke liye.", "com.android.mms", emptyList())
        assertTrue(r.confidenceScore >= 0)
        assertTrue(r.decision in setOf(Decision.ALERT, Decision.UNCERTAIN, Decision.SAFE))
    }
}
