package com.intentfirewall

import android.app.Activity
import android.os.Bundle

class MockSmsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Placeholder role target for SMS default-app capability requests.
        finish()
    }
}
