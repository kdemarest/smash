package com.smash.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.NetworkInterface

/**
 * Monitors Tailscale connectivity by polling for a 100.64.0.0/10 address.
 * Polls every 10s when VPN is up, every 2s when down for fast recovery detection.
 */
class TailscaleMonitor(
    private val context: Context,
    private val onVpnStateChanged: ((isUp: Boolean) -> Unit)? = null
) {

    companion object {
        private const val ALERT_MESSAGE = "VPN is down!"
        private const val INTERVAL_UP_MS = 10_000L
        private const val INTERVAL_DOWN_MS = 2_000L
    }

    private var isMonitoring = false
    var isVpnUp = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val checkRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                checkVpnState()
                handler.postDelayed(this, if (isVpnUp) INTERVAL_UP_MS else INTERVAL_DOWN_MS)
            }
        }
    }

    fun start() {
        if (isMonitoring) return
        isMonitoring = true
        checkVpnState()
        handler.postDelayed(checkRunnable, if (isVpnUp) INTERVAL_UP_MS else INTERVAL_DOWN_MS)
        SmashLogger.verbose("TailscaleMonitor started")
    }

    fun stop() {
        if (!isMonitoring) return
        isMonitoring = false
        handler.removeCallbacks(checkRunnable)
        AlertManager.removeAlert(AlertManager.ALERT_TAILSCALE)
        SmashLogger.verbose("TailscaleMonitor stopped")
    }

    private fun checkVpnState() {
        val hasVpn = try {
            NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.any { addr ->
                    val b = addr.address
                    b.size == 4 &&
                    b[0].toInt() and 0xFF == 100 &&
                    b[1].toInt() and 0xC0 == 0x40  // 100.64.0.0/10
                } ?: false
        } catch (e: Exception) {
            SmashLogger.error("TailscaleMonitor: failed to check interfaces: ${e.message}")
            return
        }

        if (hasVpn == isVpnUp) return

        SmashLogger.warning("TailscaleMonitor: VPN ${if (hasVpn) "up" else "down"}")

        isVpnUp = hasVpn
        if (hasVpn) {
            if (AlertManager.isAlertActive(AlertManager.ALERT_TAILSCALE)) {
                AlertManager.removeAlert(AlertManager.ALERT_TAILSCALE)
                onVpnStateChanged?.invoke(true)
            }
        } else {
            AlertManager.addAlert(AlertManager.ALERT_TAILSCALE, ALERT_MESSAGE)
            onVpnStateChanged?.invoke(false)
        }
    }
}
