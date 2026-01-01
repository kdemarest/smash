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
        private const val CMD_ENDPOINT = "endpoint"
        private const val CMD_SEND = "send"
        private const val CMD_LOG = "log"
        private const val CMD_EMAILLOG = "emaillog"
        private const val CMD_HELP = "help"
        private const val CMD_ALIAS = "alias"
        private const val CMD_TESTMMS = "testmms"
        private const val CMD_VERBOSE = "verbose"
        private const val CMD_BAN = "ban"

        // Reply messages
        const val REPLY_INVALID_COMMAND = "invalid command"
        const val REPLY_ADDED = "added"
        const val REPLY_EXISTS = "exists"
        const val REPLY_REMOVED = "removed"
        const val REPLY_NOT_FOUND = "not found"
        const val REPLY_SENT = "sent"
        const val REPLY_FAILED = "failed"
        const val REPLY_ENDPOINT_SET = "endpoint set"
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
                SmashLogger.info("[Cmd] $body -> invalid")
                CommandResult.Error(REPLY_INVALID_COMMAND)
            }
            is ParsedCommand.Valid -> {
                val result = dispatchCommand(parsed.name, parsed.args, sender)
                SmashLogger.info("[Cmd] ${parsed.name} -> ${result.reply.take(50)}")
                result
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
            CMD_ENDPOINT -> handleEndpoint(args)
            CMD_SEND -> handleSend(args)
            CMD_LOG -> handleLog(args)
            CMD_EMAILLOG -> handleEmailLog(args)
            CMD_HELP -> handleHelp()
            CMD_ALIAS -> handleAlias(args)
            CMD_TESTMMS -> handleTestMms(args)
            CMD_VERBOSE -> handleVerbose(args)
            CMD_BAN -> handleBan(args)
            else -> {
                CommandResult.Error(REPLY_INVALID_COMMAND)
            }
        }
    }

    /**
     * HELP command - list all commands with parameters.
     */
    private fun handleHelp(): CommandResult {
        val help = listOf(
            "help",
            "list [prefix/endpoints/targets/aliases/blocked]",
            "add <target>",
            "remove <target>",
            "alias <name> <number/remove>",
            "ban [list] | ban <number> | ban remove <number>",
            "prefix <new>",
            "endpoint email|log <url/disable>",
            "send <name_or_number> <text>",
            "log [n/trim]",
            "emaillog <address>",
            "verbose 0|1",
            "testmms <number>"
        ).joinToString("\n")
        
        return CommandResult.Success(help)
    }

    /**
     * LIST command - show config values.
     * Usage: list [prefix | endpoints | targets | aliases | blocked]
     */
    private fun handleList(args: String): CommandResult {
        val config = configManager.load()
        val what = args.trim().lowercase()
        
        return when (what) {
            "prefix" -> {
                CommandResult.Success("prefix=${config.prefix}")
            }
            "endpoints" -> {
                val emailUrl = config.mailEndpointUrl ?: "(not set)"
                val logUrl = config.logEndpointUrl ?: "(not set)"
                CommandResult.Success("email=$emailUrl\nlog=$logUrl")
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
            "blocked" -> {
                // Show last 10 blocked numbers
                val blocked = BlockedNumbersHelper.getBlockedNumbersRecent(context, 10)
                val total = BlockedNumbersHelper.getBlockedCount(context)
                val text = if (blocked.isEmpty()) {
                    "(none)"
                } else {
                    val suffix = if (total > 10) "\n($total total)" else ""
                    blocked.joinToString("\n") + suffix
                }
                CommandResult.Success(text)
            }
            "" -> {
                CommandResult.Success("list [prefix | endpoints | targets | aliases | blocked]")
            }
            else -> {
                CommandResult.Error("list [prefix | endpoints | targets | aliases | blocked]")
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

        SmashLogger.verbose("ADD command: added '$target'")
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

        SmashLogger.verbose("REMOVE command: removed '$target'")
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

        SmashLogger.verbose("PREFIX command: prefix changed to '$newPrefix'")
        return CommandResult.Success("prefix set to $newPrefix")
    }

    /**
     * ENDPOINT command - set or disable email/log endpoint URLs.
     * Usage: endpoint email|log <url|disable>
     */
    private fun handleEndpoint(args: String): CommandResult {
        val parts = args.trim().split("\\s+".toRegex(), limit = 2)
        val type = parts.getOrNull(0)?.lowercase() ?: ""
        val url = parts.getOrNull(1)?.trim() ?: ""
        
        if (type.isEmpty() || url.isEmpty()) {
            return CommandResult.Error("usage: endpoint email|log <url|disable>")
        }
        
        if (type != "email" && type != "log") {
            return CommandResult.Error("usage: endpoint email|log <url|disable>")
        }

        val newUrl: String? = if (url.equals("disable", ignoreCase = true)) {
            null
        } else if (url.startsWith("http://", ignoreCase = true) || 
                   url.startsWith("https://", ignoreCase = true)) {
            url
        } else {
            SmashLogger.warning("ENDPOINT command: invalid url '$url'")
            return CommandResult.Error(REPLY_INVALID_URL)
        }

        val (_, saved) = when (type) {
            "email" -> configManager.update { it.copy(mailEndpointUrl = newUrl) }
            "log" -> configManager.update { it.copy(logEndpointUrl = newUrl) }
            else -> return CommandResult.Error(REPLY_INVALID_COMMAND)
        }
        
        if (!saved) {
            SmashLogger.error("ENDPOINT command: failed to save config")
            return CommandResult.Error(REPLY_PERSIST_FAILED)
        }

        val status = if (newUrl == null) "disabled" else "set"
        SmashLogger.verbose("ENDPOINT command: $type endpoint $status")
        return CommandResult.Success("$type endpoint $status")
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
            SmashLogger.verbose("SEND command: $logMsg")
            CommandResult.Success(REPLY_SENT)
        } else {
            SmashLogger.error("SEND command: failed to send to $cleanedNumber")
            CommandResult.Error(REPLY_FAILED)
        }
    }

    /**
     * LOG command - reply with last N lines of log, or trim.
     */
    private fun handleLog(args: String): CommandResult {
        val countArg = args.trim()
        
        // Handle "log trim"
        if (countArg.equals("trim", ignoreCase = true)) {
            SmashLogger.trim(200)
            return CommandResult.Success("log trimmed to 200 entries")
        }
        
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
            SmashLogger.verbose("EMAILLOG command: sent log to $email")
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

            SmashLogger.verbose("ALIAS command: removed '$name'")
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

        SmashLogger.verbose("ALIAS command: set $name=${value}")
        return CommandResult.Success("alias $name set")
    }

    /**
     * TESTMMS command - send a test MMS with an image.
     * Usage: testmms <phone_number>
     * Sends testImg.jpg from assets with test text.
     */
    private fun handleTestMms(args: String): CommandResult {
        val destination = args.trim()
        if (destination.isEmpty()) {
            return CommandResult.Error("usage: testmms <phone_number>")
        }

        val cleanedNumber = PhoneUtils.cleanPhone(destination)
        if (cleanedNumber.isEmpty()) {
            return CommandResult.Error("invalid phone number: $destination")
        }

        return try {
            // Load test image from assets
            val imageBytes = context.assets.open("testImg.jpg").use { it.readBytes() }
            SmashLogger.verbose("TESTMMS: loaded ${imageBytes.size} bytes from testImg.jpg")

            // Create attachment
            val attachment = MediaAttachment(
                uri = android.net.Uri.EMPTY,
                mimeType = "image/jpeg",
                data = imageBytes
            )

            // Send the MMS
            val testText = "Test MMS from Smash @ ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}"
            val success = MmsUtils.sendMms(context, cleanedNumber, testText, listOf(attachment))

            if (success) {
                SmashLogger.verbose("TESTMMS: MMS send initiated to $cleanedNumber")
                CommandResult.Success("MMS test sent to $cleanedNumber")
            } else {
                CommandResult.Error("MMS send failed")
            }
        } catch (e: Exception) {
            SmashLogger.error("TESTMMS: failed", e)
            CommandResult.Error("error: ${e.message}")
        }
    }

    /**
     * VERBOSE command - toggle verbose logging.
     * Usage: verbose 0|1
     */
    private fun handleVerbose(args: String): CommandResult {
        val value = args.trim()
        
        return when (value) {
            "1", "on", "true" -> {
                val config = configManager.load()
                val newConfig = config.copy(verbose = true)
                if (configManager.save(newConfig)) {
                    SmashLogger.isVerbose = true
                    SmashLogger.info("verbose logging enabled")
                    CommandResult.Success("verbose on")
                } else {
                    CommandResult.Error(REPLY_PERSIST_FAILED)
                }
            }
            "0", "off", "false" -> {
                val config = configManager.load()
                val newConfig = config.copy(verbose = false)
                if (configManager.save(newConfig)) {
                    SmashLogger.info("verbose logging disabled")
                    SmashLogger.isVerbose = false
                    CommandResult.Success("verbose off")
                } else {
                    CommandResult.Error(REPLY_PERSIST_FAILED)
                }
            }
            "" -> {
                val current = if (SmashLogger.isVerbose) "on" else "off"
                CommandResult.Success("verbose: $current")
            }
            else -> {
                CommandResult.Error("usage: verbose 0|1")
            }
        }
    }

    /**
     * BAN command - ban/unban phone numbers from forwarding.
     * Usage: ban <number> | ban remove <number> | ban list
     */
    private fun handleBan(args: String): CommandResult {
        val parts = args.trim().split("\\s+".toRegex(), limit = 2)
        val firstArg = parts.getOrNull(0)?.lowercase() ?: ""
        
        return when {
            firstArg.isEmpty() || firstArg == "list" -> {
                // List last 10 blocked numbers (most recent first)
                val blocked = BlockedNumbersHelper.getBlockedNumbersRecent(context, 10)
                val total = BlockedNumbersHelper.getBlockedCount(context)
                val text = if (blocked.isEmpty()) {
                    "(none blocked)"
                } else {
                    val header = if (total > 10) "showing last 10 of $total:\n" else ""
                    header + blocked.joinToString("\n")
                }
                CommandResult.Success(text)
            }
            firstArg == "remove" -> {
                // Unblock a number
                val number = parts.getOrNull(1)?.trim() ?: ""
                if (number.isEmpty()) {
                    return CommandResult.Error("usage: ban remove <number>")
                }
                val result = BlockedNumbersHelper.unblockNumber(context, number)
                when (result) {
                    BlockedNumbersHelper.BlockResult.SUCCESS -> {
                        SmashLogger.verbose("BAN command: unblocked '$number'")
                        CommandResult.Success("unblocked")
                    }
                    BlockedNumbersHelper.BlockResult.NOT_FOUND -> {
                        CommandResult.Success(REPLY_NOT_FOUND)
                    }
                    BlockedNumbersHelper.BlockResult.NO_ACCESS -> {
                        CommandResult.Error("no access (not default SMS app?)")
                    }
                    BlockedNumbersHelper.BlockResult.ERROR,
                    BlockedNumbersHelper.BlockResult.ALREADY_EXISTS -> {
                        CommandResult.Error(REPLY_FAILED)
                    }
                }
            }
            else -> {
                // Block a number
                val number = firstArg
                val result = BlockedNumbersHelper.blockNumber(context, number)
                when (result) {
                    BlockedNumbersHelper.BlockResult.SUCCESS -> {
                        SmashLogger.verbose("BAN command: blocked '$number'")
                        CommandResult.Success("blocked")
                    }
                    BlockedNumbersHelper.BlockResult.ALREADY_EXISTS -> {
                        CommandResult.Success("already blocked")
                    }
                    BlockedNumbersHelper.BlockResult.NO_ACCESS -> {
                        CommandResult.Error("no access (not default SMS app?)")
                    }
                    BlockedNumbersHelper.BlockResult.ERROR -> {
                        CommandResult.Error(REPLY_FAILED)
                    }
                    else -> CommandResult.Error(REPLY_FAILED)
                }
            }
        }
    }
}

