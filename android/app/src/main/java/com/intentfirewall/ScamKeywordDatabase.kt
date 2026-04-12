package com.intentfirewall

data class ScamCategory(
    val id: String,
    val displayName: String,
)

data class ScamCategoryDef(
    val category: ScamCategory,
    val baseScore: Int,
    val high: Set<String>,
    val medium: Set<String>,
    val low: Set<String>,
    val hinglish: Set<String>,
    val evidenceBoostKeywords: Set<String>,
    val requireAllGroups: List<String>,
    val requireAnyGroup: List<String>,
    val groups: Map<String, Set<String>>,
)

/** Static scam keyword database used by Tier 1 deterministic scoring. */
object ScamKeywordDatabase {
    val urgencySignals = setOf(
        "urgent", "turant", "abhi", "abhi ke abhi", "jaldi", "immediately",
        "aaj hi", "aaj raat", "tonight", "2 hours", "kal tak", "last chance",
        "final warning", "last warning", "account band", "connection cut",
        "action liya jayega", "legal action", "arrested", "warrant"
    )

    val moneySignals = setOf(
        "paisa bhejo", "send money", "transfer karo", "pay now", "payment karo",
        "upi se bhejo", "neft", "rtgs", "wallet mein dalo", "recharge karo",
        "rs.", "rupees", "₹", "amount", "fees", "charge", "fine bharo",
        "deposit karo", "fund karo", "invest karo"
    )

    val authoritySignals = setOf(
        "bank", "rbi", "sbi", "hdfc", "icici", "axis", "kotak", "police", "cbi",
        "cid", "income tax", "government", "government of india", "trai", "bsnl",
        "jio", "airtel", "customs", "narcotics", "cyber crime", "court", "judge",
        "microsoft", "amazon", "flipkart", "google", "apple"
    )

    val categories: List<ScamCategoryDef> = listOf(
        otp(),
        kyc(),
        lottery(),
        job(),
        loan(),
        investment(),
        impersonation(),
        romance(),
        techSupport(),
        courier(),
        utility(),
    )

    val allKeywords: Set<String> by lazy {
        categories.flatMap {
            it.high + it.medium + it.low + it.hinglish + it.evidenceBoostKeywords + it.groups.values.flatten()
        }.map { k -> k.lowercase() }.toSet()
    }

    private fun otp() = ScamCategoryDef(
        category = ScamCategory("OTP_SCAM", "OTP Scam"),
        baseScore = 95,
        high = setOf("otp", "one time password", "verification code", "otp share karo", "share your otp", "give me your otp", "code batao", "code bhejo", "4 digit code", "6 digit code", "bank otp", "upi pin", "o.t.p", "otp number", "verification number", "otp aaya", "woh code", "wo code", "code aaya", "need otp", "need otp for bank account", "need otp for bank accounts security", "forward the sms you just received", "forward sms"),
        medium = setOf("verify karo", "account verify", "code aaya hoga", "sms mein code", "registration code", "token number", "security number", "confirm code", "activation code", "verify your identity", "identity verification code", "send otp quickly"),
        low = setOf("confirm karo", "verify your account", "security code", "code", "number batao", "pin"),
        hinglish = setOf("woh code batao", "bank ka code", "woh number share karo", "code aaya na", "otp dedo", "code dedo", "wo number batao", "code bata do", "otp chahiye", "sms forward karo", "bank security ke liye otp bhejo"),
        evidenceBoostKeywords = setOf("bank", "rbi", "sbi", "hdfc", "icici", "paytm", "upi", "government", "telecom", "airtel", "jio", "bsnl", "forward", "identity", "verify", "security"),
        requireAllGroups = listOf("OTP_TOKEN", "SHARE_REQUEST"),
        requireAnyGroup = emptyList(),
        groups = mapOf(
            "OTP_TOKEN" to setOf("otp", "code", "pin", "password", "verification"),
            "SHARE_REQUEST" to setOf("batao", "bhejo", "share", "send", "dedo", "bolo", "give", "give me", "provide", "provide me", "tell", "tell me", "need", "need otp", "forward", "forward sms", "sms")
        )
    )

