@file:Suppress("DEPRECATION")
package com.smash.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi

/**
 * Monitors cellular signal state and alerts when signal is lost or too weak.
 * Handles: no signal, weak signal (0-1 bars), airplane mode, cellular radio off,
 * SIM issues (removed, locked, suspended), and emergency-only mode (unpaid bill).
 * Uses AlertManager for unified alerts.
 */
class SignalMonitor(
    private val context: Context,
    private val onSignalStateChanged: ((hasSignal: Boolean, reason: String?) -> Unit)? = null
) {

    companion object {
        private const val MIN_USABLE_SIGNAL_LEVEL = 2 // 0-1 considered unusable
        private const val SIGNAL_LOSS_DEBOUNCE_MS = 20_000L
    }

    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)
    private var isMonitoring = false

    // Track individual conditions
    private var isAirplaneModeOn = false
    private var serviceState = ServiceState.STATE_OUT_OF_SERVICE // Pessimistic until queried
    private var simState = TelephonyManager.SIM_STATE_UNKNOWN // Pessimistic until queried
    private var signalLevel = 0 // Pessimistic until queried

    var hasUsableSignal = true
        private set

    // Debounce signal loss — only fire after sustained loss
    private val handler = Handler(Looper.getMainLooper())
    private var pendingLossReason: String? = null
    private val signalLossRunnable = Runnable {
        val reason = pendingLossReason ?: return@Runnable
        hasUsableSignal = false
        SmashLogger.warning("Cell signal unusable (sustained 20s): $reason")
        AlertManager.addAlert(AlertManager.ALERT_SIGNAL, reason)
        onSignalStateChanged?.invoke(false, reason)
    }

    // For API 31+
    private var telephonyCallback: TelephonyCallback? = null

    // For older APIs
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    // Airplane mode receiver
    private val airplaneModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_AIRPLANE_MODE_CHANGED) {
                isAirplaneModeOn = intent.getBooleanExtra("state", false)
                SmashLogger.verbose("SignalMonitor: Airplane mode changed: $isAirplaneModeOn")
                evaluateSignalState()
            }
        }
    }

    // SIM state receiver
    private val simStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "android.intent.action.SIM_STATE_CHANGED") {
                checkSimState()
                evaluateSignalState()
            }
        }
    }

    /**
     * Start monitoring signal state.
     */
    fun start() {
        if (isMonitoring) return
        isMonitoring = true

        // Check initial airplane mode state
        isAirplaneModeOn = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON, 0
        ) != 0

        // Check initial SIM state
        checkSimState()

        // Query initial signal strength and service state synchronously
        queryInitialTelephonyState()

        // Register for airplane mode changes
        val airplaneFilter = IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        context.registerReceiver(airplaneModeReceiver, airplaneFilter)

        // Register for SIM state changes
        val simFilter = IntentFilter().apply {
            addAction("android.intent.action.SIM_STATE_CHANGED")
        }
        context.registerReceiver(simStateReceiver, simFilter)

        // Register for signal strength and service state changes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerTelephonyCallback()
        } else {
            registerPhoneStateListener()
        }

        // Evaluate initial state
        evaluateSignalState()

        SmashLogger.verbose("SignalMonitor started (airplane: $isAirplaneModeOn, sim: ${getSimStateString()})")
    }

    private fun checkSimState() {
        simState = telephonyManager?.simState ?: TelephonyManager.SIM_STATE_UNKNOWN
        SmashLogger.verbose("SignalMonitor: SIM state: ${getSimStateString()}")
    }

    /**
     * Query current signal strength and service state synchronously.
     * This ensures we have accurate state at startup rather than relying on async callbacks.
     */
    private fun queryInitialTelephonyState() {
        try {
            // Query current signal strength
            telephonyManager?.signalStrength?.let { ss ->
                signalLevel = ss.level
                SmashLogger.verbose("SignalMonitor: Initial signal level: $signalLevel")
            }
            
            // Query current service state (requires READ_PHONE_STATE permission)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telephonyManager?.serviceState?.let { ss ->
                    serviceState = ss.state
                    SmashLogger.verbose("SignalMonitor: Initial service state: $serviceState")
                }
            }
        } catch (e: SecurityException) {
            SmashLogger.error("SignalMonitor: Permission denied querying telephony state: ${e.message}")
            // Keep pessimistic defaults
        } catch (e: Exception) {
            SmashLogger.error("SignalMonitor: Failed to query initial telephony state: ${e.message}")
            // Keep pessimistic defaults
        }
    }

    private fun getSimStateString(): String = when (simState) {
        TelephonyManager.SIM_STATE_UNKNOWN -> "UNKNOWN"
        TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
        TelephonyManager.SIM_STATE_READY -> "READY"
        TelephonyManager.SIM_STATE_NOT_READY -> "NOT_READY"
        TelephonyManager.SIM_STATE_PERM_DISABLED -> "PERM_DISABLED"
        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "CARD_IO_ERROR"
        TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "CARD_RESTRICTED"
        else -> "UNKNOWN($simState)"
    }

    /**
     * Stop monitoring signal state.
     */
    fun stop() {
        if (!isMonitoring) return
        isMonitoring = false

        handler.removeCallbacks(signalLossRunnable)
        pendingLossReason = null
        AlertManager.removeAlert(AlertManager.ALERT_SIGNAL)

        try {
            context.unregisterReceiver(airplaneModeReceiver)
        } catch (e: Exception) {
            // Receiver wasn't registered
        }

        try {
            context.unregisterReceiver(simStateReceiver)
        } catch (e: Exception) {
            // Receiver wasn't registered
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { telephonyManager?.unregisterTelephonyCallback(it) }
            telephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let { telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE) }
            phoneStateListener = null
        }

        SmashLogger.verbose("SignalMonitor stopped")
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerTelephonyCallback() {
        telephonyCallback = object : TelephonyCallback(), 
            TelephonyCallback.SignalStrengthsListener,
            TelephonyCallback.ServiceStateListener {
            
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                signalLevel = signalStrength.level
                evaluateSignalState()
            }
            
            override fun onServiceStateChanged(serviceState: ServiceState) {
                handleServiceStateChanged(serviceState)
            }
        }
        try {
            telephonyManager?.registerTelephonyCallback(
                context.mainExecutor,
                telephonyCallback!!
            )
        } catch (e: Exception) {
            SmashLogger.error("SignalMonitor: Failed to register callback: ${e.message}")
            // Fail closed: assume no signal if we can't monitor
            signalLevel = 0
            serviceState = ServiceState.STATE_OUT_OF_SERVICE
        }
    }

    @Suppress("DEPRECATION")
    private fun registerPhoneStateListener() {
        phoneStateListener = object : PhoneStateListener() {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                signalLevel = signalStrength.level
                evaluateSignalState()
            }
            
            override fun onServiceStateChanged(serviceState: ServiceState) {
                handleServiceStateChanged(serviceState)
            }
        }
        try {
            @Suppress("DEPRECATION")
            telephonyManager?.listen(
                phoneStateListener, 
                PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or PhoneStateListener.LISTEN_SERVICE_STATE
            )
        } catch (e: Exception) {
            SmashLogger.error("SignalMonitor: Failed to register listener: ${e.message}")
            // Fail closed: assume no signal if we can't monitor
            signalLevel = 0
            serviceState = ServiceState.STATE_OUT_OF_SERVICE
        }
    }

    private fun handleServiceStateChanged(ss: ServiceState) {
        val oldState = serviceState
        serviceState = ss.state
        
        if (oldState != serviceState) {
            val stateStr = when (serviceState) {
                ServiceState.STATE_IN_SERVICE -> "IN_SERVICE"
                ServiceState.STATE_OUT_OF_SERVICE -> "OUT_OF_SERVICE"
                ServiceState.STATE_EMERGENCY_ONLY -> "EMERGENCY_ONLY"
                ServiceState.STATE_POWER_OFF -> "POWER_OFF"
                else -> "UNKNOWN($serviceState)"
            }
            SmashLogger.verbose("SignalMonitor: Service state changed: $stateStr")
            evaluateSignalState()
        }
    }

    /**
     * Check if SIM is usable for SMS.
     */
    private fun isSimUsable(): Boolean = when (simState) {
        TelephonyManager.SIM_STATE_READY -> true
        else -> false
    }

    /**
     * Check if service state allows SMS.
     * Only IN_SERVICE allows normal SMS. EMERGENCY_ONLY (unpaid bill) does NOT.
     */
    private fun isServiceUsable(): Boolean = serviceState == ServiceState.STATE_IN_SERVICE

    /**
     * Evaluate all conditions to determine if we have usable signal.
     * No signal if: airplane mode on, service not usable, SIM not ready, or signal too weak.
     */
    private fun evaluateSignalState() {
        val newHasSignal = !isAirplaneModeOn &&
                           isServiceUsable() &&
                           isSimUsable() &&
                           signalLevel >= MIN_USABLE_SIGNAL_LEVEL

        if (newHasSignal) {
            // Signal is good — cancel any pending loss alert
            if (pendingLossReason != null) {
                handler.removeCallbacks(signalLossRunnable)
                pendingLossReason = null
                SmashLogger.verbose("SignalMonitor: signal recovered before debounce fired")
            }
            if (!hasUsableSignal) {
                hasUsableSignal = true
                SmashLogger.warning("Cell signal restored (level: $signalLevel)")
                AlertManager.removeAlert(AlertManager.ALERT_SIGNAL)
                onSignalStateChanged?.invoke(true, null)
            }
        } else {
            // Signal is bad — debounce before alerting
            val reason = when {
                isAirplaneModeOn -> "Turn off Airplane Mode!"
                simState == TelephonyManager.SIM_STATE_ABSENT -> "Insert SIM card!"
                simState == TelephonyManager.SIM_STATE_PIN_REQUIRED -> "Enter SIM PIN!"
                simState == TelephonyManager.SIM_STATE_PUK_REQUIRED -> "Enter SIM PUK!"
                simState == TelephonyManager.SIM_STATE_PERM_DISABLED -> "SIM is disabled!"
                !isSimUsable() -> "Check SIM card!"
                serviceState == ServiceState.STATE_EMERGENCY_ONLY -> "Pay phone bill!"
                serviceState == ServiceState.STATE_POWER_OFF -> "Turn cellular back on!"
                !isServiceUsable() -> "No cellular service!"
                else -> "Move Phone to stronger Signal!"
            }
            if (pendingLossReason == null && hasUsableSignal) {
                pendingLossReason = reason
                val delay = if (isAirplaneModeOn) 0L else SIGNAL_LOSS_DEBOUNCE_MS
                handler.postDelayed(signalLossRunnable, delay)
                if (delay > 0) SmashLogger.verbose("SignalMonitor: signal low, waiting ${delay/1000}s before alerting")
            }
        }
    }
}
