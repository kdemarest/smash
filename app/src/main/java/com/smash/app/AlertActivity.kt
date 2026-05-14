@file:Suppress("DEPRECATION")
package com.smash.app

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity

/**
 * Unified full-screen alert activity for all issues (power, signal, etc.).
 * Displays over lock screen. Shows all active alerts and auto-updates.
 * Only dismisses when ALL issues are resolved.
 */
class AlertActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ALERTS = "alerts"
        const val EXTRA_DISMISS = "dismiss"
        private const val MIN_USABLE_SIGNAL_LEVEL = 2
    }

    private lateinit var alertContainer: LinearLayout
    
    // Receivers for auto-dismiss
    private var powerReceiver: BroadcastReceiver? = null
    private var airplaneModeReceiver: BroadcastReceiver? = null
    private var telephonyCallback: TelephonyCallback? = null
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

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

        setContentView(R.layout.activity_alert)
        alertContainer = findViewById(R.id.alertContainer)

        // Dismiss button
        findViewById<Button>(R.id.dismissButton).setOnClickListener {
            finish()
        }

        // Register listeners for auto-dismiss
        registerListeners()

        // Handle intent
        handleIntent(intent)

        SmashLogger.info("AlertActivity shown")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_DISMISS, false) == true) {
            finish()
            return
        }

        // Update displayed alerts from AlertManager (source of truth)
        updateAlertDisplay()
    }

    private fun updateAlertDisplay() {
        alertContainer.removeAllViews()
        
        val alerts = AlertManager.getActiveAlerts()
        if (alerts.isEmpty()) {
            finish()
            return
        }

        for ((alertType, message) in alerts) {
            val alertView = layoutInflater.inflate(R.layout.alert_item, alertContainer, false)
            
            val iconText = alertView.findViewById<TextView>(R.id.alertIcon)
            val messageText = alertView.findViewById<TextView>(R.id.alertMessage)
            
            // Set icon and color based on alert type
            when (alertType) {
                AlertManager.ALERT_POWER -> {
                    iconText.text = "🔌"
                    alertView.setBackgroundColor(0xFFCC0000.toInt()) // Red
                }
                AlertManager.ALERT_SIGNAL -> {
                    iconText.text = "📵"
                    alertView.setBackgroundColor(0xFFCC6600.toInt()) // Orange
                }
                AlertManager.ALERT_STORAGE -> {
                    iconText.text = "💾"
                    alertView.setBackgroundColor(0xFF666600.toInt()) // Dark yellow
                }
                else -> {
                    iconText.text = "⚠️"
                    alertView.setBackgroundColor(0xFFCCCC00.toInt()) // Yellow
                }
            }
            
            messageText.text = message
            alertContainer.addView(alertView)
        }
    }

    private fun registerListeners() {
        // Power listener
        powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_POWER_CONNECTED) {
                    checkAndUpdateAlerts()
                }
            }
        }
        registerReceiver(powerReceiver, IntentFilter(Intent.ACTION_POWER_CONNECTED))

        // Airplane mode listener
        airplaneModeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_AIRPLANE_MODE_CHANGED) {
                    checkAndUpdateAlerts()
                }
            }
        }
        registerReceiver(airplaneModeReceiver, IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED))

        // Signal listener
        val telephonyManager = getSystemService(TelephonyManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerTelephonyCallback(telephonyManager)
        } else {
            registerPhoneStateListener(telephonyManager)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerTelephonyCallback(telephonyManager: TelephonyManager?) {
        telephonyCallback = object : TelephonyCallback(),
            TelephonyCallback.SignalStrengthsListener,
            TelephonyCallback.ServiceStateListener {
            
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                checkAndUpdateAlerts()
            }
            
            override fun onServiceStateChanged(serviceState: ServiceState) {
                checkAndUpdateAlerts()
            }
        }
        try {
            telephonyManager?.registerTelephonyCallback(mainExecutor, telephonyCallback!!)
        } catch (e: Exception) {
            SmashLogger.error("AlertActivity: Failed to register telephony callback: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun registerPhoneStateListener(telephonyManager: TelephonyManager?) {
        phoneStateListener = object : PhoneStateListener() {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                checkAndUpdateAlerts()
            }
            
            override fun onServiceStateChanged(serviceState: ServiceState) {
                checkAndUpdateAlerts()
            }
        }
        try {
            telephonyManager?.listen(
                phoneStateListener,
                PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or PhoneStateListener.LISTEN_SERVICE_STATE
            )
        } catch (e: Exception) {
            SmashLogger.error("AlertActivity: Failed to register phone state listener: ${e.message}")
        }
    }

    private fun checkAndUpdateAlerts() {
        // AlertManager is the source of truth - just refresh display
        // The monitors will update AlertManager when conditions change
        updateAlertDisplay()
    }

    override fun onDestroy() {
        super.onDestroy()
        
        try {
            powerReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) { }
        
        try {
            airplaneModeReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) { }
        
        val telephonyManager = getSystemService(TelephonyManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { telephonyManager?.unregisterTelephonyCallback(it) }
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let { telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE) }
        }
        
        SmashLogger.verbose("AlertActivity dismissed")
    }
}
