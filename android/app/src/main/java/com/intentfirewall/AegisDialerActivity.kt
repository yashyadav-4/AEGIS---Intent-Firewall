package com.intentfirewall

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class AegisDialerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Forward dial intents into the main app entry so role qualification is satisfied.
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            action = intent?.action
            data = intent?.data
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(launchIntent)
        finish()
    }
}
