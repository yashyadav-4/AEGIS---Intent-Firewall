package com.yourapp.scam.test

import com.intentfirewall.Decision
import com.intentfirewall.DecisionTraceLogger
import com.intentfirewall.ProcessingNotifManager
import com.intentfirewall.ScamPipeline
import com.intentfirewall.ScoreTier
import com.intentfirewall.ScamCategory
import com.intentfirewall.Tier1EnginePort
import com.intentfirewall.Tier1Result
import com.intentfirewall.Tier3GeminiClientPort
import com.intentfirewall.Tier3GeminiClient
import com.intentfirewall.Tier3GeminiResult
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PipelineIntegrationTest {
    private lateinit var tier1Engine: Tier1EnginePort
    private lateinit var tier3Client: Tier3GeminiClientPort
    private lateinit var pipeline: ScamPipeline

    @Before
    fun setup() {
        mockkObject(ProcessingNotifManager)
        mockkObject(DecisionTraceLogger)
        every { ProcessingNotifManager.showProcessing(any(), any()) } returns Unit
        every { ProcessingNotifManager.dismissProcessing() } returns Unit
        every { DecisionTraceLogger.log(any(), any(), any()) } returns Unit

        tier1Engine = mockk()
        tier3Client = mockk()
        pipeline = ScamPipeline(tier1Engine = tier1Engine, tier3GeminiClient = tier3Client)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun noise_gate_prevents_downstream_processing() = runTest {
        val result = pipeline.process("ok", "com.whatsapp", emptyList())
        coVerify(exactly = 0) { tier3Client.analyze(any(), any(), any()) }
        verify(exactly = 0) { ProcessingNotifManager.showProcessing(any(), any()) }
        assertEquals(Decision.SAFE, result.decision)
        assertEquals(1, result.tier)
    }

    @Test
    fun high_tier1_never_calls_tier3() = runTest {
        every { tier1Engine.analyze(any(), any()) } returns Tier1Result(
            score = 91,
            tier = ScoreTier.HIGH,
            category = ScamCategory("IMPERSONATION_SCAM", "Impersonation Scam"),
            matchedKeywords = listOf("arrest warrant"),
            evidencePhrases = listOf("arrest warrant"),
            requiresTier3 = false,
            showProcessingNotif = false,
        )

        val result = pipeline.process("Aapke naam arrest warrant hai CBI se. Abhi 50000 bhejo.", "com.android.mms", emptyList())
        coVerify(exactly = 0) { tier3Client.analyze(any(), any(), any()) }
        assertEquals(Decision.ALERT, result.decision)
        assertEquals(1, result.tier)
        assertEquals(false, result.usedTier3)
    }

    @Test
    fun medium_tier1_shows_notification_and_dismisses_after_tier3() = runTest {
        every { tier1Engine.analyze(any(), any()) } returns Tier1Result(
            score = 72,
            tier = ScoreTier.MEDIUM,
            category = ScamCategory("JOB_SCAM", "Job Scam"),
            matchedKeywords = listOf("part time"),
            evidencePhrases = listOf("Earn daily 500"),
            requiresTier3 = true,
            showProcessingNotif = true,
        )
        coEvery { tier3Client.analyze(any(), any(), any()) } returns
            Tier3GeminiResult("SCAM", "HIGH", "JOB_SCAM", "earn daily", 85, "job scam pattern")

        pipeline.process("Part time work. Earn daily 500.", "com.whatsapp", emptyList())

        verify(exactly = 1) { ProcessingNotifManager.showProcessing(any(), any()) }
        verify(exactly = 1) { ProcessingNotifManager.dismissProcessing() }
    }

    @Test
    fun medium_tier3_failure_returns_uncertain_and_dismisses() = runTest {
        every { tier1Engine.analyze(any(), any()) } returns Tier1Result(
            score = 68,
            tier = ScoreTier.MEDIUM,
            category = ScamCategory("JOB_SCAM", "Job Scam"),
            matchedKeywords = listOf("part time"),
            evidencePhrases = listOf("Earn daily"),
            requiresTier3 = true,
            showProcessingNotif = true,
        )
        coEvery { tier3Client.analyze(any(), any(), any()) } throws IOException("network timeout")

        val result = pipeline.process("Part time work. Earn daily.", "com.whatsapp", emptyList())

        verify(exactly = 1) { ProcessingNotifManager.dismissProcessing() }
        assertEquals(Decision.UNCERTAIN, result.decision)
    }

    @Test
    fun low_score_calls_tier3_without_notification() = runTest {
        every { tier1Engine.analyze(any(), any()) } returns Tier1Result(
            score = 45,
            tier = ScoreTier.LOW,
            category = ScamCategory("INVESTMENT_SCAM", "Investment Scam"),
            matchedKeywords = listOf("earn"),
            evidencePhrases = listOf("Earn money online"),
            requiresTier3 = true,
            showProcessingNotif = false,
        )
        coEvery { tier3Client.analyze(any(), any(), any()) } returns
            Tier3GeminiResult("SAFE", "HIGH", "SAFE", "", 10, "normal")

        pipeline.process("Earn money online.", "com.whatsapp", emptyList())

        verify(exactly = 0) { ProcessingNotifManager.showProcessing(any(), any()) }
        coVerify(exactly = 1) { tier3Client.analyze(any(), any(), any()) }
    }

    @Test
    fun safe_tier1_always_calls_tier3_without_processing_notification() = runTest {
        every { tier1Engine.analyze(any(), any()) } returns Tier1Result(
            score = 10,
            tier = ScoreTier.SAFE,
            category = null,
            matchedKeywords = emptyList(),
            evidencePhrases = emptyList(),
            requiresTier3 = false,
            showProcessingNotif = false,
        )
        coEvery { tier3Client.analyze(any(), any(), any()) } returns
            Tier3GeminiResult("SAFE", "HIGH", "SAFE", "", 10, "normal")

        pipeline.process("Kal milte hain.", "com.whatsapp", emptyList())
        coVerify(exactly = 1) { tier3Client.analyze(any(), any(), any()) }
        verify(exactly = 0) { ProcessingNotifManager.showProcessing(any(), any()) }
    }

    @Test
    fun tier3_mapping_scam_high_to_alert() = runTest {
        every { tier1Engine.analyze(any(), any()) } returns Tier1Result(
            score = 63,
            tier = ScoreTier.MEDIUM,
            category = ScamCategory("OTP_SCAM", "OTP Scam"),
            matchedKeywords = listOf("otp"),
            evidencePhrases = listOf("share"),
            requiresTier3 = true,
            showProcessingNotif = true,
        )
        coEvery { tier3Client.analyze(any(), any(), any()) } returns
            Tier3GeminiResult("SCAM", "HIGH", "OTP_SCAM", "share otp", 92, "otp")

        val result = pipeline.process("share kar do na", "com.whatsapp", listOf("otp aaya kya", "haan aaya"))
        assertEquals(Decision.ALERT, result.decision)
        assertEquals(3, result.tier)
        assertEquals(true, result.usedTier3)
    }

    @Test
    fun tier3_mapping_scam_low_to_uncertain() = runTest {
        every { tier1Engine.analyze(any(), any()) } returns Tier1Result(
            score = 38,
            tier = ScoreTier.LOW,
            category = ScamCategory("INVESTMENT_SCAM", "Investment Scam"),
            matchedKeywords = listOf("earn"),
            evidencePhrases = listOf("Earn online"),
            requiresTier3 = true,
            showProcessingNotif = false,
        )
        coEvery { tier3Client.analyze(any(), any(), any()) } returns
            Tier3GeminiResult("SCAM", "LOW", "GENERIC_SCAM", "", 45, "weak")

        val result = pipeline.process("Earn online.", "com.whatsapp", emptyList())
        assertEquals(Decision.UNCERTAIN, result.decision)
    }

    @Test
    fun logger_called_for_every_pipeline_run() = runTest {
        every { tier1Engine.analyze(any(), any()) } returns Tier1Result(
            score = 10,
            tier = ScoreTier.SAFE,
            category = null,
            matchedKeywords = emptyList(),
            evidencePhrases = emptyList(),
            requiresTier3 = false,
            showProcessingNotif = false,
        )

        pipeline.process("test message", "com.whatsapp", emptyList())
        verify(exactly = 1) { DecisionTraceLogger.log(any(), any(), any()) }
    }

    @Test
    fun pipeline_thread_safety_under_concurrency() = runTest {
        every { tier1Engine.analyze(any(), any()) } returns Tier1Result(
            score = 12,
            tier = ScoreTier.SAFE,
            category = null,
            matchedKeywords = emptyList(),
            evidencePhrases = emptyList(),
            requiresTier3 = false,
            showProcessingNotif = false,
        )

        val jobs = (1..20).map {
            async { pipeline.process("test $it", "com.whatsapp", emptyList()) }
        }
        val results = jobs.map { it.await() }
        assertEquals(20, results.size)
        results.forEach { assertNotNull(it.decision) }
    }
}
