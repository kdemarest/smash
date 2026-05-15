package com.smash.app

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs

/**
 * Monitors available storage and alerts when low.
 * Uses AlertManager for unified alerts.
 */
class StorageMonitor(
    private val context: Context,
    private val onStorageStateChanged: ((isLow: Boolean) -> Unit)? = null
) {

    companion object {
        private const val CHECK_INTERVAL_MS = 60_000L * 15 // Check every 15 minutes
        private const val LOW_STORAGE_THRESHOLD_MB = 100L // Alert when below 100MB
        private const val ALERT_MESSAGE = "Clear storage space!"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false
    private var wasLow = false

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                checkStorage()
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Start monitoring storage.
     */
    fun start() {
        if (isMonitoring) return
        isMonitoring = true

        // Check immediately, then periodically
        checkStorage()
        handler.postDelayed(checkRunnable, CHECK_INTERVAL_MS)

        SmashLogger.verbose("StorageMonitor started")
    }

    /**
     * Stop monitoring storage.
     */
    fun stop() {
        if (!isMonitoring) return
        isMonitoring = false

        handler.removeCallbacks(checkRunnable)
        AlertManager.removeAlert(AlertManager.ALERT_STORAGE)

        SmashLogger.verbose("StorageMonitor stopped")
    }

    private fun checkStorage() {
        val availableMB = getAvailableStorageMB()
        val isLow = availableMB < LOW_STORAGE_THRESHOLD_MB

        if (isLow != wasLow) {
            wasLow = isLow
            if (isLow) {
                SmashLogger.warning("Storage low: ${availableMB}MB available")
                AlertManager.addAlert(AlertManager.ALERT_STORAGE, ALERT_MESSAGE)
            } else {
                SmashLogger.warning("Storage OK: ${availableMB}MB available")
                AlertManager.removeAlert(AlertManager.ALERT_STORAGE)
            }
            onStorageStateChanged?.invoke(isLow)
        }
    }

    private fun getAvailableStorageMB(): Long {
        return try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            availableBytes / (1024 * 1024)
        } catch (e: Exception) {
            SmashLogger.error("StorageMonitor: Failed to check storage: ${e.message}")
            Long.MAX_VALUE // Assume OK if we can't check
        }
    }

    /**
     * Check if storage is currently low.
     */
    fun isStorageLow(): Boolean = wasLow
}
