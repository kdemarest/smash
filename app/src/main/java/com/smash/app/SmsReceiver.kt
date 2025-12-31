package com.smash.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage

/**
 * Receives incoming SMS messages.
 * Extracts sender and body, passes to SmashService for processing.
 * 
 * As the default SMS app, we only handle SMS_DELIVER_ACTION to avoid
 * processing messages twice (SMS_RECEIVED is also broadcast but we ignore it).
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

        // Process each complete message
        for ((sender, bodyBuilder) in messagesBySender) {
            val body = bodyBuilder.toString()
            SmashLogger.info("SMS received from $sender: ${body.take(50)}${if (body.length > 50) "..." else ""}")
            
            val incomingSms = IncomingSms(
                sender = sender,
                body = body,
                timestamp = System.currentTimeMillis()
            )
            
            // Queue for processing via the service
            SmashService.getInstance()?.processSms(incomingSms)
                ?: SmashLogger.error("SmsReceiver: SmashService not running, cannot process SMS")
        }
    }
}
