package com.smash.app

import android.app.Application

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

        /**
         * Get the ConfigManager singleton.
         */
        fun getConfigManager(): ConfigManager = instance.configManager
    }
}
