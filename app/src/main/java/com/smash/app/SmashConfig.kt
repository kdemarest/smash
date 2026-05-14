package com.smash.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Associates a flag with a specific target address.
 */
@Serializable
data class TargetFlag(
    val target: String,
    val flag: String
)

/**
 * Data model for smash.json configuration file.
 */
@Serializable
data class SmashConfig(
    val prefix: String = DEFAULT_PREFIX,
    val mailEndpointUrl: String? = null,
    val logEndpointUrl: String? = null,
    val targets: List<String> = emptyList(),
    val aliases: Map<String, String> = emptyMap(),
    val verbose: Boolean = false,
    val targetFlags: List<TargetFlag> = emptyList(),
    val filters: List<String> = emptyList()
) {
    companion object {
        const val DEFAULT_PREFIX = "Cmd"
        const val CONFIG_FILENAME = "smash.json"

        val VALID_FLAGS = listOf("getWarnings")
        fun isValidFlag(flag: String) = VALID_FLAGS.any { it.equals(flag, ignoreCase = true) }
        fun validFlagsString() = VALID_FLAGS.joinToString(", ")

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
     * Check if log endpoint is configured and valid.
     */
    fun isLogEndpointEnabled(): Boolean {
        return !logEndpointUrl.isNullOrBlank()
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

    /**
     * Add or update an alias (case-insensitive name, preserves display case).
     * Returns new config.
     */
    fun setAlias(name: String, value: String): SmashConfig {
        val trimmedName = name.trim()
        // Remove any existing alias with same name (case-insensitive)
        val filtered = aliases.filterKeys { !it.equals(trimmedName, ignoreCase = true) }
        return copy(aliases = filtered + (trimmedName to value.trim()))
    }

    /**
     * Remove an alias (case-insensitive name).
     * Returns pair of (new config, wasRemoved).
     */
    fun removeAlias(name: String): Pair<SmashConfig, Boolean> {
        val trimmedName = name.trim()
        val key = aliases.keys.firstOrNull { it.equals(trimmedName, ignoreCase = true) }
        return if (key != null) {
            copy(aliases = aliases - key) to true
        } else {
            this to false
        }
    }

    /**
     * Resolve an alias to its value, or return the input if not an alias.
     * Case-insensitive lookup.
     */
    fun resolveAlias(nameOrValue: String): String {
        val trimmedName = nameOrValue.trim()
        val entry = aliases.entries.firstOrNull { (k, _) -> k.equals(trimmedName, ignoreCase = true) }
        return entry?.value ?: nameOrValue
    }

    /**
     * Check if a message body matches any filter (case-insensitive substring match).
     */
    fun isFiltered(body: String): Boolean =
        filters.any { body.contains(it, ignoreCase = true) }

    /**
     * Add a filter. Returns (newConfig, wasAdded) — false if already present.
     */
    fun addFilter(text: String): Pair<SmashConfig, Boolean> {
        val trimmed = text.trim()
        if (filters.any { it.equals(trimmed, ignoreCase = true) }) return this to false
        return copy(filters = filters + trimmed) to true
    }

    /**
     * Remove a filter. Returns (newConfig, wasRemoved).
     */
    fun removeFilter(text: String): Pair<SmashConfig, Boolean> {
        val trimmed = text.trim()
        val updated = filters.filterNot { it.equals(trimmed, ignoreCase = true) }
        return copy(filters = updated) to (updated.size < filters.size)
    }

    /**
     * Check if a specific target has a given flag.
     */
    fun hasFlag(target: String, flag: String): Boolean =
        targetFlags.any { it.target.equals(target, ignoreCase = true) && it.flag.equals(flag, ignoreCase = true) }

    /**
     * Return all targets that have the given flag.
     */
    fun targetsWithFlag(flag: String): List<String> =
        targetFlags.filter { it.flag.equals(flag, ignoreCase = true) }.map { it.target }

    /**
     * Add a flag to a target. Returns (newConfig, wasAdded) — false if already present.
     */
    fun addFlag(target: String, flag: String): Pair<SmashConfig, Boolean> {
        if (hasFlag(target, flag)) return this to false
        return copy(targetFlags = targetFlags + TargetFlag(target, flag)) to true
    }

    /**
     * Remove a flag from a target. Returns (newConfig, wasRemoved).
     */
    fun removeFlag(target: String, flag: String): Pair<SmashConfig, Boolean> {
        val before = targetFlags.size
        val updated = targetFlags.filterNot {
            it.target.equals(target, ignoreCase = true) && it.flag.equals(flag, ignoreCase = true)
        }
        return copy(targetFlags = updated) to (updated.size < before)
    }

    /**
     * Reverse lookup: find alias name for a value (e.g., phone number).
     * Returns the alias name or null if not found.
     * Case-insensitive value matching.
     */
    fun findAliasName(value: String): String? {
        val cleanedValue = PhoneUtils.cleanPhone(value)
        return aliases.entries.firstOrNull { (_, v) ->
            PhoneUtils.cleanPhone(v) == cleanedValue || v.equals(value, ignoreCase = true)
        }?.key
    }

}
