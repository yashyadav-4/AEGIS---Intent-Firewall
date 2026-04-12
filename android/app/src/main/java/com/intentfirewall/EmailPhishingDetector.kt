package com.intentfirewall

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.util.Patterns
import java.net.URI
import java.util.regex.Pattern

data class PhishingAnalysisResult(
    val isPhishing: Boolean,
    val score: Float,
    val matchedSignals: List<String>,
    val extractedLinks: List<String>
)

class EmailPhishingDetector(private val context: Context) {

    private val accountManager: AccountManager = AccountManager.get(context)

    // Suspicious keywords indicative of urgency or financial threat (English + Hinglish/Hindi)
    private val urgencyKeywords = listOf(
        "urgent", "immediate", "action required", "suspended", "suspend", "block",
        "verify", "kyc", "update", "verify your account", "deactivated", "locked",
        "jaldi", "turant", "band ho jayega", "pan card link", "aadhar link", "blocked",
        "khatre", "warning", "attention", "alert"
    )

    // Common targeted brands/institutions for typosquatting checks
    private val criticalBrands = listOf(
        "sbi", "hdfc", "icici", "axis", "kotak", "pnb", "paytm", "phonepe", "gpay",
        "paypal", "amazon", "flipkart", "meta", "facebook", "instagram", "google", "apple",
        "netflix", "jio", "airtel", "vi", "bsnl"
    )

    // Simple Leetspeak map for deobfuscation
    private val leetMap = mapOf(
        '0' to 'o',
        '1' to 'i',
        '3' to 'e',
        '4' to 'a',
        '@' to 'a',
        '$' to 's',
        '5' to 's',
        '7' to 't',
        '!' to 'i'
    )

    fun getRegisteredEmailAccounts(): List<String> {
        val emailAccounts = mutableListOf<String>()
        // Fetch standard Google accounts
        val googleAccounts: Array<Account> = accountManager.getAccountsByType("com.google")
        googleAccounts.forEach { emailAccounts.add(it.name) }
        
        // Fetch Exchange/Outlook accounts (common types)
        val exchangeAccounts: Array<Account> = accountManager.getAccountsByType("com.microsoft.exchange.workapp.account")
        exchangeAccounts.forEach { emailAccounts.add(it.name) }

        // Fetch generic IMAP/POP3 accounts if registered with Android AccountManager
        val emailAppAccounts: Array<Account> = accountManager.getAccountsByType("com.android.email")
        emailAppAccounts.forEach { emailAccounts.add(it.name) }

        return emailAccounts.distinct()
    }

    fun analyzeEmailContent(subject: String, body: String, senderAddress: String): PhishingAnalysisResult {
        var score = 0.0f
        val signals = mutableListOf<String>()
        val combinedText = "$subject $body"
        
        // 1. Deobfuscate content (handle Leetspeak and mixed-case spacing)
        val normalizedText = normalizeText(combinedText)

        // 2. Sender Analysis
        if (isSuspiciousSender(senderAddress)) {
            score += 0.4f
            signals.add("Suspicious Sender Domain")
        }

        // 3. Urgency & Threat Keyword Analysis
        var keywordHits = 0
        for (keyword in urgencyKeywords) {
            if (normalizedText.contains(keyword)) {
                keywordHits++
                signals.add("Urgency Keyword: $keyword")
            }
        }
        score += (keywordHits * 0.15f).coerceAtMost(0.45f) // Cap at 45% for keywords

        // 4. Link & Domain Analysis
        val extractedLinks = extractUrls(combinedText)
        var suspiciousLinkCount = 0
        for (link in extractedLinks) {
            val isSuspiciousLink = isSuspiciousDomain(link)
            if (isSuspiciousLink) {
                suspiciousLinkCount++
                signals.add("Suspicious Link: $link")
            }
        }
        
        // If there are suspicious links, it escalates the score significantly
        if (suspiciousLinkCount > 0) {
            score += 0.5f
        }

        // 5. Shortened URL checks
        val shortenedCount = extractedLinks.count { it.contains(Regex("bit\\.ly|tinyurl\\.com|t\\.co|goo\\.gl|is\\.gd")) }
        if (shortenedCount > 0) {
            score += 0.2f
            signals.add("URL Shortener Used")
        }

        val finalScore = score.coerceAtMost(1.0f)
        val isPhishing = finalScore >= 0.65f // Threshold for marking as phishing

        return PhishingAnalysisResult(
            isPhishing = isPhishing,
            score = finalScore,
            matchedSignals = signals,
            extractedLinks = extractedLinks
        )
    }

    private fun normalizeText(text: String): String {
        var normalized = text.lowercase()
        // Translate leetspeak
        val sb = java.lang.StringBuilder()
        for (char in normalized) {
            sb.append(leetMap[char] ?: char)
        }
        normalized = sb.toString()
        // Remove excessive punctuation used to bypass filters (e.g., u.r.g.e.n.t)
        normalized = normalized.replace(Regex("[^a-z0-9\\s]"), "")
        return normalized
    }

    private fun extractUrls(text: String): List<String> {
        val urls = mutableListOf<String>()
        val matcher = Patterns.WEB_URL.matcher(text)
        while (matcher.find()) {
            val url = matcher.group()
            if (url != null) {
                urls.add(url)
            }
        }
        return urls
    }

    private fun isSuspiciousSender(sender: String): Boolean {
        // Simple heuristic: Free mail services masquerading as corporate
        val lowerSender = sender.lowercase()
        if (lowerSender.endsWith("@gmail.com") || lowerSender.endsWith("@yahoo.com") || lowerSender.endsWith("@hotmail.com")) {
            for (brand in criticalBrands) {
                if (lowerSender.substringBefore("@").contains(brand)) {
                    // E.g. hdfc.support.team@gmail.com is highly suspicious
                    return true
                }
            }
        }
        // Suspicious TLDs
        if (lowerSender.endsWith(".xyz") || lowerSender.endsWith(".top") || lowerSender.endsWith(".pw")) {
            return true
        }
        return false
    }

    private fun isSuspiciousDomain(url: String): Boolean {
        try {
            val uri = URI(if (!url.startsWith("http")) "http://$url" else url)
            var host = uri.host?.lowercase() ?: return false
            if (host.startsWith("www.")) {
                host = host.substring(4)
            }

            // Direct IP address used in host
            if (Patterns.IP_ADDRESS.matcher(host).matches()) {
                return true
            }

            // Check for typosquatting / homograph attacks against critical brands
            // e.g., sbi-rewards.com, sb1.in, paytm-kyc.in
            for (brand in criticalBrands) {
                // If it contains the brand name but isn't the exact official domain length-wise (simplified heuristic)
                if (host.contains(brand)) {
                    val parts = host.split(".")
                    if (parts.size >= 2) {
                        val mainDom = parts[parts.size - 2]
                        if (mainDom != brand) {
                            // Subdomain or hyphenated approach: e.g. login-hdfc.xyz or hdfc.secure-login.com
                            return true
                        }
                    }
                }
                
                // Leetspeak in domain (sb1 vs sbi)
                val leetHost = normalizeText(host)
                if (leetHost.contains(brand) && host != leetHost) {
                    return true
                }
            }
            return false
        } catch (e: Exception) {
            // Malformed URL, consider suspicious if it passed Regex but fails URI parse
            return true
        }
    }
}
