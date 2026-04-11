package com.intentfirewall

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        finish()
    }
}
