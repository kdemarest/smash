package com.smash.app

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * Centralized beeping service. Multiple clients can request beeping,
 * and beeping continues while ANY client wants it. Only stops when ALL clients stop.
 * This prevents overlapping beeps from different monitors.
 */
object BeepService {

    const val beepOnConditions = false

    private const val BEEP_INTERVAL_MS = 60_000L // 1 minute
    private const val BEEP_DURATION_MS = 200
    private const val BEEP_VOLUME = 80 // 0-100

    private val handler = Handler(Looper.getMainLooper())
    private var toneGenerator: ToneGenerator? = null
    private val activeClients = mutableSetOf<String>()

    private val beepRunnable = object : Runnable {
        override fun run() {
            if (activeClients.isNotEmpty()) {
                playBeep()
                handler.postDelayed(this, BEEP_INTERVAL_MS)
            }
        }
    }

    /**
     * Initialize the tone generator. Call once at app start.
     */
    fun init() {
        if (toneGenerator == null) {
            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, BEEP_VOLUME)
            } catch (e: Exception) {
                SmashLogger.error("BeepService: Failed to create ToneGenerator: ${e.message}")
            }
        }
    }

    /**
     * Release resources. Call when service stops.
     */
    fun release() {
        stopAll()
        toneGenerator?.release()
        toneGenerator = null
    }

    /**
     * Start beeping for a specific client. Beeps immediately, then every interval.
     * @param clientId Unique identifier for the client (e.g., "power", "signal")
     */
    fun startBeeping(clientId: String) {
        if (!beepOnConditions) return
        val wasEmpty = activeClients.isEmpty()
        activeClients.add(clientId)
        
        if (wasEmpty) {
            // First client - start the beep cycle
            SmashLogger.verbose("BeepService: Starting beeps for $clientId")
            playBeep()
            handler.postDelayed(beepRunnable, BEEP_INTERVAL_MS)
        } else {
            // Already beeping, just play one immediate beep for the new alert
            SmashLogger.verbose("BeepService: Adding $clientId (already beeping)")
            playBeep()
        }
    }

    /**
     * Stop beeping for a specific client. Beeping only stops when ALL clients have stopped.
     * @param clientId Unique identifier for the client
     */
    fun stopBeeping(clientId: String) {
        activeClients.remove(clientId)
        
        if (activeClients.isEmpty()) {
            handler.removeCallbacks(beepRunnable)
            SmashLogger.verbose("BeepService: All clients stopped, beeping ended")
        } else {
            SmashLogger.verbose("BeepService: $clientId stopped, still beeping for: ${activeClients.joinToString()}")
        }
    }

    /**
     * Stop all beeping immediately.
     */
    fun stopAll() {
        activeClients.clear()
        handler.removeCallbacks(beepRunnable)
    }

    /**
     * Check if any client is currently requesting beeps.
     */
    fun isBeeping(): Boolean = activeClients.isNotEmpty()

    /**
     * Get list of active clients.
     */
    fun getActiveClients(): Set<String> = activeClients.toSet()

    private fun playBeep() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, BEEP_DURATION_MS)
            SmashLogger.verbose("BeepService: beep (clients: ${activeClients.joinToString()})")
        } catch (e: Exception) {
            SmashLogger.error("BeepService: Failed to play beep: ${e.message}")
        }
    }
}
