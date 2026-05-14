package com.smash.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper

/**
 * Monitors power state and battery level.
 * Alerts when unplugged AND battery is below BATTERY_ALERT_THRESHOLD.
 * Also checks periodically in case the phone was unplugged before the threshold was crossed.
 */
class PowerMonitor(
    private val context: Context,
    private val onPowerStateChanged: ((isPluggedIn: Boolean) -> Unit)? = null
) {

    companion object {
        const val ALERT_MESSAGE = "Plug in the phone!"
        private const val BATTERY_ALERT_THRESHOLD = 70
        private const val CHECK_INTERVAL_MS = 60_000L
    }

    private var isMonitoring = false
    var isPluggedIn = true
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val periodicCheckRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                checkAndAlert()
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    SmashLogger.info("Power connected")
                    isPluggedIn = true
                    if (AlertManager.isAlertActive(AlertManager.ALERT_POWER)) {
                        AlertManager.removeAlert(AlertManager.ALERT_POWER)
                        onPowerStateChanged?.invoke(true)
                    }
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    isPluggedIn = false
                    val battery = getBatteryLevel()
                    SmashLogger.info("Power disconnected, battery: $battery%")
                    checkAndAlert()
                }
            }
        }
    }

    /**
     * Start monitoring power state.
     */
    fun start() {
        if (isMonitoring) return
        isMonitoring = true

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        context.registerReceiver(powerReceiver, filter)

        isPluggedIn = isCurrentlyPluggedIn()
        SmashLogger.verbose("PowerMonitor started, plugged in: $isPluggedIn, battery: ${getBatteryLevel()}%")

        checkAndAlert()
        handler.postDelayed(periodicCheckRunnable, CHECK_INTERVAL_MS)
    }

    /**
     * Stop monitoring power state.
     */
    fun stop() {
        if (!isMonitoring) return
        isMonitoring = false

        handler.removeCallbacks(periodicCheckRunnable)
        AlertManager.removeAlert(AlertManager.ALERT_POWER)
        try {
            context.unregisterReceiver(powerReceiver)
        } catch (e: Exception) {
            // Receiver wasn't registered
        }

        SmashLogger.verbose("PowerMonitor stopped")
    }

    private fun checkAndAlert() {
        if (!isPluggedIn) {
            val battery = getBatteryLevel()
            if (battery < BATTERY_ALERT_THRESHOLD) {
                if (!AlertManager.isAlertActive(AlertManager.ALERT_POWER)) {
                    SmashLogger.info("Power alert: unplugged and battery at $battery%")
                    AlertManager.addAlert(AlertManager.ALERT_POWER, ALERT_MESSAGE)
                    onPowerStateChanged?.invoke(false)
                }
            } else {
                if (AlertManager.isAlertActive(AlertManager.ALERT_POWER)) {
                    SmashLogger.info("Power alert cleared: battery recovered to $battery% (still unplugged)")
                    AlertManager.removeAlert(AlertManager.ALERT_POWER)
                }
            }
        }
    }

    private fun getBatteryLevel(): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else 100
    }

    private fun isCurrentlyPluggedIn(): Boolean {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        return plugged == BatteryManager.BATTERY_PLUGGED_AC ||
               plugged == BatteryManager.BATTERY_PLUGGED_USB ||
               plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
    }
}
