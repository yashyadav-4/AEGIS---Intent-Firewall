package com.intentfirewall

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

class NotificationStoreModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "NotificationStoreModule"

    @ReactMethod
    fun getStoredMessages(promise: Promise) {
        try {
            val raw = NotificationEventEmitter.getCapturedRawJson(reactApplicationContext)
            promise.resolve(raw)
        } catch (e: Exception) {
            promise.reject("STORE_READ_ERROR", e)
        }
    }

    @ReactMethod
    fun clearStoredMessages(promise: Promise) {
        try {
            NotificationEventEmitter.clearCaptured(reactApplicationContext)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("STORE_CLEAR_ERROR", e)
        }
    }
}
