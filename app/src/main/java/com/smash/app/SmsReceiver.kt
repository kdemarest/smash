package com.smash.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Receives incoming SMS messages.
 * Extracts sender and body, creates IncomingMessage, and enqueues
 * to the shared MessageProcessor.
 * 
 * SMS data is embedded directly in the intent, so extraction is
 * immediate and reliable - no database queries needed.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Only handle SMS_DELIVER_ACTION - as default SMS app we receive both
        // SMS_RECEIVED and SMS_DELIVER, but we only want to process once
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) {
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) {
            SmashLogger.warning("SmsReceiver: no messages in intent")
            return
        }

        // Group message parts by sender (for multipart SMS)
        val messagesBySender = mutableMapOf<String, StringBuilder>()
        
        for (smsMessage in messages) {
            val sender = smsMessage.displayOriginatingAddress ?: smsMessage.originatingAddress ?: continue
            val body = smsMessage.displayMessageBody ?: smsMessage.messageBody ?: continue
            
            messagesBySender.getOrPut(sender) { StringBuilder() }.append(body)
        }

        // Create IncomingMessage for each complete message and enqueue
        for ((sender, bodyBuilder) in messagesBySender) {
            val body = bodyBuilder.toString()
            SmashLogger.verbose("SMS received from $sender: ${body.take(50)}${if (body.length > 50) "..." else ""}\")
            
            val message = IncomingMessage(
                sender = sender,
                body = body,
                timestamp = System.currentTimeMillis(),
                attachments = emptyList()  // SMS has no attachments
            )
            
            // Enqueue to shared processor
            SmashService.getInstance()?.enqueueMessage(message)
                ?: SmashLogger.error("SmsReceiver: SmashService not running, cannot process SMS")
        }
    }
}
