package com.intentfirewall

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.util.Patterns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.regex.Matcher

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32]) // Define SDK level that Robolectric supports
class E2EScamDetectionTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockAccountManager: AccountManager
    
    @Mock
    private lateinit var mockWebUrlMatcher: Matcher

    private lateinit var phishingDetector: EmailPhishingDetector

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        
        // Mocking AccountManager logic
        `when`(mockContext.getSystemService(Context.ACCOUNT_SERVICE)).thenReturn(mockAccountManager)
        
        // Setting up standard dummy accounts
        val googleAccounts = arrayOf(Account("user@gmail.com", "com.google"))
        `when`(mockAccountManager.getAccountsByType("com.google")).thenReturn(googleAccounts)
        
        phishingDetector = EmailPhishingDetector(mockContext)
    }

    @Test
    fun testValidEmailAccountFetching() {
        // Just verify basic fetching logic is wired securely
        val accounts = phishingDetector.getRegisteredEmailAccounts()
        assertEquals(1, accounts.size)
        assertEquals("user@gmail.com", accounts[0])
    }

    @Test
    fun testHinglishUrgencyDetection() {
        val subject = "Action Required: Account Suspend"
        val body = "Bhai jaldi se pan card link karwao warna account band ho jayega."
        val sender = "no-reply.support@sbi-online.com"
        
        val result = phishingDetector.analyzeEmailContent(subject, body, sender)
        
        assertTrue("Expected highly suspicious Hinglish keywords to flag as phishing", result.isPhishing)
        assertTrue(result.matchedSignals.contains("Urgency Keyword: suspend"))
        assertTrue(result.matchedSignals.contains("Urgency Keyword: jaldi"))
        assertTrue(result.matchedSignals.contains("Urgency Keyword: pan card link"))
    }

    @Test
    fun testLeetspeakAndAdversarialObfuscation() {
        // Leetspeak mixed with punctuations
        val subject = "U.R.G.E.N.T: y0ur acc0unt wll b3 $uspended"
        val body = "P1ease cl!ck h3re to r3activate your HDFC bank acc."
        val sender = "hdfc.support@gmail.com"
        
        val result = phishingDetector.analyzeEmailContent(subject, body, sender)
        
        assertTrue("Leetspeak normalization failed to trigger urgent keywords", result.isPhishing)
        assertTrue(result.matchedSignals.contains("Suspicious Sender Domain"))
        // Normalized "y0ur acc0unt wll b3 $uspended" -> "your account wll be suspended"
        // Wait, normalizeText replaces all punctuations. So "U.R.G.E.N.T" -> "urgent".
        assertTrue("Should detect 'urgent' after stripping dots", result.matchedSignals.any { it.contains("urgent") })
        assertTrue("Should detect 'suspend' after translating from Leetspeak", result.matchedSignals.any { it.contains("suspend") })
    }

    @Test
    fun testTyposquattingURLDetection() {
        val subject = "SBI Rewards Alert"
        // In Robolectric, Patterns.WEB_URL works, but we can't extract without real framework parsing sometimes. We'll simulate HTTP content.
        val body = "Claim your 10,000 SBI Reward points here: http://sbi-rewards.com/login"
        val sender = "rewards@sbi-online.xyz"
        
        val result = phishingDetector.analyzeEmailContent(subject, body, sender)
        
        // Should catch the malicious TLD as well as typo in domain
        assertTrue("Should flag .xyz TLDs or Typosquatted links", result.isPhishing)
        assertTrue("Should identify the suspicious sender", result.matchedSignals.contains("Suspicious Sender Domain"))
    }

    @Test
    fun testLegitimateEmailPasses() {
        val subject = "Weekly Newsletter"
        val body = "Here is your weekly recap of Android development."
        val sender = "newsletter@android.com"
        
        val result = phishingDetector.analyzeEmailContent(subject, body, sender)
        
        assertTrue("Legitimate emails without urgency or bad links should not be flagged", !result.isPhishing)
        assertEquals(0.0f, result.score, 0.05f)
    }

    @Test
    fun testLeetspeakDomainDetection() {
        val subject = "Update KYC"
        val body = "Please visit http://sb1.in to update your KYC details immediately."
        val sender = "kyc@sbi.co.in" // Legitimate-looking sender
        val result = phishingDetector.analyzeEmailContent(subject, body, sender)

        assertTrue("Should flag .in domain typo (sb1 instead of sbi)", result.isPhishing)
        assertTrue("Should identify homograph attack in link", result.matchedSignals.any { it.startsWith("Suspicious Link") })
    }
}
