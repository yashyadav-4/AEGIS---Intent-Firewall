package com.intentfirewall

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class OemWhitelistGuide : Activity() {
    companion object {
        private const val TAG = "SCAM_OemWhitelistGuide"
        private const val PREFS = "scam_oem_guide"
        const val KEY_SKIP_UNTIL_MS = "skip_until_ms"
        private const val SKIP_INTERVAL_MS = 24 * 60 * 60 * 1000L

        /**
         * Returns true when guide can be shown again.
         */
        fun shouldPrompt(context: Context): Boolean {
            val until = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_SKIP_UNTIL_MS, 0L)
            return System.currentTimeMillis() >= until
        }
    }

    private val manufacturer = Build.MANUFACTURER.lowercase()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBatteryOptimizationExemption()
        requestOverlayPermission()
        setContentView(buildContent())
    }

    private fun buildContent(): ScrollView {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val title = TextView(this).apply {
            text = getString(R.string.oem_guide_title)
            textSize = 20f
        }

        val instruction = TextView(this).apply {
            text = getInstructionsForOem()
            textSize = 15f
        }

        val screenshotPlaceholder = TextView(this).apply {
            text = getString(R.string.oem_guide_screenshot_placeholder, Build.MANUFACTURER)
            textSize = 14f
            setPadding(0, 24, 0, 24)
        }

        val openSettingsButton = Button(this).apply {
            text = getString(R.string.oem_guide_open_settings)
            setOnClickListener { launchOemSettings() }
        }

        val doneButton = Button(this).apply {
            text = getString(R.string.oem_guide_done)
            setOnClickListener {
                ServiceHealthMonitor.runImmediateCheck(this@OemWhitelistGuide)
                if (ServiceHealthMonitor.currentState() == ServiceHealthMonitor.HealthState.HEALTHY) {
                    finish()
                }
            }
        }

        val skipButton = Button(this).apply {
            text = getString(R.string.oem_guide_skip)
            setOnClickListener {
                val until = System.currentTimeMillis() + SKIP_INTERVAL_MS
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_SKIP_UNTIL_MS, until)
                    .apply()
                finish()
            }
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(doneButton)
            addView(skipButton)
        }

        container.addView(title)
        container.addView(instruction)
        container.addView(screenshotPlaceholder)
        container.addView(openSettingsButton)
        container.addView(buttonRow)

        return ScrollView(this).apply { addView(container) }
    }

    private fun getInstructionsForOem(): String {
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> getString(R.string.oem_steps_xiaomi)
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> getString(R.string.oem_steps_oppo)
            manufacturer.contains("vivo") -> getString(R.string.oem_steps_vivo)
            manufacturer.contains("samsung") -> getString(R.string.oem_steps_samsung)
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> getString(R.string.oem_steps_huawei)
            manufacturer.contains("oneplus") -> getString(R.string.oem_steps_oneplus)
            else -> getString(R.string.oem_steps_default)
        }
    }

    private fun launchOemSettings() {
        val intents = when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> listOf(
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                Intent("miui.intent.action.OP_AUTO_START").setPackage("com.miui.securitycenter"),
                Intent(Settings.ACTION_SETTINGS),
            )
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> listOf(
                packageIntent("com.coloros.oppoguardelf"),
                packageIntent("com.oplus.security"),
                Intent(Settings.ACTION_SETTINGS),
            )
            manufacturer.contains("vivo") -> listOf(
                packageIntent("com.vivo.permissionmanager"),
                Intent(Settings.ACTION_SETTINGS),
            )
            manufacturer.contains("samsung") -> listOf(
                packageIntent("com.samsung.android.lool"),
                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
                Intent(Settings.ACTION_SETTINGS),
            )
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> listOf(
                packageIntent("com.huawei.systemmanager"),
                Intent(Settings.ACTION_SETTINGS),
            )
            manufacturer.contains("oneplus") -> listOf(
                packageIntent("com.oneplus.security"),
                Intent(Settings.ACTION_SETTINGS),
            )
            else -> listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                },
                Intent(Settings.ACTION_SETTINGS),
            )
        }

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (e: Exception) {
                Log.w(TAG, "Failed intent ${intent.action}", e)
            }
        }
    }

    private fun packageIntent(pkg: String): Intent {
        return packageManager.getLaunchIntentForPackage(pkg) ?: Intent(Settings.ACTION_SETTINGS)
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    },
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Battery optimization request failed", e)
        }
    }

    private fun requestOverlayPermission() {
        try {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    },
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Overlay permission request failed", e)
        }
    }
}
