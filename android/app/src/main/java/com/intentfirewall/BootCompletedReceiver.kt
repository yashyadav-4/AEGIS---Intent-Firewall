package com.intentfirewall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootCompletedReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AegisBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        try {
            val monitorIntent = Intent(context, AegisCallMonitor::class.java).apply {
                this.action = AegisCallMonitor.ACTION_PRIME
            }
            ContextCompat.startForegroundService(context, monitorIntent)
            Log.i(TAG, "Primed AegisCallMonitor after $action")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to prime AegisCallMonitor after $action: ${e.message}")
        }
    }
}