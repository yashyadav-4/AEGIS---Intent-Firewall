package com.intentfirewall

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

object Tier2ModelManager {
    private const val TAG = "IntentFirewall|Tier2Mgr"
    private const val UNLOAD_AFTER_IDLE_MS = 5 * 60 * 1000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var model: Tier2Classifier? = null
    private var lastUsedAt: Long = 0L

    private val unloadRunnable = Runnable {
        synchronized(this) {
            val idleFor = System.currentTimeMillis() - lastUsedAt
            if (idleFor >= UNLOAD_AFTER_IDLE_MS) {
                model?.close()
                model = null
                Log.i(TAG, "Tier2 model unloaded after ${idleFor}ms idle")
            }
        }
    }

    fun analyze(context: Context, input: Tier2Input): Tier2Result {
        synchronized(this) {
            if (isMemoryPressureHigh()) {
                model?.close()
                model = null
                Log.w(TAG, "Memory pressure detected, unloading Tier2 before inference")
            }

            if (model == null) {
                model = Tier2Classifier(context.applicationContext)
                Log.i(TAG, "Tier2 model lazy-loaded")
            }

            lastUsedAt = System.currentTimeMillis()
            mainHandler.removeCallbacks(unloadRunnable)
            mainHandler.postDelayed(unloadRunnable, UNLOAD_AFTER_IDLE_MS)

            val start = System.currentTimeMillis()
            val result = model!!.analyze(input)
            val elapsed = System.currentTimeMillis() - start
            Log.i(TAG, "Tier2 inference latency=${elapsed}ms")

            if (isMemoryPressureHigh()) {
                model?.close()
                model = null
                Log.w(TAG, "Memory pressure after inference, unloading Tier2")
            }

            return result
        }
    }

    fun forceUnload() {
        synchronized(this) {
            mainHandler.removeCallbacks(unloadRunnable)
            model?.close()
            model = null
            Log.i(TAG, "Tier2 model force-unloaded")
        }
    }

    private fun isMemoryPressureHigh(): Boolean {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val max = runtime.maxMemory().coerceAtLeast(1L)
        val usageRatio = used.toDouble() / max.toDouble()
        return usageRatio >= 0.8
    }
}