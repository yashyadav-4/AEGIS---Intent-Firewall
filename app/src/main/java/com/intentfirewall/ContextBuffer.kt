// Component D — Per-sender sliding window, 5 turns, RAM only, 10-min TTL
package com.intentfirewall

object ContextBuffer {
    data class Turn(
        val speaker: String,    // "[THEM]" or "[YOU]"
        val text: String,
        val timestampMs: Long
    )

    private val turnsBySender: MutableMap<String, ArrayDeque<Turn>> = mutableMapOf()
    private val lastAccessMs: MutableMap<String, Long> = mutableMapOf()

    private const val MAX_TURNS = 5
    private const val TTL_MS = 10 * 60 * 1000L   // 10 minutes

    private fun evictExpired(nowMs: Long) {
        val iterator = lastAccessMs.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.value > TTL_MS) {
                iterator.remove()
                turnsBySender.remove(entry.key)
            }
        }
    }

    @Synchronized
    fun addTurn(senderId: String, speaker: String, text: String) {
        val nowMs = System.currentTimeMillis()
        evictExpired(nowMs)

        val deque = turnsBySender.getOrPut(senderId) { ArrayDeque() }
        if (deque.size >= MAX_TURNS) {
            deque.removeFirst()
        }
        deque.addLast(Turn(speaker, text, nowMs))
        lastAccessMs[senderId] = nowMs
    }

    @Synchronized
    fun getContext(senderId: String): String {
        val deque = turnsBySender[senderId] ?: return ""
        if (deque.isEmpty()) {
            return ""
        }

        lastAccessMs[senderId] = System.currentTimeMillis()
        return deque.joinToString(separator = "\n") { "${it.speaker} ${it.text}" }
    }

    @Synchronized
    fun clearSender(senderId: String) {
        turnsBySender.remove(senderId)
        lastAccessMs.remove(senderId)
    }

    @Synchronized
    fun size(): Int {
        return turnsBySender.values.sumOf { it.size }
    }
}
