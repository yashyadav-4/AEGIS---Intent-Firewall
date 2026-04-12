package com.intentfirewall

object ScamDatabase {

    private val KNOWN_SCAM_PREFIXES = setOf(
        "+92", "+880", "+855", "+234", "+44 7",
        "+1 876", "+1 242", "+1 473"
    )

    private val HIGH_RISK_INDIAN_PATTERNS = listOf(
        Regex("""^(\+91|0)?14[0-9]{9}$"""),
        Regex("""^(\+91|0)?18[0-9]{9}$"""),
        Regex("""^(\+91|0)?1[6-9][0-9]{9}$""")
    )

    private val SPOOFED_GOVT_PATTERNS = listOf(
        Regex("""^1800[0-9]{6,7}$"""),
        Regex("""^1930$"""),
        Regex("""^1947$""")
    )

    data class ScamCheckResult(
        val riskScore: Float,
        val reason: String,
        val shouldBlock: Boolean
    )

    fun check(phoneNumber: String): ScamCheckResult {
        if (phoneNumber.isNullOrBlank()) {
            return ScamCheckResult(
                0.40f,
                "WITHHELD_NUMBER",
                false
            )
        }

        val normalized = phoneNumber
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "")

        for (prefix in KNOWN_SCAM_PREFIXES) {
            if (normalized.startsWith(prefix.replace(" ", ""))) {
                return ScamCheckResult(
                    0.60f,
                    "HIGH_RISK_PREFIX:$prefix",
                    false
                )
            }
        }

        for (pattern in HIGH_RISK_INDIAN_PATTERNS) {
            if (pattern.containsMatchIn(normalized)) {
                return ScamCheckResult(
                    0.35f,
                    "BULK_CALLER_PATTERN",
                    false
                )
            }
        }

        for (pattern in SPOOFED_GOVT_PATTERNS) {
            if (pattern.containsMatchIn(normalized)) {
                return ScamCheckResult(
                    0.55f,
                    "SPOOFED_GOVT_PATTERN",
                    false
                )
            }
        }

        return ScamCheckResult(0.0f, "CLEAN", false)
    }
}