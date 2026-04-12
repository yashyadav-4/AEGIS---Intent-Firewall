package com.intentfirewall

import java.util.concurrent.ConcurrentHashMap

object Tier1ConversationTracker {
    private const val MAX_MESSAGES_PER_THREAD = 5
    private const val KNOWN_SENDER_THRESHOLD = 3

    private val conversationHistory = ConcurrentHashMap<String, MutableList<String>>()
    private val senderMessageCount = ConcurrentHashMap<String, Int>()

    fun getRecentMessages(conversationKey: String): List<String> {
        val history = conversationHistory[conversationKey] ?: return emptyList()
        synchronized(history) {
            return history.toList()
        }
    }

    fun observeMessage(conversationKey: String, senderKey: String, message: String) {
        val normalizedMessage = message.trim()
        if (normalizedMessage.isEmpty()) return

        val history = conversationHistory.getOrPut(conversationKey) { mutableListOf() }
        synchronized(history) {
            history.add(normalizedMessage)
            if (history.size > MAX_MESSAGES_PER_THREAD) {
                history.removeAt(0)
            }
        }

        senderMessageCount.compute(senderKey) { _, count ->
            (count ?: 0) + 1
        }
    }

    fun isKnownSender(senderKey: String): Boolean {
        return (senderMessageCount[senderKey] ?: 0) >= KNOWN_SENDER_THRESHOLD
    }

    fun isFirstMessageFromSender(senderKey: String): Boolean {
        return (senderMessageCount[senderKey] ?: 0) == 0
    }
}