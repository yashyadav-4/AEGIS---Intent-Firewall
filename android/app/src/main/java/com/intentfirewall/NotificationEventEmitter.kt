package com.intentfirewall

import android.content.Context
import com.facebook.react.ReactApplication
import com.facebook.react.bridge.Arguments
import com.facebook.react.modules.core.DeviceEventManagerModule

object NotificationEventEmitter {

    fun sendNotification(
        context: Context,
        appName: String,
        title: String,
        text: String,
        packageName: String,
        flagged: Boolean,
        matchedCategory: String,
        context: String
    ) {
        try {
            val reactApplication = context.applicationContext as ReactApplication
            val reactHost = reactApplication.reactHost
            val reactContext = reactHost?.currentReactContext ?: return

            val params = Arguments.createMap().apply {
                putString("appName", appName)
                putString("title", title)
                putString("text", text)
                putString("packageName", packageName)
                putBoolean("flagged", flagged)
                putString("matchedCategory", matchedCategory)
                putString("context", context)
            }

            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("onNotification", params)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}