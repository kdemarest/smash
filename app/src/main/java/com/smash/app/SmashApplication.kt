package com.smash.app

import android.app.Application
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Application class for smash.
 * Initializes logging and loads configuration on app start.
 */
class SmashApplication : Application() {

    lateinit var configManager: ConfigManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize logger
        SmashLogger.init(this)

        // Initialize config manager and load config
        configManager = ConfigManager(this)
        val config = configManager.load()
        
        // Set verbose mode from config
        SmashLogger.isVerbose = config.verbose

        // Log startup (compact)
        val verboseFlag = if (config.verbose) " [verbose]" else ""
        SmashLogger.info("smash starting$verboseFlag, ${config.targets.size} targets")
    }

    companion object {
        lateinit var instance: SmashApplication
            private set

        // Queue for messages received before service is ready
        private val pendingMessages = ConcurrentLinkedQueue<IncomingMessage>()

        /**
         * Get the ConfigManager singleton.
         */
        fun getConfigManager(): ConfigManager = instance.configManager

        /**
         * Add a message to the pending queue (when service isn't ready yet).
         */
        fun addPendingMessage(message: IncomingMessage) {
            pendingMessages.add(message)
            SmashLogger.verbose("SmashApplication: queued pending message from ${message.sender}")
        }

        /**
         * Drain all pending messages and return them.
         * Called by SmashService when it starts.
         */
        fun drainPendingMessages(): List<IncomingMessage> {
            val messages = mutableListOf<IncomingMessage>()
            while (true) {
                val msg = pendingMessages.poll() ?: break
                messages.add(msg)
            }
            if (messages.isNotEmpty()) {
                SmashLogger.info("SmashApplication: drained ${messages.size} pending message(s)")
            }
            return messages
        }
    }
}
