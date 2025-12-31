package com.smash.app

import android.content.Context

/**
 * Result of forwarding to all targets.
 */
data class ForwardResult(
    val totalTargets: Int,
    val successCount: Int,
    val failureCount: Int
) {
    val allSuccessful: Boolean get() = failureCount == 0
}

/**
 * Forwards incoming SMS/MMS messages to configured targets.
 * - Phone targets: sends SMS via SmsUtils (images not yet supported for phone targets)
 * - Email targets: POSTs to mailEndpointUrl via EmailForwarder (includes images)
 */
class MessageForwarder(private val context: Context) {

    /**
     * Forward a message to all configured targets.
     *
     * @param message The incoming message (SMS or MMS)
     * @param config The current SmashConfig
     * @return ForwardResult with success/failure counts
     */
    fun forward(
        message: IncomingMessage,
        config: SmashConfig
    ): ForwardResult {
        val targets = config.targets
        
        if (targets.isEmpty()) {
            return ForwardResult(0, 0, 0)
        }

        var successCount = 0
        var failureCount = 0

        for (target in targets) {
            val isEmail = PhoneUtils.isEmail(target)
            
            val success = if (isEmail) {
                val displayName = ContactsHelper.getDisplayName(context, message.sender, config)
                forwardToEmail(displayName, message, target, config.mailEndpointUrl)
            } else {
                val displayName = ContactsHelper.getDisplayNameShort(context, message.sender, config)
                forwardToPhone(displayName, message, target)
            }

            if (success) {
                successCount++
            } else {
                failureCount++
                // Detailed error already logged by forwardToEmail/forwardToPhone
            }
        }

        return ForwardResult(targets.size, successCount, failureCount)
    }

    /**
     * Legacy method for backward compatibility with SMS-only code paths.
     */
    fun forward(
        sender: String,
        body: String,
        timestamp: Long,
        config: SmashConfig
    ): ForwardResult {
        return forward(
            message = IncomingMessage(sender = sender, body = body, timestamp = timestamp),
            config = config
        )
    }

    /**
     * Forward to an email target via HTTP POST.
     * Includes image attachments if present.
     */
    private fun forwardToEmail(
        sender: String,
        message: IncomingMessage,
        email: String,
        mailEndpointUrl: String?
    ): Boolean {
        if (mailEndpointUrl.isNullOrBlank()) {
            SmashLogger.error("FORWARD FAILED to $email: mailEndpointUrl is not configured! Use 'Cmd setmail <url>' to set it.")
            return false
        }
        
        // Check if we have image attachments
        val imageAttachments = message.imageAttachments
        
        return if (imageAttachments.isNotEmpty()) {
            // Forward with images
            EmailForwarder.forwardWithAttachments(
                context = context,
                endpointUrl = mailEndpointUrl,
                origin = sender,
                destinationEmail = email,
                messageBody = message.body,
                timestamp = message.timestamp,
                attachments = imageAttachments
            )
        } else {
            // Forward text only
            EmailForwarder.forward(
                endpointUrl = mailEndpointUrl,
                origin = sender,
                destinationEmail = email,
                messageBody = message.body,
                timestamp = message.timestamp
            )
        }
    }

    /**
     * Forward to a phone target via SMS.
     * Note: Images are not forwarded to phone targets (would require MMS sending).
     */
    private fun forwardToPhone(
        displayName: String,
        message: IncomingMessage,
        phoneNumber: String
    ): Boolean {
        val cleanedNumber = PhoneUtils.cleanPhone(phoneNumber)
        
        if (cleanedNumber.isEmpty()) {
            SmashLogger.error("FORWARD FAILED to '$phoneNumber': not a valid phone number (cleaned to empty string)")
            return false
        }

        // Build the message body
        var prefixedBody = "$displayName: ${message.body}"
        
        // If there were images but we can't forward them, note it
        if (message.hasImages) {
            val imageCount = message.imageAttachments.size
            prefixedBody += " [${imageCount} image${if (imageCount > 1) "s" else ""} not forwarded]"
        }
        
        return SmsUtils.sendSms(context, cleanedNumber, prefixedBody)
    }
}
