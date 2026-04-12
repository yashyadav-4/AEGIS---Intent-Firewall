package com.intentfirewall

import android.content.Context
import android.content.Intent
import android.util.Log

object AegisCallState {
    @Volatile var isCallActive: Boolean = false
    @Volatile var callerNumber: String? = null
    @Volatile var callStartMs: Long = 0L
    @Volatile private var callRiskShown: Boolean = false
    @Volatile private var otpDuringUnknownCall: Boolean = false
    @Volatile private var bankNotificationDuringCall: Boolean = false
    @Volatile private var scamPatternCaller: Boolean = false
    @Volatile private var riskSignalCount: Int = 0
    @Volatile private var lastAlertConfidence: Float = 0f
    @Volatile private var lastAlertReason: String = ""
    @Volatile private var rawOtpSmsObserved: Boolean = false
    @Volatile private var rawOtpNotificationObserved: Boolean = false
    @Volatile private var rawBankOrUpiObserved: Boolean = false
    @Volatile private var rawScamPatternObserved: Boolean = false

    private val scamNumberPatterns = listOf(
        // Only flag test/fake numbers and obviously spoofed patterns
        Regex("""^(?:\+?91)?(?:000|111|222|333|444|555|666|777|888|999|12345|123456|9999|0000)\d*$"""),
        // Flag numbers with suspicious repeated digits (5+ times)
        Regex("""^(?:\+?91)?(\d)\1{4,}\d*$""")
    )

    private val bankNotificationPackages = setOf(
        "com.google.android.apps.nbu.paisa.user",
        "net.one97.paytm",
        "in.org.npci.upiapp",
        "com.phonepe.app",
        "com.sbi.lotusintouch",
        "com.csam.icici.bank.imobile",
        "com.axis.mobile"
    )
    
    private val listeners = mutableListOf<() -> Unit>()
    private val riskEventListeners = mutableSetOf<(String, String) -> Unit>()
    
    fun onCallStarted(context: Context, number: String?) {
        isCallActive = true
        callerNumber = number
        callStartMs = System.currentTimeMillis()
        callRiskShown = false
        otpDuringUnknownCall = false
        bankNotificationDuringCall = false
        scamPatternCaller = isSuspiciousNumber(number)
        riskSignalCount = 0
        lastAlertConfidence = 0f
        lastAlertReason = ""
        rawOtpSmsObserved = false
        rawOtpNotificationObserved = false
        rawBankOrUpiObserved = false
        rawScamPatternObserved = false
        emitRiskEvent("CALL", "started caller=${number ?: "unknown"} unknown=${number.isNullOrBlank()}")
        if (scamPatternCaller) {
            rawScamPatternObserved = true
            Log.w("AegisRisk", "Scam-like caller pattern matched: $number")
            emitRiskEvent("SIGNAL", "scam_pattern_match caller=${number ?: "unknown"} confidence=0.80")
            maybeWarn(
                context = context,
                confidence = 0.80f,
                reason = "Incoming caller number matches high-risk scam pattern."
            )
        }
    }
    
    fun onCallEnded() {
        val unknownCaller = callerNumber.isNullOrBlank() || callerNumber.equals("unknown", ignoreCase = true)
        val summary = "unknown_caller=$unknownCaller raw_signals=${buildRawSignalSummary()} policy_signals=${buildPolicySignalSummary()} signal_count=$riskSignalCount alert_fired=$callRiskShown final_confidence=${"%.2f".format(java.util.Locale.US, aggregateRiskScore())} last_alert_confidence=${"%.2f".format(java.util.Locale.US, lastAlertConfidence)} last_alert_reason=${if (lastAlertReason.isBlank()) "none" else lastAlertReason}"
        emitRiskEvent("SUMMARY", summary)
        emitRiskEvent("CALL", "ended")
        isCallActive = false
        callerNumber = null
        callStartMs = 0L
        callRiskShown = false
        otpDuringUnknownCall = false
        bankNotificationDuringCall = false
        scamPatternCaller = false
        riskSignalCount = 0
        lastAlertConfidence = 0f
        lastAlertReason = ""
        rawOtpSmsObserved = false
        rawOtpNotificationObserved = false
        rawBankOrUpiObserved = false
        rawScamPatternObserved = false
    }

    fun onCallScreened(context: Context, number: String?) {
        if (callerNumber.isNullOrBlank() && !number.isNullOrBlank()) {
            callerNumber = number
        }
        emitRiskEvent("SCREEN", "caller_screened caller=${number ?: "unknown"}")
        if (isSuspiciousNumber(number)) {
            scamPatternCaller = true
            rawScamPatternObserved = true
            Log.w("AegisRisk", "Screening detected scam-like number pattern: $number")
            emitRiskEvent("SIGNAL", "screening_scam_pattern caller=${number ?: "unknown"} confidence=0.80")
            if (isCallActive) {
                maybeWarn(
                    context = context,
                    confidence = 0.80f,
                    reason = "Caller number flagged as scam pattern during screening."
                )
            }
        }
    }

    fun onOtpSignal(context: Context, source: String, message: String) {
        if (!isCallActive) return
        val unknownCaller = callerNumber.isNullOrBlank() || callerNumber.equals("unknown", ignoreCase = true)
        if (!unknownCaller) return

        otpDuringUnknownCall = true
        Log.w("AegisRisk", "OTP during unknown active call from=$source")
        emitRiskEvent("SIGNAL", "otp_during_unknown_call source=$source confidence=0.95")
        notifyOtpReceived()
        maybeWarn(
            context = context,
            confidence = 0.95f,
            reason = "OTP message detected during unknown active call. Do not share OTP."
        )
    }

