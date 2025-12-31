package com.smash.app

/**
 * Represents a parsed command from an SMS message.
 */
sealed class ParsedCommand {
    /**
     * A valid command with name and optional arguments.
     */
    data class Valid(
        val name: String,
        val args: String
    ) : ParsedCommand()

    /**
     * Invalid command (prefix present but command is empty or unrecognized).
     */
    object Invalid : ParsedCommand()
}

/**
 * Parser for SMS command messages.
 * 
 * Syntax: "<prefix> <command> <data>"
 * - Commands are case-insensitive
 * - Leading/trailing whitespace is trimmed
 * - Multiple spaces inside <text> are preserved
 */
object CommandParser {

    // Valid characters for prefix: a-zA-Z0-9 or !@#$*
    private val VALID_PREFIX_CHARS = Regex("^[a-zA-Z0-9!@#\$*]+$")

    /**
     * Check if a message body starts with the command prefix.
     */
    fun isCommand(body: String, prefix: String): Boolean {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || prefix.isEmpty()) return false
        
        // Check if starts with prefix (case-insensitive)
        if (!trimmed.startsWith(prefix, ignoreCase = true)) return false
        
        // After prefix, must be end of string or whitespace
        val afterPrefix = trimmed.substring(prefix.length)
        return afterPrefix.isEmpty() || afterPrefix[0].isWhitespace()
    }

    /**
     * Parse a command message.
     * 
     * @param body The SMS body text
     * @param prefix The current command prefix
     * @return ParsedCommand - either Valid with name/args or Invalid
     */
    fun parse(body: String, prefix: String): ParsedCommand {
        // Trim leading/trailing whitespace from the body
        val trimmed = body.trim()
        
        // Remove the prefix
        val afterPrefix = trimmed.substring(prefix.length).trimStart()
        
        if (afterPrefix.isEmpty()) {
            return ParsedCommand.Invalid
        }

        // Split into command and arguments
        val spaceIndex = afterPrefix.indexOfFirst { it.isWhitespace() }
        
        return if (spaceIndex == -1) {
            // No arguments, just command
            ParsedCommand.Valid(
                name = afterPrefix.lowercase(),
                args = ""
            )
        } else {
            // Command and arguments
            val command = afterPrefix.substring(0, spaceIndex).lowercase()
            // Preserve whitespace in args but trim leading whitespace
            val args = afterPrefix.substring(spaceIndex).trimStart()
            
            ParsedCommand.Valid(
                name = command,
                args = args
            )
        }
    }

    /**
     * Parse the "send" command which has special argument handling.
     * Syntax: "send <number> <text>"
     * The <text> may contain spaces and newlines.
     * 
     * @return Triple of (command, number, text) or null if invalid
     */
    fun parseSendCommand(args: String): Pair<String, String>? {
        val trimmed = args.trim()
        if (trimmed.isEmpty()) return null
        
        val spaceIndex = trimmed.indexOfFirst { it.isWhitespace() }
        if (spaceIndex == -1) {
            // Only number, no text
            return null
        }
        
        val number = trimmed.substring(0, spaceIndex)
        val text = trimmed.substring(spaceIndex).trimStart()
        
        if (number.isEmpty() || text.isEmpty()) return null
        
        return number to text
    }

    /**
     * Validate a new prefix value.
     * Valid characters are: a-zA-Z0-9 or !@#$*
     * 
     * @return true if valid, false otherwise
     */
    fun isValidPrefix(prefix: String): Boolean {
        if (prefix.isEmpty()) return false
        return VALID_PREFIX_CHARS.matches(prefix)
    }
}
