package com.intentfirewall

import android.content.Context
import android.util.Log
import com.facebook.react.ReactApplication
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.Arguments
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object NotificationEventEmitter {
    private const val PREFS_NAME = "intent_firewall_native"
    private const val KEY_PENDING = "pending_notifications"
    private const val KEY_CAPTURED = "captured_notifications"
    private const val MAX_PENDING = 50
    private const val MAX_CAPTURED = 1000
    private val queueLock = ReentrantLock()

    private data class NotificationPayload(
        val appName: String,
        val title: String,
        val text: String,
        val packageName: String,
        val flagged: Boolean,
        val matchedCategory: String,
        val conversationContext: String,
        val timestamp: Long
    )

    fun sendNotification(
        context: Context,
        appName: String,
        title: String,
        text: String,
        packageName: String,
        flagged: Boolean,
        matchedCategory: String,
        conversationContext: String
    ) {
        val payload = NotificationPayload(
            appName = appName,
            title = title,
            text = text,
            packageName = packageName,
            flagged = flagged,
            matchedCategory = matchedCategory,
            conversationContext = conversationContext,
            timestamp = System.currentTimeMillis()
        )

        persistCaptured(context, payload)

        try {
            val reactContext = getCurrentReactContext(context)
            if (reactContext == null) {
                persistPending(context, payload)
                return
            }

            emit(reactContext, payload)
            flushPending(context)
        } catch (e: Exception) {
            Log.e("IntentFirewall", "Failed to dispatch notification event", e)
            persistPending(context, payload)
        }
    }

    fun flushPending(context: Context) {
        val reactContext = getCurrentReactContext(context)
        if (reactContext == null) {
            return
        }
        val pending = loadPending(context)
        if (pending.isEmpty()) {
            return
        }

        pending.forEach { payload ->
            emit(reactContext, payload)
        }
        clearPending(context)
    }

    private fun getCurrentReactContext(context: Context): ReactContext? {
        val reactApplication = context.applicationContext as? ReactApplication ?: return null
        val reactHost = reactApplication.reactHost
        return reactHost?.currentReactContext
    }

    private fun emit(reactContext: ReactContext, payload: NotificationPayload) {
        val params = Arguments.createMap().apply {
            putString("appName", payload.appName)
            putString("title", payload.title)
            putString("text", payload.text)
            putString("packageName", payload.packageName)
            putBoolean("flagged", payload.flagged)
            putString("matchedCategory", payload.matchedCategory)
            putString("context", payload.conversationContext)
            putDouble("timestamp", payload.timestamp.toDouble())
        }

        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("onNotification", params)
    }

    private fun persistPending(context: Context, payload: NotificationPayload) {
        queueLock.withLock {
            val existing = loadPending(context).toMutableList()
            existing.add(payload)
            if (existing.size > MAX_PENDING) {
                val keepFrom = existing.size - MAX_PENDING
                val trimmed = existing.subList(keepFrom, existing.size)
                savePending(context, trimmed)
                return
            }
            savePending(context, existing)
        }
    }

    private fun loadPending(context: Context): List<NotificationPayload> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PENDING, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<NotificationPayload>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                out.add(
                    NotificationPayload(
                        appName = item.optString("appName", ""),
                        title = item.optString("title", ""),
                        text = item.optString("text", ""),
                        packageName = item.optString("packageName", ""),
                        flagged = item.optBoolean("flagged", false),
                        matchedCategory = item.optString("matchedCategory", ""),
                        conversationContext = item.optString("context", ""),
                        timestamp = item.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun savePending(context: Context, pending: List<NotificationPayload>) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        pending.forEach { payload ->
            val obj = JSONObject().apply {
                put("appName", payload.appName)
                put("title", payload.title)
                put("text", payload.text)
                put("packageName", payload.packageName)
                put("flagged", payload.flagged)
                put("matchedCategory", payload.matchedCategory)
                put("context", payload.conversationContext)
                put("timestamp", payload.timestamp)
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_PENDING, arr.toString()).apply()
    }

    private fun clearPending(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_PENDING).apply()
    }

    private fun persistCaptured(context: Context, payload: NotificationPayload) {
        queueLock.withLock {
            val existing = loadCaptured(context).toMutableList()
            existing.add(payload)
            val trimmed = if (existing.size > MAX_CAPTURED) {
                existing.subList(existing.size - MAX_CAPTURED, existing.size).toList()
            } else {
                existing
            }
            saveCaptured(context, trimmed)
        }
    }

    private fun loadCaptured(context: Context): List<NotificationPayload> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CAPTURED, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<NotificationPayload>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                out.add(
                    NotificationPayload(
                        appName = item.optString("appName", ""),
                        title = item.optString("title", ""),
                        text = item.optString("text", ""),
                        packageName = item.optString("packageName", ""),
                        flagged = item.optBoolean("flagged", false),
                        matchedCategory = item.optString("matchedCategory", ""),
                        conversationContext = item.optString("context", ""),
                        timestamp = item.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveCaptured(context: Context, captured: List<NotificationPayload>) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        captured.forEach { payload ->
            val obj = JSONObject().apply {
                put("appName", payload.appName)
                put("title", payload.title)
                put("text", payload.text)
                put("packageName", payload.packageName)
                put("flagged", payload.flagged)
                put("matchedCategory", payload.matchedCategory)
                put("context", payload.conversationContext)
                put("timestamp", payload.timestamp)
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_CAPTURED, arr.toString()).apply()
    }

    fun getCapturedRawJson(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CAPTURED, "[]") ?: "[]"
    }

    fun clearCaptured(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_CAPTURED).apply()
    }
}