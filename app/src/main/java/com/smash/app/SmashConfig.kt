package com.smash.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Data model for smash.json configuration file.
 */
@Serializable
data class SmashConfig(
    val prefix: String = DEFAULT_PREFIX,
    val mailEndpointUrl: String? = null,
    val targets: List<String> = emptyList()
) {
    companion object {
        const val DEFAULT_PREFIX = "Cmd"
        const val CONFIG_FILENAME = "smash.json"

        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        /**
         * Create default configuration.
         */
        fun default(): SmashConfig = SmashConfig()

        /**
         * Parse JSON string to SmashConfig.
         * Returns default config if parsing fails.
         */
        fun fromJson(jsonString: String): SmashConfig {
            return try {
                json.decodeFromString<SmashConfig>(jsonString)
            } catch (e: Exception) {
                default()
            }
        }

        /**
         * Convert SmashConfig to JSON string.
         */
        fun toJson(config: SmashConfig): String {
            return json.encodeToString(config)
        }
    }

    /**
     * Ensure prefix is never empty - return DEFAULT_PREFIX if it is.
     */
    fun withValidPrefix(): SmashConfig {
        return if (prefix.isBlank()) {
            copy(prefix = DEFAULT_PREFIX)
        } else {
            this
        }
    }

    /**
     * Check if mail endpoint is configured and valid.
     */
    fun isMailEndpointEnabled(): Boolean {
        return !mailEndpointUrl.isNullOrBlank()
    }

    /**
     * Add a target (case-insensitive duplicate check).
     * Returns pair of (new config, wasAdded).
     */
    fun addTarget(target: String): Pair<SmashConfig, Boolean> {
        val trimmedTarget = target.trim()
        val exists = targets.any { it.equals(trimmedTarget, ignoreCase = true) }
        return if (exists) {
            this to false
        } else {
            copy(targets = targets + trimmedTarget) to true
        }
    }

    /**
     * Remove a target (case-insensitive match).
     * Returns pair of (new config, wasRemoved).
     */
    fun removeTarget(target: String): Pair<SmashConfig, Boolean> {
        val trimmedTarget = target.trim()
        val index = targets.indexOfFirst { it.equals(trimmedTarget, ignoreCase = true) }
        return if (index >= 0) {
            copy(targets = targets.filterIndexed { i, _ -> i != index }) to true
        } else {
            this to false
        }
    }
}
