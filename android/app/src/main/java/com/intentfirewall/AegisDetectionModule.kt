package com.intentfirewall

import com.facebook.react.bridge.*
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.provider.Settings
import android.content.Intent
import android.app.role.RoleManager
import android.content.ComponentName
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ActivityCompat
import android.content.pm.PackageManager
import android.Manifest
import android.util.Log
import androidx.core.content.ContextCompat
import android.telecom.TelecomManager
import org.json.JSONArray
import org.json.JSONObject

class AegisDetectionModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    override fun getName(): String = "NotificationService"

    @ReactMethod
    fun testDetection(message: String, source: String, promise: Promise) {
        val regexResult = RegexSentinel.analyze(message)
        val hinglishResult = RegexSentinel.detectHinglishScam(message)
        
        val detected = regexResult.flagged || hinglishResult.detected
        val confidence = maxOf(0.5f, hinglishResult.confidence)
        
        val categories = mutableListOf<String>()
        if (regexResult.matchedCategory != null) categories.add(regexResult.matchedCategory)
        categories.addAll(hinglishResult.categories)
        
        Log.d("IntentFirewall|TEST", "Message: \$message")
        Log.d("IntentFirewall|TEST", "Source: \$source")
        Log.d("IntentFirewall|TEST", "Result: detected=\$detected, confidence=\$confidence")
        
        val result = Arguments.createMap().apply {
            putBoolean("detected", detected)
            putDouble("confidence", confidence.toDouble())
            putArray("categories", Arguments.fromList(categories.distinct()))
        }
        
        promise.resolve(result)
    }

    @ReactMethod
    fun checkNotificationPermission(promise: Promise) {
        val context = reactApplicationContext
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == 
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        
        promise.resolve(granted)
    }

    @ReactMethod
    fun requestNotificationPermission(promise: Promise) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val activity = getCurrentActivity()
            if (activity != null) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
        promise.resolve(true)
    }

    @ReactMethod
    fun promptNotificationListenerSetup(promise: Promise) {
        val context = reactApplicationContext
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        promise.resolve(true)
    }

    @ReactMethod
    fun checkNotificationListenerEnabled(promise: Promise) {
        try {
            val context = reactApplicationContext
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: ""

            val componentName = ComponentName(context, NotificationService::class.java)
            val flattened = componentName.flattenToString()

            promise.resolve(enabledListeners.contains(flattened))
        } catch (e: Exception) {
            Log.e("AegisDetectionModule", "Failed to check notification listener state", e)
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun checkAccessibilityServiceEnabled(promise: Promise) {
        try {
            val context = reactApplicationContext
            val expectedClass = ScamAccessibilityService::class.java.name
            // Primary: secure settings parsing is usually most stable across OEM builds.
            val enabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) == 1

            if (!enabled) {
                promise.resolve(false)
                return
            }

            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            val fullName = ComponentName(context, ScamAccessibilityService::class.java)
                .flattenToString()
            val shortName = ComponentName(context, ScamAccessibilityService::class.java)
                .flattenToShortString()

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledServices)
            while (splitter.hasNext()) {
                val service = splitter.next()
                if (
                    service.equals(fullName, ignoreCase = true) ||
                    service.equals(shortName, ignoreCase = true)
                ) {
                    promise.resolve(true)
                    return
                }
            }

            // Secondary: active service list for live runtime validation.
            val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
            if (accessibilityManager != null) {
                val activeServices = accessibilityManager.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                )

                for (service in activeServices) {
                    val info = service.resolveInfo?.serviceInfo ?: continue
                    val serviceName = info.name
                    val matchesClass =
                        serviceName == expectedClass ||
                            serviceName == ".${ScamAccessibilityService::class.java.simpleName}"

                    if (info.packageName == context.packageName && matchesClass) {
                        promise.resolve(true)
                        return
                    }
                }
            }

            promise.resolve(false)
        } catch (e: Exception) {
            Log.e("AegisDetectionModule", "Failed to check accessibility service state", e)
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun promptAccessibilityServiceSetup(promise: Promise) {
        try {
            val context = reactApplicationContext
            val component = ComponentName(context, ScamAccessibilityService::class.java)

            val detailsIntent = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("android.intent.extra.COMPONENT_NAME", component.flattenToString())
                putExtra(":settings:fragment_args_key", component.flattenToString())
            }

            try {
                context.startActivity(detailsIntent)
            } catch (_: Exception) {
                val fallbackIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallbackIntent)
            }

            promise.resolve(true)
        } catch (e: Exception) {
            Log.e("AegisDetectionModule", "Failed to open accessibility settings", e)
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun checkCallScreeningPermission(promise: Promise) {
        var granted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = reactApplicationContext.getSystemService(android.app.role.RoleManager::class.java)
            granted = roleManager?.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING) == true
        }
        promise.resolve(granted)
    }

    @ReactMethod
    fun requestCallScreeningPermission(promise: Promise) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val activity = getCurrentActivity()
            if (activity != null) {
                val roleManager = reactApplicationContext.getSystemService(android.app.role.RoleManager::class.java)
                if (roleManager?.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING) == false) {
                    val intent = roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING)
                    activity.startActivityForResult(intent, 1002)
                }
            }
        }
        promise.resolve(true)
    }

    @ReactMethod
    fun checkAudioRecordingPermission(promise: Promise) {
        val granted = ContextCompat.checkSelfPermission(
            reactApplicationContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        promise.resolve(granted)
    }

    @ReactMethod
    fun requestAudioRecordingPermission(promise: Promise) {
        val activity = getCurrentActivity()
        if (activity != null) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                1003
            )
        }
        promise.resolve(true)
    }

    @ReactMethod
    fun checkPhoneStatePermission(promise: Promise) {
        val granted = ContextCompat.checkSelfPermission(
            reactApplicationContext,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        promise.resolve(granted)
    }

    @ReactMethod
    fun requestPhoneStatePermission(promise: Promise) {
        val activity = getCurrentActivity()
        if (activity != null) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.READ_PHONE_STATE),
                1004
            )
        }
        promise.resolve(true)
    }

    @ReactMethod
    fun startCallProtection(promise: Promise) {
        try {
            val context = reactApplicationContext
            val micGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val phoneStateGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED

            if (!micGranted || !phoneStateGranted) {
                promise.resolve(false)
                return
            }

            CallProtectionPrefs.setArmed(context, true)

            val intent = Intent(context, AegisCallMonitor::class.java).apply {
                action = AegisCallMonitor.ACTION_START
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            promise.resolve(true)
        } catch (se: SecurityException) {
            Log.e("AegisDetectionModule", "Failed to start call protection (security)", se)
            promise.resolve(false)
        } catch (e: Exception) {
            Log.e("AegisDetectionModule", "Failed to start call protection", e)
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun stopCallProtection(promise: Promise) {
        try {
            val context = reactApplicationContext
            CallProtectionPrefs.setArmed(context, false)
            val intent = Intent(context, AegisCallMonitor::class.java).apply {
                action = AegisCallMonitor.ACTION_STOP
            }
            context.startService(intent)
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e("AegisDetectionModule", "Failed to stop call protection", e)
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun isCallProtectionRunning(promise: Promise) {
        promise.resolve(AegisCallMonitor.isServiceRunning)
    }

    @ReactMethod
    fun isCallProtectionArmed(promise: Promise) {
        promise.resolve(CallProtectionPrefs.isArmed(reactApplicationContext))
    }

    @ReactMethod
    fun checkDefaultDialerEnabled(promise: Promise) {
        try {
            val context = reactApplicationContext
            val telecom = context.getSystemService(TelecomManager::class.java)
            val packageName = telecom?.defaultDialerPackage
            promise.resolve(packageName == context.packageName)
        } catch (e: Exception) {
            Log.e("AegisDetectionModule", "checkDefaultDialerEnabled failed", e)
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun requestDefaultDialer(promise: Promise) {
        try {
            val context = reactApplicationContext
            val activity = getCurrentActivity()
            if (activity == null) {
                promise.resolve(false)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(RoleManager::class.java)
                val isHeld = roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true
                if (!isHeld && roleManager != null) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                    activity.startActivityForResult(intent, 1005)
                    promise.resolve(true)
                    return
                }
                promise.resolve(true)
                return
            }

            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
            }
            activity.startActivity(intent)
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e("AegisDetectionModule", "requestDefaultDialer failed", e)
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun checkOverlayPermission(promise: Promise) {
        try {
            promise.resolve(Settings.canDrawOverlays(reactApplicationContext))
        } catch (e: Exception) {
            Log.e("AegisDetectionModule", "checkOverlayPermission failed", e)
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun requestOverlayPermission(promise: Promise) {
        try {
            val context = reactApplicationContext
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e("AegisDetectionModule", "requestOverlayPermission failed", e)
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun startFloatingCallButton(promise: Promise) {
        try {
            val context = reactApplicationContext
            if (!Settings.canDrawOverlays(context)) {
                promise.resolve(false)
                return
            }
            val intent = Intent(context, FloatingCallControlService::class.java)
            context.startService(intent)
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e("AegisDetectionModule", "startFloatingCallButton failed", e)
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun stopFloatingCallButton(promise: Promise) {
        try {
            val context = reactApplicationContext
            context.stopService(Intent(context, FloatingCallControlService::class.java))
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e("AegisDetectionModule", "stopFloatingCallButton failed", e)
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun getProtectionDiagnostics(promise: Promise) {
        try {
            val context = reactApplicationContext
            val snapshot = ServiceHealthMonitor.getSnapshot(context)
            val micGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val phoneStateGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
            val hasApiKey = BuildConfig.GEMINI_API_KEYS.isNotBlank() || BuildConfig.GEMINI_API_KEY.isNotBlank()
            val telecom = context.getSystemService(TelecomManager::class.java)
            val defaultDialer = telecom?.defaultDialerPackage == context.packageName
            val overlayGranted = Settings.canDrawOverlays(context)

            val out = Arguments.createMap().apply {
                putBoolean("callProtectionRunning", AegisCallMonitor.isServiceRunning)
                putBoolean("callProtectionArmed", CallProtectionPrefs.isArmed(context))
                putBoolean("callMonitorArmed", AegisCallMonitor.monitorArmed)
                putBoolean("inCallDetected", AegisCallMonitor.inCallDetected)
                putBoolean("audioCaptureRunning", AegisCallMonitor.audioCaptureRunning)
                putBoolean("defaultDialerEnabled", defaultDialer)
                putBoolean("overlayPermissionGranted", overlayGranted)
                putBoolean("floatingButtonEnabled", CallProtectionPrefs.isFloatingEnabled(context))
                putBoolean("micGranted", micGranted)
                putBoolean("phoneStateGranted", phoneStateGranted)
                putBoolean("notificationEnabled", snapshot.notificationEnabled)
                putBoolean("notificationConnected", snapshot.notificationConnected)
                putBoolean("accessibilityEnabled", snapshot.accessibilityEnabled)
                putBoolean("accessibilityConnected", snapshot.accessibilityConnected)
                putDouble("lastNotificationEventAt", snapshot.lastNotificationEventAt.toDouble())
                putDouble("lastAccessibilityEventAt", snapshot.lastAccessibilityEventAt.toDouble())
                putDouble("lastSmsFallbackEventAt", snapshot.lastSmsFallbackEventAt.toDouble())
                putString("lastCaptureStatus", AegisCallMonitor.lastCaptureStatus)
                putDouble("lastCaptureAtMs", AegisCallMonitor.lastCaptureAtMs.toDouble())
                putString("lastTier3Status", AegisCallMonitor.lastTier3Status)
                putDouble("lastTier3AtMs", AegisCallMonitor.lastTier3AtMs.toDouble())
                putBoolean("tier3ApiKeyConfigured", hasApiKey)
                putString("tier3TextModel", BuildConfig.GEMINI_MODEL)
                putString("tier3VoiceModel", BuildConfig.GEMINI_VOICE_MODEL)
            }

            promise.resolve(out)
        } catch (e: Exception) {
            Log.e("AegisDetectionModule", "getProtectionDiagnostics failed", e)
            promise.resolve(Arguments.createMap())
        }
    }

    @ReactMethod
    fun simulateCall(phoneNumber: String, isIncoming: Boolean, durationMs: Int, promise: Promise) {
        val result = Arguments.createMap().apply {
            putBoolean("deepfakeDetected", false)
            putDouble("deepfakeScore", 0.0)
            putBoolean("keywordDetected", false)
            putDouble("keywordScore", 0.0)
            putArray("keywords", Arguments.fromList(emptyList<String>()))
        }
        promise.resolve(result)
    }

    @ReactMethod
    fun drainBufferedNotifications(promise: Promise) {
        try {
            val events = NotificationEventEmitter.drainBufferedEvents(reactApplicationContext)
            val out = Arguments.createArray()

            for (i in 0 until events.length()) {
                val item = events.optJSONObject(i) ?: JSONObject()
                val map = Arguments.createMap().apply {
                    putString("appName", item.optString("appName", ""))
                    putString("title", item.optString("title", ""))
                    putString("text", item.optString("text", ""))
                    putString("packageName", item.optString("packageName", ""))
                    putBoolean("flagged", item.optBoolean("flagged", false))
                    putString("matchedCategory", item.optString("matchedCategory", ""))
                    putString("context", item.optString("context", ""))
                    putDouble("confidence", item.optDouble("confidence", 0.0))
                    putDouble("timestamp", item.optDouble("timestamp", 0.0))
                    putString("sender", item.optString("sender", ""))
                    putString("appSource", item.optString("appSource", ""))
                    putString("captureMethod", item.optString("captureMethod", "notification"))
                    putString("tierUsed", item.optString("tierUsed", "tier1"))
                    putDouble("tier1Score", item.optDouble("tier1Score", 0.0))
                    putString("tier1Decision", item.optString("tier1Decision", "ALLOW"))
                    putString("tier1Category", item.optString("tier1Category", "NONE"))
                    putString("tier3Reason", item.optString("tier3Reason", item.optString("geminiReason", "")))
                    putString("tier3Model", item.optString("tier3Model", item.optString("geminiModel", "")))
                    putInt("tier3KeyIndex", item.optInt("tier3KeyIndex", item.optInt("geminiKeyIndex", 0)))
                }
                out.pushMap(map)
            }

            promise.resolve(out)
        } catch (e: Exception) {
            Log.e("AegisDetectionModule", "Failed to drain buffered notifications", e)
            promise.resolve(Arguments.createArray())
        }
    }

    @ReactMethod
    fun drainBufferedNotificationsJson(promise: Promise) {
        try {
            val events = NotificationEventEmitter.drainBufferedEvents(reactApplicationContext)
            promise.resolve(events.toString())
        } catch (e: Exception) {
            Log.e("AegisDetectionModule", "Failed to drain buffered notifications JSON", e)
            promise.resolve(JSONArray().toString())
        }
    }
}
