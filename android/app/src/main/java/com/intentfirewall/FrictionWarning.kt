package com.intentfirewall

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.telecom.TelecomManager
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

class FrictionWarningActivity : Activity() {

    private lateinit var proceedButton: TextView
    private lateinit var safeButton: Button
    private var countdownTimer: CountDownTimer? = null

    companion object {
        const val EXTRA_WARNING_MESSAGE = "warning_message"
        const val EXTRA_CONFIDENCE_SCORE = "confidence_score"
        const val WARNING_REASON = "warning_reason"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Full screen blocking
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION.SDK_INT) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }

        val reason = intent.getStringExtra(WARNING_REASON) ?: "Potential scam detected."
        val confidence = intent.getFloatExtra(EXTRA_CONFIDENCE_SCORE, 0.9f)

        setContentView(createUI(reason, confidence))

        // Vibrate to alert user
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(500)
        }

        startCountdown()
    }

    private fun createUI(reason: String, confidence: Float): View {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#B3261E")) // Strong danger red
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(64, 120, 64, 64)
        }

        // Scrollable content area
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }
        
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Warning Icon or Title
        val titleText = TextView(this).apply {
            text = "⚠️ DANGER ⚠️"
            textSize = 36f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        val subtitleText = TextView(this).apply {
            text = "Suspected Fraudulent Call"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        
        val confPercent = (confidence * 100).toInt()
        val descText = TextView(this).apply {
            text = "AI has detected highly suspicious patterns ($confPercent% confidence).\n\nDetails: $reason\n\nScammers often use fake urgency or impersonate trusted entities."
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, 64)
        }

        contentLayout.addView(titleText)
        contentLayout.addView(subtitleText)
        contentLayout.addView(descText)
        scrollView.addView(contentLayout)

        // Action Buttons Layout
        val actionsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 32, 0, 32)
            }
            gravity = Gravity.CENTER
        }

        // Safe Button (Prominent) - Dark pattern prevention: Safe action is massive and primary
        safeButton = Button(this).apply {
            text = "BLOCK & HANG UP"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.parseColor("#B3261E"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                180
            ).apply {
                setMargins(0, 0, 0, 48)
            }
            setOnClickListener {
                terminateCallAndFinish()
            }
        }

        // Proceed Button (Disabled, small, high friction text) - Dark pattern prevention: Requires waiting, unappealing text
        proceedButton = TextView(this).apply {
            text = "I accept the risks, proceed (5s)..."
            textSize = 14f
            setTextColor(Color.parseColor("#FFCDD2")) // Washed out color
            gravity = Gravity.CENTER
            isClickable = false
            alpha = 0.5f // Semi-transparent while disabled
            setPadding(32, 32, 32, 32)
            setOnClickListener {
                proceedAndFinish()
            }
        }

        actionsLayout.addView(safeButton)
        actionsLayout.addView(proceedButton)

        rootLayout.addView(scrollView)
        rootLayout.addView(actionsLayout)

        return rootLayout
    }

    private fun startCountdown() {
        countdownTimer = object : CountDownTimer(5000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = (millisUntilFinished / 1000) + 1
                proceedButton.text = "I accept the risks, proceed (${secondsLeft}s)..."
            }

            override fun onFinish() {
                proceedButton.text = "Ignore warning and proceed"
                proceedButton.isClickable = true
                proceedButton.alpha = 1.0f
                proceedButton.setTextColor(Color.WHITE)
            }
        }.start()
    }

    private fun terminateCallAndFinish() {
        countdownTimer?.cancel()
        // Notify React Native or broadast receiver that call was blocked
        val intent = Intent("com.aegis.ACTION_CALL_BLOCKED")
        sendBroadcast(intent)
        finish()
    }

    private fun proceedAndFinish() {
        countdownTimer?.cancel()
        // Notify React/Native that user bypassed
        val intent = Intent("com.aegis.ACTION_WARNING_BYPASSED")
        sendBroadcast(intent)
        finish()
    }


    private fun endActiveCall() {
        Log.i("IntentFirewall", "Executing AEGIS BLOCK & HANGUP Command...")
        try {
            val telecomManager = getSystemService(android.content.Context.TELECOM_SERVICE) as TelecomManager
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    telecomManager.endCall()
                    Log.i("IntentFirewall", "Call terminated successfully by Aegis.")
                }
            } else {
                Log.e("IntentFirewall", "Missing ANSWER_PHONE_CALLS permission to hang up.")
            }
        } catch (e: Exception) {
            Log.e("IntentFirewall", "Error executing endCall: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownTimer?.cancel()
    }

    override fun onBackPressed() {
        // Prevent physical back button from bypassing easily
        // They must make an explicit choice
    }
}
