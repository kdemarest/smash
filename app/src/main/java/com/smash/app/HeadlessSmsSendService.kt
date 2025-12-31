package com.smash.app

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Required for default SMS app status.
 * Handles "respond via message" functionality (not implemented).
 */
class HeadlessSmsSendService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Not implementing "respond via message" - just stop self
        stopSelf()
        return START_NOT_STICKY
    }
}
