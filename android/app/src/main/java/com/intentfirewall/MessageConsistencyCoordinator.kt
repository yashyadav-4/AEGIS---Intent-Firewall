package com.intentfirewall

import java.util.concurrent.ConcurrentHashMap

object MessageConsistencyCoordinator {
    private const val SMS_WINDOW_MS = 20_000L
    private const val SIGNATURE_WINDOW_MS = 1_500L

    private val recentSmsByBody = ConcurrentHashMap<String, Long>()
    private val recentSignatures = ConcurrentHashMap<String, Long>()

    fun shouldSkipSignature(signature: String): Boolean {
        val now = System.currentTimeMillis()
        recentSignatures.entries.removeIf { now - it.value > SIGNATURE_WINDOW_MS }

        val seenAt = recentSignatures[signature]
        if (seenAt != null && now - seenAt <= SIGNATURE_WINDOW_MS) {
            return true
        }

        recentSignatures[signature] = now
        return false
    }

    fun noteSms(sender: String, body: String) {
        val now = System.currentTimeMillis()
        purgeOldSms(now)
        recentSmsByBody[smsKey(sender, body)] = now
    }

    fun hasRecentSmsMatch(sender: String, body: String): Boolean {
        val now = System.currentTimeMillis()
        purgeOldSms(now)
        val key = smsKey(sender, body)
        return recentSmsByBody.containsKey(key)
    }

    fun hasRecentSmsBody(body: String): Boolean {
        val now = System.currentTimeMillis()
        purgeOldSms(now)
        val normalizedBody = normalize(body)
        return recentSmsByBody.keys.any { key -> key.endsWith("|$normalizedBody") }
    }

    private fun purgeOldSms(now: Long) {
        recentSmsByBody.entries.removeIf { now - it.value > SMS_WINDOW_MS }
    }

    private fun smsKey(sender: String, body: String): String {
        return "${normalize(sender)}|${normalize(body)}"
    }

    private fun normalize(input: String): String {
        return input.trim().lowercase().replace("\\s+".toRegex(), " ")
    }
}
