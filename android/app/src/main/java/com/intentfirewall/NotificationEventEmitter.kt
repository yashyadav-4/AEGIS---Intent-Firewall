package com.intentfirewall

import android.content.Context
import com.facebook.react.ReactApplication
import com.facebook.react.bridge.Arguments
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONArray
import org.json.JSONObject

object NotificationEventEmitter {
    private const val PREF_NAME = "intentfirewall_event_buffer"
    private const val KEY_EVENTS = "buffered_events"
    private const val MAX_BUFFER = 300

    fun sendNotification(
        context: Context,
        appName: String,
        title: String,
        text: String,
        packageName: String,
        flagged: Boolean,
        matchedCategory: String,
        conversationContext: String,
        confidence: Float = 0.0f,
        sender: String = title,
        appSource: String = appName,
        captureMethod: String = "notification",
        eventTimestamp: Long = System.currentTimeMillis(),
    ) {
        try {
            val emittedAt = if (eventTimestamp > 0L) eventTimestamp else System.currentTimeMillis()

            // Buffer first so event survives background/paused JS runtimes.
            bufferEvent(
                context = context,
                appName = appName,
                title = title,
                text = text,
                packageName = packageName,
                flagged = flagged,
                matchedCategory = matchedCategory,
                conversationContext = conversationContext,
                confidence = confidence,
                emittedAt = emittedAt,
                sender = sender,
                appSource = appSource,
                captureMethod = captureMethod,
            )

            val reactApplication = context.applicationContext as ReactApplication
            val reactHost = reactApplication.reactHost
            val reactContext = reactHost?.currentReactContext

            if (reactContext == null) {
                return
            }

            val params = Arguments.createMap().apply {
                putString("appName", appName)
                putString("title", title)
                putString("text", text)
                putString("packageName", packageName)
                putBoolean("flagged", flagged)
                putString("matchedCategory", matchedCategory)
                putString("context", conversationContext)
                putDouble("confidence", confidence.toDouble())
                putDouble("timestamp", emittedAt.toDouble())
                putString("sender", sender)
                putString("appSource", appSource)
                putString("captureMethod", captureMethod)
            }

            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("onNotification", params)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun drainBufferedEvents(context: Context): JSONArray {
        synchronized(this) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_EVENTS, "[]") ?: "[]"
            prefs.edit().putString(KEY_EVENTS, "[]").apply()
            return try {
                JSONArray(raw)
            } catch (_: Exception) {
                JSONArray()
            }
        }
    }

    private fun bufferEvent(
        context: Context,
        appName: String,
        title: String,
        text: String,
        packageName: String,
        flagged: Boolean,
        matchedCategory: String,
        conversationContext: String,
        confidence: Float,
        emittedAt: Long,
        sender: String,
        appSource: String,
        captureMethod: String,
    ) {
        synchronized(this) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val currentRaw = prefs.getString(KEY_EVENTS, "[]") ?: "[]"
            val current = try {
                JSONArray(currentRaw)
            } catch (_: Exception) {
                JSONArray()
            }

            val event = JSONObject().apply {
                put("appName", appName)
                put("title", title)
                put("text", text)
                put("packageName", packageName)
                put("flagged", flagged)
                put("matchedCategory", matchedCategory)
                put("context", conversationContext)
                put("confidence", confidence.toDouble())
                put("timestamp", emittedAt)
                put("sender", sender)
                put("appSource", appSource)
                put("captureMethod", captureMethod)
            }

            val updated = JSONArray().apply {
                put(event)
                for (i in 0 until minOf(current.length(), MAX_BUFFER - 1)) {
                    put(current.getJSONObject(i))
                }
            }

            prefs.edit().putString(KEY_EVENTS, updated.toString()).apply()
        }
    }
}