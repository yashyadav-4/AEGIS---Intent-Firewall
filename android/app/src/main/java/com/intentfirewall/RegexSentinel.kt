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

    data class Tier1RiskAssessment(
        val score: Float,
        val level: String,
        val categories: List<String>,
        val escalationDetected: Boolean,
        val reasons: List<String>
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

        // GROUP 7 - INDIAN_SCAM_INDICATORS (additive)
        CompiledPattern("INDIAN_SCAM", "\\bupdate\\s*kyc\\b", Regex("\\bupdate\\s*kyc\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("INDIAN_SCAM", "\\bkyc\\s*update\\b", Regex("\\bkyc\\s*update\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("INDIAN_SCAM", "\\baad+h+a?r\\b", Regex("\\baad+h+a?r\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("INDIAN_SCAM", "\\bpan\\s*card\\b", Regex("\\bpan\\s*card\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("INDIAN_SCAM", "\\bupdate\\s*pan\\b", Regex("\\bupdate\\s*pan\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("INDIAN_SCAM", "\\bsim\\s*card\\s*blocked\\b", Regex("\\bsim\\s*card\\s*blocked\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("INDIAN_SCAM", "\\b(?:jio|airtel|vodafone).*(?:blocked|suspend|disconnect)\\b", Regex("\\b(?:jio|airtel|vodafone).*(?:blocked|suspend|disconnect)\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("INDIAN_SCAM", "\\b(?:dpd|blue\\s*dart|delivery).*(?:customs|duty|pay)\\b", Regex("\\b(?:dpd|blue\\s*dart|delivery).*(?:customs|duty|pay)\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("INDIAN_SCAM", "\\belectricity\\s*bill.*disconnect\\b", Regex("\\belectricity\\s*bill.*disconnect\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("INDIAN_SCAM", "\\bmnp\\b", Regex("\\bmnp\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("INDIAN_SCAM", "\\bport\\s*number\\b", Regex("\\bport\\s*number\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("INDIAN_SCAM", "\\b(?:google\\s*pay|gpay|phonepe).*(?:verify|account)\\b", Regex("\\b(?:google\\s*pay|gpay|phonepe).*(?:verify|account)\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("INDIAN_SCAM", "\\bwhatsapp\\s*(?:pink|gold|new\\s*feature)\\b", Regex("\\bwhatsapp\\s*(?:pink|gold|new\\s*feature)\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("INDIAN_SCAM", "\\b(?:cbid|rbi|sebi).*(?:verify|urgent|notice|account|payment)\\b", Regex("\\b(?:cbid|rbi|sebi).*(?:verify|urgent|notice|account|payment)\\b", RegexOption.IGNORE_CASE)),

        // GROUP 8 - URGENCY_AND_THREAT
        CompiledPattern("URGENCY", "\\bwithin\\s*(?:5|10)\\s*minutes?\\b", Regex("\\bwithin\\s*(?:5|10)\\s*minutes?\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("URGENCY", "\\bwithin\\s*1\\s*hour\\b", Regex("\\bwithin\\s*1\\s*hour\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("URGENCY", "\\b(?:right\\s*now|immediately|urgent|last\\s*chance|today\\s*only)\\b", Regex("\\b(?:right\\s*now|immediately|urgent|last\\s*chance|today\\s*only)\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("URGENCY", "\\b(?:limited\\s*time|offer\\s*expires|before\\s*5pm|don'?t\\s*delay|don'?t\\s*wait)\\b", Regex("\\b(?:limited\\s*time|offer\\s*expires|before\\s*5pm|don'?t\\s*delay|don'?t\\s*wait)\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("THREAT", "\\b(?:permanently\\s*blocked|legal\\s*action|court|police|fir|complaint\\s*filed)\\b", Regex("\\b(?:permanently\\s*blocked|legal\\s*action|court|police|fir|complaint\\s*filed)\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("THREAT", "\\b(?:number\\s*will\\s*be\\s*disconnected|you\\s*will\\s*lose\\s*money|emi\\s*bounce|loan\\s*default)\\b", Regex("\\b(?:number\\s*will\\s*be\\s*disconnected|you\\s*will\\s*lose\\s*money|emi\\s*bounce|loan\\s*default)\\b", RegexOption.IGNORE_CASE)),

        // GROUP 9 - AUTHORITY_VERIFICATION_FINANCIAL_APP
        CompiledPattern("AUTHORITY_IMPERSONATION", "\\bfrom\\s*(?:bank|police\\s*station|income\\s*tax\\s*department|govt|government\\s*official)\\b", Regex("\\bfrom\\s*(?:bank|police\\s*station|income\\s*tax\\s*department|govt|government\\s*official)\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("AUTHORITY_IMPERSONATION", "\\bfrom\\s*(?:amazon|flipkart|whatsapp|google|apple)\\s*(?:support|customer\\s*care)\\b", Regex("\\bfrom\\s*(?:amazon|flipkart|whatsapp|google|apple)\\s*(?:support|customer\\s*care)\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("VERIFICATION_SCAM", "\\b(?:don'?t\\s*share\\s*with\\s*anyone|keep\\s*it\\s*secret|this\\s*is\\s*between\\s*us)\\b", Regex("\\b(?:don'?t\\s*share\\s*with\\s*anyone|keep\\s*it\\s*secret|this\\s*is\\s*between\\s*us)\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("VERIFICATION_SCAM", "\\b(?:verification\\s*code|security\\s*code|link\\s*your\\s*account)\\b", Regex("\\b(?:verification\\s*code|security\\s*code|link\\s*your\\s*account)\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("FINANCIAL_SCAM", "\\b(?:double\\s*your\\s*money|crypto|bitcoin|trading|instant\\s*loan|credit\\s*card|increase\\s*limit)\\b", Regex("\\b(?:double\\s*your\\s*money|crypto|bitcoin|trading|instant\\s*loan|credit\\s*card|increase\\s*limit)\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("FINANCIAL_SCAM", "\\b(?:refund|get\\s*money\\s*back|work\\s*from\\s*home|easy\\s*money|part\\s*time\\s*job|online\\s*job)\\b", Regex("\\b(?:refund|get\\s*money\\s*back|work\\s*from\\s*home|easy\\s*money|part\\s*time\\s*job|online\\s*job)\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("LINK_APP_SCAM", "\\b(?:install\\s*app|download|click\\s*here|tap\\s*to|install\\s*now|\\.apk\\b)\\b", Regex("\\b(?:install\\s*app|download|click\\s*here|tap\\s*to|install\\s*now|\\.apk\\b)\\b", RegexOption.IGNORE_CASE)),
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

        // === ADDITIVE INDIAN/AUTHORITY/DELIVERY ===
        "DELIVERY_SCAM_HINGLISH" to listOf(
            Regex("""(blue\s*dart|dpd|delivery|courier).*(customs|duty|fee|charge).*(pay|do|karo)""", RegexOption.IGNORE_CASE),
            Regex("""parcel\s*(atka|ruka|held).*(payment|customs|duty)""", RegexOption.IGNORE_CASE),
        ),
        "UTILITY_THREAT_HINGLISH" to listOf(
            Regex("""electricity\s*bill.*(disconnect|kat|band)""", RegexOption.IGNORE_CASE),
            Regex("""sim\s*(band|block|suspend).*(turant|abhi|immediately)""", RegexOption.IGNORE_CASE),
        ),
        "AUTHORITY_SUPPORT_HINGLISH" to listOf(
            Regex("""(bank manager|police station|income tax|govt|government official)""", RegexOption.IGNORE_CASE),
            Regex("""(amazon|flipkart|whatsapp|google|apple).*(support|customer care)""", RegexOption.IGNORE_CASE),
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
        "DELIVERY_SCAM_HINGLISH" to 0.75f,
        "UTILITY_THREAT_HINGLISH" to 0.7f,
        "AUTHORITY_SUPPORT_HINGLISH" to 0.8f,
    )

    private val highRiskPatterns = listOf(
        Regex("\\b(?:otp|one\\s*time\\s*password|verification\\s*code|security\\s*code)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:upi\\s*pin|bank\\s*account|transaction|share\\s*otp)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:don'?t\\s*tell\\s*anyone|keep\\s*it\\s*secret|between\\s*us)\\b", RegexOption.IGNORE_CASE),
    )

    private val mediumRiskPatterns = listOf(
        Regex("\\b(?:investment|crypto|bitcoin|trading|loan\\s*approved|instant\\s*loan|job|work\\s*from\\s*home)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:http[s]?://|bit\\.ly|tinyurl|click\\s*here|download|install\\s*app|\\.apk)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:urgent|immediately|within\\s*\\d+\\s*(?:minutes?|hours?))\\b", RegexOption.IGNORE_CASE),
    )

    private val lowRiskPatterns = listOf(
        Regex("\\b(?:hello|hi|can\\s*you\\s*help\\s*me|just\\s*confirm)\\b", RegexOption.IGNORE_CASE),
    )

    private val innocentStagePatterns = listOf(
        Regex("\\b(?:can\\s*you\\s*help\\s*me|just\\s*confirm|tell\\s*me\\s*your\\s*name)\\b", RegexOption.IGNORE_CASE),
    )

    private val sensitiveStagePatterns = listOf(
        Regex("\\b(?:send\\s*otp|share\\s*details|need\\s*money|transfer\\s*money|upi\\s*pin)\\b", RegexOption.IGNORE_CASE),
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

    fun assessTier1Risk(
        currentMessage: String,
        recentMessages: List<String>,
        isKnownSender: Boolean,
        isFirstMessageFromSender: Boolean,
    ): Tier1RiskAssessment {
        val normalized = currentMessage.lowercase()
        var score = 0.0f
        val categories = mutableSetOf<String>()
        val reasons = mutableListOf<String>()

        if (!isKnownSender) {
            score += 0.15f
            categories.add("UNKNOWN_SENDER_BASELINE")
            reasons.add("Unknown sender baseline")
        }
        if (isFirstMessageFromSender) {
            score += 0.12f
            categories.add("FIRST_MESSAGE_FROM_NEW_SENDER")
            reasons.add("First message from this sender")
        }

        if (highRiskPatterns.any { it.containsMatchIn(normalized) }) {
            score += 0.72f
            categories.add("HIGH_RISK_SIGNAL")
            reasons.add("High-risk credential/transaction phrase")
        }

        if (mediumRiskPatterns.any { it.containsMatchIn(normalized) }) {
            score += 0.42f
            categories.add("MEDIUM_RISK_SIGNAL")
            reasons.add("Medium-risk offer/link/urgency phrase")
        }

        if (lowRiskPatterns.any { it.containsMatchIn(normalized) }) {
            score += 0.15f
            categories.add("LOW_RISK_SIGNAL")
            reasons.add("Generic low-risk suspicious phrasing")
        }

        val authorityPresent = Regex("\\b(?:rbi|sebi|cbi|bank|police|income\\s*tax|govt|government)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
        val urgencyPresent = Regex("\\b(?:urgent|immediately|right\\s*now|within\\s*\\d+\\s*(?:minutes?|hours?))\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
        if (authorityPresent && urgencyPresent) {
            score += 0.35f
            categories.add("AUTHORITY_URGENCY_COMBO")
            reasons.add("Authority impersonation with urgency")
        }

        val escalationDetected = detectEscalation(recentMessages, currentMessage)
        if (escalationDetected) {
            score += 0.33f
            categories.add("ESCALATION_PATTERN")
            reasons.add("Conversation escalated from innocent to sensitive ask")
        }

        val boundedScore = kotlin.math.min(score, 1.0f)
        val level = when {
            boundedScore >= 0.7f -> "HIGH"
            boundedScore >= 0.4f -> "MEDIUM"
            else -> "LOW"
        }

        return Tier1RiskAssessment(
            score = boundedScore,
            level = level,
            categories = categories.toList(),
            escalationDetected = escalationDetected,
            reasons = reasons,
        )
    }

    private fun detectEscalation(recentMessages: List<String>, currentMessage: String): Boolean {
        if (recentMessages.isEmpty()) return false
        val history = recentMessages.joinToString(" ").lowercase()
        val current = currentMessage.lowercase()

        val innocentSeen = innocentStagePatterns.any { it.containsMatchIn(history) }
        val sensitiveNow = sensitiveStagePatterns.any { it.containsMatchIn(current) }
        return innocentSeen && sensitiveNow
    }
}