    fun onNotificationSignal(context: Context, packageName: String, title: String, text: String) {
        if (!isCallActive) return

        val combined = "$title $text"
        val otpPattern = Regex(
            """\b(otp|one.time.password|verification code|is your otp|do not share|passcode|security code)\b""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS)
        )
        if (otpPattern.containsMatchIn(combined)) {
            rawOtpNotificationObserved = true
            emitRiskEvent("SIGNAL", "otp_notification package=$packageName")
            onOtpSignal(context, "notification:$packageName", combined)
            return
        }

        if (isFinancialSignal(packageName, combined)) {
            bankNotificationDuringCall = true
            rawBankOrUpiObserved = true
            val score = aggregateRiskScore()
            emitRiskEvent("SIGNAL", "bank_or_upi_notification package=$packageName aggregate_confidence=${"%.2f".format(java.util.Locale.US, score)}")
            maybeWarn(
                context = context,
                confidence = score,
                reason = "Bank/UPI notification appeared during active call."
            )
        }
    }

    fun onSmsSignal(context: Context, sender: String?, body: String) {
        if (!isCallActive) return
        val otpPattern = Regex(
            """\b(otp|one.time.password|verification code|passcode|security code|one time)\b""",
            RegexOption.IGNORE_CASE
        )
        if (otpPattern.containsMatchIn(body)) {
            rawOtpSmsObserved = true
            emitRiskEvent("SIGNAL", "otp_sms sender=${sender ?: "unknown"}")
            onOtpSignal(context, "sms:${sender ?: "unknown"}", body)
        }
    }
    
    fun registerOtpListener(block: () -> Unit) {
        listeners.add(block)
    }

    fun registerRiskEventListener(listener: (String, String) -> Unit) {
        synchronized(riskEventListeners) {
            riskEventListeners.add(listener)
        }
    }

    fun unregisterRiskEventListener(listener: (String, String) -> Unit) {
        synchronized(riskEventListeners) {
            riskEventListeners.remove(listener)
        }
    }
    
    fun notifyOtpReceived() {
        if (isCallActive) listeners.forEach { it() }
    }

    private fun aggregateRiskScore(): Float {
        var score = 0f
        if (scamPatternCaller) score += 0.80f
        if (bankNotificationDuringCall) score += 0.40f
        if (otpDuringUnknownCall) score = maxOf(score, 0.95f)
        return score.coerceAtMost(1.0f)
    }

    private fun maybeWarn(context: Context, confidence: Float, reason: String) {
        if (callRiskShown) return
        val effective = maxOf(confidence, aggregateRiskScore())
        if (effective < 0.75f) return

        callRiskShown = true
        lastAlertConfidence = effective
        lastAlertReason = reason
        emitRiskEvent("ALERT", "friction_warning confidence=${"%.2f".format(java.util.Locale.US, effective)} reason=${reason.replace("\n", " ")}")
        val warningIntent = Intent(context, FrictionWarningActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(FrictionWarningActivity.EXTRA_CONFIDENCE_SCORE, effective)
            putExtra(FrictionWarningActivity.WARNING_REASON, reason)
        }
        runCatching { context.startActivity(warningIntent) }
            .onFailure { Log.e("AegisRisk", "Failed to launch warning UI", it) }
    }

    private fun isFinancialSignal(packageName: String, text: String): Boolean {
        if (packageName in bankNotificationPackages) return true
        if (text.isBlank()) return false
        val financialKeywords = Regex(
            """\b(upi|debited|credited|bank|a\/c|account|transaction|payment|collect request|autopay|mandate)\b""",
            RegexOption.IGNORE_CASE
        )
        return financialKeywords.containsMatchIn(text)
    }

    private fun isSuspiciousNumber(number: String?): Boolean {
        val normalized = number
            ?.replace(" ", "")
            ?.replace("-", "")
            ?.trim()
            ?: return false
        return scamNumberPatterns.any { it.containsMatchIn(normalized) }
    }

    private fun emitRiskEvent(label: String, text: String) {
        if (label == "SIGNAL") {
            riskSignalCount += 1
        }
        val snapshot = synchronized(riskEventListeners) { riskEventListeners.toList() }
        snapshot.forEach { listener ->
            runCatching { listener(label, text) }
                .onFailure { Log.w("AegisRisk", "Risk event listener failed", it) }
        }
    }

    private fun buildPolicySignalSummary(): String {
        val signals = mutableListOf<String>()
        if (scamPatternCaller) signals.add("scam_pattern_caller")
        if (bankNotificationDuringCall) signals.add("bank_or_upi_notification")
        if (otpDuringUnknownCall) signals.add("otp_during_unknown_call")
        return if (signals.isEmpty()) "none" else signals.joinToString(",")
    }

    private fun buildRawSignalSummary(): String {
        val rawSignals = mutableListOf<String>()
        if (rawScamPatternObserved) rawSignals.add("scam_pattern_observed")
        if (rawBankOrUpiObserved) rawSignals.add("bank_or_upi_notification_observed")
        if (rawOtpNotificationObserved) rawSignals.add("otp_notification_observed")
        if (rawOtpSmsObserved) rawSignals.add("otp_sms_observed")
        return if (rawSignals.isEmpty()) "none" else rawSignals.joinToString(",")
    }
}
