package com.smash.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import com.google.android.mms.pdu_alt.PduParser
import com.google.android.mms.pdu_alt.PduPersister
import com.google.android.mms.pdu_alt.RetrieveConf
import java.io.File

/**
 * Receives the result of MMS download operations.
 * This is called when downloadMultimediaMessage() completes.
 * 
 * As the default SMS app, we must parse the downloaded PDU ourselves.
 * The system does NOT automatically populate content://mms/inbox.
 */
class MmsDownloadReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        val resultCode = resultCode
        
        when (resultCode) {
            Activity.RESULT_OK -> {
                SmashLogger.verbose("MmsDownloadReceiver: MMS download completed successfully")
                
                // Get the PDU file path from the intent
                val pduPath = intent.getStringExtra("pdu_path")
                if (pduPath == null) {
                    SmashLogger.error("MmsDownloadReceiver: no pdu_path in intent")
                    return
                }
                
                // Parse the PDU file directly
                val message = parsePduFile(context, pduPath)
                if (message != null) {
                    SmashLogger.verbose("MmsDownloadReceiver: parsed MMS from ${message.sender} with ${message.attachments.size} attachments")
                    SmashService.getInstance()?.enqueueMessage(message)
                } else {
                    SmashLogger.error("MmsDownloadReceiver: failed to parse PDU file")
                }
            }
            else -> {
                // MMS error codes (different from SMS error codes!)
                val errorMessage = when (resultCode) {
                    SmsManager.MMS_ERROR_UNSPECIFIED -> "MMS_ERROR_UNSPECIFIED"
                    SmsManager.MMS_ERROR_INVALID_APN -> "MMS_ERROR_INVALID_APN"
                    SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS -> "MMS_ERROR_UNABLE_CONNECT_MMS"
                    SmsManager.MMS_ERROR_HTTP_FAILURE -> "MMS_ERROR_HTTP_FAILURE"
                    SmsManager.MMS_ERROR_IO_ERROR -> "MMS_ERROR_IO_ERROR"
                    SmsManager.MMS_ERROR_RETRY -> "MMS_ERROR_RETRY"
                    SmsManager.MMS_ERROR_CONFIGURATION_ERROR -> "MMS_ERROR_CONFIGURATION_ERROR"
                    SmsManager.MMS_ERROR_NO_DATA_NETWORK -> "MMS_ERROR_NO_DATA_NETWORK"
                    else -> "Unknown error code: $resultCode"
                }
                SmashLogger.error("MmsDownloadReceiver: MMS download failed - $errorMessage (code $resultCode)")
                
                // Log HTTP status if available
                val httpStatus = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, -1)
                if (httpStatus != -1) {
                    SmashLogger.error("MmsDownloadReceiver: HTTP status: $httpStatus")
                }
            }
        }
    }
    
    /**
     * Parse the downloaded PDU file and extract an IncomingMessage.
     */
    private fun parsePduFile(context: Context, pduPath: String): IncomingMessage? {
        val pduFile = File(pduPath)
        if (!pduFile.exists()) {
            SmashLogger.error("MmsDownloadReceiver: PDU file does not exist: $pduPath")
            return null
        }
        
        val pduBytes = try {
            pduFile.readBytes()
        } catch (e: Exception) {
            SmashLogger.error("MmsDownloadReceiver: failed to read PDU file", e)
            return null
        }
        
        SmashLogger.verbose("MmsDownloadReceiver: parsing ${pduBytes.size} bytes from PDU file")
        
        val pdu = try {
            PduParser(pduBytes, true).parse()
        } catch (e: Exception) {
            SmashLogger.error("MmsDownloadReceiver: failed to parse PDU", e)
            return null
        }
        
        if (pdu == null) {
            SmashLogger.error("MmsDownloadReceiver: PDU parse returned null")
            return null
        }
        
        // Downloaded MMS should be a RetrieveConf
        if (pdu !is RetrieveConf) {
            SmashLogger.error("MmsDownloadReceiver: unexpected PDU type: ${pdu.javaClass.simpleName}")
            return null
        }
        
        // Persist the MMS to the system provider (content://mms/inbox)
        // This is required for default SMS app compliance - otherwise messages
        // won't appear in other apps or system backups.
        persistMmsToProvider(context, pdu)
        
        // Extract sender
        val fromAddress = pdu.from
        val sender = if (fromAddress != null) {
            val senderStr = fromAddress.string
            // Filter out garbage placeholder values
            if (senderStr.isNullOrBlank() || 
                senderStr == "insert-address-token" ||
                senderStr.contains("insert-address", ignoreCase = true)) {
                SmashLogger.warning("MmsDownloadReceiver: invalid sender: $senderStr")
                null
            } else {
                // Clean up the address - often has /TYPE=PLMN suffix
                senderStr.substringBefore("/")
            }
        } else null
        
        if (sender == null) {
            SmashLogger.error("MmsDownloadReceiver: no valid sender in PDU")
            return null
        }
        
        SmashLogger.verbose("MmsDownloadReceiver: sender=$sender")
        
        // Extract body and attachments from PduBody
        val pduBody = pdu.body
        var textBody = ""
        val attachments = mutableListOf<MediaAttachment>()
        
        if (pduBody != null) {
            for (i in 0 until pduBody.partsNum) {
                val part = pduBody.getPart(i)
                val contentType = String(part.contentType ?: continue)
                
                SmashLogger.verbose("MmsDownloadReceiver: part $i contentType=$contentType")
                
                when {
                    contentType == "text/plain" -> {
                        val textBytes = part.data
                        if (textBytes != null) {
                            textBody = String(textBytes, Charsets.UTF_8)
                            SmashLogger.verbose("MmsDownloadReceiver: text body: $textBody")
                        }
                    }
                    contentType.startsWith("image/") -> {
                        val imageData = part.data
                        if (imageData != null && imageData.isNotEmpty()) {
                            attachments.add(MediaAttachment(
                                uri = Uri.EMPTY,  // Not needed - we have the data directly
                                mimeType = contentType,
                                data = imageData
                            ))
                            SmashLogger.verbose("MmsDownloadReceiver: image attachment ${imageData.size} bytes, type=$contentType")
                        } else {
                            SmashLogger.warning("MmsDownloadReceiver: image part has no data (contentType=$contentType)")
                        }
                    }
                }
            }
        }
        
        // Clean up the PDU file
        try {
            pduFile.delete()
        } catch (e: Exception) {
            SmashLogger.warning("MmsDownloadReceiver: failed to delete PDU file: ${e.message}")
        }
        
        return IncomingMessage(
            sender = sender,
            body = textBody,
            timestamp = System.currentTimeMillis(),
            attachments = attachments
        )
    }
    
    /**
     * Persist the downloaded MMS to the system MMS provider.
     * This writes to content://mms/inbox with all parts and addresses.
     * 
     * PduPersister handles the complex multi-table structure:
     * - mms table: message metadata
     * - part table: text/media parts  
     * - addr table: from/to addresses
     */
    private fun persistMmsToProvider(context: Context, pdu: RetrieveConf) {
        try {
            val persister = PduPersister.getPduPersister(context)
            
            // Get the default subscription ID for multi-SIM support
            val subId = SubscriptionManager.getDefaultSmsSubscriptionId()
            
            val messageUri = persister.persist(
                pdu,                                    // The parsed PDU
                Telephony.Mms.Inbox.CONTENT_URI,       // Store in inbox
                true,                                   // Create thread ID
                true,                                   // Group MMS enabled
                null,                                   // No pre-opened files
                subId                                   // Subscription ID
            )
            
            if (messageUri != null) {
                SmashLogger.verbose("MmsDownloadReceiver: persisted MMS to provider: $messageUri")
                
                // Update the date to local time and mark as read=0
                val values = android.content.ContentValues().apply {
                    put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000L)
                    put(Telephony.Mms.READ, 0)
                    put(Telephony.Mms.SEEN, 0)
                }
                context.contentResolver.update(messageUri, values, null, null)
            } else {
                SmashLogger.error("MmsDownloadReceiver: PduPersister.persist returned null")
            }
        } catch (e: Exception) {
            SmashLogger.error("MmsDownloadReceiver: failed to persist MMS to provider", e)
        }
    }
}