    private fun kyc() = ScamCategoryDef(
        category = ScamCategory("KYC_SCAM", "KYC Scam"),
        baseScore = 88,
        high = setOf("kyc update", "kyc expired", "kyc pending", "account band ho jayega", "account block", "aadhaar update", "pan update", "document submit", "kyc complete", "re-kyc", "video kyc", "kyc verify", "kyc link", "pending pan kyc", "account temporarily blocked due to pending pan kyc", "restore services", "click link to update details", "sim card kyc is pending", "number will be deactivated in 10 minutes", "to verify your identity", "forward the sms you just received to 1900"),
        medium = setOf("kyc complete karo", "link expire", "72 ghante", "48 hours", "verify your kyc", "submit documents", "account suspend", "account deactivate", "document upload", "24 ghante mein", "account blocked", "update pan details", "update your details immediately", "services restored after kyc", "sim kyc pending", "deactivated in 10 minutes", "verify identity", "send sms to 1900"),
        low = setOf("update karein", "profile update", "details update karo", "document chahiye", "id proof", "id verification"),
        hinglish = setOf("kyc nahi kiya toh band ho jayega", "aapka kyc update nahi hai", "turant kyc karein", "aaj hi kyc karo", "kyc pending hai aapka", "link pe ja ke kyc karo", "account band ho jayega kyc ke bina"),
        evidenceBoostKeywords = setOf("band ho jayega", "block", "suspend", "deactivate", "72", "48", "24 hours", "aaj hi", "expire", "last date", "hdfc", "sbi", "icici", "pan kyc", "temporarily blocked", "restore services", "click link", "sim", "1900", "deactivated in 10 minutes", "verify your identity"),
        requireAllGroups = listOf("KYC_TOKEN", "URGENCY_SIGNAL"),
        requireAnyGroup = emptyList(),
        groups = mapOf(
            "KYC_TOKEN" to setOf("kyc", "aadhaar", "pan", "document", "id proof"),
            "URGENCY_SIGNAL" to setOf("band", "block", "suspend", "expire", "aaj", "abhi")
        )
    )

    private fun lottery() = ScamCategoryDef(
        category = ScamCategory("LOTTERY_SCAM", "Lottery Scam"),
        baseScore = 97,
        high = setOf("congratulations you won", "lucky draw", "prize money", "jackpot", "lottery winner", "claim your prize", "lakh won", "free iphone", "gift voucher won", "bumper prize", "mega prize", "winner declared", "you have been selected", "cash prize", "2 crore lottery", "50 lakh prize", "credit card reward points", "reward points worth", "points will expire today", "download rewards apk", "redeem directly to your bank account"),
        medium = setOf("selected ho gaye", "winner hai aap", "redeem karo", "claim karo", "prize claim", "reward collect", "prize release", "winning amount", "prize ka paisa", "reward points expire", "redeem points", "download apk to redeem"),
        low = setOf("special offer", "gift for you", "selected customer", "exclusive reward", "you are selected", "chosen customer"),
        hinglish = setOf("aap lucky winner hain", "prize jeeta hai aapne", "abhi claim karo", "aapka naam lottery mein aaya", "aap winner hain", "reward claim karo abhi"),
        evidenceBoostKeywords = setOf("processing fee", "tax pay karo", "courier charge", "registration fee", "small fee", "release charge", "claim karne ke liye paisa", "verification fee", "reward points", "expire today", "apk", "redeem") ,
        requireAllGroups = listOf("WIN_TOKEN", "CLAIM_ACTION"),
        requireAnyGroup = emptyList(),
        groups = mapOf(
            "WIN_TOKEN" to setOf("won", "winner", "prize", "lottery", "lucky draw", "jackpot", "jeeta"),
            "CLAIM_ACTION" to setOf("claim", "redeem", "collect", "receive", "paisa lo", "prize lo")
        )
    )

