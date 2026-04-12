package com.intentfirewall

import com.facebook.react.bridge.*
import android.os.Build
import android.provider.Settings
import android.content.Intent
import android.content.ComponentName
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ActivityCompat
import android.content.pm.PackageManager
import android.Manifest
import android.util.Log
import androidx.core.content.ContextCompat

class AegisDetectionModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    private val bridgeConfig = ScamAssistBridgeConfig(reactContext)

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
        
        Log.d("IntentFirewall|TEST", "Message: $message")
        Log.d("IntentFirewall|TEST", "Source: $source")
        Log.d("IntentFirewall|TEST", "Result: detected=$detected, confidence=$confidence")
        
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
    fun checkCallScreeningPermission(promise: Promise) {
        var granted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = reactApplicationContext.getSystemService(android.app.role.RoleManager::class.java)
            granted = roleManager?.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING) == true
        }
        promise.resolve(granted)
    }

    @ReactMethod
    fun checkDefaultCallingAppPermission(promise: Promise) {
        try {
            val context = reactApplicationContext
            val telecomManager = context.getSystemService(android.telecom.TelecomManager::class.java)
            val roleManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.getSystemService(android.app.role.RoleManager::class.java)
            } else {
                null
            }

            val isDefaultDialer = telecomManager?.defaultDialerPackage == context.packageName ||
                roleManager?.isRoleHeld(android.app.role.RoleManager.ROLE_DIALER) == true

            promise.resolve(isDefaultDialer)
        } catch (e: Exception) {
            Log.e("AegisDetectionModule", "Failed to check default calling app state", e)
            promise.resolve(false)
        }
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
    fun requestDefaultCallingAppPermission(promise: Promise) {
        try {
            val activity = getCurrentActivity()
            if (activity != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val roleManager = reactApplicationContext.getSystemService(android.app.role.RoleManager::class.java)
                    if (roleManager != null && roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_DIALER) == false) {
                        val intent = roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER)
                        activity.startActivityForResult(intent, 2004)
                    }
                } else {
                    val intent = Intent(android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                    intent.putExtra(android.telecom.TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, reactApplicationContext.packageName)
                    activity.startActivityForResult(intent, 2004)
                }
            }
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e("AegisDetectionModule", "Failed to request default calling app role", e)
            promise.resolve(false)
        }
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
    fun requestDefaultRoles(promise: Promise) {
        val activity = getCurrentActivity()
        if (activity != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = reactApplicationContext.getSystemService(android.app.role.RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_DIALER) == false) {
                    val dialerIntent = roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER)
                    activity.startActivityForResult(dialerIntent, 2002)
                }
            } else {
                val intent = Intent(android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                intent.putExtra(android.telecom.TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, reactApplicationContext.packageName)
                activity.startActivityForResult(intent, 2002)
            }
        }
        promise.resolve(true)
    }

    @ReactMethod
    fun requestAccessibilityPermission(promise: Promise) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        reactApplicationContext.startActivity(intent)
        promise.resolve(true)
    }

    @ReactMethod
    fun getLatestCallTranscriptPath(promise: Promise) {
        try {
            val store = CallTranscriptStore(reactApplicationContext)
            promise.resolve(store.latestTranscriptFile()?.absolutePath)
        } catch (e: Exception) {
            Log.e("AegisDetectionModule", "Failed to resolve latest transcript path", e)
            promise.resolve(null)
        }
    }

    @ReactMethod
    fun getScamAssistBridgeConfig(promise: Promise) {
        try {
            val config = bridgeConfig.load()
            val result = Arguments.createMap().apply {
                putBoolean("enabled", config.enabled)
                putString("endpoint", config.endpoint)
                putString("authToken", config.authToken ?: "")
            }
            promise.resolve(result)
        } catch (e: Exception) {
            Log.e("AegisDetectionModule", "Failed to read Scam Assist bridge config", e)
            promise.resolve(Arguments.createMap().apply {
                putBoolean("enabled", false)
                putString("endpoint", "")
                putString("authToken", "")
            })
        }
    }

    @ReactMethod
    fun updateScamAssistBridgeConfig(config: ReadableMap, promise: Promise) {
        try {
            val enabled = if (config.hasKey("enabled")) config.getBoolean("enabled") else false
            val endpoint = if (config.hasKey("endpoint")) config.getString("endpoint") ?: "" else ""
            val authToken = if (config.hasKey("authToken")) config.getString("authToken") else null

            bridgeConfig.save(
                ScamAssistBridgeConfig.Settings(
                    enabled = enabled,
                    endpoint = endpoint,
                    authToken = authToken
                )
            )
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e("AegisDetectionModule", "Failed to update Scam Assist bridge config", e)
            promise.resolve(false)
        }
    }
}