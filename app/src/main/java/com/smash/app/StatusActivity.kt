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
    private lateinit var signalWarning: TextView
    private lateinit var tailscaleWarning: TextView
    private lateinit var endpointWarning: TextView
    private lateinit var prefixValue: TextView
    private lateinit var mailEndpointValue: TextView
    private lateinit var logEndpointValue: TextView
    private lateinit var targetsValue: TextView
    private lateinit var logLabel: TextView
    private lateinit var logLevelButton: android.widget.Button
    private lateinit var logValue: TextView
    private lateinit var logScrollView: ScrollView
    private var viewMode: SmashLogger.LogMode = SmashLogger.LogMode.INFO

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
        signalWarning = findViewById(R.id.signalWarning)
        tailscaleWarning = findViewById(R.id.tailscaleWarning)
        endpointWarning = findViewById(R.id.endpointWarning)
        prefixValue = findViewById(R.id.prefixValue)
        mailEndpointValue = findViewById(R.id.mailEndpointValue)
        logEndpointValue = findViewById(R.id.logEndpointValue)
        targetsValue = findViewById(R.id.targetsValue)
        logLabel = findViewById(R.id.logLabel)
        logLevelButton = findViewById(R.id.logLevelButton)
        logValue = findViewById(R.id.logValue)
        logScrollView = findViewById(R.id.logScrollView)

        logLevelButton.setOnClickListener {
            viewMode = when (viewMode) {
                SmashLogger.LogMode.VERBOSE -> SmashLogger.LogMode.INFO
                SmashLogger.LogMode.INFO -> SmashLogger.LogMode.WARNINGS
                SmashLogger.LogMode.WARNINGS -> SmashLogger.LogMode.VERBOSE
            }
            loadLog()
        }

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
            config.targets.joinToString("\n") { target ->
                val flags = config.targetFlags
                    .filter { it.target.equals(target, ignoreCase = true) }
                    .map { it.flag }
                if (flags.isEmpty()) target else "$target (${flags.joinToString(", ")})"
            }
        }

        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        val baseTitle = "${getString(R.string.status_title)} v$versionName"
        title = if (PhoneUtils.isDefaultSmsApp(this)) {
            baseTitle
        } else {
            "$baseTitle (NOT DEFAULT SMS APP)"
        }
    }

    private fun updateLogLabel() {
        val mode = viewMode.name.lowercase()
        logLabel.text = "Recent Log: $mode"
        logLevelButton.text = mode
    }

    private fun loadLog() {
        updateLogLabel()
        val lines = SmashLogger.getLastLines(200, viewMode).map(SmashLogger::localizeTimestamp)
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
        
        uptimeValue.text = "Uptime\n${days}d ${hours}h ${minutes}m"
        
        // Use AlertManager for warning display
        val activeAlerts = AlertManager.getActiveAlerts()
        
        val colorOk = android.graphics.Color.parseColor("#666666")
        val colorInfo = android.graphics.Color.parseColor("#CC6600")
        val colorWarn = powerWarning.context.getColor(android.R.color.holo_red_light)
        val infoStates = AlertManager.getInfoStates()

        fun setStatus(view: TextView, alertMessage: String?, warnPrefix: String, okLabel: String, infoMessage: String? = null) {
            when {
                alertMessage != null -> { view.text = "$warnPrefix $alertMessage"; view.setTextColor(colorWarn) }
                infoMessage != null  -> { view.text = "⚡ $infoMessage";           view.setTextColor(colorInfo) }
                else                 -> { view.text = "✓ $okLabel";               view.setTextColor(colorOk) }
            }
            view.visibility = android.view.View.VISIBLE
        }

        setStatus(powerWarning,    activeAlerts[AlertManager.ALERT_POWER],     "⚠️", "Power",     infoStates[AlertManager.INFO_POWER_UNPLUGGED])
        setStatus(signalWarning,   activeAlerts[AlertManager.ALERT_SIGNAL],    "📵", "Signal")
        setStatus(tailscaleWarning,activeAlerts[AlertManager.ALERT_TAILSCALE], "🔒", "VPN")
        setStatus(endpointWarning, activeAlerts[AlertManager.ALERT_ENDPOINT],  "🌐", "Endpoints", infoStates[AlertManager.INFO_ENDPOINT_UNKNOWN])
    }
}