    private fun job() = ScamCategoryDef(
        category = ScamCategory("JOB_SCAM", "Job Scam"),
        baseScore = 85,
        high = setOf("work from home", "part time job", "earn daily", "task complete karo", "like karo aur paisa lo", "youtube like job", "product rating job", "per task payment", "ghar baithe job", "online task", "telegram task", "hotel rating", "app rating job", "500 per like", "1000 per task", "daily withdrawal", "instant withdrawal task", "hr dept", "selected for part-time wfh job", "earn rs 3000-5000 daily", "google reviews", "wa.me link", "data entry role", "refundable security deposit", "offer letter", "vip level", "upgrade to vip", "withdraw commission"),
        medium = setOf("ghar baithe kamao", "daily income", "flexible job", "online earning", "task based", "500 per day", "earn per click", "social media job", "data entry job", "typing job online", "survey job", "shortlisted profile", "salary 45000", "security deposit", "merchant account", "commission withdrawal", "first task complete"),
        low = setOf("earn money online", "side income", "extra income", "part time", "online job", "work from home job"),
        hinglish = setOf("ghar se kaam karo", "roz paisa milega", "task karo paise lo", "like karke kamao", "bina investment ke kamao", "ek din mein 2000 kamao", "daily 500 guaranteed"),
        evidenceBoostKeywords = setOf("advance deposit", "registration fee", "wallet", "invest to unlock", "upgrade plan", "membership fee", "security deposit", "pehle deposit karo", "task unlock karne ke liye paisa", "wa.me", "telegram", "offer letter", "vip", "merchant account", "withdraw commission"),
        requireAllGroups = listOf("TASK_TOKEN", "PAYMENT_PROMISE"),
        requireAnyGroup = emptyList(),
        groups = mapOf(
            "TASK_TOKEN" to setOf("task", "like", "rate", "review", "earn", "rating", "click"),
            "PAYMENT_PROMISE" to setOf("paisa", "payment", "income", "salary", "earn", "milega")
        )
    )

    private fun loan() = ScamCategoryDef(
        category = ScamCategory("LOAN_SCAM", "Loan Scam"),
        baseScore = 82,
        high = setOf("instant loan", "pre-approved loan", "no documents", "5 minute loan", "loan approved", "apply karo abhi", "loan app install karo", "aadhar se loan", "no cibil", "loan without cibil", "apk download", "loan app link", "turat loan", "10 lakh loan approved", "loan seedha account mein", "guaranteed loan approval"),
        medium = setOf("low interest loan", "personal loan offer", "credit line", "cibil nahi chahiye", "instant approval", "same day loan", "loan disbursal", "low emi loan", "zero interest loan"),
        low = setOf("loan chahiye", "paisa chahiye", "fund chahiye", "financial help", "urgent money", "emergency loan"),
        hinglish = setOf("5 minute mein loan", "bina document ke loan", "turant paisa milega", "loan app download karo", "cibil nahi chahiye loan ke liye", "seedha account mein aayega"),
        evidenceBoostKeywords = setOf("install", "download", "apk", "link", "no cibil", "bina document", "processing fee pehle", "advance emi"),
        requireAllGroups = listOf("LOAN_TOKEN", "ACTION_REQUEST"),
        requireAnyGroup = emptyList(),
        groups = mapOf(
            "LOAN_TOKEN" to setOf("loan", "credit", "borrow", "lend", "paisa", "fund"),
            "ACTION_REQUEST" to setOf("install", "download", "apply", "click", "link", "bharo")
        )
    )

