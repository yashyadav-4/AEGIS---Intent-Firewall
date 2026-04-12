package com.intentfirewall

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView

class FloatingCallControlService : Service() {
    private var windowManager: WindowManager? = null
    private var bubbleView: ImageView? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        val image = ImageView(this).apply {
            setImageResource(android.R.drawable.presence_audio_online)
            setBackgroundColor(0x55000000)
            setPadding(18, 18, 18, 18)
            setOnClickListener {
                val armed = !CallProtectionPrefs.isArmed(this@FloatingCallControlService)
                CallProtectionPrefs.setArmed(this@FloatingCallControlService, armed)

                val intent = Intent(this@FloatingCallControlService, AegisCallMonitor::class.java).apply {
                    action = if (armed) AegisCallMonitor.ACTION_START else AegisCallMonitor.ACTION_STOP
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 30
            y = 250
        }

        windowManager?.addView(image, params)
        bubbleView = image
        CallProtectionPrefs.setFloatingEnabled(this, true)
    }

    override fun onDestroy() {
        bubbleView?.let { view ->
            windowManager?.removeView(view)
        }
        bubbleView = null
        windowManager = null
        CallProtectionPrefs.setFloatingEnabled(this, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
