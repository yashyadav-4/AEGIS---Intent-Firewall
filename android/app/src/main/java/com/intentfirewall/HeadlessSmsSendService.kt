package com.intentfirewall

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("AegisSms", "Headless SMS send service invoked")
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
