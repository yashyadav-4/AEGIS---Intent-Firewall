package com.intentfirewall

// Tier 1 — runs on every message, ~0.1ms, 99% exit here as BENIGN
object RegexSentinel {

    data class SentinelResult(
        val flagged: Boolean,
        val matchedCategory: String?,
        val matchedPattern: String?
    )

    data class RegexMatch(
        val pattern: String,
        val value: String,
        val index: Int
    )

    data class HinglishScamResult(
        val detected: Boolean,
        val categories: List<String>,
        val confidence: Float,
        val matches: Map<String, List<RegexMatch>>
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

    private val HINGLISH_SCAM_PATTERNS = mapOf(
        // === CREDENTIAL HARVEST ===
        "OTP_HARVEST_HINGLISH" to listOf(
            Regex("""otp\s*(batao|share karo|bolo|dedo|bhejo)""", RegexOption.IGNORE_CASE),
            Regex("""(apna|aapka)\s*(pin|password|otp)\s*(batao|dedo|share)""", RegexOption.IGNORE_CASE),
            Regex("""one\s*time\s*password\s*(share|send|give)""", RegexOption.IGNORE_CASE),
        ),

        // === AUTHORITY IMPERSONATION ===
        "AUTHORITY_HINDI" to listOf(
            Regex("""(mai|main|hum)\s*(cbi|ed|rbi|trai|income tax|cyber crime)\s*(se|officer|department)""", RegexOption.IGNORE_CASE),
            Regex("""(aapke|aapka)\s*(upar|against)\s*(case|fir|complaint)\s*(darj|file|register)""", RegexOption.IGNORE_CASE),
            Regex("""(arrest|giraftaar)\s*(ho|kar|hoge|honge)""", RegexOption.IGNORE_CASE),
            Regex("""digital\s*(arrest|giraftaari)""", RegexOption.IGNORE_CASE),
        ),

        // === URGENCY TRIGGERS ===
        "URGENCY_HINGLISH" to listOf(
            Regex("""(abhi|turant|foran|jaldi)\s*(transfer|payment|pay|bhejo|karo)""", RegexOption.IGNORE_CASE),
            Regex("""(2|do|ek|1)\s*(ghante|hour|minute)\s*(mein|me|andar)\s*(band|block|suspend)""", RegexOption.IGNORE_CASE),
            Regex("""(account|sim|number)\s*(band|block|suspend)\s*(ho\s*jayega|kar\s*denge)""", RegexOption.IGNORE_CASE),
        ),

        // === FINANCIAL PRESSURE ===
        "FINANCIAL_HINGLISH" to listOf(
            Regex("""(upi|phonepay|gpay|paytm)\s*(pin|id|number)\s*(batao|share|dedo)""", RegexOption.IGNORE_CASE),
            Regex("""(apne|aapka)\s*(account|khata)\s*(se|mein)\s*(transfer|bhejo|nikalo)""", RegexOption.IGNORE_CASE),
            Regex("""(processing|registration|customs|duty)\s*(fee|charge|amount)\s*(pay|jama|bhejo)""", RegexOption.IGNORE_CASE),
        ),

        // === KYC / VERIFICATION SCAM ===
        "KYC_SCAM_HINGLISH" to listOf(
            Regex("""(aapka|apna)\s*kyc\s*(expired|expire|update|verify|karna\s*hai)""", RegexOption.IGNORE_CASE),
            Regex("""kyc\s*(nahi|na)\s*(kiya|karaya)\s*(toh|to)\s*(band|block)""", RegexOption.IGNORE_CASE),
        ),

        // === PARCEL / CUSTOMS SCAM ===
        "CUSTOMS_SCAM_HINGLISH" to listOf(
            Regex("""(aapka|apna)\s*(parcel|package|courier)\s*(customs|airport)\s*(mein|par)\s*(ruka|held|seized)""", RegexOption.IGNORE_CASE),
        ),

        // === REMOTE ACCESS TRAP ===
        "REMOTE_ACCESS_HINGLISH" to listOf(
            Regex("""(anydesk|teamviewer|quicksupport)\s*(app|application)\s*(install|download|karo)""", RegexOption.IGNORE_CASE),
            Regex("""(screen|mobile)\s*(share karo|dikhaao|on karo)""", RegexOption.IGNORE_CASE),
        ),
    )

    private val CATEGORY_WEIGHTS = mapOf(
        "OTP_HARVEST_HINGLISH" to 0.9f,      // Highest risk — credentials
        "AUTHORITY_HINDI" to 0.8f,           // Fake authority
        "REMOTE_ACCESS_HINGLISH" to 0.85f,   // Screen sharing = theft
        "KYC_SCAM_HINGLISH" to 0.75f,
        "CUSTOMS_SCAM_HINGLISH" to 0.7f,
        "URGENCY_HINGLISH" to 0.6f,
        "FINANCIAL_HINGLISH" to 0.65f,
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

    fun detectHinglishScam(inputText: String): HinglishScamResult {
        android.util.Log.d("IntentFirewall|HINGLISH", "Analyzing: '$inputText'")
        val matches = mutableMapOf<String, List<RegexMatch>>()
        var totalWeight = 0f

        for ((category, patterns) in HINGLISH_SCAM_PATTERNS) {
            val categoryMatches = patterns.mapNotNull { pattern ->
                pattern.find(inputText)?.let { match ->
                    RegexMatch(pattern = pattern.pattern, value = match.value, index = match.range.first)
                }
            }
            if (categoryMatches.isNotEmpty()) {
                android.util.Log.d("IntentFirewall|HINGLISH", "Hinglish: $category matched with ${categoryMatches.size} patterns")
                matches[category] = categoryMatches
                totalWeight += CATEGORY_WEIGHTS[category] ?: 0.1f
            }
        }

        android.util.Log.d("IntentFirewall|HINGLISH", "Hinglish: Final confidence: ${kotlin.math.min(totalWeight, 1.0f)}, Categories: ${matches.keys.toList()}")
        return HinglishScamResult(
            detected = matches.isNotEmpty(),
            categories = matches.keys.toList(),
            confidence = kotlin.math.min(totalWeight, 1.0f),
            matches = matches
        )
    }
}
