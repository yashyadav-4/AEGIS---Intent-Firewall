package com.intentfirewall

object SpamNumberCache {

    private val knownSpamPrefixes = setOf(
        "+18005",
        "+18006",
        "+18007",
        "+18008",
        "+18009",
    )

    private val knownSpamNumbers = mutableSetOf<String>()

    fun isKnownSpam(number: String): Boolean {
        if (number == "unknown") return false
        if (number in knownSpamNumbers) return true
        return knownSpamPrefixes.any { number.startsWith(it) }
    }

    fun addSpamNumber(number: String) {
        knownSpamNumbers.add(number)
    }

    fun loadFromAsset(numbers: List<String>) {
        knownSpamNumbers.addAll(numbers)
    }
}
