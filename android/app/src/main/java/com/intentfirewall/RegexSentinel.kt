package com.intentfirewall

// Tier 1 — runs on every message, ~0.1ms, 99% exit here as BENIGN
object RegexSentinel {

    data class SentinelResult(
        val flagged: Boolean,
        val matchedCategory: String?,
        val matchedPattern: String?
    )

    private data class CompiledPattern(
        val category: String,
        val pattern: String,
        val regex: Regex
    )

    // Compiled once when object is loaded to avoid per-call regex compilation overhead.
    private val compiledPatterns: List<CompiledPattern> = listOf(
        // GROUP 1 - OTP_HARVEST
        CompiledPattern("OTP_HARVEST", "\\botp\\b", Regex("\\botp\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("OTP_HARVEST", "\\bone.?time.?pass", Regex("\\bone.?time.?pass", RegexOption.IGNORE_CASE)),
        CompiledPattern("OTP_HARVEST", "\\bverification code\\b", Regex("\\bverification code\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("OTP_HARVEST", "\\benter.*code\\b", Regex("\\benter.*code\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("OTP_HARVEST", "\\bdo not share.*otp\\b", Regex("\\bdo not share.*otp\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("OTP_HARVEST", "\\bshare.*otp\\b", Regex("\\bshare.*otp\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("OTP_HARVEST", "\\bbank.*otp\\b", Regex("\\bbank.*otp\\b", RegexOption.IGNORE_CASE)),

        // GROUP 2 - FINANCIAL_PRESSURE
        CompiledPattern("FINANCIAL_PRESSURE", "\\bsend.*money\\b", Regex("\\bsend.*money\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("FINANCIAL_PRESSURE", "\\btransfer.*amount\\b", Regex("\\btransfer.*amount\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("FINANCIAL_PRESSURE", "\\bpay.*immediately\\b", Regex("\\bpay.*immediately\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("FINANCIAL_PRESSURE", "\\burgent.*payment\\b", Regex("\\burgent.*payment\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("FINANCIAL_PRESSURE", "\\baccount.*blocked\\b", Regex("\\baccount.*blocked\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("FINANCIAL_PRESSURE", "\\bwallet.*blocked\\b", Regex("\\bwallet.*blocked\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("FINANCIAL_PRESSURE", "\\bkyc.*expir\\b", Regex("\\bkyc.*expir\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("FINANCIAL_PRESSURE", "\\bkyc.*update\\b", Regex("\\bkyc.*update\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("FINANCIAL_PRESSURE", "\\bupi.*pin\\b", Regex("\\bupi.*pin\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("FINANCIAL_PRESSURE", "\\bshare.*upi\\b", Regex("\\bshare.*upi\\b", RegexOption.IGNORE_CASE)),

        // GROUP 3 - ACCOUNT_SCARE
        CompiledPattern("ACCOUNT_SCARE", "\\baccount.*suspend\\b", Regex("\\baccount.*suspend\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("ACCOUNT_SCARE", "\\baccount.*block\\b", Regex("\\baccount.*block\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("ACCOUNT_SCARE", "\\bverify.*account\\b", Regex("\\bverify.*account\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("ACCOUNT_SCARE", "\\bconfirm.*identity\\b", Regex("\\bconfirm.*identity\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("ACCOUNT_SCARE", "\\bunusual.*activit\\b", Regex("\\bunusual.*activit\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("ACCOUNT_SCARE", "\\bsuspicious.*login\\b", Regex("\\bsuspicious.*login\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("ACCOUNT_SCARE", "\\bsecurit.*alert\\b", Regex("\\bsecurit.*alert\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("ACCOUNT_SCARE", "\\bfraud.*alert\\b", Regex("\\bfraud.*alert\\b", RegexOption.IGNORE_CASE)),

        // GROUP 4 - PHISHING_LINK
        CompiledPattern("PHISHING_LINK", "\\bclick.*link\\b", Regex("\\bclick.*link\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("PHISHING_LINK", "\\bbit\\.ly\\b", Regex("\\bbit\\.ly\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("PHISHING_LINK", "\\btinyurl\\b", Regex("\\btinyurl\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("PHISHING_LINK", "\\bfree.*reward\\b", Regex("\\bfree.*reward\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("PHISHING_LINK", "\\bclaim.*prize\\b", Regex("\\bclaim.*prize\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("PHISHING_LINK", "\\bwon.*lottery\\b", Regex("\\bwon.*lottery\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("PHISHING_LINK", "\\bcongratulations.*won\\b", Regex("\\bcongratulations.*won\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern(
            "PHISHING_LINK",
            "\\bhttp[s]?://(?!(?:www\\.)?(?:google|youtube|facebook|instagram)\\.)[^\\s]{20,}\\b",
            Regex("\\bhttp[s]?://(?!(?:www\\.)?(?:google|youtube|facebook|instagram)\\.)[^\\s]{20,}\\b", RegexOption.IGNORE_CASE)
        ),

        // GROUP 5 - GIFT_CARD_SCAM
        CompiledPattern("GIFT_CARD_SCAM", "\\bgift card\\b", Regex("\\bgift card\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("GIFT_CARD_SCAM", "\\bamazon.*card\\b", Regex("\\bamazon.*card\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("GIFT_CARD_SCAM", "\\bitunes.*card\\b", Regex("\\bitunes.*card\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("GIFT_CARD_SCAM", "\\bsteam.*card\\b", Regex("\\bsteam.*card\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("GIFT_CARD_SCAM", "\\bbuy.*voucher\\b", Regex("\\bbuy.*voucher\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("GIFT_CARD_SCAM", "\\bscratch.*card\\b", Regex("\\bscratch.*card\\b", RegexOption.IGNORE_CASE)),

        // GROUP 6 - IMPERSONATION
        CompiledPattern(
            "IMPERSONATION",
            "\\bthis is.*(?:rbi|sbi|hdfc|icici|paytm|npci)\\b",
            Regex("\\bthis is.*(?:rbi|sbi|hdfc|icici|paytm|npci)\\b", RegexOption.IGNORE_CASE)
        ),
        CompiledPattern(
            "IMPERSONATION",
            "\\b(?:rbi|sbi|hdfc|icici|paytm|npci).*(?:official|helpline|support)\\b",
            Regex("\\b(?:rbi|sbi|hdfc|icici|paytm|npci).*(?:official|helpline|support)\\b", RegexOption.IGNORE_CASE)
        ),
        CompiledPattern("IMPERSONATION", "\\bcbi.*officer\\b", Regex("\\bcbi.*officer\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("IMPERSONATION", "\\bincome.?tax.*notice\\b", Regex("\\bincome.?tax.*notice\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("IMPERSONATION", "\\barrest.*warrant\\b", Regex("\\barrest.*warrant\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("IMPERSONATION", "\\bcustoms.*package\\b", Regex("\\bcustoms.*package\\b", RegexOption.IGNORE_CASE)),
    )

    fun analyze(text: String): SentinelResult {
        val normalized = text.lowercase()

        for (entry in compiledPatterns) {
            if (entry.regex.containsMatchIn(normalized)) {
                return SentinelResult(
                    flagged = true,
                    matchedCategory = entry.category,
                    matchedPattern = entry.pattern
                )
            }
        }

        return SentinelResult(false, null, null)
    }
}
