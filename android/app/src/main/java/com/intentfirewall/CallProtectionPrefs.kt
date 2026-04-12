package com.intentfirewall

import android.content.Context

object CallProtectionPrefs {
    private const val PREF_NAME = "intentfirewall_call_protection"
    private const val KEY_ARMED = "armed"
    private const val KEY_FLOATING = "floating_enabled"

    fun isArmed(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ARMED, false)
    }

    fun setArmed(context: Context, armed: Boolean) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ARMED, armed).apply()
    }

    fun isFloatingEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FLOATING, false)
    }

    fun setFloatingEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FLOATING, enabled).apply()
    }
}
