package com.smash.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Checks reachability of mailEndpointUrl and logEndpointUrl hourly via TCP connect.
 * No HTTP is sent — just a socket connect to verify the server is listening.
 */
class EndpointMonitor(
    private val context: Context,
    private val onEndpointStateChanged: ((isUp: Boolean) -> Unit)? = null
) {

    companion object {
        private const val CHECK_INTERVAL_MS = 60_000L * 60
        private const val RETRY_INTERVAL_MS = 60_000L
        private const val CONNECT_TIMEOUT_MS = 10_000
    }

    private var isMonitoring = false
    private var allEndpointsUp = true
    private val handler = Handler(Looper.getMainLooper())

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                runCheck()
                val interval = if (allEndpointsUp) CHECK_INTERVAL_MS else RETRY_INTERVAL_MS
                handler.postDelayed(this, interval)
            }
        }
    }

    fun start() {
        if (isMonitoring) return
        isMonitoring = true
        runCheck()
        handler.postDelayed(checkRunnable, CHECK_INTERVAL_MS)
        SmashLogger.verbose("EndpointMonitor started")
    }

    fun stop() {
        if (!isMonitoring) return
        isMonitoring = false
        handler.removeCallbacks(checkRunnable)
        AlertManager.removeAlert(AlertManager.ALERT_ENDPOINT)
        SmashLogger.verbose("EndpointMonitor stopped")
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun runCheck() {
        Thread {
            if (!isNetworkAvailable()) {
                handler.post {
                    AlertManager.addInfoState(AlertManager.INFO_ENDPOINT_UNKNOWN, "Unknown (no network)")
                    SmashLogger.verbose("EndpointMonitor: skipping check, no network")
                }
                return@Thread
            }

            val config = SmashApplication.getConfigManager().load()
            val results = mutableListOf<Pair<String, Boolean>>()

            config.mailEndpointUrl?.takeIf { it.isNotBlank() }?.let {
                results.add("mail" to tcpCheck(it))
            }
            config.logEndpointUrl?.takeIf { it.isNotBlank() }?.let {
                results.add("log" to tcpCheck(it))
            }

            handler.post {
                AlertManager.removeInfoState(AlertManager.INFO_ENDPOINT_UNKNOWN)
                handleResults(results)
            }
        }.start()
    }

    private fun handleResults(results: List<Pair<String, Boolean>>) {
        if (results.isEmpty()) return

        val statusStr = results.joinToString(", ") { "${it.first}: ${if (it.second) "up" else "DOWN"}" }
        SmashLogger.warning("EndpointMonitor: $statusStr")

        val allUp = results.all { it.second }
        if (allUp == allEndpointsUp) return

        allEndpointsUp = allUp
        if (allUp) {
            if (AlertManager.isAlertActive(AlertManager.ALERT_ENDPOINT)) {
                AlertManager.removeAlert(AlertManager.ALERT_ENDPOINT)
                onEndpointStateChanged?.invoke(true)
            }
        } else {
            val down = results.filter { !it.second }.joinToString(", ") { it.first }
            AlertManager.addAlert(AlertManager.ALERT_ENDPOINT, "$down endpoint unreachable!")
            onEndpointStateChanged?.invoke(false)
        }
    }

    private fun tcpCheck(url: String): Boolean {
        return try {
            val uri = URI(url)
            val host = uri.host ?: return false
            val port = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
            Socket().use { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
            true
        } catch (e: Exception) {
            SmashLogger.error("EndpointMonitor: TCP connect failed for $url: ${e.message}")
            false
        }
    }
}
