package com.intentfirewall

data class Message(
    val sender: String,
    val content: String,
    val timestamp: Long
)

/**
 * Tracks the context of a conversation and aggregates recent messages
 * to detect Hindi/Urdu/Hinglish scam patterns spanning multiple messages.
 */
class ConversationContext(private val maxHistory: Int = 50) {
    private val messages = mutableListOf<Message>()

    fun addMessage(sender: String, content: String, timestamp: Long = System.currentTimeMillis()) {
        messages.add(Message(sender, content, timestamp))
        if (messages.size > maxHistory) {
            messages.removeAt(0)
        }
    }

    fun getRecentContext(count: Int = 10): List<Message> {
        return messages.takeLast(count)
    }

    /**
     * Analyzes trailing conversational context using RegexSentinel's
     * Hinglish/Urdu/Hindi patterns.
     */
    fun analyzeContext(messageCount: Int = 5): RegexSentinel.HinglishScamResult {
        val recentText = getRecentContext(messageCount).joinToString(" ") { it.content }
        return RegexSentinel.detectHinglishScam(recentText)
    }
    
    fun clear() {
        messages.clear()
    }
}
