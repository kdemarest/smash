package com.smash.app

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Status activity showing current configuration (read-only).
 * Opened when user taps the foreground notification.
 */
class StatusActivity : AppCompatActivity() {

    private lateinit var prefixValue: TextView
    private lateinit var mailEndpointValue: TextView
    private lateinit var targetsValue: TextView
    private lateinit var logValue: TextView
    private lateinit var logScrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status)

        prefixValue = findViewById(R.id.prefixValue)
        mailEndpointValue = findViewById(R.id.mailEndpointValue)
        targetsValue = findViewById(R.id.targetsValue)
        logValue = findViewById(R.id.logValue)
        logScrollView = findViewById(R.id.logScrollView)

        loadConfig()
        loadLog()
    }

    override fun onResume() {
        super.onResume()
        // Refresh config and log when returning to activity
        loadConfig()
        loadLog()
    }

    private fun loadConfig() {
        val config = SmashApplication.getConfigManager().load()

        prefixValue.text = config.prefix

        mailEndpointValue.text = if (config.mailEndpointUrl.isNullOrBlank()) {
            "Not configured"
        } else {
            config.mailEndpointUrl
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
}
