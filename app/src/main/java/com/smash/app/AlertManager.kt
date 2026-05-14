package com.smash.app

import android.content.Context
import android.content.Intent

/**
 * Centralized alert manager. Tracks all active alerts and shows a unified alert activity.
 * Works with BeepService for audio alerts.
 * 
 * When multiple issues occur, all are displayed. When one is fixed, others remain.
 */
object AlertManager {

    // Alert types
    const val ALERT_POWER = "power"
    const val ALERT_SIGNAL = "signal"
    const val ALERT_STORAGE = "storage"

    // Map of active alerts: type -> message
    private val activeAlerts = mutableMapOf<String, String>()
    
    private var context: Context? = null

    /**
     * Initialize with application context. Call once at service start.
     */
    fun init(context: Context) {
        this.context = context.applicationContext
    }

    /**
     * Add or update an alert. Shows/updates the alert activity.
     * @param alertType Unique type (ALERT_POWER, ALERT_SIGNAL, etc.)
     * @param message The imperative message to display (e.g., "Plug in the phone!")
     */
    fun addAlert(alertType: String, message: String) {
        activeAlerts[alertType] = message
        BeepService.startBeeping(alertType)
        showAlertActivity()
        SmashLogger.verbose("AlertManager: Added alert '$alertType': $message")
    }

    /**
     * Remove an alert. Updates or dismisses the alert activity.
     * @param alertType The alert type to remove
     */
    fun removeAlert(alertType: String) {
        if (activeAlerts.remove(alertType) != null) {
            BeepService.stopBeeping(alertType)
            SmashLogger.verbose("AlertManager: Removed alert '$alertType'")
            
            if (activeAlerts.isEmpty()) {
                dismissAlertActivity()
            } else {
                // Update activity to show remaining alerts
                showAlertActivity()
            }
        }
    }

    /**
     * Check if any alerts are active.
     */
    fun hasActiveAlerts(): Boolean = activeAlerts.isNotEmpty()

    /**
     * Get all active alerts.
     */
    fun getActiveAlerts(): Map<String, String> = activeAlerts.toMap()

    /**
     * Get combined message for all active alerts.
     */
    fun getCombinedMessage(): String {
        return activeAlerts.values.joinToString("\n")
    }

    /**
     * Check if a specific alert is active.
     */
    fun isAlertActive(alertType: String): Boolean = activeAlerts.containsKey(alertType)

    /**
     * Clear all alerts.
     */
    fun clearAll() {
        for (alertType in activeAlerts.keys.toList()) {
            BeepService.stopBeeping(alertType)
        }
        activeAlerts.clear()
        dismissAlertActivity()
    }

    private fun showAlertActivity() {
        val ctx = context ?: return
        val intent = Intent(ctx, AlertActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // Pass all active alerts
            putExtra(AlertActivity.EXTRA_ALERTS, ArrayList(activeAlerts.entries.map { "${it.key}:${it.value}" }))
        }
        ctx.startActivity(intent)
    }

    private fun dismissAlertActivity() {
        val ctx = context ?: return
        val intent = Intent(ctx, AlertActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(AlertActivity.EXTRA_DISMISS, true)
        }
        ctx.startActivity(intent)
    }
}
