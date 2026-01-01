package com.smash.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper

/**
 * Monitors power state and beeps periodically when unplugged.
 * Helps alert caretakers to plug the phone back in.
 */
class PowerMonitor(
    private val context: Context,
    private val onPowerStateChanged: ((isPluggedIn: Boolean) -> Unit)? = null
) {

    companion object {
        private const val DEFAULT_BEEP_INTERVAL_MS = 60_000L // 1 minute
        private const val BEEP_DURATION_MS = 150
        private const val BEEP_VOLUME = 80 // 0-100
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false
    var isPluggedIn = true
        private set
    private var toneGenerator: ToneGenerator? = null

    private val beepRunnable = object : Runnable {
        override fun run() {
            if (!isPluggedIn && isMonitoring) {
                playBeep()
                handler.postDelayed(this, DEFAULT_BEEP_INTERVAL_MS)
            }
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    SmashLogger.info("Power connected")
                    isPluggedIn = true
                    stopBeeping()
                    onPowerStateChanged?.invoke(true)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    SmashLogger.info("Power disconnected - will beep every ${DEFAULT_BEEP_INTERVAL_MS / 1000}s")
                    isPluggedIn = false
                    startBeeping()
                    showUnpluggedAlert()
                    onPowerStateChanged?.invoke(false)
                }
            }
        }
    }

    /**
     * Launch full-screen alert activity when unplugged.
     */
    private fun showUnpluggedAlert() {
        val alertIntent = Intent(context, UnpluggedAlertActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(alertIntent)
    }

    /**
     * Start monitoring power state.
     */
    fun start() {
        if (isMonitoring) return
        isMonitoring = true

        // Initialize tone generator
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, BEEP_VOLUME)
        } catch (e: Exception) {
            SmashLogger.error("Failed to create ToneGenerator: ${e.message}")
        }

        // Register for power state changes
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        context.registerReceiver(powerReceiver, filter)

        // Check initial power state
        isPluggedIn = isCurrentlyPluggedIn()
        SmashLogger.verbose("PowerMonitor started, plugged in: $isPluggedIn")

        // If already unplugged when we start, begin beeping
        if (!isPluggedIn) {
            SmashLogger.info("Phone is unplugged - will beep every ${DEFAULT_BEEP_INTERVAL_MS / 1000}s")
            startBeeping()
            onPowerStateChanged?.invoke(false)
        }
    }

    /**
     * Stop monitoring power state.
     */
    fun stop() {
        if (!isMonitoring) return
        isMonitoring = false

        stopBeeping()
        try {
            context.unregisterReceiver(powerReceiver)
        } catch (e: Exception) {
            // Receiver wasn't registered
        }

        toneGenerator?.release()
        toneGenerator = null

        SmashLogger.verbose("PowerMonitor stopped")
    }

    /**
     * Check if phone is currently plugged in.
     */
    private fun isCurrentlyPluggedIn(): Boolean {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        return plugged == BatteryManager.BATTERY_PLUGGED_AC ||
               plugged == BatteryManager.BATTERY_PLUGGED_USB ||
               plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
    }

    private fun startBeeping() {
        handler.removeCallbacks(beepRunnable)
        // Beep immediately, then every interval
        playBeep()
        handler.postDelayed(beepRunnable, DEFAULT_BEEP_INTERVAL_MS)
    }

    private fun stopBeeping() {
        handler.removeCallbacks(beepRunnable)
    }

    private fun playBeep() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, BEEP_DURATION_MS)
            SmashLogger.verbose("Power unplugged beep")
        } catch (e: Exception) {
            SmashLogger.error("Failed to play beep: ${e.message}")
        }
    }
}
