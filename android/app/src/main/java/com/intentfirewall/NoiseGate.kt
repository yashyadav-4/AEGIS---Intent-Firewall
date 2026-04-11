package com.intentfirewall

/** Noise gate for dropping UI/system/non-semantic text before scam analysis. */
object NoiseGate {
    private const val TAG = "SCAM_NoiseGate"

    private val uiDropList = setOf(
        "ok", "okay", "k", "hlo", "hello", "hi", "hey", "haan", "nahi",
        "yes", "no", "noted", "hmm", "done", "sure", "fine", "received",
        "thanks", "thank you", "dhanyawad", "shukriya", "acha", "theek hai",
        "bilkul", "zaroor", "ha ha", "lol", "haha", "bye", "good morning",
        "good night", "good evening", "shubh prabhat", "subh ratri"
    )

    private val statusDropList = setOf(
        "calling", "on a call", "open chat", "read more", "missed call",
        "declined", "call ended", "ringing", "connected", "on hold",
        "recording", "typing...", "online", "last seen", "delivered", "seen",
        "voice message", "photo", "video", "sticker", "document", "contact"
    )

    /** Returns true when text should be dropped before tiered analysis. */
    fun shouldDrop(text: String): Boolean {
        val normalized = text.trim().lowercase().replace("\\s+".toRegex(), " ")
        if (normalized.isBlank()) return true

        if (uiDropList.contains(normalized)) return true
        if (statusDropList.contains(normalized)) return true

        val words = normalized.split(" ").filter { it.isNotBlank() }
        if (words.size > 8) return false
        if (containsScamKeyword(normalized)) return false

        if (normalized.length < 4) return true
        if (Regex("^[\\p{So}\\s]+$").matches(normalized)) return true
        if (Regex("^\\d{1,2}:\\d{2}\\s?(am|pm)?$", RegexOption.IGNORE_CASE).matches(normalized)) return true
        if (Regex("^\\d+$").matches(normalized)) return true
        if (Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(normalized)) return true

        return false
    }

    private fun containsScamKeyword(normalized: String): Boolean {
        return ScamKeywordDatabase.allKeywords.any { kw -> normalized.contains(kw) }
    }
}
