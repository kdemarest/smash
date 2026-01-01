package com.smash.app

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button

/**
 * Full-screen alert shown when phone is unplugged.
 * Displays over lock screen to alert caretaker.
 * Auto-dismisses when power is reconnected.
 */
class UnpluggedAlertActivity : AppCompatActivity() {

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_POWER_CONNECTED) {
                SmashLogger.info("Power reconnected - dismissing alert")
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Allow showing over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(KeyguardManager::class.java)
            keyguardManager?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        // Keep screen on while this activity is showing
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_unplugged_alert)

        // Dismiss button
        findViewById<Button>(R.id.dismissButton).setOnClickListener {
            finish()
        }

        // Listen for power reconnection
        registerReceiver(powerReceiver, IntentFilter(Intent.ACTION_POWER_CONNECTED))

        SmashLogger.info("UnpluggedAlertActivity shown")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(powerReceiver)
        } catch (e: Exception) {
            // Receiver wasn't registered
        }
        SmashLogger.verbose("UnpluggedAlertActivity dismissed")
    }
}
