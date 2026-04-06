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
}
