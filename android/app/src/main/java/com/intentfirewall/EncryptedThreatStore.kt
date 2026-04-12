package com.intentfirewall

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class EncryptedThreatStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "threat_history_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveThreatRecord(threatId: String, details: String) {
        // Strip PII before saving to ensure maximum privacy
        val sanitizedDetails = PIIStripper.stripPII(details)
        sharedPreferences.edit().putString(threatId, sanitizedDetails).apply()
    }

    fun getThreatRecord(threatId: String): String? {
        return sharedPreferences.getString(threatId, null)
    }

    fun clearAllThreats() {
        sharedPreferences.edit().clear().apply()
    }
}
