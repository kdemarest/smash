package com.smash.app

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Handles persistence of SmashConfig to/from smash.json.
 * Thread-safe read/write operations with atomic file writes.
 */
class ConfigManager(private val context: Context) {

    private val lock = ReentrantReadWriteLock()
    
    @Volatile
    private var cachedConfig: SmashConfig? = null

    private val configFile: File
        get() = File(context.filesDir, SmashConfig.CONFIG_FILENAME)

    /**
     * Load configuration from disk.
     * Returns default config if file doesn't exist or is corrupted.
     */
    fun load(): SmashConfig {
        lock.read {
            cachedConfig?.let { return it }
        }

        return lock.write {
            // Double-check after acquiring write lock
            cachedConfig?.let { return@write it }

            val config = try {
                if (configFile.exists()) {
                    val jsonString = configFile.readText(Charsets.UTF_8)
                    SmashConfig.fromJson(jsonString).withValidPrefix()
                } else {
                    SmashConfig.default()
                }
            } catch (e: Exception) {
                SmashConfig.default()
            }

            cachedConfig = config
            config
        }
    }

    /**
     * Save configuration to disk atomically.
     * Returns true on success, false on failure.
     */
    fun save(config: SmashConfig): Boolean {
        return lock.write {
            try {
                val validConfig = config.withValidPrefix()
                val jsonString = SmashConfig.toJson(validConfig)

                // Write to temp file first, then rename for atomic write
                val tempFile = File(context.filesDir, "${SmashConfig.CONFIG_FILENAME}.tmp")
                tempFile.writeText(jsonString, Charsets.UTF_8)

                // Atomic rename
                if (tempFile.renameTo(configFile)) {
                    cachedConfig = validConfig
                    true
                } else {
                    // Fallback: direct write if rename fails
                    configFile.writeText(jsonString, Charsets.UTF_8)
                    cachedConfig = validConfig
                    true
                }
            } catch (e: IOException) {
                false
            }
        }
    }

    /**
     * Update configuration with a transform function.
     * Returns pair of (new config, success).
     */
    fun update(transform: (SmashConfig) -> SmashConfig): Pair<SmashConfig, Boolean> {
        return lock.write {
            val current = cachedConfig ?: load()
            val updated = transform(current)
            val success = save(updated)
            updated to success
        }
    }

    /**
     * Clear cached config, forcing reload from disk on next access.
     */
    fun invalidateCache() {
        lock.write {
            cachedConfig = null
        }
    }

    /**
     * Get the current prefix.
     */
    fun getPrefix(): String {
        return load().prefix
    }

    /**
     * Get formatted config info for display/logging.
     */
    fun getConfigSummary(): String {
        val config = load()
        return buildString {
            appendLine("prefix=${config.prefix}")
            appendLine("mailEndpointUrl=${config.mailEndpointUrl ?: "null"}")
            appendLine("targets count=${config.targets.size}")
            config.targets.forEach { target ->
                appendLine("  - $target")
            }
        }
    }
}
