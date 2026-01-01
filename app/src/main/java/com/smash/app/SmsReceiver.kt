package com.smash.app

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Receives incoming SMS messages.
 * 
 * As the default SMS app, we are responsible for:
 * 1. Receiving the SMS_DELIVER broadcast
 * 2. Parsing the PDU data from the intent
 * 3. Writing the message to the system SMS provider (content://sms/inbox)
 * 4. Processing the message (forward, execute command, etc.)
 * 
 * Writing to the provider is required - otherwise messages won't be visible
 * to other apps, won't be backed up, and MessageSyncManager can't find them.
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
        // Also track the earliest timestamp from the PDUs
        val messagesBySender = mutableMapOf<String, StringBuilder>()
        val timestampsBySender = mutableMapOf<String, Long>()
        
        for (smsMessage in messages) {
            val sender = smsMessage.displayOriginatingAddress ?: smsMessage.originatingAddress ?: continue
            val body = smsMessage.displayMessageBody ?: smsMessage.messageBody ?: continue
            
            messagesBySender.getOrPut(sender) { StringBuilder() }.append(body)
            
            // Use the timestamp from the PDU (when carrier sent it)
            val pduTimestamp = smsMessage.timestampMillis
            val existing = timestampsBySender[sender]
            if (existing == null || pduTimestamp < existing) {
                timestampsBySender[sender] = pduTimestamp
            }
        }

        // Create IncomingMessage for each complete message and enqueue
        for ((sender, bodyBuilder) in messagesBySender) {
            val body = bodyBuilder.toString()
            val dateSent = timestampsBySender[sender] ?: System.currentTimeMillis()
            val dateReceived = System.currentTimeMillis()
            
            SmashLogger.verbose("SMS received from $sender: ${body.take(50)}${if (body.length > 50) "..." else ""}")
            
            // Write to system SMS provider (required as default SMS app)
            val insertedId = writeToSmsProvider(context, sender, body, dateReceived, dateSent)
            if (insertedId != null) {
                SmashLogger.verbose("SmsReceiver: wrote to provider id=$insertedId")
            } else {
                SmashLogger.warning("SmsReceiver: failed to write to provider")
            }
            
            val message = IncomingMessage(
                sender = sender,
                body = body,
                timestamp = dateReceived,
                attachments = emptyList()  // SMS has no attachments
            )
            
            // Enqueue to shared processor - start service if not running
            val service = SmashService.getInstance()
            if (service != null) {
                service.enqueueMessage(message)
            } else {
                // Service not ready yet - start it and use pending queue
                SmashLogger.warning("SmsReceiver: SmashService not ready, starting and queuing message")
                SmashService.start(context)
                // Store in application-level pending queue
                SmashApplication.addPendingMessage(message)
            }
        }
    }
    
    /**
     * Write received SMS to the system provider.
     * This is required as the default SMS app - without this, messages
     * won't be visible to other apps or backed up by the system.
     * 
     * @return The ID of the inserted row, or null if insert failed
     */
    private fun writeToSmsProvider(
        context: Context,
        address: String,
        body: String,
        dateReceived: Long,
        dateSent: Long
    ): Long? {
        return try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, dateReceived)
                put(Telephony.Sms.DATE_SENT, dateSent)
                put(Telephony.Sms.READ, 0)  // 0 = unread
                put(Telephony.Sms.SEEN, 0)  // 0 = not seen by user
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
            
            val uri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            
            // Extract the ID from the returned URI
            uri?.lastPathSegment?.toLongOrNull()
        } catch (e: Exception) {
            SmashLogger.error("SmsReceiver: failed to insert into SMS provider", e)
            null
        }
    }
}
