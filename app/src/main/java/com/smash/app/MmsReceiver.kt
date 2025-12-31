package com.smash.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Required for default SMS app status.
 * MMS messages are received but not processed (per spec: no MMS forwarding).
 */
class MmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // MMS not supported - just acknowledge receipt
        // This receiver is required for default SMS app status
    }
}
