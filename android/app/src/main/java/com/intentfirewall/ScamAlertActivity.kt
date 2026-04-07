package com.intentfirewall

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

class ScamAlertActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appName = intent.getStringExtra("appName") ?: "Unknown App"
        val message = intent.getStringExtra("message") ?: "Potential scam detected"
        val category = intent.getStringExtra("category") ?: "SUSPICIOUS"
        val confidence = intent.getFloatExtra("confidence", 0.85f)
        val notificationId = intent.getIntExtra(ScamAlertNotifier.EXTRA_NOTIFICATION_ID, Int.MIN_VALUE)

        if (notificationId != Int.MIN_VALUE) {
            NotificationManagerCompat.from(this).cancel(notificationId)
        }

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#E63946"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 96, 48, 64)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val title = TextView(this).apply {
            text = "SCAM DETECTED"
            setTextColor(Color.WHITE)
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        val sub = TextView(this).apply {
            text = "Detected in $appName"
            setTextColor(Color.parseColor("#FFF0F0"))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 14, 0, 10)
        }

        val badge = TextView(this).apply {
            text = category.uppercase()
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#B22234"))
            setPadding(24, 10, 24, 10)
            gravity = Gravity.CENTER
        }

        val messageView = TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(0, 32, 0, 22)
            gravity = Gravity.CENTER
        }

        val confidenceView = TextView(this).apply {
            val pct = (confidence * 100).toInt().coerceIn(0, 99)
            text = "Confidence: $pct%"
            setTextColor(Color.parseColor("#FFEAEA"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        }

        val discard = Button(this).apply {
            text = "Discard Threat"
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.parseColor("#E63946"))
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener {
                finish()
            }
        }

        val openApp = Button(this).apply {
            text = "Open Intent Firewall"
            setBackgroundColor(Color.parseColor("#B22234"))
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 16, 0, 16)
            setOnClickListener {
                val launchIntent = Intent(this@ScamAlertActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(launchIntent)
                finish()
            }
        }

        val dismiss = Button(this).apply {
            text = "Dismiss"
            setBackgroundColor(Color.parseColor("#7A1F2A"))
            setTextColor(Color.WHITE)
            setOnClickListener { finish() }
        }

        val buttonParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = 16
        }

        container.addView(title)
        container.addView(sub)
        container.addView(badge)
        container.addView(messageView)
        container.addView(confidenceView)
        container.addView(discard, buttonParams)
        container.addView(openApp, buttonParams)
        container.addView(dismiss, buttonParams)

        root.addView(container)
        setContentView(root)
    }
}
