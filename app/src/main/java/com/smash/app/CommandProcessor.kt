package com.smash.app

import android.content.Context

/**
 * Result of executing a command.
 */
sealed class CommandResult {
    abstract val reply: String
    
    /**
     * Command executed successfully with a reply message.
     */
    data class Success(override val reply: String) : CommandResult()
    
    /**
     * Command failed with an error reply.
     */
    data class Error(override val reply: String) : CommandResult()
}

/**
 * Processes commands and dispatches to appropriate handlers.
 */
class CommandProcessor(
    private val context: Context,
    private val configManager: ConfigManager
) {
    companion object {
        // Command names
        private const val CMD_LIST = "list"
        private const val CMD_ADD = "add"
        private const val CMD_REMOVE = "remove"
        private const val CMD_PREFIX = "prefix"
        private const val CMD_SETMAIL = "setmail"
        private const val CMD_SEND = "send"
        private const val CMD_LOG = "log"
        private const val CMD_EMAILLOG = "emaillog"
        private const val CMD_HELP = "help"
        private const val CMD_ALIAS = "alias"

        // Reply messages
        const val REPLY_INVALID_COMMAND = "invalid command"
        const val REPLY_ADDED = "added"
        const val REPLY_EXISTS = "exists"
        const val REPLY_REMOVED = "removed"
        const val REPLY_NOT_FOUND = "not found"
        const val REPLY_SENT = "sent"
        const val REPLY_FAILED = "failed"
        const val REPLY_MAIL_ENDPOINT_SET = "mail endpoint set"
        const val REPLY_INVALID_URL = "invalid url"
        const val REPLY_PERSIST_FAILED = "persist failed"
    }

    /**
     * Process a command and return the result.
     * 
     * @param sender The phone number that sent the command
     * @param body The SMS body containing the command
     * @return CommandResult with reply message
     */
    fun process(sender: String, body: String): CommandResult {
        val config = configManager.load()
        
        val parsed = CommandParser.parse(body, config.prefix)
        
        return when (parsed) {
            is ParsedCommand.Invalid -> {
                CommandResult.Error(REPLY_INVALID_COMMAND)
            }
            is ParsedCommand.Valid -> {
                dispatchCommand(parsed.name, parsed.args, sender)
            }
        }
    }

    /**
     * Dispatch to the appropriate command handler.
     */
    private fun dispatchCommand(command: String, args: String, sender: String): CommandResult {
        return when (command) {
            CMD_LIST -> handleList(args)
            CMD_ADD -> handleAdd(args)
            CMD_REMOVE -> handleRemove(args)
            CMD_PREFIX -> handlePrefix(args)
            CMD_SETMAIL -> handleSetMail(args)
            CMD_SEND -> handleSend(args)
            CMD_LOG -> handleLog(args)
            CMD_EMAILLOG -> handleEmailLog(args)
            CMD_HELP -> handleHelp()
            CMD_ALIAS -> handleAlias(args)
            else -> {
                CommandResult.Error(REPLY_INVALID_COMMAND)
            }
        }
    }

    /**
     * HELP command - list all commands with parameters.
     */
    private fun handleHelp(): CommandResult {
        val prefix = configManager.load().prefix
        val help = """
			help
            list [prefix | email | targets | aliases]
            add <target>
            remove <target>
            alias <name> <number> | remove
            prefix <new>
            setmail <url> | disable
            send <name_or_number> <text>
            log [n]
            emaillog <address>
        """.trimIndent()
        
        //SmashLogger.info("HELP command executed")
        return CommandResult.Success(help)
    }

    /**
     * LIST command - show config values.
     * Usage: list [prefix | email | targets | aliases]
     */
    private fun handleList(args: String): CommandResult {
        val config = configManager.load()
        val what = args.trim().lowercase()
        
        return when (what) {
            "prefix" -> {
                CommandResult.Success("prefix=${config.prefix}")
            }
            "email" -> {
                val mailUrl = config.mailEndpointUrl ?: "(not configured)"
                CommandResult.Success("mailEndpointUrl=$mailUrl")
            }
            "targets" -> {
                val text = if (config.targets.isEmpty()) {
                    "(none)"
                } else {
                    config.targets.joinToString("\n")
                }
                CommandResult.Success(text)
            }
            "aliases" -> {
                val text = if (config.aliases.isEmpty()) {
                    "(none)"
                } else {
                    config.aliases.entries.joinToString("\n") { (name, value) -> "$name=$value" }
                }
                CommandResult.Success(text)
            }
            "" -> {
                CommandResult.Success("list [prefix | email | targets | aliases]")
            }
            else -> {
                CommandResult.Error("list [prefix | email | targets | aliases]")
            }
        }
    }

    /**
     * ADD command - add target if not present.
     */
    private fun handleAdd(args: String): CommandResult {
        val target = args.trim()
        
        if (target.isEmpty()) {
            SmashLogger.warning("ADD command with empty target")
            return CommandResult.Error(REPLY_INVALID_COMMAND)
        }

        val (newConfig, wasAdded) = configManager.load().addTarget(target)
        
        if (!wasAdded) {
            return CommandResult.Success(REPLY_EXISTS)
        }

        val saved = configManager.save(newConfig)
        if (!saved) {
            SmashLogger.error("ADD command: failed to save config")
            return CommandResult.Error(REPLY_PERSIST_FAILED)
        }

        SmashLogger.info("ADD command: added '$target'")
        val totalCount = newConfig.targets.size
        return CommandResult.Success("added $target ($totalCount entries total)")
    }

    /**
     * REMOVE command - remove target if present.
     */
    private fun handleRemove(args: String): CommandResult {
        val target = args.trim()
        
        if (target.isEmpty()) {
            SmashLogger.warning("REMOVE command with empty target")
            return CommandResult.Error("nothing specified for removal")
        }

        val (newConfig, wasRemoved) = configManager.load().removeTarget(target)
        
        if (!wasRemoved) {
            return CommandResult.Success("$target not found")
        }

        val saved = configManager.save(newConfig)
        if (!saved) {
            SmashLogger.error("REMOVE command: failed to save config")
            return CommandResult.Error(REPLY_PERSIST_FAILED)
        }

        SmashLogger.info("REMOVE command: removed '$target'")
        val totalCount = newConfig.targets.size
        return CommandResult.Success("removed $target ($totalCount entries total)")
    }

    /**
     * PREFIX command - change the command prefix.
     */
    private fun handlePrefix(args: String): CommandResult {
        val newPrefix = args.trim()
        
        // If empty, prefix is unchanged
        if (newPrefix.isEmpty()) {
            return CommandResult.Error(REPLY_INVALID_COMMAND)
        }

        // Validate prefix characters
        if (!CommandParser.isValidPrefix(newPrefix)) {
            SmashLogger.warning("PREFIX command: invalid characters in '$newPrefix'")
            return CommandResult.Error(REPLY_INVALID_COMMAND)
        }

        val (_, saved) = configManager.update { it.copy(prefix = newPrefix) }
        
        if (!saved) {
            SmashLogger.error("PREFIX command: failed to save config")
            return CommandResult.Error(REPLY_PERSIST_FAILED)
        }

        SmashLogger.info("PREFIX command: prefix changed to '$newPrefix'")
        return CommandResult.Success("prefix set to $newPrefix")
    }

    /**
     * SETMAIL command - set or disable mail endpoint URL.
     */
    private fun handleSetMail(args: String): CommandResult {
        val url = args.trim()
        
        if (url.isEmpty()) {
            SmashLogger.warning("SETMAIL command with empty url")
            return CommandResult.Error(REPLY_INVALID_COMMAND)
        }

        val newUrl: String? = if (url.equals("disable", ignoreCase = true)) {
            null
        } else if (url.startsWith("http://", ignoreCase = true) || 
                   url.startsWith("https://", ignoreCase = true)) {
            url
        } else {
            SmashLogger.warning("SETMAIL command: invalid url '$url'")
            return CommandResult.Error(REPLY_INVALID_URL)
        }

        val (_, saved) = configManager.update { it.copy(mailEndpointUrl = newUrl) }
        
        if (!saved) {
            SmashLogger.error("SETMAIL command: failed to save config")
            return CommandResult.Error(REPLY_PERSIST_FAILED)
        }

        val logMessage = if (newUrl == null) "disabled" else "set to '$newUrl'"
        SmashLogger.info("SETMAIL command: mail endpoint $logMessage")
        return CommandResult.Success(REPLY_MAIL_ENDPOINT_SET)
    }

    /**
     * SEND command - send SMS to specified number or alias.
     */
    private fun handleSend(args: String): CommandResult {
        val parsed = CommandParser.parseSendCommand(args)
        
        if (parsed == null) {
            SmashLogger.warning("SEND command: invalid arguments '$args'")
            return CommandResult.Error(REPLY_INVALID_COMMAND)
        }

        val (numberOrAlias, text) = parsed
        val config = configManager.load()
        
        // Resolve alias if it exists
        val resolved = config.resolveAlias(numberOrAlias)
        val cleanedNumber = PhoneUtils.cleanPhone(resolved)
        
        if (cleanedNumber.isEmpty()) {
            SmashLogger.warning("SEND command: invalid phone number '$numberOrAlias' (resolved to '$resolved')")
            return CommandResult.Error(REPLY_FAILED)
        }

        val success = SmsUtils.sendSms(context, cleanedNumber, text)
        
        return if (success) {
            val logMsg = if (resolved != numberOrAlias) "sent to $numberOrAlias ($cleanedNumber)" else "sent to $cleanedNumber"
            SmashLogger.info("SEND command: $logMsg")
            CommandResult.Success(REPLY_SENT)
        } else {
            SmashLogger.error("SEND command: failed to send to $cleanedNumber")
            CommandResult.Error(REPLY_FAILED)
        }
    }

    /**
     * LOG command - reply with last N lines of log.
     */
    private fun handleLog(args: String): CommandResult {
        val countArg = args.trim()
        
        val count = if (countArg.isEmpty()) {
            20
        } else {
            countArg.toIntOrNull() ?: 20
        }

        val logLines = SmashLogger.getLastLinesAsString(count)
        
        val reply = if (logLines.isEmpty()) {
            "(log is empty)"
        } else {
            logLines
        }

        return CommandResult.Success(reply)
    }

    /**
     * EMAILLOG command - email the log to a specified address.
     */
    private fun handleEmailLog(args: String): CommandResult {
        val email = args.trim()
        
        if (email.isEmpty()) {
            return CommandResult.Error("please specify an address, eg Cmd emaillog address@domain.com")
        }

        val config = configManager.load()
        
        if (config.mailEndpointUrl.isNullOrBlank()) {
            SmashLogger.warning("EMAILLOG command: mailEndpointUrl not configured")
            return CommandResult.Error("mail endpoint not configured, use setmail first")
        }

        val logContent = SmashLogger.getLastLinesAsString(200)
        val body = if (logContent.isEmpty()) {
            "(log is empty)"
        } else {
            logContent
        }

        val success = EmailForwarder.forward(
            endpointUrl = config.mailEndpointUrl,
            origin = "smash-log",
            destinationEmail = email,
            messageBody = body,
            timestamp = System.currentTimeMillis()
        )

        return if (success) {
            SmashLogger.info("EMAILLOG command: sent log to $email")
            CommandResult.Success("log emailed to $email")
        } else {
            SmashLogger.error("EMAILLOG command: failed to send to $email")
            CommandResult.Error("failed to email log")
        }
    }

    /**
     * ALIAS command - set or remove an alias for a phone number.
     * Usage: alias <name> <number> | alias <name> remove
     */
    private fun handleAlias(args: String): CommandResult {
        val parts = args.trim().split(Regex("\\s+"), limit = 2)
        
        if (parts.size < 2) {
            // If just a name, show that alias
            if (parts.size == 1 && parts[0].isNotEmpty()) {
                val config = configManager.load()
                val entry = config.aliases.entries.firstOrNull { (k, _) -> 
                    k.equals(parts[0], ignoreCase = true) 
                }
                return if (entry != null) {
                    CommandResult.Success("${entry.key}=${entry.value}")
                } else {
                    CommandResult.Error("alias '${parts[0]}' not found")
                }
            }
            return CommandResult.Error(REPLY_INVALID_COMMAND)
        }

        val (name, value) = parts
        
        // Handle "alias <name> remove"
        if (value.equals("remove", ignoreCase = true)) {
            val (newConfig, wasRemoved) = configManager.load().removeAlias(name)
            
            if (!wasRemoved) {
                return CommandResult.Error(REPLY_NOT_FOUND)
            }

            val saved = configManager.save(newConfig)
            
            if (!saved) {
                SmashLogger.error("ALIAS command: failed to save config")
                return CommandResult.Error(REPLY_PERSIST_FAILED)
            }

            SmashLogger.info("ALIAS command: removed '$name'")
            return CommandResult.Success(REPLY_REMOVED)
        }
        
        // Don't allow alias names that look like phone numbers
        if (name.any { it.isDigit() }) {
            return CommandResult.Error("alias name should not contain digits")
        }
        
        // Don't allow email addresses as alias values
        if (value.contains('@')) {
            return CommandResult.Error("alias only supports phone numbers")
        }

        val newConfig = configManager.load().setAlias(name, value)
        val saved = configManager.save(newConfig)
        
        if (!saved) {
            SmashLogger.error("ALIAS command: failed to save config")
            return CommandResult.Error(REPLY_PERSIST_FAILED)
        }

        SmashLogger.info("ALIAS command: set $name=${value}")
        return CommandResult.Success("alias $name set")
    }

}