    private fun investment() = ScamCategoryDef(
        category = ScamCategory("INVESTMENT_SCAM", "Investment Scam"),
        baseScore = 87,
        high = setOf("guaranteed returns", "double your money", "10x profit", "100x returns", "crypto investment", "trading group join karo", "insider tip", "sure shot profit", "paisa double karo", "guaranteed profit", "risk free investment", "100% return", "stock tip today", "forex profit", "binary options", "futures trading guaranteed", "usdt investment", "bitcoin double", "eth profit guaranteed"),
        medium = setOf("investment opportunity", "high returns", "risk free", "trading signal", "forex profit", "stock tip", "crypto se kamao", "bitcoin profit", "nifty tip", "sensex prediction", "mutual fund guaranteed"),
        low = setOf("invest karo", "returns milenge", "profit milega", "trading karo", "stock market", "share market"),
        hinglish = setOf("paisa double hoga", "guaranteed profit denge", "sirf invest karo", "trading mein kamao", "group join karo profit ke liye", "ek hafte mein double"),
        evidenceBoostKeywords = setOf("telegram group", "whatsapp group", "send money", "wallet", "crypto", "bitcoin", "usdt", "tether", "binance", "group mein aao", "signal group"),
        requireAllGroups = listOf("RETURN_CLAIM", "INVEST_ACTION"),
        requireAnyGroup = emptyList(),
        groups = mapOf(
            "RETURN_CLAIM" to setOf("guaranteed", "double", "10x", "profit", "returns", "interest"),
            "INVEST_ACTION" to setOf("invest", "send", "deposit", "transfer", "join group")
        )
    )

    private fun impersonation() = ScamCategoryDef(
        category = ScamCategory("IMPERSONATION_SCAM", "Impersonation Scam"),
        baseScore = 90,
        high = setOf("main bank se bol raha hoon", "cbi officer", "police se bol raha", "trai", "rbi officer", "income tax department", "main aapka dost hoon new number", "courier agent", "fedex parcel", "customs officer", "cyber crime department", "narcotics officer", "it officer", "enforcement directorate", "ed officer", "ncb officer", "this side", "i lost my phone", "new number save", "send me 5k", "send me 10k", "need urgent money", "please send money on this number", "from police department", "send us 2 lakh", "will not write fir", "we will not write fir", "your son have been killed", "digital arrest", "automated call from trai", "number will be deactivated in 2 hours", "press 9 to speak", "cyber crime executive", "pay a penalty of rs", "avoid immediate fir", "arrest warrant", "safe rbi account", "video statement", "money laundering case", "transfer your funds to our safe account"),
        medium = setOf("government official", "legal notice", "case filed against you", "customs duty", "parcel pakda gaya", "your number involved", "aapka number blacklist mein", "aapke naam complaint", "summons bheja hai", "i am akansha this side", "my old number is not working", "this is my new number", "please help urgently", "send money quickly", "emergency money", "police department", "do not write fir", "not write fir", "2 lakh", "killed", "cyber cell", "money laundering", "deactivated in 2 hours", "verification account", "video statement on skype", "safe account for auditing"),
        low = setOf("official call", "legal matter", "notice bheja hai", "complaint hai aapke against", "verification call", "new number", "urgent money", "help me with money"),
        hinglish = setOf("main police se hoon", "aapke naam arrest warrant hai", "ye mera naya number hai paise bhejo", "cbi ne case kiya hai", "aapka number illegal activity mein use hua", "aapke account se fraud hua hai", "mera phone kho gaya", "mera naya number hai", "is number pe paise bhej do", "urgent paisa bhejo"),
        evidenceBoostKeywords = setOf("arrest", "warrant", "case", "fine", "pay now", "jail", "legal action", "court", "giraftari", "fir", "chalaan", "lost my phone", "new number", "urgent money", "send money", "5k", "10k", "2 lakh", "police department", "not write fir", "killed", "digital arrest", "trai", "cyber crime", "money laundering", "safe rbi account", "verification account", "press 9"),
        requireAllGroups = listOf("THREAT_OR_DEMAND"),
        requireAnyGroup = listOf("AUTHORITY_CLAIM_TOKEN", "IDENTITY_CLAIM_TOKEN"),
        groups = mapOf(
            "AUTHORITY_CLAIM_TOKEN" to setOf("officer", "police", "cbi", "rbi", "trai", "government", "department", "court", "bank", "customs", "narcotics", "ed", "ncb"),
            "IDENTITY_CLAIM_TOKEN" to setOf("this side", "i am", "it's me", "it is me", "my new number", "new number", "lost my phone", "old number", "save this number"),
            "THREAT_OR_DEMAND" to setOf("arrest", "warrant", "case", "fine", "pay", "legal", "action", "giraftari", "send money", "account freeze", "urgent money", "need money", "5k", "10k", "transfer", "help urgently", "send us", "2 lakh", "not write fir", "fir", "penalty", "deactivated", "safe account", "verification account", "auditing")
        )
    )

