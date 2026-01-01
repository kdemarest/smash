package com.smash.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Status activity showing current configuration (read-only).
 * Opened when user taps the foreground notification.
 */
class StatusActivity : AppCompatActivity() {

    private lateinit var uptimeValue: TextView
    private lateinit var powerWarning: TextView
    private lateinit var prefixValue: TextView
    private lateinit var mailEndpointValue: TextView
    private lateinit var logEndpointValue: TextView
    private lateinit var targetsValue: TextView
    private lateinit var logValue: TextView
    private lateinit var logScrollView: ScrollView

    private val mainHandler = Handler(Looper.getMainLooper())
    
    private val uptimeRunnable = object : Runnable {
        override fun run() {
            updateUptime()
            mainHandler.postDelayed(this, 60_000) // Update every minute
        }
    }
    
    private val configListener = ConfigManager.OnConfigChangedListener { config ->
        // Config changed, update UI on main thread
        mainHandler.post { updateConfigDisplay(config) }
    }

    private val logListener = SmashLogger.OnLogChangedListener {
        // Log changed, update UI on main thread
        mainHandler.post { loadLog() }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Power state changed, update UI immediately
            mainHandler.post { updateUptime() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status)

        uptimeValue = findViewById(R.id.uptimeValue)
        powerWarning = findViewById(R.id.powerWarning)
        prefixValue = findViewById(R.id.prefixValue)
        mailEndpointValue = findViewById(R.id.mailEndpointValue)
        logEndpointValue = findViewById(R.id.logEndpointValue)
        targetsValue = findViewById(R.id.targetsValue)
        logValue = findViewById(R.id.logValue)
        logScrollView = findViewById(R.id.logScrollView)

        loadConfig()
        loadLog()
        updateUptime()
    }

    override fun onResume() {
        super.onResume()
        // Register for config and log changes
        SmashApplication.getConfigManager().addOnConfigChangedListener(configListener)
        SmashLogger.addOnLogChangedListener(logListener)
        // Register for power state changes
        val powerFilter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(powerReceiver, powerFilter)
        // Refresh config and log when returning to activity
        loadConfig()
        loadLog()
        // Start uptime updates
        updateUptime()
        mainHandler.postDelayed(uptimeRunnable, 60_000)
    }

    override fun onPause() {
        super.onPause()
        // Unregister when not visible
        SmashApplication.getConfigManager().removeOnConfigChangedListener(configListener)
        SmashLogger.removeOnLogChangedListener(logListener)
        try {
            unregisterReceiver(powerReceiver)
        } catch (e: Exception) {
            // Receiver wasn't registered
        }
        // Stop uptime updates
        mainHandler.removeCallbacks(uptimeRunnable)
    }

    private fun loadConfig() {
        val config = SmashApplication.getConfigManager().load()
        updateConfigDisplay(config)
    }

    private fun updateConfigDisplay(config: SmashConfig) {
        prefixValue.text = config.prefix

        mailEndpointValue.text = if (config.mailEndpointUrl.isNullOrBlank()) {
            "Not configured"
        } else {
            config.mailEndpointUrl
        }

        logEndpointValue.text = if (config.logEndpointUrl.isNullOrBlank()) {
            "Not configured"
        } else {
            config.logEndpointUrl
        }

        targetsValue.text = if (config.targets.isEmpty()) {
            "None configured"
        } else {
            config.targets.joinToString("\n")
        }

        title = if (PhoneUtils.isDefaultSmsApp(this)) {
            getString(R.string.status_title)
        } else {
            "${getString(R.string.status_title)} (NOT DEFAULT SMS APP)"
        }
    }

    private fun loadLog() {
        val lines = SmashLogger.getLastLines(50)
        logValue.text = if (lines.isEmpty()) {
            "No log entries"
        } else {
            lines.joinToString("\n")
        }
        // Scroll to bottom to show most recent entries
        logScrollView.post {
            logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun updateUptime() {
        val uptimeMillis = System.currentTimeMillis() - SmashApplication.startupTimeMillis
        val totalMinutes = uptimeMillis / 60_000
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        
        uptimeValue.text = "Uptime: ${days}d ${hours}h ${minutes}m"
        
        // Check power state from service
        val isUnplugged = SmashService.getInstance()?.let { service ->
            !service.isPowerPluggedIn()
        } ?: false
        
        powerWarning.visibility = if (isUnplugged) android.view.View.VISIBLE else android.view.View.GONE
    }
}
