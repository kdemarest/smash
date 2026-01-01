package com.smash.app

import android.content.Context
import android.telephony.SmsManager
import android.os.Build

/**
 * Utility for sending SMS messages.
 * Handles message splitting for long messages (>160 chars).
 * 
 * Note: Smash is a repeater/forwarder, NOT a user messaging app.
 * We intentionally do NOT persist sent SMS to content://sms/sent.
 * These are automated forwards, not user-composed messages.
 */
object SmsUtils {

    private const val MAX_SMS_LENGTH = 160

    /**
     * Send an SMS message to the specified phone number.
     * Automatically splits long messages into multiple parts.
     * 
     * @param context Android context
     * @param destination Phone number to send to (will be cleaned)
     * @param message Message text to send
     * @return true if send was initiated successfully, false on error
     */
    fun sendSms(context: Context, destination: String, message: String): Boolean {
        val cleanedNumber = PhoneUtils.cleanPhone(destination)
        if (cleanedNumber.isEmpty()) {
            SmashLogger.error("sendSms failed: empty destination after cleaning '$destination'")
            return false
        }

        if (message.isEmpty()) {
            SmashLogger.error("sendSms failed: empty message")
            return false
        }

        return try {
            val smsManager = getSmsManager(context)
            
            if (message.length <= MAX_SMS_LENGTH) {
                // Single SMS
                smsManager.sendTextMessage(
                    cleanedNumber,
                    null, // service center - use default
                    message,
                    null, // sent intent
                    null  // delivery intent
                )
            } else {
                // Multipart SMS
                val parts = smsManager.divideMessage(message)
                smsManager.sendMultipartTextMessage(
                    cleanedNumber,
                    null, // service center
                    parts,
                    null, // sent intents
                    null  // delivery intents
                )
            }
            
            true
        } catch (e: SecurityException) {
            SmashLogger.error("FORWARD FAILED to $cleanedNumber: SMS permission denied - ${e.message}")
            false
        } catch (e: IllegalArgumentException) {
            SmashLogger.error("FORWARD FAILED to $cleanedNumber: Invalid phone number or message - ${e.message}")
            false
        } catch (e: Exception) {
            SmashLogger.error("FORWARD FAILED to $cleanedNumber: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * Send a reply SMS, automatically splitting into multiple messages if needed.
     * This is used for command replies that may exceed 160 characters.
     * 
     * @param context Android context
     * @param destination Phone number to reply to
     * @param message Reply message (may be longer than 160 chars)
     * @return true if all parts sent successfully
     */
    fun sendReply(context: Context, destination: String, message: String): Boolean {
        val cleanedNumber = PhoneUtils.cleanPhone(destination)
        if (cleanedNumber.isEmpty()) {
            SmashLogger.error("sendReply failed: empty destination")
            return false
        }

        // For replies longer than 160 chars, we send multiple separate SMS messages
        // (not multipart, but individual messages) as per spec
        val messages = splitIntoMessages(message)
        var allSuccess = true

        for ((index, part) in messages.withIndex()) {
            val success = sendSms(context, cleanedNumber, part)
            if (!success) {
                SmashLogger.error("sendReply part ${index + 1}/${messages.size} failed")
                allSuccess = false
            }
        }

        return allSuccess
    }

    /**
     * Split a long message into multiple SMS-sized chunks.
     * Preserves word boundaries when possible.
     */
    private fun splitIntoMessages(message: String): List<String> {
        if (message.length <= MAX_SMS_LENGTH) {
            return listOf(message)
        }

        val messages = mutableListOf<String>()
        var remaining = message

        while (remaining.isNotEmpty()) {
            if (remaining.length <= MAX_SMS_LENGTH) {
                messages.add(remaining)
                break
            }

            // Find a good break point (space, newline) near the limit
            var breakPoint = MAX_SMS_LENGTH
            for (i in MAX_SMS_LENGTH downTo MAX_SMS_LENGTH - 20) {
                if (i < remaining.length && (remaining[i] == ' ' || remaining[i] == '\n')) {
                    breakPoint = i
                    break
                }
            }

            messages.add(remaining.substring(0, breakPoint))
            remaining = remaining.substring(breakPoint).trimStart()
        }

        return messages
    }

    /**
     * Get the SmsManager instance.
     */
    @Suppress("DEPRECATION")
    private fun getSmsManager(context: Context): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
    }
}
