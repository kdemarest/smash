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

        // Log startup
        SmashLogger.info("smash starting")
        SmashLogger.info("prefix=${config.prefix}")
        SmashLogger.info("mailEndpointUrl=${config.mailEndpointUrl ?: "null"}")
        SmashLogger.info("targets count=${config.targets.size}")
        config.targets.forEach { target ->
            SmashLogger.info("  target: $target")
        }
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
