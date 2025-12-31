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
            val success = if (PhoneUtils.isEmail(target)) {
                forwardToEmail(sender, body, timestamp, target, config.mailEndpointUrl)
            } else {
                forwardToPhone(sender, body, target)
            }

            if (success) {
                successCount++
            } else {
                failureCount++
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
            SmashLogger.warning("Cannot forward to $email: mailEndpointUrl not configured")
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
        sender: String,
        body: String,
        phoneNumber: String
    ): Boolean {
        val cleanedNumber = PhoneUtils.cleanPhone(phoneNumber)
        
        if (cleanedNumber.isEmpty()) {
            SmashLogger.error("Cannot forward to '$phoneNumber': invalid phone number")
            return false
        }

        val success = SmsUtils.sendSms(context, cleanedNumber, body)
        
        if (!success) {
            SmashLogger.error("Failed to forward SMS to $cleanedNumber")
        }
        
        return success
    }
}
