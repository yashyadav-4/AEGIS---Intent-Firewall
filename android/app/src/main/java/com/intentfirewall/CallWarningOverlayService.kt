package com.intentfirewall

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

class CallWarningOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val number = intent?.getStringExtra("CALLER_NUMBER") ?: "Unknown"
        val reason = intent?.getStringExtra("RISK_REASON") ?: ""
        val score = intent?.getFloatExtra("RISK_SCORE", 0f) ?: 0f

        showOverlay(number, reason, score)
        return START_NOT_STICKY
    }

    private fun showOverlay(number: String, reason: String, score: Float) {
        if (!Settings.canDrawOverlays(this)) {
            Log.e("AegisOverlay", "SYSTEM_ALERT_WINDOW permission not granted")
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
        }

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_call_warning, null)

        overlayView?.apply {
            findViewById<TextView>(R.id.tv_number)?.text = "Incoming: $number"
            findViewById<TextView>(R.id.tv_risk)?.text = "Risk: ${(score * 100).toInt()}%"
            findViewById<TextView>(R.id.tv_reason)?.text = formatReason(reason)

            findViewById<Button>(R.id.btn_block)?.setOnClickListener {
                blockActiveCall()
                dismissOverlay()
            }
            findViewById<Button>(R.id.btn_answer)?.setOnClickListener {
                dismissOverlay()
            }
        }

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e("AegisOverlay", "Failed to add overlay: $e")
        }

        Handler(Looper.getMainLooper()).postDelayed({ dismissOverlay() }, 30000L)
    }

    private fun blockActiveCall() {
        val telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
        try {
            @Suppress("DEPRECATION")
            telecom.endCall()
        } catch (e: SecurityException) {
            Log.e(
                "AegisOverlay",
                "Cannot end call: ANSWER_PHONE_CALLS permission required"
            )
        }
    }

    private fun dismissOverlay() {
        try {
            overlayView?.let {
                windowManager?.removeView(it)
            }
            overlayView = null
        } catch (e: Exception) {
            Log.e("AegisOverlay", "Dismiss error: $e")
        }
        stopSelf()
    }

    private fun formatReason(reason: String): String {
        return reason.split("|").joinToString("\n") {
            when (it) {
                "STIR_FAILED" -> "Network verification failed"
                "WITHHELD_NUMBER" -> "Number hidden by caller"
                "HIGH_RISK_PREFIX" -> "Calling from high-risk region"
                "BULK_CALLER_PATTERN" -> "Bulk caller number pattern"
                else -> it
            }
        }
    }

    override fun onDestroy() {
        dismissOverlay()
        super.onDestroy()
    }
}