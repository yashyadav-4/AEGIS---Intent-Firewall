package com.intentfirewall

import java.util.concurrent.ConcurrentHashMap

object ContextBuffer {
    private const val MAX_TURNS = 8
    private val turnsByPackage = ConcurrentHashMap<String, MutableList<String>>()

    fun addTurn(packageName: String, speaker: String, message: String) {
        val normalized = message.trim()
        if (normalized.isEmpty()) return

        val turns = turnsByPackage.getOrPut(packageName) { mutableListOf() }
        synchronized(turns) {
            turns.add("$speaker $normalized")
            if (turns.size > MAX_TURNS) {
                turns.removeAt(0)
            }
        }
    }

    fun getContext(packageName: String): String {
        val turns = turnsByPackage[packageName] ?: return ""
        synchronized(turns) {
            return turns.joinToString(separator = "\n")
        }
    }
}