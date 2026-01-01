package com.smash.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives BOOT_COMPLETED broadcast to auto-start SmashService after device reboot.
 * 
 * Starting foreground services from BOOT_COMPLETED is explicitly exempted from
 * Android 12+ background start restrictions.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        
        Log.i("smash", "Boot completed, starting SmashService")
        
        try {
            // Start foreground service immediately - this is allowed from BOOT_COMPLETED
            SmashService.start(context.applicationContext, SmashService.TRIGGER_BOOT)
        } catch (e: Exception) {
            Log.e("smash", "Failed to start SmashService from boot: ${e.message}", e)
        }
    }
}