    private fun romance() = ScamCategoryDef(
        category = ScamCategory("ROMANCE_SCAM", "Romance Sextortion Scam"),
        baseScore = 80,
        high = setOf("video call record kar liya", "screenshot le liya", "contacts ko bhej dunga", "blackmail", "intimate video", "viral kar dunga", "pay karo warna", "video leak karunga", "photo bhej dunga", "family ko dikhaunga", "nude video", "private video", "aapki photo mere paas", "morphed photo", "leak those images", "i will leak those images", "last chance", "give me money or else", "either give me money", "upload this video to youtube", "send it to all your instagram followers", "deletion fee", "pay the deletion fee"),
        medium = setOf("meri photo mat share karna", "hamari baat private hai", "paisa bhejo", "army officer hu", "video hai mere paas", "screenshot liya", "foreign mein hoon", "dollar mein kamata hoon", "give me money", "send money or else", "or else i will leak", "private photos synced to our server", "face the consequences", "send 50k right now", "pay 10000"),
        low = setOf("mujhse pyar hai", "foreign mein hoon", "lonely hoon", "tumse baat karna accha lagta hai", "whatsapp pe aao"),
        hinglish = setOf("tumhara video hai mere paas", "sab ko dikha dunga", "paise do warna video viral", "family ko bata dunga", "contacts mein bhej dunga", "viral ho jayega"),
        evidenceBoostKeywords = setOf("pay", "send money", "paisa bhejo", "family", "contacts", "warna", "otherwise", "50000", "100000", "transfer karo", "money", "or else", "last chance", "leak", "images", "instagram", "youtube", "deletion fee", "consequences"),
        requireAllGroups = listOf("BLACKMAIL_TOKEN", "PAYMENT_THREAT"),
        requireAnyGroup = emptyList(),
        groups = mapOf(
            "BLACKMAIL_TOKEN" to setOf("video", "photo", "screenshot", "record", "viral", "leak", "nude", "image", "images"),
            "PAYMENT_THREAT" to setOf("pay", "send", "transfer", "paisa", "bhejo", "warna", "otherwise", "money", "give me", "or else")
        )
    )

    private fun techSupport() = ScamCategoryDef(
        category = ScamCategory("TECH_SUPPORT_SCAM", "Tech Support Scam"),
        baseScore = 83,
        high = setOf("anydesk install karo", "teamviewer", "remote access", "screen share karo", "virus detected", "your computer hacked", "refund process", "amazon refund", "microsoft support", "quicksupport", "rustdesk", "ultraviewer", "airdroid", "remote desktop", "screen mirroring karo", "hamein access do", "download the anydesk app", "download quicksupport app", "credit card cancellation", "our executive can guide you"),
        medium = setOf("technical issue", "your account compromised", "security breach", "support agent", "helpline number", "customer care", "device infected", "malware found", "suspicious activity", "account hack ho gaya", "data breach", "cancellation process", "install app to proceed"),
        low = setOf("computer problem", "account issue", "help chahiye", "technical support", "call back karo", "customer support"),
        hinglish = setOf("anydesk download karo", "hamein screen dikhao", "virus mila hai aapke phone mein", "remote se theek kar denge", "screen share karo hum fix karenge", "access do hum dekh lete hain"),
        evidenceBoostKeywords = setOf("install", "download", "refund", "virus", "hacked", "microsoft", "amazon", "google", "bank", "account access"),
        requireAllGroups = listOf("REMOTE_APP_MENTION"),
        requireAnyGroup = emptyList(),
        groups = mapOf(
            "REMOTE_APP_MENTION" to setOf("anydesk", "teamviewer", "quicksupport", "rustdesk", "ultraviewer", "airdroid", "remote access", "screen share", "screen mirroring", "remote desktop")
        )
    )

