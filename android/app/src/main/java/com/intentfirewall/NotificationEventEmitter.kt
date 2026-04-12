package com.intentfirewall

import android.content.Context
import android.os.Bundle
import android.util.Log
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
        tierUsed: String = "tier1",
        tier1Score: Float = 0.0f,
        tier1Decision: String = "ALLOW",
        tier1Category: String = "NONE",
        tier3Reason: String = "",
        tier3Model: String = "",
        tier3KeyIndex: Int = 0,
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
                tierUsed = tierUsed,
                tier1Score = tier1Score,
                tier1Decision = tier1Decision,
                tier1Category = tier1Category,
                tier3Reason = tier3Reason,
                tier3Model = tier3Model,
                tier3KeyIndex = tier3KeyIndex,
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
                putString("tierUsed", tierUsed)
                putDouble("tier1Score", tier1Score.toDouble())
                putString("tier1Decision", tier1Decision)
                putString("tier1Category", tier1Category)
                putString("tier3Reason", tier3Reason)
                putString("tier3Model", tier3Model)
                putInt("tier3KeyIndex", tier3KeyIndex)
            }

            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("onNotification", params)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun sendCallEvent(context: Context, data: Bundle) {
        try {
            val emittedAt = System.currentTimeMillis()
            val type = data.getString("type", "call_event")
            val number = data.getString("number", "unknown")
            val riskScore = data.getInt("riskScore", 0).coerceIn(0, 100)
            val reason = data.getString("reason", "")
            val captureMethod = data.getString("captureMethod", "call")
            val tierUsed = data.getString("tierUsed", "call")
            val message = data.getString("message", "")

            bufferEvent(
                context = context,
                appName = "Phone Call",
                title = "Call Risk Alert",
                text = if (reason.isNotBlank()) reason else message,
                packageName = "phone",
                flagged = riskScore >= 55,
                matchedCategory = type,
                conversationContext = "call:$number",
                confidence = riskScore / 100.0f,
                emittedAt = emittedAt,
                sender = number,
                appSource = "Phone Call",
                captureMethod = captureMethod,
                tierUsed = tierUsed,
                tier1Score = 0.0f,
                tier1Decision = "ALLOW",
                tier1Category = "NONE",
                tier3Reason = reason,
                tier3Model = BuildConfig.GEMINI_VOICE_MODEL,
                tier3KeyIndex = 0,
            )

            val reactApplication = context.applicationContext as ReactApplication
            val reactHost = reactApplication.reactHost
            val reactContext = reactHost?.currentReactContext ?: return

            val params = Arguments.createMap().apply {
                putString("source", "call")
                putString("type", type)
                putString("number", number)
                putInt("riskScore", riskScore)
                putString("reason", reason)
                putString("captureMethod", captureMethod)
                putString("tierUsed", tierUsed)
                putString("message", message)
                putDouble("timestamp", emittedAt.toDouble())

                // Existing event shape fields for current JS listeners.
                putString("appName", "Phone Call")
                putString("title", "Call Risk Alert")
                putString("text", if (reason.isNotBlank()) reason else message)
                putString("packageName", "phone")
                putBoolean("flagged", riskScore >= 55)
                putString("matchedCategory", type)
                putString("context", "call:$number")
                putDouble("confidence", riskScore.toDouble())
                putString("sender", number)
                putString("appSource", "Phone Call")
                putDouble("tier1Score", 0.0)
                putString("tier1Decision", "ALLOW")
                putString("tier1Category", "NONE")
                putString("tier3Reason", reason)
                putString("tier3Model", BuildConfig.GEMINI_VOICE_MODEL)
                putInt("tier3KeyIndex", 0)
            }

            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("onNotification", params)
        } catch (e: Exception) {
            Log.e("NotificationEventEmitter", "Failed to emit call event", e)
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
        tierUsed: String,
        tier1Score: Float,
        tier1Decision: String,
        tier1Category: String,
        tier3Reason: String,
        tier3Model: String,
        tier3KeyIndex: Int,
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
                put("tierUsed", tierUsed)
                put("tier1Score", tier1Score.toDouble())
                put("tier1Decision", tier1Decision)
                put("tier1Category", tier1Category)
                put("tier3Reason", tier3Reason)
                put("tier3Model", tier3Model)
                put("tier3KeyIndex", tier3KeyIndex)
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