package com.intentfirewall

// Tier 1 — runs on every message, ~0.1ms, 99% exit here as BENIGN
object RegexSentinel {

    private data class CuratedSignature(
        val category: String,
        val tokens: List<String>
    )

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

    data class RegexResult(
        val isScam: Boolean,
        val confidence: Float,
        val detections: List<String>
    )

    fun analyzeCallTranscript(text: String): RegexResult {
        // Hinglish patterns for call context only
        // Triggered on user's own speech (not caller's)
        val patterns = listOf(
            Regex("""\b(otp|one.?time)\b""", RegexOption.IGNORE_CASE) to 0.85f,
            Regex("""\b(pin|password)\b.{0,20}\b(share|bata|dedo|batao)\b""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)) to 0.90f,
            Regex("""\b(account|khata).{0,20}\b(number|detail)\b""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)) to 0.70f,
            Regex("""\b(transfer|bhejo|send).{0,20}\b(money|paisa|amount|rupee)\b""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)) to 0.80f,
        )
        val matches = patterns
            .filter { (regex, _) -> regex.containsMatchIn(text) }
            .map { (_, conf) -> conf }
        val maxConf = matches.maxOrNull() ?: 0f
        return RegexResult(
            isScam = maxConf >= 0.70f,
            confidence = maxConf,
            detections = emptyList()
        )
    }

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
        CompiledPattern("FINANCIAL_PRESSURE", "\\bsend.*(?:money|₹|rs|rupees)\\b", Regex("\\bsend.*(?:money|₹|rs|rupees)\\b", RegexOption.IGNORE_CASE)),
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
        // GROUP 7 - HINGLISH_SCAM
        CompiledPattern("OTP_HARVEST_HINGLISH", "\\botp\\s*(bata|de|bhejo|share|dedo|batao)\\b", Regex("\\botp\\s*(bata|de|bhejo|share|dedo|batao)\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("OTP_HARVEST_HINGLISH", "\\b(apna|aapka)\\s*(pin|password|otp)\\s*(batao|dedo|share)\\b", Regex("\\b(apna|aapka)\\s*(pin|password|otp)\\s*(batao|dedo|share)\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("AUTHORITY_HINDI", "\\b(mai|main|hum)\\s*(cbi|ed|rbi|trai|income tax|cyber crime)\\b", Regex("\\b(mai|main|hum)\\s*(cbi|ed|rbi|trai|income tax|cyber crime)\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("URGENCY_HINGLISH", "\\b(abhi|turant|foran|jaldi)\\s*(transfer|payment|pay|bhejo)\\b", Regex("\\b(abhi|turant|foran|jaldi)\\s*(transfer|payment|pay|bhejo)\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("URGENCY_HINGLISH", "\\b(account|sim|number)\\s*(band|block|suspend)\\b", Regex("\\b(account|sim|number)\\s*(band|block|suspend)\\b", RegexOption.IGNORE_CASE)),
        CompiledPattern("FINANCIAL_HINGLISH", "\\b(upi|phonepay|gpay|paytm)\\s*(pin|id|number)\\b", Regex("\\b(upi|phonepay|gpay|paytm)\\s*(pin|id|number)\\b", RegexOption.IGNORE_CASE)),
    )

    // GROUP 7 — HINGLISH_SCAM
    // Covers Hindi-English mixed scam phrases
    // Specifically tuned for Indian telecom fraud
    private val GROUP_7_HINGLISH = listOf(
        // Authority impersonation in Hinglish
        Regex("\\b(main|mein|mai)\\s*(sbi|rbi|hdfc|icici|bank|police|cbi)\\s*(se|ka|officer)\\b", 
              RegexOption.IGNORE_CASE),
        Regex("\\b(officer|sahab|inspector)\\s*(bol|speaking|here|hun|hoon)\\b", 
              RegexOption.IGNORE_CASE),
        Regex("\\b(cyber|cybercrime)\\s*(cell|police|department|se)\\b", 
              RegexOption.IGNORE_CASE),
        Regex("\\bbank.{0,10}(bol raha|calling|se hun)\\b", 
              RegexOption.IGNORE_CASE),
        
        // OTP requests in Hinglish
        Regex("\\b(otp|top|o\\.t\\.p).{0,15}(bata|de|bhejo|share|dedo|batao|dijiye|bataiye)\\b", 
              RegexOption.IGNORE_CASE),
        Regex("\\b(bata|de|dedo|batao|share karo).{0,10}(otp|top|code|number)\\b", 
              RegexOption.IGNORE_CASE),
        Regex("\\b(apna|aapka|tera|tumhara).{0,10}(otp|pin|password|code)\\b", 
              RegexOption.IGNORE_CASE),
        
        // Urgency in Hinglish
        Regex("\\b(abhi|turant|jaldi|fauran).{0,10}(bata|de|karo|kijiye|bhejo)\\b", 
              RegexOption.IGNORE_CASE),
        Regex("\\b(warna|nahi\\s*to).{0,15}(block|band|action|arrest|case)\\b", 
              RegexOption.IGNORE_CASE),
        Regex("\\b(sirf|bas).{0,5}(\\d+).{0,5}(minute|second|ghanta|din)\\b", 
              RegexOption.IGNORE_CASE),
        Regex("\\b(account|khata).{0,10}(band|block|suspend).{0,10}(ho\\s*ga|hoga|jayega)\\b", 
              RegexOption.IGNORE_CASE),
        
        // Financial pressure in Hinglish
        Regex("\\b(paisa|paise|rupaye|amount).{0,10}(bhejo|transfer|do|de)\\b", 
              RegexOption.IGNORE_CASE),
        Regex("\\bupi.{0,10}(pin|id|number|send|bhejo)\\b", 
              RegexOption.IGNORE_CASE),
        Regex("\\b(fine|penalty|tax|bail).{0,10}(bhar|pay|de|bharo)\\b", 
              RegexOption.IGNORE_CASE),
        
        // Threat escalation in Hinglish  
        Regex("\\b(ghar|address).{0,10}(aayenge|aao|visit|bhejenge)\\b", 
              RegexOption.IGNORE_CASE),
        Regex("\\b(arrest|pakad|jail).{0,10}(ho|kar|lenge|jayenge)\\b", 
              RegexOption.IGNORE_CASE),
        Regex("\\b(case|fir|complaint).{0,10}(file|darj|hoga|karenge)\\b", 
              RegexOption.IGNORE_CASE)
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

    // High-priority hardcoded signatures from live scam reports.
    // Matching is token-based on normalized text so sender identity is irrelevant.
    private val CURATED_SCAM_SIGNATURES = listOf(
        CuratedSignature(
            category = "CURATED_FLIPKART_PAYLATER_OTP",
            tokens = listOf("flipkart", "paylater", "blocked", "otp", "verify", "unblock")
        ),
        CuratedSignature(
            category = "CURATED_JIOFIBER_REMOTE_ACCESS",
            tokens = listOf("jiofiber", "firmware", "teamviewer", "quicksupport", "technician", "remotely")
        ),
        CuratedSignature(
            category = "CURATED_ELECTRICITY_OBFUSCATED_LINK",
            tokens = listOf("electricity", "power", "cut", "930", "update", "bill", "clicking")
        ),
        CuratedSignature(
            category = "CURATED_WHATSAPP_LOGIN_CODE",
            tokens = listOf("whatsapp", "logged", "another", "device", "share", "6digit", "verification", "sms")
        ),
        CuratedSignature(
            category = "CURATED_CBI_SKYPE_INTERROGATION",
            tokens = listOf("cbi", "frozen", "money", "laundering", "skype", "cbidesk04", "interrogation")
        ),
        CuratedSignature(
            category = "CURATED_ITR_REFUND_PAN_UPDATE",
            tokens = listOf("income", "tax", "itr", "refund", "incorrect", "bank", "pan", "update")
        ),
        CuratedSignature(
            category = "CURATED_LOTTERY_6DIGIT_OTP",
            tokens = listOf("lottery", "claim", "send", "6digit", "number", "batayein")
        ),
    )

    private fun normalizeForCuratedMatch(text: String): String {
        val lower = text.lowercase()
        val deobfuscated = lower
            .replace("0", "o")
            .replace("1", "l")
            .replace("3", "e")
            .replace("4", "a")
            .replace("5", "s")
            .replace("7", "t")

        val compact = buildString(deobfuscated.length) {
            for (c in deobfuscated) {
                if (c.isLetterOrDigit()) append(c)
                else append(' ')
            }
        }
        return compact.replace(Regex("\\s+"), " ").trim()
    }

    private fun containsCuratedSignature(normalizedText: String): CuratedSignature? {
        val compactNoSpaces = normalizedText.replace(" ", "")
        return CURATED_SCAM_SIGNATURES.firstOrNull { sig ->
            sig.tokens.all { token ->
                normalizedText.contains(token) || compactNoSpaces.contains(token)
            }
        }
    }

    fun analyze(text: String): SentinelResult {
        val normalized = text.lowercase()
        val curatedNormalized = normalizeForCuratedMatch(text)

        val curatedMatch = containsCuratedSignature(curatedNormalized)
        if (curatedMatch != null) {
            return SentinelResult(
                flagged = true,
                matchedCategory = curatedMatch.category,
                matchedPattern = "CURATED_SCAM_SIGNATURE"
            )
        }

        for (entry in compiledPatterns) {
            if (entry.regex.containsMatchIn(normalized)) {
                return SentinelResult(
                    flagged = true,
                    matchedCategory = entry.category,
                    matchedPattern = entry.pattern
                )
            }
        }

        for (pattern in GROUP_7_HINGLISH) {
            val match = pattern.find(text)
            if (match != null) {
                return SentinelResult(
                    flagged = true,
                    matchedCategory = "HINGLISH_SCAM",
                    matchedPattern = match.value
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
