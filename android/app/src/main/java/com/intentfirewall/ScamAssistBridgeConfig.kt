package com.intentfirewall

import android.content.Context

class ScamAssistBridgeConfig(context: Context) {
    data class Settings(
        val enabled: Boolean,
        val endpoint: String,
        val authToken: String?
    )

    companion object {
        private const val PREF_NAME = "aegis_bridge"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_AUTH_TOKEN = "auth_token"
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun load(): Settings {
        val endpoint = prefs.getString(KEY_ENDPOINT, "")?.trim().orEmpty()
        val token = prefs.getString(KEY_AUTH_TOKEN, "")?.trim().orEmpty()
        return Settings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            endpoint = endpoint,
            authToken = token.ifBlank { null }
        )
    }

    fun save(settings: Settings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_ENDPOINT, settings.endpoint.trim())
            .putString(KEY_AUTH_TOKEN, settings.authToken?.trim().orEmpty())
            .apply()
    }
}
