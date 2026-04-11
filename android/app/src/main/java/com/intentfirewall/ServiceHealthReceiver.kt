package com.intentfirewall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class ServiceHealthReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SCAM_ServiceHealthReceiver"
    }

    /**
     * Restarts required process-anchor services on boot/update/package restart signals.
     */
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        try {
            when (action) {
                Intent.ACTION_BOOT_COMPLETED -> startForegroundAnchor(context)
                Intent.ACTION_MY_PACKAGE_REPLACED -> {
                    restartAccessibilityAnchor(context)
                    startForegroundAnchor(context)
                }
                Intent.ACTION_PACKAGE_RESTARTED -> {
                    val data = intent?.data?.schemeSpecificPart.orEmpty()
                    if (data == context.packageName) {
                        restartAccessibilityAnchor(context)
                        startForegroundAnchor(context)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onReceive failed for $action", e)
        }
    }

    private fun restartAccessibilityAnchor(context: Context) {
        val stopIntent = Intent(context, ScamAccessibilityService::class.java)
        context.stopService(stopIntent)
    }

    private fun startForegroundAnchor(context: Context) {
        val intent = Intent(context, ScamForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
