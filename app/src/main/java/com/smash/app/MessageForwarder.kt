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
 * Forwards incoming SMS messages to configured targets.
 * - Phone targets: sends SMS via SmsUtils
 * - Email targets: POSTs to mailEndpointUrl via EmailForwarder
 */
class MessageForwarder(private val context: Context) {

    /**
     * Forward a message to all configured targets.
     *
     * @param sender The original sender's phone number
     * @param body The SMS body to forward
     * @param timestamp The message timestamp
     * @param config The current SmashConfig
     * @return ForwardResult with success/failure counts
     */
    fun forward(
        sender: String,
        body: String,
        timestamp: Long,
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
            //SmashLogger.info("Processing target: $target (isEmail=$isEmail)")
            
            val success = if (isEmail) {
                val displayName = ContactsHelper.getDisplayName(context, sender, config)
                forwardToEmail(displayName, body, timestamp, target, config.mailEndpointUrl)
            } else {
                val displayName = ContactsHelper.getDisplayNameShort(context, sender, config)
                forwardToPhone(displayName, body, target)
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
     * Forward to an email target via HTTP POST.
     */
    private fun forwardToEmail(
        sender: String,
        body: String,
        timestamp: Long,
        email: String,
        mailEndpointUrl: String?
    ): Boolean {
        if (mailEndpointUrl.isNullOrBlank()) {
            SmashLogger.error("FORWARD FAILED to $email: mailEndpointUrl is not configured! Use 'Cmd setmail <url>' to set it.")
            return false
        }
        
        return EmailForwarder.forward(
            endpointUrl = mailEndpointUrl,
            origin = sender,
            destinationEmail = email,
            messageBody = body,
            timestamp = timestamp
        )
    }

    /**
     * Forward to a phone target via SMS.
     */
    private fun forwardToPhone(
        displayName: String,
        body: String,
        phoneNumber: String
    ): Boolean {
        val cleanedNumber = PhoneUtils.cleanPhone(phoneNumber)
        
        if (cleanedNumber.isEmpty()) {
            SmashLogger.error("FORWARD FAILED to '$phoneNumber': not a valid phone number (cleaned to empty string)")
            return false
        }

        val prefixedBody = "$displayName: $body"
        return SmsUtils.sendSms(context, cleanedNumber, prefixedBody)
    }
}
