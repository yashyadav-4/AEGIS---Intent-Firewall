package com.intentfirewall

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

object MessageConsistencyCoordinator {
    private const val DEDUP_WINDOW_MS = 2000L
    private val recentEvents = ConcurrentHashMap<String, Long>()
    private val recentSms = ConcurrentHashMap<String, Long>()

    fun shouldProcessNotification(packageName: String, text: String): Boolean {
        if (text.isEmpty()) return true 
        
        // Correlation with SMS: If this is an SMS app and we just got a matching SMS broadcast
        if (isSmsApp(packageName)) {
            val smsKey = text.trim().lowercase()
            val lastSmsTime = recentSms[smsKey] ?: 0L
            if (System.currentTimeMillis() - lastSmsTime < DEDUP_WINDOW_MS) {
                Log.d("IntentFirewall|Coordinator", "Skipping redundant SMS notification: already captured via Broadcast")
                return false
            }
        }

        // General deduplication
        val eventKey = "${packageName}_${text.trim().lowercase()}"
        val lastEventTime = recentEvents[eventKey] ?: 0L
        if (System.currentTimeMillis() - lastEventTime < DEDUP_WINDOW_MS) {
            Log.d("IntentFirewall|Coordinator", "Skipping duplicate notification: $eventKey")
            return false
        }
        
        recentEvents[eventKey] = System.currentTimeMillis()
        return true
    }

    fun shouldProcessSms(sender: String, body: String): Boolean {
        val smsKey = body.trim().lowercase()
        val dedupKey = "${sender}_$smsKey"
        
        val lastEventTime = recentEvents[dedupKey] ?: 0L
        if (System.currentTimeMillis() - lastEventTime < DEDUP_WINDOW_MS) {
            Log.d("IntentFirewall|Coordinator", "Skipping duplicate SMS broadcast: $dedupKey")
            return false
        }
        
        recentEvents[dedupKey] = System.currentTimeMillis()
        recentSms[smsKey] = System.currentTimeMillis()
        return true
    }

    private fun isSmsApp(packageName: String): Boolean {
        return packageName in listOf(
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.truecaller"
        )
    }
}