    private fun courier() = ScamCategoryDef(
        category = ScamCategory("COURIER_SCAM", "Courier Scam"),
        baseScore = 86,
        high = setOf("parcel pakda gaya", "customs duty pay karo", "courier hold", "illegal item parcel", "fedex parcel", "dhl notice", "drug parcel aapke naam", "courier agent", "parcel seized", "narcotics in parcel", "aadhaar card parcel", "sim card parcel", "parcel mein drugs mili", "illegal courier aapke naam", "india post delivery failed", "incomplete address", "redelivery fee", "international parcel held at delhi customs", "clearance charge", "release the package or legal action"),
        medium = setOf("delivery pending", "parcel release karne ke liye", "fine bharo", "package seized", "customs clearance", "delivery agent", "parcel stuck customs mein", "courier pakda gaya", "update your details", "delivery failed", "held at customs", "clearance fee"),
        low = setOf("delivery hai", "parcel hai", "courier aaya", "package delivery", "shipment"),
        hinglish = setOf("aapka parcel customs mein pakda gaya", "fine dena hoga", "drug mila hai parcel mein", "courier agent bol raha hoon", "aapke naam illegal parcel aaya hai"),
        evidenceBoostKeywords = setOf("drug", "illegal", "narcotic", "seized", "fine", "duty", "arrested", "case", "police", "narcotics department", "india post", "redelivery fee", "clearance charge", "customs", "legal action"),
        requireAllGroups = listOf("PARCEL_TOKEN", "PAYMENT_OR_THREAT"),
        requireAnyGroup = emptyList(),
        groups = mapOf(
            "PARCEL_TOKEN" to setOf("parcel", "courier", "package", "delivery", "customs", "shipment"),
            "PAYMENT_OR_THREAT" to setOf("pay", "fine", "duty", "seized", "illegal", "drug", "arrested")
        )
    )

    private fun utility() = ScamCategoryDef(
        category = ScamCategory("UTILITY_SCAM", "Utility Scam"),
        baseScore = 89,
        high = setOf("bijli kategi aaj raat", "electricity disconnected", "bill due aaj", "last warning bijli", "pay now warna cut", "bescom", "mseb", "bses notice", "electricity cut tonight", "power disconnection", "meter disconnect notice", "gas connection cut", "broadband disconnect notice", "bijli vibhag se bol raha hoon", "disconnected tonight at 9:30 pm", "previous month bill was not updated", "call our executive immediately", "mahanagar gas connection will be stopped today", "pending dues", "avoid rs 500 penalty"),
        medium = setOf("bill pending", "overdue bill", "disconnection notice", "pay immediately", "link se pay karo", "meter disconnect", "supply cut", "outstanding bill", "bill baaki hai", "update office", "connection will be stopped today", "pending dues", "pay through this link"),
        low = setOf("bill aaya", "payment karo", "due date", "electricity bill", "gas bill", "internet bill"),
        hinglish = setOf("aaj raat bijli kat jayegi", "abhi pay karo", "link se payment karo warna connection cut", "electricity department se bol raha hoon", "bijli ka bill baaki hai aaj raat kategi"),
        evidenceBoostKeywords = setOf("aaj raat", "tonight", "2 hours", "immediately", "link", "pay now", "cut ho jayega", "abhi bharo", "online pay karo", "9:30 pm", "penalty", "mahanagar gas", "executive"),
        requireAllGroups = listOf("UTILITY_TOKEN", "DISCONNECT_THREAT"),
        requireAnyGroup = emptyList(),
        groups = mapOf(
            "UTILITY_TOKEN" to setOf("bijli", "electricity", "bill", "power", "connection", "meter", "gas", "broadband", "internet", "bescom", "mseb", "bses"),
            "DISCONNECT_THREAT" to setOf("cut", "disconnect", "band", "kategi", "katega", "supply cut", "aaj raat", "tonight")
        )
    )
}
