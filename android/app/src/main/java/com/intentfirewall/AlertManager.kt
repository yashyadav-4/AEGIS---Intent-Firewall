package com.intentfirewall

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log

/** Overlay-based alert manager with actions for dismiss, block sender, and report scam. */
object AlertManager {
    private const val TAG = "SCAM_AlertManager"
    private const val AUTO_DISMISS_MS = 30_000L

    private var currentView: View? = null
    private var windowManager: WindowManager? = null
    private var appContext: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoDismiss = Runnable { dismissOverlay() }

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun showAlert(pipelineResult: PipelineResult) {
        val ctx = appContext ?: return
        showAlert(
            context = ctx,
            appName = "Scam Protection",
            categoryDisplayName = pipelineResult.category ?: "Possible Scam",
            evidencePhrases = pipelineResult.evidence,
            confidenceScore = pipelineResult.confidenceScore,
            tier = pipelineResult.tier,
            tier3Used = pipelineResult.usedTier3,
            decision = pipelineResult.decision,
        )
    }

    /** Show scam overlay with evidence, confidence, and source tier. */
    fun showAlert(
        context: Context,
        appName: String,
        categoryDisplayName: String,
        evidencePhrases: List<String>,
        confidenceScore: Int,
        tier: Int,
        tier3Used: Boolean,
        decision: Decision = Decision.ALERT,
    ) {
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission missing; fallback notification path should handle alert")
            return
        }

        dismissOverlay()

        val overlay = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#DD1A1A1A"))
        }

        val title = TextView(context).apply {
            text = "⚠ Possible $categoryDisplayName"
            setTextColor(Color.WHITE)
            textSize = 18f
        }

        val evidence = TextView(context).apply {
            text = "Evidence: ${evidencePhrases.joinToString(", ").ifBlank { "Not provided" }}"
            setTextColor(Color.LTGRAY)
            textSize = 14f
        }

        val sub = TextView(context).apply {
            text = "Confidence: $confidenceScore% · Detected via Tier $tier"
            setTextColor(Color.parseColor("#FFD166"))
            textSize = 13f
        }

        val aiVerified = tier == 3 && decision == Decision.ALERT

        val pill = TextView(context).apply {
            text = if (aiVerified) "AI-verified" else "Pattern matched"
            setTextColor(Color.WHITE)
            setBackgroundColor(if (aiVerified) Color.parseColor("#2A9D8F") else Color.parseColor("#F4A261"))
            setPadding(14, 6, 14, 6)
        }

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val dismissBtn = Button(context).apply {
            text = "Dismiss"
            setOnClickListener {
                DecisionTraceLogger.recordUserAction("dismissed")
                dismissOverlay()
            }
        }

        val blockBtn = Button(context).apply {
            text = "Block Sender"
            setOnClickListener {
                DecisionTraceLogger.recordUserAction("blocked")
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                dismissOverlay()
            }
        }

        val reportBtn = Button(context).apply {
            text = "Report Scam"
            setOnClickListener {
                DecisionTraceLogger.recordUserAction("reported")
                dismissOverlay()
            }
        }

        actions.addView(dismissBtn)
        actions.addView(blockBtn)
        actions.addView(reportBtn)

        overlay.addView(title)
        overlay.addView(evidence)
        overlay.addView(sub)
        overlay.addView(pill)
        overlay.addView(actions)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
        }

        wm.addView(overlay, params)
        currentView = overlay
        windowManager = wm

        handler.removeCallbacks(autoDismiss)
        handler.postDelayed(autoDismiss, AUTO_DISMISS_MS)
    }

    /** Dismiss currently visible overlay and clear pending callbacks. */
    fun dismissOverlay() {
        handler.removeCallbacks(autoDismiss)
        val wm = windowManager
        val view = currentView
        if (wm != null && view != null) {
            try {
                wm.removeView(view)
            } catch (_: Exception) {
            }
        }
        currentView = null
        windowManager = null
    }
}
