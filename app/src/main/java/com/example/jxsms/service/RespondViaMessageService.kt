package com.example.jxsms.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class RespondViaMessageService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.w("RespondViaMessage", "Rejected external respond-via-message request; sending unsupported")
        stopSelf(startId)
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? {
        Log.w("RespondViaMessage", "Rejected external respond-via-message request; sending unsupported")
        stopSelf()
        return null
    }
}
